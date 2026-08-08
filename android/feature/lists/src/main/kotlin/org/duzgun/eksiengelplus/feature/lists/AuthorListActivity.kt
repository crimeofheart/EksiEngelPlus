package org.duzgun.eksiengelplus.feature.lists

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
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
    private lateinit var spinner: ProgressBar

    /** Every button that must not be tapped twice into a half-applied list. */
    private lateinit var mutators: List<Button>

    /** True when the picked file should replace the list rather than extend it. */
    private var importReplaces = true

    /**
     * Set by the Clear button so an empty list wipes the draft too.
     *
     * Without it, an empty list on first open would blank whatever the user had
     * begun typing before the flow's first emission arrived.
     */
    private var clearedByUser = false

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
        spinner = findViewById(R.id.authorSpinner)

        val save = findViewById<Button>(R.id.authorSave)
        val append = findViewById<Button>(R.id.authorAppend)
        val clear = findViewById<Button>(R.id.authorClear)
        val import = findViewById<Button>(R.id.authorImport)
        val run = findViewById<Button>(R.id.authorRun)
        mutators = listOf(save, append, clear, import, run)

        save.setOnClickListener { model.save(text.text.toString()) }
        append.setOnClickListener { model.append(text.text.toString()) }
        clear.setOnClickListener {
            clearedByUser = true
            model.clear()
        }
        import.setOnClickListener {
            importReplaces = true
            openDocument.launch(IMPORT_MIME_TYPES)
        }
        run.setOnClickListener { askMode() }

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
                    //
                    // The exception is the list going empty, which only happens
                    // because the user asked it to. Leaving their names on screen
                    // after Clear made a working clear look broken.
                    model.nicks.collect { nicks ->
                        when {
                            nicks.isEmpty() -> if (clearedByUser) text.setText("")
                            text.text.isEmpty() -> text.setText(nicks.joinToString("\n"))
                        }
                    }
                }
                launch {
                    model.busy.collect { busy ->
                        spinner.visibility = if (busy) View.VISIBLE else View.GONE
                        mutators.forEach { it.isEnabled = !busy }
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

    /**
     * The run chooser.
     *
     * A custom view rather than setItems: every action here has an inverse, and
     * pairing the two in one colour on one row says "same lever, opposite
     * direction". As a flat list, engelle and engeli kaldir sat adjacent and
     * looked interchangeable, which is a bad property for a pair of bulk actions
     * that are tedious to undo.
     */
    private fun askMode() {
        val view = layoutInflater.inflate(R.layout.dialog_run_actions, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.author_list_run)
            .setView(view)
            .setNegativeButton(R.string.author_list_cancel, null)
            .create()

        // TargetType is the r= code Eksi expects; BanMode picks addrelation vs
        // removerelation. All three groups go through that same pair.
        mapOf(
            R.id.runBlock to (BanMode.BAN to TargetType.USER),
            R.id.runUnblock to (BanMode.UNDOBAN to TargetType.USER),
            R.id.runMute to (BanMode.BAN to TargetType.MUTE),
            R.id.runUnmute to (BanMode.UNDOBAN to TargetType.MUTE),
            R.id.runFollow to (BanMode.BAN to TargetType.FOLLOW),
            R.id.runUnfollow to (BanMode.UNDOBAN to TargetType.FOLLOW),
        ).forEach { (id, action) ->
            view.findViewById<View>(id).setOnClickListener {
                model.run(action.first, action.second)
                dialog.dismiss()
            }
        }

        // The extension's combined actions: undo a relation, then follow. Two
        // relations per user, so the follow only lands if the first did.
        mapOf(
            R.id.runUnblockFollow to TargetType.USER,
            R.id.runUnmuteFollow to TargetType.MUTE,
        ).forEach { (id, undo) ->
            view.findViewById<View>(id).setOnClickListener {
                model.run(BanMode.UNDOBAN, undo, thenApplyTo = TargetType.FOLLOW)
                dialog.dismiss()
            }
        }

        dialog.show()
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
