package org.duzgun.eksiengelplus

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Parity with the extension, checked against the extension.
 *
 * The app and the extension share a backend and a user, so a setting that
 * differs or a source that is missing is a real divergence rather than a
 * cosmetic one -- and both have happened: enableMute and
 * enableProtectFollowedUsers shipped false here while config.js had them true,
 * and OpsModule returned null for six of the fourteen ban sources.
 *
 * These read the extension's own files, so the extension changing is what makes
 * them fail. A hand-maintained list would drift exactly like the code did.
 */
class ParityTest {

    private val repo = File("../..").canonicalFile
    private fun read(path: String) = File(repo, path).readText()

    // ------------------------------------------------------------- settings

    /** Every default in config.js, as name to literal. */
    private fun extensionDefaults(): Map<String, String> =
        Regex(""""(\w+)"\s*:\s*(true|false)""")
            .findAll(read("frontend/app/assets/js/config.js"))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun androidDefaults(): Map<String, String> =
        Regex("""val\s+(\w+)\s*:\s*Boolean\s*=\s*(true|false)""")
            .findAll(read("android/core/datastore/src/main/kotlin/org/duzgun/eksiengelplus/datastore/Config.kt"))
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * Settings the app deliberately does not carry.
     *
     * Named rather than silently absent, so dropping one is a decision someone
     * wrote down instead of an omission nobody noticed.
     */
    private val notPorted = setOf(
        "enableLog",     // console logging; the app logs through logcat
        "logConsole",    // same
    )

    /**
     * Settings the app deliberately defaults differently, and why.
     *
     * Separate from [notPorted] because the field exists and is compared for
     * everything else -- only the default is allowed to differ, and only with a
     * reason written here. Adding a name to this set is the decision; leaving it
     * out is what makes drift fail the build.
     */
    private val deliberatelyDifferentDefault = mapOf(
        "enableDateFilter" to
            "On here, off in config.js:36. The extension's default rule cannot " +
            "protect anyone -- utils.js:238 blocks every user who matched no " +
            "rule, so a decade-old account falls through it and is blocked " +
            "anyway. This client requires every enabled rule to pass and " +
            "resolves a missing registration date rather than skipping, so the " +
            "rule does what it says and is safe to have on. Safe also because " +
            "the rules narrow only a run that restricts someone -- see " +
            "activeDateRules. Gating undos with them turned the default rule " +
            "into a reason to leave decade-old accounts blocked.",
    )

    @Test fun `every extension setting exists here`() {
        val missing = extensionDefaults().keys - androidDefaults().keys - notPorted

        assertWithMessage("settings in config.js with no EksiConfig field")
            .that(missing)
            .isEmpty()
    }

    @Test fun `and carries the same default`() {
        val android = androidDefaults()
        val differing = extensionDefaults()
            .filterKeys { it in android.keys }
            .filterKeys { it !in deliberatelyDifferentDefault }
            .filter { (name, value) -> android[name] != value }

        assertWithMessage("defaults that disagree with config.js")
            .that(differing)
            .isEmpty()
    }

    @Test fun `a divergence is only excused while it is still a divergence`() {
        // Otherwise the exemption outlives the reason for it: config.js changing
        // to match would leave a permanent licence to differ, silently.
        val android = androidDefaults()
        val agreeing = extensionDefaults()
            .filterKeys { it in deliberatelyDifferentDefault }
            .filter { (name, value) -> android[name] == value }

        assertWithMessage("exempted in deliberatelyDifferentDefault but no longer differing")
            .that(agreeing.keys)
            .isEmpty()
    }

    // ---------------------------------------------------------- ban sources

    @Test fun `every ban source the extension defines is handled`() {
        val sources = Regex("""(\w+)\s*:\s*\d+""")
            .findAll(read("frontend/app/assets/js/enums.js").substringAfter("BanSource").substringBefore("}"))
            .map { it.groupValues[1] }
            .toSet()

        val factory = read("android/ops/runtime/src/main/kotlin/org/duzgun/eksiengelplus/ops/runtime/di/OpsModule.kt")
        val unhandled = sources.filterNot { factory.contains("BanSource.$it") }

        assertWithMessage("ban sources with no branch in the task factory")
            .that(unhandled)
            .isEmpty()
    }

    // ------------------------------------------------------- date-based bulk

    /**
     * The date-based chooser offers what the extension's form offers.
     *
     * It offered three fixed combinations, and the source was a label only --
     * the task factory scraped the blocked list whichever one was picked, so
     * "sessiz kullanıcılar" sent removerelation r=u against people who had never
     * been muted. Names are matched verbatim against enums.js, which is why the
     * Kotlin enum keeps the extension's Turkish spellings.
     */
    @Test fun `every date-bulk source and action the extension defines exists here`() {
        val enums = read("frontend/app/assets/js/enums.js")
        val model = read(
            "android/core/model/src/main/kotlin/org/duzgun/eksiengelplus/model/DateBulk.kt",
        )

        fun namesIn(enumName: String) = Regex("""(\w+)\s*:\s*"""")
            .findAll(enums.substringAfter("$enumName = {").substringBefore("}"))
            .map { it.groupValues[1] }
            .toSet()

        val declared = namesIn("DateBulkSource") + namesIn("DateBulkAction")

        // Otherwise a rename in enums.js empties the set and the check passes by
        // finding nothing to compare, which is the failure mode of every test
        // that greps a file it does not own.
        assertWithMessage("enums.js parsed to no date-bulk names at all")
            .that(declared)
            .hasSize(11)

        val missing = declared.filterNot { Regex("""\b$it\s*\(""").containsMatchIn(model) }

        assertWithMessage("date-bulk names in enums.js with no Kotlin counterpart")
            .that(missing)
            .isEmpty()
    }

    /**
     * And the chooser can actually reach them.
     *
     * The enum existing is not the feature: the previous version had every
     * BanSource and still offered three of the twelve source-action pairs.
     */
    @Test fun `the chooser lists every source and every action`() {
        val activity = read(
            "android/feature/lists/src/main/kotlin/org/duzgun/eksiengelplus/feature/lists/ListsActivity.kt",
        )

        val unreachable =
            org.duzgun.eksiengelplus.model.DateBulkSource.entries.map { "DateBulkSource.${it.name}" }
                .plus(org.duzgun.eksiengelplus.model.DateBulkAction.entries.map { "DateBulkAction.${it.name}" })
                .filterNot { activity.contains(it) }

        assertWithMessage("choices with no row in the chooser")
            .that(unreachable)
            .isEmpty()
    }

    // ---------------------------------------------------- browsing surface

    /**
     * A relation can be taken away from the page it is shown on.
     *
     * bridge.js hardcoded BanMode.BAN in all seven of its enqueues, so the whole
     * browsing surface could only ever add. It read the two attributes that carry
     * the state -- data-add-caption and data-added, script.js:475-516 -- and used
     * them as an existence gate. A profile of someone already blocked offered a
     * button reading "engelle", and re-sending a block that exists returns 2,
     * which RelationClient counts as success.
     *
     * Asserted against the source rather than through the WebView because the
     * instrumented cases need a device, and this is the regression that must not
     * reach one.
     */
    @Test fun `the browsing surface can undo a relation, not only add one`() {
        val bridge = read("android/webview/src/main/assets/bridge.js")

        assertWithMessage("bridge.js never enqueues an undo")
            .that(bridge)
            .contains("BanMode.UNDOBAN")

        assertWithMessage("the relation's state is not read from the page")
            .that(bridge)
            .contains("data-added")
    }

    // ------------------------------------------------------- author actions

    @Test fun `the author list offers what the extension's page offers`() {
        val buttons = Regex("""id="(start\w+)"""")
            .findAll(read("frontend/app/assets/html/authorListPage.html"))
            .map { it.groupValues[1] }
            .toSet()

        // Each extension button maps to a run action in the dialog.
        val activity = read(
            "android/feature/lists/src/main/kotlin/org/duzgun/eksiengelplus/feature/lists/AuthorListActivity.kt",
        )
        val expected = mapOf(
            "startBan" to "runBlock",
            "startUndoban" to "runUnblock",
            "startFollow" to "runFollow",
            "startUnfollow" to "runUnfollow",
            "startUnblockFollow" to "runUnblockFollow",
            "startUnmuteFollow" to "runUnmuteFollow",
        )

        val missing = buttons.mapNotNull { expected[it] }.filterNot { activity.contains(it) }

        assertWithMessage("author list actions present in the extension but not here")
            .that(missing)
            .isEmpty()
    }

    // ----------------------------------------------------------- app hygiene

    /**
     * textAllCaps uppercases with the default locale, and Turkish maps i to İ,
     * so the platform rendered "işlem" as "IŞLEM".
     */
    @Test fun `no layout or style asks the platform to uppercase Turkish`() {
        val offenders = File(repo, "android").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && "/build/" !in it.path }
            .filter { it.readText().contains("textAllCaps\">true") || it.readText().contains("textAllCaps=\"true\"") }
            .map { it.relativeTo(repo).path }
            .toList()

        assertThat(offenders).isEmpty()
    }

    /**
     * A table nothing writes to is a feature that silently does nothing.
     *
     * queued_task, completed_operation and telemetry_outbox each existed for
     * weeks with no writer: operations were dropped instead of queued, history
     * was empty by construction, and telemetry never left the device.
     */
    /**
     * Two mechanisms for one padding is worse than the broken one.
     *
     * `fitsSystemWindows` stopped covering the navigation bar at targetSdk 35
     * and is ignored outright at 36, so the activities now apply the insets
     * themselves. If a layout brings the attribute back, both fire on any
     * platform where the old one still works and the screen pads twice -- on
     * devices the developer does not have.
     */
    @Test fun `no layout declares fitsSystemWindows`() {
        val offenders = File(repo, "android").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && "/build/" !in it.path }
            // The spike harness is a separate application that never ships and
            // does not depend on core:ui, so taking the attribute away there
            // would leave it with nothing.
            .filterNot { "/devharness/" in it.path }
            .filter { it.readText().contains("fitsSystemWindows") }
            .map { it.relativeTo(repo).path }
            .toList()

        assertWithMessage("the activities apply insets; a layout must not also")
            .that(offenders)
            .isEmpty()
    }

    /**
     * A DAO method nothing calls is a policy nobody enforces.
     *
     * `trimExpired` and `size` sat on RegistrationDateCacheDao with no caller
     * for the cache's whole life. Every read filtered expired rows out, so the
     * 30-day TTL looked implemented and was in fact only a read filter: nothing
     * ever deleted a row, and an author list may carry 10,000 nicks. It passed
     * every test, because the tests called the methods.
     *
     * Narrower than "every DAO method", deliberately. Some exist for one caller
     * that has not been written yet, and a blanket rule here would be answered
     * by deleting the check rather than by wiring the method.
     */
    @Test fun `maintenance methods are reachable from production code`() {
        val production = File(repo, "android").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
            .filter { "/src/main/" in it.path }
            .filterNot { "/database/" in it.path }   // the declarations themselves
            .joinToString("\n") { it.readText() }

        val required = listOf(
            "trimExpired",     // bounds the registration-date cache
            "expiredCount",    // reports what a prune would delete
            "liveCount",       // guards destructive maintenance
        )

        val orphaned = required.filterNot { production.contains("$it(") }

        assertWithMessage("declared but called only from tests")
            .that(orphaned)
            .isEmpty()
    }

    @Test fun `every table has a writer and a reader`() {
        val production = File(repo, "android").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
            .filter { "/src/main/" in it.path }
            .filterNot { "/database/" in it.path }   // the DAOs themselves are not use
            .joinToString("\n") { it.readText() }

        val accessors = mapOf(
            "relationUsers" to "relation_user",
            "listSyncState" to "list_sync_state",
            "registrationDates" to "registration_date_cache",
            "queuedTasks" to "queued_task",
            "checkpoints" to "operation_checkpoint",
            "completedOperations" to "completed_operation",
            "authorList" to "author_list",
        )

        val unused = accessors.filterNot { (accessor, _) -> production.contains("$accessor()") }

        assertWithMessage("tables no production code touches")
            .that(unused.values)
            .isEmpty()
    }
}
