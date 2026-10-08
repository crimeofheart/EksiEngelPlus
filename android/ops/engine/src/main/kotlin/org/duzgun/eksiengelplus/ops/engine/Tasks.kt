package org.duzgun.eksiengelplus.ops.engine

import org.duzgun.eksiengelplus.eksi.client.FollowEndpoint
import org.duzgun.eksiengelplus.eksi.client.RelationClient
import org.duzgun.eksiengelplus.eksi.client.RelationResult
import org.duzgun.eksiengelplus.eksi.client.ScrapeClient
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.model.toEksiSlug

/** A user to act on. The id may need resolving from the nick first. */
data class Target(val nick: String, val id: Long?)

/**
 * What every paginated scrape does between pages.
 *
 * Collecting targets is the half of a run that happens before TargetRunner sees
 * anything, and it used to be a straight walk: one read permit taken for the
 * whole thing, then as many requests as the author had pages, with no
 * ensureActive() anywhere. Duraklat and Durdur post to the command bus, which
 * only ensureActive() and the pacing waits read, so during collection both
 * buttons did nothing at all -- on a large account, for minutes -- and İşlem
 * durumu had no counts to show because none existed yet. The run was
 * indistinguishable from a hung one, and the only way out was to wait.
 *
 * Passed to ScrapeClient's pagination as onPage, so all three of those become
 * true per page instead of per operation:
 *
 *  - the signal is seen between pages, never mid-page, so a cancelled walk still
 *    leaves the response it already paid for consistent;
 *  - the page is paced, which is what the read limit was always for -- one
 *    permit for 120 requests paced nothing;
 *  - the count so far reaches the screen, so collection reads as "toplanıyor"
 *    rather than 0 / 0.
 *
 * Deliberately in this order. A run being stopped must not first wait out a
 * read permit it will never use.
 */
suspend fun OperationContext.collectPage(found: Int) {
    ensureActive()
    publishCollecting(found)
    awaitReadPermit()
}

/**
 * The loop every task shares.
 *
 * Each source differs only in how it resolves its target set; applying the
 * relation is identical. The extension repeats this loop per branch in
 * background.js (`:663-1091`), which is why its retry and cooldown behaviour
 * drifted between them.
 */
class TargetRunner(
    private val relations: RelationClient,
    private val scrape: ScrapeClient,
    private val retry: RetryPolicy = RetryPolicy(),
) {
    companion object {
        /**
         * Relation-list pages a follow may read per pending target before
         * lifting blind becomes the cheaper way to clear restrictions. See
         * restrictionsToLift for the arithmetic.
         */
        const val LOOKUP_PAGES_PER_TARGET = 10
    }

    suspend fun applyToAll(
        ctx: OperationContext,
        targets: List<Target>,
        checkpointEvery: Int = 5,
    ): OperationOutcome {
        var cursor = ctx.startCursor
        val mode = ctx.request.mode
        val targetType = ctx.request.targetType

        // Nothing to do costs nothing. An entry with no favouriters, an author
        // with no followers: the run is over before the rate limit is relevant,
        // and it must not check in with the pacer or write a checkpoint on the
        // way past.
        if (targets.isEmpty()) return OperationOutcome.COMPLETED

        /*
         * The size, before the first action rather than after it.
         *
         * Progress was published only once a target had been dealt with, so a
         * run waiting out its first cooldown read "0 / 0 · API limiti
         * bekleniyor" -- indistinguishable from a run against nobody, and the
         * reason a genuine 37-follower run looked like a minute wasted on an
         * empty one.
         *
         * Also before the restriction lookup below, so the screen names the
         * run's own size while that runs rather than still showing collection.
         */
        ctx.publishProgress(
            OperationProgress(cursor.processed, targets.size, cursor.successful, cursor.failed),
        )

        /*
         * What a follow has to undo first.
         *
         * Ekşi holds block (r=m), mute (r=u) and follow (r=b) as independent
         * relations, so following an account you blocked leaves the block in
         * place: the follow reports success and the account stays hidden.
         * Following is a request to see someone, which cannot be true while a
         * restriction says otherwise.
         *
         * Read once for the run and only when a follow is what the run does, so
         * an ordinary block run pays nothing for it. The lists are the
         * authority; the synced copies in Room go stale the moment a block
         * happens on another device.
         *
         * Adding the follow only. TAKIPTEN_CIKAR is also TargetType.FOLLOW, and
         * un-following someone is no reason to unblock them.
         */
        val restricted =
            if (targetType == TargetType.FOLLOW && mode == org.duzgun.eksiengelplus.model.BanMode.BAN) {
                restrictionsToLift(ctx, pending = targets.size - cursor.index)
            } else {
                null
            }

        var i = cursor.index
        /*
         * The whole loop, not just ensureActive().
         *
         * Durdur and Duraklat now also reach the run from inside a rate-limit
         * wait, which happens down in performWithRetry -- outside the guard
         * this used to be. A signal raised there escaped the task and surfaced
         * as "İşlem başarısız", so pausing during a cooldown looked like a
         * crash. Every signal parks the run wherever it stands.
         */
        try {
        while (i < targets.size) {
            ctx.ensureActive()

            val target = targets[i]

            // Filtered out: counted as processed so the cursor advances and a
            // resume does not re-examine it, but never acted on.
            if (!ctx.allows(target.nick)) {
                cursor = cursor.copy(index = i + 1, processed = cursor.processed + 1)
                i++
                continue
            }
            val id = target.id?.takeIf { it > 0 } ?: resolveId(ctx, target.nick)?.takeIf { it > 0 }
            if (id == null) {
                cursor = cursor.copy(processed = cursor.processed + 1, failed = cursor.failed + 1)
                i++
                continue
            }
            // Attempted, which is exactly what the extension reports: it builds
            // author_list from the planned list minus everyone whose id came back
            // 0 -- the same set that reaches here.
            ctx.recordTarget(target.nick, id)

            // Mirrored by background.js followAfterClearing: an unsuccessful
            // lift cannot be hidden by a successful follow of a still-hidden user.
            var preparation: Applied = Applied.Ok
            if (restricted != null) {
                val key = target.nick.toEksiSlug()
                if (restricted.isBlocked(key)) {
                    preparation = performWithRetry(ctx, org.duzgun.eksiengelplus.model.BanMode.UNDOBAN, TargetType.USER, id)
                }
                if (preparation == Applied.Ok && restricted.isMuted(key)) {
                    preparation = performWithRetry(ctx, org.duzgun.eksiengelplus.model.BanMode.UNDOBAN, TargetType.MUTE, id)
                }
            }

            when (if (preparation == Applied.Ok) performWithRetry(ctx, mode, targetType, id) else preparation) {
                is Applied.Ok ->
                    cursor = cursor.copy(
                        processed = cursor.processed + 1,
                        successful = cursor.successful + 1,
                    )
                is Applied.Failed ->
                    cursor = cursor.copy(
                        processed = cursor.processed + 1,
                        failed = cursor.failed + 1,
                    )
                // A lost session cannot be recovered without a human, because
                // /giris is behind Turnstile. Park rather than burn the remaining
                // budget failing every subsequent target.
                is Applied.SessionGone -> {
                    ctx.checkpoint(cursor.copy(index = i))
                    return OperationOutcome.PAUSED_AUTH
                }
            }

            i++
            if (i % checkpointEvery == 0) ctx.checkpoint(cursor.copy(index = i))
            ctx.publishProgress(
                OperationProgress(cursor.processed, targets.size, cursor.successful, cursor.failed),
            )
        }
        } catch (e: PauseSignal) {
            return park(ctx, cursor, i, OperationOutcome.PAUSED)
        } catch (e: StopSignal) {
            return park(ctx, cursor, i, OperationOutcome.STOPPED)
        } catch (e: BudgetExhaustedSignal) {
            return park(ctx, cursor, i, OperationOutcome.PAUSED_BUDGET)
        }

        ctx.checkpoint(cursor.copy(index = targets.size))
        return OperationOutcome.COMPLETED
    }

    /**
     * Saves where the run stopped and reports why.
     *
     * The checkpoint can itself raise StopSignal: RoomOperationContext throws
     * one when the row is gone, which is how a cancel reaches a live run. A
     * signal thrown while handling a signal would leave the task as a failure,
     * so it is absorbed here -- there is nothing to save when the row the state
     * would go into is exactly what was deleted.
     */
    private suspend fun park(
        ctx: OperationContext,
        cursor: OperationCursor,
        at: Int,
        outcome: OperationOutcome,
    ): OperationOutcome {
        try {
            ctx.checkpoint(cursor.copy(index = at))
        } catch (e: StopSignal) {
            return OperationOutcome.STOPPED
        }
        return outcome
    }

    /**
     * Two relations per target, in order, second only if the first landed.
     *
     * Conversion adds the replacement restriction before removing the source,
     * mirrored by programController.js. A failed removal can leave both
     * restrictions; a failed addition must never remove existing protection.
     */
    suspend fun applyPairToAll(
        ctx: OperationContext,
        targets: List<Target>,
        first: Pair<org.duzgun.eksiengelplus.model.BanMode, TargetType>,
        second: Pair<org.duzgun.eksiengelplus.model.BanMode, TargetType>,
        checkpointEvery: Int = 5,
    ): OperationOutcome {
        if (targets.isEmpty()) return OperationOutcome.COMPLETED

        var cursor = ctx.startCursor
        var i = cursor.index

        // The size up front, for the reason applyToAll spells out.
        ctx.publishProgress(
            OperationProgress(cursor.processed, targets.size, cursor.successful, cursor.failed),
        )

        // Guarded as a whole, for the reason applyToAll spells out.
        try {
        while (i < targets.size) {
            ctx.ensureActive()

            val target = targets[i]
            if (!ctx.allows(target.nick)) {
                cursor = cursor.copy(index = i + 1, processed = cursor.processed + 1)
                i++
                continue
            }

            val id = target.id?.takeIf { it > 0 } ?: resolveId(ctx, target.nick)?.takeIf { it > 0 }
            if (id == null) {
                cursor = cursor.copy(processed = cursor.processed + 1, failed = cursor.failed + 1)
                i++
                continue
            }
            // Attempted, which is exactly what the extension reports: it builds
            // author_list from the planned list minus everyone whose id came back
            // 0 -- the same set that reaches here.
            ctx.recordTarget(target.nick, id)

            when (performWithRetry(ctx, first.first, first.second, id)) {
                is Applied.SessionGone -> {
                    ctx.checkpoint(cursor.copy(index = i)); return OperationOutcome.PAUSED_AUTH
                }
                is Applied.Failed ->
                    cursor = cursor.copy(processed = cursor.processed + 1, failed = cursor.failed + 1)
                is Applied.Ok -> when (performWithRetry(ctx, second.first, second.second, id)) {
                    is Applied.SessionGone -> {
                        ctx.checkpoint(cursor.copy(index = i)); return OperationOutcome.PAUSED_AUTH
                    }
                    is Applied.Ok ->
                        cursor = cursor.copy(
                            processed = cursor.processed + 1,
                            successful = cursor.successful + 1,
                        )
                    is Applied.Failed ->
                        cursor = cursor.copy(processed = cursor.processed + 1, failed = cursor.failed + 1)
                }
            }

            i++
            if (i % checkpointEvery == 0) ctx.checkpoint(cursor.copy(index = i))
            ctx.publishProgress(
                OperationProgress(cursor.processed, targets.size, cursor.successful, cursor.failed),
            )
        }
        } catch (e: PauseSignal) {
            return park(ctx, cursor, i, OperationOutcome.PAUSED)
        } catch (e: StopSignal) {
            return park(ctx, cursor, i, OperationOutcome.STOPPED)
        } catch (e: BudgetExhaustedSignal) {
            return park(ctx, cursor, i, OperationOutcome.PAUSED_BUDGET)
        }

        ctx.checkpoint(cursor.copy(index = targets.size))
        return OperationOutcome.COMPLETED
    }

    private sealed interface Applied {
        data object Ok : Applied
        data object Failed : Applied
        data object SessionGone : Applied
    }

    /**
     * The blocked and muted nicks, slugged to match how targets are keyed.
     *
     * A null list was not read, and answers yes for everyone: lifting a relation
     * that is not there is a no-op the site accepts (`"result": true`, see
     * RelationClient.classify), so not knowing costs actions, never correctness.
     */
    private class Restrictions(private val blocked: Set<String>?, private val muted: Set<String>?) {
        fun isBlocked(key: String) = blocked?.contains(key) ?: true
        fun isMuted(key: String) = muted?.contains(key) ?: true
    }

    /**
     * Reads the blocked and muted lists, but only while that is the cheaper way
     * to know.
     *
     * Both lists used to be read in full before every follow, whatever its
     * size. Their length is the account's, not the run's: "yazarı takip et" on
     * an account with 5,000 blocks walked 200 pages to follow one person, and
     * the walk published its count as collection, so the screen read as if the
     * run had found thousands of targets.
     *
     * Not reading them costs two uncounted actions per target -- about ten
     * seconds of rate limit at 12 per 62s. A page costs one read, a quarter of
     * a second of pacing plus the round trip. So the walk gets
     * [LOOKUP_PAGES_PER_TARGET] pages per pending target, shared by both lists;
     * a list that does not end within what is left is not read, and everyone
     * is lifted blind on it instead. A small run on a large account lifts
     * blind at once; a large run reads the lists, as before.
     *
     * Reads, not mutations, so they take the read permit rather than the action
     * one. No publishCollecting: the targets are already collected, and this is
     * not more of them.
     */
    private suspend fun restrictionsToLift(ctx: OperationContext, pending: Int): Restrictions {
        var budget = pending.toLong() * LOOKUP_PAGES_PER_TARGET

        suspend fun walk(type: TargetType): Set<String>? {
            val nicks = HashSet<String>()
            var page = ScrapeClient.FIRST_PAGE
            while (true) {
                if (budget <= 0) return null
                budget--
                ctx.ensureActive()
                ctx.awaitReadPermit()
                val p = scrape.relationPage(type, page)
                p.nicks.mapTo(nicks) { it.toEksiSlug() }
                if (p.isLast) return nicks
                page++
            }
        }

        val blocked = walk(TargetType.USER)
        val muted = walk(TargetType.MUTE)
        if (blocked == null || muted == null) {
            ctx.log("restriction lookup over budget for $pending targets; lifting blind")
        }
        return Restrictions(blocked, muted)
    }

    private suspend fun performWithRetry(
        ctx: OperationContext,
        mode: org.duzgun.eksiengelplus.model.BanMode,
        targetType: TargetType,
        id: Long,
    ): Applied {
        var attempt = 1
        while (true) {
            ctx.awaitActionPermit()
            val result = relations.perform(mode, targetType, id)

            when (val decision = retry.decide(result, attempt)) {
                is RetryPolicy.Decision.Done -> return Applied.Ok
                is RetryPolicy.Decision.RetryAfter -> {
                    // Hand the delay to the pacer so every caller waits, not just
                    // this one. That is the whole reason the client returns the
                    // delay instead of sleeping on it.
                    ctx.penalizeRateLimit(decision.seconds)
                    attempt++
                }
                is RetryPolicy.Decision.GiveUp -> {
                    // Keep the actual status/code; unknown codes are not evidence
                    // that this target has blocked the authenticated user.
                    ctx.log("relation failed: id=$id mode=$mode type=$targetType result=$result")
                    return if (result is RelationResult.SessionExpired) Applied.SessionGone
                    else Applied.Failed
                }
            }
        }
    }

    private suspend fun resolveId(ctx: OperationContext, nick: String): Long? = try {
        ctx.awaitReadPermit()
        scrape.authorProfile(nick).authorId
    } catch (e: PauseSignal) {
        // The permit wait raises these, and they are RuntimeExceptions, so the
        // catch-all below was swallowing the user's own Durdur and charging the
        // target as unresolvable.
        throw e
    } catch (e: StopSignal) {
        throw e
    } catch (e: BudgetExhaustedSignal) {
        throw e
    } catch (e: Exception) {
        ctx.log("could not resolve id for $nick: ${e.message}")
        null
    }
}

/**
 * Pacer feedback lives on the context so tasks never hold the pacer directly --
 * a task that could reset the bucket could also defeat it.
 */
suspend fun OperationContext.penalizeRateLimit(seconds: Int) {
    (this as? RateLimitAware)?.onRateLimited(seconds)
}

interface RateLimitAware {
    suspend fun onRateLimited(retryAfterSeconds: Int)
}

// ---------------------------------------------------------------- the six sources

/** ban_source 1. One user, straight from a menu tap. */
class SingleActionTask(private val runner: TargetRunner) : OperationTask {
    override val source = BanSource.SINGLE
    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val nick = ctx.request.authorNick?.toEksiSlug() ?: return OperationOutcome.COMPLETED
        return runner.applyToAll(ctx, listOf(Target(nick, ctx.request.authorId)), checkpointEvery = 1)
    }
}

/**
 * ban_source 4. An explicit list the user pasted or imported.
 *
 * The nicks arrive in the request, resolved from `author_list` when the run was
 * enqueued -- deliberately not read here. The request is serialised into the
 * checkpoint and TargetRunner checkpoints by index, so a task that re-read the
 * table on resume could pick up at the wrong position in a list the user had
 * edited in the meantime.
 */
class ListActionTask(private val runner: TargetRunner) : OperationTask {
    override val source = BanSource.LIST
    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val targets = ctx.request.nicks.map { Target(it.toEksiSlug(), null) }
        val second = ctx.request.thenApplyTo
            ?: return runner.applyToAll(ctx, targets)
        return runner.applyPairToAll(
            ctx,
            targets,
            first = ctx.request.mode to ctx.request.targetType,
            second = org.duzgun.eksiengelplus.model.BanMode.BAN to second,
        )
    }
}

/**
 * ban_source 2. Everyone who favourited an entry.
 *
 * Two endpoints, because novice favourites live separately. The novice pass is
 * gated on enableNoobBan, matching scrapingHandler.js:186. Neither returns ids,
 * so each nick costs a profile fetch -- which is why resolution is lazy and
 * paced rather than done up front.
 */
class FavActionTask(
    private val runner: TargetRunner,
    private val scrape: ScrapeClient,
    private val includeNovices: () -> Boolean,
) : OperationTask {
    override val source = BanSource.FAV

    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val entryId = ctx.request.entryId ?: return OperationOutcome.COMPLETED
        // Two requests rather than a walk, but the same contract as the paged
        // sources: a stop between them is honoured, and the novice pass is not
        // spent on a run the user has already abandoned.
        ctx.collectPage(0)
        val nicks = LinkedHashSet(scrape.favouriters(entryId))
        if (includeNovices()) {
            ctx.collectPage(nicks.size)
            nicks += scrape.noviceFavouriters(entryId)
        }
        return runner.applyToAll(ctx, nicks.map { Target(it, null) })
    }
}

/** ban_source 3. Everyone following a given author. */
class FollowActionTask(
    private val runner: TargetRunner,
    private val scrape: ScrapeClient,
) : OperationTask {
    override val source = BanSource.FOLLOW

    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val nick = ctx.request.authorNick?.toEksiSlug() ?: return OperationOutcome.COMPLETED
        val followers = scrape.allFollow(FollowEndpoint.FOLLOWER, nick) { _, found ->
            ctx.collectPage(found)
        }
        return runner.applyToAll(
            ctx,
            followers.map { Target(it.nick.value.toEksiSlug(), it.id) },
        )
    }
}

/**
 * ban_source 15. Everyone a given author follows.
 *
 * The mirror of [FollowActionTask] -- same walk, the other endpoint. Offered
 * only as a follow, because there was never a block or mute of this audience to
 * keep parity with.
 */
class FolloweesActionTask(
    private val runner: TargetRunner,
    private val scrape: ScrapeClient,
) : OperationTask {
    override val source = BanSource.FOLLOWEES

    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val nick = ctx.request.authorNick?.toEksiSlug() ?: return OperationOutcome.COMPLETED
        val followees = scrape.allFollow(FollowEndpoint.FOLLOWING, nick) { _, found ->
            ctx.collectPage(found)
        }
        return runner.applyToAll(
            ctx,
            followees.map { Target(it.nick.value.toEksiSlug(), it.id) },
        )
    }
}

/**
 * ban_source 6. Everyone who posted in a title.
 *
 * De-duplicated: a prolific author appears on many pages but must be acted on
 * once.
 */
class TitleActionTask(
    private val runner: TargetRunner,
    private val scrape: ScrapeClient,
) : OperationTask {
    override val source = BanSource.TITLE

    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val slug = ctx.request.titleSlug ?: return OperationOutcome.COMPLETED
        val id = ctx.request.titleId ?: return OperationOutcome.COMPLETED
        val authors = scrape.allTopicAuthors(slug, id, lastDayOnly = ctx.request.lastDayOnly) { _, found ->
            // Paced per page rather than a fixed sleep, so a long thread does not
            // outrun the read budget -- and interruptible per page, so a title
            // deep enough to take minutes to walk can still be stopped.
            ctx.collectPage(found)
        }
        return runner.applyToAll(ctx, authors.map { Target(it.nick, it.authorId) })
    }
}

/**
 * ban_source 5. Unblock everyone.
 *
 * Destructive and irreversible at scale, so it checkpoints every unit: a crash
 * halfway must not leave the user unable to tell who was already unblocked.
 */
class UndoBanAllTask(
    private val runner: TargetRunner,
    private val scrape: ScrapeClient,
) : OperationTask {
    override val source = BanSource.UNDOBANALL

    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val page = scrape.allRelations(TargetType.USER) { _, found -> ctx.collectPage(found) }
        val targets = page.nicks.zip(page.ids) { nick, id -> Target(nick.toEksiSlug(), id) }
        return runner.applyToAll(ctx, targets, checkpointEvery = 1)
    }
}


/**
 * The sources that act on a relation list the account actually has.
 *
 * The list is fetched when the run starts, not read from our synced copy. These
 * are operations on the user's real blocked and muted lists, so requiring a
 * manual refresh first -- and refusing with "list is empty" when none had
 * happened -- described our cache rather than their account.
 *
 * ban_source 7, 9, 12 and 13 differ only in which list they read and what the
 * backend should call them, so they share one loop rather than each growing a
 * copy the way background.js did.
 */
class RelationListTask(
    override val source: BanSource,
    /**
     * Which relation list is walked, and readable because it is part of what the
     * task is. The wiring that chooses it was pinned to USER for every
     * date-based run, so a test has to be able to see the choice without
     * standing up an HTTP server to watch the query string.
     */
    val listOf: TargetType,
    private val runner: TargetRunner,
    private val scrape: ScrapeClient,
) : OperationTask {
    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val page = scrape.allRelations(listOf) { _, found -> ctx.collectPage(found) }
        val targets = page.nicks.zip(page.ids) { nick, id -> Target(nick.toEksiSlug(), id) }
        return runner.applyToAll(ctx, targets, checkpointEvery = 1)
    }
}

/**
 * ban_source 8. Move everyone blocked into muted instead.
 *
 * Two relations per user, because Ekşi models them separately: unblock, then
 * mute. A user left half-migrated would be in neither state, so the mute only
 * follows a successful unblock.
 */
class MigrateBlockedToMutedTask(
    private val runner: TargetRunner,
    private val scrape: ScrapeClient,
) : OperationTask {
    override val source = BanSource.MIGRATE_BLOCKED_TO_MUTED
    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val page = scrape.allRelations(TargetType.USER) { _, found -> ctx.collectPage(found) }
        return runner.applyPairToAll(
            ctx,
            page.nicks.zip(page.ids) { nick, id -> Target(nick.toEksiSlug(), id) },
            first = org.duzgun.eksiengelplus.model.BanMode.BAN to TargetType.MUTE,
            second = org.duzgun.eksiengelplus.model.BanMode.UNDOBAN to TargetType.USER,
        )
    }
}


/** Mirrored by programController.js: block first; unmute only on confirmed success. */
class BlockMutedUsersTask(
    private val runner: TargetRunner,
    private val scrape: ScrapeClient,
) : OperationTask {
    override val source = BanSource.BLOCK_MUTED_USERS
    override suspend fun run(ctx: OperationContext): OperationOutcome {
        val page = scrape.allRelations(TargetType.MUTE) { _, found -> ctx.collectPage(found) }
        return runner.applyPairToAll(
            ctx,
            page.nicks.zip(page.ids) { nick, id -> Target(nick.toEksiSlug(), id) },
            first = org.duzgun.eksiengelplus.model.BanMode.BAN to TargetType.USER,
            second = org.duzgun.eksiengelplus.model.BanMode.UNDOBAN to TargetType.MUTE,
        )
    }
}
