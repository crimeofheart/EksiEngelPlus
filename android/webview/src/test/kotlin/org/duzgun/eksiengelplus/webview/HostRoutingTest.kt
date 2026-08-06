package org.duzgun.eksiengelplus.webview

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Getting this wrong is not a cosmetic bug: handing an Ekşi URL to the system
 * opens the browser, which Android app-links then forward to the OFFICIAL Ekşi
 * app. A tap inside this client would silently land the user in a different one.
 */
class HostRoutingTest {

    @Test fun `every Ekşi host stays in the app`() {
        listOf(
            "eksisozluk.com",
            "www.eksisozluk.com",
            "m.eksisozluk.com",
            // Mirrors exist because the site is periodically blocked in Turkey,
            // so an exact-match list would leak exactly when it matters most.
            "eksisozluk1923.com",
            "eksisozluk2023.com",
            "static.eksisozluk.com",
        ).forEach { assertWithMessage(it).that(isEksiHost(it)).isTrue() }
    }

    @Test fun `unrelated hosts do not`() {
        listOf(
            "google.com",
            "twitter.com",
            "youtube.com",
            "example.org",
            "eksi.com",          // similar, but not the site
            "sozluk.com",
        ).forEach { assertWithMessage(it).that(isEksiHost(it)).isFalse() }
    }

    @Test fun `matching ignores case`() {
        assertWithMessage("EksiSozluk.COM").that(isEksiHost("EksiSozluk.COM")).isTrue()
    }
}
