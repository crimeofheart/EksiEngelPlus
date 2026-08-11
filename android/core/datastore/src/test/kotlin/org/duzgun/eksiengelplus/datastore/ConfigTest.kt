package org.duzgun.eksiengelplus.datastore

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.model.DateCriteria
import org.duzgun.eksiengelplus.model.DateFilter
import org.duzgun.eksiengelplus.model.DateFilterRule
import org.junit.Test

/**
 * The serializer contract, tested without Android: round-tripping, forward
 * compatibility, and the deliberate default divergence from the extension.
 */
class ConfigTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Checked against config.js line by line rather than by memory.
     *
     * enableMute and enableProtectFollowedUsers were both false here while the
     * extension shipped them true, and this test asserted the wrong value for
     * one of them -- so it confirmed the drift instead of catching it.
     */
    @Test fun `defaults match the extension`() {
        val c = EksiConfig()
        assertThat(c.eksiSozlukUrl).isEqualTo("https://eksisozluk.com")
        assertThat(c.enableNoobBan).isTrue()                  // config.js:29
        assertThat(c.enableMute).isTrue()                     // config.js:30
        assertThat(c.enableTitleBan).isFalse()                // config.js:31
        assertThat(c.enableAnalysisBeforeOperation).isTrue()  // config.js:32
        assertThat(c.enableOnlyRequiredActions).isFalse()     // config.js:33
        assertThat(c.enableProtectFollowedUsers).isTrue()     // config.js:34
        assertThat(c.banPremiumIcons).isFalse()               // config.js:35
        // Deliberate divergence from config.js:36, which ships this off. The
        // extension's own default rule cannot protect anyone -- utils.js:238
        // blocks every user who matched no rule, so a decade-old account falls
        // through it and is blocked anyway. Here every enabled rule must pass,
        // and the engine resolves a missing registration date rather than
        // treating it as a reason to skip, so on is both meaningful and safe.
        assertThat(c.enableDateFilter).isTrue()
        // Deliberate parity with config.js:25-26, not an oversight. Defaulting
        // these off makes the dashboard report the client as near-dead, since
        // nobody enables telemetry by hand. See openspec/specs/android-persistence.
        assertThat(c.sendData).isTrue()
        assertThat(c.sendLog).isTrue()
    }

    /**
     * A config stored before versioning must be recognisable as old.
     *
     * The whole migration rests on absent-means-zero, so this is the assumption
     * worth pinning rather than the migration's own arithmetic.
     */
    @Test fun `a config written before versioning reads as version zero`() {
        val old = json.decodeFromString(
            EksiConfig.serializer(),
            """{"enableMute":false,"enableProtectFollowedUsers":false}""",
        )

        assertThat(old.configVersion).isEqualTo(0)
        assertThat(old.configVersion).isLessThan(EksiConfig.CURRENT_VERSION)
        // And the stored values really do beat the corrected defaults, which is
        // the reason a migration is needed at all.
        assertThat(old.enableMute).isFalse()
        assertThat(old.enableProtectFollowedUsers).isFalse()
    }

    @Test fun `the user can turn telemetry off and it persists`() {
        // Default-on only defensible if opting out actually works.
        val off = EksiConfig(sendData = false, sendLog = false)
        val back = json.decodeFromString(
            EksiConfig.serializer(),
            json.encodeToString(EksiConfig.serializer(), off),
        )
        assertThat(back.sendData).isFalse()
        assertThat(back.sendLog).isFalse()
    }

    @Test fun `a fresh install already protects decade-old accounts`() {
        val rule = EksiConfig().dateFilterRules.single()

        // The extension's values, field for field (config.js:43-55).
        assertThat(rule.id).isEqualTo("block-new-users")
        assertThat(rule.criteria).isEqualTo(DateCriteria.NEWER_THAN)
        assertThat(rule.days).isEqualTo(3650)
        assertThat(rule.enabled).isTrue()
    }

    @Test fun `the default rule spares an eleven-year-old account and acts on a young one`() {
        // The rule is only worth shipping if it decides these two the right way
        // round, which is the half a value check cannot cover.
        val rules = EksiConfig().dateFilterRules
        val today = 20_000L

        assertThat(DateFilter.allows(rules, today - 4015, today)).isFalse()  // ~11 years
        assertThat(DateFilter.allows(rules, today - 400, today)).isTrue()    // ~1 year
    }

    @Test fun `upgrading adds the rule without touching the user's own`() {
        val mine = DateFilterRule("mine", DateCriteria.OLDER_THAN, days = 30)

        val after = DateFilterRule.withDefault(listOf(mine))

        assertThat(after).containsExactly(mine, DateFilterRule.PROTECT_OLD_ACCOUNTS).inOrder()
    }

    @Test fun `upgrading twice does not stack two copies of the rule`() {
        val once = DateFilterRule.withDefault(emptyList())

        assertThat(DateFilterRule.withDefault(once)).isEqualTo(once)
    }

    @Test fun `a rule the user edited is left as they left it`() {
        // Same id, different value: matching on the id is what stops the
        // migration reverting someone's deliberate change to five years.
        val edited = DateFilterRule.PROTECT_OLD_ACCOUNTS.copy(days = 1825)

        assertThat(DateFilterRule.withDefault(listOf(edited))).containsExactly(edited)
    }

    @Test fun `rule list round trips`() {
        val c = EksiConfig(
            enableDateFilter = true,
            dateFilterRules = listOf(
                DateFilterRule("r1", DateCriteria.OLDER_THAN, days = 3650, description = "legacy"),
                DateFilterRule("r2", DateCriteria.BEFORE_DATE, epochDay = 19000, enabled = false),
            ),
        )
        val back = json.decodeFromString(EksiConfig.serializer(), json.encodeToString(EksiConfig.serializer(), c))
        assertThat(back).isEqualTo(c)
        assertThat(back.dateFilterRules).hasSize(2)
        assertThat(back.dateFilterRules[1].enabled).isFalse()
    }

    @Test fun `unknown fields are tolerated so a downgrade does not wipe config`() {
        val payload = """{"eksiSozlukUrl":"https://x.example","enableMute":true,"futureField":42}"""
        val c = json.decodeFromString(EksiConfig.serializer(), payload)
        assertThat(c.eksiSozlukUrl).isEqualTo("https://x.example")
        assertThat(c.enableMute).isTrue()
        assertThat(c.enableNoobBan).isTrue()   // absent field falls back to its default
    }

    @Test fun `an empty document yields defaults`() {
        assertThat(json.decodeFromString(EksiConfig.serializer(), "{}")).isEqualTo(EksiConfig())
    }

    @Test fun `identity round trips`() {
        val i = Identity(clientUid = "abc-123", firstRunAtMillis = 99, consentVersion = 2)
        val back = json.decodeFromString(Identity.serializer(), json.encodeToString(Identity.serializer(), i))
        assertThat(back).isEqualTo(i)
    }

    @Test fun `every date criterion survives serialisation`() {
        DateCriteria.entries.forEach { c ->
            val r = DateFilterRule("id", c)
            val back = json.decodeFromString(
                DateFilterRule.serializer(),
                json.encodeToString(DateFilterRule.serializer(), r),
            )
            assertThat(back.criteria).isEqualTo(c)
        }
    }
}
