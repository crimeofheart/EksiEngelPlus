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

    /**
     * Records that a user is currently on a list, without disturbing what is already
     * known about them.
     *
     * A plain @Upsert would be wrong here: the relation endpoints carry no
     * registration date, so upserting a whole row would null out a date learned from
     * a CSV import or a profile fetch, and the next date-filtered run would refetch
     * every profile. `addedAt` is likewise left alone -- the user joined the list
     * when they joined it, not when we last looked.
     */
    @Query(
        "INSERT INTO relation_user " +
            "(listType, userId, nick, addedAt, lastSeenAt, registrationDate, isFollowCurrentUser, isBuddy) " +
            "VALUES (:listType, :userId, :nick, :seenAt, :seenAt, NULL, :isFollowCurrentUser, :isBuddy) " +
            "ON CONFLICT(listType, userId) DO UPDATE SET " +
            "nick = excluded.nick, " +
            "lastSeenAt = excluded.lastSeenAt, " +
            "isFollowCurrentUser = excluded.isFollowCurrentUser, " +
            "isBuddy = excluded.isBuddy",
    )
    suspend fun markSeen(
        listType: ListType,
        userId: Long,
        nick: String,
        seenAt: Long,
        isFollowCurrentUser: Boolean?,
        isBuddy: Boolean?,
    )

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

    /**
     * Removes users who have left the list, identified by a [lastSeenAt] older than
     * the sync that just completed.
     *
     * This is why a sync upserts forward instead of calling [clear] first: an
     * interrupted sync must not be able to leave the user with fewer rows than they
     * started with. Only a sync that reached its terminator may prune.
     */
    @Query("DELETE FROM relation_user WHERE listType = :listType AND lastSeenAt < :seenBefore")
    suspend fun pruneStale(listType: ListType, seenBefore: Long): Int

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

    /** `nick` is the primary key here, so a plain upsert is exactly right. */
    @Upsert suspend fun upsertAll(rows: List<RegistrationDateCacheEntity>)

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

    /** Observed so a screen can gate an action on "an operation is running" without polling. */
    @Query("SELECT COUNT(*) FROM operation_checkpoint WHERE state = :state")
    fun countWithState(state: String): Flow<Int>

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

    /**
     * The one-shot read a LIST operation takes at start. Not a Flow: the operation
     * checkpoints by index, so a target set that shifted under a resumed run would
     * resume at the wrong place.
     */
    @Query("SELECT * FROM author_list ORDER BY addedAt")
    suspend fun getAll(): List<AuthorListEntity>

    @Query("DELETE FROM author_list")
    suspend fun clear()

    /**
     * IGNORE rather than @Upsert: `nick` is uniquely indexed but `id` is the primary
     * key, so an upsert of a row with id 0 would conflict on the index and then look
     * for a primary key that does not exist. A duplicate nick is simply not a new
     * author.
     *
     * `addedAt` is supplied by the caller, which sequences a bulk import so the
     * insertion order the LIST operation depends on cannot collapse when a hundred
     * rows land in the same millisecond.
     */
    @Query("INSERT OR IGNORE INTO author_list (nick, authorId, addedAt) VALUES (:nick, :authorId, :addedAt)")
    suspend fun insertIgnoring(nick: String, authorId: Long?, addedAt: Long)

    @Query("SELECT COUNT(*) FROM author_list")
    fun count(): Flow<Int>
}

@Dao
interface TelemetryOutboxDao {
    @Insert suspend fun add(row: TelemetryOutboxEntity): Long

    @Query("SELECT * FROM telemetry_outbox WHERE nextAttemptAt <= :now ORDER BY id LIMIT :limit")
    suspend fun due(now: Long, limit: Int = 20): List<TelemetryOutboxEntity>

    @Query("DELETE FROM telemetry_outbox WHERE id = :id")
    suspend fun remove(id: Long)
}
