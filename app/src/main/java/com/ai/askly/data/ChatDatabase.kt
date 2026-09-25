package com.ai.askly.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "chat_sessions")
data class ChatEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val messagesJson: String,
    val position: Int
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY position ASC")
    suspend fun getAll(): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chats: List<ChatEntity>)

    @Query("DELETE FROM chat_sessions WHERE id = :chatId")
    suspend fun deleteById(chatId: String)
}

@Database(entities = [ChatEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "askly.db"
            ).build().also { instance = it }
        }
    }
}

