package com.tgweb.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgweb.core.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY last_message_at DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE chat_id = :chatId LIMIT 1")
    suspend fun getChat(chatId: Long): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY last_message_at DESC LIMIT :limit")
    suspend fun listForBootstrap(limit: Int): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chats: List<ChatEntity>)
}
