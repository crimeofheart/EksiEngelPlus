package org.duzgun.eksiengelplus.ops.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.database.CompletedOperationEntity
import org.duzgun.eksiengelplus.database.HistoryTaskEntity
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.database.QueuedTaskEntity
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.duzgun.eksiengelplus.ops.engine.RelationOutcome
import org.junit.Test

/**
 * What the other devices are told, derived from the tables the app keeps.
 *
 * The derivation is the whole design on this client -- no cancel path reports
 * on its own -- so every way a task can leave the queue is pinned here.
 */
class HistoryTaskStatesTest {

    private val request = OperationRequest(BanSource.FAV, BanMode.BAN, TargetType.USER, authorNick = "coh")
    private val requestJson = Json.encodeToString(OperationRequest.serializer(), request)

    private fun queued(taskId: String?) = QueuedTaskEntity(
        seq = 1, banSourcePk = 2, banModePk = 1, targetTypePk = 1, clickSourcePk = null,
        payloadJson = requestJson, status = "QUEUED", enqueuedAt = 10, taskId = taskId,
    )

    private fun checkpoint(id: String, state: OperationState, processed: Int = 0, total: Int = 0) =
        OperationCheckpointEntity(
            operationId = id, type = "FAV", state = state.name, cursorJson = "{}",
            processed = processed, total = total, successful = processed, failed = 0,
            startedAt = 20, updatedAt = 30, workRequestId = null, requestJson = requestJson,
        )

    private fun completed(id: String, stopped: Boolean) = CompletedOperationEntity(
        banSourcePk = 2, banModePk = 1, processed = 5, successful = 4, failed = 1,
        startedAt = 20, finishedAt = 40, summaryJson = "{}", requestJson = requestJson,
        taskId = id, stopped = stopped,
    )

    private fun known(id: String, status: String, pushed: Boolean = true, cancelRequested: Boolean = false) =
        HistoryTaskEntity(
            taskId = id, epoch = 0, status = status, banSourcePk = 2, banModePk = 1,
            description = "favlayanlar (coh)", requestJson = requestJson, actorId = 7,
            planned = 0, performed = 0, successful = 0, createdAt = 10,
            cancelRequested = cancelRequested, pushed = pushed,
        )

    private fun reconcile(
        known: List<HistoryTaskEntity> = emptyList(),
        queued: List<QueuedTaskEntity> = emptyList(),
        checkpoints: List<OperationCheckpointEntity> = emptyList(),
        completed: List<CompletedOperationEntity> = emptyList(),
    ) = TaskStates.reconcile(
        known,
        TaskStates.local(queued, checkpoints, completed),
        actorId = 7,
        describe = { "favlayanlar (coh)" },
    )

    @Test fun `a queued run is announced with what it is`() {
        val r = reconcile(queued = listOf(queued("t1")))
        val row = r.upserts.single()
        assertThat(row.status).isEqualTo("QUEUED")
        assertThat(row.banSourcePk).isEqualTo(2)
        assertThat(row.description).isEqualTo("favlayanlar (coh)")
        assertThat(row.pushed).isFalse()
    }

    @Test fun `a row queued before the ledger has no id and is not announced`() {
        assertThat(reconcile(queued = listOf(queued(null))).upserts).isEmpty()
    }

    @Test fun `running, paused and finished map to the server's states`() {
        val cases = mapOf(
            OperationState.IDLE to "QUEUED",
            OperationState.RUNNING to "RUNNING",
            OperationState.PAUSED to "RUNNING",
            OperationState.PAUSED_BUDGET to "RUNNING",
            OperationState.STOPPED to "STOPPED",
            OperationState.INTERRUPTED to "FAILED",
        )
        for ((state, expected) in cases) {
            val r = reconcile(checkpoints = listOf(checkpoint("t", state)))
            assertThat(r.upserts.single().status).isEqualTo(expected)
        }
        assertThat(reconcile(completed = listOf(completed("t", stopped = false))).upserts.single().status)
            .isEqualTo("DONE")
        assertThat(reconcile(completed = listOf(completed("t", stopped = true))).upserts.single().status)
            .isEqualTo("STOPPED")
    }

    @Test fun `progress moves forward and is pushed again`() {
        val r = reconcile(
            known = listOf(known("t", "RUNNING")),
            checkpoints = listOf(checkpoint("t", OperationState.RUNNING, processed = 4, total = 9)),
        )
        val row = r.upserts.single()
        assertThat(row.performed).isEqualTo(4)
        assertThat(row.planned).isEqualTo(9)
        assertThat(row.pushed).isFalse()
    }

    @Test fun `nothing changed means nothing to push`() {
        val r = reconcile(
            known = listOf(known("t", "QUEUED")),
            queued = listOf(queued("t")),
        )
        assertThat(r.upserts).isEmpty()
    }

    @Test fun `removed from the queue reads as cancelled`() {
        val r = reconcile(known = listOf(known("t", "QUEUED")))
        assertThat(r.upserts.single().status).isEqualTo("CANCELLED")
    }

    @Test fun `stopped because another device asked is a cancel`() {
        val r = reconcile(
            known = listOf(known("t", "RUNNING", cancelRequested = true)),
            completed = listOf(completed("t", stopped = true)),
        )
        assertThat(r.upserts.single().status).isEqualTo("CANCELLED")
    }

    @Test fun `a finished task never goes back`() {
        val r = reconcile(
            known = listOf(known("t", "DONE")),
            checkpoints = listOf(checkpoint("t", OperationState.RUNNING)),
        )
        assertThat(r.upserts).isEmpty()
    }

    @Test fun `a task taken over elsewhere is never reported again`() {
        val r = reconcile(
            known = listOf(known("t", HistoryWire.LOST)),
            checkpoints = listOf(checkpoint("t", OperationState.RUNNING, processed = 3)),
        )
        assertThat(r.upserts).isEmpty()
        assertThat(reconcile(known = listOf(known("t", HistoryWire.LOST))).deletes).containsExactly("t")
    }

    @Test fun `a settled, told task that aged out of the archive is forgotten`() {
        assertThat(reconcile(known = listOf(known("t", "DONE", pushed = true))).deletes).containsExactly("t")
        assertThat(reconcile(known = listOf(known("t", "DONE", pushed = false))).deletes).isEmpty()
    }

    @Test fun `status rules match the server`() {
        assertThat(HistoryWire.merge("RUNNING", "QUEUED")).isEqualTo("RUNNING")
        assertThat(HistoryWire.merge("DONE", "FAILED")).isEqualTo("DONE")
        assertThat(HistoryWire.merge(null, "QUEUED")).isEqualTo("QUEUED")
        assertThat(HistoryWire.merge("QUEUED", "CANCELLED")).isEqualTo("CANCELLED")
    }

    @Test fun `an outcome row uses the wire names`() {
        val recording = HistoryRecording("task", 7, "me", clock = { 99 }, uuid = { "u" })
        val row = recording.row(BanMode.UNDOBAN, TargetType.MUTE, 5, RelationOutcome.SKIPPED)
        assertThat(listOf(row.kind, row.direction, row.result)).containsExactly("MUTE", "REMOVE", "SKIPPED").inOrder()
        assertThat(row.taskId).isEqualTo("task")
        assertThat(row.occurredAt).isEqualTo(99)
    }
}
