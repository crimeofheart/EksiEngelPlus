package org.duzgun.eksiengelplus.feature.lists

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.TargetType

/**
 * The saved author list — the app's `authorListPage.html`.
 *
 * A paste field rather than a row editor because that is how lists actually move
 * between people: as text. The file picker covers the CSV case.
 */
@AndroidEntryPoint
class AuthorListActivity : AppCompatActivity() {

    private val model: AuthorListViewModel by viewModels()

    private lateinit var text: EditText
    private lateinit var count: TextView

    /** True when the picked file should replace the list rather than extend it. */
    private var importReplaces = true

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        model.importFrom(importReplaces) { contentResolver.openInputStream(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_author_list)
        title = getString(R.string.author_list_title)

        text = findViewById(R.id.authorText)
        count = findViewById(R.id.authorCount)

        findViewById<Button>(R.id.authorSave).setOnClickListener { model.save(text.text.toString()) }
        findViewById<Button>(R.id.authorAppend).setOnClickListener { model.append(text.text.toString()) }
        findViewById<Button>(R.id.authorClear).setOnClickListener { model.clear() }
        findViewById<Button>(R.id.authorImport).setOnClickListener {
            importReplaces = true
            openDocument.launch(IMPORT_MIME_TYPES)
        }
        findViewById<Button>(R.id.authorRun).setOnClickListener { askMode() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.count.collect {
                        count.text = resources.getQuantityString(R.plurals.author_list_count, it, it)
                    }
                }
                launch {
                    // Seeded once: after that the field is the user's draft, and
                    // overwriting it on every emission would fight their typing.
                    model.nicks.collect { nicks ->
                        if (text.text.isEmpty() && nicks.isNotEmpty()) {
                            text.setText(nicks.joinToString("\n"))
                        }
                    }
                }
                launch {
                    model.message.collect {
                        Toast.makeText(this@AuthorListActivity, it, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun askMode() {
        val labels = arrayOf(
            getString(R.string.author_list_mode_block),
            getString(R.string.author_list_mode_unblock),
            getString(R.string.author_list_mode_mute),
            getString(R.string.author_list_mode_unmute),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.author_list_run)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> model.run(BanMode.BAN, TargetType.USER)
                    1 -> model.run(BanMode.UNDOBAN, TargetType.USER)
                    2 -> model.run(BanMode.BAN, TargetType.MUTE)
                    else -> model.run(BanMode.UNDOBAN, TargetType.MUTE)
                }
            }
            .show()
    }

    private companion object {
        /**
         * Both, because a list exported by the extension is `text/csv` but a list
         * someone typed into a notes app is `text/plain`, and the parser does not
         * care which.
         */
        val IMPORT_MIME_TYPES = arrayOf("text/csv", "text/plain", "text/comma-separated-values")
    }
}
