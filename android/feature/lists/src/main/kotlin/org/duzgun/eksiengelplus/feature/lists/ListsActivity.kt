package org.duzgun.eksiengelplus.feature.lists

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
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
    private lateinit var authorSummary: TextView
    private lateinit var opsSummary: TextView
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
        val menu: TextView = root.findViewById(R.id.rowMenu)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lists)
        title = getString(R.string.lists_title)
        notice = findViewById(R.id.listsNotice)
        authorSummary = findViewById(R.id.authorSummary)
        opsSummary = findViewById(R.id.opsSummary)

        bind(ListType.BLOCKED, R.id.rowBlocked, R.string.lists_blocked)
        bind(ListType.MUTED, R.id.rowMuted, R.string.lists_muted)
        bind(ListType.FOLLOWED, R.id.rowFollowed, R.string.lists_followed)
        bind(ListType.TITLE_BANNED, R.id.rowTitleBanned, R.string.lists_title_banned)

        findViewById<Button>(R.id.openBulk).setOnClickListener { askBulkAction() }
        findViewById<Button>(R.id.openOperations).setOnClickListener {
            startActivity(Intent(this, OperationsActivity::class.java))
        }
        findViewById<Button>(R.id.openAuthorList).setOnClickListener {
            startActivity(Intent(this, AuthorListActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch { model.message.collect { showMessage(it) } }
                // Each card says what it holds, so the screen answers the obvious
                // questions without being opened.
                launch {
                    model.authorListCount.collect {
                        authorSummary.text = getString(R.string.author_list_summary, it)
                    }
                }
                launch {
                    model.operationSummary.collect { (live, queued) ->
                        opsSummary.text = if (live == 0 && queued == 0) {
                            getString(R.string.ops_summary_idle)
                        } else {
                            getString(R.string.ops_summary_busy, live, queued)
                        }
                    }
                }
            }
        }
    }

    private fun bind(listType: ListType, rootId: Int, titleRes: Int) {
        val views = RowViews(findViewById(rootId))
        views.title.setText(titleRes)
        views.menu.setOnClickListener { showRowMenu(listType, views.menu) }
        rows[listType] = views
    }

    /**
     * The row's verbs, in a menu rather than as three buttons per list.
     *
     * Availability is decided here instead of by greying out controls that are
     * always on screen: a menu item that cannot apply simply is not offered.
     */
    private fun showRowMenu(listType: ListType, anchor: View) {
        val state = model.state.value
        val row = state.rows.firstOrNull { it.listType == listType } ?: return
        val syncing = row.sync.isActive

        val menu = android.widget.PopupMenu(this, anchor)
        if (!syncing && !state.operationRunning) menu.menu.add(0, 1, 0, R.string.lists_refresh)
        if (syncing) menu.menu.add(0, 2, 1, R.string.lists_stop)
        if (row.count > 0 && state.exporting == null && !syncing) {
            menu.menu.add(0, 3, 2, R.string.lists_export)
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> model.refresh(listType)
                2 -> model.stop(listType)
                3 -> {
                    pendingExport = listType
                    createDocument.launch(CsvCodec.suggestedFilename(listType, today()))
                }
            }
            true
        }
        menu.show()
    }

    /**
     * The extension's bulk operations, in its own grouping.
     *
     * These act on the user's own relation lists, which is why they live here
     * rather than in the author list's chooser -- that one runs against a list
     * the user typed.
     */
    /**
     * Every bulk action in one chooser.
     *
     * The same shape the author list uses, rather than a stack of buttons in the
     * middle of the screen: one entry point, one dialog, one way to pick.
     */
    private fun askBulkAction() {
        data class Bulk(
            val labelRes: Int,
            val source: BanSource,
            val mode: BanMode,
            val target: TargetType,
        )

        val actions = listOf(
            Bulk(R.string.bulk_migrate, BanSource.MIGRATE_BLOCKED_TO_MUTED, BanMode.UNDOBAN, TargetType.USER),
            Bulk(R.string.bulk_block_muted, BanSource.BLOCK_MUTED_USERS, BanMode.BAN, TargetType.USER),
            Bulk(R.string.bulk_undoban_all, BanSource.UNDOBANALL, BanMode.UNDOBAN, TargetType.USER),
            Bulk(R.string.bulk_unmute_all, BanSource.UNMUTEALL, BanMode.UNDOBAN, TargetType.MUTE),
            Bulk(R.string.bulk_block_titles, BanSource.BLOCKED_MUTED_TITLES, BanMode.BAN, TargetType.TITLE),
            Bulk(R.string.bulk_unblock_titles, BanSource.BLOCKED_MUTED_TITLES, BanMode.UNDOBAN, TargetType.TITLE),
            Bulk(R.string.bulk_date_based, BanSource.DATE_BASED_BULK, BanMode.UNDOBAN, TargetType.USER),
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.bulk_title)
            .setItems(actions.map { getString(it.labelRes) }.toTypedArray()) { _, which ->
                val a = actions[which]
                // The date-filtered run is the one action whose target set the
                // filter decides, so it asks what to apply rather than assuming
                // a direction the user never chose.
                if (a.source == BanSource.DATE_BASED_BULK) askDateBasedAction()
                else model.runOnList(a.source, a.mode, a.target)
            }
            .setNegativeButton(R.string.author_list_cancel, null)
            .show()
    }

    /**
     * The date-filtered run, which needs a direction of its own.
     *
     * It was hardcoded to unblocking from the blocked list, which is one of
     * three reasonable readings of "apply my date filter" and not obviously the
     * one intended.
     */
    private fun askDateBasedAction() {
        data class Choice(val labelRes: Int, val mode: BanMode, val target: TargetType)

        val choices = listOf(
            Choice(R.string.bulk_date_blocked_unblock, BanMode.UNDOBAN, TargetType.USER),
            Choice(R.string.bulk_date_muted_unmute, BanMode.UNDOBAN, TargetType.MUTE),
            Choice(R.string.bulk_date_blocked_mute, BanMode.BAN, TargetType.MUTE),
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.bulk_date_which)
            .setItems(choices.map { getString(it.labelRes) }.toTypedArray()) { _, which ->
                val c = choices[which]
                model.runDateBased(c.mode, c.target)
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
