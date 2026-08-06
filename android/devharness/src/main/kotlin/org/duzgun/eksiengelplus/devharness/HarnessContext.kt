package org.duzgun.eksiengelplus.devharness

import org.duzgun.eksiengelplus.ops.engine.ActionPacer
import org.duzgun.eksiengelplus.ops.engine.OperationContext
import org.duzgun.eksiengelplus.ops.engine.OperationCursor
import org.duzgun.eksiengelplus.ops.engine.OperationProgress
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.RateLimitAware
import org.duzgun.eksiengelplus.ops.engine.ReadPacer

/**
 * An OperationContext that logs to the harness instead of writing to Room.
 *
 * Everything above it -- TargetRunner, RelationClient, the pacer, the retry
 * policy -- is production code. Only persistence is swapped, so a failure here
 * is a failure in what ships.
 */
class HarnessContext(
    override val request: OperationRequest,
    private val actionPacer: ActionPacer,
    private val readPacer: ReadPacer,
    private val emit: (String) -> Unit,
) : OperationContext, RateLimitAware {

    override val startCursor = OperationCursor()
    val checkpoints = mutableListOf<OperationCursor>()

    override suspend fun ensureActive() = Unit

    override suspend fun checkpoint(cursor: OperationCursor, effects: suspend () -> Unit) {
        effects()
        checkpoints += cursor
        emit("  checkpoint: $cursor")
    }

    override suspend fun publishProgress(progress: OperationProgress) {
        emit("  progress: ${progress.processed}/${progress.total} ok=${progress.successful} fail=${progress.failed}")
    }

    override suspend fun awaitActionPermit() = actionPacer.acquire()
    override suspend fun awaitReadPermit() = readPacer.acquire()
    override suspend fun onRateLimited(retryAfterSeconds: Int) {
        emit("  429 -> pacer penalised ${retryAfterSeconds}s (applies to every caller)")
        actionPacer.penalize(retryAfterSeconds)
    }
    override suspend fun log(message: String) { emit("  $message") }
}
