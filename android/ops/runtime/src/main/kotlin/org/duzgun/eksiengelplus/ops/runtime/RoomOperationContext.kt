package org.duzgun.eksiengelplus.ops.runtime

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.ops.engine.BudgetExhaustedSignal
import org.duzgun.eksiengelplus.ops.engine.OperationContext
import org.duzgun.eksiengelplus.ops.engine.OperationCursor
import org.duzgun.eksiengelplus.ops.engine.OperationProgress
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.duzgun.eksiengelplus.ops.engine.PauseSignal
import org.duzgun.eksiengelplus.ops.engine.RateLimitAware
import org.duzgun.eksiengelplus.ops.engine.StopSignal
import org.duzgun.eksiengelplus.ops.engine.ActionPacer
import org.duzgun.eksiengelplus.ops.engine.ReadPacer

private val Json = Json { ignoreUnknownKeys = true }

/**
 * Tracks foreground-service time against a soft budget.
 *
 * Android 15 caps a dataSync foreground service at roughly six hours per rolling
 * twenty-four and then calls onTimeout(). At twelve actions a minute a
 * ten-thousand-user run needs about fourteen hours, so a run has to span
 * sessions. The soft budget sits under the platform cap so the checkpoint is
 * ours to schedule rather than the system's to force.
 *
 * Time counts only while the foreground service holds the process. With a
 * visible activity the operation runs unconstrained, so leaving the app open
 * genuinely finishes sooner -- which is worth telling the user.
 */
class ForegroundBudget(
    private val softBudgetMs: Long = DEFAULT_SOFT_BUDGET_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Five hours, comfortably under the ~6h platform cap. */
        const val DEFAULT_SOFT_BUDGET_MS = 5L * 60 * 60 * 1000
    }

    private var startedAt: Long? = null
    private var consumedMs: Long = 0

    fun resume(alreadyConsumedMs: Long) {
        consumedMs = alreadyConsumedMs
        startedAt = clock()
    }

    /** Called when a visible activity takes over; stops billing. */
    fun releaseForeground() {
        startedAt?.let { consumedMs += clock() - it }
        startedAt = null
    }

    fun consumedMs(): Long = consumedMs + (startedAt?.let { clock() - it } ?: 0)

    fun isExhausted(): Boolean = consumedMs() >= softBudgetMs

    fun remainingMs(): Long = (softBudgetMs - consumedMs()).coerceAtLeast(0)
}

/**
 * The OperationContext a real run uses.
 *
 * The important part is checkpoint(): the cursor and the rows the unit produced
 * are written in ONE transaction. Writing them separately is the single place a
 * crash corrupts user-visible state -- a cursor ahead of its rows silently skips
 * users, one behind re-processes them, and neither is visible until someone
 * notices the wrong people are blocked.
 */
class RoomOperationContext(
    private val operationId: String,
    override val request: OperationRequest,
    override val startCursor: OperationCursor,
    private val db: EksiDatabase,
    private val commands: OperationCommandBus,
    private val budget: ForegroundBudget,
    private val actionPacer: ActionPacer,
    private val readPacer: ReadPacer,
    private val onProgress: suspend (OperationProgress) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : OperationContext, RateLimitAware {

    private val logBuffer = ArrayDeque<String>()

    override suspend fun ensureActive() {
        when (commands.peek(operationId)) {
            OperationCommand.PAUSE -> throw PauseSignal()
            OperationCommand.STOP -> throw StopSignal()
            null -> Unit
        }
        // Checked here rather than on a timer so the operation always stops
        // between units, never mid-mutation.
        if (budget.isExhausted()) throw BudgetExhaustedSignal()
    }

    override suspend fun checkpoint(cursor: OperationCursor, effects: suspend () -> Unit) {
        db.withTransaction {
            effects()
            db.checkpoints().upsert(
                OperationCheckpointEntity(
                    operationId = operationId,
                    type = request.source.name,
                    state = OperationState.RUNNING.name,
                    cursorJson = Json.encodeToString(OperationCursor.serializer(), cursor),
                    processed = cursor.processed,
                    total = 0,
                    successful = cursor.successful,
                    failed = cursor.failed,
                    startedAt = 0,
                    updatedAt = clock(),
                    workRequestId = null,
                    fgsMillisUsed = budget.consumedMs(),
                ),
            )
        }
    }

    override suspend fun publishProgress(progress: OperationProgress) = onProgress(progress)

    override suspend fun awaitActionPermit() = actionPacer.acquire()

    override suspend fun awaitReadPermit() = readPacer.acquire()

    override suspend fun onRateLimited(retryAfterSeconds: Int) =
        actionPacer.penalize(retryAfterSeconds)

    override suspend fun log(message: String) {
        // Ring buffer: the telemetry log field is capped at 10,000 characters
        // server-side (api/serializers.py:191), so unbounded retention would only
        // be discarded later.
        logBuffer.addLast(message)
        while (logBuffer.size > 200) logBuffer.removeFirst()
    }

    fun logs(): List<String> = logBuffer.toList()
}
