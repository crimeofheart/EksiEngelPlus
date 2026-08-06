package org.duzgun.eksiengelplus

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.datastore.EksiConfig
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.runtime.OperationWorker
import org.duzgun.eksiengelplus.webview.BridgeHost
import org.duzgun.eksiengelplus.webview.EksiWebViewClient
import org.duzgun.eksiengelplus.webview.SessionMonitor
import org.duzgun.eksiengelplus.webview.SessionState
import org.duzgun.eksiengelplus.webview.allowedHostsFor
import org.duzgun.eksiengelplus.webview.allowedOriginsFor
import org.duzgun.eksiengelplus.webview.configureForEksi

/**
 * The browsing surface: the real site with our menu items in it.
 *
 * Also the only way to obtain a session, since /giris is behind Cloudflare
 * Turnstile and needs a real browser context.
 */
@AndroidEntryPoint
class BrowserActivity : AppCompatActivity() {

    @Inject lateinit var sessionMonitor: SessionMonitor

    private lateinit var web: WebView
    private lateinit var sessionBar: TextView
    private lateinit var bridge: BridgeHost

    private val base = EksiConfig.DEFAULT_BASE_URL

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { it.startsWith("http") }?.let { web.loadUrl(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        web = findViewById(R.id.web)
        sessionBar = findViewById(R.id.sessionBar)

        web.configureForEksi(this)

        bridge = BridgeHost(
            context = this,
            allowedOrigins = allowedOriginsFor(base),
            onEnqueue = ::enqueue,
            onShare = ::share,
        )
        bridge.install(
            web,
            configJson = Json.encodeToString(EksiConfig.serializer(), EksiConfig()),
            iconDataUri = ICON_DATA_URI,
        )

        web.webViewClient = EksiWebViewClient(this, allowedHostsFor(base)) { url ->
            // Only re-probe on pages that can actually change the session; every
            // page would mean a network round trip per navigation.
            if (sessionMonitor.shouldReprobe(url)) {
                lifecycleScope.launch { sessionMonitor.refreshNow() }
            }
            bridge.pendingFallbackScript?.let { web.evaluateJavascript(it, null) }
        }

        lifecycleScope.launch {
            sessionMonitor.state.collectLatest { render(it) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        // A VIEW intent lets other apps -- and adb -- open a specific entry here
        // rather than in a browser.
        val requested = intent?.data?.toString()?.takeIf { it.startsWith("http") }
        web.loadUrl(requested ?: base)
    }

    private fun render(state: SessionState) {
        sessionBar.text = when (state) {
            is SessionState.LoggedIn -> "giriş yapıldı: ${state.nick}"
            SessionState.LoggedOut -> "giriş yapılmadı — devam etmek için giriş yapın"
            SessionState.Unknown -> "…"
        }
    }

    /**
     * The site offers per-network share destinations only. The system sheet
     * covers whatever the user actually has installed, which is what an Android
     * user expects from a share affordance.
     */
    private fun share(url: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun enqueue(request: OperationRequest) {
        OperationWorker.enqueue(
            WorkManager.getInstance(applicationContext),
            operationId = UUID.randomUUID().toString(),
            request = request,
        )
    }

    companion object {
        /**
         * Inlined rather than served.
         *
         * This is the only thing web_accessible_resources was providing, and that
         * mechanism exposed thirty files -- the shared API key among them -- to
         * every website. There is no Android equivalent and none is recreated.
         */
        private const val ICON_DATA_URI =
            "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIH" +
                "ZpZXdCb3g9IjAgMCAxNiAxNiI+PGNpcmNsZSBjeD0iOCIgY3k9IjgiIHI9IjciIGZpbGw9IiM4MW" +
                "MxNGIiLz48L3N2Zz4="
    }
}
