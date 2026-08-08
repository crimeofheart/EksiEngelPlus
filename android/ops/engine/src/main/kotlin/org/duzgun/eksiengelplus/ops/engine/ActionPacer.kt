package org.duzgun.eksiengelplus.ops.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persisted window, so a killed process does not resume with a fresh budget. */
data class PacerSnapshot(
    val windowStartedAt: Long,
    val usedInWindow: Int,
    val blockedUntil: Long,
)

interface PacerState {
    fun load(): PacerSnapshot?
    fun save(snapshot: PacerSnapshot)
}

class InMemoryPacerState : PacerState {
    private var snapshot: PacerSnapshot? = null
    override fun load() = snapshot
    override fun save(snapshot: PacerSnapshot) { this.snapshot = snapshot }
}

/**
 * A fixed window of mutations: 12 actions, then a full minute.
 *
 * This was a leaky bucket refilling one token every 5s, with AIMD widening the
 * interval after each 429. Two things came of that. The pause between actions
 * was permanent but small, so the run never had a clean boundary; and a 429 was
 * honoured for whatever Retry-After said, so the cooldown started from 23 or 24
 * seconds as often as from 60 -- a partial wait against a window whose start we
 * cannot see, which lands the next burst inside the same window and trips the
 * limit again.
 *
 * A window matches how the limit is actually expressed
 * (notificationHandler.js:60, "Dakikada 12 engel limiti bekleniyor"): spend the
 * twelve, spaced by 50ms as the extension spaces them, then wait a full window
 * from the moment they run out. Every wait is therefore exactly 61 seconds and starts from the same
 * number, which is what makes the countdown legible -- and it is never shorter
 * than the window the server is enforcing.
 *
 * Retry-After is deliberately ignored. A rejection means the window is spent,
 * and only a full fresh one is guaranteed to clear it.
 *
 * Not user-configurable upward. A user cannot consent on the server's behalf.
 */
class ActionPacer(
    private val permitsPerWindow: Int = DEFAULT_PERMITS_PER_WINDOW,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit,
    private val state: PacerState = InMemoryPacerState(),
) {
    companion object {
        /** notificationHandler.js:60 -- "Dakikada 12 engel limiti bekleniyor". */
        const val DEFAULT_PERMITS_PER_WINDOW = 12

        /**
         * 62 seconds, the same wait the extension uses (background.js:642,
         * `let waitTimeInSec = 62`).
         *
         * Two seconds past the minute the limit is expressed in: landing on the
         * boundary races the server's own bookkeeping. Matching the extension
         * exactly matters more than shaving a second -- the two clients hit the
         * same account, and a value the extension has been running against this
         * server for years is better evidence than our arithmetic.
         */
        const val WINDOW_MS = 62_000L

        /**
         * Minimum gap between two mutations, from background.js:548 --
         * `await utils.sleep(50)`, commented "Small delay to avoid rate
         * limiting".
         *
         * The window governs how many actions a minute allows; this governs how
         * hard they arrive. Twelve requests fired back to back inside a few
         * hundred milliseconds is a burst whether or not the count is legal, and
         * bursts are what get an account looked at rather than merely throttled.
         */
        const val MIN_ACTION_GAP_MS = 50L
    }

    private val mutex = Mutex()

    private var windowStartedAt: Long
    private var usedInWindow: Int
    private var blockedUntil: Long

    /**
     * Not persisted: a process that has just started cannot be mid-burst, and
     * the gap is 50ms.
     *
     * Far in the past rather than 0, so the first action is not held back and a
     * clock that legitimately reads 0 is not mistaken for "no action yet".
     */
    private var lastActionAt: Long = Long.MIN_VALUE / 2

    init {
        val restored = state.load()
        windowStartedAt = restored?.windowStartedAt ?: 0L
        usedInWindow = restored?.usedInWindow ?: 0
        blockedUntil = restored?.blockedUntil ?: 0L
    }

    /** Suspends until this caller may perform one action. */
    suspend fun acquire() {
        while (true) {
            val wait = mutex.withLock {
                val now = clock()

                // A rejection outranks the window: every caller waits it out,
                // including ones that never saw the 429.
                if (now < blockedUntil) return@withLock blockedUntil - now

                // A window that has turned over costs nothing to start.
                if (now - windowStartedAt >= WINDOW_MS) {
                    windowStartedAt = now
                    usedInWindow = 0
                }

                if (usedInWindow < permitsPerWindow) {
                    // Space them out even when the window has room.
                    val sinceLast = now - lastActionAt
                    if (sinceLast < MIN_ACTION_GAP_MS) {
                        return@withLock MIN_ACTION_GAP_MS - sinceLast
                    }
                    usedInWindow++
                    lastActionAt = now
                    persist()
                    return@withLock 0L
                }

                /*
                 * Spent. A whole window from this moment, not from when the
                 * window opened.
                 *
                 * Counting from the open subtracts however long the twelve took
                 * to send -- so the first wait was 61s and the next 56s, and the
                 * user watching the countdown saw the limit drift. It also
                 * assumes our clock agrees with the server's about when the
                 * window began, which is the assumption Retry-After already
                 * disproved.
                 *
                 * Waiting from exhaustion is the conservative reading: never
                 * shorter than the window the server is enforcing.
                 */
                blockedUntil = now + WINDOW_MS
                windowStartedAt = blockedUntil
                usedInWindow = 0
                persist()
                WINDOW_MS
            }
            if (wait <= 0L) return
            sleep(wait)
        }
    }

    /**
     * The server rejected a request. Wait out a whole fresh window.
     *
     * [retryAfterSeconds] is accepted so callers need not decide whether the
     * header is trustworthy, and ignored: it describes the remainder of a window
     * whose start we cannot see, and honouring it is what produced cooldowns of
     * 23 and 24 seconds that then tripped the limit again.
     *
     * The window is reset to begin when the penalty ends, so the run does not
     * come back and immediately spend twelve more into a window the server still
     * considers full.
     */
    suspend fun penalize(retryAfterSeconds: Int) = mutex.withLock {
        val now = clock()
        blockedUntil = maxOf(blockedUntil, now + WINDOW_MS)
        windowStartedAt = blockedUntil
        usedInWindow = 0
        persist()
    }

    private fun persist() =
        state.save(PacerSnapshot(windowStartedAt, usedInWindow, blockedUntil))
}

/**
 * Reads are not what the server limits, so they get their own looser budget --
 * throttling scrapes to the action rate would make a list refresh take hours for
 * no reason. Replaces the ad-hoc 500ms and 50-100ms sleeps scattered through
 * scrapingHandler.js.
 */
class ReadPacer(
    private val minGapMs: Long = 250L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit,
) {
    private val mutex = Mutex()
    private var lastAt = 0L

    suspend fun acquire() {
        val wait = mutex.withLock {
            val now = clock()
            val gap = now - lastAt
            if (lastAt != 0L && gap < minGapMs) {
                lastAt = now + (minGapMs - gap)
                minGapMs - gap
            } else {
                lastAt = now
                0L
            }
        }
        if (wait > 0) sleep(wait)
    }
}
