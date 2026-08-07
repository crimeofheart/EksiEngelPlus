package org.duzgun.eksiengelplus.webview

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What may be dropped, and what must never be.
 *
 * The blocklist was built from hosts actually observed on one Ekşi page load, so
 * the risk is not missing an ad network but catching something the site needs.
 */
class AdHostTest {

    @Test fun `observed ad and tracker hosts are blocked`() {
        listOf(
            "www.googletagmanager.com",
            "www.google-analytics.com",
            "region1.google-analytics.com",
            "www.googleadservices.com",
            "securepubads.g.doubleclick.net",
            "googleads.g.doubleclick.net",
            "sb.scorecardresearch.com",
            "uip.semasio.net",
            "gatr.hit.gemius.pl",
            "nativespot.com",
            "app.networkad.net",
            "app.gelirartisi.com",
        ).forEach {
            assertThat(isAdOrTrackerHost(it)).isTrue()
        }
    }

    @Test fun `eksi's own hosts are never blocked`() {
        listOf(
            "eksisozluk.com",
            "www.eksisozluk.com",
            "ekstat.com",
            "img.ekstat.com",
            "eksiseyler.com",
            "soz.lk",
        ).forEach {
            assertThat(isAdOrTrackerHost(it)).isFalse()
        }
    }

    /** Blocking these would trade a load win for a visible rendering change. */
    @Test fun `font CDNs are left alone`() {
        assertThat(isAdOrTrackerHost("fonts.googleapis.com")).isFalse()
        assertThat(isAdOrTrackerHost("fonts.gstatic.com")).isFalse()
    }

    /** Suffix matching must not be a substring match. */
    @Test fun `a lookalike host is not caught by accident`() {
        assertThat(isAdOrTrackerHost("notdoubleclick.net.example.com")).isFalse()
        assertThat(isAdOrTrackerHost("mygoogle-analytics.com")).isFalse()
    }
}
