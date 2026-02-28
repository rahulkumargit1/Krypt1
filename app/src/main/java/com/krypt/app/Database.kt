package com.krypt.app

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val uuid: String,
    val publicKey: String,
    val nickname: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val fromUuid: String,
    val content: String,
    val contentType: String = "text",
    val filePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isSent: Boolean = true,
    val isDelivered: Boolean = false,
    val isRead: Boolean = false
)

@Entity(tableName = "statuses")
data class StatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromUuid: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
)

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY addedAt DESC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE uuid = :uuid LIMIT 1")
    suspend fun getContact(uuid: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE uuid = :uuid")
    suspend fun deleteContact(uuid: String)

    @Query("UPDATE contacts SET nickname = :nickname WHERE uuid = :uuid")
    suspend fun updateNickname(uuid: String, nickname: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages GROUP BY conversationId HAVING MAX(timestamp) ORDER BY timestamp DESC")
    fun getConversationPreviews(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND isSent = 0 AND isRead = 0")
    fun getUnreadCount(conversationId: String): Flow<Int>

    @Query("UPDATE messages SET isDelivered = 1 WHERE id = :messageId")
    suspend fun markDelivered(messageId: Long)

    @Query("UPDATE messages SET isRead = 1 WHERE conversationId = :conversationId AND isSent = 1")
    suspend fun markAllRead(conversationId: String)

    @Query("UPDATE messages SET isRead = 1 WHERE conversationId = :conversationId AND isSent = 0")
    suspend fun markIncomingRead(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)
}

@Dao
interface StatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatus(status: StatusEntity): Long

    @Query("SELECT * FROM statuses WHERE expiresAt > :now ORDER BY timestamp DESC")
    fun getActiveStatuses(now: Long = System.currentTimeMillis()): Flow<List<StatusEntity>>

    @Query("DELETE FROM statuses WHERE expiresAt <= :now")
    suspend fun deleteExpiredStatuses(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM statuses WHERE fromUuid = :uuid")
    suspend fun deleteStatusByUser(uuid: String)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE messages ADD COLUMN isDelivered INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [ContactEntity::class, MessageEntity::class, StatusEntity::class],
    version = 2,
    exportSchema = false
)
abstract class KryptDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun statusDao(): StatusDao

    companion object {
        @Volatile private var INSTANCE: KryptDatabase? = null

        fun getInstance(context: Context): KryptDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    KryptDatabase::class.java,
                    "krypt_db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
