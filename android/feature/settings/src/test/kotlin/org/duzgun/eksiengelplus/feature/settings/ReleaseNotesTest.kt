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
    private fun changelogJsSections(version: String): Map<ReleaseNotes.Platform, List<String>> {
        val source = File("../../../frontend/app/assets/js/changelog.js").canonicalFile.readText()
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
