package org.duzgun.eksiengelplus.ops.runtime

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.RelationUserEntity
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.ListType
import org.duzgun.eksiengelplus.ops.engine.BudgetExhaustedSignal
import org.duzgun.eksiengelplus.ops.engine.ActionPacer
import org.duzgun.eksiengelplus.ops.engine.OperationCursor
import org.duzgun.eksiengelplus.ops.engine.OperationProgress
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.PauseSignal
import org.duzgun.eksiengelplus.ops.engine.ReadPacer
import org.duzgun.eksiengelplus.ops.engine.StopSignal
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeTest {

    private lateinit var db: EksiDatabase
    private lateinit var commands: OperationCommandBus
    private var now = 0L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EksiDatabase::class.java,
        ).build()
        commands = InMemoryCommandBus()
    }

    @After fun tearDown() = db.close()

    /**
     * A run's checkpoint row, as enqueue would have written it.
     *
     * checkpoint() treats a missing row as cancellation -- that is what stops a
     * cancelled run recreating itself as RUNNING -- so a context built straight
     * in a test has to start from the same state production does.
     */
    private suspend fun seedCheckpoint(id: String = "op1") {
        db.checkpoints().upsert(
            org.duzgun.eksiengelplus.database.OperationCheckpointEntity(
                operationId = id,
                type = BanSource.LIST.name,
                state = org.duzgun.eksiengelplus.ops.engine.OperationState.RUNNING.name,
                cursorJson = "{}",
                processed = 0,
                total = 0,
                successful = 0,
                failed = 0,
                startedAt = now,
                updatedAt = now,
                workRequestId = null,
            ),
        )
    }

    private fun context(
        budget: ForegroundBudget = ForegroundBudget(clock = { now }).apply { resume(0) },
    ) = RoomOperationContext(
        operationId = "op1",
        request = OperationRequest(BanSource.LIST, BanMode.BAN),
        startCursor = OperationCursor(),
        db = db,
        commands = commands,
        budget = budget,
        actionPacer = ActionPacer(sleep = {}, clock = { now }),
        readPacer = ReadPacer(sleep = {}, clock = { now }),
        clock = { now },
    )

    /**
     * The row and the notification must not disagree about the same run.
     *
     * The notification took the total from the worker's memory, İşlem durumu
     * from this row, and nothing wrote the row until the first checkpoint --
     * five targets in, later still when the run opens with a cooldown. The two
     * surfaces read "0 / 1" and "0 / 0" for the same run, and 0 / 0 is what a
     * run against nobody looks like.
     */
    @Test fun publishingProgressRecordsTheCountsOnTheRow() = runTest {
        seedCheckpoint()
        val ctx = context()

        ctx.publishProgress(OperationProgress(processed = 0, total = 37, successful = 0, failed = 0))
        assertThat(db.checkpoints().get("op1")!!.total).isEqualTo(37)

        // Between checkpoints, which is where the two surfaces used to diverge.
        ctx.publishProgress(OperationProgress(processed = 8, total = 13, successful = 7, failed = 1))
        val row = db.checkpoints().get("op1")!!
        assertThat(row.processed).isEqualTo(8)
        assertThat(row.successful).isEqualTo(7)
        assertThat(row.failed).isEqualTo(1)

        // The cursor stays the checkpoint's business alone.
        assertThat(row.cursorJson).isEqualTo("{}")
    }

    @Test fun checkpointAndEffectsCommitTogether() = runTest {
        seedCheckpoint()
        val ctx = context()
        ctx.checkpoint(OperationCursor(index = 3, processed = 3, successful = 3)) {
            db.relationUsers().upsert(
                RelationUserEntity(ListType.BLOCKED, 1, "a", 0, 0),
            )
        }
        // Both landed, in one transaction.
        assertThat(db.checkpoints().get("op1")!!.processed).isEqualTo(3)
        assertThat(db.relationUsers().countOfNow(ListType.BLOCKED)).isEqualTo(1)
    }

    @Test fun aFailingEffectRollsBackTheCheckpointToo() = runTest {
        seedCheckpoint()
        val ctx = context()
        runCatching {
            ctx.checkpoint(OperationCursor(index = 9)) { error("effect blew up") }
        }
        // Neither, never one without the other. A cursor ahead of its rows
        // silently skips users; behind, it re-processes them.
        //
        // The run's row is there because enqueue wrote it, so rolled back means
        // untouched rather than absent: the cursor must not have advanced to the
        // position the failed effect was for.
        assertThat(db.checkpoints().get("op1")?.cursorJson).isEqualTo("{}")
    }

    @Test fun pauseCommandSurfacesAtTheNextCheckpoint() = runTest {
        seedCheckpoint()
        val ctx = context()
        ctx.ensureActive()                       // clean
        commands.post("op1", OperationCommand.PAUSE)
        runCatching { ctx.ensureActive() }
            .onSuccess { throw AssertionError("expected PauseSignal") }
            .onFailure { assertThat(it).isInstanceOf(PauseSignal::class.java) }
    }

    @Test fun stopOutranksPause() = runTest {
        seedCheckpoint()
        commands.post("op1", OperationCommand.PAUSE)
        commands.post("op1", OperationCommand.STOP)
        runCatching { context().ensureActive() }
            .onFailure { assertThat(it).isInstanceOf(StopSignal::class.java) }
    }

    @Test fun aPauseDoesNotDowngradeAnExistingStop() = runTest {
        seedCheckpoint()
        commands.post("op1", OperationCommand.STOP)
        commands.post("op1", OperationCommand.PAUSE)
        runCatching { context().ensureActive() }
            .onFailure { assertThat(it).isInstanceOf(StopSignal::class.java) }
    }

    @Test fun exhaustedForegroundBudgetStopsTheRun() = runTest {
        seedCheckpoint()
        val budget = ForegroundBudget(softBudgetMs = 1_000, clock = { now }).apply { resume(0) }
        val ctx = context(budget)
        ctx.ensureActive()          // inside budget
        now += 1_500
        runCatching { ctx.ensureActive() }
            .onSuccess { throw AssertionError("expected BudgetExhaustedSignal") }
            .onFailure { assertThat(it).isInstanceOf(BudgetExhaustedSignal::class.java) }
    }

    @Test fun foregroundTimeIsNotBilledWhileTheAppIsVisible() {
        val budget = ForegroundBudget(softBudgetMs = 10_000, clock = { now }).apply { resume(0) }
        now += 3_000
        budget.releaseForeground()      // a visible activity took over
        now += 60_000                   // an hour of foreground use
        assertThat(budget.consumedMs()).isEqualTo(3_000)
        assertThat(budget.isExhausted()).isFalse()
    }

    @Test fun budgetResumesFromWhatEarlierSlicesConsumed() {
        val budget = ForegroundBudget(softBudgetMs = 10_000, clock = { now })
        budget.resume(alreadyConsumedMs = 9_000)
        now += 500
        assertThat(budget.isExhausted()).isFalse()
        now += 1_000
        assertThat(budget.isExhausted()).isTrue()
    }

    @Test fun consumedBudgetIsPersistedOnEveryCheckpoint() = runTest {
        seedCheckpoint()
        val budget = ForegroundBudget(clock = { now }).apply { resume(0) }
        val ctx = context(budget)
        now += 7_000
        ctx.checkpoint(OperationCursor(index = 1))
        // Without this the next slice would start its budget from zero and the
        // platform would kill it partway through.
        assertThat(db.checkpoints().get("op1")!!.fgsMillisUsed).isAtLeast(7_000)
    }

    @Test fun reconcilerMarksAStaleRunningCheckpointInterrupted() = runTest {
        seedCheckpoint()
        val ctx = context()
        ctx.checkpoint(OperationCursor(index = 2))
        assertThat(db.checkpoints().get("op1")!!.state).isEqualTo("RUNNING")

        val reconciler = OperationReconciler(db, androidx.work.WorkManager.getInstance(
            ApplicationProvider.getApplicationContext(),
        ))
        val stale = reconciler.reconcile()
        assertThat(stale).contains("op1")
        assertThat(db.checkpoints().get("op1")!!.state).isEqualTo("INTERRUPTED")
        // Surfaced for the user to choose, never silently restarted.
        assertThat(reconciler.resumable()).contains("op1")
    }

    @Test fun budgetWarningFiresOnceAtTheThreshold() = runTest {
        seedCheckpoint()
        var warnings = 0
        val budget = ForegroundBudget(softBudgetMs = 10_000, warnFraction = 0.8, clock = { now })
            .apply { resume(0) }
        val ctx = RoomOperationContext(
            operationId = "op1",
            request = OperationRequest(BanSource.LIST, BanMode.BAN),
            startCursor = OperationCursor(),
            db = db,
            commands = commands,
            budget = budget,
            actionPacer = ActionPacer(sleep = {}, clock = { now }),
            readPacer = ReadPacer(sleep = {}, clock = { now }),
            onBudgetWarning = { warnings++ },
            clock = { now },
        )

        ctx.ensureActive()                 // 0% consumed
        assertThat(warnings).isEqualTo(0)

        now += 7_000                       // 70%
        ctx.ensureActive()
        assertThat(warnings).isEqualTo(0)

        now += 1_500                       // 85% -- crosses the threshold
        ctx.ensureActive()
        assertThat(warnings).isEqualTo(1)

        // Repeating it would train the user to dismiss the one that matters.
        repeat(5) { ctx.ensureActive() }
        assertThat(warnings).isEqualTo(1)
    }

    @Test fun theWarningLeavesEnoughRunwayToAct() {
        val budget = ForegroundBudget(softBudgetMs = 10_000, warnFraction = 0.8, clock = { now })
            .apply { resume(0) }
        now += 8_000
        assertThat(budget.shouldWarn()).isTrue()
        // Fired with a fifth of the budget still available, not at the buzzer.
        assertThat(budget.remainingMs()).isEqualTo(2_000)
        assertThat(budget.isExhausted()).isFalse()
    }

    @Test fun goingVisibleStopsBillingSoTheRunContinuesFree() {
        // The whole point of the warning: with the app open, work costs nothing.
        val budget = ForegroundBudget(softBudgetMs = 10_000, clock = { now }).apply { resume(0) }
        now += 8_000
        budget.releaseForeground()
        now += 10 * 60 * 1000               // ten minutes of visible use
        assertThat(budget.consumedMs()).isEqualTo(8_000)
        assertThat(budget.isExhausted()).isFalse()
    }
}
