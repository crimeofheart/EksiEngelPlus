package org.duzgun.eksiengelplus.eksi.parser

import java.time.LocalDate
import org.duzgun.eksiengelplus.model.TurkishDateParser
import org.duzgun.eksiengelplus.model.toEksiSlug
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class AuthorProfile(val nick: String, val authorId: Long?, val registrationDate: LocalDate?)

data class EntryMetadata(
    val entryId: Long,
    val authorId: Long?,
    val authorNick: String?,
    val titleId: Long?,
    val titleName: String?,
)

data class TopicAuthor(val nick: String, val authorId: Long?)

/**
 * Records selectors that should have matched but did not. Site changes surface
 * here first; without it a template change looks like "the user has no blocked
 * users" rather than "the parser is broken".
 */
class SelectorHealth {
    private val misses = linkedMapOf<String, Int>()
    fun miss(selector: String) { misses[selector] = (misses[selector] ?: 0) + 1 }
    fun snapshot(): Map<String, Int> = LinkedHashMap(misses)
    fun isHealthy(): Boolean = misses.isEmpty()
}

class EksiHtmlParser(private val health: SelectorHealth = SelectorHealth()) {

    fun health(): SelectorHealth = health

    fun parse(html: String): Document = Jsoup.parse(html)

    fun parseFragment(html: String): Document = Jsoup.parseBodyFragment(html)

    /** Presence of the avatar is the login check; its title is the nick. */
    fun parseOwnNick(doc: Document): String? {
        val el = doc.selectFirst(Selectors.OWN_NICK)
        if (el == null) { health.miss(Selectors.OWN_NICK); return null }
        return el.attr("title").takeIf { it.isNotBlank() }?.toEksiSlug()
    }

    fun parseAuthorId(doc: Document): Long? {
        val v = doc.selectFirst(Selectors.AUTHOR_ID)?.attr("value")
        if (v.isNullOrBlank()) { health.miss(Selectors.AUTHOR_ID); return null }
        // The extension returns "0" on failure (scrapingHandler.js:1052); treat as absent.
        return v.toLongOrNull()?.takeIf { it != 0L }
    }

    fun parseRegistrationDate(doc: Document): LocalDate? {
        doc.selectFirst(Selectors.RECORD_DATE)?.text()?.let { text ->
            TurkishDateParser.parse(text)?.let { return it }
        }
        for (sel in Selectors.RECORD_DATE_FALLBACKS) {
            val el = doc.selectFirst(sel) ?: continue
            val raw = el.attr("datetime").ifBlank { el.attr("data-date") }.ifBlank { el.text() }
            TurkishDateParser.parse(raw)?.let { return it }
        }
        // Bounded replacement for the extension's full-tree querySelectorAll('*') scan.
        val hit = doc.body().select(Selectors.RECORD_DATE_SCAN)
            .firstOrNull { Selectors.RECORD_DATE_TEXT.containsMatchIn(it.ownText()) }
        if (hit != null) {
            TurkishDateParser.parse(hit.ownText())?.let { return it }
            hit.nextElementSibling()?.let { sib ->
                TurkishDateParser.parse(sib.attr("datetime").ifBlank { sib.text() })?.let { return it }
            }
        }
        health.miss(Selectors.RECORD_DATE)
        return null
    }

    fun parseAuthorProfile(nick: String, doc: Document) =
        AuthorProfile(nick.toEksiSlug(), parseAuthorId(doc), parseRegistrationDate(doc))

    fun parseEntry(entryId: Long, doc: Document): EntryMetadata {
        val li = doc.selectFirst(Selectors.ENTRY_LIST_ITEM)
            ?: doc.selectFirst(Selectors.ENTRY_LIST_ITEM_ANY)
        if (li == null) health.miss(Selectors.ENTRY_LIST_ITEM)
        val title = doc.selectFirst(Selectors.TITLE_NODE)
        if (title == null) health.miss(Selectors.TITLE_NODE)
        return EntryMetadata(
            entryId = entryId,
            authorId = li?.attr("data-author-id")?.toLongOrNull(),
            authorNick = li?.attr("data-author")?.takeIf { it.isNotBlank() }?.toEksiSlug(),
            titleId = title?.attr("data-id")?.toLongOrNull(),
            titleName = title?.attr("data-title")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Favouriter fragments are a flat list of anchors whose text is "@nick".
     * Live nicks contain spaces, so both the @-strip and the slug rule matter.
     */
    fun parseFavouriters(fragmentHtml: String): List<String> =
        parseFragment(fragmentHtml).select("a")
            .map { it.text().trim() }
            .filter { it.startsWith("@") }
            .map { it.removePrefix("@").toEksiSlug() }
            .filter { it.isNotBlank() }
            .distinct()

    /** Authors on one page of a title. The author lives on the .content parent. */
    fun parseTopicAuthors(doc: Document): List<TopicAuthor> =
        doc.select(Selectors.TITLE_ENTRY_CONTENT).mapNotNull { content ->
            val p = content.parent() ?: return@mapNotNull null
            val nick = p.attr("data-author").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TopicAuthor(nick.toEksiSlug(), p.attr("data-author-id").toLongOrNull())
        }.distinctBy { it.nick }
}
