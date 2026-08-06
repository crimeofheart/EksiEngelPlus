package org.duzgun.eksiengelplus.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.duzgun.eksiengelplus.model.ListType

/**
 * One row per user per list.
 *
 * Replaces storageHandler.js's mutedUserList/blockedUserList/followedUserList AND
 * their *Count keys. The count is a COUNT(*) query and is never stored: the
 * extension keeps both and they can disagree, which this makes unrepresentable.
 *
 * The composite key also makes re-scraping idempotent -- no dedup pass needed.
 */
@Entity(
    tableName = "relation_user",
    primaryKeys = ["listType", "userId"],
    indices = [Index("nick"), Index("listType")],
)
data class RelationUserEntity(
    val listType: ListType,
    val userId: Long,
    val nick: String,
    val addedAt: Long,
    val lastSeenAt: Long,
    /** Denormalised so date filters are a query rather than N profile fetches. */
    val registrationDate: Long? = null,
    /** /follower and /following only. */
    val isFollowCurrentUser: Boolean? = null,
    val isBuddy: Boolean? = null,
)

/**
 * Cursor-based resumption, replacing partial{Muted,Blocked,Followed}Users and
 * their timestamps. Those exist purely because chrome.storage.local caps at 5 MB
 * and the extension had to chunk lists it could not hold; SQLite has no such
 * limit, so only the page cursor needs persisting.
 */
@Entity(tableName = "list_sync_state")
data class ListSyncStateEntity(
    @PrimaryKey val listType: ListType,
    val cursorPage: Int,
    val isPartial: Boolean,
    val lastFullRefreshAt: Long?,
    val updatedAt: Long,
)

/**
 * Resolving a registration date costs one profile fetch per uncached user, which
 * makes it the most expensive path in a date-filtered bulk run. 30-day TTL,
 * matching the extension.
 */
@Entity(tableName = "registration_date_cache")
data class RegistrationDateCacheEntity(
    @PrimaryKey val nick: String,
    val authorId: Long?,
    /** Epoch day. Null means "looked up and genuinely unknown", which is still worth caching. */
    val registrationEpochDay: Long?,
    val fetchedAt: Long,
) {
    companion object {
        const val TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

/** Serial task queue, replacing the queueData storage key. */
@Entity(tableName = "queued_task")
data class QueuedTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seq: Long,
    val banSourcePk: Int,
    val banModePk: Int,
    val targetTypePk: Int?,
    val clickSourcePk: Int?,
    val payloadJson: String,
    val status: String,
    val enqueuedAt: Long,
)

/**
 * Replaces the resumableOp_<id> keys. Written in the same transaction as the
 * list rows it describes -- cursor and content must never be persisted
 * separately, since that is the one place a crash corrupts user-visible state.
 */
@Entity(tableName = "operation_checkpoint")
data class OperationCheckpointEntity(
    @PrimaryKey val operationId: String,
    val type: String,
    val state: String,
    val cursorJson: String,
    val processed: Int,
    val total: Int,
    val successful: Int,
    val failed: Int,
    val startedAt: Long,
    val updatedAt: Long,
    val workRequestId: String?,
    /** Foreground-service milliseconds consumed, for the Android 15 6h/24h budget. */
    val fgsMillisUsed: Long = 0,
    /**
     * The serialised OperationRequest, so a paused run can be resumed without the
     * caller reconstructing it.
     *
     * Necessary because WorkManager's WorkInfo does not expose input data, so once
     * the worker returns there is nowhere else the request survives. A PAUSED_AUTH
     * operation is resumed by a screen that never saw the original request --
     * typically hours later, after a login.
     */
    val requestJson: String? = null,
)

/** History. The extension caps this at 100 rows; SQLite affords far more. */
@Entity(tableName = "completed_operation")
data class CompletedOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val banSourcePk: Int,
    val banModePk: Int,
    val processed: Int,
    val successful: Int,
    val failed: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val summaryJson: String,
)

/** The pasted author list, replacing the userList storage key. */
@Entity(tableName = "author_list", indices = [Index(value = ["nick"], unique = true)])
data class AuthorListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nick: String,
    val authorId: Long?,
    val addedAt: Long,
)

/** Telemetry that failed to send. New -- the extension drops it on failure. */
@Entity(tableName = "telemetry_outbox")
data class TelemetryOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val endpoint: String,
    val bodyJson: String,
    val attempts: Int,
    val nextAttemptAt: Long,
)
