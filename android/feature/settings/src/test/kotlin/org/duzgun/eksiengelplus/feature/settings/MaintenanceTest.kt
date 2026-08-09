package org.duzgun.eksiengelplus.feature.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The rule worth testing here is the refusal.
 *
 * Everything else this class does is a delegation; deleting a user's lists out
 * from under a running worker is the one outcome that cannot be undone by
 * pressing the button again.
 */
class MaintenanceTest {

    private var live = 0
    private var total = 12
    private var expired = 5
    private var cacheCleared = 0
    private var allCleared = 0

    private val now = 1_000_000_000L
    private val ttl = 30L * 24 * 60 * 60 * 1000
    private var seenCutoff: Long? = null

    private fun maintenance() = Maintenance(
        liveOperations = { live },
        cacheTotal = { total },
        cacheExpired = { cutoff -> seenCutoff = cutoff; expired },
        databaseBytes = { 4096L },
        clearCacheRows = { cacheCleared++ },
        clearAllRows = { allCleared++ },
        ttlMillis = ttl,
        now = { now },
    )

    @Test fun `stats report the cache, its expired subset and the database size`() = runTest {
        val stats = maintenance().stats()

        assertThat(stats.cacheTotal).isEqualTo(12)
        assertThat(stats.cacheExpired).isEqualTo(5)
        assertThat(stats.databaseBytes).isEqualTo(4096L)
    }

    @Test fun `the expired count uses the same cutoff a prune would`() = runTest {
        maintenance().stats()

        // If this drifts, the screen reports a number the button does not act on.
        assertThat(seenCutoff).isEqualTo(now - ttl)
    }

    @Test fun `clearing the cache needs no permission`() = runTest {
        live = 3

        maintenance().clearCache()

        assertThat(cacheCleared).isEqualTo(1)
    }

    @Test fun `clearing data is refused while an operation is running`() = runTest {
        live = 1

        val result = maintenance().clearStoredData()

        assertThat(result).isEqualTo(Maintenance.ClearResult.RefusedRunning(1))
        assertThat(allCleared).isEqualTo(0)
    }

    @Test fun `clearing data proceeds when nothing is running`() = runTest {
        live = 0

        val result = maintenance().clearStoredData()

        assertThat(result).isEqualTo(Maintenance.ClearResult.Cleared)
        assertThat(allCleared).isEqualTo(1)
    }
}
