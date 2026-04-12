package com.tgweb.core.data

import com.tgweb.core.db.dao.ChatDao
import com.tgweb.core.db.dao.MessageDao
import com.tgweb.core.db.dao.SyncStateDao
import com.tgweb.core.db.entity.ChatEntity
import com.tgweb.core.db.entity.MessageEntity
import com.tgweb.core.db.entity.SyncStateEntity
import com.tgweb.core.tdlib.IncomingMessageEvent
import com.tgweb.core.tdlib.RemoteDialog
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
                runCatching { ingestTdLibMessage(event) }
                    .onFailure { DebugLogStore.logError("TDLIB_MSG", "ingestTdLibMessage failed", it) }
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
        val existingChat = chatDao.getChat(chatId)
        val chatTitle = existingChat?.title ?: "Chat $chatId"
        chatDao.upsertAll(
            listOf(
                ChatEntity(
                    chatId = chatId,
                    title = chatTitle,
                    unreadCount = existingChat?.unreadCount ?: 0,
                    lastMessagePreview = draft,
                    lastMessageAt = now,
                    avatarFileId = existingChat?.avatarFileId,
                )
            )
        )
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
        tdLibGateway.markRead(chatId, messageId)
        val chat = chatDao.getChat(chatId) ?: return
        chatDao.upsertAll(listOf(chat.copy(unreadCount = 0)))
        postUnreadState()
    }

    override suspend fun ingestPushMessage(chatId: Long, messageId: Long, preview: String) {
        val existingChat = chatDao.getChat(chatId)
        val title = existingChat?.title ?: "Chat $chatId"
        ingestMessage(
            MessageItem(
                messageId = messageId,
                chatId = chatId,
                senderUserId = 0L,
                text = preview,
                status = "received",
                createdAt = System.currentTimeMillis(),
                chatTitle = title,
                peerType = PeerType.UNKNOWN,
            ),
            incrementUnread = true,
            source = "push",
        )
    }

    override suspend fun syncNow(reason: String) {
        tdLibGateway.synchronize(reason)
        val dialogs = tdLibGateway.fetchDialogs(limit = 200)
        chatDao.upsertAll(dialogs.map { it.toEntity() })
        val unreadCount = dialogs.sumOf { it.unreadCount }
        AppRepositories.postSyncState(
            lastSyncAt = System.currentTimeMillis(),
            unreadCount = unreadCount,
        )
        syncStateDao.upsert(
            SyncStateEntity(
                key = "last_sync",
                value = reason,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun ingestTdLibMessage(event: IncomingMessageEvent) {
        val item = MessageItem(
            messageId = event.messageId,
            chatId = event.chatId,
            senderUserId = event.senderUserId,
            text = event.text,
            status = "received",
            createdAt = event.createdAt,
            chatTitle = event.chatTitle.ifBlank { null },
            senderName = event.senderName.ifBlank { null },
            peerType = event.peerType,
            silent = event.silent,
            mentioned = event.mentioned,
        )
        ingestMessage(
            message = item,
            incrementUnread = !event.silent,
            source = "tdlib",
        )
        AppRepositories.postPushMessageReceived(
            chatId = item.chatId,
            messageId = item.messageId,
            preview = item.text,
        )
        AppRepositories.postNativeMessageHint(item)
        AppRepositories.notificationService.handleIncomingMessage(item)
    }

    private suspend fun ingestMessage(
        message: MessageItem,
        incrementUnread: Boolean,
        source: String,
    ) {
        val now = System.currentTimeMillis()
        val existingChat = chatDao.getChat(message.chatId)
        val updatedUnread = when {
            incrementUnread -> (existingChat?.unreadCount ?: 0) + 1
            else -> existingChat?.unreadCount ?: 0
        }
        val title = message.chatTitle?.takeIf { it.isNotBlank() }
            ?: existingChat?.title
            ?: "Chat ${message.chatId}"

        chatDao.upsertAll(
            listOf(
                ChatEntity(
                    chatId = message.chatId,
                    title = title,
                    unreadCount = updatedUnread,
                    lastMessagePreview = message.text,
                    lastMessageAt = message.createdAt,
                    avatarFileId = existingChat?.avatarFileId,
                )
            )
        )

        messageDao.upsertAll(
            listOf(
                MessageEntity(
                    messageId = message.messageId,
                    chatId = message.chatId,
                    senderUserId = message.senderUserId,
                    text = message.text,
                    status = message.status,
                    createdAt = message.createdAt,
                    updatedAt = now,
                    mediaFileId = message.mediaFileId,
                )
            )
        )

        DebugLogStore.log(
            "MSG_STORE",
            "Ingested $source message chatId=${message.chatId} messageId=${message.messageId} unread=$updatedUnread",
        )
        postUnreadState()
    }

    private suspend fun postUnreadState() {
        val unreadCount = chatDao.listForBootstrap(limit = 200).sumOf { it.unreadCount }
        AppRepositories.postSyncState(
            lastSyncAt = System.currentTimeMillis(),
            unreadCount = unreadCount,
        )
    }

    private fun RemoteDialog.toEntity(): ChatEntity {
        return ChatEntity(
            chatId = chatId,
            title = title,
            unreadCount = unreadCount,
            lastMessagePreview = lastMessagePreview,
            lastMessageAt = lastMessageAt,
        )
    }
}
