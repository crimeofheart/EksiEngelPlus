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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.duzgun.eksiengelplus.ui.fitContentInsideSystemBars
import org.duzgun.eksiengelplus.ui.onPullToRefresh
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
    @Inject lateinit var identityRepository: org.duzgun.eksiengelplus.datastore.IdentityRepository

    private lateinit var web: WebView
    private lateinit var webRefresh: SwipeRefreshLayout
    private lateinit var sessionBar: TextView
    private lateinit var resumeBar: android.view.ViewGroup
    private lateinit var bridge: BridgeHost
    private lateinit var loadingCover: TextView

    /** Latest stored config, so per-request checks do not touch the store. */
    @Volatile private var currentConfig: EksiConfig = EksiConfig()

    /** The run the resume bar is currently offering, if any. */
    private var offered: PausedOperation? = null

    /** The same run's id, or a plain paused run's, when there is no parked request. */
    private var offeredId: String? = null

    /**
     * Set when the bar is swiped away.
     *
     * Deliberately not persisted: a swipe means "not now", so the offer returns
     * on the next launch. Only iptal deletes the run.
     */
    private var resumeDismissedThisRun = false

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
        fitContentInsideSystemBars()
        web = findViewById(R.id.web)
        webRefresh = findViewById(R.id.webRefresh)
        webRefresh.onPullToRefresh { reloadPage() }
        sessionBar = findViewById(R.id.sessionBar)
        loadingCover = findViewById(R.id.loadingCover)
        resumeBar = findViewById(R.id.resumeBar)
        findViewById<TextView>(R.id.resumeResume).setOnClickListener { resumeOffered() }
        findViewById<TextView>(R.id.resumeCancel).setOnClickListener { cancelOffered() }
        installResumeBarSwipe()
        findViewById<TextView>(R.id.listsEntry).setOnClickListener {
            startActivity(Intent(this, ListsActivity::class.java))
        }
        findViewById<TextView>(R.id.settingsEntry).setOnClickListener {
            startActivity(Intent(this, org.duzgun.eksiengelplus.feature.settings.SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.helpEntry).setOnClickListener {
            startActivity(Intent(this, org.duzgun.eksiengelplus.feature.settings.HelpActivity::class.java))
        }
        askForNotificationsOnce()

        web.configureForEksi(this)

        bridge = BridgeHost(
            context = this,
            allowedOrigins = allowedOriginsFor(base),
            onEnqueue = ::enqueue,
            onShare = ::share,
            onNavigating = ::coverLoad,
        )

        web.webViewClient = EksiWebViewClient(
            context = this,
            allowedHosts = allowedHostsFor(base),
            blockAds = { currentConfig.blockAds },
            onNavigated = { url: String? ->
                // Only re-probe on pages that can actually change the session;
                // every page would mean a network round trip per navigation.
                if (sessionMonitor.shouldReprobe(url)) {
                    lifecycleScope.launch { sessionMonitor.refreshNow() }
                }
                bridge.pendingFallbackScript?.let { web.evaluateJavascript(it, null) }
                loadingCover.visibility = View.GONE
                webRefresh.isRefreshing = false
            },
        )

        /*
         * Reconcile before anything reads operation state.
         *
         * A row left RUNNING by a process that died is neither terminal nor
         * resumable, so it shows as an operation in progress that nothing can act
         * on -- the lists screen warns about a run that is not happening and the
         * resume bar has nothing to offer. This is what turns it into INTERRUPTED,
         * which is resumable, and until now nothing called it.
         */
        lifecycleScope.launch { reconciler.reconcile() }
        // Before the config is first read, so a corrected default is what the
        // screens and the engine see rather than the stale stored value.
        lifecycleScope.launch { configRepository.migrate() }
        showReleaseNotesOnce(savedInstanceState)
        // Drains anything the last run recorded. Inert without a key.
        org.duzgun.eksiengelplus.ops.runtime.TelemetryWorker.enqueue(
            WorkManager.getInstance(applicationContext),
            org.duzgun.eksiengelplus.ops.runtime.BuildConfig.TELEMETRY_KEY,
        )

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
                currentConfig = config
                val json = org.duzgun.eksiengelplus.datastore.BridgeConfigJson.encode(config)
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

    /**
     * The swipe-down reload.
     *
     * The spinner is cleared by onPageFinished, which fires for error pages too,
     * so a failed reload does not leave it turning. The timeout is the same guard
     * the loading cover uses, for the load that never comes back at all: a
     * WebView stuck on an unresponsive host reports nothing, and the spinner is
     * the one thing on screen that would then be lying.
     */
    private fun reloadPage() {
        web.reload()
        webRefresh.postDelayed({ webRefresh.isRefreshing = false }, COVER_TIMEOUT_MS)
    }

    /**
     * Covers the load with the destination's name.
     *
     * Hidden again on the next onPageFinished. A timeout also clears it, so a
     * navigation that never completes cannot leave the browser behind a panel.
     */
    private fun coverLoad(label: String, topPx: Int, leftPx: Int) {
        // Start where the page starts, so the tab strip and the bar under it stay
        // visible while the load happens -- the same line the drag stops at.
        (loadingCover.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.topMargin = topPx.coerceAtLeast(0)
            loadingCover.layoutParams = it
        }
        // Under the tab it names, rather than centred: the label is telling the
        // user which tab is arriving, so it belongs where that tab is.
        loadingCover.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        loadingCover.setPadding(
            (leftPx + COVER_TEXT_LEFT_NUDGE_PX).coerceAtLeast(0),
            COVER_TEXT_TOP_PAD_PX,
            0,
            0,
        )
        loadingCover.text = getString(R.string.tab_loading, label)
        loadingCover.visibility = View.VISIBLE
        loadingCover.postDelayed({ loadingCover.visibility = View.GONE }, COVER_TIMEOUT_MS)
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
            val show = offeredId != null && !resumeDismissedThisRun
            resumeBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun hideResumeOffer() {
        offered = null
        offeredId = null
        resumeBar.visibility = View.GONE
    }

    /**
     * Swipe the bar aside to silence it for this run of the app.
     *
     * A dismissal, not a decision: the run stays parked and the offer comes back
     * next launch. Horizontal only, so it cannot be triggered while scrolling.
     */
    private fun installResumeBarSwipe() {
        var downX = 0f
        var downY = 0f
        resumeBar.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    // Claim the gesture. Returning false here says "not
                    // interested", and Android then delivers no MOVE at all, so
                    // the swipe could never be seen. The buttons are separate
                    // views and consume their own touches before this runs.
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (kotlin.math.abs(dx) > SWIPE_MIN_PX &&
                        kotlin.math.abs(event.rawY - downY) < SWIPE_MAX_DRIFT_PX
                    ) {
                        view.animate().translationX(if (dx > 0) view.width.toFloat() else -view.width.toFloat())
                            .alpha(0f).setDuration(150).withEndAction {
                                resumeDismissedThisRun = true
                                view.translationX = 0f
                                view.alpha = 1f
                                hideResumeOffer()
                            }.start()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
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

    /**
     * Shows what changed, on the first launch after an install or an upgrade.
     *
     * The extension opens welcome.html on both INSTALL and UPDATE
     * (background.js:1095-1101); an Android user takes an unattended Play update
     * and would otherwise be told nothing at all.
     *
     * Skipped on a recreate -- a rotation is not a launch, and the claim itself
     * would swallow the notes rather than show them twice. The claim is what
     * makes it once-per-version, and it is atomic, so two entry points racing
     * cannot both win.
     */
    private fun showReleaseNotesOnce(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        lifecycleScope.launch {
            if (identityRepository.claimReleaseNotes(version)) {
                startActivity(
                    org.duzgun.eksiengelplus.feature.settings.ReleaseNotesActivity
                        .intent(this@BrowserActivity, version),
                )
            }
        }
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
         * Enough movement to be a swipe rather than a tap that wandered.
         *
         * Either direction dismisses: the bar is being pushed out of the way, and
         * which way it leaves is not a decision the user should have to make.
         */
        /**
         * The anchor's left edge is the edge of its tap target, not of its text,
         * so aligning to it alone sat every label slightly left of the tab it
         * names. This is the inner padding the site puts inside the anchor.
         */
        private const val COVER_TEXT_LEFT_NUDGE_PX = 38

        /** A little breathing room under the line the cover starts at. */
        private const val COVER_TEXT_TOP_PAD_PX = 24

        /** Long enough for a slow page, short enough not to strand the browser. */
        private const val COVER_TIMEOUT_MS = 6_000L

        private const val SWIPE_MIN_PX = 48f
        private const val SWIPE_MAX_DRIFT_PX = 56f

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
