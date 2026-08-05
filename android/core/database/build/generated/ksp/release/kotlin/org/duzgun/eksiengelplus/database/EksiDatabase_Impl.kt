package org.duzgun.eksiengelplus.database

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class EksiDatabase_Impl : EksiDatabase() {
  private val _relationUserDao: Lazy<RelationUserDao> = lazy {
    RelationUserDao_Impl(this)
  }

  private val _listSyncStateDao: Lazy<ListSyncStateDao> = lazy {
    ListSyncStateDao_Impl(this)
  }

  private val _registrationDateCacheDao: Lazy<RegistrationDateCacheDao> = lazy {
    RegistrationDateCacheDao_Impl(this)
  }

  private val _queuedTaskDao: Lazy<QueuedTaskDao> = lazy {
    QueuedTaskDao_Impl(this)
  }

  private val _operationCheckpointDao: Lazy<OperationCheckpointDao> = lazy {
    OperationCheckpointDao_Impl(this)
  }

  private val _completedOperationDao: Lazy<CompletedOperationDao> = lazy {
    CompletedOperationDao_Impl(this)
  }

  private val _authorListDao: Lazy<AuthorListDao> = lazy {
    AuthorListDao_Impl(this)
  }

  private val _telemetryOutboxDao: Lazy<TelemetryOutboxDao> = lazy {
    TelemetryOutboxDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(1, "a05a2d64d05d4d88e5a2a1707e499e29", "252bd120c47cb4c48d140075a54ae226") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `relation_user` (`listType` TEXT NOT NULL, `userId` INTEGER NOT NULL, `nick` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, `registrationDate` INTEGER, `isFollowCurrentUser` INTEGER, `isBuddy` INTEGER, PRIMARY KEY(`listType`, `userId`))")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_relation_user_nick` ON `relation_user` (`nick`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_relation_user_listType` ON `relation_user` (`listType`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `list_sync_state` (`listType` TEXT NOT NULL, `cursorPage` INTEGER NOT NULL, `isPartial` INTEGER NOT NULL, `lastFullRefreshAt` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`listType`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `registration_date_cache` (`nick` TEXT NOT NULL, `authorId` INTEGER, `registrationEpochDay` INTEGER, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`nick`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `queued_task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `seq` INTEGER NOT NULL, `banSourcePk` INTEGER NOT NULL, `banModePk` INTEGER NOT NULL, `targetTypePk` INTEGER, `clickSourcePk` INTEGER, `payloadJson` TEXT NOT NULL, `status` TEXT NOT NULL, `enqueuedAt` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `operation_checkpoint` (`operationId` TEXT NOT NULL, `type` TEXT NOT NULL, `state` TEXT NOT NULL, `cursorJson` TEXT NOT NULL, `processed` INTEGER NOT NULL, `total` INTEGER NOT NULL, `successful` INTEGER NOT NULL, `failed` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `workRequestId` TEXT, `fgsMillisUsed` INTEGER NOT NULL, PRIMARY KEY(`operationId`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `completed_operation` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `banSourcePk` INTEGER NOT NULL, `banModePk` INTEGER NOT NULL, `processed` INTEGER NOT NULL, `successful` INTEGER NOT NULL, `failed` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `summaryJson` TEXT NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `author_list` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `nick` TEXT NOT NULL, `authorId` INTEGER, `addedAt` INTEGER NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_author_list_nick` ON `author_list` (`nick`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `telemetry_outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `endpoint` TEXT NOT NULL, `bodyJson` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'a05a2d64d05d4d88e5a2a1707e499e29')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `relation_user`")
        connection.execSQL("DROP TABLE IF EXISTS `list_sync_state`")
        connection.execSQL("DROP TABLE IF EXISTS `registration_date_cache`")
        connection.execSQL("DROP TABLE IF EXISTS `queued_task`")
        connection.execSQL("DROP TABLE IF EXISTS `operation_checkpoint`")
        connection.execSQL("DROP TABLE IF EXISTS `completed_operation`")
        connection.execSQL("DROP TABLE IF EXISTS `author_list`")
        connection.execSQL("DROP TABLE IF EXISTS `telemetry_outbox`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
        val _columnsRelationUser: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsRelationUser.put("listType", TableInfo.Column("listType", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRelationUser.put("userId", TableInfo.Column("userId", "INTEGER", true, 2, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRelationUser.put("nick", TableInfo.Column("nick", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRelationUser.put("addedAt", TableInfo.Column("addedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRelationUser.put("lastSeenAt", TableInfo.Column("lastSeenAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRelationUser.put("registrationDate", TableInfo.Column("registrationDate", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRelationUser.put("isFollowCurrentUser", TableInfo.Column("isFollowCurrentUser", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRelationUser.put("isBuddy", TableInfo.Column("isBuddy", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysRelationUser: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesRelationUser: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesRelationUser.add(TableInfo.Index("index_relation_user_nick", false, listOf("nick"), listOf("ASC")))
        _indicesRelationUser.add(TableInfo.Index("index_relation_user_listType", false, listOf("listType"), listOf("ASC")))
        val _infoRelationUser: TableInfo = TableInfo("relation_user", _columnsRelationUser, _foreignKeysRelationUser, _indicesRelationUser)
        val _existingRelationUser: TableInfo = read(connection, "relation_user")
        if (!_infoRelationUser.equals(_existingRelationUser)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |relation_user(org.duzgun.eksiengelplus.database.RelationUserEntity).
              | Expected:
              |""".trimMargin() + _infoRelationUser + """
              |
              | Found:
              |""".trimMargin() + _existingRelationUser)
        }
        val _columnsListSyncState: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsListSyncState.put("listType", TableInfo.Column("listType", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsListSyncState.put("cursorPage", TableInfo.Column("cursorPage", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsListSyncState.put("isPartial", TableInfo.Column("isPartial", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsListSyncState.put("lastFullRefreshAt", TableInfo.Column("lastFullRefreshAt", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsListSyncState.put("updatedAt", TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysListSyncState: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesListSyncState: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoListSyncState: TableInfo = TableInfo("list_sync_state", _columnsListSyncState, _foreignKeysListSyncState, _indicesListSyncState)
        val _existingListSyncState: TableInfo = read(connection, "list_sync_state")
        if (!_infoListSyncState.equals(_existingListSyncState)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |list_sync_state(org.duzgun.eksiengelplus.database.ListSyncStateEntity).
              | Expected:
              |""".trimMargin() + _infoListSyncState + """
              |
              | Found:
              |""".trimMargin() + _existingListSyncState)
        }
        val _columnsRegistrationDateCache: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsRegistrationDateCache.put("nick", TableInfo.Column("nick", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRegistrationDateCache.put("authorId", TableInfo.Column("authorId", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRegistrationDateCache.put("registrationEpochDay", TableInfo.Column("registrationEpochDay", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRegistrationDateCache.put("fetchedAt", TableInfo.Column("fetchedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysRegistrationDateCache: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesRegistrationDateCache: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoRegistrationDateCache: TableInfo = TableInfo("registration_date_cache", _columnsRegistrationDateCache, _foreignKeysRegistrationDateCache, _indicesRegistrationDateCache)
        val _existingRegistrationDateCache: TableInfo = read(connection, "registration_date_cache")
        if (!_infoRegistrationDateCache.equals(_existingRegistrationDateCache)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |registration_date_cache(org.duzgun.eksiengelplus.database.RegistrationDateCacheEntity).
              | Expected:
              |""".trimMargin() + _infoRegistrationDateCache + """
              |
              | Found:
              |""".trimMargin() + _existingRegistrationDateCache)
        }
        val _columnsQueuedTask: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsQueuedTask.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsQueuedTask.put("seq", TableInfo.Column("seq", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsQueuedTask.put("banSourcePk", TableInfo.Column("banSourcePk", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsQueuedTask.put("banModePk", TableInfo.Column("banModePk", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsQueuedTask.put("targetTypePk", TableInfo.Column("targetTypePk", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsQueuedTask.put("clickSourcePk", TableInfo.Column("clickSourcePk", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsQueuedTask.put("payloadJson", TableInfo.Column("payloadJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsQueuedTask.put("status", TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsQueuedTask.put("enqueuedAt", TableInfo.Column("enqueuedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysQueuedTask: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesQueuedTask: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoQueuedTask: TableInfo = TableInfo("queued_task", _columnsQueuedTask, _foreignKeysQueuedTask, _indicesQueuedTask)
        val _existingQueuedTask: TableInfo = read(connection, "queued_task")
        if (!_infoQueuedTask.equals(_existingQueuedTask)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |queued_task(org.duzgun.eksiengelplus.database.QueuedTaskEntity).
              | Expected:
              |""".trimMargin() + _infoQueuedTask + """
              |
              | Found:
              |""".trimMargin() + _existingQueuedTask)
        }
        val _columnsOperationCheckpoint: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsOperationCheckpoint.put("operationId", TableInfo.Column("operationId", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("type", TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("state", TableInfo.Column("state", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("cursorJson", TableInfo.Column("cursorJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("processed", TableInfo.Column("processed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("total", TableInfo.Column("total", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("successful", TableInfo.Column("successful", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("failed", TableInfo.Column("failed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("startedAt", TableInfo.Column("startedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("updatedAt", TableInfo.Column("updatedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("workRequestId", TableInfo.Column("workRequestId", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsOperationCheckpoint.put("fgsMillisUsed", TableInfo.Column("fgsMillisUsed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysOperationCheckpoint: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesOperationCheckpoint: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoOperationCheckpoint: TableInfo = TableInfo("operation_checkpoint", _columnsOperationCheckpoint, _foreignKeysOperationCheckpoint, _indicesOperationCheckpoint)
        val _existingOperationCheckpoint: TableInfo = read(connection, "operation_checkpoint")
        if (!_infoOperationCheckpoint.equals(_existingOperationCheckpoint)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |operation_checkpoint(org.duzgun.eksiengelplus.database.OperationCheckpointEntity).
              | Expected:
              |""".trimMargin() + _infoOperationCheckpoint + """
              |
              | Found:
              |""".trimMargin() + _existingOperationCheckpoint)
        }
        val _columnsCompletedOperation: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsCompletedOperation.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCompletedOperation.put("banSourcePk", TableInfo.Column("banSourcePk", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCompletedOperation.put("banModePk", TableInfo.Column("banModePk", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCompletedOperation.put("processed", TableInfo.Column("processed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCompletedOperation.put("successful", TableInfo.Column("successful", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCompletedOperation.put("failed", TableInfo.Column("failed", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCompletedOperation.put("startedAt", TableInfo.Column("startedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCompletedOperation.put("finishedAt", TableInfo.Column("finishedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsCompletedOperation.put("summaryJson", TableInfo.Column("summaryJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysCompletedOperation: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesCompletedOperation: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoCompletedOperation: TableInfo = TableInfo("completed_operation", _columnsCompletedOperation, _foreignKeysCompletedOperation, _indicesCompletedOperation)
        val _existingCompletedOperation: TableInfo = read(connection, "completed_operation")
        if (!_infoCompletedOperation.equals(_existingCompletedOperation)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |completed_operation(org.duzgun.eksiengelplus.database.CompletedOperationEntity).
              | Expected:
              |""".trimMargin() + _infoCompletedOperation + """
              |
              | Found:
              |""".trimMargin() + _existingCompletedOperation)
        }
        val _columnsAuthorList: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAuthorList.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAuthorList.put("nick", TableInfo.Column("nick", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAuthorList.put("authorId", TableInfo.Column("authorId", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAuthorList.put("addedAt", TableInfo.Column("addedAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAuthorList: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesAuthorList: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesAuthorList.add(TableInfo.Index("index_author_list_nick", true, listOf("nick"), listOf("ASC")))
        val _infoAuthorList: TableInfo = TableInfo("author_list", _columnsAuthorList, _foreignKeysAuthorList, _indicesAuthorList)
        val _existingAuthorList: TableInfo = read(connection, "author_list")
        if (!_infoAuthorList.equals(_existingAuthorList)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |author_list(org.duzgun.eksiengelplus.database.AuthorListEntity).
              | Expected:
              |""".trimMargin() + _infoAuthorList + """
              |
              | Found:
              |""".trimMargin() + _existingAuthorList)
        }
        val _columnsTelemetryOutbox: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsTelemetryOutbox.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTelemetryOutbox.put("endpoint", TableInfo.Column("endpoint", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTelemetryOutbox.put("bodyJson", TableInfo.Column("bodyJson", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTelemetryOutbox.put("attempts", TableInfo.Column("attempts", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTelemetryOutbox.put("nextAttemptAt", TableInfo.Column("nextAttemptAt", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTelemetryOutbox: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesTelemetryOutbox: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoTelemetryOutbox: TableInfo = TableInfo("telemetry_outbox", _columnsTelemetryOutbox, _foreignKeysTelemetryOutbox, _indicesTelemetryOutbox)
        val _existingTelemetryOutbox: TableInfo = read(connection, "telemetry_outbox")
        if (!_infoTelemetryOutbox.equals(_existingTelemetryOutbox)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |telemetry_outbox(org.duzgun.eksiengelplus.database.TelemetryOutboxEntity).
              | Expected:
              |""".trimMargin() + _infoTelemetryOutbox + """
              |
              | Found:
              |""".trimMargin() + _existingTelemetryOutbox)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "relation_user", "list_sync_state", "registration_date_cache", "queued_task", "operation_checkpoint", "completed_operation", "author_list", "telemetry_outbox")
  }

  public override fun clearAllTables() {
    super.performClear(false, "relation_user", "list_sync_state", "registration_date_cache", "queued_task", "operation_checkpoint", "completed_operation", "author_list", "telemetry_outbox")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(RelationUserDao::class, RelationUserDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(ListSyncStateDao::class, ListSyncStateDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(RegistrationDateCacheDao::class, RegistrationDateCacheDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(QueuedTaskDao::class, QueuedTaskDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(OperationCheckpointDao::class, OperationCheckpointDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(CompletedOperationDao::class, CompletedOperationDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AuthorListDao::class, AuthorListDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(TelemetryOutboxDao::class, TelemetryOutboxDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>): List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun relationUsers(): RelationUserDao = _relationUserDao.value

  public override fun listSyncState(): ListSyncStateDao = _listSyncStateDao.value

  public override fun registrationDates(): RegistrationDateCacheDao = _registrationDateCacheDao.value

  public override fun queuedTasks(): QueuedTaskDao = _queuedTaskDao.value

  public override fun checkpoints(): OperationCheckpointDao = _operationCheckpointDao.value

  public override fun completedOperations(): CompletedOperationDao = _completedOperationDao.value

  public override fun authorList(): AuthorListDao = _authorListDao.value

  public override fun telemetryOutbox(): TelemetryOutboxDao = _telemetryOutboxDao.value
}
