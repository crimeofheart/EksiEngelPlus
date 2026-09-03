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
         */
        ctx.publishProgress(
            OperationProgress(cursor.processed, targets.size, cursor.successful, cursor.failed),
        )

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
            val id = target.id ?: resolveId(ctx, target.nick)
            if (id == null) {
                cursor = cursor.copy(processed = cursor.processed + 1, failed = cursor.failed + 1)
                i++
                continue
            }
            // Attempted, which is exactly what the extension reports: it builds
            // author_list from the planned list minus everyone whose id came back
            // 0 -- the same set that reaches here.
            ctx.recordTarget(target.nick, id)

            when (val outcome = performWithRetry(ctx, mode, targetType, id)) {
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
     * Migrating a blocked user to muted is not one action with a different
     * argument: it is an unblock and then a mute, and doing the second to
     * someone still blocked would leave them in both states.
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

            val id = target.id ?: resolveId(ctx, target.nick)
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
                is RetryPolicy.Decision.GiveUp ->
                    return if (result is RelationResult.SessionExpired) Applied.SessionGone
                    else Applied.Failed
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
        ctx.awaitReadPermit()
        val nicks = LinkedHashSet(scrape.favouriters(entryId))
        if (includeNovices()) {
            ctx.awaitReadPermit()
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
        ctx.awaitReadPermit()
        val followers = scrape.allFollow(FollowEndpoint.FOLLOWER, nick)
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
        ctx.awaitReadPermit()
        val followees = scrape.allFollow(FollowEndpoint.FOLLOWING, nick)
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
        ctx.awaitReadPermit()
        val authors = scrape.allTopicAuthors(slug, id, lastDayOnly = ctx.request.lastDayOnly) {
            // Paced per page rather than a fixed sleep, so a long thread does not
            // outrun the read budget.
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
        ctx.awaitReadPermit()
        val page = scrape.allRelations(TargetType.USER)
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
        ctx.awaitReadPermit()
        val page = scrape.allRelations(listOf)
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
        ctx.awaitReadPermit()
        val page = scrape.allRelations(TargetType.USER)
        return runner.applyPairToAll(
            ctx,
            page.nicks.zip(page.ids) { nick, id -> Target(nick.toEksiSlug(), id) },
            first = org.duzgun.eksiengelplus.model.BanMode.UNDOBAN to TargetType.USER,
            second = org.duzgun.eksiengelplus.model.BanMode.BAN to TargetType.MUTE,
        )
    }
}
