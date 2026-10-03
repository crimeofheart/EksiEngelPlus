package org.duzgun.eksiengelplus.ops.runtime

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest

/**
 * How a run names itself on every surface.
 *
 * "favlayanlar" alone identifies a kind of work, not a piece of it. Queue three
 * of them from three entries and the screen shows the same row three times, with
 * nothing to say which one is running and which two are waiting -- so the nick
 * the run is about goes in brackets after the name: "favlayanlar (coh)".
 *
 * Lives in :ops:runtime rather than the screen module because the notification
 * needs the same string and cannot depend on :feature:lists.
 */
object OperationLabel {

    /**
     * The run's name, with its target in brackets when there is one, after what
     * it does when that is not already in the name: "takip: favlayanlar (coh)".
     */
    fun of(context: Context, source: BanSource?, target: String?, kind: Kind? = null): String {
        val name = context.getString(sourceRes(source))
        val named = if (target.isNullOrBlank()) {
            name
        } else {
            context.getString(R.string.src_with_target, name, target)
        }
        return if (kind == null) named else context.getString(R.string.src_with_kind, context.getString(kind.res), named)
    }

    /** The same, from the BanSource constant a checkpoint stores. */
    fun of(context: Context, sourceName: String, target: String?, kind: Kind? = null): String =
        of(context, runCatching { BanSource.valueOf(sourceName) }.getOrNull(), target, kind)

    /** The same, for a request in hand. */
    fun of(context: Context, request: OperationRequest): String =
        of(context, request.source, target(request), kind(request))

    /**
     * What a run does to its targets.
     *
     * The same six categories the extension's İşlem durumu shows
     * (getTaskCategory in frontend/app/assets/js/queue.js). Without them a
     * queued "favlayanlar (coh)" was the same row whether it would block,
     * mute or follow those people -- SINGLE, FAV, FOLLOW, LIST, TITLE and
     * DATE_BASED_BULK each carry every relation.
     */
    enum class Kind(val res: Int) {
        BLOCK(R.string.kind_block),
        UNBLOCK(R.string.kind_unblock),
        MUTE(R.string.kind_mute),
        UNMUTE(R.string.kind_unmute),
        FOLLOW(R.string.kind_follow),
        UNFOLLOW(R.string.kind_unfollow),
    }

    /**
     * Which [Kind] a request is, or null where the source's name already says
     * it ("tüm engelleri kaldırma", "liste yenileme").
     *
     * BLOCKED_MUTED_TITLES is not one of those: it runs in both directions, and
     * its name, "başlık engelleme", called the unblock run a block too. It is
     * now named for its audience and takes its kind like the others.
     *
     * Read off the request alone: Android resolves block-or-mute when the run
     * is enqueued, so the target type is always explicit, unlike the
     * extension's FAV and FOLLOW menu items.
     */
    fun kind(request: OperationRequest): Kind? {
        when (request.source) {
            BanSource.UNDOBANALL,
            BanSource.UNMUTEALL,
            BanSource.MIGRATE_BLOCKED_TO_MUTED,
            BanSource.BLOCK_MUTED_USERS,
            BanSource.REFRESH_BLOCKED_LIST,
            BanSource.REFRESH_MUTED_LIST,
            BanSource.REFRESH_FOLLOWED_LIST,
            -> return null
            else -> Unit
        }
        val undo = request.mode == BanMode.UNDOBAN
        return when {
            // "engeli kaldır ve takip et": the unblock is the way to the follow.
            request.thenApplyTo == TargetType.FOLLOW -> Kind.FOLLOW
            request.targetType == TargetType.FOLLOW -> if (undo) Kind.UNFOLLOW else Kind.FOLLOW
            request.targetType == TargetType.MUTE -> if (undo) Kind.UNMUTE else Kind.MUTE
            else -> if (undo) Kind.UNBLOCK else Kind.BLOCK
        }
    }

    /** The kind of a stored request -- checkpoint requestJson or queue payloadJson. */
    fun kindFromRequest(json: String?): Kind? = decode(json)?.let(::kind)

    /**
     * Which nick the run is about.
     *
     * Not every source has one -- a full unblock sweep is about the whole list --
     * and null means the label stays bare rather than growing empty brackets.
     */
    fun target(request: OperationRequest): String? = when (request.source) {
        // FAV is the entry's author, not the favouriters: the user picked the
        // entry from that author's line, and it is what they will recognise.
        BanSource.SINGLE, BanSource.FAV, BanSource.FOLLOW -> request.authorNick
        BanSource.TITLE -> request.titleSlug
        // A pasted list has no single subject; naming one of many would be a lie.
        BanSource.LIST -> request.nicks.singleOrNull()
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** The target of a stored request -- checkpoint requestJson or queue payloadJson. */
    fun targetFromRequest(json: String?): String? = decode(json)?.let(::target)

    private fun decode(json: String?): OperationRequest? = json
        ?.let { runCatching { Json.decodeFromString(OperationRequest.serializer(), it) }.getOrNull() }

    /**
     * The target, kept for history.
     *
     * completed_operation holds no request -- the checkpoint that carried it is
     * removed as the run is archived -- so the nick has to survive in the summary
     * or the finished list loses it the moment the run ends.
     */
    fun summaryJson(target: String?): String = buildJsonObject {
        if (!target.isNullOrBlank()) put(KEY_TARGET, JsonPrimitive(target))
    }.toString()

    /** Reads back what summaryJson wrote. Older rows hold "{}" and yield null. */
    fun targetFromSummary(json: String?): String? = json
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?.get(KEY_TARGET)
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    private const val KEY_TARGET = "target"

    private fun sourceRes(source: BanSource?): Int = when (source) {
        BanSource.SINGLE -> R.string.src_single
        BanSource.FAV -> R.string.src_fav
        BanSource.FOLLOW -> R.string.src_follow
        BanSource.FOLLOWEES -> R.string.src_followees
        BanSource.LIST -> R.string.src_list
        BanSource.TITLE -> R.string.src_title
        BanSource.UNDOBANALL -> R.string.src_undobanall
        BanSource.UNMUTEALL -> R.string.src_unmuteall
        BanSource.BLOCKED_MUTED_TITLES -> R.string.src_blocked_muted_titles
        BanSource.MIGRATE_BLOCKED_TO_MUTED -> R.string.src_migrate
        BanSource.BLOCK_MUTED_USERS -> R.string.src_block_muted
        BanSource.DATE_BASED_BULK -> R.string.src_date_based
        BanSource.REFRESH_BLOCKED_LIST,
        BanSource.REFRESH_MUTED_LIST,
        BanSource.REFRESH_FOLLOWED_LIST,
        -> R.string.src_refresh
        null -> R.string.src_unknown
    }
}
