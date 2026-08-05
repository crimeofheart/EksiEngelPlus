package org.duzgun.eksiengelplus.eksi.parser

/**
 * Every Ekşi Sözlük selector, in one place.
 *
 * The site is undocumented, unversioned and third-party. Keeping the selectors
 * together means "Ekşi changed their HTML" is a one-file diff here plus the
 * matching change in the extension's scrapingHandler.js -- rather than an
 * archaeology exercise across two codebases.
 *
 * Verified against docs/fixtures/eksisozluk/ and, for the auth-gated ones, on a
 * real device during android-spike. See openspec eksisozluk-client-contract.
 */
object Selectors {

    /** Homepage avatar. Presence means logged in; the title attribute is the nick. */
    const val OWN_NICK = ".mobile-notification-icons .mobile-only a[title]"

    /** Hidden input on a profile page. Only rendered when logged in. */
    const val AUTHOR_ID = "#who"

    /** Primary registration-date target: renders as a Turkish month name and year. */
    const val RECORD_DATE = ".recorddate"

    /**
     * Fallbacks tried in order when .recorddate is absent, ported from
     * scrapingHandler.js:1083-1091.
     */
    val RECORD_DATE_FALLBACKS = listOf(
        "[data-registration-date]",
        ".registration-date",
        ".user-registration-date",
        ".profile-info .date",
        ".user-info [title*=kayıt]",
        ".user-info [title*=katılım]",
    )

    /**
     * Last-resort scan. The extension walks querySelectorAll('*') and reads every
     * node's text (scrapingHandler.js:1104-1118) -- executed per uncached user, it
     * is the single most expensive path in a date-filtered bulk run. Bounded here
     * to leaf-ish elements and own-text only.
     */
    const val RECORD_DATE_SCAN = "li,span,div,p,dd,td"
    val RECORD_DATE_TEXT = Regex("""(?i)(kayıt|katılım)\s+tarihi""")

    const val ENTRY_LIST_ITEM = "#entry-item-list li[data-author-id]"
    const val ENTRY_LIST_ITEM_ANY = "#entry-item-list li[data-id]"
    const val TITLE_NODE = "#title"

    /** One per entry on a title page; the author lives on the parent li. */
    const val TITLE_ENTRY_CONTENT = ".content"

    /** Injection target for the two "başlıktakileri engelle" items. */
    const val IN_TOPIC_SEARCH_OPTIONS = "#in-topic-search-options"

    /**
     * Four of these render per page, so position cannot identify the entry menu.
     * The extension text-matches instead (script.js:315) -- required, not defensive.
     */
    const val DROPDOWN_MENU = ".dropdown-menu"
    val ENTRY_MENU_MARKERS = listOf("engelle", "modlog", "şikayet", "mesaj")

    /**
     * Matches zero elements on both title and entry pages, logged in and out.
     * A dead alternative in the extension's selector list, kept documented so a
     * reimplementation does not take a dependency on it.
     */
    const val TOGGLES_MENU_DEAD = "ul.toggles-menu"

    const val USER_NOTIFICATIONS = "#user-notifications"
    const val PROFILE_BUTTONS = ".profile-buttons"
    const val RELATION_LINK = ".relation-link[data-add-caption]"
    const val BLOCKED_LINK_BUTTON = "#button-blocked-link"
    const val NICK_HOLDER = "[data-nick]"
}
