package com.tgweb.core.data

data class ChatSummary(
    val chatId: Long,
    val title: String,
    val lastMessagePreview: String,
    val lastMessageAt: Long,
    val unreadCount: Int,
)

data class MessageItem(
    val messageId: Long,
    val chatId: Long,
    val senderUserId: Long,
    val text: String,
    val status: String,
    val createdAt: Long,
    val mediaFileId: String? = null,
)

data class MediaDescriptor(
    val fileId: String,
    val mimeType: String,
    val sizeBytes: Long,
)

enum class EvictionPolicy {
    LRU,
}

enum class AutoDownloadPolicy {
    WIFI_ONLY,
    ALWAYS,
    MANUAL,
}

data class OfflinePolicy(
    val maxCacheBytes: Long = 5L * 1024L * 1024L * 1024L,
    val evictionPolicy: EvictionPolicy = EvictionPolicy.LRU,
    val autoDownloadPolicy: AutoDownloadPolicy = AutoDownloadPolicy.WIFI_ONLY,
)
