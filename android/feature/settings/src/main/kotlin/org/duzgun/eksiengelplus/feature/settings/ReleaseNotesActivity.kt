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
        // Absent means "just this one": the Settings entry point re-opens the
        // notes for the version already running, not a history of upgrades.
        val from = intent.getStringExtra(EXTRA_FROM) ?: version
        findViewById<TextView>(R.id.notesVersion).text =
            getString(R.string.notes_version, version)
        findViewById<TextView>(R.id.notesBody).text = body(version, from)
        findViewById<android.widget.Button>(R.id.notesDismiss)
            .setOnClickListener { finish() }
    }

    /**
     * The sections, one after another, each under its platform's name, for
     * every release since [from].
     *
     * A SpannableStringBuilder rather than a view per section: the layout is a
     * single scrolling TextView, and a handful of headings do not justify
     * building the list dynamically.
     *
     * The version heading only earns its place when there is more than one
     * release to tell apart, which is the ordinary case of an upgrade that
     * skipped none.
     */
    private fun body(version: String, from: String): CharSequence {
        val text = android.text.SpannableStringBuilder()
        val shown = ReleaseNotes.versionsToShow(version, from)

        for (each in shown) {
            if (shown.size > 1) {
                if (text.isNotEmpty()) text.append("\n\n")
                appendBold(text, getString(R.string.notes_version, each))
            }
            for (section in ReleaseNotes.forVersion(each)) {
                if (text.isNotEmpty()) text.append("\n\n")
                if (section.label.isNotEmpty()) {
                    appendBold(text, section.label)
                    text.append("\n")
                }
                text.append(section.notes.joinToString("\n\n") { "• $it" })
            }
        }
        return text
    }

    private fun appendBold(text: android.text.SpannableStringBuilder, value: String) {
        val start = text.length
        text.append(value)
        text.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start,
            text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    companion object {
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_FROM = "from"

        /**
         * [from] is the version being replaced, and everything released after
         * it is shown. Left out -- the Settings entry point -- the screen shows
         * [version] alone. Blank is a fresh install and shows the whole list,
         * which is what background.js does with no `from` on the welcome page.
         */
        fun intent(context: Context, version: String, from: String? = null): Intent =
            Intent(context, ReleaseNotesActivity::class.java)
                .putExtra(EXTRA_VERSION, version)
                .apply { if (from != null) putExtra(EXTRA_FROM, from) }
    }
}
