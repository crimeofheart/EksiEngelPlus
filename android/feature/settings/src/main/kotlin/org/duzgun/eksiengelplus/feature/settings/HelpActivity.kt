package org.duzgun.eksiengelplus.feature.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import org.duzgun.eksiengelplus.ui.fitContentInsideSystemBars

/**
 * The extension's Kullanım Kılavuzu, rewritten for this app and dressed like the
 * project's own site.
 *
 * Content ported from faq.html:427-570, shape not. The extension's guide is
 * written around a popup and two browser tabs; describing those here would
 * document a program the user does not have. Where the behaviour itself
 * diverges -- the date filter above all -- the text says so rather than
 * repeating the extension's rules, because a user who applies them here will
 * predict the opposite outcome.
 *
 * The styling follows eksiengelplus.duzgun.org: a green gradient with white
 * cards on it, headings in the dark green over an accent rule, and the site's
 * .feature block for the two callouts. Taken from the site's stylesheet, not
 * from a screenshot, so the greens are the site's exact values.
 *
 * Sections are inflated from a list rather than written out in XML: the card's
 * metrics then live in one layout, and adding a section is adding two strings.
 */
class HelpActivity : AppCompatActivity() {

    /** Title, body, and the optional callout beneath it. */
    private class Section(val title: Int, val body: Int, val note: Int? = null)

    private val sections = listOf(
        Section(R.string.help_browsing_title, R.string.help_browsing_body),
        Section(
            R.string.help_operations_title,
            R.string.help_operations_body,
            R.string.help_operations_note,
        ),
        Section(R.string.help_lists_title, R.string.help_lists_body),
        Section(R.string.help_author_list_title, R.string.help_author_list_body),
        Section(
            R.string.help_date_filter_title,
            R.string.help_date_filter_body,
            R.string.help_date_filter_note,
        ),
        Section(R.string.help_storage_title, R.string.help_storage_body),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        fitContentInsideSystemBars()
        // The app's theme is NoActionBar, so there is nothing to hang an up
        // button on and the heading lives in the layout. This still names the
        // screen in the recents list.
        title = getString(R.string.help_title)

        val cards = findViewById<ViewGroup>(R.id.helpCards)
        for (section in sections) {
            val card = layoutInflater.inflate(R.layout.view_help_card, cards, false)
            card.findViewById<TextView>(R.id.cardTitle).setText(section.title)
            card.findViewById<TextView>(R.id.cardBody).text = markup(section.body)
            section.note?.let {
                card.findViewById<TextView>(R.id.cardNote).apply {
                    text = markup(it)
                    visibility = android.view.View.VISIBLE
                }
            }
            cards.addView(card)
        }
    }

    /**
     * The strings carry light markup -- bold, italic, monospace, breaks.
     *
     * Trimmed at both ends: the CDATA blocks open and close on their own lines
     * for readability, and `fromHtml` turns that into a blank first line and a
     * trailing gap inside every card. `trimEnd` on the result keeps the spans,
     * because it subsequences rather than rebuilding the string.
     */
    private fun markup(res: Int): CharSequence =
        HtmlCompat.fromHtml(getString(res).trim(), HtmlCompat.FROM_HTML_MODE_COMPACT)
            .trimEnd()
}
