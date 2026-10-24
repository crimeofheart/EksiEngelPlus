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
    /**
     * The id the run will have once it starts, and the history ledger's task id
     * from the moment it is queued. Null on rows queued before the ledger, which
     * get a fresh one when they are adopted.
     */
    val taskId: String? = null,
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
    /**
     * The request that produced this run, so the history screen can queue it
     * again or open what it acted on.
     *
     * Nullable because every row written before this column existed has no
     * request to recover -- the checkpoint carrying it was deleted when the run
     * was archived. Those rows offer neither action rather than guessing.
     */
    val requestJson: String? = null,
    /** The run's history task id: the checkpoint's operationId. Null before the ledger. */
    val taskId: String? = null,
    /** Ended by Durdur rather than by running out of targets. */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val stopped: Boolean = false,
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

/**
 * One settled outcome, waiting to reach the history ledger.
 *
 * Written in the same transaction as the checkpoint that records the target as
 * processed, so a crash leaves both or neither. Deleted only once the server has
 * acknowledged the event id -- never abandoned after N attempts the way
 * telemetry_outbox is, because history is meant to be complete.
 */
@Entity(tableName = "history_outbox", indices = [Index(value = ["eventUuid"], unique = true)])
data class HistoryOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventUuid: String,
    val taskId: String?,
    /** Wire names: BLOCK, TITLE_BLOCK, MUTE, FOLLOW / ADD, REMOVE / OK, FAIL, SKIPPED. */
    val kind: String,
    val direction: String,
    val result: String,
    val actorId: Long,
    val actorNick: String?,
    val targetId: Long,
    val targetNick: String?,
    val occurredAt: Long,
)

/**
 * What the server was last told about a task this device owns.
 *
 * The state itself is derived from queued_task, operation_checkpoint and
 * completed_operation each time history syncs, so no cancel path in the app has
 * to remember to report; this row is what makes the derivation incremental.
 */
@Entity(tableName = "history_task")
data class HistoryTaskEntity(
    @PrimaryKey val taskId: String,
    val epoch: Int,
    /** QUEUED, RUNNING, DONE, FAILED, CANCELLED, STOPPED; or LOST once another device owns it. */
    val status: String,
    val banSourcePk: Int,
    val banModePk: Int,
    val description: String,
    val requestJson: String?,
    val actorId: Long?,
    val planned: Int,
    val performed: Int,
    val successful: Int,
    val createdAt: Long,
    /** Another device asked for a cancel; the run's end is then reported as CANCELLED. */
    val cancelRequested: Boolean = false,
    /** Whether the server has the current state. */
    val pushed: Boolean = false,
)

/** A task another device holds, as the last pull described it. */
@Entity(tableName = "remote_task")
data class RemoteTaskEntity(
    @PrimaryKey val taskId: String,
    val description: String,
    val status: String,
    val ownerLabel: String,
    val ownerClient: String?,
    val performed: Int,
    val planned: Int,
    val cancelRequested: Boolean,
    /** The task's OperationRequest when an Android device created it, so it can run here. */
    val requestJson: String?,
    val updatedAt: Long,
)

/** Where history sync stands. One row, id 0. */
@Entity(tableName = "history_sync_state")
data class HistorySyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val cursor: Long = 0,
    val failures: Int = 0,
    val backoffUntil: Long = 0,
    val lastSyncAt: Long = 0,
    /** Events the outbox could not keep. Room has no cap today, so this stays 0. */
    val droppedEvents: Long = 0,
)
