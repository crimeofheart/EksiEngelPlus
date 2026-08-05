package org.duzgun.eksiengelplus.datastore

import kotlinx.serialization.Serializable

/**
 * Ported from frontend/app/assets/js/config.js.
 *
 * Defaults match the extension EXCEPT the two telemetry flags -- see below. The
 * shared API key is deliberately absent: it belongs in BuildConfig, not in a file
 * the user's backup could carry off the device.
 */
@Serializable
data class EksiConfig(
    val eksiSozlukUrl: String = DEFAULT_BASE_URL,

    val enableMute: Boolean = false,
    val enableTitleBan: Boolean = false,
    val enableNoobBan: Boolean = true,
    val enableProtectFollowedUsers: Boolean = false,
    val enableOnlyRequiredActions: Boolean = false,
    val banPremiumIcons: Boolean = false,

    /**
     * The extension defaults both of these to true (config.js:25-26), shipping the
     * user's own nick plus up to 10,000 target nicks by default.
     *
     * Off here, pending the first-run consent screen. Flipping them is a product
     * decision recorded in the plan, and it materially de-risks the Play data
     * safety declaration. Do not silently restore the extension's default.
     */
    val sendData: Boolean = false,
    val sendLog: Boolean = false,

    val enableDateFilter: Boolean = false,
    val dateFilterRules: List<DateFilterRule> = emptyList(),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://eksisozluk.com"
    }
}

/**
 * Structured and repeated, which is exactly why this cannot live in a
 * Preferences DataStore.
 */
@Serializable
data class DateFilterRule(
    val id: String,
    val criteria: DateCriteria,
    /** Meaningful for NEWER_THAN and OLDER_THAN. */
    val days: Int? = null,
    /** Epoch day; meaningful for BEFORE_DATE and AFTER_DATE. */
    val epochDay: Long? = null,
    val description: String = "",
    val enabled: Boolean = true,
)

enum class DateCriteria { NEWER_THAN, OLDER_THAN, BEFORE_DATE, AFTER_DATE }

/** Install identity. Separate store: different lifetime and different sensitivity. */
@Serializable
data class Identity(
    val clientUid: String = "",
    val firstRunAtMillis: Long = 0,
    /** Bumped when the consent copy changes, so consent is re-requested. */
    val consentVersion: Int = 0,
)
