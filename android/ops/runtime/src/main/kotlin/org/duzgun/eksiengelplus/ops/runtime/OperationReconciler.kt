package org.duzgun.eksiengelplus.ops.runtime

import androidx.work.WorkInfo
import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.OperationState

private val Json = Json { ignoreUnknownKeys = true }

/**
 * Startup crash recovery.
 *
 * A checkpoint left in RUNNING means the process died mid-operation: nothing
 * transitions it, because whatever would have done so is gone. WorkManager knows
 * whether any work is actually live, so the two together distinguish "still
 * running" from "died and never cleaned up".
 *
 * Ports background.js:23-59, which does this by hand because the extension has
 * no equivalent of WorkManager's bookkeeping.
 *
 * Deliberately does NOT auto-resume. The user may have force-quit on purpose, or
 * a run may have been killed for a reason they would want to know about, so an
 * interrupted operation is surfaced and offered rather than silently restarted.
 */
@Singleton
class OperationReconciler @Inject constructor(
    private val db: EksiDatabase,
    private val workManager: WorkManager,
    private val commands: OperationCommandBus,
) {

    private companion object {
        /**
         * How long a live worker gets to honour Durdur before it is cut off.
         *
         * Long enough for a cooperative stop: the command bus is polled four
         * times a second inside a pacing wait, and the worst case is one action
         * already in flight. Short enough that a run which is never going to
         * answer does not keep the user waiting to find that out.
         */
        const val STOP_GRACE_MS = 8_000L
        const val STOP_POLL_MS = 400L
    }

    suspend fun reconcile(): List<String> {
        val stale = mutableListOf<String>()

        /*
         * An IDLE row is a run that was scheduled and never started.
         *
         * The row is written at enqueue so the worker can read its request back;
         * if the work never ran, nothing will ever clear it, and it lingers as an
         * operation at 0/0 that cannot be resumed or stopped because there is
         * nothing behind it. Deleted rather than marked: there is no state worth
         * keeping in a run that never began.
         */
        for (cp in db.checkpoints().withState(OperationState.IDLE.name)) {
            if (isWorkLive(cp.operationId)) continue
            db.checkpoints().remove(cp.operationId)
        }

        for (cp in db.checkpoints().withState(OperationState.RUNNING.name)) {
            if (isWorkLive(cp.operationId)) continue
            db.checkpoints().upsert(
                cp.copy(
                    state = OperationState.INTERRUPTED.name,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            stale += cp.operationId
        }

        drainQueue()

        return stale
    }

    /**
     * Starts whatever is waiting, if nothing is running.
     *
     * The queue was drained only when a run reached a terminal state, so a queue
     * that outlived the process -- the app killed, or simply reinstalled -- had
     * nothing left to trigger it and sat there indefinitely. Startup is exactly
     * the moment to check, and so is the moment a dead run is cleared away.
     */
    private suspend fun drainQueue() {
        if (db.checkpoints().liveCount() != 0) return
        val next = db.queuedTasks().next() ?: return
        val request = runCatching {
            Json.decodeFromString(OperationRequest.serializer(), next.payloadJson)
        }.getOrNull() ?: return
        db.queuedTasks().remove(next.id)
        OperationWorker.startNow(
            workManager,
            db,
            java.util.UUID.randomUUID().toString(),
            request,
        )
    }

    /**
     * Whether *this* operation's work is still scheduled or executing.
     *
     * Asked per operation, by the tag the work carries. It used to be asked of
     * the unique work name, which answers a different question -- "is anything
     * live" -- and so let a single enqueued run vouch for every orphaned row in
     * the table. A checkpoint left RUNNING by a killed process then stayed
     * RUNNING through every reconcile, kept liveCount() above zero, and queued
     * every later request behind a run that had not existed for days. Pull to
     * refresh looked broken because it was: the loop it drives skipped the very
     * row it was there to fix.
     *
     * A tag rather than the stored work id, which is written at enqueue time and
     * so is null exactly when a crash makes the check matter.
     */
    private suspend fun isWorkLive(operationId: String): Boolean =
        // get() blocks on WorkManager's own database, and Durdur now asks this
        // from a tap: off the main thread, where reconcile() should also have
        // been asking it.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            workManager.getWorkInfosByTag(OperationWorker.tagFor(operationId)).get()
                .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }

    /** Operations the user could pick up again. */
    suspend fun resumable(): List<String> =
        listOf(
            OperationState.PAUSED,
            OperationState.PAUSED_AUTH,
            OperationState.PAUSED_BUDGET,
            OperationState.PAUSED_NETWORK,
            OperationState.INTERRUPTED,
        ).flatMap { db.checkpoints().withState(it.name) }.map { it.operationId }

    /**
     * Runs parked because the session went away.
     *
     * Separated from resumable() because this is the one pause with an external
     * trigger: the user logging back in is exactly the condition that makes these
     * runnable again, and nothing else is watching for it. A row whose request
     * never made it to disk is skipped rather than guessed at.
     */
    suspend fun pausedForAuth(): List<PausedOperation> =
        db.checkpoints().withState(OperationState.PAUSED_AUTH.name).mapNotNull { cp ->
            val request = cp.requestJson
                ?.let { runCatching { Json.decodeFromString(OperationRequest.serializer(), it) }.getOrNull() }
                ?: return@mapNotNull null
            PausedOperation(cp.operationId, request)
        }

    /**
     * Hands the operation back to WorkManager, which picks up from the stored
     * cursor rather than starting over.
     *
     * Offered, never automatic: the reconciler deliberately does not restart work
     * on its own, and a login is not consent to resume a run the user may have
     * abandoned on purpose.
     */
    /**
     * Abandons a parked run for good.
     *
     * Deletes the checkpoint rather than marking it STOPPED. A terminal state
     * still leaves a row, and anything that sweeps checkpoints at startup can
     * bring it back -- which is exactly what happened: cancelling appeared to
     * work and the offer returned on the next launch. No row, nothing to offer.
     *
     * The work is cancelled too, since a deleted checkpoint would otherwise let
     * scheduled work start over from nothing.
     */
    suspend fun cancel(operationId: String) {
        forceEnd(operationId)
    }

    /**
     * Durdur, with an end guaranteed.
     *
     * Stopping was a post to the command bus and nothing else, which works only
     * while a worker is alive to read it. Every other case left the button inert:
     * a run whose process was killed, one wedged somewhere that never reaches
     * ensureActive(), or a row the reconciler could not correct. The operation
     * then sat in "süren ve bekleyen" with no progress and no way out, and
     * because liveCount() counts anything non-terminal, it queued every later
     * request behind itself -- an app that looked entirely broken over one
     * mistaken tap, recoverable only by clearing its data.
     *
     * So: ask nicely, then insist. A live worker gets [graceMs] to park itself
     * properly, which is what keeps a stop clean -- the cursor is written, the
     * report is sent, the queue moves on. Anything still standing after that is
     * not going to answer, and the row is ended here instead.
     *
     * Returns true when it had to be forced, which is worth telling the user:
     * the run ended without the tidy finish, and the counts it shows are the
     * last ones that reached the database.
     */
    suspend fun stop(operationId: String, graceMs: Long = STOP_GRACE_MS): Boolean {
        commands.post(operationId, OperationCommand.STOP)

        if (isWorkLive(operationId)) {
            val deadline = System.currentTimeMillis() + graceMs
            while (System.currentTimeMillis() < deadline) {
                val cp = db.checkpoints().get(operationId) ?: return false
                val state = runCatching { OperationState.valueOf(cp.state) }.getOrNull()
                if (state == null || state.isTerminal) return false
                kotlinx.coroutines.delay(STOP_POLL_MS)
            }
        }

        forceEnd(operationId)
        return true
    }

    /**
     * Removes the row and whatever work still claims it, then lets the queue
     * move.
     *
     * Cancelling by tag rather than by the unique work name: the name covers
     * whatever is scheduled under it, so abandoning a dead run used to cancel a
     * healthy one that had since taken its place.
     */
    private suspend fun forceEnd(operationId: String) {
        workManager.cancelAllWorkByTag(OperationWorker.tagFor(operationId))
        db.checkpoints().remove(operationId)
        // The reason the stuck row mattered: with it gone, whatever queued behind
        // it can finally start, and nothing else is going to notice that it can.
        drainQueue()
    }

    fun resume(operation: PausedOperation) {
        OperationWorker.enqueueExisting(workManager, operation.operationId)
    }
}

/** A parked run plus everything needed to restart it. */
data class PausedOperation(
    val operationId: String,
    val request: OperationRequest,
)
