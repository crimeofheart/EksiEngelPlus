package org.duzgun.eksiengelplus.ops.engine

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.duzgun.eksiengelplus.ops.engine.OperationEvent as E
import org.duzgun.eksiengelplus.ops.engine.OperationState as S
import org.junit.Test

class StateMachineTest {

    private fun next(s: S, e: E) = OperationStateMachine.next(s, e)?.state

    @Test fun `an idle operation starts`() {
        assertThat(next(S.IDLE, E.Start)).isEqualTo(S.RUNNING)
    }

    @Test fun `pause is cooperative, not immediate`() {
        // RUNNING -> PAUSING -> PAUSED. The intermediate state is the point: the
        // current unit finishes rather than being abandoned half-done.
        assertThat(next(S.RUNNING, E.RequestPause)).isEqualTo(S.PAUSING)
        assertThat(next(S.PAUSING, E.PauseAcknowledged)).isEqualTo(S.PAUSED)
    }

    @Test fun `a timed-out pause is forced and flags the checkpoint`() {
        val t = OperationStateMachine.next(S.PAUSING, E.PauseTimedOut)!!
        assertThat(t.state).isEqualTo(S.PAUSED)
        // The last unit may or may not have landed, so resume must re-verify.
        assertThat(t.checkpointDirty).isTrue()
    }

    @Test fun `an acknowledged pause leaves the checkpoint trustworthy`() {
        assertThat(OperationStateMachine.next(S.PAUSING, E.PauseAcknowledged)!!.checkpointDirty).isFalse()
    }

    @Test fun `stop is terminal while pause is resumable`() {
        assertThat(next(S.STOPPING, E.StopAcknowledged)).isEqualTo(S.STOPPED)
        assertThat(S.STOPPED.isTerminal).isTrue()
        assertThat(S.STOPPED.isResumable).isFalse()
        assertThat(S.PAUSED.isResumable).isTrue()
    }

    @Test fun `every paused variant resumes`() {
        listOf(S.PAUSED, S.PAUSED_AUTH, S.PAUSED_BUDGET, S.PAUSED_NETWORK, S.INTERRUPTED)
            .forEach { assertWithMessage(it.name).that(next(it, E.Resume)).isEqualTo(S.RUNNING) }
    }

    @Test fun `session loss parks rather than failing`() {
        // Turnstile means no retry can succeed, so this must be a resumable pause
        // and not a terminal failure.
        assertThat(next(S.RUNNING, E.SessionLost)).isEqualTo(S.PAUSED_AUTH)
        assertThat(S.PAUSED_AUTH.isResumable).isTrue()
        assertThat(S.PAUSED_AUTH.isTerminal).isFalse()
    }

    @Test fun `budget exhaustion is a pause, not a completion`() {
        // Android 15 caps the foreground service well below a full run, so this
        // is routine mid-run behaviour rather than an error.
        assertThat(next(S.RUNNING, E.BudgetExhausted)).isEqualTo(S.PAUSED_BUDGET)
        assertThat(S.PAUSED_BUDGET.isTerminal).isFalse()
    }

    @Test fun `session loss during a pause still reaches the auth state`() {
        assertThat(next(S.PAUSING, E.SessionLost)).isEqualTo(S.PAUSED_AUTH)
    }

    @Test fun `terminal states accept nothing`() {
        listOf(S.STOPPED, S.COMPLETED).forEach { s ->
            listOf(E.Start, E.Resume, E.RequestPause, E.Finished).forEach { e ->
                assertWithMessage("$s + $e").that(next(s, e)).isNull()
            }
        }
    }

    @Test fun `illegal transitions are rejected rather than silently ignored`() {
        // A machine that absorbs nonsense hides the bug that produced it.
        assertThat(next(S.IDLE, E.RequestPause)).isNull()
        assertThat(next(S.IDLE, E.Finished)).isNull()
        assertThat(next(S.PAUSED, E.PauseAcknowledged)).isNull()
        assertThat(next(S.RUNNING, E.Start)).isNull()
        assertThat(next(S.STOPPING, E.Resume)).isNull()
    }

    @Test fun `a stale running operation is reconciled to interrupted`() {
        assertThat(next(S.RUNNING, E.FoundStale)).isEqualTo(S.INTERRUPTED)
        assertThat(S.INTERRUPTED.isResumable).isTrue()
    }

    @Test fun `a stop request from any paused state ends the operation`() {
        listOf(S.PAUSED, S.PAUSED_AUTH, S.PAUSED_BUDGET, S.PAUSED_NETWORK)
            .forEach { assertWithMessage(it.name).that(next(it, E.RequestStop)).isEqualTo(S.STOPPED) }
    }
}
