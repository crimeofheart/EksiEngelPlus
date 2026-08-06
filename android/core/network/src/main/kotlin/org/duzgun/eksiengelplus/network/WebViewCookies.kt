package org.duzgun.eksiengelplus.network

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import org.duzgun.eksiengelplus.eksi.client.CookieSink
import org.duzgun.eksiengelplus.eksi.client.CookieSource

/**
 * Bridges the WebView's cookie jar to OkHttp. Proven end to end on a real device
 * during android-spike: a session established interactively in the WebView
 * authenticated an OkHttp request and completed a block/unblock round trip.
 *
 * Deliberately NOT an OkHttp CookieJar. CookieManager.getCookie() hands back a
 * pre-assembled "a=1; b=2" header with no domain, path or expiry attributes, so
 * rebuilding okhttp3.Cookie objects from it loses information and gains nothing.
 */
class WebViewCookieJar(
    private val cookies: CookieManager = CookieManager.getInstance(),
    private val flusher: CookieFlusher = CookieFlusher(cookies),
) : CookieSource, CookieSink {

    override fun cookieHeader(url: String): String? = cookies.getCookie(url)

    override fun store(url: String, setCookie: String) {
        cookies.setCookie(url, setCookie)
        // Sliding-expiration renewals arrive as Set-Cookie on ordinary responses;
        // dropping them logs the user out early for no reason.
        flusher.requestFlush()
    }

    fun acceptCookies() {
        cookies.setAcceptCookie(true)
    }

    fun flushNow() = flusher.flushNow()
}

/**
 * CookieManager.flush() writes to disk. Calling it per response is wasteful, but
 * never calling it loses the session on process death, so it is debounced and
 * forced at checkpoints.
 */
class CookieFlusher(
    private val flush: () -> Unit,
    private val minIntervalMs: Long = 10_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(cookies: CookieManager) : this(flush = { cookies.flush() })

    // Not a sentinel timestamp: with lastFlush = 0 the first call would only
    // flush because wall-clock millis happen to be large, which is accidental
    // and breaks under an injected clock.
    @Volatile private var lastFlush: Long? = null

    fun requestFlush() {
        val now = clock()
        val last = lastFlush
        if (last == null || now - last >= minIntervalMs) {
            lastFlush = now
            flush()
        }
    }

    fun flushNow() {
        lastFlush = clock()
        flush()
    }
}

/** Whether this device can supply a WebView at all. */
sealed interface WebViewState {
    data object Available : WebViewState
    data class Unavailable(val reason: String) : WebViewState
}

/**
 * CookieManager.getInstance() implicitly loads the WebView provider and throws
 * when it is disabled or mid-update on some OEM builds. Without this the app
 * crashes at startup rather than explaining itself; the whole product depends on
 * a WebView, so the failure has to be legible.
 */
object WebViewAvailability {
    fun check(): WebViewState = try {
        CookieManager.getInstance()
        WebViewState.Available
    } catch (e: Throwable) {
        WebViewState.Unavailable(e.javaClass.simpleName + ": " + (e.message ?: "no message"))
    }
}

/**
 * OkHttp must send the same user agent the WebView used to establish the session.
 * OkHttp otherwise announces itself as okhttp/x.y, and if Ekşi binds a session to
 * its originating agent that mismatch silently redirects to login.
 *
 * Cached: building a WebView per request to read this would be absurd.
 */
object UserAgent {
    @Volatile private var cached: String? = null

    fun of(context: Context): String = cached ?: synchronized(this) {
        cached ?: WebSettings.getDefaultUserAgent(context).also { cached = it }
    }
}
