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
        val host = request.url.host ?: return true
        if (allowedHosts.any { host == it || host.endsWith(".$it") }) return false

        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, request.url).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: ActivityNotFoundException) {
            // No browser to hand off to. Refusing to load is still correct --
            // better a dead tap than an arbitrary page inside the bridge origin.
            true
        }
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
        setSupportMultipleWindows(false)
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

/** Hosts that may load inside the WebView. */
fun allowedHostsFor(baseUrl: String): Set<String> =
    setOfNotNull(Uri.parse(baseUrl).host, "eksisozluk.com", "challenges.cloudflare.com")

/** Origin patterns for the bridge's allowlist. */
fun allowedOriginsFor(baseUrl: String): Set<String> =
    setOf(baseUrl.trimEnd('/'), "https://eksisozluk.com")
