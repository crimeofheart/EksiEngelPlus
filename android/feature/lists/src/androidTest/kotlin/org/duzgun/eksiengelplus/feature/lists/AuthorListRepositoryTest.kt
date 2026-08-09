package org.duzgun.eksiengelplus.feature.lists

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.model.ListType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthorListRepositoryTest {

    private lateinit var db: EksiDatabase
    private lateinit var repo: AuthorListRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EksiDatabase::class.java,
        ).build()
        repo = AuthorListRepository(db)
    }

    @After fun tearDown() = db.close()

    private fun rows(vararg nicks: String) = nicks.map { CsvCodec.Row(it, null) }

    @Test fun replaceKeepsInsertionOrder() = runTest {
        repo.replaceAll(rows("ucuncu", "birinci", "ikinci"))

        assertThat(repo.nicksNow()).containsExactly("ucuncu", "birinci", "ikinci").inOrder()
    }

    @Test fun duplicateNicksCollapseToOneRow() = runTest {
        repo.replaceAll(listOf(CsvCodec.Row("birisi", null), CsvCodec.Row("birisi", 100L)))

        assertThat(repo.nicksNow()).containsExactly("birisi")
    }

    @Test fun replaceDropsThePreviousList() = runTest {
        repo.replaceAll(rows("eski"))
        repo.replaceAll(rows("yeni"))

        assertThat(repo.nicksNow()).containsExactly("yeni")
    }

    @Test fun parsedDatesSeedTheRegistrationCache() = runTest {
        repo.replaceAll(listOf(CsvCodec.Row("birisi", 14_700L), CsvCodec.Row("baskasi", null)))

        assertThat(db.registrationDates().get("birisi")?.registrationEpochDay).isEqualTo(14_700L)
        assertThat(db.registrationDates().get("baskasi")).isNull()
    }

    /**
     * The guarantee that makes replace safe: the parse happens before the write, and
     * the write is one transaction, so nothing can leave the user with half a list
     * and no way back.
     */
    @Test fun aFailedWriteLeavesThePreviousListIntact() = runTest {
        repo.replaceAll(rows("eski_bir", "eski_iki"))

        val boom = runCatching {
            db.withTransaction {
                db.authorList().clear()
                db.authorList().insertIgnoring("yeni", null, 1)
                error("import blew up partway")
            }
        }

        assertThat(boom.isFailure).isTrue()
        assertThat(repo.nicksNow()).containsExactly("eski_bir", "eski_iki").inOrder()
    }

    @Test fun countIsObservedNotStored() = runTest {
        repo.replaceAll(rows("a", "b", "c"))

        assertThat(repo.count.first()).isEqualTo(3)
    }

    @Test fun syncDoesNotForgetAKnownRegistrationDate() = runTest {
        val relations = db.relationUsers()
        relations.markSeen(ListType.BLOCKED, 1L, "birisi", seenAt = 100, null, null)
        relations.upsert(
            relations.get(ListType.BLOCKED).single().copy(registrationDate = 14_700L, addedAt = 5),
        )

        // A later sync sees the same user again and must not null the date out.
        relations.markSeen(ListType.BLOCKED, 1L, "birisi", seenAt = 900, null, null)

        val row = relations.get(ListType.BLOCKED).single()
        assertThat(row.registrationDate).isEqualTo(14_700L)
        assertThat(row.addedAt).isEqualTo(5)
        assertThat(row.lastSeenAt).isEqualTo(900)
    }

    @Test fun pruneStaleRemovesOnlyUsersNotSeenInThisPass() = runTest {
        val relations = db.relationUsers()
        relations.markSeen(ListType.BLOCKED, 1L, "kalan", seenAt = 900, null, null)
        relations.markSeen(ListType.BLOCKED, 2L, "giden", seenAt = 100, null, null)

        assertThat(relations.pruneStale(ListType.BLOCKED, seenBefore = 900)).isEqualTo(1)
        assertThat(relations.get(ListType.BLOCKED).map { it.nick }).containsExactly("kalan")
    }
}
