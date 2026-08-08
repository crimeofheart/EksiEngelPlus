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

        var i = cursor.index
        while (i < targets.size) {
            try {
                ctx.ensureActive()
            } catch (e: PauseSignal) {
                ctx.checkpoint(cursor.copy(index = i))
                return OperationOutcome.PAUSED
            } catch (e: StopSignal) {
                ctx.checkpoint(cursor.copy(index = i))
                return OperationOutcome.STOPPED
            } catch (e: BudgetExhaustedSignal) {
                ctx.checkpoint(cursor.copy(index = i))
                return OperationOutcome.PAUSED_BUDGET
            }

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
    override suspend fun run(ctx: OperationContext): OperationOutcome =
        runner.applyToAll(ctx, ctx.request.nicks.map { Target(it.toEksiSlug(), null) })
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
