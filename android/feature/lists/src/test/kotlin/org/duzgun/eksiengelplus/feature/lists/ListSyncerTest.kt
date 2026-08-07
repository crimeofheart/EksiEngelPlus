package org.duzgun.eksiengelplus.feature.lists

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.duzgun.eksiengelplus.eksi.client.SessionExpiredException
import org.duzgun.eksiengelplus.model.ListType
import org.junit.Test

/** Pages of nicks, one list per page, indexed 1-based like the real endpoints. */
private class FakeSource(
    private val pages: List<List<SeenUser>>,
    private val nick: String? = "aktor",
    private val throwOnPage: Int? = null,
    private val terminatorless: Boolean = false,
) : RelationSource {

    val requested = mutableListOf<Int>()

    override suspend fun ownNick(): String? = nick

    override suspend fun page(listType: ListType, ownNick: String, pageIndex: Int): SeenPage {
        requested += pageIndex
        if (pageIndex == throwOnPage) throw SessionExpiredException("html from /relation-list")
        val users = pages.getOrElse(pageIndex - 1) { emptyList() }
        // terminatorless models /following, which never says isLast.
        val isLast = if (terminatorless) users.isEmpty() else pageIndex >= pages.size
        return SeenPage(users, isLast)
    }
}

private class FakeStore(private var cursor: Int = 1) : ListSyncStore {
    /** userId -> lastSeenAt, which is all the pruning rule looks at. */
    val rows = linkedMapOf<Long, Long>()
    var partial = false
    var fullRefreshAt: Long? = null
    var prunes = 0

    override suspend fun cursor(listType: ListType) = cursor

    override suspend fun markSeen(listType: ListType, users: List<SeenUser>, seenAt: Long) {
        users.forEach { rows[it.id] = seenAt }
    }

    override suspend fun advance(listType: ListType, nextPage: Int, at: Long) {
        cursor = nextPage
        partial = true
    }

    override suspend fun finishPartial(listType: ListType, nextPage: Int, at: Long) {
        cursor = nextPage
        partial = true
    }

    override suspend fun finishComplete(listType: ListType, at: Long) {
        cursor = 1
        partial = false
        fullRefreshAt = at
    }

    override suspend fun pruneStale(listType: ListType, seenBefore: Long): Int {
        prunes++
        val gone = rows.filterValues { it < seenBefore }.keys
        gone.forEach { rows.remove(it) }
        return gone.size
    }
}

private fun users(vararg ids: Long) = ids.map { SeenUser(id = it, nick = "nick$it") }

class ListSyncerTest {

    private fun syncer(source: RelationSource, store: ListSyncStore, at: Long = 1_000L) =
        ListSyncer(source, store, now = { at })

    @Test
    fun `complete pass writes every page and stops on isLast`() = runTest {
        val source = FakeSource(listOf(users(1, 2), users(3)))
        val store = FakeStore()

        val outcome = syncer(source, store).sync(ListType.BLOCKED)

        assertThat(outcome).isEqualTo(SyncOutcome.Completed(seen = 3, pruned = 0))
        assertThat(source.requested).containsExactly(1, 2).inOrder()
        assertThat(store.rows.keys).containsExactly(1L, 2L, 3L)
        assertThat(store.partial).isFalse()
        assertThat(store.fullRefreshAt).isEqualTo(1_000L)
    }

    @Test
    fun `followed list terminates on an empty page`() = runTest {
        val source = FakeSource(listOf(users(1), users(2)), terminatorless = true)
        val store = FakeStore()

        val outcome = syncer(source, store).sync(ListType.FOLLOWED)

        assertThat(outcome).isInstanceOf(SyncOutcome.Completed::class.java)
        // Page 3 is the empty terminator and must actually be fetched.
        assertThat(source.requested).containsExactly(1, 2, 3).inOrder()
        assertThat(store.rows.keys).containsExactly(1L, 2L)
    }

    @Test
    fun `interrupted sync resumes at the stored cursor`() = runTest {
        val pages = (1..10).map { users(it.toLong()) }
        val source = FakeSource(pages)
        val store = FakeStore(cursor = 8)

        syncer(source, store).sync(ListType.BLOCKED)

        assertThat(source.requested.first()).isEqualTo(8)
        assertThat(source.requested).containsNoneOf(1, 7)
    }

    @Test
    fun `resumed pass does not prune`() = runTest {
        // Pruning by lastSeenAt would delete exactly the pages resumption preserved.
        val source = FakeSource(listOf(users(1), users(2)))
        val store = FakeStore(cursor = 2).apply { rows[99L] = 1L }

        val outcome = syncer(source, store).sync(ListType.BLOCKED)

        assertThat(store.prunes).isEqualTo(0)
        assertThat(store.rows.keys).contains(99L)
        assertThat(outcome).isEqualTo(SyncOutcome.Completed(seen = 1, pruned = 0))
    }

    @Test
    fun `complete unbroken pass prunes departed users`() = runTest {
        val source = FakeSource(listOf(users(1, 2)))
        val store = FakeStore().apply { rows[99L] = 1L }

        val outcome = syncer(source, store).sync(ListType.BLOCKED)

        assertThat(store.rows.keys).containsExactly(1L, 2L)
        assertThat(outcome).isEqualTo(SyncOutcome.Completed(seen = 2, pruned = 1))
    }

    @Test
    fun `stop keeps what was fetched and prunes nothing`() = runTest {
        val pages = (1..10).map { users(it.toLong()) }
        val source = FakeSource(pages)
        val store = FakeStore().apply { rows[99L] = 1L }
        var pagesSeen = 0

        val outcome = ListSyncer(source, store, now = { 1_000L })
            .sync(ListType.BLOCKED) { pagesSeen++ >= 3 }

        assertThat(outcome).isEqualTo(SyncOutcome.Stopped(seen = 3))
        assertThat(store.rows.keys).containsExactly(1L, 2L, 3L, 99L)
        assertThat(store.partial).isTrue()
        assertThat(store.prunes).isEqualTo(0)
    }

    @Test
    fun `session loss leaves the previous list intact`() = runTest {
        val source = FakeSource(listOf(users(1), users(2), users(3)), throwOnPage = 2)
        val store = FakeStore().apply { rows[99L] = 1L }

        val outcome = syncer(source, store).sync(ListType.BLOCKED)

        assertThat(outcome).isEqualTo(SyncOutcome.SessionLost)
        assertThat(store.rows.keys).containsExactly(1L, 99L)
        assertThat(store.partial).isTrue()
        assertThat(store.prunes).isEqualTo(0)
    }

    @Test
    fun `no session at all is a session loss before any request`() = runTest {
        val source = FakeSource(listOf(users(1)), nick = null)
        val store = FakeStore()

        assertThat(syncer(source, store).sync(ListType.FOLLOWED)).isEqualTo(SyncOutcome.SessionLost)
        assertThat(source.requested).isEmpty()
    }

    @Test
    fun `progress is reported per page, after the rows are durable`() = runTest {
        val store = FakeStore()
        val reported = mutableListOf<SyncProgress>()
        val rowsAtEachReport = mutableListOf<Int>()

        syncer(FakeSource(listOf(users(1, 2), users(3), users(4, 5, 6))), store).sync(
            listType = ListType.BLOCKED,
            onProgress = {
                reported += it
                rowsAtEachReport += store.rows.size
            },
        )

        assertThat(reported).containsExactly(
            SyncProgress(page = 1, seen = 2),
            SyncProgress(page = 2, seen = 3),
            SyncProgress(page = 3, seen = 6),
        ).inOrder()

        // Progress that ran ahead of the write would promise rows a crash on the
        // next page would take back, so the store is already caught up at each report.
        assertThat(rowsAtEachReport).containsExactly(2, 3, 6).inOrder()
    }

    @Test
    fun `progress stops being reported once stopped`() = runTest {
        val pages = (1..10).map { users(it.toLong()) }
        val reported = mutableListOf<SyncProgress>()
        var pagesSeen = 0

        ListSyncer(FakeSource(pages), FakeStore(), now = { 1_000L }).sync(
            listType = ListType.BLOCKED,
            shouldStop = { pagesSeen++ >= 2 },
            onProgress = { reported += it },
        )

        assertThat(reported.map { it.page }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `a network failure is retryable, not a session loss`() = runTest {
        val source = object : RelationSource {
            override suspend fun ownNick() = "aktor"
            override suspend fun page(listType: ListType, ownNick: String, pageIndex: Int): SeenPage {
                if (pageIndex == 2) throw java.io.IOException("timeout")
                return SeenPage(users(1), isLast = false)
            }
        }
        val store = FakeStore()

        val outcome = syncer(source, store).sync(ListType.BLOCKED)

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        assertThat(store.partial).isTrue()
        assertThat(store.prunes).isEqualTo(0)
    }
}
