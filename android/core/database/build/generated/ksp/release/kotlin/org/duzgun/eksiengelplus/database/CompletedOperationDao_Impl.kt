package org.duzgun.eksiengelplus.database

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
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
public class CompletedOperationDao_Impl(
  __db: RoomDatabase,
) : CompletedOperationDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfCompletedOperationEntity:
      EntityInsertAdapter<CompletedOperationEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfCompletedOperationEntity = object : EntityInsertAdapter<CompletedOperationEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `completed_operation` (`id`,`banSourcePk`,`banModePk`,`processed`,`successful`,`failed`,`startedAt`,`finishedAt`,`summaryJson`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: CompletedOperationEntity) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.banSourcePk.toLong())
        statement.bindLong(3, entity.banModePk.toLong())
        statement.bindLong(4, entity.processed.toLong())
        statement.bindLong(5, entity.successful.toLong())
        statement.bindLong(6, entity.failed.toLong())
        statement.bindLong(7, entity.startedAt)
        statement.bindLong(8, entity.finishedAt)
        statement.bindText(9, entity.summaryJson)
      }
    }
  }

  public override suspend fun insert(row: CompletedOperationEntity): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfCompletedOperationEntity.insertAndReturnId(_connection, row)
    _result
  }

  public override fun recent(limit: Int): Flow<List<CompletedOperationEntity>> {
    val _sql: String = "SELECT * FROM completed_operation ORDER BY finishedAt DESC LIMIT ?"
    return createFlow(__db, false, arrayOf("completed_operation")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfBanSourcePk: Int = getColumnIndexOrThrow(_stmt, "banSourcePk")
        val _columnIndexOfBanModePk: Int = getColumnIndexOrThrow(_stmt, "banModePk")
        val _columnIndexOfProcessed: Int = getColumnIndexOrThrow(_stmt, "processed")
        val _columnIndexOfSuccessful: Int = getColumnIndexOrThrow(_stmt, "successful")
        val _columnIndexOfFailed: Int = getColumnIndexOrThrow(_stmt, "failed")
        val _columnIndexOfStartedAt: Int = getColumnIndexOrThrow(_stmt, "startedAt")
        val _columnIndexOfFinishedAt: Int = getColumnIndexOrThrow(_stmt, "finishedAt")
        val _columnIndexOfSummaryJson: Int = getColumnIndexOrThrow(_stmt, "summaryJson")
        val _result: MutableList<CompletedOperationEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: CompletedOperationEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpBanSourcePk: Int
          _tmpBanSourcePk = _stmt.getLong(_columnIndexOfBanSourcePk).toInt()
          val _tmpBanModePk: Int
          _tmpBanModePk = _stmt.getLong(_columnIndexOfBanModePk).toInt()
          val _tmpProcessed: Int
          _tmpProcessed = _stmt.getLong(_columnIndexOfProcessed).toInt()
          val _tmpSuccessful: Int
          _tmpSuccessful = _stmt.getLong(_columnIndexOfSuccessful).toInt()
          val _tmpFailed: Int
          _tmpFailed = _stmt.getLong(_columnIndexOfFailed).toInt()
          val _tmpStartedAt: Long
          _tmpStartedAt = _stmt.getLong(_columnIndexOfStartedAt)
          val _tmpFinishedAt: Long
          _tmpFinishedAt = _stmt.getLong(_columnIndexOfFinishedAt)
          val _tmpSummaryJson: String
          _tmpSummaryJson = _stmt.getText(_columnIndexOfSummaryJson)
          _item = CompletedOperationEntity(_tmpId,_tmpBanSourcePk,_tmpBanModePk,_tmpProcessed,_tmpSuccessful,_tmpFailed,_tmpStartedAt,_tmpFinishedAt,_tmpSummaryJson)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun trim(keep: Int): Int {
    val _sql: String = "DELETE FROM completed_operation WHERE id NOT IN (SELECT id FROM completed_operation ORDER BY finishedAt DESC LIMIT ?)"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, keep.toLong())
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
