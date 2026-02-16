package com.tgweb.core.data

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun observeDialogList(): Flow<List<ChatSummary>>
    fun observeMessages(chatId: Long): Flow<List<MessageItem>>
    suspend fun sendMessage(chatId: Long, draft: String)
    suspend fun markRead(chatId: Long, messageId: Long)
    suspend fun ingestPushMessage(chatId: Long, messageId: Long, preview: String)
    suspend fun syncNow(reason: String)
}

interface MediaRepository {
    suspend fun getMediaFile(fileId: String): Result<String>
    suspend fun cacheRemoteFile(fileId: String, url: String, mimeType: String, fileName: String?): Result<String>
    suspend fun prefetch(chatId: Long, window: Int)
    suspend fun downloadToPublicStorage(fileId: String, targetCollection: String): Result<String>
    suspend fun removeCachedFile(fileId: String): Boolean
    suspend fun clearCache(scope: String)
}

interface NotificationService {
    suspend fun registerDeviceToken(fcmToken: String)
    suspend fun handlePush(payload: Map<String, String>)
    fun showMessageNotification(event: MessageItem)
    suspend fun sendServerTestPush(): PushDebugResult
}

data class PushDebugResult(
    val success: Boolean,
    val title: String,
    val details: String,
)
