package org.duzgun.eksiengelplus.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rule that decides whether a bulk run touches an account.
 *
 * Worth testing on its own: it is the only thing standing between "block
 * accounts older than a year" and blocking everyone.
 */
class DateFilterTest {

    private val today = 20_000L

    private fun rule(
        criteria: DateCriteria,
        days: Int? = null,
        epochDay: Long? = null,
        enabled: Boolean = true,
    ) = DateFilterRule("r", criteria, days = days, epochDay = epochDay, enabled = enabled)

    @Test fun `no rules allows everything`() {
        assertThat(DateFilter.allows(emptyList(), null, today)).isTrue()
        assertThat(DateFilter.allows(emptyList(), 1L, today)).isTrue()
    }

    @Test fun `disabled rules are not rules`() {
        val rules = listOf(rule(DateCriteria.OLDER_THAN, days = 365, enabled = false))

        assertThat(DateFilter.allows(rules, today, today)).isTrue()
    }

    /**
     * The safety property. A filter exists to keep accounts out of a run, so one
     * whose date could not be established must not be acted on.
     */
    @Test fun `an unknown date never passes an active filter`() {
        val rules = listOf(rule(DateCriteria.OLDER_THAN, days = 365))

        assertThat(DateFilter.allows(rules, null, today)).isFalse()
    }

    @Test fun `older than counts age, not date`() {
        val rules = listOf(rule(DateCriteria.OLDER_THAN, days = 365))

        assertThat(DateFilter.allows(rules, today - 400, today)).isTrue()
        assertThat(DateFilter.allows(rules, today - 100, today)).isFalse()
    }

    @Test fun `newer than is its inverse`() {
        val rules = listOf(rule(DateCriteria.NEWER_THAN, days = 30))

        assertThat(DateFilter.allows(rules, today - 10, today)).isTrue()
        assertThat(DateFilter.allows(rules, today - 90, today)).isFalse()
    }

    @Test fun `before and after compare the registration date itself`() {
        val before = listOf(rule(DateCriteria.BEFORE_DATE, epochDay = 15_000))
        val after = listOf(rule(DateCriteria.AFTER_DATE, epochDay = 15_000))

        assertThat(DateFilter.allows(before, 14_000, today)).isTrue()
        assertThat(DateFilter.allows(before, 16_000, today)).isFalse()
        assertThat(DateFilter.allows(after, 16_000, today)).isTrue()
        assertThat(DateFilter.allows(after, 14_000, today)).isFalse()
    }

    /** Every rule must pass, so two rules narrow rather than widen. */
    @Test fun `rules combine with and`() {
        val rules = listOf(
            rule(DateCriteria.OLDER_THAN, days = 100),
            rule(DateCriteria.NEWER_THAN, days = 500),
        )

        assertThat(DateFilter.allows(rules, today - 300, today)).isTrue()
        assertThat(DateFilter.allows(rules, today - 50, today)).isFalse()
        assertThat(DateFilter.allows(rules, today - 900, today)).isFalse()
    }

    /** A rule missing its value cannot exclude anyone; a half-typed row is not a filter. */
    @Test fun `a rule with no value does not exclude`() {
        assertThat(DateFilter.allows(listOf(rule(DateCriteria.OLDER_THAN)), today, today)).isTrue()
        assertThat(DateFilter.allows(listOf(rule(DateCriteria.BEFORE_DATE)), today, today)).isTrue()
    }
}
