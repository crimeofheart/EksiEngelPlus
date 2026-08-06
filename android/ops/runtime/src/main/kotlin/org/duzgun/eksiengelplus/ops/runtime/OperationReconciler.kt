package org.duzgun.eksiengelplus.ops.runtime

import androidx.work.WorkInfo
import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Singleton
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.ops.engine.OperationState

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
}
