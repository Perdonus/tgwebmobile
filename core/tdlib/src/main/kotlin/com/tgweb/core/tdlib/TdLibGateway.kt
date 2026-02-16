package com.tgweb.core.tdlib

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * TDLib gateway abstraction so the app can swap between stub and real JNI-backed client.
 */
interface TdLibGateway {
    fun observeIncomingMessages(): Flow<IncomingMessageEvent>
    suspend fun fetchDialogs(limit: Int = 100): List<RemoteDialog>
    suspend fun sendMessage(chatId: Long, text: String): Long
    suspend fun synchronize(reason: String)
}

data class RemoteDialog(
    val chatId: Long,
    val title: String,
    val unreadCount: Int,
    val lastMessagePreview: String,
    val lastMessageAt: Long,
)

data class IncomingMessageEvent(
    val messageId: Long,
    val chatId: Long,
    val senderUserId: Long,
    val text: String,
    val createdAt: Long,
)

class StubTdLibGateway : TdLibGateway {
    private val incomingMessages = MutableSharedFlow<IncomingMessageEvent>(extraBufferCapacity = 64)

    override fun observeIncomingMessages(): Flow<IncomingMessageEvent> = incomingMessages.asSharedFlow()

    override suspend fun fetchDialogs(limit: Int): List<RemoteDialog> {
        delay(100)
        return listOf(
            RemoteDialog(
                chatId = 1001L,
                title = "Engineering",
                unreadCount = 2,
                lastMessagePreview = "Build is green",
                lastMessageAt = System.currentTimeMillis(),
            ),
            RemoteDialog(
                chatId = 1002L,
                title = "Design",
                unreadCount = 0,
                lastMessagePreview = "Updated mockups",
                lastMessageAt = System.currentTimeMillis() - 120_000,
            ),
        ).take(limit)
    }

    override suspend fun sendMessage(chatId: Long, text: String): Long {
        val messageId = System.currentTimeMillis()
        incomingMessages.tryEmit(
            IncomingMessageEvent(
                messageId = messageId,
                chatId = chatId,
                senderUserId = 1L,
                text = text,
                createdAt = System.currentTimeMillis(),
            )
        )
        return messageId
    }

    override suspend fun synchronize(reason: String) {
        delay(50)
        // Stub sync should not emit noisy synthetic chat notifications.
    }
}
