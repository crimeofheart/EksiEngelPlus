package org.duzgun.eksiengelplus.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ported from frontend/app/assets/js/config.js.
 *
 * Defaults are taken from config.js:25-36 and verified against it, not assumed.
 * enableMute and enableProtectFollowedUsers were both wrong here until that
 * check was actually done. The
 * shared API key is deliberately absent: it belongs in BuildConfig, not in a file
 * the user's backup could carry off the device.
 */
@Serializable
data class EksiConfig(
    /**
     * Bumped when a stored config needs correcting on upgrade.
     *
     * Zero for anything written before versioning existed, which is exactly the
     * set that needs the fix, so absent-means-old is the behaviour we want.
     */
    val configVersion: Int = 0,

    val eksiSozlukUrl: String = DEFAULT_BASE_URL,

    val enableMute: Boolean = true,
    val enableTitleBan: Boolean = false,
    val enableNoobBan: Boolean = true,
    val enableProtectFollowedUsers: Boolean = true,
    val enableOnlyRequiredActions: Boolean = false,
    /** config.js:32. Checks the existing relation before acting on it. */
    val enableAnalysisBeforeOperation: Boolean = true,
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

    /**
     * On, unlike the extension, and only because this client can honour it.
     *
     * config.js seeds the same rule with the filter switched off, and switching
     * it on there protects nobody anyway: utils.js:238 blocks every user who
     * matched no rule, so an account older than ten years falls through the
     * default rule and is blocked regardless. The rule reads as protection and
     * is not.
     *
     * Here the semantics are the ones the rule describes -- every enabled rule
     * must pass -- so defaulting it on means a fresh install does not touch
     * decade-old accounts. That is only safe because the engine now resolves a
     * missing registration date instead of treating it as a reason to skip
     * everyone; see OperationWorker's allowTarget.
     */
    val enableDateFilter: Boolean = true,
    val dateFilterRules: List<DateFilterRule> = listOf(DateFilterRule.PROTECT_OLD_ACCOUNTS),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://eksisozluk.com"

        /** Raise this, and add a step to ConfigRepository.migrate, together. */
        const val CURRENT_VERSION = 2
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
) {
    companion object {
        /**
         * The default rule, ported from config.js:43-55.
         *
         * Same id, same criteria, same 3650 days, and the same sentence, so a
         * user who has seen the extension's settings recognises this one.
         *
         * The boundary differs by a day on purpose-free grounds: the extension
         * matches `age < 3650` and DateFilter uses `age <= days`, so an account
         * exactly 3650 days old is acted on here and spared there. Left as it
         * is rather than churned, because a rule about decades should not turn
         * on which side of one midnight a comparison falls.
         */
        val PROTECT_OLD_ACCOUNTS = DateFilterRule(
            id = "block-new-users",
            criteria = DateCriteria.NEWER_THAN,
            days = 3650,
            description = "Yapılacak işlem 10 yıldan yeni hesapları kapsar",
        )

        /**
         * The rule list an upgrading install should end up with.
         *
         * Appended, never assigned: an existing install may have rules of its
         * own, and introducing a default by replacing the list would throw away
         * the user's work. Keyed on the id so it cannot be added twice.
         *
         * A function rather than three lines inside the migration so the part
         * that can lose data is testable without a DataStore.
         */
        fun withDefault(rules: List<DateFilterRule>): List<DateFilterRule> =
            if (rules.any { it.id == PROTECT_OLD_ACCOUNTS.id }) rules
            else rules + PROTECT_OLD_ACCOUNTS
    }
}

enum class DateCriteria {
    NEWER_THAN,
    OLDER_THAN,
    BEFORE_DATE,
    AFTER_DATE;

    /** Whether the rule is expressed as a day count rather than a calendar date. */
    val usesDays: Boolean get() = this == NEWER_THAN || this == OLDER_THAN
}

/**
 * How config is serialised for the page.
 *
 * encodeDefaults is the whole point. Kotlin's default omits any field equal to
 * its default, so a value that happens to match ships without the field at all
 * and the page reads undefined -- which is falsy, so every menu quietly reverted
 * to "engelle" the day true became the default of enableMute.
 *
 * Named and tested here rather than constructed at the call site, so the
 * property belongs to the format instead of to whoever remembered.
 */
object BridgeConfigJson {
    val json: Json = Json { encodeDefaults = true }

    fun encode(config: EksiConfig): String = json.encodeToString(EksiConfig.serializer(), config)
}

/**
 * Whether a registration date passes the user's rules.
 *
 * Pure, so the decision is testable without a device, a store or a network.
 *
 * An unknown date does NOT pass. A filter exists to keep accounts out of a bulk
 * run, so acting on one whose date could not be established would defeat the
 * only reason it was switched on. Dates arrive from the CSV import and the list
 * sync, both of which fill the cache.
 */
object DateFilter {

    fun allows(rules: List<DateFilterRule>, registrationEpochDay: Long?, todayEpochDay: Long): Boolean {
        val active = rules.filter { it.enabled }
        if (active.isEmpty()) return true
        if (registrationEpochDay == null) return false
        return active.all { it.allows(registrationEpochDay, todayEpochDay) }
    }

    private fun DateFilterRule.allows(regDay: Long, today: Long): Boolean {
        val age = today - regDay
        return when (criteria) {
            DateCriteria.NEWER_THAN -> days?.let { age <= it } ?: true
            DateCriteria.OLDER_THAN -> days?.let { age >= it } ?: true
            DateCriteria.BEFORE_DATE -> epochDay?.let { regDay < it } ?: true
            DateCriteria.AFTER_DATE -> epochDay?.let { regDay > it } ?: true
        }
    }
}

/** Install identity. Separate store: different lifetime and different sensitivity. */
@Serializable
data class Identity(
    val clientUid: String = "",
    val firstRunAtMillis: Long = 0,
    /** Bumped when the consent copy changes, so consent is re-requested. */
    val consentVersion: Int = 0,
    /**
     * Who the user is on Ekşi Sözlük, once resolved.
     *
     * Reporting requires it -- the backend keys every action to an
     * eksi_engel_user -- and resolving it costs a homepage fetch plus a profile
     * fetch, so it is cached here exactly as commHandler.js caches it in
     * chrome.storage. Blank means "not resolved yet", which is also what a
     * logged-out install looks like.
     */
    val eksiNick: String = "",
    val eksiUserId: Long = 0,
)
