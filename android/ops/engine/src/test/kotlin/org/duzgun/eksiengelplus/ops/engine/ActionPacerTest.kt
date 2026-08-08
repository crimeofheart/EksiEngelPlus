package org.duzgun.eksiengelplus.ops.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Written before the implementation. The pacer is the one component that can
 * quietly get a user's account rate-limited, and every failure mode here is
 * invisible at runtime -- too fast just looks like 429s, too slow looks like the
 * app being sluggish.
 *
 * A virtual clock throughout: real time would make this suite take minutes.
 */
class ActionPacerTest {

    private var now = 0L
    private val sleeps = mutableListOf<Long>()

    private fun pacer(perMinute: Int = 12, capacity: Int = 12) = ActionPacer(
        permitsPerMinute = perMinute,
        capacity = capacity,
        clock = { now },
        sleep = { ms -> sleeps += ms; now += ms },
        state = InMemoryPacerState(),
    )

    @Test fun `a fresh bucket releases up to capacity immediately`() = runTest {
        val p = pacer()
        repeat(12) { p.acquire() }
        assertThat(sleeps).isEmpty()
    }

    @Test fun `the thirteenth action waits for a refill`() = runTest {
        val p = pacer()
        repeat(12) { p.acquire() }
        p.acquire()
        // 12/min is one token per 5000 ms.
        assertThat(sleeps).containsExactly(5_000L)
    }

    @Test fun `sustained rate never exceeds the configured permits per minute`() = runTest {
        val p = pacer(perMinute = 12, capacity = 1)
        val start = now
        repeat(13) { p.acquire() }
        // 13 actions from a one-token bucket spans at least 12 intervals.
        assertThat(now - start).isAtLeast(12 * 5_000L)
    }

    @Test fun `idle time refills the bucket up to capacity but no further`() = runTest {
        val p = pacer()
        repeat(12) { p.acquire() }
        now += 60 * 60 * 1000          // an hour idle
        sleeps.clear()
        repeat(12) { p.acquire() }     // capacity, not an hour's worth
        assertThat(sleeps).isEmpty()
        p.acquire()
        assertThat(sleeps).isNotEmpty()
    }

    @Test fun `a penalty blocks every caller, not just the one that hit it`() = runTest {
        val p = pacer()
        p.acquire()
        p.penalize(retryAfterSeconds = 30)
        sleeps.clear()
        // A different caller, which never saw the 429, must still wait.
        p.acquire()
        assertThat(sleeps.sum()).isAtLeast(30_000L)
    }

    @Test fun `a penalty drains tokens so the bucket cannot burst afterwards`() = runTest {
        val p = pacer()
        p.penalize(retryAfterSeconds = 10)
        sleeps.clear()
        p.acquire()          // pays the penalty
        sleeps.clear()
        p.acquire()          // and then the normal interval, not a free burst
        assertThat(sleeps.sum()).isAtLeast(5_000L)
    }

    @Test fun `repeated penalties widen the interval`() = runTest {
        val p = pacer(capacity = 1)
        val baseline = intervalAfter(p)
        repeat(4) { p.penalize(1) ; p.acquire() }
        assertThat(intervalAfter(p)).isGreaterThan(baseline)
    }

    @Test fun `sustained success decays the interval back toward the configured rate`() = runTest {
        val p = pacer(capacity = 1)
        val baseline = intervalAfter(p)
        repeat(4) { p.penalize(1); p.acquire() }
        val widened = intervalAfter(p)
        repeat(200) { p.acquire(); p.onSuccess() }
        val recovered = intervalAfter(p)
        assertThat(recovered).isLessThan(widened)
        assertThat(recovered).isAtLeast(baseline)   // never faster than configured
    }

    @Test fun `bucket state survives a restart`() = runTest {
        val shared = InMemoryPacerState()
        val first = ActionPacer(12, 12, { now }, { ms -> sleeps += ms; now += ms }, shared)
        repeat(12) { first.acquire() }

        // Process dies and comes back. The server already counted those twelve.
        sleeps.clear()
        val second = ActionPacer(12, 12, { now }, { ms -> sleeps += ms; now += ms }, shared)
        second.acquire()
        assertThat(sleeps).isNotEmpty()
    }

    private suspend fun intervalAfter(p: ActionPacer): Long {
        sleeps.clear()
        p.acquire()
        return sleeps.sum()
    }

    /**
     * A wait must be abandonable.
     *
     * Durdur and Duraklat are delivered by the injected sleep throwing, which is
     * only sound if acquire() has not already spent the token it was waiting
     * for -- otherwise stopping a run would silently consume budget the next run
     * has to wait out again.
     */
    @Test
    fun `a signal thrown from the wait escapes without spending a token`() = runTest {
        var now = 0L
        val pacer = ActionPacer(
            permitsPerMinute = 12,
            capacity = 1,
            clock = { now },
            sleep = { throw StopSignal() },
        )

        // Drains the single token, so the next call has to wait.
        pacer.acquire()

        assertThrows(StopSignal::class.java) { runBlocking { pacer.acquire() } }

        // The interrupted wait took nothing: once the bucket refills on its own
        // schedule, exactly one token is there -- not zero.
        now += 60_000L / 12
        pacer.acquire()
    }
}
