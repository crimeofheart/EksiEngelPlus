package org.duzgun.eksiengelplus.ops.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The budget as the app's visibility drives it.
 *
 * Bringing the app to the foreground resets the platform's dataSync timer, and
 * the service has the full allowance again once the app leaves the screen. Billed
 * any less, a run outlives the soft budget and the system ends it mid-run; billed
 * any more -- as it was, with nothing calling releaseForeground -- a run watched
 * from İşlem durumu parks for the day hours before it has to.
 */
class ForegroundBudgetVisibilityTest {

    private var now = 0L
    private val hour = 60 * 60 * 1000L

    private fun budget(used: Long = 0) =
        ForegroundBudget(clock = { now }).apply { resume(used) }

    @Test fun `opening the app starts the allowance over`() {
        val budget = budget(used = 4 * hour)
        now += 30 * 60 * 1000

        budget.releaseForeground()
        now += 3 * hour                     // watched the whole time

        assertThat(budget.consumedMs()).isEqualTo(0)
        assertThat(budget.isExhausted()).isFalse()
    }

    @Test fun `leaving the app bills again from that moment`() {
        val budget = budget()
        budget.releaseForeground()
        now += 2 * hour

        budget.holdForeground()
        now += hour

        assertThat(budget.consumedMs()).isEqualTo(hour)
    }

    @Test fun `a full background allowance after leaving still parks the run`() {
        val budget = budget()
        budget.releaseForeground()
        budget.holdForeground()
        now += ForegroundBudget.DEFAULT_SOFT_BUDGET_MS

        assertThat(budget.isExhausted()).isTrue()
    }

    @Test fun `repeated signals change nothing`() {
        val budget = budget()
        budget.releaseForeground()
        budget.holdForeground()
        now += hour
        // A second "off screen" must not restart the clock and forgive the hour.
        budget.holdForeground()
        now += hour

        assertThat(budget.consumedMs()).isEqualTo(2 * hour)
    }

    @Test fun `the warning comes back for the new allowance`() {
        val budget = budget()
        now += 4 * hour + 1
        assertThat(budget.shouldWarn()).isTrue()

        budget.releaseForeground()
        budget.holdForeground()
        now += 4 * hour + 1

        assertThat(budget.shouldWarn()).isTrue()
        assertThat(budget.shouldWarn()).isFalse()
    }
}
