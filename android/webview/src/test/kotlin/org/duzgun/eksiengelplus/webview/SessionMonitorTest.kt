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

    @Test fun `navigation to pages that can change the session triggers a reprobe`() {
        listOf(
            "https://eksisozluk.com/",
            "https://eksisozluk.com/giris",
            "https://eksisozluk.com/giris?ReturnUrl=%2f",
            "https://eksisozluk.com/cikis",
        ).forEach {
            assertWithMessage(it).that(monitor.shouldReprobe(it)).isTrue()
        }
    }

    @Test fun `ordinary browsing does not`() {
        listOf(
            "https://eksisozluk.com/biri/ssg",
            "https://eksisozluk.com/entry/123",
            "https://eksisozluk.com/some-title--456?p=2",
        ).forEach {
            assertWithMessage(it).that(monitor.shouldReprobe(it)).isFalse()
        }
    }

    @Test fun `a null url is not a reprobe trigger`() {
        assertThat(monitor.shouldReprobe(null)).isFalse()
    }
}
