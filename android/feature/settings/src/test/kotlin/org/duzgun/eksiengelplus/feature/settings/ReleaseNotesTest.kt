package org.duzgun.eksiengelplus.feature.settings

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

class ReleaseNotesTest {

    @Test fun `a known version returns its own notes`() {
        assertThat(ReleaseNotes.forVersion("0.1.7")).isNotEmpty()
        assertThat(ReleaseNotes.forVersion("0.1.7")).doesNotContain(ReleaseNotes.FALLBACK)
    }

    @Test fun `an unknown version falls back rather than returning nothing`() {
        // A blank screen after an upgrade is worse than a generic line, and a
        // release must never be blocked on someone writing a note.
        assertThat(ReleaseNotes.forVersion("9.9.9")).containsExactly(ReleaseNotes.FALLBACK)
    }

    @Test fun `a blank version falls back too`() {
        // versionName is nullable at the platform level; the caller passes "".
        assertThat(ReleaseNotes.forVersion("")).containsExactly(ReleaseNotes.FALLBACK)
    }

    /**
     * The drift guard.
     *
     * Deliberately keyed on the *shipping* version rather than on every version
     * changelog.js knows. Both artifacts carry one version number, but a release
     * whose whole content was a Firefox packaging fix has nothing to tell an
     * Android user, and demanding an entry for it would produce filler. What
     * must not happen is shipping the current version with the fallback.
     */
    @Test fun `the version being shipped has notes of its own`() {
        val version = Regex(""""version"\s*:\s*"([^"]+)"""")
            .find(File("../../version.json").canonicalFile.readText())
            ?.groupValues
            ?.get(1)

        assertWithMessage("version.json did not parse").that(version).isNotNull()
        assertWithMessage("no ReleaseNotes entry for $version -- users would see the fallback")
            .that(ReleaseNotes.has(version!!))
            .isTrue()
    }
}
