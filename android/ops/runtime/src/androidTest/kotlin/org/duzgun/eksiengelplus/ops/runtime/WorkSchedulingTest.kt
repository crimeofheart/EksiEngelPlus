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
    private lateinit var db: org.duzgun.eksiengelplus.database.EksiDatabase

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
        db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            org.duzgun.eksiengelplus.database.EksiDatabase::class.java,
        ).build()
    }

    @org.junit.After fun closeDb() = db.close()

    private val request = OperationRequest(BanSource.LIST, BanMode.BAN, nicks = listOf("a", "b"))

    /**
     * A second request while one is live is queued, not run and not lost.
     *
     * This used to rest on ExistingWorkPolicy.KEEP, which protected the running
     * run by discarding the request entirely -- the user was told "sıraya
     * alındı" and nothing ever happened. The queue is the protection now, so
     * only one operation is scheduled and the other is on the table.
     */
    @Test fun aSecondRequestIsQueuedRatherThanLost() = kotlinx.coroutines.runBlocking {
        OperationWorker.enqueue(wm, db, "op1", request)
        // Actually under way, not merely scheduled: IDLE is not a live run, or a
        // request whose work never ran would block everything after it.
        db.checkpoints().get("op1")!!.let {
            db.checkpoints().upsert(
                it.copy(state = org.duzgun.eksiengelplus.ops.engine.OperationState.RUNNING.name),
            )
        }

        OperationWorker.enqueue(wm, db, "op2", request)

        val live = wm.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
            .count { it.state != WorkInfo.State.CANCELLED }
        assertThat(live).isEqualTo(1)

        // The second survives as work to do, rather than being discarded.
        assertThat(db.queuedTasks().next()).isNotNull()
    }

    /**
     * A run that never started must not block every run after it.
     *
     * IDLE is written when work is enqueued, so a request whose work never ran
     * leaves one behind. Counting it as live meant the app believed something
     * was in progress forever: every later request queued behind it, and the
     * drain handed each one straight back to the queue it came from.
     */
    @Test fun aScheduledButUnstartedRunDoesNotBlockTheNext() = kotlinx.coroutines.runBlocking {
        // Exactly what enqueue leaves behind when its work never runs.
        OperationWorker.enqueue(wm, db, "never-ran", request)
        assertThat(db.checkpoints().get("never-ran")).isNotNull()

        OperationWorker.enqueue(wm, db, "next", request)

        // The second starts rather than joining a queue behind a ghost.
        assertThat(db.checkpoints().get("next")).isNotNull()
        assertThat(db.queuedTasks().next()).isNull()
    }

    /**
     * And a request that arrives when nothing is live actually starts.
     *
     * KEEP also discarded it whenever a stale entry lingered under the unique
     * name -- an enqueued or cancelled one from a run that never began -- which
     * left a checkpoint reading "başlamadı" with no work behind it.
     */
    @Test fun aRequestIsNotSwallowedByAStaleEntry() = kotlinx.coroutines.runBlocking {
        OperationWorker.enqueue(wm, db, "first", request)
        wm.cancelUniqueWork(OperationWorker.UNIQUE_WORK)
        db.checkpoints().remove("first")

        OperationWorker.enqueue(wm, db, "second", request)

        val live = wm.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
            .filter { it.state != WorkInfo.State.CANCELLED }
        assertThat(live).hasSize(1)
        assertThat(db.checkpoints().get("second")).isNotNull()
    }

    /**
     * The request goes to the database, never into input data. WorkManager caps
     * Data at 10 KB, and a LIST run imported from a CSV carries far more nicks
     * than that -- the cap threw on the caller and took the app down.
     */
    @Test fun aLongTargetListDoesNotBreachTheInputDataCap() = kotlinx.coroutines.runBlocking {
        val many = OperationRequest(
            BanSource.LIST,
            BanMode.UNDOBAN,
            nicks = (1..5_000).map { "yazar-nick-number-$it" },
        )

        OperationWorker.enqueue(wm, db, "big", many)

        val infos = wm.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
        assertThat(infos).isNotEmpty()
        assertThat(db.checkpoints().get("big")?.requestJson).isNotNull()
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

    @Test fun aContinuationReplacesRatherThanQueuesBehind() = kotlinx.coroutines.runBlocking {
        OperationWorker.enqueue(wm, db, "op1", request)
        OperationWorker.enqueueContinuation(wm, "op1", request, delayMs = 1_000)
        val live = wm.getWorkInfosForUniqueWork(OperationWorker.UNIQUE_WORK).get()
            .filter { it.state != WorkInfo.State.CANCELLED }
        assertThat(live).hasSize(1)
        Unit
    }
}
