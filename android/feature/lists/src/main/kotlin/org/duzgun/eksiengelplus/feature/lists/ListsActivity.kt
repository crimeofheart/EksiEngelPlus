package org.duzgun.eksiengelplus.feature.lists

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
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
import org.duzgun.eksiengelplus.model.ListType
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

    private fun render(state: ListsUiState) {
        notice.visibility = if (state.operationRunning) View.VISIBLE else View.GONE

        for (row in state.rows) {
            val views = rows[row.listType] ?: continue
            views.count.text = resources.getQuantityString(R.plurals.lists_user_count, row.count, row.count)
            views.freshness.text = row.lastFullRefreshAt?.let(::formatWhen)
                ?: getString(R.string.lists_never_refreshed)
            views.partial.visibility = if (row.isPartial) View.VISIBLE else View.GONE
            views.refresh.isEnabled = !state.operationRunning
            views.export.isEnabled = row.count > 0
        }
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
