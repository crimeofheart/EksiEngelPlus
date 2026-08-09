package org.duzgun.eksiengelplus.feature.lists

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.duzgun.eksiengelplus.database.EksiDatabase
import org.duzgun.eksiengelplus.database.RegistrationDateCacheEntity

/**
 * The saved author list — the extension's `userList` storage key
 * (authorListPage.js:22-31), and the target set a LIST operation runs against.
 */
@Singleton
class AuthorListRepository @Inject constructor(
    private val db: EksiDatabase,
) {

    private fun now() = System.currentTimeMillis()

    val nicks: Flow<List<String>> = db.authorList().observe().map { rows -> rows.map { it.nick } }

    val count: Flow<Int> = db.authorList().count()

    /** The one-shot read an enqueue takes, in insertion order. */
    suspend fun nicksNow(): List<String> = db.authorList().getAll().map { it.nick }

    /**
     * Replaces the whole list.
     *
     * One transaction covering both the list and the seeded dates, so an import
     * that throws partway leaves the previous list intact rather than a half-written
     * one. Parsing happens before this is ever called, which is what makes that
     * guarantee cheap.
     */
    suspend fun replaceAll(rows: List<CsvCodec.Row>) = write(rows)

    suspend fun clear() = db.withTransaction { db.authorList().clear() }

    private suspend fun write(rows: List<CsvCodec.Row>) {
        val base = now()
        db.withTransaction {
            db.authorList().clear()
            rows.forEachIndexed { index, row ->
                // base + index, not now(): a hundred rows landing in the same
                // millisecond would otherwise order arbitrarily, and insertion order
                // is what a LIST run walks.
                db.authorList().insertIgnoring(row.nick, authorId = null, addedAt = base + index)
            }
            val dated = rows.filter { it.epochDay != null }
            if (dated.isNotEmpty()) {
                db.registrationDates().upsertAll(
                    dated.map {
                        RegistrationDateCacheEntity(
                            nick = it.nick,
                            authorId = null,
                            registrationEpochDay = it.epochDay,
                            fetchedAt = base,
                        )
                    },
                )
            }
        }
    }
}
