package org.duzgun.eksiengelplus.database

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
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

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class TelemetryOutboxDao_Impl(
  __db: RoomDatabase,
) : TelemetryOutboxDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfTelemetryOutboxEntity: EntityInsertAdapter<TelemetryOutboxEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfTelemetryOutboxEntity = object : EntityInsertAdapter<TelemetryOutboxEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `telemetry_outbox` (`id`,`endpoint`,`bodyJson`,`attempts`,`nextAttemptAt`) VALUES (nullif(?, 0),?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: TelemetryOutboxEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.endpoint)
        statement.bindText(3, entity.bodyJson)
        statement.bindLong(4, entity.attempts.toLong())
        statement.bindLong(5, entity.nextAttemptAt)
      }
    }
  }

  public override suspend fun add(row: TelemetryOutboxEntity): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfTelemetryOutboxEntity.insertAndReturnId(_connection, row)
    _result
  }

  public override suspend fun due(now: Long, limit: Int): List<TelemetryOutboxEntity> {
    val _sql: String = "SELECT * FROM telemetry_outbox WHERE nextAttemptAt <= ? ORDER BY id LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, now)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfEndpoint: Int = getColumnIndexOrThrow(_stmt, "endpoint")
        val _columnIndexOfBodyJson: Int = getColumnIndexOrThrow(_stmt, "bodyJson")
        val _columnIndexOfAttempts: Int = getColumnIndexOrThrow(_stmt, "attempts")
        val _columnIndexOfNextAttemptAt: Int = getColumnIndexOrThrow(_stmt, "nextAttemptAt")
        val _result: MutableList<TelemetryOutboxEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: TelemetryOutboxEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpEndpoint: String
          _tmpEndpoint = _stmt.getText(_columnIndexOfEndpoint)
          val _tmpBodyJson: String
          _tmpBodyJson = _stmt.getText(_columnIndexOfBodyJson)
          val _tmpAttempts: Int
          _tmpAttempts = _stmt.getLong(_columnIndexOfAttempts).toInt()
          val _tmpNextAttemptAt: Long
          _tmpNextAttemptAt = _stmt.getLong(_columnIndexOfNextAttemptAt)
          _item = TelemetryOutboxEntity(_tmpId,_tmpEndpoint,_tmpBodyJson,_tmpAttempts,_tmpNextAttemptAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun remove(id: Long) {
    val _sql: String = "DELETE FROM telemetry_outbox WHERE id = ?"
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
