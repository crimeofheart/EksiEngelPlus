package org.duzgun.eksiengelplus.feature.lists

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.duzgun.eksiengelplus.database.CompletedOperationEntity
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.duzgun.eksiengelplus.ops.runtime.OperationCommand
import org.duzgun.eksiengelplus.ops.runtime.OperationCommandBus
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

    private lateinit var running: ViewGroup
    private lateinit var runningEmpty: TextView
    private lateinit var queued: ViewGroup
    private lateinit var queuedEmpty: TextView
    private lateinit var finished: ViewGroup
    private lateinit var finishedEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operations)
        title = getString(R.string.ops_title)

        running = findViewById(R.id.opsRunning)
        runningEmpty = findViewById(R.id.opsRunningEmpty)
        queued = findViewById(R.id.opsQueued)
        queuedEmpty = findViewById(R.id.opsQueuedEmpty)
        finished = findViewById(R.id.opsFinished)
        finishedEmpty = findViewById(R.id.opsFinishedEmpty)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    db.checkpoints().observeAll().collect { renderRunning(it) }
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

    private var queuedTasks: List<org.duzgun.eksiengelplus.database.QueuedTaskEntity> = emptyList()
    private var pendingCheckpoints: List<OperationCheckpointEntity> = emptyList()

    private fun renderRunning(all: List<OperationCheckpointEntity>) {
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
        runningEmpty.visibility = if (live.isEmpty()) View.VISIBLE else View.GONE

        for (cp in live) {
            val state = runCatching { OperationState.valueOf(cp.state) }.getOrNull() ?: continue
            val row = section()
            row.addView(
                label("${sourceName(cp.type)} · ${stateName(state)}", bold = true),
            )
            // Two runs of the same source are otherwise indistinguishable.
            row.addView(label(whenText(cp.startedAt), small = true))
            row.addView(
                label(
                    getString(R.string.ops_progress, cp.processed, cp.total, cp.successful, cp.failed),
                    small = true,
                ),
            )

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
            queued.addView(
                action(R.string.ops_clear_pending) {
                    lifecycleScope.launch {
                        pending.forEach { db.checkpoints().remove(it.operationId) }
                    }
                },
            )
        }

        for (cp in pending) {
            queued.addView(
                pendingRow(
                    "${sourceName(cp.type)} · ${getString(R.string.ops_pending)}",
                    whenText(cp.startedAt),
                ) { lifecycleScope.launch { db.checkpoints().remove(cp.operationId) } },
            )
        }

        for (task in all) {
            queued.addView(
                pendingRow(
                    sourceName(BanSource.fromPk(task.banSourcePk)?.name ?: "?"),
                    whenText(task.enqueuedAt),
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
                    "${sourceName(BanSource.fromPk(op.banSourcePk)?.name ?: "?")} · ${whenText(op.finishedAt)}",
                    bold = true,
                ),
            )
            row.addView(
                label(
                    getString(R.string.ops_progress, op.processed, op.processed, op.successful, op.failed),
                    small = true,
                ),
            )
            finished.addView(row)
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
    private fun pendingRow(title: String, whenText: String, onRemove: () -> Unit): LinearLayout {
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
     * What the run is, in the language the rest of the app speaks.
     *
     * The stored type is a BanSource constant, and showing it raw put "fav" and
     * "follow" on a Turkish screen.
     */
    private fun sourceName(raw: String): String = getString(
        when (runCatching { BanSource.valueOf(raw) }.getOrNull()) {
            BanSource.SINGLE -> R.string.src_single
            BanSource.FAV -> R.string.src_fav
            BanSource.FOLLOW -> R.string.src_follow
            BanSource.LIST -> R.string.src_list
            BanSource.TITLE -> R.string.src_title
            BanSource.UNDOBANALL -> R.string.src_undobanall
            BanSource.UNMUTEALL -> R.string.src_unmuteall
            BanSource.BLOCKED_MUTED_TITLES -> R.string.src_blocked_muted_titles
            BanSource.MIGRATE_BLOCKED_TO_MUTED -> R.string.src_migrate
            BanSource.BLOCK_MUTED_USERS -> R.string.src_block_muted
            BanSource.DATE_BASED_BULK -> R.string.src_date_based
            BanSource.REFRESH_BLOCKED_LIST,
            BanSource.REFRESH_MUTED_LIST,
            BanSource.REFRESH_FOLLOWED_LIST,
            -> R.string.src_refresh
            null -> R.string.src_unknown
        },
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
