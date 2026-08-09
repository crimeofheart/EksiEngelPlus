package org.duzgun.eksiengelplus.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.duzgun.eksiengelplus.ui.fitContentInsideSystemBars

/**
 * What changed in this version, shown once after an install or an upgrade.
 *
 * An ordinary activity rather than a dialog over the browser: it is finishable
 * with back, survives rotation without the host having to hold state, and is
 * reachable again from Settings afterwards. Never a gate -- dismissing it lands
 * the user where they were going, and nothing is withheld until it is read.
 */
class ReleaseNotesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_release_notes)
        fitContentInsideSystemBars()
        title = getString(R.string.notes_title)

        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        findViewById<TextView>(R.id.notesVersion).text =
            getString(R.string.notes_version, version)
        findViewById<TextView>(R.id.notesBody).text =
            ReleaseNotes.forVersion(version).joinToString("\n\n") { "• $it" }
        findViewById<android.widget.Button>(R.id.notesDismiss)
            .setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_VERSION = "version"

        fun intent(context: Context, version: String): Intent =
            Intent(context, ReleaseNotesActivity::class.java)
                .putExtra(EXTRA_VERSION, version)
    }
}
