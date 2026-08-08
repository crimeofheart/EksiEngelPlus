package org.duzgun.eksiengelplus.ops.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persisted bucket state, so a killed process does not resume with a full budget. */
data class PacerSnapshot(
    val tokens: Double,
    val lastRefillAt: Long,
    val intervalMs: Long,
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
 * Proactive token bucket for mutations.
 *
 * The extension does not pace: it fires as fast as it can and absorbs the 429s,
 * spending a full cooldown every time it overshoots. It also holds two
 * disagreeing cooldown implementations -- programController.js:615 honours
 * Retry-After over three attempts, background.js:639 hard-codes 62 seconds and
 * ignores the header -- and each only sleeps the caller that was rejected.
 *
 * One shared bucket replaces both, and is what lets a 429 penalise everyone
 * rather than one unlucky caller.
 *
 * Default 12/min, the limit surfaced to users at notificationHandler.js:60. It is
 * documented rather than measured, which is why AIMD sits on top: a wrong
 * constant degrades gracefully instead of hammering a limit we cannot see.
 *
 * Not user-configurable upward. A user cannot consent on the server's behalf.
 */
class ActionPacer(
    private val permitsPerMinute: Int = DEFAULT_PERMITS_PER_MINUTE,
    private val capacity: Int = DEFAULT_PERMITS_PER_MINUTE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit,
    private val state: PacerState = InMemoryPacerState(),
) {
    companion object {
        /** notificationHandler.js:60 -- "Dakikada 12 engel limiti bekleniyor". */
        const val DEFAULT_PERMITS_PER_MINUTE = 12

        /** Each 429 multiplies the interval by this, up to the ceiling. */
        private const val BACKOFF_FACTOR = 1.5

        /** Never slower than one action per 30s, however many 429s arrive. */
        private const val MAX_INTERVAL_MS = 30_000L

        /** Consecutive successes before the interval decays a step. */
        private const val DECAY_AFTER_SUCCESSES = 50

        private const val DECAY_FACTOR = 0.9
    }

    private val baseIntervalMs: Long = 60_000L / permitsPerMinute
    private val mutex = Mutex()

    private var tokens: Double
    private var lastRefillAt: Long
    private var intervalMs: Long
    private var blockedUntil: Long
    private var consecutiveSuccesses = 0

    init {
        val restored = state.load()
        tokens = restored?.tokens ?: capacity.toDouble()
        lastRefillAt = restored?.lastRefillAt ?: clock()
        intervalMs = restored?.intervalMs ?: baseIntervalMs
        blockedUntil = restored?.blockedUntil ?: 0L
    }

    /** Suspends until this caller may perform one action. */
    suspend fun acquire() {
        while (true) {
            val wait = mutex.withLock {
                val now = clock()

                // A penalty outranks the bucket: every caller waits it out, including
                // ones that never saw the 429.
                if (now < blockedUntil) return@withLock blockedUntil - now

                refill(now)
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    persist()
                    return@withLock 0L
                }
                // Time until the next whole token.
                (((1.0 - tokens) * intervalMs).toLong()).coerceAtLeast(1L)
            }
            if (wait <= 0L) return
            sleep(wait)
        }
    }

    /**
     * Applies a server-instructed cooldown to the whole bucket.
     *
     * Tokens are drained as well as blocked: otherwise the wait expires and the
     * client immediately bursts a full bucket at a server that just asked it to
     * slow down.
     */
    suspend fun penalize(retryAfterSeconds: Int) = mutex.withLock {
        val now = clock()
        tokens = 0.0
        lastRefillAt = now
        blockedUntil = maxOf(blockedUntil, now + retryAfterSeconds * 1000L)
        intervalMs = (intervalMs * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_INTERVAL_MS)
        consecutiveSuccesses = 0
        persist()
    }

    /** Sustained success decays the interval back toward the configured rate. */
    suspend fun onSuccess() = mutex.withLock {
        if (intervalMs <= baseIntervalMs) return@withLock
        if (++consecutiveSuccesses < DECAY_AFTER_SUCCESSES) return@withLock
        consecutiveSuccesses = 0
        intervalMs = (intervalMs * DECAY_FACTOR).toLong().coerceAtLeast(baseIntervalMs)
        persist()
    }

    private fun refill(now: Long) {
        val elapsed = now - lastRefillAt
        if (elapsed <= 0) return
        tokens = (tokens + elapsed.toDouble() / intervalMs).coerceAtMost(capacity.toDouble())
        lastRefillAt = now
    }

    private fun persist() = state.save(PacerSnapshot(tokens, lastRefillAt, intervalMs, blockedUntil))
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
