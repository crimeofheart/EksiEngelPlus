package org.duzgun.eksiengelplus.database

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class QueuedTaskDao_Impl(
  __db: RoomDatabase,
) : QueuedTaskDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfQueuedTaskEntity: EntityInsertAdapter<QueuedTaskEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfQueuedTaskEntity = object : EntityInsertAdapter<QueuedTaskEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `queued_task` (`id`,`seq`,`banSourcePk`,`banModePk`,`targetTypePk`,`clickSourcePk`,`payloadJson`,`status`,`enqueuedAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: QueuedTaskEntity) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.seq)
        statement.bindLong(3, entity.banSourcePk.toLong())
        statement.bindLong(4, entity.banModePk.toLong())
        val _tmpTargetTypePk: Int? = entity.targetTypePk
        if (_tmpTargetTypePk == null) {
          statement.bindNull(5)
        } else {
          statement.bindLong(5, _tmpTargetTypePk.toLong())
        }
        val _tmpClickSourcePk: Int? = entity.clickSourcePk
        if (_tmpClickSourcePk == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpClickSourcePk.toLong())
        }
        statement.bindText(7, entity.payloadJson)
        statement.bindText(8, entity.status)
        statement.bindLong(9, entity.enqueuedAt)
      }
    }
  }

  public override suspend fun enqueue(task: QueuedTaskEntity): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfQueuedTaskEntity.insertAndReturnId(_connection, task)
    _result
  }

  public override fun observeAll(): Flow<List<QueuedTaskEntity>> {
    val _sql: String = "SELECT * FROM queued_task ORDER BY seq ASC"
    return createFlow(__db, false, arrayOf("queued_task")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfSeq: Int = getColumnIndexOrThrow(_stmt, "seq")
        val _columnIndexOfBanSourcePk: Int = getColumnIndexOrThrow(_stmt, "banSourcePk")
        val _columnIndexOfBanModePk: Int = getColumnIndexOrThrow(_stmt, "banModePk")
        val _columnIndexOfTargetTypePk: Int = getColumnIndexOrThrow(_stmt, "targetTypePk")
        val _columnIndexOfClickSourcePk: Int = getColumnIndexOrThrow(_stmt, "clickSourcePk")
        val _columnIndexOfPayloadJson: Int = getColumnIndexOrThrow(_stmt, "payloadJson")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfEnqueuedAt: Int = getColumnIndexOrThrow(_stmt, "enqueuedAt")
        val _result: MutableList<QueuedTaskEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: QueuedTaskEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpSeq: Long
          _tmpSeq = _stmt.getLong(_columnIndexOfSeq)
          val _tmpBanSourcePk: Int
          _tmpBanSourcePk = _stmt.getLong(_columnIndexOfBanSourcePk).toInt()
          val _tmpBanModePk: Int
          _tmpBanModePk = _stmt.getLong(_columnIndexOfBanModePk).toInt()
          val _tmpTargetTypePk: Int?
          if (_stmt.isNull(_columnIndexOfTargetTypePk)) {
            _tmpTargetTypePk = null
          } else {
            _tmpTargetTypePk = _stmt.getLong(_columnIndexOfTargetTypePk).toInt()
          }
          val _tmpClickSourcePk: Int?
          if (_stmt.isNull(_columnIndexOfClickSourcePk)) {
            _tmpClickSourcePk = null
          } else {
            _tmpClickSourcePk = _stmt.getLong(_columnIndexOfClickSourcePk).toInt()
          }
          val _tmpPayloadJson: String
          _tmpPayloadJson = _stmt.getText(_columnIndexOfPayloadJson)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpEnqueuedAt: Long
          _tmpEnqueuedAt = _stmt.getLong(_columnIndexOfEnqueuedAt)
          _item = QueuedTaskEntity(_tmpId,_tmpSeq,_tmpBanSourcePk,_tmpBanModePk,_tmpTargetTypePk,_tmpClickSourcePk,_tmpPayloadJson,_tmpStatus,_tmpEnqueuedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun remove(id: Long) {
    val _sql: String = "DELETE FROM queued_task WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
