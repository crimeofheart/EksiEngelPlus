package org.duzgun.eksiengelplus.feature.lists

import androidx.room.withTransaction
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.ListSyncStateEntity
import org.duzgun.eksiengelplus.eksi.client.FollowEndpoint
import org.duzgun.eksiengelplus.eksi.client.ScrapeClient
import org.duzgun.eksiengelplus.model.ListType
import org.duzgun.eksiengelplus.model.TargetType
import javax.inject.Inject

/**
 * Binds [RelationSource] to the real endpoints.
 *
 * The two families do not agree on how a list ends and this is where that is
 * reconciled: /relation-list returns `relations.isLast`, /following returns a bare
 * array whose emptiness is the only terminator (ScrapeClient.kt:94 and :123).
 */
class ScrapeRelationSource @Inject constructor(
    private val scrape: ScrapeClient,
) : RelationSource {

    override suspend fun ownNick(): String? = scrape.ownNick()

    override suspend fun page(listType: ListType, ownNick: String, pageIndex: Int): SeenPage =
        when (listType) {
            ListType.BLOCKED -> relationPage(TargetType.USER, pageIndex)
            ListType.MUTED -> relationPage(TargetType.MUTE, pageIndex)
            ListType.FOLLOWED -> followingPage(ownNick, pageIndex)
        }

    private suspend fun relationPage(targetType: TargetType, pageIndex: Int): SeenPage {
        val page = scrape.relationPage(targetType, pageIndex)
        return SeenPage(
            users = page.nicks.zip(page.ids) { nick, id -> SeenUser(id = id, nick = nick) },
            isLast = page.isLast,
        )
    }

    private suspend fun followingPage(ownNick: String, pageIndex: Int): SeenPage {
        val users = scrape.followPage(FollowEndpoint.FOLLOWING, ownNick, pageIndex)
        return SeenPage(
            users = users.map {
                SeenUser(
                    id = it.id,
                    nick = it.nick.value,
                    isFollowCurrentUser = it.isFollowCurrentUser,
                    isBuddy = it.isBuddy,
                )
            },
            // No IsLast on this endpoint: an empty page is the end, and it
            // contributes nothing, so reporting it as last is exact.
            isLast = users.isEmpty(),
        )
    }
}

/**
 * Binds [ListSyncStore] to Room.
 *
 * `cursorPage` is the *next* page to fetch, so a completed pass resets it to 1 and
 * the next refresh starts from the top rather than off the end of the list.
 */
class RoomListSyncStore @Inject constructor(
    private val db: EksiDatabase,
) : ListSyncStore {

    private val relations get() = db.relationUsers()
    private val state get() = db.listSyncState()

    override suspend fun cursor(listType: ListType): Int =
        state.get(listType)?.takeIf { it.isPartial }?.cursorPage ?: FIRST_PAGE

    override suspend fun markSeen(listType: ListType, users: List<SeenUser>, seenAt: Long) {
        if (users.isEmpty()) return
        db.withTransaction {
            for (u in users) {
                relations.markSeen(listType, u.id, u.nick, seenAt, u.isFollowCurrentUser, u.isBuddy)
            }
        }
    }

    override suspend fun advance(listType: ListType, nextPage: Int, at: Long) {
        write(listType, nextPage, isPartial = true, at = at)
    }

    override suspend fun finishPartial(listType: ListType, nextPage: Int, at: Long) {
        write(listType, nextPage, isPartial = true, at = at)
    }

    override suspend fun finishComplete(listType: ListType, at: Long) {
        write(listType, FIRST_PAGE, isPartial = false, at = at, fullRefreshAt = at)
    }

    override suspend fun pruneStale(listType: ListType, seenBefore: Long): Int =
        relations.pruneStale(listType, seenBefore)

    private suspend fun write(
        listType: ListType,
        cursorPage: Int,
        isPartial: Boolean,
        at: Long,
        fullRefreshAt: Long? = null,
    ) {
        val previous = state.get(listType)
        state.upsert(
            ListSyncStateEntity(
                listType = listType,
                cursorPage = cursorPage,
                isPartial = isPartial,
                lastFullRefreshAt = fullRefreshAt ?: previous?.lastFullRefreshAt,
                updatedAt = at,
            ),
        )
    }

    private companion object {
        const val FIRST_PAGE = 1
    }
}
