package org.duzgun.eksiengelplus.eksi.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.TargetType

/**
 * Outcome of a mutation.
 *
 * A sealed type rather than a boolean because the site returns numeric codes and
 * the set is not fully known: android-spike discovered `4` only because the
 * harness printed unrecognised numbers instead of folding them into false. The
 * next unknown code must be equally visible.
 */
sealed interface RelationResult {
    data object Success : RelationResult
    /** BAN returned 2: the relation already existed. Not an error. */
    data object AlreadyInState : RelationResult
    /** BAN returned 4, observed when the target is the authenticated user. */
    data object SelfTarget : RelationResult
    data class RateLimited(val retryAfterSeconds: Int) : RelationResult
    data object SessionExpired : RelationResult
    data class Failed(val httpCode: Int?, val code: Int?, val body: String?) : RelationResult
}

class RelationClient(
    private val http: OkHttpClient,
    private val baseUrlProvider: () -> String,
) {
    companion object {
        /** relationHandler.js:158 -- used when Retry-After is missing or unparseable. */
        const val DEFAULT_RETRY_AFTER_SECONDS = 65
    }

    suspend fun perform(mode: BanMode, targetType: TargetType, id: Long): RelationResult =
        withContext(Dispatchers.IO) {
            val url = "${baseUrlProvider()}/userrelation/${mode.urlSegment}/$id?r=${targetType.relationCode}"
            val req = Request.Builder().url(url).post("id=$id".toRequestBody()).build()
            try {
                http.newCall(req).execute().use { res ->
                    val body = res.body?.string()
                    when {
                        res.code == 429 -> RateLimited(parseRetryAfter(res.header("Retry-After")))
                        SessionExpiry.isLoginRedirect(res.code, res.header("Location")) ->
                            RelationResult.SessionExpired
                        SessionExpiry.isDenied(res.code) -> RelationResult.SessionExpired
                        SessionExpiry.looksLikeHtml(body) -> RelationResult.SessionExpired
                        !res.isSuccessful -> RelationResult.Failed(res.code, null, body)
                        else -> classify(mode, body)
                    }
                }
            } catch (e: SessionExpiredException) {
                RelationResult.SessionExpired
            } catch (e: Exception) {
                RelationResult.Failed(null, null, e.message)
            }
        }

    /**
     * BAN answers with a bare number, UNDOBAN with an object -- a single parse
     * target cannot cover both (relationHandler.js:185-193).
     */
    private fun classify(mode: BanMode, body: String?): RelationResult {
        val t = body?.trim().orEmpty()
        return if (mode == BanMode.BAN) {
            when (val n = t.toIntOrNull()) {
                0 -> RelationResult.Success
                2 -> RelationResult.AlreadyInState
                4 -> RelationResult.SelfTarget
                // Deliberately not a guess. An unknown code is a failure that
                // records itself, which is how 4 was found in the first place.
                else -> RelationResult.Failed(200, n, t.take(200))
            }
        } else {
            // result:true came back on a no-op removal during the spike, so it
            // proves the call was accepted -- not that a relation existed.
            val ok = Regex("\"result\"\\s*:\\s*true").containsMatchIn(t)
            if (ok) RelationResult.Success else RelationResult.Failed(200, null, t.take(200))
        }
    }

    /**
     * Integer seconds plus a one-second buffer. The HTTP-date form is explicitly
     * not handled, matching relationHandler.js:157.
     *
     * The delay is returned, never slept on: a 429 must penalise every caller
     * through a shared pacer, and a client that slept internally would make that
     * impossible. The extension's two divergent cooldowns are what that looks like.
     */
    private fun parseRetryAfter(header: String?): Int {
        val n = header?.trim()?.toIntOrNull() ?: return DEFAULT_RETRY_AFTER_SECONDS
        return if (n > 0) n + 1 else DEFAULT_RETRY_AFTER_SECONDS
    }
}

private fun RateLimited(seconds: Int) = RelationResult.RateLimited(seconds)
