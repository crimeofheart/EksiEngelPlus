package org.duzgun.eksiengelplus.eksi.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.duzgun.eksiengelplus.eksi.parser.EksiHtmlParser
import org.duzgun.eksiengelplus.eksi.parser.EksiJson
import org.duzgun.eksiengelplus.eksi.parser.FollowUser
import org.duzgun.eksiengelplus.eksi.parser.RelationListResponse
import org.duzgun.eksiengelplus.model.TargetType

data class RelationPage(val nicks: List<String>, val ids: List<Long>, val isLast: Boolean)

/**
 * A non-2xx response, carrying the code so a caller can tell one from another.
 *
 * It used to be a plain IOException with the code in its message, which meant
 * the only way to act on a status was to parse English out of a string. Nobody
 * did, so a 404 that merely means "no such page" killed whole operations.
 */
class HttpStatusException(val code: Int, val url: String) :
    java.io.IOException("HTTP $code for $url")

class ScrapeClient(
    private val http: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val parser: EksiHtmlParser = EksiHtmlParser(),
) {
    companion object {
        /**
         * Pagination is 1-indexed. pageIndex=0 answers HTTP 500 with an empty
         * body -- measured on device -- which reads as a dead endpoint rather
         * than a bad argument, so it is easy to reintroduce by accident.
         */
        const val FIRST_PAGE = 1

        /**
         * Observed page sizes, for progress estimation only -- never terminators.
         * They differ by endpoint family, so neither may be assumed of the other:
         * /relation-list ends on IsLast, the follow endpoints on an empty array.
         */
        const val RELATION_PAGE_SIZE = 25
        const val FOLLOW_PAGE_SIZE = 100
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
            val body = res.body?.string()
            if (SessionExpiry.isLoginRedirect(res.code, res.header("Location")) ||
                SessionExpiry.isDenied(res.code)
            ) throw SessionExpiredException("http ${res.code}")
            if (!res.isSuccessful) throw HttpStatusException(res.code, url)
            body.orEmpty()
        }
    }

    suspend fun ownNick(): String? = parser.parseOwnNick(parser.parse(get("${baseUrlProvider()}/")))

    suspend fun authorProfile(nick: String) =
        parser.parseAuthorProfile(nick, parser.parse(get("${baseUrlProvider()}/biri/$nick")))


    /**
     * Favouriters of an entry. The fragment is a flat anchor list of "@nick" with
     * no ids, so callers must resolve ids per nick afterwards.
     */
    suspend fun favouriters(entryId: Long): List<String> =
        parser.parseFavouriters(get("${baseUrlProvider()}/entry/favorileyenler?entryId=$entryId"))

    /** Novice favourites live on a separate endpoint (scrapingHandler.js:186). */
    suspend fun noviceFavouriters(entryId: Long): List<String> =
        parser.parseFavouriters(get("${baseUrlProvider()}/entry/caylakfavorites?entryId=$entryId"))

    /**
     * Every distinct author in a title, de-duplicated across pages: a prolific
     * author appears many times but must be acted on once.
     *
     * lastDayOnly maps to ?a=dailynice, the extension's LAST_24_H specifier.
     *
     * Pagination ends when a page yields no authors **or answers 404**. Ekşi does
     * not serve an empty page past the end of a title -- it serves a 404 -- so
     * treating that as a failure ended the run instead of the loop. Measured:
     * `/yeni-parti--473428?a=dailynice&p=2` on a title with one page of daily
     * entries. The whole operation was reported failed having acted on nobody,
     * with page 1's authors already in hand and thrown away.
     *
     * The extension has always survived this, by catching every error and
     * calling it the last page (scrapingHandler.js:1227-1230). That is too
     * broad: it cannot tell "no more pages" from "the network went away", and
     * silently acts on a short list. Only 404 ends the loop here.
     *
     * A 404 on the *first* page is still an error. Every real title renders page
     * one, so a 404 there means the slug or the id is wrong, and returning an
     * empty list would turn that into an operation that quietly does nothing.
     */
    suspend fun allTopicAuthors(
        slug: String,
        titleId: Long,
        lastDayOnly: Boolean = false,
        onPage: suspend (Int) -> Unit = {},
    ): List<org.duzgun.eksiengelplus.eksi.parser.TopicAuthor> {
        val seen = LinkedHashMap<String, org.duzgun.eksiengelplus.eksi.parser.TopicAuthor>()
        var page = FIRST_PAGE
        while (true) {
            onPage(page)
            val daily = if (lastDayOnly) "a=dailynice&" else ""
            val body = try {
                get("${baseUrlProvider()}/$slug--$titleId?$daily" + "p=$page")
            } catch (e: HttpStatusException) {
                if (e.code == 404 && page > FIRST_PAGE) break else throw e
            }
            val authors = parser.parseTopicAuthors(parser.parse(body))
            if (authors.isEmpty()) break
            authors.forEach { seen.putIfAbsent(it.nick, it) }
            page++
        }
        return seen.values.toList()
    }

    /** relationType: m blocked, i title-blocked, u muted. */
    suspend fun relationPage(targetType: TargetType, pageIndex: Int): RelationPage {
        require(pageIndex >= FIRST_PAGE) { "pageIndex is 1-based; $pageIndex would 500" }
        val url = "${baseUrlProvider()}/relation-list" +
            "?relationType=${targetType.relationCode}&pageIndex=$pageIndex"
        val body = get(url)
        if (SessionExpiry.looksLikeHtml(body)) throw SessionExpiredException("html from $url")
        val r = EksiJson.decodeFromString(RelationListResponse.serializer(), body)
        return RelationPage(
            nicks = r.relations.items.map { it.nick.value },
            ids = r.relations.items.map { it.id },
            isLast = r.relations.isLast,
        )
    }

    /** Terminates on IsLast. */
    suspend fun allRelations(targetType: TargetType, onPage: (RelationPage) -> Unit = {}): RelationPage {
        val nicks = mutableListOf<String>()
        val ids = mutableListOf<Long>()
        var page = FIRST_PAGE
        while (true) {
            val p = relationPage(targetType, page)
            onPage(p)
            nicks += p.nicks; ids += p.ids
            if (p.isLast) break
            page++
        }
        return RelationPage(nicks, ids, isLast = true)
    }

    suspend fun followPage(endpoint: FollowEndpoint, nick: String, pageIndex: Int): List<FollowUser> {
        require(pageIndex >= FIRST_PAGE) { "pageIndex is 1-based; $pageIndex would 500" }
        val url = "${baseUrlProvider()}/${endpoint.path}?nick=$nick&pageIndex=$pageIndex"
        val body = get(url)
        if (SessionExpiry.looksLikeHtml(body)) throw SessionExpiredException("html from $url")
        return EksiJson.decodeFromString(ListSerializer(FollowUser.serializer()), body)
    }

    /** No IsLast on these two: an empty array is the terminator. */
    suspend fun allFollow(endpoint: FollowEndpoint, nick: String): List<FollowUser> {
        val out = mutableListOf<FollowUser>()
        var page = FIRST_PAGE
        while (true) {
            val p = followPage(endpoint, nick, page)
            if (p.isEmpty()) break
            out += p
            page++
        }
        return out
    }
}

enum class FollowEndpoint(val path: String) { FOLLOWER("follower"), FOLLOWING("following") }
