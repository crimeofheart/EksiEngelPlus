package org.duzgun.eksiengelplus.ops.engine

import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType

/** Raised at a checkpoint when a pause was requested. Resumable. */
class PauseSignal : RuntimeException("pause requested")

/** Raised at a checkpoint when a stop was requested. Not resumable. */
class StopSignal : RuntimeException("stop requested")

/** Raised when the foreground-service time budget runs out mid-operation. */
class BudgetExhaustedSignal : RuntimeException("foreground budget exhausted")

/** What the user asked for. Mirrors the payload script.js:30-45 posts. */
@kotlinx.serialization.Serializable
data class OperationRequest(
    val source: BanSource,
    val mode: BanMode,
    val targetType: TargetType = TargetType.USER,
    /** SINGLE: the author. FOLLOW: whose followers. */
    val authorNick: String? = null,
    val authorId: Long? = null,
    /** FAV: which entry's favouriters. */
    val entryId: Long? = null,
    /** TITLE: which title, and how far back. */
    val titleSlug: String? = null,
    val titleId: Long? = null,
    val lastDayOnly: Boolean = false,
    /** LIST: explicit nicks. */
    val nicks: List<String> = emptyList(),
)

/** Where an operation got to, so a resumed run does not redo work. */
@kotlinx.serialization.Serializable
data class OperationCursor(
    val page: Int = 1,
    val index: Int = 0,
    val processed: Int = 0,
    val successful: Int = 0,
    val failed: Int = 0,
)

data class OperationProgress(
    val processed: Int,
    val total: Int,
    val successful: Int,
    val failed: Int,
)

enum class OperationOutcome { COMPLETED, PAUSED, STOPPED, PAUSED_AUTH, PAUSED_BUDGET }

/**
 * Everything a task is allowed to touch.
 *
 * Narrow on purpose. A task can pace, checkpoint, report progress and record a
 * result; it cannot reach a screen, schedule work, or post a notification. That
 * is what keeps the extension's failure mode -- operations that stall because a
 * UI surface went away (background.js:86-164) -- structurally impossible.
 */
interface OperationContext {

    val request: OperationRequest

    /** Where a resumed run should pick up. */
    val startCursor: OperationCursor

    /**
     * Cooperative pause/stop point. Throws PauseSignal, StopSignal or
     * BudgetExhaustedSignal. Called between units, never mid-mutation, so an
     * in-flight action is never abandoned half-done.
     */
    suspend fun ensureActive()

    /**
     * Persists the cursor together with whatever rows the unit produced, in one
     * transaction. Split writes are the one place a crash corrupts user-visible
     * state: a cursor ahead of its rows silently skips users, behind it
     * re-processes them.
     */
    suspend fun checkpoint(cursor: OperationCursor, effects: suspend () -> Unit = {})

    suspend fun publishProgress(progress: OperationProgress)

    /** Waits for a pacer permit before a mutation. */
    suspend fun awaitActionPermit()

    /** Waits for a read permit before a scrape. */
    suspend fun awaitReadPermit()

    suspend fun log(message: String)
}

/** One user-visible operation. Every implementation is a loop over a cursor. */
interface OperationTask {
    val source: BanSource
    suspend fun run(ctx: OperationContext): OperationOutcome
}
