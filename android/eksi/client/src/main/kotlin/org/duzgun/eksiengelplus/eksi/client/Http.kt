package org.duzgun.eksiengelplus.eksi.client

import okhttp3.Interceptor
import okhttp3.Response

/** Supplies the Cookie header for a URL. Implemented over WebView's CookieManager. */
fun interface CookieSource {
    fun cookieHeader(url: String): String?
}

/** Receives Set-Cookie values so sliding expiry is not lost. */
fun interface CookieSink {
    fun store(url: String, setCookie: String)
}

/**
 * The two headers Ekşi requires on every request (relationHandler.js:142-145).
 *
 * x-requested-with is not cosmetic: without it /relation-list answers HTTP 500,
 * measured on device during android-spike. No Origin is sent.
 */
class EksiHeadersInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("x-requested-with", "XMLHttpRequest")
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .build()
        return chain.proceed(req)
    }
}

/** Reads cookies from the WebView jar and writes rotated ones back. */
class CookieBridgeInterceptor(
    private val source: CookieSource,
    private val sink: CookieSink,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()
        val builder = chain.request().newBuilder()
        source.cookieHeader(url)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        val res = chain.proceed(builder.build())
        res.headers("Set-Cookie").forEach { sink.store(url, it) }
        return res
    }
}

/** Raised when the session is gone. Never retried -- see SessionExpiry below. */
class SessionExpiredException(val reason: String) : java.io.IOException("session expired: $reason")

/**
 * Classifies session loss.
 *
 * A session cannot be renewed headlessly: /giris is behind Cloudflare Turnstile,
 * so re-authentication requires a human in a real browser. Anything that looks
 * like expiry must therefore stop the caller rather than trigger a retry loop
 * that can never succeed.
 */
object SessionExpiry {
    fun isLoginRedirect(code: Int, location: String?): Boolean =
        code in 300..399 && location?.contains("giris", ignoreCase = true) == true

    fun isDenied(code: Int): Boolean = code == 401 || code == 403

    /** A JSON endpoint answering with markup means we were bounced to a page. */
    fun looksLikeHtml(body: String?): Boolean {
        val t = body?.trimStart()?.take(200)?.lowercase() ?: return false
        return t.startsWith("<!doctype") || t.startsWith("<html")
    }
}
