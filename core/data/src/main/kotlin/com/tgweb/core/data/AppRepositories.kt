package com.tgweb.core.data

import com.tgweb.core.webbridge.BridgeEvent
import com.tgweb.core.webbridge.BridgeEventTypes
import com.tgweb.core.webbridge.WebBootstrapSnapshot
import com.tgweb.core.webbridge.WebBridgeContract

/**
 * Lightweight service locator for workers/services that are created by the Android framework.
 */
object AppRepositories {
    lateinit var chatRepository: ChatRepository
    lateinit var mediaRepository: MediaRepository
    lateinit var notificationService: NotificationService
    lateinit var webBridge: WebBridgeContract
    lateinit var webBootstrapProvider: suspend () -> WebBootstrapSnapshot

    fun postPushMessageReceived(chatId: Long, messageId: Long, preview: String) {
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.PUSH_MESSAGE_RECEIVED,
                payload = mapOf(
                    "chatId" to chatId.toString(),
                    "messageId" to messageId.toString(),
                    "preview" to preview,
                ),
            )
        )
    }

    fun postDownloadProgress(fileId: String, percent: Int, localUri: String? = null, error: String? = null) {
        if (!::webBridge.isInitialized) return

        val payload = mutableMapOf(
            "fileId" to fileId,
            "percent" to percent.toString(),
        )
        if (localUri != null) payload["localUri"] = localUri
        if (error != null) payload["error"] = error

        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.DOWNLOAD_PROGRESS,
                payload = payload,
            )
        )
    }

    fun postNetworkState(online: Boolean) {
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.NETWORK_STATE,
                payload = mapOf("online" to online.toString()),
            )
        )
    }

    fun postSyncState(lastSyncAt: Long, unreadCount: Int) {
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.SYNC_STATE,
                payload = mapOf(
                    "lastSyncAt" to lastSyncAt.toString(),
                    "unreadCount" to unreadCount.toString(),
                ),
            )
        )
    }

    fun isInitialized(): Boolean {
        return ::chatRepository.isInitialized &&
            ::mediaRepository.isInitialized &&
            ::notificationService.isInitialized &&
            ::webBridge.isInitialized &&
            ::webBootstrapProvider.isInitialized
    }
}
