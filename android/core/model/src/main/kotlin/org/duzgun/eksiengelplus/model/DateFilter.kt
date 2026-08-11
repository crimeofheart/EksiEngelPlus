package org.duzgun.eksiengelplus.model

import kotlinx.serialization.Serializable

/**
 * The user's date rules, and the predicate that applies them.
 *
 * Here rather than in `core:datastore`, where they started, because a
 * date-based bulk run carries its own rule inside its `OperationRequest` and
 * `ops:engine` is a pure-JVM module that must not depend on an Android DataStore
 * module to hold one. Nothing here touches storage; this module's build file
 * already claimed the date-filter predicates as its own.
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
