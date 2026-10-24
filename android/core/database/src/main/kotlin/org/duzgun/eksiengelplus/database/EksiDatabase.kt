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
        HistoryOutboxEntity::class,
        HistoryTaskEntity::class,
        RemoteTaskEntity::class,
        HistorySyncStateEntity::class,
    ],
    version = 3,
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
    abstract fun historyOutbox(): HistoryOutboxDao
    abstract fun historyTasks(): HistoryTaskDao
    abstract fun remoteTasks(): RemoteTaskDao
    abstract fun historySyncState(): HistorySyncStateDao

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

        /**
         * The history ledger's tables, and the task id the queue and the
         * archive now carry so a task is one id from queueing to the server.
         *
         * Additive only: existing queued and completed rows keep a null task
         * id -- the queue gives an old row a fresh one when it is adopted, and
         * history that predates the ledger is not reconstructed.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queued_task ADD COLUMN taskId TEXT")
                db.execSQL("ALTER TABLE completed_operation ADD COLUMN taskId TEXT")
                db.execSQL("ALTER TABLE completed_operation ADD COLUMN stopped INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `history_outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`eventUuid` TEXT NOT NULL, `taskId` TEXT, `kind` TEXT NOT NULL, `direction` TEXT NOT NULL, " +
                        "`result` TEXT NOT NULL, `actorId` INTEGER NOT NULL, `actorNick` TEXT, " +
                        "`targetId` INTEGER NOT NULL, `targetNick` TEXT, `occurredAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_history_outbox_eventUuid` " +
                        "ON `history_outbox` (`eventUuid`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `history_task` (`taskId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `banSourcePk` INTEGER NOT NULL, `banModePk` INTEGER NOT NULL, " +
                        "`description` TEXT NOT NULL, `requestJson` TEXT, `actorId` INTEGER, " +
                        "`planned` INTEGER NOT NULL, `performed` INTEGER NOT NULL, `successful` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `cancelRequested` INTEGER NOT NULL, " +
                        "`pushed` INTEGER NOT NULL, PRIMARY KEY(`taskId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_task` (`taskId` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `ownerLabel` TEXT NOT NULL, `ownerClient` TEXT, " +
                        "`performed` INTEGER NOT NULL, `planned` INTEGER NOT NULL, " +
                        "`cancelRequested` INTEGER NOT NULL, `requestJson` TEXT, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `history_sync_state` (`id` INTEGER NOT NULL, " +
                        "`cursor` INTEGER NOT NULL, `failures` INTEGER NOT NULL, `backoffUntil` INTEGER NOT NULL, " +
                        "`lastSyncAt` INTEGER NOT NULL, `droppedEvents` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
