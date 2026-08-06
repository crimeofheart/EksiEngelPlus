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
 * The origin allowlist is the whole reason addWebMessageListener was chosen over
 * addJavascriptInterface, which exposes its object to every page with no scoping.
 * This WebView browses a user-content site full of arbitrary outbound links, so
 * "present here, absent there" is the security boundary, not a nicety.
 */
@RunWith(AndroidJUnit4::class)
class BridgeOriginScopeTest {

    private val allowed = "https://eksisozluk.com"
    private val foreign = "https://example.com"

    @Before
    fun requireFeatures() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @Test
    fun bridgeIsPresentOnAnAllowedOrigin() {
        withBridge { scenario ->
            scenario.loadHtml(allowed, ENTRY_FIXTURE)

            assertThat(scenario.eval("typeof EksiEngelPlus")).isEqualTo("object")
            // The document-start script ran, not merely the listener object.
            assertThat(scenario.eval("String(!!window.__eksiEngelBridgeLoaded)")).isEqualTo("true")
        }
    }

    @Test
    fun bridgeIsAbsentEverywhereElse() {
        withBridge { scenario ->
            scenario.loadHtml(foreign, ENTRY_FIXTURE)

            assertThat(scenario.eval("typeof EksiEngelPlus")).isEqualTo("undefined")
            assertThat(scenario.eval("String(!!window.__eksiEngelBridgeLoaded)")).isEqualTo("false")
        }
    }

    private fun withBridge(block: (ActivityScenario<BridgeTestActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(BridgeTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                BridgeHost(
                    context = context,
                    allowedOrigins = setOf(allowed),
                    onEnqueue = {},
                ).install(activity.web, configJson = "{}", iconDataUri = "")
            }
            block(scenario)
        }
    }
}
