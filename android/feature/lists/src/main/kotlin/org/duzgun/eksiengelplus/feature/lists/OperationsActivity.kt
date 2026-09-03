package org.duzgun.eksiengelplus.feature.lists

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.duzgun.eksiengelplus.ui.fitContentInsideSystemBars
import org.duzgun.eksiengelplus.ui.onPullToRefresh
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.database.CompletedOperationEntity
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.duzgun.eksiengelplus.ops.runtime.OperationCommand
import org.duzgun.eksiengelplus.ops.runtime.OperationCommandBus
import org.duzgun.eksiengelplus.ops.runtime.OperationLabel
import org.duzgun.eksiengelplus.ops.runtime.OperationReconciler
import org.duzgun.eksiengelplus.ops.runtime.OperationWaits
import org.duzgun.eksiengelplus.ops.runtime.OperationWorker

/**
 * What the app is doing, has queued, and has finished.
 *
 * The extension shows this on its notification page; here it existed only as a
 * system notification, so a dismissed notification meant a run with no visible
 * state at all and no controls.
 *
 * Reads the same rows the engine writes -- checkpoints for live work, the
 * completed table for history -- rather than keeping a parallel record, so the
 * screen cannot disagree with what actually ran.
 */
@AndroidEntryPoint
class OperationsActivity : AppCompatActivity() {

    @Inject lateinit var db: EksiDatabase
    @Inject lateinit var commands: OperationCommandBus
    @Inject lateinit var waits: OperationWaits
    @Inject lateinit var reconciler: OperationReconciler
    @Inject lateinit var config: org.duzgun.eksiengelplus.datastore.ConfigRepository

    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var running: ViewGroup
    private lateinit var runningEmpty: TextView
    private lateinit var queued: ViewGroup
    private lateinit var queuedEmpty: TextView
    private lateinit var finished: ViewGroup
    private lateinit var finishedEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operations)
        fitContentInsideSystemBars()
        title = getString(R.string.ops_title)

        /*
         * The rows come from Room flows, so nothing here is ever stale for want
         * of a re-read -- but a checkpoint left RUNNING by a process that died is
         * stale in a way no flow can fix, and until now the only thing that
         * cleared it was a cold start of the browser screen. This is the screen
         * that shows the wrong row, so this is where the correction belongs.
         */
        refresh = findViewById(R.id.opsRefresh)
        refresh.onPullToRefresh {
            lifecycleScope.launch {
                reconciler.reconcile()
                refresh.isRefreshing = false
            }
        }

        running = findViewById(R.id.opsRunning)
        runningEmpty = findViewById(R.id.opsRunningEmpty)
        queued = findViewById(R.id.opsQueued)
        queuedEmpty = findViewById(R.id.opsQueuedEmpty)
        finished = findViewById(R.id.opsFinished)
        finishedEmpty = findViewById(R.id.opsFinishedEmpty)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    db.checkpoints().observeAll().collect { renderRunning(it, waits.remaining.value) }
                }
                launch {
                    /*
                     * The countdown retexts the rows it belongs to. It must not
                     * rebuild them.
                     *
                     * Rendering the section from a combined flow rebuilt every
                     * row once a second for the length of a cooldown --
                     * removeAllViews() and back again -- which destroyed the
                     * Duraklat and Durdur buttons under the user's finger
                     * between touch-down and touch-up. The taps went nowhere,
                     * and only ever during a cooldown, which is exactly when
                     * they are most wanted.
                     */
                    waits.remaining.collect(::retimeRunning)
                }
                launch {
                    db.queuedTasks().observeAll().collect { renderQueued(it) }
                }
                launch {
                    db.completedOperations().recent(50).collect { renderFinished(it) }
                }
            }
        }
    }

    /** The progress line per run, so a tick can retext it without a rebuild. */
    private val progressLabels = mutableMapOf<String, TextView>()
    private var runningCheckpoints: List<OperationCheckpointEntity> = emptyList()

    private fun retimeRunning(waiting: Map<String, Long>) {
        for (cp in runningCheckpoints) {
            progressLabels[cp.operationId]?.text = progressText(cp, waiting[cp.operationId] ?: 0L)
        }
    }

    private fun progressText(cp: OperationCheckpointEntity, waitMs: Long): String =
        getString(R.string.ops_progress, cp.processed, cp.total, cp.successful, cp.failed) +
            if (waitMs > 0L) " · " + getString(R.string.ops_rate_wait, (waitMs + 999) / 1000) else ""

    private var queuedTasks: List<org.duzgun.eksiengelplus.database.QueuedTaskEntity> = emptyList()
    private var pendingCheckpoints: List<OperationCheckpointEntity> = emptyList()

    private fun renderRunning(
        all: List<OperationCheckpointEntity>,
        waiting: Map<String, Long>,
    ) {
        pendingCheckpoints = all
            .filter { runCatching { OperationState.valueOf(it.state) }.getOrNull() == OperationState.IDLE }
            .distinctBy { it.operationId }
        renderPending()

        // Terminal rows belong in history, not here; a completed run left in the
        // live section would read as something still happening.
        val live = all
            .filter {
                val state = runCatching { OperationState.valueOf(it.state) }.getOrNull()
                // IDLE is scheduled, not started: the row is written when the work
                // is enqueued, so a run that never began was showing here as
                // something in progress at 0/0 -- five of them looked like the
                // same operation repeated.
                state != null && !state.isTerminal && state != OperationState.IDLE
            }
            // One row per run. A checkpoint is upserted many times during a run,
            // and reconciliation can leave more than one row for the same id;
            // showing each would read as several operations rather than one.
            .distinctBy { it.operationId }
        running.removeAllViews()
        progressLabels.clear()
        runningCheckpoints = live
        runningEmpty.visibility = if (live.isEmpty()) View.VISIBLE else View.GONE

        for (cp in live) {
            val state = runCatching { OperationState.valueOf(cp.state) }.getOrNull() ?: continue
            val row = section()
            row.addView(
                label("${runName(cp)} · ${stateName(state)}", bold = true),
            )
            // Two runs of the same source are otherwise indistinguishable.
            row.addView(label(whenText(cp.startedAt), small = true))
            // The API-limit wait, the same number the notification counts down.
            // Held so retimeRunning can update it in place each second.
            val progress = label(progressText(cp, waiting[cp.operationId] ?: 0L), small = true)
            progressLabels[cp.operationId] = progress
            row.addView(progress)

            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (state == OperationState.RUNNING) {
                controls.addView(
                    action(R.string.ops_pause) { commands.post(cp.operationId, OperationCommand.PAUSE) },
                )
            }
            if (state.isResumable) {
                controls.addView(
                    action(R.string.ops_resume) {
                        OperationWorker.enqueueExisting(
                            WorkManager.getInstance(applicationContext),
                            cp.operationId,
                        )
                    },
                )
            }
            controls.addView(
                action(R.string.ops_stop) { commands.post(cp.operationId, OperationCommand.STOP) },
            )
            row.addView(controls)
            running.addView(row)
        }
    }

    /**
     * What is waiting behind the current run.
     *
     * Its own section rather than mixed into the live one: a queued run has no
     * progress and no controls beyond removal, and showing it beside a running
     * one with an empty progress line read as a stalled operation.
     */
    private fun renderQueued(all: List<org.duzgun.eksiengelplus.database.QueuedTaskEntity>) {
        queuedTasks = all
        renderPending()
    }

    /** Scheduled-but-not-started runs and the explicit queue, together. */
    private fun renderPending() {
        val all = queuedTasks
        val pending = pendingCheckpoints
        queued.removeAllViews()
        queuedEmpty.visibility =
            if (all.isEmpty() && pending.isEmpty()) View.VISIBLE else View.GONE

        if (pending.size > 1) {
            // A full-width button of its own. action() carries weight 1, which in
            // this vertical column stretches to fill the screen while staying zero
            // wide -- an invisible control and a page of blank space above the
            // rows it was meant to sit over.
            queued.addView(
                TextView(this).apply {
                    text = getString(R.string.ops_clear_pending)
                    textSize = 12f
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = androidx.core.content.ContextCompat.getDrawable(
                        this@OperationsActivity,
                        R.drawable.bg_card,
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) }
                    setOnClickListener {
                        lifecycleScope.launch {
                            pending.forEach { db.checkpoints().remove(it.operationId) }
                        }
                    }
                },
            )
        }

        for (cp in pending) {
            queued.addView(
                pendingRow(
                    "${runName(cp)} · ${getString(R.string.ops_pending)}",
                    whenText(cp.startedAt),
                ) { lifecycleScope.launch { db.checkpoints().remove(cp.operationId) } },
            )
        }

        for (task in all) {
            queued.addView(
                pendingRow(
                    OperationLabel.of(
                        this,
                        BanSource.fromPk(task.banSourcePk),
                        OperationLabel.targetFromRequest(task.payloadJson),
                    ),
                    whenText(task.enqueuedAt),
                    task.payloadJson,
                ) { lifecycleScope.launch { db.queuedTasks().remove(task.id) } },
            )
        }
    }

    private fun renderFinished(all: List<CompletedOperationEntity>) {
        finished.removeAllViews()
        finishedEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE

        for (op in all) {
            val row = section()
            row.addView(
                label(
                    OperationLabel.of(
                        this,
                        BanSource.fromPk(op.banSourcePk),
                        // The request is gone by now; the summary is where the
                        // archiver put the nick.
                        OperationLabel.targetFromSummary(op.summaryJson),
                    ) + " · ${whenText(op.finishedAt)}",
                    bold = true,
                ),
            )
            row.addView(
                label(
                    getString(R.string.ops_progress, op.processed, op.processed, op.successful, op.failed),
                    small = true,
                ),
            )
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addRowActions(controls, op.requestJson)
            if (controls.childCount > 0) row.addView(controls)
            finished.addView(row)
        }
    }

    // -------------------------------------------------------- row actions

    /**
     * The request behind a row, or null when there is nothing to replay.
     *
     * History rows archived before requestJson existed decode to null, as do
     * any that were written without one. Both callers treat that as "offer no
     * actions" rather than reconstructing a request from the label.
     */
    private fun requestOf(json: String?): OperationRequest? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            Json.decodeFromString(OperationRequest.serializer(), json)
        }.getOrNull()
    }

    /** Queues the same request again, as a new run. */
    private fun retry(request: OperationRequest) {
        lifecycleScope.launch {
            OperationWorker.enqueue(
                WorkManager.getInstance(applicationContext),
                db = db,
                operationId = java.util.UUID.randomUUID().toString(),
                request = request,
            )
            showMessage(UiMessage(getString(R.string.ops_retry_queued)))
        }
    }

    /**
     * The page a run acted on.
     *
     * A plain address, so it still resolves when the author or title is already
     * blocked or muted -- those pages are reachable directly even once they stop
     * appearing in any listing. Built from the configured host so a run started
     * against a mirror opens on that mirror.
     */
    private fun sourceUrl(request: OperationRequest, base: String): String? {
        val origin = base.trimEnd('/')
        request.entryId?.let { return "$origin/entry/$it" }
        request.authorNick?.takeIf { it.isNotBlank() }
            ?.let { return "$origin/biri/${it.replace(" ", "-")}" }
        val slug = request.titleSlug
        val titleId = request.titleId
        if (!slug.isNullOrBlank() && titleId != null) return "$origin/$slug--$titleId"
        return null
    }

    private fun openSource(request: OperationRequest) {
        lifecycleScope.launch {
            val base = runCatching { config.config.first().eksiSozlukUrl }
                .getOrNull()
                ?: org.duzgun.eksiengelplus.datastore.EksiConfig.DEFAULT_BASE_URL
            val url = sourceUrl(request, base)
            if (url == null) {
                showMessage(UiMessage(getString(R.string.ops_open_unavailable)))
                return@launch
            }
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .setPackage(packageName),
            )
        }
    }

    /**
     * Adds "tekrarla" and "git" to a row, when the run carries enough to do so.
     *
     * Bulk and list-sourced runs have no single page to open, so "git" is left
     * off those rather than pointing somewhere arbitrary.
     */
    private fun addRowActions(into: ViewGroup, json: String?) {
        val request = requestOf(json) ?: return
        into.addView(action(R.string.ops_retry) { retry(request) })
        if (sourceUrl(request, org.duzgun.eksiengelplus.datastore.EksiConfig.DEFAULT_BASE_URL) != null) {
            into.addView(action(R.string.ops_open_source) { openSource(request) })
        }
    }

    // ------------------------------------------------------------ tiny views

    /**
     * A waiting entry, on one line.
     *
     * Nothing is happening to it, so it has no progress to show and one verb;
     * stacking a title, a timestamp and a full-width button gave three lines of
     * chrome to a row that says "later".
     */
    private fun pendingRow(
        title: String,
        whenText: String,
        requestJson: String? = null,
        onRemove: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(6), dp(6))
            background = androidx.core.content.ContextCompat.getDrawable(
                this@OperationsActivity,
                R.drawable.bg_card,
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }

        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(label(title, bold = true).apply { textSize = 14f })
        labels.addView(label(whenText, small = true))
        row.addView(labels)

        addRowActions(row, requestJson)

        row.addView(
            TextView(this).apply {
                text = getString(R.string.ops_remove)
                textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = android.util.TypedValue().let {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                    androidx.core.content.ContextCompat.getDrawable(this@OperationsActivity, it.resourceId)
                }
                setOnClickListener { onRemove() }
            },
        )
        return row
    }

    /** Bordered, so two entries of the same source do not read as one repeated. */
    private fun section() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = androidx.core.content.ContextCompat.getDrawable(
            this@OperationsActivity,
            R.drawable.bg_card,
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun label(text: String, bold: Boolean = false, small: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = if (small) 12f else 15f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (small) alpha = 0.7f
        }

    private fun action(labelRes: Int, onClick: () -> Unit) = Button(this).apply {
        text = getString(labelRes)
        textSize = 12f
        minHeight = dp(40)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(6) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * What the run is, with the nick it is about.
     *
     * The label comes from :ops:runtime so this screen and the notification for
     * the same run cannot word it differently; the nick comes out of the request
     * the checkpoint already stores, so nothing new had to be persisted for the
     * live and pending rows.
     */
    private fun runName(cp: OperationCheckpointEntity): String = OperationLabel.of(
        this,
        cp.type,
        OperationLabel.targetFromRequest(cp.requestJson),
    )

    /** The run's state, likewise. */
    private fun stateName(state: OperationState): String = getString(
        when (state) {
            OperationState.RUNNING -> R.string.state_running
            OperationState.PAUSING -> R.string.state_pausing
            OperationState.PAUSED -> R.string.state_paused
            OperationState.PAUSED_AUTH -> R.string.state_paused_auth
            OperationState.PAUSED_BUDGET -> R.string.state_paused_budget
            OperationState.PAUSED_NETWORK -> R.string.state_paused_network
            OperationState.STOPPING -> R.string.state_stopping
            OperationState.INTERRUPTED -> R.string.state_interrupted
            OperationState.IDLE -> R.string.ops_pending
            OperationState.STOPPED, OperationState.COMPLETED -> R.string.ops_finished
        },
    )

    private fun whenText(millis: Long): String =
        WHEN_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private companion object {
        val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}
