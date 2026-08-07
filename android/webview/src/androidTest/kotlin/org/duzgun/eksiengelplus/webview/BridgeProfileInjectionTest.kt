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
 * Profile buttons go only where the site offers a relation.
 *
 * Ekşi renders no `.relation-link` on your own profile, because there is nothing
 * there to block or follow. Keying off the `/biri/` path alone offered the user
 * the chance to block themselves, which is the bug these cover.
 */
@RunWith(AndroidJUnit4::class)
class BridgeProfileInjectionTest {

    private val origin = "https://eksisozluk.com"

    /**
     * The base URL has to be the profile path, not the bare origin.
     * injectProfile keys off location.pathname, and loadDataWithBaseURL with just
     * the origin yields "/" -- which silently skipped the injector and made an
     * earlier version of these tests pass while asserting nothing.
     */
    private val profileUrl = "https://eksisozluk.com/biri/birisi"

    @Before
    fun requireFeatures() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @Test
    fun someoneElsesProfileGetsTheButtons() {
        withBridge { scenario ->
            scenario.loadHtml(profileUrl, profile(withRelationLinks = true))
            settle()

            assertThat(scenario.count("engelle")).isAtLeast(1)
            assertThat(scenario.count("takipçilerini engelle")).isEqualTo(1)
        }
    }

    @Test
    fun yourOwnProfileGetsNone() {
        withBridge { scenario ->
            // No .relation-link is exactly how the site renders your own page.
            scenario.loadHtml(profileUrl, profile(withRelationLinks = false))
            settle()

            assertThat(scenario.count("engelle")).isEqualTo(0)
            assertThat(scenario.count("takipçilerini engelle")).isEqualTo(0)
            assertThat(scenario.count("başlıklarını engelle")).isEqualTo(0)
        }
    }

    /**
     * The container often renders before the buttons inside it do. "Not ready" has
     * to stay retryable, or a slow profile would silently never get the items.
     */
    @Test
    fun buttonsArrivingLateAreStillPickedUp() {
        withBridge { scenario ->
            scenario.loadHtml(profileUrl, profile(withRelationLinks = false))
            settle()
            assertThat(scenario.count("takipçilerini engelle")).isEqualTo(0)

            scenario.eval(
                """
                (function () {
                  var a = document.createElement('a');
                  a.className = 'relation-link';
                  a.setAttribute('data-add-caption', 'engelle');
                  document.querySelector('.profile-buttons').appendChild(a);
                  return '1';
                })()
                """.trimIndent(),
            )
            settle()

            assertThat(scenario.count("takipçilerini engelle")).isEqualTo(1)
        }
    }

    /** And still exactly once, however many mutations the page goes on to make. */
    @Test
    fun aReadyProfileIsNotInjectedTwice() {
        withBridge { scenario ->
            scenario.loadHtml(profileUrl, profile(withRelationLinks = true))
            settle()

            repeat(4) {
                scenario.eval("document.body.appendChild(document.createElement('div')); '1'")
                settle(passes = 1)
            }
            settle()

            assertThat(scenario.count("takipçilerini engelle")).isEqualTo(1)
        }
    }

    private fun profile(withRelationLinks: Boolean): String {
        val relation = if (withRelationLinks) {
            "<a class='relation-link' data-add-caption='engelle' id='button-blocked-link'></a>"
        } else {
            ""
        }
        return """
        <html><body>
          <h1 data-nick="birisi">birisi</h1>
          <input id="who" value="7"/>
          <div class="profile-buttons">$relation</div>
        </body></html>
        """.trimIndent()
    }

    private fun ActivityScenario<BridgeTestActivity>.count(label: String): Int {
        val js = """
            String(Array.prototype.filter.call(
              document.querySelectorAll('a'),
              function (a) { return a.textContent.indexOf('$label') !== -1; }
            ).length)
        """.trimIndent()
        return eval(js).toInt()
    }

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
}
