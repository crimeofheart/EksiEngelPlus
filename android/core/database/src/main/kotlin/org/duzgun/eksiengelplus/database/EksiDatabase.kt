package org.duzgun.eksiengelplus.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import org.duzgun.eksiengelplus.model.ListType

class Converters {
    @TypeConverter fun listTypeToString(v: ListType): String = v.name
    @TypeConverter fun stringToListType(v: String): ListType = ListType.valueOf(v)
}

/**
 * Version 1 stands until the first release: there is no installed base, so no
 * migrations are written yet. exportSchema is on and the exported JSON is
 * committed, with CI failing on drift -- an uncommitted schema change means a
 * migration was never authored.
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
    version = 1,
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

    companion object { const val NAME = "eksiengelplus.db" }
}
