package org.duzgun.eksiengelplus.feature.lists

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import androidx.appcompat.app.AlertDialog
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.ListType
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.model.TurkishDateParser

/**
 * The three relation lists: how many, how fresh, and whether what is stored is a
 * whole list or a half-finished sync.
 *
 * Replaces the counts and the Refresh/Export buttons on the extension's
 * notification page (notificationHandler.js:120-317).
 */
@AndroidEntryPoint
class ListsActivity : AppCompatActivity() {

    private val model: ListsViewModel by viewModels()

    private lateinit var notice: TextView
    private val rows = mutableMapOf<ListType, RowViews>()

    /** The list whose export is waiting on the file picker. */
    private var pendingExport: ListType? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_CSV),
    ) { uri: Uri? ->
        val listType = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        // A dismissed picker is a no-op, not an error.
        if (uri == null) return@registerForActivityResult
        model.export(listType) { contentResolver.openOutputStream(uri) }
    }

    private class RowViews(root: View) {
        val title: TextView = root.findViewById(R.id.rowTitle)
        val count: TextView = root.findViewById(R.id.rowCount)
        val freshness: TextView = root.findViewById(R.id.rowFreshness)
        val partial: TextView = root.findViewById(R.id.rowPartial)
        val spinner: ProgressBar = root.findViewById(R.id.rowSpinner)
        val refresh: Button = root.findViewById(R.id.rowRefresh)
        val stop: Button = root.findViewById(R.id.rowStop)
        val export: Button = root.findViewById(R.id.rowExport)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lists)
        title = getString(R.string.lists_title)
        notice = findViewById(R.id.listsNotice)

        bind(ListType.BLOCKED, R.id.rowBlocked, R.string.lists_blocked)
        bind(ListType.MUTED, R.id.rowMuted, R.string.lists_muted)
        bind(ListType.FOLLOWED, R.id.rowFollowed, R.string.lists_followed)

        buildBulkActions()
        findViewById<Button>(R.id.openOperations).setOnClickListener {
            startActivity(Intent(this, OperationsActivity::class.java))
        }
        findViewById<Button>(R.id.openAuthorList).setOnClickListener {
            startActivity(Intent(this, AuthorListActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch { model.message.collect { Toast.makeText(this@ListsActivity, it, Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    private fun bind(listType: ListType, rootId: Int, titleRes: Int) {
        val views = RowViews(findViewById(rootId))
        views.title.setText(titleRes)
        views.refresh.setOnClickListener { model.refresh(listType) }
        views.stop.setOnClickListener { model.stop(listType) }
        views.export.setOnClickListener {
            pendingExport = listType
            createDocument.launch(CsvCodec.suggestedFilename(listType, today()))
        }
        rows[listType] = views
    }

    /**
     * The extension's bulk operations, in its own grouping.
     *
     * These act on the user's own relation lists, which is why they live here
     * rather than in the author list's chooser -- that one runs against a list
     * the user typed.
     */
    private fun buildBulkActions() {
        data class Bulk(
            val labelRes: Int,
            val list: ListType,
            val source: BanSource,
            val mode: BanMode,
            val target: TargetType,
        )

        val groups = listOf(
            R.string.bulk_group_relations to listOf(
                Bulk(R.string.bulk_migrate, ListType.BLOCKED, BanSource.MIGRATE_BLOCKED_TO_MUTED, BanMode.UNDOBAN, TargetType.USER),
                Bulk(R.string.bulk_block_muted, ListType.MUTED, BanSource.BLOCK_MUTED_USERS, BanMode.BAN, TargetType.USER),
                Bulk(R.string.bulk_undoban_all, ListType.BLOCKED, BanSource.UNDOBANALL, BanMode.UNDOBAN, TargetType.USER),
                Bulk(R.string.bulk_unmute_all, ListType.MUTED, BanSource.UNMUTEALL, BanMode.UNDOBAN, TargetType.MUTE),
            ),
            R.string.bulk_group_titles to listOf(
                Bulk(R.string.bulk_block_titles, ListType.BLOCKED, BanSource.BLOCKED_MUTED_TITLES, BanMode.BAN, TargetType.TITLE),
                Bulk(R.string.bulk_unblock_titles, ListType.BLOCKED, BanSource.BLOCKED_MUTED_TITLES, BanMode.UNDOBAN, TargetType.TITLE),
            ),
            R.string.bulk_group_date to listOf(
                Bulk(R.string.bulk_date_based, ListType.BLOCKED, BanSource.DATE_BASED_BULK, BanMode.UNDOBAN, TargetType.USER),
            ),
        )

        val container = findViewById<android.view.ViewGroup>(R.id.bulkActions)
        for ((headingRes, actions) in groups) {
            container.addView(
                TextView(this).apply {
                    text = getString(headingRes)
                    textSize = 12f
                    alpha = 0.6f
                    setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
                },
            )
            for (a in actions) {
            val b = com.google.android.material.button.MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
            b.text = getString(a.labelRes)
            b.textSize = 12f
            b.isAllCaps = false
            b.cornerRadius = 0
            b.setOnClickListener { model.runOnList(a.list, a.source, a.mode, a.target) }
            container.addView(
                b,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() },
                )
            }
        }
    }

    /**
     * What can be done with a list depends on which list it is.
     *
     * Offering "sessizden çıkar" for the blocked list would be an action with no
     * meaning, so each list shows only its own migrations.
     */
    private fun askListAction(listType: ListType) {
        data class Choice(val labelRes: Int, val run: () -> Unit)

        val choices = when (listType) {
            ListType.BLOCKED -> listOf(
                Choice(R.string.lists_run_migrate) {
                    model.runOnList(
                        listType,
                        BanSource.MIGRATE_BLOCKED_TO_MUTED,
                        BanMode.UNDOBAN,
                        TargetType.USER,
                    )
                },
                Choice(R.string.lists_run_unblock_all) {
                    model.runOnList(listType, BanSource.UNDOBANALL, BanMode.UNDOBAN, TargetType.USER)
                },
                Choice(R.string.lists_run_titles) {
                    model.runOnList(
                        listType,
                        BanSource.BLOCKED_MUTED_TITLES,
                        BanMode.BAN,
                        TargetType.TITLE,
                    )
                },
            )
            ListType.MUTED -> listOf(
                Choice(R.string.lists_run_block_muted) {
                    model.runOnList(listType, BanSource.BLOCK_MUTED_USERS, BanMode.BAN, TargetType.USER)
                },
                Choice(R.string.lists_run_unmute_all) {
                    model.runOnList(listType, BanSource.UNMUTEALL, BanMode.UNDOBAN, TargetType.MUTE)
                },
                Choice(R.string.lists_run_titles) {
                    model.runOnList(
                        listType,
                        BanSource.BLOCKED_MUTED_TITLES,
                        BanMode.BAN,
                        TargetType.TITLE,
                    )
                },
            )
            ListType.FOLLOWED -> listOf(
                Choice(R.string.author_list_mode_unfollow) {
                    model.runOnList(listType, BanSource.LIST, BanMode.UNDOBAN, TargetType.FOLLOW)
                },
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.lists_run_title)
            .setItems(choices.map { getString(it.labelRes) }.toTypedArray()) { _, which ->
                choices[which].run()
            }
            .setNegativeButton(R.string.author_list_cancel, null)
            .show()
    }

    private fun render(state: ListsUiState) {
        notice.visibility = if (state.operationRunning) View.VISIBLE else View.GONE

        for (row in state.rows) {
            val views = rows[row.listType] ?: continue
            val syncing = row.sync.isActive
            val exporting = state.exporting == row.listType

            views.count.text = resources.getQuantityString(R.plurals.lists_user_count, row.count, row.count)
            views.freshness.text = if (exporting) getString(R.string.lists_exporting) else statusLine(row)
            views.spinner.visibility = if (syncing || exporting) View.VISIBLE else View.GONE

            // A half-finished list is still partial while it is being finished, but
            // saying so next to a live progress line reads as an error rather than
            // as the state the refresh is busy resolving.
            views.partial.visibility = if (row.isPartial && !syncing) View.VISIBLE else View.GONE

            // Refresh is pointless while this list is already syncing (KEEP would
            // drop it silently) and unwise while an operation runs.
            views.refresh.isEnabled = !state.operationRunning && !syncing
            views.stop.isEnabled = syncing
            // Only one export at a time, and none while this list is still being
            // written to -- exporting mid-sync would write a snapshot the progress
            // line is actively contradicting.
            views.export.isEnabled = row.count > 0 && state.exporting == null && !syncing
            // Nothing to run against an empty list, and a second operation while
            // one is going would double the rate the pacer is holding down.
        }
    }

    /** The freshness line doubles as the progress line while a sync is live. */
    private fun statusLine(row: ListRowState): String = when (val sync = row.sync) {
        SyncStatus.Idle ->
            row.lastFullRefreshAt?.let(::formatWhen) ?: getString(R.string.lists_never_refreshed)
        SyncStatus.Queued -> getString(R.string.lists_sync_queued)
        is SyncStatus.Running -> sync.progress?.let {
            getString(R.string.lists_sync_progress, it.page, it.seen)
        } ?: getString(R.string.lists_syncing)
    }

    /** Ekşi's own zone, so a date shown here matches a date shown on the site. */
    private fun today(): LocalDate = LocalDate.now(TurkishDateParser.ZONE)

    private fun formatWhen(epochMillis: Long): String =
        WHEN_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    private companion object {
        const val MIME_CSV = "text/csv"
        val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    }
}
