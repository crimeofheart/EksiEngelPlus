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
 * Going back returns to where the page was left.
 *
 * The pushState path is the one exercised here because it is the one nothing
 * else could ever fix: no navigation happens as far as the browser is concerned,
 * so the renderer has nothing to restore and the position is only kept if we
 * keep it.
 */
@RunWith(AndroidJUnit4::class)
class BridgeScrollMemoryTest {

    private val origin = "https://eksisozluk.com"

    @Before
    fun requireFeatures() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @Test
    fun goingBackReturnsToWhereTheListWasLeft() {
        withBridge { scenario ->
            scenario.loadHtml(origin, TALL_FIXTURE)
            settle()

            scenario.scrollTo(1200)
            settle()
            scenario.eval("(history.pushState({}, '', '/bir-baslik--1?p=2'), window.scrollTo(0, 0), '1')")
            settle()
            scenario.eval("(history.back(), '1')")
            settle()

            assertThat(scenario.scrollY()).isWithin(4.0).of(1200.0)
        }
    }

    /**
     * Forward is not a return.
     *
     * Restoring on any history change would drop the user halfway down a page
     * they have not read, which is the failure this feature would be mistaken for.
     */
    @Test
    fun goingForwardToANewPageStaysWhereTheSitePutIt() {
        withBridge { scenario ->
            scenario.loadHtml(origin, TALL_FIXTURE)
            settle()

            scenario.scrollTo(1200)
            settle()
            scenario.eval("(history.pushState({}, '', '/bir-baslik--1?p=2'), window.scrollTo(0, 0), '1')")
            settle()

            assertThat(scenario.scrollY()).isWithin(4.0).of(0.0)
        }
    }

    /** A page left at the top has nothing to restore, and must not be moved. */
    @Test
    fun aPageNeverScrolledIsLeftAtTheTop() {
        withBridge { scenario ->
            scenario.loadHtml(origin, TALL_FIXTURE)
            settle()

            scenario.eval("(history.pushState({}, '', '/bir-baslik--1?p=2'), window.scrollTo(0, 900), '1')")
            settle()
            scenario.eval("(history.back(), '1')")
            settle()

            assertThat(scenario.scrollY()).isWithin(4.0).of(0.0)
        }
    }

    private fun ActivityScenario<BridgeTestActivity>.scrollTo(y: Int) {
        eval("(window.scrollTo(0, $y), '1')")
    }

    private fun ActivityScenario<BridgeTestActivity>.scrollY(): Double =
        eval("String(window.pageYOffset)").toDouble()

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
        /** Tall enough that a remembered position is well past the first screen. */
        val TALL_FIXTURE = """
        <html><body style="margin:0">
          <ul class="topic-list"><li style="height:3000px">bir başlık</li></ul>
        </body></html>
        """.trimIndent()
    }
}
