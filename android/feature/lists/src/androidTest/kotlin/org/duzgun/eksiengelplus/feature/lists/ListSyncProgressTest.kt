package org.duzgun.eksiengelplus.feature.lists

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import com.google.common.truth.Truth.assertThat
import org.duzgun.eksiengelplus.model.ListType
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The progress hand-off between the worker and the screen.
 *
 * Two string keys with no compiler linking them is exactly the seam where a typo
 * costs nothing at build time and shows an empty progress line at runtime.
 */
@RunWith(AndroidJUnit4::class)
class ListSyncProgressTest {

    @Test
    fun progressSurvivesTheRoundTripThroughWorkData() {
        val data = ListSyncWorker.progressData(SyncProgress(page = 12, seen = 240))

        assertThat(ListSyncWorker.progressOf(data)).isEqualTo(SyncProgress(page = 12, seen = 240))
    }

    /** WorkInfo carries empty progress until the first setProgress lands. */
    @Test
    fun emptyProgressReadsAsNoProgressRatherThanPageZero() {
        assertThat(ListSyncWorker.progressOf(Data.EMPTY)).isNull()
    }

    @Test
    fun eachListGetsItsOwnWorkNameSoTheyRunIndependently() {
        val names = ListType.entries.map { ListSyncWorker.uniqueWorkName(it) }

        assertThat(names).containsNoDuplicates()
        assertThat(names).hasSize(3)
    }
}
