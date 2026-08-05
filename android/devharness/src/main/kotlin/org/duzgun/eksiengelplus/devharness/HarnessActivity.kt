package org.duzgun.eksiengelplus.devharness

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.duzgun.eksiengelplus.devharness.databinding.ActivityMainBinding
import org.duzgun.eksiengelplus.eksi.client.CookieBridgeInterceptor
import org.duzgun.eksiengelplus.eksi.client.EksiHeadersInterceptor
import org.duzgun.eksiengelplus.eksi.client.FollowEndpoint
import org.duzgun.eksiengelplus.eksi.client.RelationClient
import org.duzgun.eksiengelplus.eksi.client.RelationResult
import org.duzgun.eksiengelplus.eksi.client.ScrapeClient
import org.duzgun.eksiengelplus.eksi.client.SessionExpiredException
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.network.UserAgent
import org.duzgun.eksiengelplus.network.WebViewAvailability
import org.duzgun.eksiengelplus.network.WebViewCookieJar
import org.duzgun.eksiengelplus.network.WebViewState

/**
 * Dogfoods the production modules against the live site.
 *
 * Unlike the throwaway spike harness this replaced, every call below goes through
 * the real ScrapeClient, RelationClient, EksiHtmlParser and WebViewCookieJar.
 * A failure here is a failure in shipping code, which is the point.
 *
 * Temporary: deleted when android-foundations is archived.
 */
class HarnessActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val log = StringBuilder()

    private val base = "https://eksisozluk.com"
    private val jar by lazy { WebViewCookieJar() }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(CookieBridgeInterceptor(jar, jar))
            .addInterceptor(EksiHeadersInterceptor(UserAgent.of(this)))
            .followRedirects(false)
            .build()
    }

    private val scrape by lazy { ScrapeClient(http, baseUrlProvider = { base }) }
    private val relations by lazy { RelationClient(http) { base } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        out("EksiEngelPlus dev harness — PRODUCTION modules")
        when (val s = WebViewAvailability.check()) {
            is WebViewState.Available -> jar.acceptCookies()
            is WebViewState.Unavailable -> {
                out("!! WebView unavailable: ${s.reason}")
                return
            }
        }

        val ua = UserAgent.of(this)
        out("UA: $ua")
        out("")
        out("=== WebView capability floor ===")
        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        out("provider: ${pkg?.packageName} ${pkg?.versionName}")
        out("DOCUMENT_START_SCRIPT : ${WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)}")
        out("WEB_MESSAGE_LISTENER  : ${WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)}")
        out("")
        out("STEP 1 — Login, sign in (solve Turnstile).  STEP 2 — Run checks.")
        out("")

        b.web.settings.javaScriptEnabled = true
        b.web.settings.domStorageEnabled = true
        b.web.settings.userAgentString = ua
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(b.web, true)
        b.web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                b.status.text = "loaded: $url"
                jar.flushNow()
            }
        }

        b.btnLogin.setOnClickListener { b.web.loadUrl("$base/giris") }
        b.btnHome.setOnClickListener { b.web.loadUrl("$base/") }
        b.btnChecks.setOnClickListener { runChecks() }
        b.btnMutate.setOnClickListener { runMutation() }
        b.btnShare.setOnClickListener { share() }
    }

    private fun runChecks() = lifecycleScope.launch {
        try {
            out("=== cookie bridge (WebViewCookieJar -> OkHttp) ===")
            val nick = scrape.ownNick()
            if (nick == null) {
                out("NOT LOGGED IN — log in via the WebView first.")
                return@launch
            }
            out(">> authenticated as '$nick'")
            out("")

            out("=== profile (EksiHtmlParser) ===")
            val p = scrape.authorProfile(nick)
            out("  authorId         -> ${p.authorId}")
            out("  registrationDate -> ${p.registrationDate}  (parsed by TurkishDateParser)")
            out("")

            out("=== relation lists (1-indexed, IsLast termination) ===")
            for ((label, tt) in listOf(
                "blocked" to TargetType.USER,
                "titles" to TargetType.TITLE,
                "muted" to TargetType.MUTE,
            )) {
                runCatching {
                    val first = scrape.relationPage(tt, ScrapeClient.FIRST_PAGE)
                    out("  $label page1: items=${first.nicks.size} isLast=${first.isLast}" +
                        (first.nicks.firstOrNull()?.let { "  first='$it'" } ?: ""))
                }.onFailure { out("  $label FAILED: ${it.javaClass.simpleName} ${it.message}") }
            }
            out("")

            out("=== follower / following (empty-array termination) ===")
            for (ep in FollowEndpoint.entries) {
                runCatching {
                    val page = scrape.followPage(ep, nick, ScrapeClient.FIRST_PAGE)
                    out("  ${ep.path}: items=${page.size}" + (page.firstOrNull()?.let {
                        "  first='${it.nick.value}' id=${it.id} isBuddy=${it.isBuddy} followsMe=${it.isFollowCurrentUser}"
                    } ?: "  (empty = end of pagination)"))
                }.onFailure { out("  ${ep.path} FAILED: ${it.javaClass.simpleName} ${it.message}") }
            }
            out("")
            out("=== pageIndex=0 guard ===")
            runCatching { scrape.relationPage(TargetType.USER, 0) }
                .onSuccess { out("  !! page 0 was allowed — guard is broken") }
                .onFailure { out("  rejected before any request: ${it.message}") }

            out("")
            out(">> tap Share and send the results.")
        } catch (e: SessionExpiredException) {
            out("SESSION EXPIRED: ${e.reason}")
        } catch (e: Exception) {
            out("ERROR ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun runMutation() = lifecycleScope.launch {
        val target = b.target.text.toString().trim()
        if (target.isEmpty()) { out("!! type a target nick you control"); return@launch }
        try {
            val me = scrape.ownNick() ?: run { out("not logged in"); return@launch }
            val myId = scrape.authorProfile(me).authorId
            val id = scrape.authorProfile(target).authorId
            if (id == null) { out("could not resolve id for $target"); return@launch }
            out("=== mutation round trip (RelationClient) ===")
            out("actor=$me id=$myId | target=$target id=$id")
            if (id == myId) { out("!! that is you — the site answers 4. Use another account."); return@launch }

            val add = relations.perform(BanMode.BAN, TargetType.USER, id)
            out("  addrelation    -> $add")
            val rm = relations.perform(BanMode.UNDOBAN, TargetType.USER, id)
            out("  removerelation -> $rm")
            if (add is RelationResult.Success && rm is RelationResult.Success) {
                out(">> production RelationClient works end to end")
            }
        } catch (e: Exception) {
            out("ERROR ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun out(s: String) {
        log.append(s).append('\n')
        runOnUiThread {
            b.results.text = log.toString()
            b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun share() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, log.toString())
                },
                "harness results",
            ),
        )
    }
}
