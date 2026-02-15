package com.tgweb.core.data

import com.tgweb.core.db.dao.ChatDao
import com.tgweb.core.db.dao.MessageDao
import com.tgweb.core.db.dao.SyncStateDao
import com.tgweb.core.db.entity.ChatEntity
import com.tgweb.core.db.entity.MessageEntity
import com.tgweb.core.db.entity.SyncStateEntity
import com.tgweb.core.tdlib.TdLibGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ChatRepositoryImpl(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val syncStateDao: SyncStateDao,
    private val tdLibGateway: TdLibGateway,
) : ChatRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            tdLibGateway.observeIncomingMessages().collect { event ->
                messageDao.upsertAll(
                    listOf(
                        MessageEntity(
                            messageId = event.messageId,
                            chatId = event.chatId,
                            senderUserId = event.senderUserId,
                            text = event.text,
                            status = "received",
                            createdAt = event.createdAt,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                )
            }
        }
    }

    override fun observeDialogList(): Flow<List<ChatSummary>> {
        return chatDao.observeChats().map { chats ->
            chats.map {
                ChatSummary(
                    chatId = it.chatId,
                    title = it.title,
                    lastMessagePreview = it.lastMessagePreview,
                    lastMessageAt = it.lastMessageAt,
                    unreadCount = it.unreadCount,
                )
            }
        }
    }

    override fun observeMessages(chatId: Long): Flow<List<MessageItem>> {
        return messageDao.observeMessages(chatId).map { messages ->
            messages.map {
                MessageItem(
                    messageId = it.messageId,
                    chatId = it.chatId,
                    senderUserId = it.senderUserId,
                    text = it.text,
                    status = it.status,
                    createdAt = it.createdAt,
                    mediaFileId = it.mediaFileId,
                )
            }
        }
    }

    override suspend fun sendMessage(chatId: Long, draft: String) {
        val localMessageId = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        messageDao.upsertAll(
            listOf(
                MessageEntity(
                    messageId = localMessageId,
                    chatId = chatId,
                    senderUserId = 1L,
                    text = draft,
                    status = "pending",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        )

        runCatching {
            val remoteMessageId = tdLibGateway.sendMessage(chatId, draft)
            messageDao.updateStatus(localMessageId, "sent", System.currentTimeMillis())
            if (remoteMessageId != localMessageId) {
                messageDao.updateStatus(remoteMessageId, "sent", System.currentTimeMillis())
            }
        }.onFailure {
            messageDao.updateStatus(localMessageId, "failed", System.currentTimeMillis())
        }
    }

    override suspend fun markRead(chatId: Long, messageId: Long) {
        val chat = chatDao.getChat(chatId) ?: return
        chatDao.upsertAll(listOf(chat.copy(unreadCount = 0)))
    }

    override suspend fun ingestPushMessage(chatId: Long, messageId: Long, preview: String) {
        val now = System.currentTimeMillis()
        messageDao.upsertAll(
            listOf(
                MessageEntity(
                    messageId = messageId,
                    chatId = chatId,
                    senderUserId = 0L,
                    text = preview,
                    status = "received",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        )

        val existingChat = chatDao.getChat(chatId)
        val updatedUnread = (existingChat?.unreadCount ?: 0) + 1
        chatDao.upsertAll(
            listOf(
                ChatEntity(
                    chatId = chatId,
                    title = existingChat?.title ?: "Chat $chatId",
                    unreadCount = updatedUnread,
                    lastMessagePreview = preview,
                    lastMessageAt = now,
                    avatarFileId = existingChat?.avatarFileId,
                )
            )
        )
    }

    override suspend fun syncNow(reason: String) {
        tdLibGateway.synchronize(reason)
        val dialogs = tdLibGateway.fetchDialogs(limit = 200)
        chatDao.upsertAll(
            dialogs.map {
                ChatEntity(
                    chatId = it.chatId,
                    title = it.title,
                    unreadCount = it.unreadCount,
                    lastMessagePreview = it.lastMessagePreview,
                    lastMessageAt = it.lastMessageAt,
                )
            }
        )
        AppRepositories.postSyncState(
            lastSyncAt = System.currentTimeMillis(),
            unreadCount = dialogs.sumOf { it.unreadCount },
        )
        syncStateDao.upsert(
            SyncStateEntity(
                key = "last_sync",
                value = reason,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
