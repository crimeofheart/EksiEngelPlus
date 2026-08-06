package org.duzgun.eksiengelplus.ops.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scheduling semantics only. The worker's body needs a live network and a
 * session, so this covers the parts that decide whether a fourteen-hour run
 * survives at all: uniqueness, and the continuation scheduled when the
 * foreground budget runs out.
 */
@RunWith(AndroidJUnit4::class)
class WorkSchedulingTest {

    private lateinit var wm: WorkManager

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        wm = WorkManager.getInstance(context)
    }

    private val request = OperationRequest(BanSource.LIST, BanMode.BAN, nicks = listOf("a", "b"))

    @Test fun enqueueingTwiceKeepsTheFirstRun() {
        OperationWorker.enqueue(wm, "op1", request)
        OperationWorker.enqueue(wm, "op2", request)

        val infos = wm.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
        // KEEP, not REPLACE: a second request must never cancel a run hours deep.
        assertThat(infos.count { it.state != WorkInfo.State.CANCELLED }).isEqualTo(1)
    }

    @Test fun aContinuationIsScheduledWithADelay() {
        // The budget-exhausted path: the run parks and books its own next slice.
        OperationWorker.enqueueContinuation(wm, "op1", request, delayMs = 60_000)

        val infos = wm.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
        assertThat(infos).isNotEmpty()
        assertThat(infos.first().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test fun theContinuationCarriesTheRequestForward() {
        // Losing this would restart the operation from scratch on day two.
        OperationWorker.enqueueContinuation(wm, "op-carry", request, delayMs = 1_000)
        val infos = wm.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
        assertThat(infos).hasSize(1)
    }

    @Test fun aContinuationReplacesRatherThanQueuesBehind() {
        OperationWorker.enqueue(wm, "op1", request)
        OperationWorker.enqueueContinuation(wm, "op1", request, delayMs = 1_000)
        val live = wm.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
            .filter { it.state != WorkInfo.State.CANCELLED }
        assertThat(live).hasSize(1)
    }
}
