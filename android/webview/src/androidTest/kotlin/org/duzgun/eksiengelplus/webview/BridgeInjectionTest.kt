package org.duzgun.eksiengelplus.webview

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.google.common.truth.Truth.assertThat
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
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

    /**
     * The four states of the two profile relations.
     *
     * They invert independently -- a user may have their titles blocked and not
     * themselves -- so the pair is the unit worth testing, not each alone.
     */
    @Test
    fun profileItemsFollowTheRelationTheSiteSaysItHolds() {
        onProfile(banned = false, titlesBanned = false) { scenario, _ ->
            assertThat(scenario.labels()).contains("engelle")
            assertThat(scenario.labels()).contains("başlıklarını engelle")
            assertThat(scenario.labels()).doesNotContain("engellemeyi bırak")
        }
        onProfile(banned = true, titlesBanned = false) { scenario, _ ->
            assertThat(scenario.labels()).contains("engellemeyi bırak")
            assertThat(scenario.labels()).contains("başlıklarını engelle")
        }
        onProfile(banned = false, titlesBanned = true) { scenario, _ ->
            assertThat(scenario.labels()).contains("engelle")
            assertThat(scenario.labels()).contains("başlıkları engellemeyi kaldır")
        }
        onProfile(banned = true, titlesBanned = true) { scenario, _ ->
            assertThat(scenario.labels()).contains("engellemeyi bırak")
            assertThat(scenario.labels()).contains("başlıkları engellemeyi kaldır")
        }
    }

    @Test
    fun undoingABlockEnqueuesUndoban() {
        onProfile(banned = true, titlesBanned = false) { scenario, sent ->
            scenario.click("engellemeyi bırak")
            settle()
            val r = sent.single()
            assertThat(r.source).isEqualTo(BanSource.SINGLE)
            assertThat(r.mode).isEqualTo(BanMode.UNDOBAN)
            assertThat(r.targetType).isEqualTo(TargetType.USER)
            assertThat(r.authorNick).isEqualTo("testyazar")
        }
    }

    @Test
    fun undoingATitleBlockCarriesTheTitleRelation() {
        onProfile(banned = false, titlesBanned = true) { scenario, sent ->
            scenario.click("başlıkları engellemeyi kaldır")
            settle()
            val r = sent.single()
            assertThat(r.mode).isEqualTo(BanMode.UNDOBAN)
            assertThat(r.targetType).isEqualTo(TargetType.TITLE)
        }
    }

    /**
     * The mute-aware label is exactly what makes sending r=u here look plausible.
     * The relation Ekşi recorded is the block, so undoing it is r=m either way.
     */
    @Test
    fun undoingABlockUnderMuteStillTargetsTheBlockRelation() {
        onProfile(banned = true, titlesBanned = false, config = """{"enableMute":true}""") { scenario, sent ->
            assertThat(scenario.labels()).contains("engellemeyi bırak")
            scenario.click("engellemeyi bırak")
            settle()
            assertThat(sent.single().targetType).isEqualTo(TargetType.USER)
        }
    }

    /** And the other direction is still mute-aware, so the label is not cosmetic. */
    @Test
    fun blockingUnderMuteStillTargetsTheMuteRelation() {
        onProfile(banned = false, titlesBanned = false, config = """{"enableMute":true}""") { scenario, sent ->
            scenario.click("sessize al")
            settle()
            val r = sent.single()
            assertThat(r.mode).isEqualTo(BanMode.BAN)
            assertThat(r.targetType).isEqualTo(TargetType.MUTE)
        }
    }

    /**
     * Not a relation on this profile but an operation over someone else's
     * follower list, so no data-added describes it and it never inverts.
     */
    @Test
    fun blockingFollowersIsNeverInverted() {
        onProfile(banned = true, titlesBanned = true) { scenario, sent ->
            scenario.click("takipçilerini engelle")
            settle()
            val r = sent.single()
            assertThat(r.source).isEqualTo(BanSource.FOLLOW)
            assertThat(r.mode).isEqualTo(BanMode.BAN)
        }
    }

    /** Your own profile carries no relation links, and must receive no items. */
    @Test
    fun aProfileWithNoRelationLinksReceivesNothing() {
        withBridge { scenario, _ ->
            scenario.loadHtml(
                "$origin/biri/testyazar",
                """<html><body><h1 data-nick="testyazar"></h1>
                   <input id="who" value="7"><ul class="profile-buttons"></ul></body></html>""",
            )
            settle()
            assertThat(scenario.labels()).isEmpty()
        }
    }

    /**
     * The labels of our own injected items, and nothing else.
     *
     * Scoped to [ITEM_MARK] rather than to every anchor: the fixture's native
     * relation links say "engelle" too, and a counter that cannot tell them
     * apart would pass whether or not anything was injected.
     */
    private fun ActivityScenario<BridgeTestActivity>.labels(): List<String> {
        val js = """
            Array.prototype.map.call(
              document.querySelectorAll('[data-eksiengel-item="true"]'),
              function (li) { return li.textContent.trim(); }
            ).join('|')
        """.trimIndent()
        return eval(js).split("|").filter { it.isNotEmpty() }
    }

    /** Clicks the injected item whose label is exactly [label]. */
    private fun ActivityScenario<BridgeTestActivity>.click(label: String) {
        val js = """
            (function () {
              var li = Array.prototype.filter.call(
                document.querySelectorAll('[data-eksiengel-item="true"]'),
                function (li) { return li.textContent.trim() === '$label'; }
              )[0];
              if (!li) return 'missing';
              li.querySelector('a').click();
              return 'ok';
            })()
        """.trimIndent()
        check(eval(js) == "ok") { "no injected item labelled $label" }
    }

    /** Loads a profile fixture and hands back everything the page enqueued. */
    private fun onProfile(
        banned: Boolean,
        titlesBanned: Boolean,
        config: String = "{}",
        block: (ActivityScenario<BridgeTestActivity>, List<OperationRequest>) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sent = java.util.Collections.synchronizedList(mutableListOf<OperationRequest>())
        val bridge = BridgeHost(
            context = context,
            allowedOrigins = setOf(origin),
            onEnqueue = { sent.add(it) },
        )
        ActivityScenario.launch(BridgeTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                bridge.install(activity.web, configJson = config, iconDataUri = "")
            }
            scenario.loadHtml("$origin/biri/testyazar", profileFixture(banned, titlesBanned))
            settle()
            block(scenario, sent)
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
