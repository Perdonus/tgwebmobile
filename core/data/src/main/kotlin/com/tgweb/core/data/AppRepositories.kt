package com.tgweb.core.data

import com.tgweb.core.webbridge.BridgeEvent
import com.tgweb.core.webbridge.BridgeEventTypes
import com.tgweb.core.webbridge.ProxyConfigSnapshot
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
    lateinit var persistProxyState: suspend (ProxyConfigSnapshot) -> Unit

    @Volatile
    private var proxyState: ProxyConfigSnapshot = ProxyConfigSnapshot()

    @Volatile
    private var pushPermissionGranted: Boolean = false

    @Volatile
    private var keepAliveEnabled: Boolean = true

    @Volatile
    private var interfaceScalePercent: Int = 100

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

    suspend fun updateProxyState(state: ProxyConfigSnapshot) {
        proxyState = state
        if (::persistProxyState.isInitialized) {
            persistProxyState(state)
        }
        postProxyState(state)
    }

    fun getProxyState(): ProxyConfigSnapshot = proxyState

    fun postProxyState(state: ProxyConfigSnapshot = proxyState) {
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.PROXY_STATE,
                payload = mapOf(
                    "enabled" to state.enabled.toString(),
                    "type" to state.type.name,
                    "host" to state.host,
                    "port" to state.port.toString(),
                    "username" to (state.username ?: ""),
                    "secret" to (state.secret ?: ""),
                ),
            )
        )
    }

    fun postPushPermissionState(granted: Boolean) {
        pushPermissionGranted = granted
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.PUSH_PERMISSION_STATE,
                payload = mapOf("granted" to granted.toString()),
            )
        )
    }

    fun isPushPermissionGranted(): Boolean = pushPermissionGranted

    fun postKeepAliveState(enabled: Boolean = keepAliveEnabled) {
        keepAliveEnabled = enabled
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.KEEP_ALIVE_STATE,
                payload = mapOf("enabled" to enabled.toString()),
            )
        )
    }

    fun postInterfaceScaleState(scalePercent: Int = interfaceScalePercent) {
        interfaceScalePercent = scalePercent
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.INTERFACE_SCALE_STATE,
                payload = mapOf("scalePercent" to scalePercent.toString()),
            )
        )
    }

    fun isInitialized(): Boolean {
        return ::chatRepository.isInitialized &&
            ::mediaRepository.isInitialized &&
            ::notificationService.isInitialized &&
            ::webBridge.isInitialized &&
            ::webBootstrapProvider.isInitialized &&
            ::persistProxyState.isInitialized
    }
}
