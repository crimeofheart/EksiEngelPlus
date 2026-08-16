package org.duzgun.eksiengelplus.webview

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Hosts the WebView in a real window so rAF-driven work actually runs. */
class BridgeTestActivity : Activity() {
    lateinit var web: WebView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
    }
}

/**
 * Loads HTML as if it came from [origin].
 *
 * Origin matters here rather than being incidental: both the document-start script
 * and the message listener are scoped by it, so content served from the wrong
 * origin is exactly what the bridge is supposed to refuse.
 */
fun ActivityScenario<BridgeTestActivity>.loadHtml(origin: String, html: String) {
    val loaded = CountDownLatch(1)
    onActivity { activity ->
        activity.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) = loaded.countDown()
        }
        activity.web.settings.javaScriptEnabled = true
        activity.web.loadDataWithBaseURL(origin, html, "text/html", "utf-8", null)
    }
    check(loaded.await(20, TimeUnit.SECONDS)) { "page never finished loading" }
}

/** Evaluates [js] and returns the JSON-encoded result, blocking the test thread. */
fun ActivityScenario<BridgeTestActivity>.eval(js: String): String {
    val done = CountDownLatch(1)
    var result = ""
    onActivity { activity ->
        activity.web.evaluateJavascript(js) { value ->
            result = value
            done.countDown()
        }
    }
    check(done.await(20, TimeUnit.SECONDS)) { "script never returned" }
    return result.trim('"')
}

/** Lets the injector's rAF-plus-debounce coalescing complete. */
fun settle(passes: Int = 3) {
    repeat(passes) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(300)
    }
}

/**
 * A profile, shaped the way Ekşi shapes one.
 *
 * The two `.relation-link` anchors are the whole point: `data-add-caption` names
 * the relation and `data-added` says whether it is in place, which is what
 * decides the direction of the items we inject (script.js:475-516).
 *
 * `#button-blocked-link` is present because the injector removes it, and a
 * fixture without it would let that removal rot unnoticed.
 */
fun profileFixture(banned: Boolean, titlesBanned: Boolean): String = """
<html><body>
  <h1 id="user-profile-title" data-nick="testyazar"></h1>
  <input id="who" value="7">
  <ul class="profile-buttons">
    <li><a id="button-blocked-link" class="relation-link"
           data-add-caption="engelle" data-added="$banned">engelle</a></li>
    <li><a class="relation-link"
           data-add-caption="başlıklarını engelle" data-added="$titlesBanned">başlıklarını engelle</a></li>
  </ul>
</body></html>
"""

/**
 * A gündem row, shaped the way Ekşi shapes one.
 *
 * The trailing `<small>` is the entry count and the `?a=popular` is how this list
 * is sorted; neither is part of the title, and both are on every row of the real
 * page, so a fixture without them would test a row that does not exist.
 */
const val TOPIC_LIST_FIXTURE = """
<html><body>
  <ul class="topic-list partial">
    <li><a href="/mohamed-salah-ghaly--3459509?a=popular">mohamed salah ghaly <small>627</small></a></li>
  </ul>
</body></html>
"""

/**
 * A title page: its header, and the pager that carries the same address.
 *
 * "sonraki" links to `/slug--123?p=2`, which is a title address by every test the
 * hold applies except the one that excludes the pager.
 */
const val TITLE_PAGE_FIXTURE = """
<html><body>
  <h1 id="title" data-title="mohamed salah ghaly" data-id="3459509" data-slug="mohamed-salah-ghaly">
    <a href="/mohamed-salah-ghaly--3459509">mohamed salah ghaly</a>
  </h1>
  <div class="pager" data-pagecount="4" data-currentpage="1">
    <a class="next" href="/mohamed-salah-ghaly--3459509?p=2">sonraki</a>
  </div>
</body></html>
"""

/**
 * One entry, shaped the way Ekşi shapes one: the dropdown is identified by the
 * items it contains, not by position, so the markers have to be present.
 */
const val ENTRY_FIXTURE = """
<html><body>
  <li data-id="42" data-author="testyazar" data-author-id="7">
    <ul class="dropdown-menu">
      <li><a>şikayet</a></li>
      <li><a>modlog</a></li>
      <li><a>mesaj gönder</a></li>
      <li><a>engelle</a></li>
    </ul>
  </li>
</body></html>
"""
