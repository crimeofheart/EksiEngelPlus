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

    private companion object {
        const val NAME = "migration-test.db"
    }
}
