package org.duzgun.eksiengelplus.ops.runtime

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.duzgun.eksiengelplus.datastore.EksiConfig
import org.duzgun.eksiengelplus.ops.engine.OperationRequest

/**
 * The body `POST /api/action/` expects.
 *
 * Built to the extension's shape (commHandler.js:47-113) rather than a shape of
 * its own, so the backend needs no Android branch. That is not cosmetic: the
 * endpoint's serializer nests everything under `action` and `action_config`, and
 * the app was posting a flat object of eight keys -- rejected as malformed
 * before any field was even looked at.
 *
 * `eksi_engel_user` is required by the serializer and was absent entirely, which
 * is why a run from the app could never appear against an account.
 */
object ActionReport {

    /** Which build posted this. The backend has no other way to tell. */
    const val CLIENT = "ANDROID"

    @Suppress("LongParameterList")
    fun body(
        request: OperationRequest,
        config: EksiConfig,
        nick: String,
        userId: Long,
        version: String,
        userAgent: String,
        plannedAction: Int,
        performedAction: Int,
        successfulAction: Int,
        isEarlyStopped: Boolean,
        logLines: List<String>,
        targets: List<Pair<String, Long>>,
    ): String {
        val action = buildJsonObject {
            put("eksi_engel_user", user(nick, userId))
            put("client", JsonPrimitive(CLIENT))
            put("version", JsonPrimitive(version))
            put("user_agent", JsonPrimitive(userAgent))
            put("ban_source", JsonPrimitive(request.source.pk))
            put("ban_mode", JsonPrimitive(request.mode.pk))

            /*
             * Everyone the run acted on, in plaintext.
             *
             * openspec/specs/android-persistence records this as a weighed
             * decision rather than an oversight: the admin's most_banned and
             * user-stat views rank by nick and id, so an empty list would make
             * every Android run invisible to them while still counting as
             * telemetry. A resumed run reports only the slice its worker ran --
             * the buffer does not survive the process, and reconstructing it
             * would mean persisting every target for the sake of a report.
             */
            put(
                "author_list",
                buildJsonArray {
                    for ((nick, id) in targets) {
                        user(nick, id).takeIf { it != JsonNull }?.let { add(it) }
                    }
                },
            )
            put("author_list_size", JsonPrimitive(targets.size))

            put("planned_action", JsonPrimitive(plannedAction))
            put("performed_action", JsonPrimitive(performedAction))
            put("successful_action", JsonPrimitive(successfulAction))
            put("is_early_stopped", JsonPrimitive(isEarlyStopped))

            // Matching background.js:1061-1067: a level is always sent, and the
            // log body only when the user allows logs.
            val sendLog = config.sendLog && logLines.isNotEmpty()
            put("log_level", JsonPrimitive(if (sendLog) LOG_LEVEL_INFO else LOG_LEVEL_DISABLED))
            put("log", if (sendLog) JsonPrimitive(logLines.joinToString("\n").take(MAX_LOG)) else JsonNull)

            put("target_type", JsonPrimitive(request.targetType.pk))
            put("click_source", JsonNull)

            // FAV columns. The entry's author is the one the run is named after;
            // the title is not resolved on this client, and the serializer
            // accepts each of the three independently.
            put("fav_author", user(request.authorNick.orEmpty(), request.authorId ?: 0))
            put("fav_title", JsonNull)
            put("fav_entry", JsonNull)

            put("time_specifier", if (request.lastDayOnly) JsonPrimitive(TIME_LAST_24H) else JsonNull)
            put("date_criteria", JsonNull)
            put("bulk_action", JsonNull)
            put("source_list", JsonNull)
        }

        val actionConfig = buildJsonObject {
            put("eksi_sozluk_url", JsonPrimitive(config.eksiSozlukUrl))
            put("send_data", JsonPrimitive(config.sendData))
            put("enable_noob_ban", JsonPrimitive(config.enableNoobBan))
            put("enable_mute", JsonPrimitive(config.enableMute))
            put("enable_title_ban", JsonPrimitive(config.enableTitleBan))
            put("enable_anaylsis_before_operations", JsonPrimitive(config.enableAnalysisBeforeOperation))
            put("enable_only_required_actions", JsonPrimitive(config.enableOnlyRequiredActions))
            put("enable_protect_followed_users", JsonPrimitive(config.enableProtectFollowedUsers))
            put("ban_premium_icons", JsonPrimitive(config.banPremiumIcons))
        }

        return JsonObject(mapOf("action" to action, "action_config" to actionConfig)).toString()
    }

    /**
     * A user the backend can key on, or null.
     *
     * createEksiSozlukUser (commHandler.js:38-44) returns null unless both the
     * name and the id are present, and the serializer treats a half-filled user
     * as a validation error rather than an unknown one. Same rule here.
     */
    private fun user(nick: String, id: Long): JsonElement =
        if (nick.isBlank() || id <= 0L) {
            JsonNull
        } else {
            buildJsonObject {
                put("eksisozluk_name", JsonPrimitive(nick))
                put("eksisozluk_id", JsonPrimitive(id))
            }
        }

    /*
     * The seeded LogLevel rows are 1..4 = DEBUG, INFO, WARNING, ERROR
     * (0007_seed_lookup_data.py). The extension's DISABLED is also 1, so 1 means
     * "no log attached" on both clients even though the row reads DEBUG.
     */
    private const val LOG_LEVEL_DISABLED = 1
    private const val LOG_LEVEL_INFO = 2

    /** TimeSpecifier row 1. */
    private const val TIME_LAST_24H = 1

    /** The column is 1 MB; the serializer rejects anything over 10 000 characters. */
    private const val MAX_LOG = 10_000
}
