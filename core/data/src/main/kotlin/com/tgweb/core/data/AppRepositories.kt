package com.tgweb.core.data

import com.tgweb.core.tdlib.TdLibGateway
import com.tgweb.core.webbridge.BackgroundStateSnapshot
import com.tgweb.core.webbridge.BridgeEvent
import com.tgweb.core.webbridge.BridgeEventTypes
import com.tgweb.core.webbridge.ProxyConfigSnapshot
import com.tgweb.core.webbridge.WebBootstrapSnapshot
import com.tgweb.core.webbridge.WebBridgeContract
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight service locator for workers/services that are created by the Android framework.
 */
object AppRepositories {
    data class DownloadStatusSnapshot(
        val fileId: String,
        val displayName: String,
        val mimeType: String,
        val expectedBytes: Long,
        val percent: Int,
        val localUri: String?,
        val error: String?,
        val updatedAt: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var chatRepository: ChatRepository
    lateinit var mediaRepository: MediaRepository
    lateinit var notificationService: NotificationService
    lateinit var tdLibGateway: TdLibGateway
    lateinit var webBridge: WebBridgeContract
    lateinit var webBootstrapProvider: suspend () -> WebBootstrapSnapshot
    lateinit var persistProxyState: suspend (ProxyConfigSnapshot) -> Unit
    lateinit var persistBackgroundState: suspend (BackgroundStateSnapshot) -> Unit

    @Volatile
    private var proxyState: ProxyConfigSnapshot = ProxyConfigSnapshot()

    @Volatile
    private var pushPermissionGranted: Boolean = false

    @Volatile
    private var keepAliveEnabled: Boolean = true

    @Volatile
    private var interfaceScalePercent: Int = 100

    @Volatile
    private var backgroundState: BackgroundStateSnapshot = BackgroundStateSnapshot()

    private val downloadStates = ConcurrentHashMap<String, DownloadStatusSnapshot>()
    private val proxyStateListeners = CopyOnWriteArrayList<(ProxyConfigSnapshot) -> Unit>()
    private val backgroundStateListeners = CopyOnWriteArrayList<(BackgroundStateSnapshot) -> Unit>()

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

    fun postNativeMessageHint(message: MessageItem) {
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.NATIVE_MESSAGE_HINT,
                payload = mapOf(
                    "chatId" to message.chatId.toString(),
                    "messageId" to message.messageId.toString(),
                    "preview" to message.text,
                    "chatTitle" to (message.chatTitle ?: ""),
                    "senderName" to (message.senderName ?: ""),
                    "peerType" to message.peerType.name,
                ),
            )
        )
    }

    fun registerDownload(
        fileId: String,
        displayName: String,
        mimeType: String = "application/octet-stream",
        expectedBytes: Long = 0L,
    ) {
        val current = downloadStates[fileId]
        downloadStates[fileId] = DownloadStatusSnapshot(
            fileId = fileId,
            displayName = displayName.ifBlank { current?.displayName ?: fileId },
            mimeType = mimeType.ifBlank { current?.mimeType ?: "application/octet-stream" },
            expectedBytes = expectedBytes.takeIf { it > 0L } ?: current?.expectedBytes ?: 0L,
            percent = current?.percent ?: 0,
            localUri = current?.localUri,
            error = current?.error,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun getDownloadStatuses(): List<DownloadStatusSnapshot> {
        return downloadStates.values
            .sortedByDescending { it.updatedAt }
    }

    fun postDownloadProgress(
        fileId: String,
        percent: Int,
        localUri: String? = null,
        error: String? = null,
        displayName: String? = null,
        mimeType: String? = null,
        expectedBytes: Long? = null,
    ) {
        val current = downloadStates[fileId]
        downloadStates[fileId] = DownloadStatusSnapshot(
            fileId = fileId,
            displayName = displayName?.ifBlank { null } ?: current?.displayName ?: fileId,
            mimeType = mimeType?.ifBlank { null } ?: current?.mimeType ?: "application/octet-stream",
            expectedBytes = expectedBytes?.takeIf { it > 0L } ?: current?.expectedBytes ?: 0L,
            percent = percent.coerceIn(0, 100),
            localUri = localUri ?: current?.localUri,
            error = error,
            updatedAt = System.currentTimeMillis(),
        )
        if (!::webBridge.isInitialized) return
        DebugLogStore.log(
            "DOWNLOAD_EVT",
            "postDownloadProgress fileId=$fileId percent=$percent localUri=${localUri.orEmpty().take(180)} error=${error.orEmpty()}",
        )

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
        if (::webBridge.isInitialized) {
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
        postNativeUnreadState(unreadCount)
    }

    fun postNativeUnreadState(unreadCount: Int) {
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.NATIVE_UNREAD_STATE,
                payload = mapOf("unreadCount" to unreadCount.toString()),
            )
        )
    }

    suspend fun updateProxyState(state: ProxyConfigSnapshot) {
        proxyState = state
        if (::persistProxyState.isInitialized) {
            persistProxyState(state)
        }
        proxyStateListeners.forEach { listener ->
            runCatching { listener(state) }
                .onFailure { DebugLogStore.logError("PROXY", "Proxy listener failed", it) }
        }
        postProxyState(state)
    }

    fun getProxyState(): ProxyConfigSnapshot = proxyState

    fun registerProxyStateListener(listener: (ProxyConfigSnapshot) -> Unit): () -> Unit {
        proxyStateListeners.add(listener)
        runCatching { listener(proxyState) }
            .onFailure { DebugLogStore.logError("PROXY", "Proxy listener initial dispatch failed", it) }
        return { proxyStateListeners.remove(listener) }
    }

    fun getBackgroundState(): BackgroundStateSnapshot = backgroundState

    fun registerBackgroundStateListener(listener: (BackgroundStateSnapshot) -> Unit): () -> Unit {
        backgroundStateListeners.add(listener)
        runCatching { listener(backgroundState) }
            .onFailure { DebugLogStore.logError("TDLIB", "Background listener initial dispatch failed", it) }
        return { backgroundStateListeners.remove(listener) }
    }

    fun postBackgroundState(state: BackgroundStateSnapshot) {
        backgroundState = state
        if (::persistBackgroundState.isInitialized) {
            scope.launch {
                runCatching { persistBackgroundState(state) }
                    .onFailure { DebugLogStore.logError("TDLIB", "Persist background state failed", it) }
            }
        }
        backgroundStateListeners.forEach { listener ->
            runCatching { listener(state) }
                .onFailure { DebugLogStore.logError("TDLIB", "Background listener failed", it) }
        }
        if (!::webBridge.isInitialized) return
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.BACKGROUND_STATE,
                payload = mapOf(
                    "running" to state.running.toString(),
                    "connected" to state.connected.toString(),
                    "authorized" to state.authorized.toString(),
                    "syncing" to state.syncing.toString(),
                    "transportLabel" to state.transportLabel,
                    "details" to state.details,
                    "lastEventAt" to state.lastEventAt.toString(),
                ),
            )
        )
        webBridge.postToWeb(
            BridgeEvent(
                type = BridgeEventTypes.TDLIB_AUTH_STATE,
                payload = mapOf(
                    "authorized" to state.authorized.toString(),
                    "running" to state.running.toString(),
                    "connected" to state.connected.toString(),
                    "details" to state.details,
                ),
            )
        )
    }

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
            ::tdLibGateway.isInitialized &&
            ::webBridge.isInitialized &&
            ::webBootstrapProvider.isInitialized &&
            ::persistProxyState.isInitialized &&
            ::persistBackgroundState.isInitialized
    }
}
