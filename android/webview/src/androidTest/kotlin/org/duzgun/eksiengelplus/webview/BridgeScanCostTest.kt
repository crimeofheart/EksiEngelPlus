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
 * What one observer pass costs on a long page.
 *
 * Follower and following lists are the worst case the app has: several hundred
 * rows, each a div, extended by XHR as the user scrolls. Every extension is a
 * mutation, every mutation is a scan, and a scan that walks the whole document
 * turns that into quadratic work the site itself never does.
 *
 * getComputedStyle calls are the metric rather than elapsed milliseconds: each one
 * can force a style resolution, the count is exactly what the pathology produces,
 * and unlike a timing bound it does not go flaky on a loaded CI machine.
 */
@RunWith(AndroidJUnit4::class)
class BridgeScanCostTest {

    private val origin = "https://eksisozluk.com"

    @Before
    fun requireFeatures() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @Test
    fun aScanOnALongListDoesNotWalkTheWholeDocument() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, followerListFixture(rows = 600))
            settle()

            // Count only what happens after the page has settled, so first-load work
            // is not confused with the per-mutation cost that actually hurts.
            scenario.eval(COUNT_COMPUTED_STYLE)
            scenario.appendRow()
            settle()

            val perScan = scenario.eval("String(window.__gcsCount)").toInt()

            // One appended row is allowed to cost a bounded look at the new content,
            // never a walk of the 600 that were already there. Measured: 1 after the
            // fix, 1202 before it.
            assertThat(perScan).isLessThan(10)
        }
    }

    /** The other half: the promo fallback must not re-examine what it has already seen. */
    @Test
    fun repeatedMutationsDoNotRecheckTheSameElements() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, followerListFixture(rows = 300))
            settle()

            scenario.eval(COUNT_COMPUTED_STYLE)
            repeat(5) {
                scenario.appendRow()
                settle(passes = 1)
            }
            settle()

            val overFiveScans = scenario.eval("String(window.__gcsCount)").toInt()

            // Measured: 5 after the fix -- one per new row -- against 3020 before.
            assertThat(overFiveScans).isLessThan(25)
        }
    }

    /** One more page of followers, appended where the site appends them. */
    private fun ActivityScenario<BridgeTestActivity>.appendRow() {
        eval(
            """
            (function () {
              var d = document.createElement('div');
              d.className = 'follower-row';
              d.innerHTML = '<div class="nick"><a>yeni</a></div>';
              document.getElementById('follower-list').appendChild(d);
              return '1';
            })()
            """.trimIndent(),
        )
    }

    /**
     * A profile follower list: many rows inside a scroll container, the way the
     * site nests them, plus the profile buttons the bridge legitimately injects
     * into. Shaped for node count, which is what the scan reacts to.
     */
    private fun followerListFixture(rows: Int): String {
        val body = buildString {
            repeat(rows) { i ->
                append("<div class='follower-row'><div class='nick'><a>yazar$i</a></div></div>")
            }
        }
        return "<html><body><div class='profile-buttons'></div>" +
            "<div id='follower-list'>$body</div></body></html>"
    }

    private companion object {
        /** Wraps getComputedStyle in a counter and zeroes it. */
        val COUNT_COMPUTED_STYLE = """
            (function () {
              if (!window.__gcsPatched) {
                window.__gcsPatched = true;
                var original = window.getComputedStyle.bind(window);
                window.getComputedStyle = function () {
                  window.__gcsCount++;
                  return original.apply(null, arguments);
                };
              }
              window.__gcsCount = 0;
              return '1';
            })()
        """.trimIndent()
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
