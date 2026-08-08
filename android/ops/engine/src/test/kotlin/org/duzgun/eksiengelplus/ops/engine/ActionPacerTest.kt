package org.duzgun.eksiengelplus.ops.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The pacer is the one component that can quietly get a user's account
 * rate-limited, and every failure mode here is invisible at runtime -- too fast
 * just looks like 429s, too slow looks like the app being sluggish.
 *
 * A virtual clock throughout: real time would make this suite take minutes.
 */
class ActionPacerTest {

    private var now = 0L
    private val sleeps = mutableListOf<Long>()

    private fun pacer(perWindow: Int = 12, state: PacerState = InMemoryPacerState()) = ActionPacer(
        permitsPerWindow = perWindow,
        clock = { now },
        sleep = { ms -> sleeps += ms; now += ms },
        state = state,
    )

    /**
     * The allowance is released without waiting on the window, but not as a
     * burst: the extension spaces mutations by 50ms (background.js:548) and so
     * do we.
     */
    @Test fun `a fresh window releases its whole allowance, spaced`() = runTest {
        val p = pacer()
        repeat(12) { p.acquire() }

        // Eleven gaps between twelve actions, and no window wait.
        assertThat(sleeps).hasSize(11)
        assertThat(sleeps.toSet()).containsExactly(ActionPacer.MIN_ACTION_GAP_MS)
    }

    @Test fun `an action that arrives late needs no gap`() = runTest {
        val p = pacer()
        p.acquire()
        now += 1_000L

        p.acquire()

        assertThat(sleeps).isEmpty()
    }

    /**
     * The window, not a fraction of it.
     *
     * The old bucket dribbled a token back every 5s, so the run never had a
     * clean boundary and the wait shown to the user was a different number each
     * time.
     */
    @Test fun `the thirteenth action waits out the whole window`() = runTest {
        val p = pacer()
        repeat(12) { p.acquire() }
        sleeps.clear()

        p.acquire()

        assertThat(sleeps).containsExactly(ActionPacer.WINDOW_MS)
    }

    /**
     * Every wait is the same length, however long the twelve took to send.
     *
     * Counting the wait from when the window opened subtracted the time spent
     * sending, so the first cooldown was 61s and the next 56s -- the countdown
     * visibly drifting, against a server whose window start we cannot see.
     */
    @Test fun `the wait is a whole window however long the actions took`() = runTest {
        val p = pacer()
        repeat(12) {
            p.acquire()
            now += 5_000L      // the actions themselves take real time
        }

        p.acquire()

        assertThat(sleeps).containsExactly(ActionPacer.WINDOW_MS)
    }

    @Test fun `a second cooldown is as long as the first`() = runTest {
        val p = pacer()
        repeat(12) { p.acquire(); now += 3_000L }
        p.acquire()                                   // first cooldown
        now += 3_000L
        repeat(11) { p.acquire(); now += 3_000L }
        p.acquire()                                   // second

        assertThat(sleeps).containsExactly(ActionPacer.WINDOW_MS, ActionPacer.WINDOW_MS)
    }

    @Test fun `the allowance returns once the window turns over`() = runTest {
        val p = pacer()
        repeat(12) { p.acquire(); now += 1_000L }
        now += ActionPacer.WINDOW_MS
        sleeps.clear()

        repeat(12) { p.acquire(); now += 1_000L }

        assertThat(sleeps).isEmpty()
    }

    @Test fun `sustained rate never exceeds the allowance per window`() = runTest {
        val p = pacer()
        val start = now
        repeat(25) { p.acquire() }
        // 25 actions is three windows' worth: two full waits at least.
        assertThat(now - start).isAtLeast(2 * ActionPacer.WINDOW_MS)
    }

    /**
     * Retry-After is ignored on purpose.
     *
     * The server sent 23 and 24 as often as 60, because the header describes
     * what is left of a window whose start we cannot see. Waiting that fraction
     * put the next burst back inside the same window and tripped the limit
     * again -- and the countdown started from a different number each time.
     */
    @Test fun `a rejection costs a full window whatever Retry-After says`() = runTest {
        val p = pacer()
        p.penalize(retryAfterSeconds = 23)

        p.acquire()

        assertThat(sleeps).containsExactly(ActionPacer.WINDOW_MS)
    }

    @Test fun `a rejection does not release a fresh allowance the moment it expires`() = runTest {
        val p = pacer()
        p.penalize(retryAfterSeconds = 23)
        p.acquire()          // waits out the penalty
        now += 1_000L        // and that action takes time, like the rest
        sleeps.clear()

        // The window began when the penalty ended, so the twelve are spent from
        // there rather than immediately colliding with the server's own window.
        repeat(11) { p.acquire(); now += 1_000L }
        assertThat(sleeps).isEmpty()
        p.acquire()
        assertThat(sleeps).containsExactly(ActionPacer.WINDOW_MS)
    }

    @Test fun `the window survives a restart`() = runTest {
        val shared = InMemoryPacerState()
        repeat(12) { pacer(state = shared).acquire(); now += 1_000L }

        // Process dies and comes back. The server already counted those twelve.
        sleeps.clear()
        pacer(state = shared).acquire()

        assertThat(sleeps).isNotEmpty()
    }

    /**
     * A wait must be abandonable.
     *
     * Durdur and Duraklat are delivered by the injected sleep throwing, which is
     * only sound if acquire() has not already spent the permit it was waiting
     * for -- otherwise stopping a run would consume allowance the next run has
     * to wait out again.
     */
    @Test
    fun `a signal thrown from the wait escapes without spending a permit`() = runTest {
        val p = ActionPacer(
            permitsPerWindow = 1,
            clock = { now },
            sleep = { throw StopSignal() },
        )
        p.acquire()   // spends the only permit

        assertThrows(StopSignal::class.java) { runBlocking { p.acquire() } }

        // The interrupted wait took nothing: once the window turns over, the
        // whole allowance is there.
        now += ActionPacer.WINDOW_MS
        p.acquire()
    }
}
