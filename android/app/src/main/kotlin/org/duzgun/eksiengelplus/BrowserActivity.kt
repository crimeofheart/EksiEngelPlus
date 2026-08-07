package org.duzgun.eksiengelplus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.datastore.ConfigRepository
import org.duzgun.eksiengelplus.feature.lists.ListsActivity
import org.duzgun.eksiengelplus.datastore.EksiConfig
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.runtime.OperationReconciler
import org.duzgun.eksiengelplus.ops.runtime.OperationWorker
import org.duzgun.eksiengelplus.ops.runtime.PausedOperation
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
    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var reconciler: OperationReconciler
    @Inject lateinit var db: org.duzgun.eksiengelplus.database.EksiDatabase

    private lateinit var web: WebView
    private lateinit var sessionBar: TextView
    private lateinit var resumeBar: android.view.ViewGroup
    private lateinit var bridge: BridgeHost

    /** The run the resume bar is currently offering, if any. */
    private var offered: PausedOperation? = null

    /** The same run's id, or a plain paused run's, when there is no parked request. */
    private var offeredId: String? = null

    /** Result ignored: a denial is survivable, and re-asking is the system's call. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        resumeBar = findViewById(R.id.resumeBar)
        findViewById<TextView>(R.id.resumeText).setOnClickListener { resumeOffered() }
        findViewById<TextView>(R.id.resumeCancel).setOnClickListener { cancelOffered() }
        findViewById<TextView>(R.id.listsEntry).setOnClickListener {
            startActivity(Intent(this, ListsActivity::class.java))
        }
        askForNotificationsOnce()

        web.configureForEksi(this)

        bridge = BridgeHost(
            context = this,
            allowedOrigins = allowedOriginsFor(base),
            onEnqueue = ::enqueue,
            onShare = ::share,
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

        // The first page load waits for stored config, so the very first render of
        // the menu already carries the right labels. Every later emission is a
        // settings change: pushed to the open page and folded into the preamble the
        // next document will read.
        lifecycleScope.launch {
            var loaded = false
            configRepository.config.collectLatest { config ->
                val json = Json.encodeToString(EksiConfig.serializer(), config)
                if (!loaded) {
                    loaded = true
                    bridge.install(web, configJson = json, iconDataUri = ICON_DATA_URI)
                    web.loadUrl(requested ?: base)
                } else {
                    bridge.updateConfig(web, configJson = json, iconDataUri = ICON_DATA_URI)
                }
            }
        }
    }

    /**
     * Asks for POST_NOTIFICATIONS, which from API 33 must be granted at run time.
     *
     * Asked here because this is the first screen, and asked once: the system
     * stops showing the dialog after a denial, and an operation's notification is
     * a convenience rather than a precondition -- OpsNotifier already degrades
     * without it.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun render(state: SessionState) {
        sessionBar.text = when (state) {
            is SessionState.LoggedIn -> "giriş yapıldı: ${state.nick}"
            SessionState.LoggedOut -> "giriş yapılmadı — devam etmek için giriş yapın"
            SessionState.Unknown -> "…"
        }
        if (state is SessionState.LoggedIn) offerResume() else hideResumeOffer()
    }

    /**
     * A session reappearing is the only thing that makes a PAUSED_AUTH run
     * runnable again, and nothing else in the app is watching for it.
     *
     * Offered rather than resumed: the user may have logged in to read, not to
     * restart a run they walked away from hours ago.
     */
    private fun offerResume() {
        lifecycleScope.launch {
            // Auth-parked runs first, since those are the ones the login just
            // unblocked. Any other paused run is offered too: pausing from the
            // notification used to leave no way back into the run from the app.
            offered = reconciler.pausedForAuth().firstOrNull()
            offeredId = offered?.operationId ?: reconciler.resumable().firstOrNull()
            resumeBar.visibility = if (offeredId == null) View.GONE else View.VISIBLE
        }
    }

    private fun hideResumeOffer() {
        offered = null
        offeredId = null
        resumeBar.visibility = View.GONE
    }

    /** Abandons the parked run rather than resuming it. */
    private fun cancelOffered() {
        val id = offeredId ?: return
        lifecycleScope.launch {
            reconciler.cancel(id)
            android.widget.Toast.makeText(
                this@BrowserActivity,
                getString(R.string.resume_cancelled),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            hideResumeOffer()
        }
    }

    private fun resumeOffered() {
        val parked = offered
        val id = offeredId
        when {
            parked != null -> reconciler.resume(parked)
            id != null -> OperationWorker.enqueueExisting(WorkManager.getInstance(applicationContext), id)
        }
        hideResumeOffer()
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
        // Suspending now: the request is recorded before the work is scheduled,
        // because WorkManager's 10 KB input-data cap cannot hold a long target list.
        lifecycleScope.launch {
            OperationWorker.enqueue(
                WorkManager.getInstance(applicationContext),
                db = db,
                operationId = UUID.randomUUID().toString(),
                request = request,
            )
        }
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
