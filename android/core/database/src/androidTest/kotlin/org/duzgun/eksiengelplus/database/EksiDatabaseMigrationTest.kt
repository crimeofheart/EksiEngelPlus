package org.duzgun.eksiengelplus.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Version 1 is installed on real devices, so the upgrade has to be run, not read.
 *
 * `fallbackToDestructiveMigration` is deliberately off — it would take the user's
 * synced lists and history with it — which means a migration that does not apply
 * is a crash on launch for everyone who already has the app, and nothing before
 * this executed one.
 */
@RunWith(AndroidJUnit4::class)
class EksiDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EksiDatabase::class.java,
    )

    @Test
    fun oneToTwoAddsTheColumnAndKeepsTheRunsAlreadyRecorded() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO completed_operation
                  (id, banSourcePk, banModePk, processed, successful, failed,
                   startedAt, finishedAt, summaryJson)
                VALUES (7, 1, 1, 3, 2, 1, 100, 200, '{}')
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(NAME, 2, true, *EksiDatabase.MIGRATIONS).use { db ->
            db.query("SELECT id, successful, requestJson FROM completed_operation").use { row ->
                assertThat(row.moveToFirst()).isTrue()
                assertThat(row.getLong(0)).isEqualTo(7)
                assertThat(row.getInt(1)).isEqualTo(2)
                // Null rather than absent: the request these rows came from was
                // deleted with their checkpoint, so there is nothing to backfill.
                assertThat(row.isNull(2)).isTrue()
                assertThat(row.moveToNext()).isFalse()
            }
        }
    }

    @Test
    fun twoToThreeAddsTheLedgerTablesAndKeepsQueueAndHistory() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO queued_task
                  (id, seq, banSourcePk, banModePk, targetTypePk, clickSourcePk, payloadJson, status, enqueuedAt)
                VALUES (3, 1, 4, 1, 1, NULL, '{}', 'QUEUED', 50)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO completed_operation
                  (id, banSourcePk, banModePk, processed, successful, failed,
                   startedAt, finishedAt, summaryJson, requestJson)
                VALUES (7, 1, 1, 3, 2, 1, 100, 200, '{}', NULL)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(NAME, 3, true, *EksiDatabase.MIGRATIONS).use { db ->
            db.query("SELECT id, taskId FROM queued_task").use { row ->
                assertThat(row.moveToFirst()).isTrue()
                assertThat(row.getLong(0)).isEqualTo(3)
                assertThat(row.isNull(1)).isTrue()
            }
            db.query("SELECT id, taskId, stopped FROM completed_operation").use { row ->
                assertThat(row.moveToFirst()).isTrue()
                assertThat(row.getLong(0)).isEqualTo(7)
                assertThat(row.isNull(1)).isTrue()
                assertThat(row.getInt(2)).isEqualTo(0)
            }
            for (table in listOf("history_outbox", "history_task", "remote_task", "history_sync_state")) {
                db.query("SELECT COUNT(*) FROM $table").use { row ->
                    assertThat(row.moveToFirst()).isTrue()
                    assertThat(row.getInt(0)).isEqualTo(0)
                }
            }
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
