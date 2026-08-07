package org.duzgun.eksiengelplus.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which paths may never be navigated to.
 *
 * The membership of this set was measured against the live site rather than
 * guessed, and this test records the measurement: the three partials answer 500
 * to a navigation and 200 to an XHR, while /istatistik -- which looks like a
 * sibling -- is an ordinary page that intercepting would break.
 */
class XhrOnlyPathTest {

    @Test fun `the profile tab partials are xhr only`() {
        listOf("/son-entryleri", "/favori-entryleri", "/en-cok-favorilenen-entryleri").forEach {
            assertThat(isXhrOnlyPartial(it)).isTrue()
        }
    }

    @Test fun `ordinary pages are left alone`() {
        listOf("/istatistik", "/biri/ssg", "/entry/123", "/", "/giris").forEach {
            assertThat(isXhrOnlyPartial(it)).isFalse()
        }
    }

    @Test fun `a trailing slash does not smuggle one past`() {
        assertThat(isXhrOnlyPartial("/son-entryleri/")).isTrue()
    }

    @Test fun `case does not either`() {
        assertThat(isXhrOnlyPartial("/Son-Entryleri")).isTrue()
    }

    @Test fun `a null path is not a partial`() {
        assertThat(isXhrOnlyPartial(null)).isFalse()
    }
}
