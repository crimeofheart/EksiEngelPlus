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
) {

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
        if (!isWorkLive()) {
            for (cp in db.checkpoints().withState(OperationState.IDLE.name)) {
                db.checkpoints().remove(cp.operationId)
            }
        }

        for (cp in db.checkpoints().withState(OperationState.RUNNING.name)) {
            if (isWorkLive()) continue
            db.checkpoints().upsert(
                cp.copy(
                    state = OperationState.INTERRUPTED.name,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            stale += cp.operationId
        }

        return stale
    }

    /**
     * Checked against the unique work name rather than a stored request id: the
     * id is written at enqueue time, so a crash between enqueue and the first
     * checkpoint would leave it null and make the check silently pass.
     */
    private fun isWorkLive(): Boolean =
        workManager.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
            .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

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
        workManager.cancelUniqueWork(OperationWorker.UNIQUE_WORK)
        db.checkpoints().remove(operationId)
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
