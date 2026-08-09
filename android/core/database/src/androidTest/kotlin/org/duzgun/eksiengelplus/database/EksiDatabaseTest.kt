package org.duzgun.eksiengelplus.database

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.duzgun.eksiengelplus.model.ListType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EksiDatabaseTest {

    private lateinit var db: EksiDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EksiDatabase::class.java,
        ).build()
    }

    @After fun tearDown() = db.close()

    private fun user(id: Long, nick: String, list: ListType, reg: Long? = null) =
        RelationUserEntity(
            listType = list, userId = id, nick = nick,
            addedAt = 0, lastSeenAt = 0, registrationDate = reg,
        )

    @Test fun countFollowsContentWithNoStoredCounter() = runTest {
        val dao = db.relationUsers()
        assertThat(dao.countOfNow(ListType.MUTED)).isEqualTo(0)
        dao.upsertAll(listOf(user(1, "a", ListType.MUTED), user(2, "b", ListType.MUTED)))
        assertThat(dao.countOfNow(ListType.MUTED)).isEqualTo(2)
        dao.delete(ListType.MUTED, 1)
        assertThat(dao.countOfNow(ListType.MUTED)).isEqualTo(1)
    }

    @Test fun reScrapingIsIdempotentOnTheCompositeKey() = runTest {
        val dao = db.relationUsers()
        dao.upsert(user(1, "a", ListType.BLOCKED))
        dao.upsert(user(1, "a-renamed", ListType.BLOCKED))
        assertThat(dao.countOfNow(ListType.BLOCKED)).isEqualTo(1)
        assertThat(dao.get(ListType.BLOCKED).single().nick).isEqualTo("a-renamed")
    }

    @Test fun sameUserCanAppearInDifferentLists() = runTest {
        val dao = db.relationUsers()
        dao.upsert(user(1, "a", ListType.BLOCKED))
        dao.upsert(user(1, "a", ListType.MUTED))
        assertThat(dao.countOfNow(ListType.BLOCKED)).isEqualTo(1)
        assertThat(dao.countOfNow(ListType.MUTED)).isEqualTo(1)
    }

    @Test fun clearingOneListLeavesOthersIntact() = runTest {
        val dao = db.relationUsers()
        dao.upsert(user(1, "a", ListType.BLOCKED))
        dao.upsert(user(2, "b", ListType.FOLLOWED))
        dao.clear(ListType.BLOCKED)
        assertThat(dao.countOfNow(ListType.BLOCKED)).isEqualTo(0)
        assertThat(dao.countOfNow(ListType.FOLLOWED)).isEqualTo(1)
    }

    @Test fun dateFilteringIsAQueryNotNProfileFetches() = runTest {
        val dao = db.relationUsers()
        dao.upsertAll(
            listOf(
                user(1, "old", ListType.MUTED, reg = 10_000),
                user(2, "new", ListType.MUTED, reg = 20_000),
                user(3, "unknown", ListType.MUTED, reg = null),
            ),
        )
        val old = dao.olderThan(ListType.MUTED, 15_000)
        // Null registration dates are excluded: unknown is not the same as old.
        assertThat(old.map { it.nick }).containsExactly("old")
    }

    @Test fun registrationCacheHonoursTheTtl() = runTest {
        val dao = db.registrationDates()
        val now = 1_000_000_000L
        val stale = now - RegistrationDateCacheEntity.TTL_MS - 1
        dao.upsert(RegistrationDateCacheEntity("fresh", 1, 100, now))
        dao.upsert(RegistrationDateCacheEntity("stale", 2, 200, stale))

        val cutoff = now - RegistrationDateCacheEntity.TTL_MS
        assertThat(dao.getFresh("fresh", cutoff)).isNotNull()
        assertThat(dao.getFresh("stale", cutoff)).isNull()
        // The stale row still exists until trimmed -- absence and expiry differ.
        assertThat(dao.get("stale")).isNotNull()
        assertThat(dao.trimExpired(cutoff)).isEqualTo(1)
        assertThat(dao.size()).isEqualTo(1)
    }

    @Test fun expiredCountAgreesWithWhatTrimDeletes() = runTest {
        // The screen shows one number and the button acts on another if these
        // two predicates ever drift apart.
        val dao = db.registrationDates()
        val now = 1_000_000_000L
        val cutoff = now - RegistrationDateCacheEntity.TTL_MS
        dao.upsert(RegistrationDateCacheEntity("fresh", 1, 100, now))
        dao.upsert(RegistrationDateCacheEntity("stale", 2, 200, cutoff - 1))
        dao.upsert(RegistrationDateCacheEntity("staler", 3, 300, 0))

        val predicted = dao.expiredCount(cutoff)
        assertThat(predicted).isEqualTo(2)
        assertThat(dao.trimExpired(cutoff)).isEqualTo(predicted)
        // Pruning never costs a refetch: the fresh row is untouched.
        assertThat(dao.getFresh("fresh", cutoff)).isNotNull()
        assertThat(dao.size()).isEqualTo(1)
    }

    @Test fun clearingTheCacheTakesTheFreshRowsToo() = runTest {
        val dao = db.registrationDates()
        dao.upsert(RegistrationDateCacheEntity("a", 1, 100, System.currentTimeMillis()))
        dao.upsert(RegistrationDateCacheEntity("b", 2, 200, System.currentTimeMillis()))

        dao.clear()

        assertThat(dao.size()).isEqualTo(0)
    }

    @Test fun aKnownAbsentRegistrationDateIsStillCached() = runTest {
        // "looked up, genuinely unknown" must not trigger a refetch every run.
        val dao = db.registrationDates()
        dao.upsert(RegistrationDateCacheEntity("nobody", null, null, 5_000))
        val row = dao.getFresh("nobody", 0)
        assertThat(row).isNotNull()
        assertThat(row!!.registrationEpochDay).isNull()
    }

    @Test fun syncStateStoresACursorRatherThanAPayload() = runTest {
        val dao = db.listSyncState()
        dao.upsert(ListSyncStateEntity(ListType.MUTED, cursorPage = 7, isPartial = true, lastFullRefreshAt = null, updatedAt = 1))
        assertThat(dao.get(ListType.MUTED)!!.cursorPage).isEqualTo(7)
        dao.upsert(ListSyncStateEntity(ListType.MUTED, cursorPage = 8, isPartial = false, lastFullRefreshAt = 99, updatedAt = 2))
        val s = dao.get(ListType.MUTED)!!
        assertThat(s.cursorPage).isEqualTo(8)
        assertThat(s.isPartial).isFalse()
    }

    @Test fun checkpointAndListRowsCommitAtomically() = runTest {
        // Cursor and content must never be written separately: that is the one
        // place a crash corrupts user-visible state.
        db.withTransaction {
            db.relationUsers().upsert(user(1, "a", ListType.MUTED))
            db.checkpoints().upsert(
                OperationCheckpointEntity(
                    operationId = "op1", type = "REFRESH_MUTED", state = "RUNNING",
                    cursorJson = """{"page":1}""", processed = 1, total = 10,
                    successful = 1, failed = 0, startedAt = 0, updatedAt = 0,
                    workRequestId = null,
                ),
            )
        }
        assertThat(db.relationUsers().countOfNow(ListType.MUTED)).isEqualTo(1)
        assertThat(db.checkpoints().get("op1")!!.processed).isEqualTo(1)
    }

    @Test fun completedHistoryTrimsToTheKeepWindow() = runTest {
        val dao = db.completedOperations()
        repeat(12) { i ->
            dao.insert(
                CompletedOperationEntity(
                    banSourcePk = 1, banModePk = 1, processed = i, successful = i,
                    failed = 0, startedAt = i.toLong(), finishedAt = i.toLong(),
                    summaryJson = "{}",
                ),
            )
        }
        assertThat(dao.trim(keep = 5)).isEqualTo(7)
    }

    @Test fun authorListNickIsUnique() = runTest {
        val dao = db.authorList()
        dao.upsertAll(listOf(AuthorListEntity(nick = "a", authorId = 1, addedAt = 0)))
        dao.upsertAll(listOf(AuthorListEntity(nick = "a", authorId = 2, addedAt = 1)))
        // Upsert on a unique index replaces rather than duplicating.
        assertThat(db.query("SELECT COUNT(*) FROM author_list", null).use {
            it.moveToFirst(); it.getInt(0)
        }).isEqualTo(1)
    }
}
