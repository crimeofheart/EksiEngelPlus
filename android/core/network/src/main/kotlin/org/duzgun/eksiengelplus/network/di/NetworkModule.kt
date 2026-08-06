package org.duzgun.eksiengelplus.network.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.duzgun.eksiengelplus.eksi.client.CookieBridgeInterceptor
import org.duzgun.eksiengelplus.eksi.client.EksiHeadersInterceptor
import org.duzgun.eksiengelplus.eksi.client.RelationClient
import org.duzgun.eksiengelplus.eksi.client.ScrapeClient
import org.duzgun.eksiengelplus.network.UserAgent
import org.duzgun.eksiengelplus.network.WebViewCookieJar

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun cookieJar(): WebViewCookieJar = WebViewCookieJar().also { it.acceptCookies() }

    @Provides @Singleton
    fun okHttp(
        @ApplicationContext context: Context,
        jar: WebViewCookieJar,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(CookieBridgeInterceptor(jar, jar))
        .addInterceptor(EksiHeadersInterceptor(UserAgent.of(context)))
        // A 302 to /giris is how session expiry announces itself. Following it
        // would turn that signal into an HTML login page and lose the status code.
        .followRedirects(false)
        .build()

    @Provides @Singleton
    fun scrapeClient(http: OkHttpClient, base: BaseUrlHolder): ScrapeClient =
        ScrapeClient(http, baseUrlProvider = base::current)

    @Provides @Singleton
    fun relationClient(http: OkHttpClient, base: BaseUrlHolder): RelationClient =
        RelationClient(http, base::current)
}

/**
 * The base URL is mutable at runtime: eksisozluk.com is periodically blocked in
 * Turkey and the client falls back to a resolver endpoint. Holding it behind an
 * indirection means the OkHttp graph does not have to be rebuilt when it changes.
 */
@Singleton
class BaseUrlHolder @javax.inject.Inject constructor() {
    @Volatile private var value: String = DEFAULT
    fun current(): String = value
    fun set(url: String) { value = url.trimEnd('/') }
    companion object { const val DEFAULT = "https://eksisozluk.com" }
}
