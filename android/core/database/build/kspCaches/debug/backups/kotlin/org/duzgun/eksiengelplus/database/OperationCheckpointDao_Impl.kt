package org.duzgun.eksiengelplus.database

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.EntityUpsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class OperationCheckpointDao_Impl(
  __db: RoomDatabase,
) : OperationCheckpointDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfOperationCheckpointEntity:
      EntityUpsertAdapter<OperationCheckpointEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfOperationCheckpointEntity = EntityUpsertAdapter<OperationCheckpointEntity>(object : EntityInsertAdapter<OperationCheckpointEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `operation_checkpoint` (`operationId`,`type`,`state`,`cursorJson`,`processed`,`total`,`successful`,`failed`,`startedAt`,`updatedAt`,`workRequestId`,`fgsMillisUsed`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: OperationCheckpointEntity) {
        statement.bindText(1, entity.operationId)
        statement.bindText(2, entity.type)
        statement.bindText(3, entity.state)
        statement.bindText(4, entity.cursorJson)
        statement.bindLong(5, entity.processed.toLong())
        statement.bindLong(6, entity.total.toLong())
        statement.bindLong(7, entity.successful.toLong())
        statement.bindLong(8, entity.failed.toLong())
        statement.bindLong(9, entity.startedAt)
        statement.bindLong(10, entity.updatedAt)
        val _tmpWorkRequestId: String? = entity.workRequestId
        if (_tmpWorkRequestId == null) {
          statement.bindNull(11)
        } else {
          statement.bindText(11, _tmpWorkRequestId)
        }
        statement.bindLong(12, entity.fgsMillisUsed)
      }
    }, object : EntityDeleteOrUpdateAdapter<OperationCheckpointEntity>() {
      protected override fun createQuery(): String = "UPDATE `operation_checkpoint` SET `operationId` = ?,`type` = ?,`state` = ?,`cursorJson` = ?,`processed` = ?,`total` = ?,`successful` = ?,`failed` = ?,`startedAt` = ?,`updatedAt` = ?,`workRequestId` = ?,`fgsMillisUsed` = ? WHERE `operationId` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: OperationCheckpointEntity) {
        statement.bindText(1, entity.operationId)
        statement.bindText(2, entity.type)
        statement.bindText(3, entity.state)
        statement.bindText(4, entity.cursorJson)
        statement.bindLong(5, entity.processed.toLong())
        statement.bindLong(6, entity.total.toLong())
        statement.bindLong(7, entity.successful.toLong())
        statement.bindLong(8, entity.failed.toLong())
        statement.bindLong(9, entity.startedAt)
        statement.bindLong(10, entity.updatedAt)
        val _tmpWorkRequestId: String? = entity.workRequestId
        if (_tmpWorkRequestId == null) {
          statement.bindNull(11)
        } else {
          statement.bindText(11, _tmpWorkRequestId)
        }
        statement.bindLong(12, entity.fgsMillisUsed)
        statement.bindText(13, entity.operationId)
      }
    })
  }

  public override suspend fun upsert(cp: OperationCheckpointEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfOperationCheckpointEntity.upsert(_connection, cp)
  }

  public override suspend fun `get`(id: String): OperationCheckpointEntity? {
    val _sql: String = "SELECT * FROM operation_checkpoint WHERE operationId = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        val _columnIndexOfOperationId: Int = getColumnIndexOrThrow(_stmt, "operationId")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfState: Int = getColumnIndexOrThrow(_stmt, "state")
        val _columnIndexOfCursorJson: Int = getColumnIndexOrThrow(_stmt, "cursorJson")
        val _columnIndexOfProcessed: Int = getColumnIndexOrThrow(_stmt, "processed")
        val _columnIndexOfTotal: Int = getColumnIndexOrThrow(_stmt, "total")
        val _columnIndexOfSuccessful: Int = getColumnIndexOrThrow(_stmt, "successful")
        val _columnIndexOfFailed: Int = getColumnIndexOrThrow(_stmt, "failed")
        val _columnIndexOfStartedAt: Int = getColumnIndexOrThrow(_stmt, "startedAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _columnIndexOfWorkRequestId: Int = getColumnIndexOrThrow(_stmt, "workRequestId")
        val _columnIndexOfFgsMillisUsed: Int = getColumnIndexOrThrow(_stmt, "fgsMillisUsed")
        val _result: OperationCheckpointEntity?
        if (_stmt.step()) {
          val _tmpOperationId: String
          _tmpOperationId = _stmt.getText(_columnIndexOfOperationId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpState: String
          _tmpState = _stmt.getText(_columnIndexOfState)
          val _tmpCursorJson: String
          _tmpCursorJson = _stmt.getText(_columnIndexOfCursorJson)
          val _tmpProcessed: Int
          _tmpProcessed = _stmt.getLong(_columnIndexOfProcessed).toInt()
          val _tmpTotal: Int
          _tmpTotal = _stmt.getLong(_columnIndexOfTotal).toInt()
          val _tmpSuccessful: Int
          _tmpSuccessful = _stmt.getLong(_columnIndexOfSuccessful).toInt()
          val _tmpFailed: Int
          _tmpFailed = _stmt.getLong(_columnIndexOfFailed).toInt()
          val _tmpStartedAt: Long
          _tmpStartedAt = _stmt.getLong(_columnIndexOfStartedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpWorkRequestId: String?
          if (_stmt.isNull(_columnIndexOfWorkRequestId)) {
            _tmpWorkRequestId = null
          } else {
            _tmpWorkRequestId = _stmt.getText(_columnIndexOfWorkRequestId)
          }
          val _tmpFgsMillisUsed: Long
          _tmpFgsMillisUsed = _stmt.getLong(_columnIndexOfFgsMillisUsed)
          _result = OperationCheckpointEntity(_tmpOperationId,_tmpType,_tmpState,_tmpCursorJson,_tmpProcessed,_tmpTotal,_tmpSuccessful,_tmpFailed,_tmpStartedAt,_tmpUpdatedAt,_tmpWorkRequestId,_tmpFgsMillisUsed)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun withState(state: String): List<OperationCheckpointEntity> {
    val _sql: String = "SELECT * FROM operation_checkpoint WHERE state = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, state)
        val _columnIndexOfOperationId: Int = getColumnIndexOrThrow(_stmt, "operationId")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfState: Int = getColumnIndexOrThrow(_stmt, "state")
        val _columnIndexOfCursorJson: Int = getColumnIndexOrThrow(_stmt, "cursorJson")
        val _columnIndexOfProcessed: Int = getColumnIndexOrThrow(_stmt, "processed")
        val _columnIndexOfTotal: Int = getColumnIndexOrThrow(_stmt, "total")
        val _columnIndexOfSuccessful: Int = getColumnIndexOrThrow(_stmt, "successful")
        val _columnIndexOfFailed: Int = getColumnIndexOrThrow(_stmt, "failed")
        val _columnIndexOfStartedAt: Int = getColumnIndexOrThrow(_stmt, "startedAt")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updatedAt")
        val _columnIndexOfWorkRequestId: Int = getColumnIndexOrThrow(_stmt, "workRequestId")
        val _columnIndexOfFgsMillisUsed: Int = getColumnIndexOrThrow(_stmt, "fgsMillisUsed")
        val _result: MutableList<OperationCheckpointEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: OperationCheckpointEntity
          val _tmpOperationId: String
          _tmpOperationId = _stmt.getText(_columnIndexOfOperationId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpState: String
          _tmpState = _stmt.getText(_columnIndexOfState)
          val _tmpCursorJson: String
          _tmpCursorJson = _stmt.getText(_columnIndexOfCursorJson)
          val _tmpProcessed: Int
          _tmpProcessed = _stmt.getLong(_columnIndexOfProcessed).toInt()
          val _tmpTotal: Int
          _tmpTotal = _stmt.getLong(_columnIndexOfTotal).toInt()
          val _tmpSuccessful: Int
          _tmpSuccessful = _stmt.getLong(_columnIndexOfSuccessful).toInt()
          val _tmpFailed: Int
          _tmpFailed = _stmt.getLong(_columnIndexOfFailed).toInt()
          val _tmpStartedAt: Long
          _tmpStartedAt = _stmt.getLong(_columnIndexOfStartedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpWorkRequestId: String?
          if (_stmt.isNull(_columnIndexOfWorkRequestId)) {
            _tmpWorkRequestId = null
          } else {
            _tmpWorkRequestId = _stmt.getText(_columnIndexOfWorkRequestId)
          }
          val _tmpFgsMillisUsed: Long
          _tmpFgsMillisUsed = _stmt.getLong(_columnIndexOfFgsMillisUsed)
          _item = OperationCheckpointEntity(_tmpOperationId,_tmpType,_tmpState,_tmpCursorJson,_tmpProcessed,_tmpTotal,_tmpSuccessful,_tmpFailed,_tmpStartedAt,_tmpUpdatedAt,_tmpWorkRequestId,_tmpFgsMillisUsed)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun remove(id: String) {
    val _sql: String = "DELETE FROM operation_checkpoint WHERE operationId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
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
