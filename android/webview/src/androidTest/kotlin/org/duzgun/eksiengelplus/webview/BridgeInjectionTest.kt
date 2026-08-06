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
 * Injection is driven by a persistent observer rather than waitForElm, so the
 * question is no longer "did it run" but "did it run exactly once per node". In a
 * WebView the user never reloads, so a duplicating injector accumulates for the
 * whole session.
 */
@RunWith(AndroidJUnit4::class)
class BridgeInjectionTest {

    private val origin = "https://eksisozluk.com"

    @Before
    fun requireFeatures() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @Test
    fun injectionIsIdempotentAcrossRepeatedObserverPasses() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, ENTRY_FIXTURE)
            settle()
            assertThat(scenario.count("yazarı engelle")).isEqualTo(1)

            // Each append is a mutation the observer must react to. If the guard
            // rested on anything but the per-injector mark, this is where the
            // duplicates would appear.
            repeat(5) {
                scenario.eval("document.body.appendChild(document.createElement('div')); '1'")
                settle(passes = 1)
            }
            settle()

            assertThat(scenario.count("yazarı engelle")).isEqualTo(1)
        }
    }

    /**
     * The other half of the config contract: the open page is told, rather than
     * being left stale until the user happens to navigate.
     */
    @Test
    fun aConfigPushRelabelsTheOpenPage() {
        withBridge { scenario, bridge ->
            scenario.loadHtml(origin, ENTRY_FIXTURE)
            settle()
            assertThat(scenario.count("yazarı engelle")).isEqualTo(1)
            assertThat(scenario.count("yazarı sessize al")).isEqualTo(0)

            scenario.onActivity { activity ->
                bridge.updateConfig(
                    activity.web,
                    configJson = """{"enableMute":true}""",
                    iconDataUri = "",
                )
            }
            settle()

            assertThat(scenario.count("yazarı sessize al")).isEqualTo(1)
            // Relabelled, not duplicated: the old item is removed before the rescan.
            assertThat(scenario.count("yazarı engelle")).isEqualTo(0)
        }
    }

    /** Counts injected anchors carrying [label], ignoring the containers holding them. */
    private fun ActivityScenario<BridgeTestActivity>.count(label: String): Int {
        val js = """
            String(Array.prototype.filter.call(
              document.querySelectorAll('a'),
              function (a) { return a.textContent.indexOf('$label') !== -1; }
            ).length)
        """.trimIndent()
        return eval(js).toInt()
    }

    private fun withBridge(
        block: (ActivityScenario<BridgeTestActivity>, BridgeHost) -> Unit,
    ) {
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
            block(scenario, bridge)
        }
    }
}
