package org.duzgun.eksiengelplus.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.duzgun.eksiengelplus.model.ListType

class Converters {
    @TypeConverter fun listTypeToString(v: ListType): String = v.name
    @TypeConverter fun stringToListType(v: String): ListType = ListType.valueOf(v)
}

/**
 * exportSchema is on and the exported JSON is committed, with CI failing on
 * drift -- an uncommitted schema change means a migration was never authored.
 *
 * Version 1 shipped in v0.3.0, so there is an installed base from here on and
 * every bump needs a migration in [EksiDatabase.MIGRATIONS]. The builder
 * registers no destructive fallback on purpose: dropping the database would
 * take the user's synced lists and history with it.
 */
@Database(
    entities = [
        RelationUserEntity::class,
        ListSyncStateEntity::class,
        RegistrationDateCacheEntity::class,
        QueuedTaskEntity::class,
        OperationCheckpointEntity::class,
        CompletedOperationEntity::class,
        AuthorListEntity::class,
        TelemetryOutboxEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EksiDatabase : RoomDatabase() {
    abstract fun relationUsers(): RelationUserDao
    abstract fun listSyncState(): ListSyncStateDao
    abstract fun registrationDates(): RegistrationDateCacheDao
    abstract fun queuedTasks(): QueuedTaskDao
    abstract fun checkpoints(): OperationCheckpointDao
    abstract fun completedOperations(): CompletedOperationDao
    abstract fun authorList(): AuthorListDao
    abstract fun telemetryOutbox(): TelemetryOutboxDao

    companion object {
        const val NAME = "eksiengelplus.db"

        /**
         * Keeps the request that produced a finished run, so İşlem durumu can
         * queue it again or open what it acted on.
         *
         * Nullable and left null for existing rows: the request they came from
         * was deleted with their checkpoint when they were archived, and there
         * is nothing to backfill it from.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE completed_operation ADD COLUMN requestJson TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
