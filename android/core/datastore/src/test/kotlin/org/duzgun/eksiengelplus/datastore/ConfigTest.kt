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

    @Test fun `defaults match the extension except telemetry`() {
        val c = EksiConfig()
        assertThat(c.eksiSozlukUrl).isEqualTo("https://eksisozluk.com")
        assertThat(c.enableNoobBan).isTrue()      // config.js:29
        assertThat(c.enableMute).isFalse()
        // config.js:25-26 default BOTH of these to true. Off here pending the
        // first-run consent screen; a silent restore would be a privacy change.
        assertThat(c.sendData).isFalse()
        assertThat(c.sendLog).isFalse()
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
