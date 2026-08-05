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
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code} for $url")
            body.orEmpty()
        }
    }

    suspend fun ownNick(): String? = parser.parseOwnNick(parser.parse(get("${baseUrlProvider()}/")))

    suspend fun authorProfile(nick: String) =
        parser.parseAuthorProfile(nick, parser.parse(get("${baseUrlProvider()}/biri/$nick")))

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
