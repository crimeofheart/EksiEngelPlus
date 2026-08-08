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

    private fun renderRunning(all: List<OperationCheckpointEntity>) {
        // Terminal rows belong in history, not here; a completed run left in the
        // live section would read as something still happening.
        val live = all
            .filter {
                val state = runCatching { OperationState.valueOf(it.state) }.getOrNull()
                state != null && !state.isTerminal
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
                label(
                    "${sourceName(cp.type)} · ${state.name.lowercase()}",
                    bold = true,
                ),
            )
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
        queued.removeAllViews()
        queuedEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE

        for (task in all) {
            val row = section()
            row.addView(
                label(sourceName(BanSource.fromPk(task.banSourcePk)?.name ?: "?"), bold = true),
            )
            row.addView(label(whenText(task.enqueuedAt), small = true))
            row.addView(
                action(R.string.ops_remove) {
                    lifecycleScope.launch { db.queuedTasks().remove(task.id) }
                },
            )
            queued.addView(row)
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

    private fun section() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(10))
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

    /** The stored type is a BanSource name; shown as itself rather than a pk. */
    private fun sourceName(raw: String) = raw.lowercase().replace('_', ' ')

    private fun whenText(millis: Long): String =
        WHEN_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private companion object {
        val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}
