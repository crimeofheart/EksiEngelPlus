package org.duzgun.eksiengelplus.feature.lists

import org.duzgun.eksiengelplus.eksi.client.SessionExpiredException
import org.duzgun.eksiengelplus.model.ListType

/** One user as a relation endpoint reports them. */
data class SeenUser(
    val id: Long,
    val nick: String,
    val isFollowCurrentUser: Boolean? = null,
    val isBuddy: Boolean? = null,
)

/** One page, plus whether the endpoint said it was the last one. */
data class SeenPage(val users: List<SeenUser>, val isLast: Boolean)

/**
 * The paging half of a sync, narrowed to what [ListSyncer] needs.
 *
 * An interface rather than ScrapeClient itself so the syncer stays a pure-JVM
 * class with unit tests -- the paging and pruning rules are exactly the logic
 * worth testing without an emulator.
 */
interface RelationSource {
    suspend fun ownNick(): String?
    suspend fun page(listType: ListType, ownNick: String, pageIndex: Int): SeenPage
}

/** The persistence half, same reasoning. */
interface ListSyncStore {
    suspend fun cursor(listType: ListType): Int
    suspend fun markSeen(listType: ListType, users: List<SeenUser>, seenAt: Long)
    suspend fun advance(listType: ListType, nextPage: Int, at: Long)
    suspend fun finishPartial(listType: ListType, nextPage: Int, at: Long)
    suspend fun finishComplete(listType: ListType, at: Long)
    suspend fun pruneStale(listType: ListType, seenBefore: Long): Int
}

sealed interface SyncOutcome {
    /** Reached the endpoint's terminator. [pruned] is 0 on a resumed pass -- see below. */
    data class Completed(val seen: Int, val pruned: Int) : SyncOutcome

    /** The user asked it to stop. What was fetched is kept. */
    data class Stopped(val seen: Int) : SyncOutcome

    /** The session is gone. Rows are untouched, so a logged-out user keeps their list. */
    data object SessionLost : SyncOutcome

    data class Failed(val seen: Int, val cause: Throwable) : SyncOutcome
}

/**
 * Fills `relation_user` from the site, page by page, resumably.
 *
 * Replaces the extension's refreshMutedList / refreshBlockedList /
 * refreshFollowedList (notificationHandler.js:216-317) and their `partial*`
 * storage keys -- a row-per-user schema makes the separate partial key
 * unnecessary, because a half-finished list is just fewer rows.
 */
class ListSyncer(
    private val source: RelationSource,
    private val store: ListSyncStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Syncs one list. [shouldStop] is polled between pages, so a stop is
     * cooperative and never loses the page in flight.
     */
    suspend fun sync(listType: ListType, shouldStop: () -> Boolean = { false }): SyncOutcome {
        val startedAt = now()
        val firstPage = store.cursor(listType).coerceAtLeast(FIRST_PAGE)
        val resumed = firstPage > FIRST_PAGE

        var page = firstPage
        var seen = 0

        try {
            val nick = source.ownNick() ?: return SyncOutcome.SessionLost

            while (true) {
                if (shouldStop()) {
                    store.finishPartial(listType, page, now())
                    return SyncOutcome.Stopped(seen)
                }

                val result = source.page(listType, nick, page)
                store.markSeen(listType, result.users, startedAt)
                seen += result.users.size
                page++

                if (result.isLast) break
                store.advance(listType, page, now())
            }
        } catch (e: SessionExpiredException) {
            // Deliberately not treated as an empty list. HTML where JSON was expected
            // means logged out, and the extension's equivalent path is where a
            // logged-out refresh silently blanks the stored list.
            store.finishPartial(listType, page, now())
            return SyncOutcome.SessionLost
        } catch (e: Exception) {
            store.finishPartial(listType, page, now())
            return SyncOutcome.Failed(seen, e)
        }

        /*
         * Pruning is what removes users who have left the list, and it can only run
         * on a pass that saw the whole list in one go.
         *
         * A resumed pass cannot prune: pages before the cursor were stamped by an
         * earlier attempt with an earlier timestamp, so pruning by `lastSeenAt <
         * startedAt` would delete exactly the rows resumption existed to preserve.
         * Persisting the original pass timestamp would fix that at the cost of a
         * schema version; leaving departed users in place until one uninterrupted
         * pass runs is the cheaper and safer half of that trade.
         */
        val pruned = if (resumed) 0 else store.pruneStale(listType, startedAt)
        store.finishComplete(listType, now())
        return SyncOutcome.Completed(seen, pruned)
    }

    private companion object {
        /** pageIndex is 1-based on both endpoint families; page 0 returns HTTP 500. */
        const val FIRST_PAGE = 1
    }
}
