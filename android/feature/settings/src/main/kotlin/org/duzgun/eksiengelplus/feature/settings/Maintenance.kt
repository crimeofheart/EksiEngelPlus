package org.duzgun.eksiengelplus.feature.settings

/**
 * What the storage section reports and what its two buttons do.
 *
 * Built out of function types rather than the database, so the part that can
 * destroy a user's data is testable on the JVM. Room needs a device; the rule
 * "refuse while a run is live" does not, and it is the rule worth testing.
 */
class Maintenance(
    private val liveOperations: suspend () -> Int,
    private val cacheTotal: suspend () -> Int,
    private val cacheExpired: suspend (Long) -> Int,
    private val databaseBytes: () -> Long,
    private val clearCacheRows: suspend () -> Unit,
    private val clearAllRows: suspend () -> Unit,
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Stats(
        val cacheTotal: Int,
        /** The subset of [cacheTotal] past its TTL, which a prune would delete. */
        val cacheExpired: Int,
        val databaseBytes: Long,
    )

    /**
     * Whether clearing everything was allowed, and why it was not.
     *
     * A sealed result rather than a boolean plus a thrown exception: refusing is
     * an ordinary outcome the screen reports, not an error.
     */
    sealed interface ClearResult {
        data object Cleared : ClearResult

        /** A run is under way, so its rows would vanish from under it. */
        data class RefusedRunning(val liveOperations: Int) : ClearResult
    }

    suspend fun stats(): Stats = Stats(
        cacheTotal = cacheTotal(),
        cacheExpired = cacheExpired(now() - ttlMillis),
        databaseBytes = databaseBytes(),
    )

    /**
     * Unconditional, because the table holds only refetchable dates.
     *
     * The worst it costs is one profile read per nick the next time a date
     * filter runs, and a user asking to clear a cache has already accepted that
     * trade.
     */
    suspend fun clearCache() = clearCacheRows()

    /**
     * Everything the database holds, once the user has confirmed and no run is
     * live.
     *
     * The guard is not politeness. A worker reads its checkpoint, its cursor and
     * its queued rows as it goes; deleting them mid-run does not cancel the
     * operation, it leaves one executing against state that no longer exists.
     * Refusing is the only outcome that is not a corruption -- the user can stop
     * the run and ask again.
     */
    suspend fun clearStoredData(): ClearResult {
        val live = liveOperations()
        if (live > 0) return ClearResult.RefusedRunning(live)
        clearAllRows()
        return ClearResult.Cleared
    }
}
