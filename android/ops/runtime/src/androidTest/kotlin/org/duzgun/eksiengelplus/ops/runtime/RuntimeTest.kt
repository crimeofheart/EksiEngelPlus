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

    @Test fun checkpointAndEffectsCommitTogether() = runTest {
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
        val ctx = context()
        runCatching {
            ctx.checkpoint(OperationCursor(index = 9)) { error("effect blew up") }
        }
        // Neither, never one without the other. A cursor ahead of its rows
        // silently skips users; behind, it re-processes them.
        assertThat(db.checkpoints().get("op1")).isNull()
    }

    @Test fun pauseCommandSurfacesAtTheNextCheckpoint() = runTest {
        val ctx = context()
        ctx.ensureActive()                       // clean
        commands.post("op1", OperationCommand.PAUSE)
        runCatching { ctx.ensureActive() }
            .onSuccess { throw AssertionError("expected PauseSignal") }
            .onFailure { assertThat(it).isInstanceOf(PauseSignal::class.java) }
    }

    @Test fun stopOutranksPause() = runTest {
        commands.post("op1", OperationCommand.PAUSE)
        commands.post("op1", OperationCommand.STOP)
        runCatching { context().ensureActive() }
            .onFailure { assertThat(it).isInstanceOf(StopSignal::class.java) }
    }

    @Test fun aPauseDoesNotDowngradeAnExistingStop() = runTest {
        commands.post("op1", OperationCommand.STOP)
        commands.post("op1", OperationCommand.PAUSE)
        runCatching { context().ensureActive() }
            .onFailure { assertThat(it).isInstanceOf(StopSignal::class.java) }
    }

    @Test fun exhaustedForegroundBudgetStopsTheRun() = runTest {
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
        val budget = ForegroundBudget(clock = { now }).apply { resume(0) }
        val ctx = context(budget)
        now += 7_000
        ctx.checkpoint(OperationCursor(index = 1))
        // Without this the next slice would start its budget from zero and the
        // platform would kill it partway through.
        assertThat(db.checkpoints().get("op1")!!.fgsMillisUsed).isAtLeast(7_000)
    }

    @Test fun reconcilerMarksAStaleRunningCheckpointInterrupted() = runTest {
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
}
