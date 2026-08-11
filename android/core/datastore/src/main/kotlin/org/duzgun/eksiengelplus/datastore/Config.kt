package org.duzgun.eksiengelplus.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.model.DateBulkAction
import org.duzgun.eksiengelplus.model.DateBulkSource
import org.duzgun.eksiengelplus.model.DateCriteria
import org.duzgun.eksiengelplus.model.DateFilterRule

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
     * must pass -- so defaulting it on means a fresh install does not block
     * decade-old accounts. That is only safe because the engine now resolves a
     * missing registration date instead of treating it as a reason to skip
     * everyone; see OperationWorker's allowTarget.
     *
     * "Does not block", not "does not touch": these rules narrow only a run that
     * restricts someone. Gating every operation with them made the default rule
     * spare decade-old accounts from "tüm engelleri kaldır" too, which left them
     * blocked -- protection pointed at the one direction that needed none. See
     * activeDateRules.
     */
    val enableDateFilter: Boolean = true,
    val dateFilterRules: List<DateFilterRule> = listOf(DateFilterRule.PROTECT_OLD_ACCOUNTS),

    /**
     * What the date-based bulk chooser was last set to.
     *
     * Config rather than a rule, and that distinction is the point:
     * [dateFilterRules] is standing protection applied to every operation, while
     * this is the last thing the user typed into one dialog. Remembering a
     * composition must never add to the rules.
     *
     * Defaulted, so no CURRENT_VERSION bump and no migration step -- an install
     * written before this field existed decodes with these values.
     */
    val dateBulk: DateBulkPrefs = DateBulkPrefs(),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://eksisozluk.com"

        /** Raise this, and add a step to ConfigRepository.migrate, together. */
        const val CURRENT_VERSION = 2
    }
}

/**
 * The chooser's last composition, defaulted to the extension's
 * createDefaultDateBulkConfig (config.js:58-66) so someone arriving from it
 * finds the dialog already set the way they left the other one.
 *
 * The unit is not stored. The extension keeps a `lastValueType` beside its
 * value; here months and years are normalised to days on the way in, because
 * [DateFilterRule.days] is what the predicate compares and two representations
 * of one number is how they come to disagree.
 */
@Serializable
data class DateBulkPrefs(
    val source: DateBulkSource = DateBulkSource.MUTED_USERS,
    val criteria: DateCriteria = DateCriteria.OLDER_THAN,
    val days: Int = 3650,
    /** Epoch day, for the two calendar criteria. Null until one is used. */
    val epochDay: Long? = null,
    val action: DateBulkAction = DateBulkAction.SESSIZDEN_CIKAR,
) {
    /**
     * The rule this composition means, or null when it names no boundary.
     *
     * Null rather than a permissive rule, because a rule with no value passes
     * everyone: [DateFilter] treats a missing `days` or `epochDay` as "this rule
     * does not apply", so an unset calendar criterion would quietly turn a
     * filtered run into a run over the whole list.
     *
     * The id is fixed and not the settings rule's, so a per-run rule can never
     * be confused with the standing one if it is ever written somewhere.
     */
    fun toRule(): DateFilterRule? = when {
        criteria.usesDays -> if (days > 0) {
            DateFilterRule(id = RUN_RULE_ID, criteria = criteria, days = days)
        } else {
            null
        }
        else -> epochDay?.let {
            DateFilterRule(id = RUN_RULE_ID, criteria = criteria, epochDay = it)
        }
    }

    companion object {
        const val RUN_RULE_ID = "date-bulk-run"
    }
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
    /**
     * The version whose release notes have already been shown.
     *
     * Blank on a fresh install, which differs from every real version, so a new
     * install sees the notes for what it installed rather than nothing.
     */
    val lastNotesVersion: String = "",
)
