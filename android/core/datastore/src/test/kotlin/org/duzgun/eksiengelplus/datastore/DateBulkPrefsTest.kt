package org.duzgun.eksiengelplus.datastore

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.model.DateBulkAction
import org.duzgun.eksiengelplus.model.DateBulkSource
import org.duzgun.eksiengelplus.model.DateCriteria
import org.junit.Test

/**
 * The chooser's memory, and the rule a composition means.
 *
 * Both matter for the same reason: the composition is configuration and the rule
 * is a run's own criterion, and neither may leak into the standing
 * dateFilterRules that protect every other operation.
 */
class DateBulkPrefsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `a fresh install carries the extension's defaults`() {
        // config.js:58-66.
        val prefs = DateBulkPrefs()
        assertThat(prefs.source).isEqualTo(DateBulkSource.MUTED_USERS)
        assertThat(prefs.criteria).isEqualTo(DateCriteria.OLDER_THAN)
        assertThat(prefs.days).isEqualTo(3650)
        assertThat(prefs.action).isEqualTo(DateBulkAction.SESSIZDEN_CIKAR)
    }

    /** Defaulted, which is why no CURRENT_VERSION bump and no migration step. */
    @Test fun `a config written before the field existed still decodes`() {
        val old = """{"enableMute":true,"enableDateFilter":true}"""
        assertThat(json.decodeFromString(EksiConfig.serializer(), old).dateBulk)
            .isEqualTo(DateBulkPrefs())
    }

    @Test fun `remembering a composition leaves the saved rules alone`() {
        val before = EksiConfig()
        val after = before.copy(
            dateBulk = DateBulkPrefs(
                source = DateBulkSource.AUTHOR_LIST,
                criteria = DateCriteria.BEFORE_DATE,
                epochDay = 18_262L,
                action = DateBulkAction.ENGELLE,
            ),
        )
        assertThat(after.dateFilterRules).isEqualTo(before.dateFilterRules)
    }

    @Test fun `a day criterion becomes a day rule`() {
        val rule = DateBulkPrefs(criteria = DateCriteria.OLDER_THAN, days = 365).toRule()!!
        assertThat(rule.criteria).isEqualTo(DateCriteria.OLDER_THAN)
        assertThat(rule.days).isEqualTo(365)
        assertThat(rule.epochDay).isNull()
    }

    @Test fun `a date criterion becomes a date rule`() {
        val rule = DateBulkPrefs(criteria = DateCriteria.BEFORE_DATE, epochDay = 18_262L).toRule()!!
        assertThat(rule.epochDay).isEqualTo(18_262L)
        assertThat(rule.days).isNull()
    }

    /**
     * Null, not a permissive rule. DateFilter reads a missing value as "this
     * rule does not apply", so a boundary the user never set would turn a
     * filtered run into a run over the whole list.
     */
    @Test fun `a criterion with no value is not a rule`() {
        assertThat(DateBulkPrefs(criteria = DateCriteria.BEFORE_DATE, epochDay = null).toRule())
            .isNull()
        assertThat(DateBulkPrefs(criteria = DateCriteria.OLDER_THAN, days = 0).toRule())
            .isNull()
    }

    /** Its own id, so a per-run rule can never be mistaken for the standing one. */
    @Test fun `the run's rule is not the settings rule`() {
        assertThat(DateBulkPrefs().toRule()!!.id)
            .isNotEqualTo(org.duzgun.eksiengelplus.model.DateFilterRule.PROTECT_OLD_ACCOUNTS.id)
    }
}
