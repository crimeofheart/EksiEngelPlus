package org.duzgun.eksiengelplus.webview

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class SessionMonitorTest {

    // shouldReprobe is pure URL inspection and never touches the client, so a
    // real one that is never called beats adding a mocking framework.
    private val monitor = SessionMonitor(
        org.duzgun.eksiengelplus.eksi.client.ScrapeClient(
            okhttp3.OkHttpClient(),
            baseUrlProvider = { "https://eksisozluk.com" },
        ),
    )

    private val loggedIn = SessionState.LoggedIn("birisi")

    @Test fun `navigation to pages that can change the session triggers a reprobe`() {
        listOf(
            "https://eksisozluk.com/",
            "https://eksisozluk.com/giris",
            "https://eksisozluk.com/giris?ReturnUrl=%2f",
            "https://eksisozluk.com/cikis",
        ).forEach {
            assertWithMessage(it).that(monitor.shouldReprobe(it, loggedIn)).isTrue()
        }
    }

    @Test fun `ordinary browsing does not, once a session is known`() {
        listOf(
            "https://eksisozluk.com/biri/ssg",
            "https://eksisozluk.com/entry/123",
            "https://eksisozluk.com/some-title--456?p=2",
        ).forEach {
            assertWithMessage(it).that(monitor.shouldReprobe(it, loggedIn)).isFalse()
        }
    }

    /**
     * While logged out, everything is a trigger. Ekşi does not reliably land on
     * one of the named paths after a login, and keying only off them left the bar
     * saying "giriş yapılmadı" until the app was restarted.
     */
    @Test fun `while logged out, any navigation is a trigger`() {
        listOf(
            "https://eksisozluk.com/biri/ssg",
            "https://eksisozluk.com/entry/123",
            "https://eksisozluk.com/some-title--456?p=2",
        ).forEach {
            assertWithMessage(it).that(monitor.shouldReprobe(it, SessionState.LoggedOut)).isTrue()
        }
    }

    /** Losing the session puts it back to probing eagerly. */
    @Test fun `a logged out monitor goes back to triggering on everything`() {
        val url = "https://eksisozluk.com/entry/123"

        assertThat(monitor.shouldReprobe(url, loggedIn)).isFalse()
        assertThat(monitor.shouldReprobe(url, SessionState.LoggedOut)).isTrue()
        assertThat(monitor.shouldReprobe(url, SessionState.Unknown)).isTrue()
    }

    @Test fun `a null url is not a reprobe trigger`() {
        assertThat(monitor.shouldReprobe(null)).isFalse()
    }
}
