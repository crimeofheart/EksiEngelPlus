package org.duzgun.eksiengelplus.ops.engine

/**
 * Records what a task did without touching Room, WorkManager or a screen.
 *
 * That this is a ~50-line fake is itself the point: OperationContext is narrow
 * enough that a task cannot reach anything it should not.
 */
class FakeContext(
    override val request: OperationRequest,
    override val startCursor: OperationCursor = OperationCursor(),
    /** Fires the given signal once, before the Nth ensureActive() call. */
    private val signalAt: Pair<Int, RuntimeException>? = null,
) : OperationContext, RateLimitAware {

    val checkpoints = mutableListOf<OperationCursor>()
    val progress = mutableListOf<OperationProgress>()
    val logs = mutableListOf<String>()
    var actionPermits = 0

    /** Raised from inside the permit wait, the way a real Durdur now arrives. */
    var permitSignal: (() -> Throwable)? = null
    var readPermits = 0
    val penalties = mutableListOf<Int>()
    private var activeCalls = 0

    override suspend fun ensureActive() {
        activeCalls++
        signalAt?.let { (n, e) -> if (activeCalls == n) throw e }
    }

    override suspend fun checkpoint(cursor: OperationCursor, effects: suspend () -> Unit) {
        effects()
        checkpoints += cursor
    }

    override suspend fun publishProgress(progress: OperationProgress) { this.progress += progress }
    override suspend fun awaitActionPermit() { actionPermits++; permitSignal?.let { throw it() } }
    override suspend fun awaitReadPermit() { readPermits++ }
    override suspend fun log(message: String) { logs += message }
    override suspend fun onRateLimited(retryAfterSeconds: Int) { penalties += retryAfterSeconds }

    val lastCheckpoint: OperationCursor? get() = checkpoints.lastOrNull()
}
