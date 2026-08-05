package org.duzgun.eksiengelplus.database

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.EntityUpsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.getTotalChangedRows
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class RegistrationDateCacheDao_Impl(
  __db: RoomDatabase,
) : RegistrationDateCacheDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfRegistrationDateCacheEntity:
      EntityUpsertAdapter<RegistrationDateCacheEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfRegistrationDateCacheEntity = EntityUpsertAdapter<RegistrationDateCacheEntity>(object : EntityInsertAdapter<RegistrationDateCacheEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `registration_date_cache` (`nick`,`authorId`,`registrationEpochDay`,`fetchedAt`) VALUES (?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: RegistrationDateCacheEntity) {
        statement.bindText(1, entity.nick)
        val _tmpAuthorId: Long? = entity.authorId
        if (_tmpAuthorId == null) {
          statement.bindNull(2)
        } else {
          statement.bindLong(2, _tmpAuthorId)
        }
        val _tmpRegistrationEpochDay: Long? = entity.registrationEpochDay
        if (_tmpRegistrationEpochDay == null) {
          statement.bindNull(3)
        } else {
          statement.bindLong(3, _tmpRegistrationEpochDay)
        }
        statement.bindLong(4, entity.fetchedAt)
      }
    }, object : EntityDeleteOrUpdateAdapter<RegistrationDateCacheEntity>() {
      protected override fun createQuery(): String = "UPDATE `registration_date_cache` SET `nick` = ?,`authorId` = ?,`registrationEpochDay` = ?,`fetchedAt` = ? WHERE `nick` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: RegistrationDateCacheEntity) {
        statement.bindText(1, entity.nick)
        val _tmpAuthorId: Long? = entity.authorId
        if (_tmpAuthorId == null) {
          statement.bindNull(2)
        } else {
          statement.bindLong(2, _tmpAuthorId)
        }
        val _tmpRegistrationEpochDay: Long? = entity.registrationEpochDay
        if (_tmpRegistrationEpochDay == null) {
          statement.bindNull(3)
        } else {
          statement.bindLong(3, _tmpRegistrationEpochDay)
        }
        statement.bindLong(4, entity.fetchedAt)
        statement.bindText(5, entity.nick)
      }
    })
  }

  public override suspend fun upsert(row: RegistrationDateCacheEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfRegistrationDateCacheEntity.upsert(_connection, row)
  }

  public override suspend fun `get`(nick: String): RegistrationDateCacheEntity? {
    val _sql: String = "SELECT * FROM registration_date_cache WHERE nick = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nick)
        val _columnIndexOfNick: Int = getColumnIndexOrThrow(_stmt, "nick")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfRegistrationEpochDay: Int = getColumnIndexOrThrow(_stmt, "registrationEpochDay")
        val _columnIndexOfFetchedAt: Int = getColumnIndexOrThrow(_stmt, "fetchedAt")
        val _result: RegistrationDateCacheEntity?
        if (_stmt.step()) {
          val _tmpNick: String
          _tmpNick = _stmt.getText(_columnIndexOfNick)
          val _tmpAuthorId: Long?
          if (_stmt.isNull(_columnIndexOfAuthorId)) {
            _tmpAuthorId = null
          } else {
            _tmpAuthorId = _stmt.getLong(_columnIndexOfAuthorId)
          }
          val _tmpRegistrationEpochDay: Long?
          if (_stmt.isNull(_columnIndexOfRegistrationEpochDay)) {
            _tmpRegistrationEpochDay = null
          } else {
            _tmpRegistrationEpochDay = _stmt.getLong(_columnIndexOfRegistrationEpochDay)
          }
          val _tmpFetchedAt: Long
          _tmpFetchedAt = _stmt.getLong(_columnIndexOfFetchedAt)
          _result = RegistrationDateCacheEntity(_tmpNick,_tmpAuthorId,_tmpRegistrationEpochDay,_tmpFetchedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getFresh(nick: String, minFetchedAt: Long): RegistrationDateCacheEntity? {
    val _sql: String = "SELECT * FROM registration_date_cache WHERE nick = ? AND fetchedAt >= ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, nick)
        _argIndex = 2
        _stmt.bindLong(_argIndex, minFetchedAt)
        val _columnIndexOfNick: Int = getColumnIndexOrThrow(_stmt, "nick")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfRegistrationEpochDay: Int = getColumnIndexOrThrow(_stmt, "registrationEpochDay")
        val _columnIndexOfFetchedAt: Int = getColumnIndexOrThrow(_stmt, "fetchedAt")
        val _result: RegistrationDateCacheEntity?
        if (_stmt.step()) {
          val _tmpNick: String
          _tmpNick = _stmt.getText(_columnIndexOfNick)
          val _tmpAuthorId: Long?
          if (_stmt.isNull(_columnIndexOfAuthorId)) {
            _tmpAuthorId = null
          } else {
            _tmpAuthorId = _stmt.getLong(_columnIndexOfAuthorId)
          }
          val _tmpRegistrationEpochDay: Long?
          if (_stmt.isNull(_columnIndexOfRegistrationEpochDay)) {
            _tmpRegistrationEpochDay = null
          } else {
            _tmpRegistrationEpochDay = _stmt.getLong(_columnIndexOfRegistrationEpochDay)
          }
          val _tmpFetchedAt: Long
          _tmpFetchedAt = _stmt.getLong(_columnIndexOfFetchedAt)
          _result = RegistrationDateCacheEntity(_tmpNick,_tmpAuthorId,_tmpRegistrationEpochDay,_tmpFetchedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun size(): Int {
    val _sql: String = "SELECT COUNT(*) FROM registration_date_cache"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun trimExpired(minFetchedAt: Long): Int {
    val _sql: String = "DELETE FROM registration_date_cache WHERE fetchedAt < ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, minFetchedAt)
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
