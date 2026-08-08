package org.duzgun.eksiengelplus.datastore

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
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
        assertThat(c.enableDateFilter).isFalse()              // config.js:36
        // Deliberate parity with config.js:25-26, not an oversight. Defaulting
        // these off makes the dashboard report the client as near-dead, since
        // nobody enables telemetry by hand. See openspec/specs/android-persistence.
        assertThat(c.sendData).isTrue()
        assertThat(c.sendLog).isTrue()
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
