package org.duzgun.eksiengelplus.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * flush() writes to disk, so calling it per response is wasteful -- but never
 * calling it loses the session on process death. The debounce is the compromise,
 * and it is worth pinning because both failure modes are silent.
 */
class CookieFlusherTest {

    private var now = 0L
    private var flushes = 0
    private val flusher = CookieFlusher(
        flush = { flushes++ },
        minIntervalMs = 10_000L,
        clock = { now },
    )

    @Test fun `the first request flushes immediately`() {
        flusher.requestFlush()
        assertThat(flushes).isEqualTo(1)
    }

    @Test fun `requests inside the window collapse into one`() {
        flusher.requestFlush()
        repeat(50) { now += 100; flusher.requestFlush() }
        assertThat(flushes).isEqualTo(1)
    }

    @Test fun `a request after the window flushes again`() {
        flusher.requestFlush()
        now += 10_001
        flusher.requestFlush()
        assertThat(flushes).isEqualTo(2)
    }

    @Test fun `flushNow bypasses the debounce and resets it`() {
        flusher.requestFlush()
        now += 10
        flusher.flushNow()
        assertThat(flushes).isEqualTo(2)
        now += 10
        flusher.requestFlush()          // still inside the window from flushNow
        assertThat(flushes).isEqualTo(2)
    }
}
