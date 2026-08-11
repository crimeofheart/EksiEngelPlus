package org.duzgun.eksiengelplus.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * What each choice in the date-based chooser means.
 *
 * Asserted as a table rather than one case per enum, because the failure this
 * guards against is a plausible-looking mapping — the previous version sent
 * `removerelation r=u` against the blocked list, and every individual piece of
 * it read correctly.
 */
class DateBulkTest {

    @Test fun `each source names the list it walks`() {
        assertThat(DateBulkSource.BLOCKED_USERS.relationList).isEqualTo(TargetType.USER)
        assertThat(DateBulkSource.MUTED_USERS.relationList).isEqualTo(TargetType.MUTE)
        // Null is the marker for "resolved to nicks, nothing to scrape".
        assertThat(DateBulkSource.AUTHOR_LIST.relationList).isNull()
    }

    @Test fun `each action names the relation it applies`() {
        val expected = mapOf(
            DateBulkAction.ENGELLE to Triple(BanMode.BAN, TargetType.USER, null),
            DateBulkAction.SESSIZE_AL to Triple(BanMode.BAN, TargetType.MUTE, null),
            DateBulkAction.ENGEL_KALDIR to Triple(BanMode.UNDOBAN, TargetType.USER, null),
            DateBulkAction.SESSIZDEN_CIKAR to Triple(BanMode.UNDOBAN, TargetType.MUTE, null),
            DateBulkAction.TAKIP_ET to Triple(BanMode.BAN, TargetType.FOLLOW, null),
            DateBulkAction.TAKIPTEN_CIKAR to Triple(BanMode.UNDOBAN, TargetType.FOLLOW, null),
            DateBulkAction.ENGEL_KALDIR_VE_TAKIP_ET to
                Triple(BanMode.UNDOBAN, TargetType.USER, TargetType.FOLLOW),
            DateBulkAction.SESSIZDEN_CIKAR_VE_TAKIP_ET to
                Triple(BanMode.UNDOBAN, TargetType.MUTE, TargetType.FOLLOW),
        )

        assertWithMessage("an action with no expectation written down")
            .that(DateBulkAction.entries.toSet() - expected.keys)
            .isEmpty()

        expected.forEach { (action, want) ->
            assertWithMessage(action.name)
                .that(Triple(action.mode, action.target, action.then))
                .isEqualTo(want)
        }
    }

    /**
     * Only the two combined actions carry a second relation. A stray `then`
     * anywhere else would follow people the user only asked to unblock.
     */
    @Test fun `only the combined actions apply a second relation`() {
        val combined = DateBulkAction.entries.filter { it.then != null }
        assertThat(combined).containsExactly(
            DateBulkAction.ENGEL_KALDIR_VE_TAKIP_ET,
            DateBulkAction.SESSIZDEN_CIKAR_VE_TAKIP_ET,
        )
        assertThat(combined.map { it.then }).containsExactly(TargetType.FOLLOW, TargetType.FOLLOW)
    }
}
