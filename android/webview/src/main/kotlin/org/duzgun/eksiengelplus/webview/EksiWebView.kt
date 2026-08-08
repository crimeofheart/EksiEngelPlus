package org.duzgun.eksiengelplus.webview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.duzgun.eksiengelplus.network.UserAgent

/**
 * Restricts the WebView to Ekşi and sends everything else out to the system
 * browser.
 *
 * This is what makes the bridge's origin allowlist meaningful: an allowlist on
 * the message listener is only a boundary if off-site pages cannot load inside
 * the privileged context in the first place. Ekşi is full of user-posted links,
 * so this is a routine path, not an edge case.
 */
class EksiWebViewClient(
    private val context: Context,
    private val allowedHosts: Set<String>,
    private val onNavigated: (String?) -> Unit,
    /** Read per request, so a settings change takes effect without a reload. */
    private val blockAds: () -> Boolean = { true },
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()

        /*
         * Sub-frames are the page's business, not ours.
         *
         * This policy exists to stop the user being carried off-site by a link
         * they tapped. An iframe is not a tap: the profile page embeds
         * eksiseyler.com, and treating that embed as an off-site navigation meant
         * trying to hand a widget to an external browser, then sitting on the
         * frame for thirty seconds before it failed. Cross-origin framing is
         * already governed by the embedded site's own headers.
         */
        if (!request.isForMainFrame) return false

        // Anything that is not plain web content gets inspected before it is
        // handed anywhere. This is where users were being lost: the page fires an
        // app-open URL, and blindly ACTION_VIEWing it launches the official Ekşi
        // app -- from inside this one.
        if (scheme != "http" && scheme != "https") {
            return handleNonWebScheme(view, url.toString(), scheme)
        }

        val host = url.host ?: return true
        val ours = isEksiHost(host) || allowedHosts.any { host == it || host.endsWith(".$it") }

        if (ours && request.isForMainFrame && isXhrOnlyPartial(url.path)) {
            fetchPartialInPage(view, url.toString())
            return true
        }

        if (ours) return false

        // A browser, never another app: see openOutside.
        return openOutside(url)
    }

    /**
     * Drops third-party advertising and analytics requests.
     *
     * onPageFinished waits for every subresource, and the page's own scripts
     * queue behind them, which is why the vote and block controls arrived so
     * late. Measured on a real device: a single homepage load pulled in eleven
     * third-party hosts and took 18.2 seconds from loadUrl to finished, against
     * 0.3 seconds for the document itself.
     *
     * Returns an empty 200 rather than an error, so a script that expects a
     * response gets one and fails fast instead of retrying or hanging.
     *
     * Deliberately narrow. Ekşi's own hosts and the font CDNs are untouched:
     * ekstat.com serves the site's images, and blocking fonts trades a load win
     * for a visible rendering change.
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!blockAds()) return null
        val host = request.url.host?.lowercase() ?: return null
        if (!isAdOrTrackerHost(host)) return null
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    /**
     * Loads an XHR-only fragment the way the site would have.
     *
     * A safety net, not a reimplementation: when the site's own tab handler runs,
     * no navigation happens and this is never reached. It exists for when the
     * click falls through to the anchor's href, which is a plain navigation and
     * always fails.
     *
     * credentials are same-origin so the session travels; the header is the whole
     * point, since without it these paths answer 500.
     */
    private fun fetchPartialInPage(view: WebView, url: String) {
        val js = """
        (function () {
          var target = document.getElementById('content') || document.body;
          fetch('$url', {
            headers: { 'x-requested-with': 'XMLHttpRequest' },
            credentials: 'same-origin'
          })
            .then(function (r) { return r.ok ? r.text() : Promise.reject(r.status); })
            .then(function (html) {
              target.innerHTML = html;
              window.scrollTo(0, 0);
            })
            .catch(function (e) { console.error('eksiengel: partial load failed', e); });
        })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /**
     * Handles intent:// and custom-scheme URLs.
     *
     * The mobile site pushes readers into the official app with these. Following
     * one means a tap inside this client opens a competing one, so an Ekşi-bound
     * app intent is swallowed and its web equivalent loaded here instead.
     *
     * Unrelated schemes -- mailto, tel -- are still handed to the system, because
     * those are things this app genuinely cannot do.
     */
    private fun handleNonWebScheme(view: WebView, raw: String, scheme: String?): Boolean {
        val parsed = if (scheme == "intent") {
            runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
        } else {
            null
        }

        // Prefer the site's own fallback: it is the same content as a web URL.
        val fallback = parsed?.getStringExtra("browser_fallback_url")
        if (fallback != null && Uri.parse(fallback).host?.let(::isEksiHost) == true) {
            view.loadUrl(fallback)
            return true
        }

        val targetsEksiApp = parsed?.`package`?.contains("eksisozluk", ignoreCase = true) == true ||
            scheme?.startsWith("eksi") == true

        if (targetsEksiApp) {
            // Swallowed. The user is already in a client; bouncing them into
            // another is never what the tap meant.
            return true
        }

        // An intent whose data is an Ekşi page, with no package naming the app.
        // Starting it hands the URL to whichever app has verified the domain --
        // which on a device with the official client installed is the official
        // client, and the user is silently thrown out of this app.
        val intentData = parsed?.data
        if (intentData?.host?.let(::isEksiHost) == true) {
            view.loadUrl(intentData.toString())
            return true
        }

        if (scheme == "intent") {
            // A non-Ekşi app intent. Let the system decide, but never crash if
            // nothing handles it.
            return runCatching {
                parsed?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                parsed?.let { context.startActivity(it) }
                true
            }.getOrDefault(true)
        }

        return openOutside(Uri.parse(raw))
    }

    /**
     * Hands a URL to a browser, never to an app.
     *
     * A plain ACTION_VIEW is resolved by app-link verification, so any app that
     * has verified the domain wins with no chooser. On a device with the official
     * Ekşi client installed that means an off-site hop can silently replace this
     * app with that one, taking the user's session and our injected controls with
     * it.
     *
     * The browser-only selector asks for something that handles bare http, which
     * apps claiming a specific domain do not.
     */
    private fun openOutside(url: Uri): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                selector = Intent(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    data = Uri.fromParts("https", "", null)
                }
            },
        )
        true
    }.getOrDefault(true)

    override fun onPageFinished(view: WebView, url: String?) {
        CookieManager.getInstance().flush()
        onNavigated(url)
    }

    /**
     * Surfaces failing sub-requests.
     *
     * Without this a page that half-renders looks identical to a slow one: the
     * WebView reports nothing, and the only evidence is on the wire.
     */
    override fun onReceivedHttpError(
        view: WebView,
        request: android.webkit.WebResourceRequest,
        errorResponse: android.webkit.WebResourceResponse,
    ) {
        android.util.Log.w(
            "EksiWebView",
            "HTTP ${errorResponse.statusCode} for ${request.url} " +
                "(mainFrame=${request.isForMainFrame})",
        )
    }
}

/** Page-side console output, which is otherwise dropped on the floor. */
class EksiChromeClient : android.webkit.WebChromeClient() {
    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
        android.util.Log.d(
            "EksiConsole",
            "${msg.messageLevel()} ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}",
        )
        return true
    }
}

/**
 * Third-party advertising, analytics and audience-measurement hosts.
 *
 * Every entry was observed on a single Ekşi page load on a real device. Matching
 * is on the registrable suffix so subdomains are covered without listing each
 * one -- region1.google-analytics.com and securepubads.g.doubleclick.net both
 * arrived alongside their parents.
 */
private val AD_AND_TRACKER_HOSTS = setOf(
    "googletagmanager.com",
    "google-analytics.com",
    "googleadservices.com",
    "googlesyndication.com",
    "doubleclick.net",
    "scorecardresearch.com",
    "semasio.net",
    "gemius.pl",
    "nativespot.com",
    "networkad.net",
    "gelirartisi.com",
    "adnxs.com",
    "criteo.com",
    "taboola.com",
    "outbrain.com",
)

internal fun isAdOrTrackerHost(host: String): Boolean =
    AD_AND_TRACKER_HOSTS.any { host == it || host.endsWith(".$it") }

/**
 * Paths that only ever answer to an XHR.
 *
 * Ekşi serves these as bare fragments and returns HTTP 500 to a plain
 * navigation, whatever headers a browser sends -- only
 * `x-requested-with: XMLHttpRequest` gets a 200. The profile tabs link to them
 * directly (`<a class="tab-trigger" href="/son-entryleri?nick=...">`), so any
 * click that reaches the href instead of the site's handler lands on an error
 * page. That is the "entry'ler is slow and sometimes 500s" report.
 *
 * Every entry here was checked against the live site: these three answer 500 to a
 * navigation and 200 to an XHR. /istatistik looks like a sibling and is not --
 * it is an ordinary page, and intercepting it would break it.
 */
private val XHR_ONLY_PATHS = setOf(
    "/son-entryleri",
    "/favori-entryleri",
    "/en-cok-favorilenen-entryleri",
)

internal fun isXhrOnlyPartial(path: String?): Boolean =
    path != null && XHR_ONLY_PATHS.contains(path.trimEnd('/').lowercase())

/** Applies the settings the browsing surface needs, and none it does not. */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureForEksi(context: Context) {
    settings.apply {
        javaScriptEnabled = true          // Turnstile and the site both require it
        domStorageEnabled = true
        // The WebView must present the same agent OkHttp sends, or a session
        // established here may not be accepted there.
        userAgentString = UserAgent.of(context)
        allowFileAccess = false
        allowContentAccess = false
        // target="_blank" and window.open load in this same WebView rather than
        // asking for a new window we would then have to route somewhere.
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    webChromeClient = EksiChromeClient()
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        // Cloudflare challenge frames need this even though Ekşi's own login is
        // first-party.
        setAcceptThirdPartyCookies(this@configureForEksi, true)
    }
}

/**
 * Any Ekşi property stays in the app.
 *
 * Handing one to the system is worse than it looks: the browser opens, then
 * Android app-link handling forwards it to the OFFICIAL Ekşi app, so a tap inside
 * this client silently lands the user in a different one. An exact-match list
 * would let any mirror or sibling site do that, and mirrors exist precisely
 * because the site is periodically blocked in Turkey.
 *
 * The family is wider than the dictionary itself -- eksiup hosts images,
 * eksiseyler is the content arm -- so matching is by prefix rather than by name.
 *
 * A dot-separated LABEL must start with "eksi"; a bare substring test would drag
 * in unrelated hosts, "meksika" being the obvious Turkish one. That still admits
 * something like eksik.com, which is a fair trade: the cost is one page rendering
 * in the app instead of the browser, against the cost of silently handing users
 * to a competing client.
 */
fun isEksiHost(host: String): Boolean {
    val h = host.lowercase()
    if (h.split('.').any { it.startsWith("eksi") }) return true
    return EKSI_OWNED_HOSTS.any { h == it || h.endsWith(".$it") }
}

/**
 * Ekşi properties whose names contain no "eksi" at all.
 *
 * soz.lk is their link shortener, and it is the one that actually bit: an entry
 * embedding a soz.lk image link looked external, so it went to the browser, which
 * followed the redirect to an Ekşi domain, which app-links then handed to the
 * official app. A shortener defeats host matching by design -- the destination is
 * only knowable after the redirect -- so it has to be named.
 *
 * sourtimes.org is the historical domain and still redirects here.
 */
private val EKSI_OWNED_HOSTS = setOf(
    "soz.lk",
    "sourtimes.org",
)

/** Hosts that may load inside the WebView, beyond the Ekşi match above. */
fun allowedHostsFor(baseUrl: String): Set<String> =
    setOfNotNull(Uri.parse(baseUrl).host, "eksisozluk.com", "challenges.cloudflare.com")

/** Origin patterns for the bridge's allowlist. */
fun allowedOriginsFor(baseUrl: String): Set<String> =
    setOf(baseUrl.trimEnd('/'), "https://eksisozluk.com")
