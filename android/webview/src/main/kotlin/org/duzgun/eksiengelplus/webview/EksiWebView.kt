package org.duzgun.eksiengelplus.webview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()

        // Anything that is not plain web content gets inspected before it is
        // handed anywhere. This is where users were being lost: the page fires an
        // app-open URL, and blindly ACTION_VIEWing it launches the official Ekşi
        // app -- from inside this one.
        if (scheme != "http" && scheme != "https") {
            return handleNonWebScheme(view, url.toString(), scheme)
        }

        val host = url.host ?: return true
        if (isEksiHost(host) || allowedHosts.any { host == it || host.endsWith(".$it") }) return false

        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: ActivityNotFoundException) {
            // No browser to hand off to. Refusing to load is still correct --
            // better a dead tap than an arbitrary page inside the bridge origin.
            true
        }
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

        if (scheme == "intent") {
            // A non-Ekşi app intent. Let the system decide, but never crash if
            // nothing handles it.
            return runCatching {
                parsed?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                parsed?.let { context.startActivity(it) }
                true
            }.getOrDefault(true)
        }

        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(true)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        CookieManager.getInstance().flush()
        onNavigated(url)
    }
}

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
