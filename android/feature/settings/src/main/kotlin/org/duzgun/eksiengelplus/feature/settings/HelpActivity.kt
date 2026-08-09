package org.duzgun.eksiengelplus.feature.settings

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.duzgun.eksiengelplus.ui.fitContentInsideSystemBars
import androidx.core.text.HtmlCompat

/**
 * The extension's Kullanım Kılavuzu, rewritten for this app.
 *
 * Content ported from faq.html:427-570, shape not. The extension's guide is
 * written around a popup and two browser tabs; describing those here would
 * document a program the user does not have. Where the behaviour itself
 * diverges -- the date filter above all -- the text says so rather than
 * repeating the extension's rules, because a user who applies them here will
 * predict the opposite outcome.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        fitContentInsideSystemBars()
        title = getString(R.string.help_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.helpBody).apply {
            text = HtmlCompat.fromHtml(
                getString(R.string.help_body),
                HtmlCompat.FROM_HTML_MODE_COMPACT,
            )
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
