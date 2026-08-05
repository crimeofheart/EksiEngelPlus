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
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import org.duzgun.eksiengelplus.model.ListType

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class RelationUserDao_Impl(
  __db: RoomDatabase,
) : RelationUserDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfRelationUserEntity: EntityUpsertAdapter<RelationUserEntity>

  private val __converters: Converters = Converters()
  init {
    this.__db = __db
    this.__upsertAdapterOfRelationUserEntity = EntityUpsertAdapter<RelationUserEntity>(object : EntityInsertAdapter<RelationUserEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `relation_user` (`listType`,`userId`,`nick`,`addedAt`,`lastSeenAt`,`registrationDate`,`isFollowCurrentUser`,`isBuddy`) VALUES (?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: RelationUserEntity) {
        val _tmp: String = __converters.listTypeToString(entity.listType)
        statement.bindText(1, _tmp)
        statement.bindLong(2, entity.userId)
        statement.bindText(3, entity.nick)
        statement.bindLong(4, entity.addedAt)
        statement.bindLong(5, entity.lastSeenAt)
        val _tmpRegistrationDate: Long? = entity.registrationDate
        if (_tmpRegistrationDate == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpRegistrationDate)
        }
        val _tmpIsFollowCurrentUser: Boolean? = entity.isFollowCurrentUser
        val _tmp_1: Int? = _tmpIsFollowCurrentUser?.let { if (it) 1 else 0 }
        if (_tmp_1 == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmp_1.toLong())
        }
        val _tmpIsBuddy: Boolean? = entity.isBuddy
        val _tmp_2: Int? = _tmpIsBuddy?.let { if (it) 1 else 0 }
        if (_tmp_2 == null) {
          statement.bindNull(8)
        } else {
          statement.bindLong(8, _tmp_2.toLong())
        }
      }
    }, object : EntityDeleteOrUpdateAdapter<RelationUserEntity>() {
      protected override fun createQuery(): String = "UPDATE `relation_user` SET `listType` = ?,`userId` = ?,`nick` = ?,`addedAt` = ?,`lastSeenAt` = ?,`registrationDate` = ?,`isFollowCurrentUser` = ?,`isBuddy` = ? WHERE `listType` = ? AND `userId` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: RelationUserEntity) {
        val _tmp: String = __converters.listTypeToString(entity.listType)
        statement.bindText(1, _tmp)
        statement.bindLong(2, entity.userId)
        statement.bindText(3, entity.nick)
        statement.bindLong(4, entity.addedAt)
        statement.bindLong(5, entity.lastSeenAt)
        val _tmpRegistrationDate: Long? = entity.registrationDate
        if (_tmpRegistrationDate == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpRegistrationDate)
        }
        val _tmpIsFollowCurrentUser: Boolean? = entity.isFollowCurrentUser
        val _tmp_1: Int? = _tmpIsFollowCurrentUser?.let { if (it) 1 else 0 }
        if (_tmp_1 == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmp_1.toLong())
        }
        val _tmpIsBuddy: Boolean? = entity.isBuddy
        val _tmp_2: Int? = _tmpIsBuddy?.let { if (it) 1 else 0 }
        if (_tmp_2 == null) {
          statement.bindNull(8)
        } else {
          statement.bindLong(8, _tmp_2.toLong())
        }
        val _tmp_3: String = __converters.listTypeToString(entity.listType)
        statement.bindText(9, _tmp_3)
        statement.bindLong(10, entity.userId)
      }
    })
  }

  public override suspend fun upsertAll(rows: List<RelationUserEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfRelationUserEntity.upsert(_connection, rows)
  }

  public override suspend fun upsert(row: RelationUserEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfRelationUserEntity.upsert(_connection, row)
  }

  public override fun countOf(listType: ListType): Flow<Int> {
    val _sql: String = "SELECT COUNT(*) FROM relation_user WHERE listType = ?"
    return createFlow(__db, false, arrayOf("relation_user")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
        val _result: Int
        if (_stmt.step()) {
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(0).toInt()
          _result = _tmp_1
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun countOfNow(listType: ListType): Int {
    val _sql: String = "SELECT COUNT(*) FROM relation_user WHERE listType = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
        val _result: Int
        if (_stmt.step()) {
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(0).toInt()
          _result = _tmp_1
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun observe(listType: ListType): Flow<List<RelationUserEntity>> {
    val _sql: String = "SELECT * FROM relation_user WHERE listType = ? ORDER BY nick"
    return createFlow(__db, false, arrayOf("relation_user")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
        val _columnIndexOfListType: Int = getColumnIndexOrThrow(_stmt, "listType")
        val _columnIndexOfUserId: Int = getColumnIndexOrThrow(_stmt, "userId")
        val _columnIndexOfNick: Int = getColumnIndexOrThrow(_stmt, "nick")
        val _columnIndexOfAddedAt: Int = getColumnIndexOrThrow(_stmt, "addedAt")
        val _columnIndexOfLastSeenAt: Int = getColumnIndexOrThrow(_stmt, "lastSeenAt")
        val _columnIndexOfRegistrationDate: Int = getColumnIndexOrThrow(_stmt, "registrationDate")
        val _columnIndexOfIsFollowCurrentUser: Int = getColumnIndexOrThrow(_stmt, "isFollowCurrentUser")
        val _columnIndexOfIsBuddy: Int = getColumnIndexOrThrow(_stmt, "isBuddy")
        val _result: MutableList<RelationUserEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RelationUserEntity
          val _tmpListType: ListType
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfListType)
          _tmpListType = __converters.stringToListType(_tmp_1)
          val _tmpUserId: Long
          _tmpUserId = _stmt.getLong(_columnIndexOfUserId)
          val _tmpNick: String
          _tmpNick = _stmt.getText(_columnIndexOfNick)
          val _tmpAddedAt: Long
          _tmpAddedAt = _stmt.getLong(_columnIndexOfAddedAt)
          val _tmpLastSeenAt: Long
          _tmpLastSeenAt = _stmt.getLong(_columnIndexOfLastSeenAt)
          val _tmpRegistrationDate: Long?
          if (_stmt.isNull(_columnIndexOfRegistrationDate)) {
            _tmpRegistrationDate = null
          } else {
            _tmpRegistrationDate = _stmt.getLong(_columnIndexOfRegistrationDate)
          }
          val _tmpIsFollowCurrentUser: Boolean?
          val _tmp_2: Int?
          if (_stmt.isNull(_columnIndexOfIsFollowCurrentUser)) {
            _tmp_2 = null
          } else {
            _tmp_2 = _stmt.getLong(_columnIndexOfIsFollowCurrentUser).toInt()
          }
          _tmpIsFollowCurrentUser = _tmp_2?.let { it != 0 }
          val _tmpIsBuddy: Boolean?
          val _tmp_3: Int?
          if (_stmt.isNull(_columnIndexOfIsBuddy)) {
            _tmp_3 = null
          } else {
            _tmp_3 = _stmt.getLong(_columnIndexOfIsBuddy).toInt()
          }
          _tmpIsBuddy = _tmp_3?.let { it != 0 }
          _item = RelationUserEntity(_tmpListType,_tmpUserId,_tmpNick,_tmpAddedAt,_tmpLastSeenAt,_tmpRegistrationDate,_tmpIsFollowCurrentUser,_tmpIsBuddy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun `get`(listType: ListType): List<RelationUserEntity> {
    val _sql: String = "SELECT * FROM relation_user WHERE listType = ? ORDER BY nick"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
        val _columnIndexOfListType: Int = getColumnIndexOrThrow(_stmt, "listType")
        val _columnIndexOfUserId: Int = getColumnIndexOrThrow(_stmt, "userId")
        val _columnIndexOfNick: Int = getColumnIndexOrThrow(_stmt, "nick")
        val _columnIndexOfAddedAt: Int = getColumnIndexOrThrow(_stmt, "addedAt")
        val _columnIndexOfLastSeenAt: Int = getColumnIndexOrThrow(_stmt, "lastSeenAt")
        val _columnIndexOfRegistrationDate: Int = getColumnIndexOrThrow(_stmt, "registrationDate")
        val _columnIndexOfIsFollowCurrentUser: Int = getColumnIndexOrThrow(_stmt, "isFollowCurrentUser")
        val _columnIndexOfIsBuddy: Int = getColumnIndexOrThrow(_stmt, "isBuddy")
        val _result: MutableList<RelationUserEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RelationUserEntity
          val _tmpListType: ListType
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfListType)
          _tmpListType = __converters.stringToListType(_tmp_1)
          val _tmpUserId: Long
          _tmpUserId = _stmt.getLong(_columnIndexOfUserId)
          val _tmpNick: String
          _tmpNick = _stmt.getText(_columnIndexOfNick)
          val _tmpAddedAt: Long
          _tmpAddedAt = _stmt.getLong(_columnIndexOfAddedAt)
          val _tmpLastSeenAt: Long
          _tmpLastSeenAt = _stmt.getLong(_columnIndexOfLastSeenAt)
          val _tmpRegistrationDate: Long?
          if (_stmt.isNull(_columnIndexOfRegistrationDate)) {
            _tmpRegistrationDate = null
          } else {
            _tmpRegistrationDate = _stmt.getLong(_columnIndexOfRegistrationDate)
          }
          val _tmpIsFollowCurrentUser: Boolean?
          val _tmp_2: Int?
          if (_stmt.isNull(_columnIndexOfIsFollowCurrentUser)) {
            _tmp_2 = null
          } else {
            _tmp_2 = _stmt.getLong(_columnIndexOfIsFollowCurrentUser).toInt()
          }
          _tmpIsFollowCurrentUser = _tmp_2?.let { it != 0 }
          val _tmpIsBuddy: Boolean?
          val _tmp_3: Int?
          if (_stmt.isNull(_columnIndexOfIsBuddy)) {
            _tmp_3 = null
          } else {
            _tmp_3 = _stmt.getLong(_columnIndexOfIsBuddy).toInt()
          }
          _tmpIsBuddy = _tmp_3?.let { it != 0 }
          _item = RelationUserEntity(_tmpListType,_tmpUserId,_tmpNick,_tmpAddedAt,_tmpLastSeenAt,_tmpRegistrationDate,_tmpIsFollowCurrentUser,_tmpIsBuddy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun olderThan(listType: ListType, beforeEpochDay: Long): List<RelationUserEntity> {
    val _sql: String = "SELECT * FROM relation_user WHERE listType = ? AND registrationDate IS NOT NULL AND registrationDate < ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
        _argIndex = 2
        _stmt.bindLong(_argIndex, beforeEpochDay)
        val _columnIndexOfListType: Int = getColumnIndexOrThrow(_stmt, "listType")
        val _columnIndexOfUserId: Int = getColumnIndexOrThrow(_stmt, "userId")
        val _columnIndexOfNick: Int = getColumnIndexOrThrow(_stmt, "nick")
        val _columnIndexOfAddedAt: Int = getColumnIndexOrThrow(_stmt, "addedAt")
        val _columnIndexOfLastSeenAt: Int = getColumnIndexOrThrow(_stmt, "lastSeenAt")
        val _columnIndexOfRegistrationDate: Int = getColumnIndexOrThrow(_stmt, "registrationDate")
        val _columnIndexOfIsFollowCurrentUser: Int = getColumnIndexOrThrow(_stmt, "isFollowCurrentUser")
        val _columnIndexOfIsBuddy: Int = getColumnIndexOrThrow(_stmt, "isBuddy")
        val _result: MutableList<RelationUserEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: RelationUserEntity
          val _tmpListType: ListType
          val _tmp_1: String
          _tmp_1 = _stmt.getText(_columnIndexOfListType)
          _tmpListType = __converters.stringToListType(_tmp_1)
          val _tmpUserId: Long
          _tmpUserId = _stmt.getLong(_columnIndexOfUserId)
          val _tmpNick: String
          _tmpNick = _stmt.getText(_columnIndexOfNick)
          val _tmpAddedAt: Long
          _tmpAddedAt = _stmt.getLong(_columnIndexOfAddedAt)
          val _tmpLastSeenAt: Long
          _tmpLastSeenAt = _stmt.getLong(_columnIndexOfLastSeenAt)
          val _tmpRegistrationDate: Long?
          if (_stmt.isNull(_columnIndexOfRegistrationDate)) {
            _tmpRegistrationDate = null
          } else {
            _tmpRegistrationDate = _stmt.getLong(_columnIndexOfRegistrationDate)
          }
          val _tmpIsFollowCurrentUser: Boolean?
          val _tmp_2: Int?
          if (_stmt.isNull(_columnIndexOfIsFollowCurrentUser)) {
            _tmp_2 = null
          } else {
            _tmp_2 = _stmt.getLong(_columnIndexOfIsFollowCurrentUser).toInt()
          }
          _tmpIsFollowCurrentUser = _tmp_2?.let { it != 0 }
          val _tmpIsBuddy: Boolean?
          val _tmp_3: Int?
          if (_stmt.isNull(_columnIndexOfIsBuddy)) {
            _tmp_3 = null
          } else {
            _tmp_3 = _stmt.getLong(_columnIndexOfIsBuddy).toInt()
          }
          _tmpIsBuddy = _tmp_3?.let { it != 0 }
          _item = RelationUserEntity(_tmpListType,_tmpUserId,_tmpNick,_tmpAddedAt,_tmpLastSeenAt,_tmpRegistrationDate,_tmpIsFollowCurrentUser,_tmpIsBuddy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun delete(listType: ListType, userId: Long) {
    val _sql: String = "DELETE FROM relation_user WHERE listType = ? AND userId = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
        _argIndex = 2
        _stmt.bindLong(_argIndex, userId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clear(listType: ListType) {
    val _sql: String = "DELETE FROM relation_user WHERE listType = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: String = __converters.listTypeToString(listType)
        _stmt.bindText(_argIndex, _tmp)
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
