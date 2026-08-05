package org.duzgun.eksiengelplus.database

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.EntityUpsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import org.duzgun.eksiengelplus.model.ListType

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class ListSyncStateDao_Impl(
  __db: RoomDatabase,
) : ListSyncStateDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfListSyncStateEntity: EntityUpsertAdapter<ListSyncStateEntity>

  private val __converters: Converters = Converters()
  init {
    this.__db = __db
    this.__upsertAdapterOfListSyncStateEntity = EntityUpsertAdapter<ListSyncStateEntity>(object : EntityInsertAdapter<ListSyncStateEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `list_sync_state` (`listType`,`cursorPage`,`isPartial`,`lastFullRefreshAt`,`updatedAt`) VALUES (?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ListSyncStateEntity) {
        val _tmp: String = __converters.listTypeToString(entity.listType)
        statement.bindText(1, _tmp)
        statement.bindLong(2, entity.cursorPage.toLong())
        val _tmp_1: Int = if (entity.isPartial) 1 else 0
        statement.bindLong(3, _tmp_1.toLong())
        val _tmpLastFullRefreshAt: Long? = entity.lastFullRefreshAt
        if (_tmpLastFullRefreshAt == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpLastFullRefreshAt)
        }
        statement.bindLong(5, entity.updatedAt)
      }
    }, object : EntityDeleteOrUpdateAdapter<ListSyncStateEntity>() {
      protected override fun createQuery(): String = "UPDATE `list_sync_state` SET `listType` = ?,`cursorPage` = ?,`isPartial` = ?,`lastFullRefreshAt` = ?,`updatedAt` = ? WHERE `listType` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: ListSyncStateEntity) {
        val _tmp: String = __converters.listTypeToString(entity.listType)
        statement.bindText(1, _tmp)
        statement.bindLong(2, entity.cursorPage.toLong())
        val _tmp_1: Int = if (entity.isPartial) 1 else 0
        statement.bindLong(3, _tmp_1.toLong())
        val _tmpLastFullRefreshAt: Long? = entity.lastFullRefreshAt
        if (_tmpLastFullRefreshAt == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpLastFullRefreshAt)
        }
        statement.bindLong(5, entity.updatedAt)
        val _tmp_2: String = __converters.listTypeToString(entity.listType)
        statement.bindText(6, _tmp_2)
      }
    })
  }

  public override suspend fun upsert(state: ListSyncStateEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfListSyncStateEntity.upsert(_connection, state)
  }

  public override suspend fun `get`(listType: ListType): ListSyncStateEntity? {
    val _sql: String = "SELECT * FROM list_sync_state WHERE listType = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
        val _columnIndexOfListType: Int = getColumnIndexOrThrow(_stmt, "listType")
        val _columnIndexOfCursorPage: Int = getColumnIndexOrThrow(_stmt, "cursorPage")
        val _columnIndexOfIsPartial: Int = getColumnIndexOrThrow(_stmt, "isPartial")
        val _columnIndexOfLastFullRefreshAt: Int = getColumnIndexOrThrow(_stmt, "lastFullRefreshAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: ListSyncStateEntity?
        if (_stmt.step()) {
          val _tmpListType: ListType
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfListType)
          _tmpListType = __converters.stringToListType(_tmp_1)
          val _tmpCursorPage: Int
          _tmpCursorPage = _stmt.getLong(_columnIndexOfCursorPage).toInt()
          val _tmpIsPartial: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfIsPartial).toInt()
          _tmpIsPartial = _tmp_2 != 0
          val _tmpLastFullRefreshAt: Long?
          if (_stmt.isNull(_columnIndexOfLastFullRefreshAt)) {
            _tmpLastFullRefreshAt = null
          } else {
            _tmpLastFullRefreshAt = _stmt.getLong(_columnIndexOfLastFullRefreshAt)
          }
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = ListSyncStateEntity(_tmpListType,_tmpCursorPage,_tmpIsPartial,_tmpLastFullRefreshAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observe(listType: ListType): Flow<ListSyncStateEntity?> {
    val _sql: String = "SELECT * FROM list_sync_state WHERE listType = ?"
    return createFlow(__db, false, arrayOf("list_sync_state")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
        val _columnIndexOfListType: Int = getColumnIndexOrThrow(_stmt, "listType")
        val _columnIndexOfCursorPage: Int = getColumnIndexOrThrow(_stmt, "cursorPage")
        val _columnIndexOfIsPartial: Int = getColumnIndexOrThrow(_stmt, "isPartial")
        val _columnIndexOfLastFullRefreshAt: Int = getColumnIndexOrThrow(_stmt, "lastFullRefreshAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _result: ListSyncStateEntity?
        if (_stmt.step()) {
          val _tmpListType: ListType
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfListType)
          _tmpListType = __converters.stringToListType(_tmp_1)
          val _tmpCursorPage: Int
          _tmpCursorPage = _stmt.getLong(_columnIndexOfCursorPage).toInt()
          val _tmpIsPartial: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfIsPartial).toInt()
          _tmpIsPartial = _tmp_2 != 0
          val _tmpLastFullRefreshAt: Long?
          if (_stmt.isNull(_columnIndexOfLastFullRefreshAt)) {
            _tmpLastFullRefreshAt = null
          } else {
            _tmpLastFullRefreshAt = _stmt.getLong(_columnIndexOfLastFullRefreshAt)
          }
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = ListSyncStateEntity(_tmpListType,_tmpCursorPage,_tmpIsPartial,_tmpLastFullRefreshAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
