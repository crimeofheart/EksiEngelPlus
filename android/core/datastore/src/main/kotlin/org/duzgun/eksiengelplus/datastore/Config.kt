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
     * Hides Ekşi's "open in our app" interstitial, which covers the lower part of
     * the page on mobile. Same category as banPremiumIcons: site chrome the user
     * has asked not to see. On by default because in this app the prompt is pure
     * friction -- the user already chose a client.
     */
    val hideAppPromo: Boolean = true,
    /**
     * Third-party advertising and analytics hosts are dropped.
     *
     * On by default because the measurement was decisive -- a cold start went
     * from 23.4s to 6.0s -- but a setting, because it withholds revenue from a
     * site the user chooses to use, and that is theirs to decide.
     */
    val blockAds: Boolean = true,

    /**
     * On by default, at parity with the extension (config.js:25-26).
     *
     * Deliberate, and NOT to be quietly reversed while tidying -- see
     * openspec/specs/android-persistence. Defaulting off was tried and rejected:
     * approximately nobody enables telemetry by hand, so the dashboard would
     * report this client as near-dead regardless of real usage. Hashing
     * author_list was also rejected because it empties the admin's most_banned
     * and EksiSozlukUserStatView views (api/views.py:44-65), which rank users by
     * plaintext identity.
     *
     * The consequence is carried by the Play submission: author_list holds up to
     * 10,000 identifiers belonging to people who are not users of this app, so
     * Data Safety declares User IDs as collected and not optional.
     */
    val sendData: Boolean = true,
    val sendLog: Boolean = true,

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

enum class DateCriteria {
    NEWER_THAN,
    OLDER_THAN,
    BEFORE_DATE,
    AFTER_DATE;

    /** Whether the rule is expressed as a day count rather than a calendar date. */
    val usesDays: Boolean get() = this == NEWER_THAN || this == OLDER_THAN
}

/** Install identity. Separate store: different lifetime and different sensitivity. */
@Serializable
data class Identity(
    val clientUid: String = "",
    val firstRunAtMillis: Long = 0,
    /** Bumped when the consent copy changes, so consent is re-requested. */
    val consentVersion: Int = 0,
)
