package org.duzgun.eksiengelplus.webview

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The promo fallback still catches what it is for.
 *
 * Written alongside the scan-cost fix: that change narrowed the candidate set to
 * shallow elements and moved the "already checked" mark ahead of the position
 * test, so the two ways it could have been broken are a promo that is no longer
 * found and ordinary sticky chrome that now is.
 */
@RunWith(AndroidJUnit4::class)
class BridgePromoHidingTest {

    private val origin = "https://eksisozluk.com"

    @Before
    fun requireFeatures() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @Test
    fun aFixedAppInterstitialIsHidden() {
        withBridge { scenario ->
            scenario.loadHtml(origin, PROMO_FIXTURE)
            settle()

            assertThat(scenario.displayOf("promo")).isEqualTo("none")
        }
    }

    /** The narrow anchoring is the point: ordinary sticky UI must survive it. */
    @Test
    fun anOrdinaryStickyHeaderIsLeftAlone() {
        withBridge { scenario ->
            scenario.loadHtml(origin, PROMO_FIXTURE)
            settle()

            assertThat(scenario.displayOf("header")).isNotEqualTo("none")
            assertThat(scenario.displayOf("row")).isNotEqualTo("none")
        }
    }

    /** A promo injected after load, the way the site actually injects it. */
    @Test
    fun aPromoAppearingAfterLoadIsStillCaught() {
        withBridge { scenario ->
            scenario.loadHtml(origin, PROMO_FIXTURE)
            settle()

            scenario.eval(
                """
                (function () {
                  var d = document.createElement('div');
                  d.id = 'late';
                  d.style.position = 'fixed';
                  d.textContent = 'uygulamamızda devam et';
                  document.body.appendChild(d);
                  return '1';
                })()
                """.trimIndent(),
            )
            settle()

            assertThat(scenario.displayOf("late")).isEqualTo("none")
        }
    }

    private fun ActivityScenario<BridgeTestActivity>.displayOf(id: String): String =
        eval("(document.getElementById('$id') || {style:{}}).style.display || 'visible'")

    private fun withBridge(block: (ActivityScenario<BridgeTestActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = BridgeHost(
            context = context,
            allowedOrigins = setOf(origin),
            onEnqueue = {},
        )
        ActivityScenario.launch(BridgeTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                bridge.install(activity.web, configJson = "{}", iconDataUri = "")
            }
            block(scenario)
        }
    }

    private companion object {
        /**
         * The promo is body-level and fixed, and mentions both the app and
         * continuing. The sticky header mentions neither, and the row is ordinary
         * content one level deeper.
         */
        val PROMO_FIXTURE = """
        <html><body>
          <div id="header" style="position:sticky">ekşi sözlük</div>
          <div id="promo" style="position:fixed">
            uygulamamızda devam et
          </div>
          <div id="list"><div id="row">bir şey</div></div>
        </body></html>
        """.trimIndent()
    }
}
