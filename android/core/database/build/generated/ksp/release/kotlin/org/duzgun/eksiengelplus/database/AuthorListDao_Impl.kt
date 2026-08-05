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

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AuthorListDao_Impl(
  __db: RoomDatabase,
) : AuthorListDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfAuthorListEntity: EntityUpsertAdapter<AuthorListEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfAuthorListEntity = EntityUpsertAdapter<AuthorListEntity>(object : EntityInsertAdapter<AuthorListEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `author_list` (`id`,`nick`,`authorId`,`addedAt`) VALUES (nullif(?, 0),?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AuthorListEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.nick)
        val _tmpAuthorId: Long? = entity.authorId
        if (_tmpAuthorId == null) {
          statement.bindNull(3)
        } else {
          statement.bindLong(3, _tmpAuthorId)
        }
        statement.bindLong(4, entity.addedAt)
      }
    }, object : EntityDeleteOrUpdateAdapter<AuthorListEntity>() {
      protected override fun createQuery(): String = "UPDATE `author_list` SET `id` = ?,`nick` = ?,`authorId` = ?,`addedAt` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: AuthorListEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.nick)
        val _tmpAuthorId: Long? = entity.authorId
        if (_tmpAuthorId == null) {
          statement.bindNull(3)
        } else {
          statement.bindLong(3, _tmpAuthorId)
        }
        statement.bindLong(4, entity.addedAt)
        statement.bindLong(5, entity.id)
      }
    })
  }

  public override suspend fun upsertAll(rows: List<AuthorListEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfAuthorListEntity.upsert(_connection, rows)
  }

  public override fun observe(): Flow<List<AuthorListEntity>> {
    val _sql: String = "SELECT * FROM author_list ORDER BY addedAt"
    return createFlow(__db, false, arrayOf("author_list")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfNick: Int = getColumnIndexOrThrow(_stmt, "nick")
        val _columnIndexOfAuthorId: Int = getColumnIndexOrThrow(_stmt, "authorId")
        val _columnIndexOfAddedAt: Int = getColumnIndexOrThrow(_stmt, "addedAt")
        val _result: MutableList<AuthorListEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: AuthorListEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpNick: String
          _tmpNick = _stmt.getText(_columnIndexOfNick)
          val _tmpAuthorId: Long?
          if (_stmt.isNull(_columnIndexOfAuthorId)) {
            _tmpAuthorId = null
          } else {
            _tmpAuthorId = _stmt.getLong(_columnIndexOfAuthorId)
          }
          val _tmpAddedAt: Long
          _tmpAddedAt = _stmt.getLong(_columnIndexOfAddedAt)
          _item = AuthorListEntity(_tmpId,_tmpNick,_tmpAuthorId,_tmpAddedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clear() {
    val _sql: String = "DELETE FROM author_list"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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
