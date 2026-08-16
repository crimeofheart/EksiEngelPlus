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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The hold menu, which is the only affordance in the app that is invisible until
 * used. That makes the negative cases the important ones: a tap that opened it,
 * or a scroll that opened it, would be a menu appearing while the user was
 * reading, on a page that is nothing but title links.
 */
@RunWith(AndroidJUnit4::class)
class BridgeTitleHoldTest {

    private val origin = "https://eksisozluk.com"
    private val titleUrl = "$origin/mohamed-salah-ghaly--3459509"

    @Before
    fun requireFeatures() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @Test
    fun holdingATitleRowOpensTheMenu() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()

            assertThat(scenario.menus()).isEqualTo(0)
            scenario.hold(".topic-list a")

            assertThat(scenario.menus()).isEqualTo(1)
            assertThat(scenario.buttons()).isEqualTo(3)
        }
    }

    @Test
    fun tappingATitleOpensNothing() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()

            scenario.touch(".topic-list a", "touchstart")
            scenario.touch(".topic-list a", "touchend")
            Thread.sleep(HOLD_WAIT_MS)

            assertThat(scenario.menus()).isEqualTo(0)
        }
    }

    /** A finger that travels is scrolling a list, not holding a row of it. */
    @Test
    fun movingAwayCancelsTheHold() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()

            scenario.touch(".topic-list a", "touchstart", x = 10, y = 10)
            scenario.touch(".topic-list a", "touchmove", x = 10, y = 90)
            Thread.sleep(HOLD_WAIT_MS)

            assertThat(scenario.menus()).isEqualTo(0)
        }
    }

    /**
     * The pager carries a title's address and is not a title. Holding it has to do
     * nothing at all, rather than offer to share the page the user is leaving.
     */
    @Test
    fun holdingThePagerOpensNothing() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TITLE_PAGE_FIXTURE)
            settle()

            scenario.hold(".pager a")

            assertThat(scenario.menus()).isEqualTo(0)
        }
    }

    @Test
    fun sharingSendsTheTitleAddressWithoutTheListSort() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()
            scenario.hold(".topic-list a")

            scenario.press(SHARE)

            assertThat(shared.await(5, TimeUnit.SECONDS)).isTrue()
            // "?a=popular" says how the list was sorted, which is nothing to the
            // person receiving the link.
            assertThat(sharedUrl).isEqualTo(titleUrl)
            assertThat(sharedTitle).isEqualTo("mohamed salah ghaly")
        }
    }

    /** The count Ekşi appends to a row is not part of the name being copied. */
    @Test
    fun copyingTheTitleSendsItsNameWithoutTheEntryCount() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()
            scenario.hold(".topic-list a")

            scenario.press(COPY_TITLE)

            assertThat(copied.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(copiedText).isEqualTo("mohamed salah ghaly")
        }
    }

    @Test
    fun copyingTheLinkSendsTheAddress() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()
            scenario.hold(".topic-list a")

            scenario.press(COPY_LINK)

            assertThat(copied.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(copiedText).isEqualTo(titleUrl)
        }
    }

    /** On the title's own page the name comes from the header's `data-title`. */
    @Test
    fun holdingTheHeaderUsesTheTitleItNames() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TITLE_PAGE_FIXTURE)
            settle()
            scenario.hold("h1#title a")

            scenario.press(COPY_TITLE)

            assertThat(copied.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(copiedText).isEqualTo("mohamed salah ghaly")
        }
    }

    /** Choosing an option takes the menu with it, so the tap is not left half-done. */
    @Test
    fun choosingAnOptionClosesTheMenu() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()
            scenario.hold(".topic-list a")

            scenario.press(SHARE)

            assertThat(scenario.menus()).isEqualTo(0)
        }
    }

    /**
     * The click the opening lift leaves behind is addressed to the title it was
     * held over — a touch sequence belongs to the element it began on — and would
     * open that title behind the menu. It is dropped, and only it.
     */
    @Test
    fun theOpeningClickDoesNotOpenTheTitleUnderneath() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()

            scenario.touch(".topic-list a", "touchstart")
            Thread.sleep(HOLD_WAIT_MS)
            scenario.touch(".topic-list a", "touchend")
            assertThat(scenario.clickTitleAnchor()).isEqualTo("default-prevented")

            assertThat(scenario.menus()).isEqualTo(1)
        }
    }

    /**
     * And a press on an option always acts, however many times the cycle is run.
     *
     * Copy, copy, share, copy: nothing about the menu is one-shot, and nothing it
     * leaves behind may make the next press land differently from the first.
     */
    @Test
    fun everyCycleWorksHoweverManyTimesItIsRepeated() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()

            val order = listOf(COPY_TITLE, COPY_LINK, SHARE, COPY_TITLE, COPY_LINK)
            order.forEach { option ->
                scenario.hold(".topic-list a")
                assertThat(scenario.menus()).isEqualTo(1)
                scenario.press(option)
                assertThat(scenario.menus()).isEqualTo(0)
            }

            assertThat(shared.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(copies.get()).isEqualTo(4)
        }
    }

    /**
     * The menu is tappable the instant it exists, at any speed.
     *
     * Every timing gate tried here was wrong. A menu inert for a period is a menu
     * the user can tap into and be ignored by — that was "the second copy does
     * nothing" — and a period that fails to end is a full-screen backdrop that
     * answers nothing, which reads as the app having frozen.
     */
    @Test
    fun theMenuAcceptsATapImmediatelyAfterTheLift() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()

            scenario.touch(".topic-list a", "touchstart")
            Thread.sleep(HOLD_WAIT_MS)
            assertThat(scenario.hitTestFirstButton()).isEqualTo("menu")

            scenario.touch(".topic-list a", "touchend")
            scenario.press(COPY_LINK)          // no delay at all

            assertThat(copied.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(copiedText).isEqualTo(titleUrl)
        }
    }

    /**
     * Copy one thing, then the other, as fast as the gestures can be made.
     *
     * The reported freeze: the second menu appearing but answering nothing, over a
     * dimmed page. Both cycles have to complete, and no backdrop may be left
     * behind.
     */
    @Test
    fun twoCyclesBackToBackBothComplete() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()

            scenario.hold(".topic-list a")
            scenario.press(COPY_TITLE)
            assertThat(copied.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(copiedText).isEqualTo("mohamed salah ghaly")

            scenario.hold(".topic-list a")
            scenario.press(COPY_LINK)

            assertThat(secondCopy.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(copiedText).isEqualTo(titleUrl)
            assertThat(scenario.menus()).isEqualTo(0)
        }
    }

    /**
     * A title page turns pages on a horizontal swipe, and the menu opens under a
     * finger that is still down. The finger that lifts off a hold drags a little.
     *
     * Nothing about that gesture is a page turn: it belongs to the menu. If the
     * swipe engine takes it, the page slides and prefetches under a modal sheet
     * and can commit a navigation nobody asked for — which is why this only ever
     * showed up on a title page, never on the feed.
     */
    @Test
    fun theSwipeDoesNotRunUnderTheMenu() {
        withBridge { scenario, _ ->
            // Loaded *at* the title's own address, not merely at the origin: the
            // page ring exists only where `location.pathname` is a title's, so a
            // fixture served from "/" cannot swipe at all and would pass this
            // test without ever exercising it.
            scenario.loadHtml(titleUrl, TITLE_PAGE_FIXTURE)
            settle()

            scenario.touch("h1#title a", "touchstart", x = 200, y = 200)
            Thread.sleep(HOLD_WAIT_MS)
            // The drift of a finger coming off a hold, well past the swipe's
            // threshold for claiming the gesture.
            scenario.touch("h1#title a", "touchmove", x = 120, y = 205)
            scenario.touch("h1#title a", "touchmove", x = 60, y = 205)
            scenario.touch("h1#title a", "touchend", x = 60, y = 205)

            /*
             * The surface the swipe slides is an ancestor of the menu, and it
             * takes `will-change: transform` the moment a drag begins. That makes
             * it the containing block for everything fixed inside it, so the
             * backdrop stops being viewport-sized and the sheet is anchored to
             * the bottom of the whole document — off screen, on a page of
             * entries. A dimmed page with no menu on it and nothing responding is
             * what that looks like from the outside.
             */
            assertThat(scenario.draggedElements()).isEqualTo(0)
            assertThat(scenario.sheetOnScreen()).isEqualTo("yes")
            assertThat(scenario.menus()).isEqualTo(1)

            scenario.press(COPY_TITLE)
            assertThat(copied.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    /**
     * The control for the test above: the same drag, with no menu open, does
     * engage the swipe. Without this, a fixture that simply cannot swipe would
     * satisfy "the swipe did not run" while proving nothing.
     */
    @Test
    fun theSameDragWithoutAMenuDoesEngageTheSwipe() {
        withBridge { scenario, _ ->
            scenario.loadHtml(titleUrl, TITLE_PAGE_FIXTURE)
            settle()

            scenario.touch("h1#title a", "touchstart", x = 200, y = 200)
            scenario.touch("h1#title a", "touchmove", x = 120, y = 205)
            scenario.touch("h1#title a", "touchmove", x = 60, y = 205)

            assertThat(scenario.draggedElements()).isAtLeast(1)
        }
    }

    /**
     * A touch anywhere past the sheet dismisses it, on the touch itself.
     *
     * Whatever else goes wrong, a full-screen dimmed backdrop must never be
     * something the user is stuck behind — and a dismissal that waited for a
     * click would depend on clicks working, which is exactly what cannot be
     * assumed here.
     */
    @Test
    fun aTouchOutsideTheSheetDismissesIt() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()
            scenario.hold(".topic-list a")
            assertThat(scenario.menus()).isEqualTo(1)

            scenario.touchBackdrop("touchstart")
            // Gone as far as the user is concerned, on the touch itself — but the
            // node is still there, because it owns the touch still in flight.
            assertThat(scenario.style("[data-eksiengel-hold-menu]", "cs.visibility"))
                .isEqualTo("hidden")

            scenario.touchBackdrop("touchend")

            assertThat(scenario.menus()).isEqualTo(0)
        }
    }

    /** And a touch on the sheet is not "outside", or no option could be chosen. */
    @Test
    fun aTouchOnTheSheetKeepsItOpen() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()
            scenario.hold(".topic-list a")

            scenario.press(COPY_TITLE)

            assertThat(copied.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(copiedText).isEqualTo("mohamed salah ghaly")
        }
    }

    /**
     * The WebView's own long-press runs on the same gesture, and where the menu is
     * selectable it takes the text of whichever button landed under the finger:
     * our own label highlighted, with the browser's Copy/Share bar over it.
     */
    @Test
    fun theMenuIsNotSelectableText() {
        withBridge { scenario, _ ->
            scenario.loadHtml(origin, TOPIC_LIST_FIXTURE)
            settle()
            scenario.hold(".topic-list a")

            assertThat(scenario.style("[data-eksiengel-hold-menu] button", "cs.userSelect || cs.webkitUserSelect"))
                .isEqualTo("none")
        }
    }

    // ------------------------------------------------------------------ harness

    private val shared = CountDownLatch(1)
    private var sharedUrl = ""
    private var sharedTitle = ""

    private val copies = java.util.concurrent.atomic.AtomicInteger(0)
    private val copied = CountDownLatch(1)

    /** Reaches zero on the second copy, so a back-to-back pair can be awaited. */
    private val secondCopy = CountDownLatch(2)
    private var copiedText = ""

    private fun withBridge(
        block: (ActivityScenario<BridgeTestActivity>, BridgeHost) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = BridgeHost(
            context = context,
            allowedOrigins = setOf(origin),
            onEnqueue = {},
            onShare = { url, title ->
                sharedUrl = url
                sharedTitle = title
                shared.countDown()
            },
            onCopy = { text, _ ->
                copiedText = text
                copies.incrementAndGet()
                copied.countDown()
                secondCopy.countDown()
            },
        )
        ActivityScenario.launch(BridgeTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                bridge.install(activity.web, configJson = "{}", iconDataUri = "")
            }
            block(scenario, bridge)
        }
    }

    private companion object {
        /** Comfortably past the 500ms the page waits before deciding it is a hold. */
        const val HOLD_WAIT_MS = 800L

        // The order the sheet renders them in.
        const val COPY_TITLE = 0
        const val COPY_LINK = 1
        const val SHARE = 2
    }

    /** A whole gesture: down, held past the threshold, and lifted. */
    private fun ActivityScenario<BridgeTestActivity>.hold(selector: String) {
        touch(selector, "touchstart")
        Thread.sleep(HOLD_WAIT_MS)
        touch(selector, "touchend")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Asserts the element was found, because a selector that matches nothing
     * dispatches nothing and then passes every "no menu appeared" case.
     */
    private fun ActivityScenario<BridgeTestActivity>.touch(
        selector: String,
        type: String,
        x: Int = 10,
        y: Int = 10,
    ) {
        val result = eval(
            """
            (function () {
              var el = document.querySelector('$selector');
              if (!el) return 'missing';
              var t = new Touch({ identifier: 1, target: el, clientX: $x, clientY: $y });
              el.dispatchEvent(new TouchEvent('$type', {
                bubbles: true, cancelable: true, composed: true,
                touches: [t], targetTouches: [t], changedTouches: [t]
              }));
              return 'ok';
            })()
            """.trimIndent()
        )
        assertThat(result).isEqualTo("ok")
    }

    /**
     * A tap the user meant: its own touch, then the click that follows it.
     *
     * The touch is what distinguishes it from the click the opening gesture
     * leaves behind — that one arrives with no touchstart of its own, and is the
     * only click the page drops.
     */
    private fun ActivityScenario<BridgeTestActivity>.press(index: Int) {
        val result = eval(
            """
            (function () {
              var b = document.querySelectorAll('[data-eksiengel-hold-menu] button');
              if (!b[$index]) return 'missing';
              var el = b[$index];
              var r = el.getBoundingClientRect();
              var t = new Touch({
                identifier: 2, target: el,
                clientX: r.left + r.width / 2, clientY: r.top + r.height / 2
              });
              el.dispatchEvent(new TouchEvent('touchstart', {
                bubbles: true, cancelable: true, composed: true,
                touches: [t], targetTouches: [t], changedTouches: [t]
              }));
              el.dispatchEvent(new TouchEvent('touchend', {
                bubbles: true, cancelable: true, composed: true,
                touches: [], targetTouches: [], changedTouches: [t]
              }));
              el.click();
              return 'ok';
            })()
            """.trimIndent()
        )
        assertThat(result).isEqualTo("ok")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * How many elements the swipe has claimed for dragging.
     *
     * `will-change: transform` is what `beginDrag` stamps on its surface, and the
     * surface is chosen by height — so once the menu is open it is the menu's own
     * backdrop, not the page. Counting them all is the only assertion that does
     * not depend on which element the swipe happened to pick.
     */
    private fun ActivityScenario<BridgeTestActivity>.draggedElements(): Int =
        eval("String(document.querySelectorAll('[style*=\"will-change\"]').length)").toInt()

    /** Whether the card is where the user can actually see and reach it. */
    private fun ActivityScenario<BridgeTestActivity>.sheetOnScreen(): String = eval(
        """
        (function () {
          var b = document.querySelector('[data-eksiengel-hold-menu] button');
          if (!b) return 'missing';
          var r = b.getBoundingClientRect();
          var h = window.innerHeight || 0;
          return (r.top >= 0 && r.bottom <= h) ? 'yes' : 'no';
        })()
        """.trimIndent()
    )

    /** A touch on the backdrop: on the menu's overlay, but past the sheet. */
    private fun ActivityScenario<BridgeTestActivity>.touchBackdrop(type: String) {
        val result = eval(
            """
            (function () {
              var back = document.querySelector('[data-eksiengel-hold-menu]');
              if (!back) return 'missing';
              var t = new Touch({ identifier: 3, target: back, clientX: 5, clientY: 5 });
              back.dispatchEvent(new TouchEvent('$type', {
                bubbles: true, cancelable: true, composed: true,
                touches: [t], targetTouches: [t], changedTouches: [t]
              }));
              return 'ok';
            })()
            """.trimIndent()
        )
        assertThat(result).isEqualTo("ok")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * The leftover click, dispatched where it is really delivered: on the title
     * the gesture began on. Reports whether the page refused it.
     */
    private fun ActivityScenario<BridgeTestActivity>.clickTitleAnchor(): String = eval(
        """
        (function () {
          var a = document.querySelector('.topic-list a');
          if (!a) return 'missing';
          var ev = new MouseEvent('click', { bubbles: true, cancelable: true });
          a.dispatchEvent(ev);
          return ev.defaultPrevented ? 'default-prevented' : 'went-through';
        })()
        """.trimIndent()
    )

    /** The bare click, with no touch of its own: the opening gesture's leftover. */
    private fun ActivityScenario<BridgeTestActivity>.clickOnly(index: Int) {
        val result = eval(
            """
            (function () {
              var b = document.querySelectorAll('[data-eksiengel-hold-menu] button');
              if (!b[$index]) return 'missing';
              b[$index].click();
              return 'ok';
            })()
            """.trimIndent()
        )
        assertThat(result).isEqualTo("ok")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun ActivityScenario<BridgeTestActivity>.menus(): Int =
        eval("String(document.querySelectorAll('[data-eksiengel-hold-menu]').length)").toInt()

    /**
     * Computed, not inline: what the page ends up with is what matters here.
     *
     * [read] is an expression over `cs`, so a property the WebView only answers to
     * under its prefix can say so.
     */
    private fun ActivityScenario<BridgeTestActivity>.style(
        selector: String,
        read: String,
    ): String = eval(
        """
        (function () {
          var el = document.querySelector('$selector');
          if (!el) return 'missing';
          var cs = getComputedStyle(el);
          return String($read);
        })()
        """.trimIndent()
    )

    /**
     * What a real tap over the first button would reach.
     *
     * `elementFromPoint` is the check that matters, not the declared style: it is
     * hit-testing, so it answers "would a finger here land on the menu" — which is
     * the whole question — where a synthetic `click()` would bypass it entirely.
     */
    private fun ActivityScenario<BridgeTestActivity>.hitTestFirstButton(): String = eval(
        """
        (function () {
          var b = document.querySelector('[data-eksiengel-hold-menu] button');
          if (!b) return 'missing';
          var r = b.getBoundingClientRect();
          var el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
          if (!el) return 'nothing';
          return el.closest('[data-eksiengel-hold-menu]') ? 'menu' : 'through';
        })()
        """.trimIndent()
    )

    private fun ActivityScenario<BridgeTestActivity>.buttons(): Int =
        eval("String(document.querySelectorAll('[data-eksiengel-hold-menu] button').length)").toInt()
}
