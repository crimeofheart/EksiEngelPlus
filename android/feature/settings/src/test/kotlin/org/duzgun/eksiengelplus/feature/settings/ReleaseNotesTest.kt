package org.duzgun.eksiengelplus.feature.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

class ReleaseNotesTest {

    @Test fun `a known version returns a section per platform`() {
        val sections = ReleaseNotes.forVersion("0.1.7")
        assertThat(sections.map { it.platform })
            .containsExactly(ReleaseNotes.Platform.APP, ReleaseNotes.Platform.EXTENSION)
            .inOrder()
        assertThat(sections.flatMap { it.notes }).doesNotContain(ReleaseNotes.FALLBACK)
    }

    @Test fun `a platform with nothing to report says so rather than showing empty`() {
        // 0.1.8 was an Android-only fix. The extension section has to exist and
        // has to say nothing changed -- an absent section would read as "we
        // forgot", which is the ambiguity the split exists to remove.
        val extension = ReleaseNotes.forVersion("0.1.8")
            .single { it.platform == ReleaseNotes.Platform.EXTENSION }
        assertThat(extension.notes).containsExactly(ReleaseNotes.NO_CHANGES)
    }

    @Test fun `a platform that did not exist yet is left out entirely`() {
        // Claiming "no changes to the Android app" for a release that predates
        // the app would be a statement about something that was not there.
        assertThat(ReleaseNotes.forVersion("0.1.6").map { it.platform })
            .containsExactly(ReleaseNotes.Platform.EXTENSION)
    }

    @Test fun `an unknown version falls back rather than returning nothing`() {
        // A blank screen after an upgrade is worse than a generic line, and a
        // release must never be blocked on someone writing a note.
        val sections = ReleaseNotes.forVersion("9.9.9")
        assertThat(sections.single().notes).containsExactly(ReleaseNotes.FALLBACK)
        assertThat(sections.single().platform).isNull()
    }

    @Test fun `a blank version falls back too`() {
        // versionName is nullable at the platform level; the caller passes "".
        assertThat(ReleaseNotes.forVersion("").single().notes)
            .containsExactly(ReleaseNotes.FALLBACK)
    }

    @Test fun `versions sort numerically, not lexically`() {
        assertThat(ReleaseNotes.compareVersions("0.10.0", "0.9.0")).isGreaterThan(0)
        assertThat(ReleaseNotes.compareVersions("0.5.1", "0.5.0")).isGreaterThan(0)
        assertThat(ReleaseNotes.compareVersions("0.5.0", "0.5.0")).isEqualTo(0)
        // Short and long forms of the same version are the same version.
        assertThat(ReleaseNotes.compareVersions("0.5", "0.5.0")).isEqualTo(0)
    }

    @Test fun `upgrading from an old store build shows every release since`() {
        // The case this exists for: the listing sat on 0.2.0 while four
        // releases went out, and this screen is the only place their notes are
        // ever shown.
        val since = ReleaseNotes.versionsSince("0.2.0")

        assertThat(since).contains("0.5.1")
        assertThat(since.none { ReleaseNotes.compareVersions(it, "0.2.0") <= 0 }).isTrue()
        assertThat(since).isInOrder { a, b ->
            ReleaseNotes.compareVersions(b as String, a as String)
        }
    }

    @Test fun `a fresh install is shown everything`() {
        // Blank is what claimReleaseNotes hands back on a first run.
        assertThat(ReleaseNotes.versionsSince("")).isNotEmpty()
        assertThat(ReleaseNotes.versionsSince("")).contains("0.1.0")
    }

    @Test fun `an unparseable previous version degrades to everything`() {
        assertThat(ReleaseNotes.versionsSince("bozuk"))
            .isEqualTo(ReleaseNotes.versionsSince(""))
    }

    @Test fun `a release written but not yet shipped is never announced`() {
        // An entry can land before its version does. Running 0.4.0 must not
        // reveal 0.5.0's notes.
        assertThat(ReleaseNotes.versionsToShow("0.4.0", "0.4.0")).containsExactly("0.4.0")
        assertThat(ReleaseNotes.versionsToShow("0.4.0", "0.2.0"))
            .doesNotContain("0.5.0")
    }

    @Test fun `the Settings entry point shows one version`() {
        // It re-opens the notes for what is running, not a history of upgrades.
        assertThat(ReleaseNotes.versionsToShow("0.5.1", "0.5.1")).containsExactly("0.5.1")
    }

    @Test fun `the range matches the extension's for the same input`() {
        // Feature parity is the point: both clients answer "what have I not
        // seen" the same way, so the two screens cannot drift apart.
        val fromJs = changelogJsVersionsSince("0.2.0")
        assertWithMessage("versionsSince disagrees with getVersionsSince in changelog.js")
            .that(ReleaseNotes.versionsSince("0.2.0"))
            .isEqualTo(fromJs)
    }

    /**
     * The versions changelog.js lists after [previous], newest first.
     *
     * Parsed out of the source the same way [changelogJsSections] does, rather
     * than running the JS: the point is that the two files agree, and a test
     * that needed node to say so would be skipped wherever node is absent.
     */
    private fun changelogJsVersionsSince(previous: String): List<String> =
        Regex("^  \"([0-9.]+)\":", RegexOption.MULTILINE)
            .findAll(changelogJs())
            .map { it.groupValues[1] }
            .filter { ReleaseNotes.compareVersions(it, previous) > 0 }
            .sortedWith { a, b -> ReleaseNotes.compareVersions(b, a) }
            .toList()

    /**
     * The drift guard.
     *
     * Keyed on the *shipping* version. Both artifacts carry one version number,
     * and now that every shipped version has an entry here -- with an explicit
     * empty section where a platform saw no change -- the fallback is only ever
     * correct for a version that does not exist. Shipping one that hits it is
     * the failure this catches.
     */
    @Test fun `the version being shipped has notes of its own`() {
        assertWithMessage("no ReleaseNotes entry for $shippingVersion -- users would see the fallback")
            .that(ReleaseNotes.has(shippingVersion))
            .isTrue()
    }

    /**
     * The copy guard.
     *
     * changelog.js and this file are two hand-maintained statements of the same
     * release, in two languages neither of which can import the other. Nothing
     * stops them disagreeing except this: for the version actually going out,
     * every platform section must match the original word for word.
     *
     * Only the shipping version is compared. Backfilling the whole history would
     * turn a wording fix on an old note into a build failure, which is not what
     * this is protecting.
     */
    @Test fun `the shipping version matches changelog js word for word`() {
        val fromJs = changelogJsSections(shippingVersion)
        assertWithMessage("changelog.js has no entry for $shippingVersion")
            .that(fromJs).isNotEmpty()

        for ((platform, expected) in fromJs) {
            assertWithMessage("$platform notes for $shippingVersion differ from changelog.js")
                .that(ReleaseNotes.linesFor(shippingVersion, platform))
                .isEqualTo(expected)
        }

        // And nothing here that is missing there.
        val here = ReleaseNotes.Platform.entries
            .filter { ReleaseNotes.linesFor(shippingVersion, it) != null }
        assertWithMessage("platforms listed here but not in changelog.js")
            .that(here).containsExactlyElementsIn(fromJs.keys)
    }

    // ---------------------------------------------------------------- sources

    private val shippingVersion: String by lazy {
        val version = Regex(""""version"\s*:\s*"([^"]+)"""")
            .find(File("../../version.json").canonicalFile.readText())
            ?.groupValues
            ?.get(1)
        assertWithMessage("version.json did not parse").that(version).isNotNull()
        version!!
    }

    /**
     * Reads one version's platform lists out of the JS object literal.
     *
     * A parser rather than a substring search, because a wrong answer here is
     * worse than no answer: silently matching the wrong version's block would
     * turn the guard into a test that passes for the wrong reason. It brackets
     * the version's `{ ... }` by counting braces, then takes each platform's
     * `[ ... ]` the same way, and fails loudly if the shape is not what it
     * expects.
     */
    /** The one copy of the path, so the two readers cannot drift apart. */
    private fun changelogJs(): String =
        File("../../../frontend/app/assets/js/changelog.js").canonicalFile.readText()

    private fun changelogJsSections(version: String): Map<ReleaseNotes.Platform, List<String>> {
        val source = changelogJs()
        val block = balanced(source, """"${Regex.escape(version)}"\s*:\s*\{""", '{', '}')
            ?: return emptyMap()

        val names = mapOf(
            ReleaseNotes.Platform.APP to "app",
            ReleaseNotes.Platform.EXTENSION to "extension",
        )
        return buildMap {
            for ((platform, key) in names) {
                val list = balanced(block, """(^|[\s,{])$key\s*:\s*\[""", '[', ']') ?: continue
                put(platform, quotedStrings(list))
            }
        }
    }

    /** The text between [open] and its matching [close], after [prefix]. */
    private fun balanced(source: String, prefix: String, open: Char, close: Char): String? {
        val start = Regex(prefix).find(source)?.range?.last ?: return null
        var depth = 1
        var i = start + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        assertWithMessage("unbalanced $open in changelog.js").that(depth).isEqualTo(0)
        return source.substring(start + 1, i - 1)
    }

    /** The double-quoted strings in a JS array literal, in order. */
    private fun quotedStrings(list: String): List<String> =
        Regex(""""((?:[^"\\]|\\.)*)"""")
            .findAll(list)
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .toList()
}
