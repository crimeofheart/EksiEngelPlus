package org.duzgun.eksiengelplus.ops.runtime

import com.google.common.truth.Truth.assertThat
import org.duzgun.eksiengelplus.datastore.EksiConfig
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.DateCriteria
import org.duzgun.eksiengelplus.model.DateFilterRule
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.junit.Test

/**
 * Which rules gate a run's targets.
 *
 * The precedence is the whole point and it is silent when wrong: a run that
 * quietly fell back to the saved rules would act on a set the user did not
 * choose, and one that wrote its own rule into settings would disarm the
 * standing protection for every operation afterwards.
 */
class ActiveDateRulesTest {

    private val ownRule = DateFilterRule(
        id = "date-bulk-run",
        criteria = DateCriteria.BEFORE_DATE,
        epochDay = 18_262L,
    )

    private fun request(rule: DateFilterRule? = null) = OperationRequest(
        source = BanSource.DATE_BASED_BULK,
        mode = BanMode.UNDOBAN,
        dateRule = rule,
    )

    /** A run that restricts someone, which is what the saved rules narrow. */
    private fun blocking(target: TargetType = TargetType.USER) = OperationRequest(
        source = BanSource.LIST,
        mode = BanMode.BAN,
        targetType = target,
    )

    @Test fun `a run's own criterion beats the saved rules`() {
        val config = EksiConfig()   // carries PROTECT_OLD_ACCOUNTS, enabled
        assertThat(activeDateRules(request(ownRule), config)).containsExactly(ownRule)
    }

    @Test fun `it beats them even where they disagree in direction`() {
        // NEWER_THAN in settings, OLDER_THAN-shaped rule on the run: taking the
        // union rather than the override would let nobody through at all.
        val config = EksiConfig(
            dateFilterRules = listOf(DateFilterRule.PROTECT_OLD_ACCOUNTS),
        )
        assertThat(activeDateRules(request(ownRule), config)).doesNotContain(
            DateFilterRule.PROTECT_OLD_ACCOUNTS,
        )
    }

    @Test fun `a blocking run without one uses the saved rules`() {
        val config = EksiConfig()
        assertThat(activeDateRules(blocking(), config))
            .containsExactly(DateFilterRule.PROTECT_OLD_ACCOUNTS)
    }

    @Test fun `the filter switched off means no rules`() {
        val config = EksiConfig(enableDateFilter = false)
        assertThat(activeDateRules(blocking(), config)).isEmpty()
    }

    // ------------------------------- the saved rules only narrow restrictions

    /**
     * The bug. The default ten-year rule gated every operation, so "tüm
     * engelleri kaldır" skipped decade-old accounts and left them blocked --
     * protection applied to the one direction that needed none. Silent, because
     * a skipped target is not a failure.
     */
    @Test fun `the saved rules do not narrow an undo`() {
        val config = EksiConfig()   // PROTECT_OLD_ACCOUNTS, enabled
        val undos = listOf(TargetType.USER, TargetType.MUTE, TargetType.TITLE, TargetType.FOLLOW)
            .map { OperationRequest(BanSource.UNDOBANALL, BanMode.UNDOBAN, targetType = it) }

        undos.forEach {
            assertThat(activeDateRules(it, config)).isEmpty()
        }
    }

    @Test fun `they narrow blocking, muting and title blocking`() {
        val config = EksiConfig()
        listOf(TargetType.USER, TargetType.MUTE, TargetType.TITLE).forEach { target ->
            assertThat(activeDateRules(blocking(target), config))
                .containsExactly(DateFilterRule.PROTECT_OLD_ACCOUNTS)
        }
    }

    /**
     * Following adds a relation without taking anything away from the person it
     * names, so a rule about protecting old accounts has nothing to say about it.
     */
    @Test fun `they do not narrow following`() {
        val config = EksiConfig()
        assertThat(activeDateRules(blocking(TargetType.FOLLOW), config)).isEmpty()
    }

    /**
     * A migration is UNDOBAN-shaped and moves people already blocked into muted.
     * Nobody is newly restricted, so nothing needs sparing.
     */
    @Test fun `they do not narrow a migration`() {
        val config = EksiConfig()
        val migrate = OperationRequest(
            source = BanSource.MIGRATE_BLOCKED_TO_MUTED,
            mode = BanMode.UNDOBAN,
            targetType = TargetType.USER,
        )
        assertThat(activeDateRules(migrate, config)).isEmpty()
    }

    /** But a run that blocks the people on the muted list is still a block. */
    @Test fun `they narrow blocking the muted`() {
        val config = EksiConfig()
        val blockMuted = OperationRequest(
            source = BanSource.BLOCK_MUTED_USERS,
            mode = BanMode.BAN,
            targetType = TargetType.USER,
        )
        assertThat(activeDateRules(blockMuted, config))
            .containsExactly(DateFilterRule.PROTECT_OLD_ACCOUNTS)
    }

    /**
     * And a date-based undo the user composed is still filtered, by its own
     * criterion. That is the whole feature -- the extension's default
     * composition is an unmute (config.js:58-66).
     */
    @Test fun `a composed undo is filtered by the criterion it carries`() {
        val config = EksiConfig()
        assertThat(activeDateRules(request(ownRule), config)).containsExactly(ownRule)
    }

    /**
     * A run carrying a criterion is filtered whatever settings say. The user
     * asked for that boundary on this run; the master switch governs the
     * standing rules, which is a different question.
     */
    @Test fun `the master switch does not disarm a run's own criterion`() {
        val config = EksiConfig(enableDateFilter = false)
        assertThat(activeDateRules(request(ownRule), config)).containsExactly(ownRule)
    }
}
