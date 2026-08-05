package org.duzgun.eksiengelplus.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * These integers are primary keys in the shared backend's lookup tables, holding
 * rows the extension already wrote. This suite exists so a rename or reorder
 * cannot silently reinterpret historical telemetry.
 */
class EnumsTest {

    @Test
    fun `ban source pks match enums js exactly`() {
        val expected = mapOf(
            BanSource.SINGLE to 1, BanSource.FAV to 2, BanSource.FOLLOW to 3,
            BanSource.LIST to 4, BanSource.UNDOBANALL to 5, BanSource.TITLE to 6,
            BanSource.BLOCKED_MUTED_TITLES to 7, BanSource.MIGRATE_BLOCKED_TO_MUTED to 8,
            BanSource.BLOCK_MUTED_USERS to 9, BanSource.REFRESH_MUTED_LIST to 10,
            BanSource.REFRESH_BLOCKED_LIST to 11, BanSource.DATE_BASED_BULK to 12,
            BanSource.UNMUTEALL to 13, BanSource.REFRESH_FOLLOWED_LIST to 14,
        )
        expected.forEach { (e, pk) -> assertWithMessage(e.name).that(e.pk).isEqualTo(pk) }
        assertThat(BanSource.entries.map { it.pk }).containsNoDuplicates()
    }

    @Test
    fun `target type relation codes match the mutation endpoint`() {
        assertThat(TargetType.USER.relationCode).isEqualTo("m")
        assertThat(TargetType.TITLE.relationCode).isEqualTo("i")
        assertThat(TargetType.MUTE.relationCode).isEqualTo("u")
        assertThat(TargetType.FOLLOW.relationCode).isEqualTo("b")
    }

    @Test
    fun `ban mode maps to the right url segment`() {
        assertThat(BanMode.BAN.urlSegment).isEqualTo("addrelation")
        assertThat(BanMode.UNDOBAN.urlSegment).isEqualTo("removerelation")
    }

    @Test
    fun `log level keeps the client mapping not the server seed`() {
        // Server seeds 1=DEBUG (api/migrations/0007_seed_lookup_data.py:38); every
        // client sends 1=DISABLED. Divergence is deliberate -- see LogLevel's kdoc.
        assertThat(LogLevel.DISABLED.pk).isEqualTo(1)
        assertThat(LogLevel.INFO.pk).isEqualTo(2)
        assertThat(LogLevel.WARN.pk).isEqualTo(3)
        assertThat(LogLevel.ERR.pk).isEqualTo(4)
    }

    @Test
    fun `round trips through pk`() {
        BanSource.entries.forEach { assertThat(BanSource.fromPk(it.pk)).isEqualTo(it) }
        TargetType.entries.forEach { assertThat(TargetType.fromPk(it.pk)).isEqualTo(it) }
        TimeSpecifier.entries.forEach { assertThat(TimeSpecifier.fromPk(it.pk)).isEqualTo(it) }
        assertThat(BanSource.fromPk(99)).isNull()
    }
}
