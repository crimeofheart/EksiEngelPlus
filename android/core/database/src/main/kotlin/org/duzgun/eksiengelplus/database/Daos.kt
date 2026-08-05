package org.duzgun.eksiengelplus.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.duzgun.eksiengelplus.model.ListType

/**
 * Flow-returning throughout. The UI observes; nothing polls. That is what
 * replaces storageHandler.js's roughly fifty getX/setX methods.
 */
@Dao
interface RelationUserDao {

    @Upsert
    suspend fun upsertAll(rows: List<RelationUserEntity>)

    @Upsert
    suspend fun upsert(row: RelationUserEntity)

    /** Derived, never stored -- content and count cannot drift apart. */
    @Query("SELECT COUNT(*) FROM relation_user WHERE listType = :listType")
    fun countOf(listType: ListType): Flow<Int>

    @Query("SELECT COUNT(*) FROM relation_user WHERE listType = :listType")
    suspend fun countOfNow(listType: ListType): Int

    @Query("SELECT * FROM relation_user WHERE listType = :listType ORDER BY nick")
    fun observe(listType: ListType): Flow<List<RelationUserEntity>>

    @Query("SELECT * FROM relation_user WHERE listType = :listType ORDER BY nick")
    suspend fun get(listType: ListType): List<RelationUserEntity>

    @Query("DELETE FROM relation_user WHERE listType = :listType AND userId = :userId")
    suspend fun delete(listType: ListType, userId: Long)

    @Query("DELETE FROM relation_user WHERE listType = :listType")
    suspend fun clear(listType: ListType)

    /** Date filtering is a query because registrationDate is denormalised onto the row. */
    @Query(
        "SELECT * FROM relation_user WHERE listType = :listType " +
            "AND registrationDate IS NOT NULL AND registrationDate < :beforeEpochDay",
    )
    suspend fun olderThan(listType: ListType, beforeEpochDay: Long): List<RelationUserEntity>
}

@Dao
interface ListSyncStateDao {
    @Upsert suspend fun upsert(state: ListSyncStateEntity)

    @Query("SELECT * FROM list_sync_state WHERE listType = :listType")
    suspend fun get(listType: ListType): ListSyncStateEntity?

    @Query("SELECT * FROM list_sync_state WHERE listType = :listType")
    fun observe(listType: ListType): Flow<ListSyncStateEntity?>
}

@Dao
interface RegistrationDateCacheDao {
    @Upsert suspend fun upsert(row: RegistrationDateCacheEntity)

    @Query("SELECT * FROM registration_date_cache WHERE nick = :nick")
    suspend fun get(nick: String): RegistrationDateCacheEntity?

    /** Only rows still inside the TTL are usable. */
    @Query("SELECT * FROM registration_date_cache WHERE nick = :nick AND fetchedAt >= :minFetchedAt")
    suspend fun getFresh(nick: String, minFetchedAt: Long): RegistrationDateCacheEntity?

    @Query("DELETE FROM registration_date_cache WHERE fetchedAt < :minFetchedAt")
    suspend fun trimExpired(minFetchedAt: Long): Int

    @Query("SELECT COUNT(*) FROM registration_date_cache")
    suspend fun size(): Int
}

@Dao
interface QueuedTaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(task: QueuedTaskEntity): Long

    @Query("SELECT * FROM queued_task ORDER BY seq ASC")
    fun observeAll(): Flow<List<QueuedTaskEntity>>

    @Query("DELETE FROM queued_task WHERE id = :id")
    suspend fun remove(id: Long)
}

@Dao
interface OperationCheckpointDao {
    @Upsert suspend fun upsert(cp: OperationCheckpointEntity)

    @Query("SELECT * FROM operation_checkpoint WHERE operationId = :id")
    suspend fun get(id: String): OperationCheckpointEntity?

    @Query("SELECT * FROM operation_checkpoint WHERE state = :state")
    suspend fun withState(state: String): List<OperationCheckpointEntity>

    @Query("DELETE FROM operation_checkpoint WHERE operationId = :id")
    suspend fun remove(id: String)
}

@Dao
interface CompletedOperationDao {
    @Insert suspend fun insert(row: CompletedOperationEntity): Long

    @Query("SELECT * FROM completed_operation ORDER BY finishedAt DESC LIMIT :limit")
    fun recent(limit: Int = 1000): Flow<List<CompletedOperationEntity>>

    @Query(
        "DELETE FROM completed_operation WHERE id NOT IN " +
            "(SELECT id FROM completed_operation ORDER BY finishedAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int = 1000): Int
}

@Dao
interface AuthorListDao {
    @Upsert suspend fun upsertAll(rows: List<AuthorListEntity>)

    @Query("SELECT * FROM author_list ORDER BY addedAt")
    fun observe(): Flow<List<AuthorListEntity>>

    @Query("DELETE FROM author_list")
    suspend fun clear()
}

@Dao
interface TelemetryOutboxDao {
    @Insert suspend fun add(row: TelemetryOutboxEntity): Long

    @Query("SELECT * FROM telemetry_outbox WHERE nextAttemptAt <= :now ORDER BY id LIMIT :limit")
    suspend fun due(now: Long, limit: Int = 20): List<TelemetryOutboxEntity>

    @Query("DELETE FROM telemetry_outbox WHERE id = :id")
    suspend fun remove(id: Long)
}
