package org.duzgun.eksiengelplus.webview

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Getting this wrong is not a cosmetic bug: handing an Ekşi URL to the system
 * opens the browser, which Android app-links then forward to the OFFICIAL Ekşi
 * app. A tap inside this client would silently land the user in a different one.
 */
class HostRoutingTest {

    @Test fun `every Ekşi property stays in the app`() {
        listOf(
            "eksisozluk.com",
            "www.eksisozluk.com",
            "m.eksisozluk.com",
            "static.eksisozluk.com",
            // Mirrors exist because the site is periodically blocked in Turkey,
            // so an exact-match list would leak exactly when it matters most.
            "eksisozluk1923.com",
            "eksisozluk2023.com",
            // The family is wider than the dictionary: eksiup hosts images,
            // eksiseyler is the content arm.
            "eksiup.com",
            "img.eksiup.com",
            "eksiseyler.com",
            "eksi.com",
            // Their shortener. This is the one that actually bit: an entry
            // embedding a soz.lk image link went to the browser, which followed
            // the redirect and let app-links hand the user to the official app.
            "soz.lk",
            "www.soz.lk",
            "sourtimes.org",
        ).forEach { assertWithMessage(it).that(isEksiHost(it)).isTrue() }
    }

    @Test fun `unrelated hosts do not`() {
        listOf(
            "google.com",
            "twitter.com",
            "youtube.com",
            "example.org",
            "sozluk.com",
            // A bare substring test would drag these in. "meksika" is the one
            // that actually occurs in Turkish content.
            "meksika-haber.com",
            "www.meksika.org",
            "deksia.io",
            // Other shorteners are NOT assumed to be theirs.
            "bit.ly",
            "t.co",
            "soz.lk.evil.com",
        ).forEach { assertWithMessage(it).that(isEksiHost(it)).isFalse() }
    }

    @Test fun `a subdomain of an unrelated host is still excluded`() {
        // The label test must apply per label, not to the whole string.
        assertWithMessage("cdn.meksika.com").that(isEksiHost("cdn.meksika.com")).isFalse()
    }

    @Test fun `matching ignores case`() {
        assertWithMessage("EksiSozluk.COM").that(isEksiHost("EksiSozluk.COM")).isTrue()
    }
}
