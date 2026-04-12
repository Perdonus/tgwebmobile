package com.tgweb.core.tdlib

import com.tgweb.core.webbridge.PeerType
import com.tgweb.core.webbridge.ProxyConfigSnapshot
import com.tgweb.core.webbridge.ProxyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TDLib gateway abstraction so the app can swap between an unavailable stub and a real JNI-backed client.
 */
interface TdLibGateway {
    fun observeIncomingMessages(): Flow<IncomingMessageEvent>
    fun observeRuntimeState(): Flow<TdRuntimeState>
    suspend fun start()
    suspend fun stop()
    suspend fun setProxy(config: ProxyConfigSnapshot)
    suspend fun fetchDialogs(limit: Int = 100): List<RemoteDialog>
    suspend fun sendMessage(chatId: Long, text: String): Long
    suspend fun markRead(chatId: Long, messageId: Long)
    suspend fun synchronize(reason: String)
    suspend fun measureTelegramLatency(config: ProxyConfigSnapshot): Double?
}

enum class TdAuthorizationState {
    LOGGED_OUT,
    WAITING,
    READY,
}

enum class TdConnectionState {
    STOPPED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

data class TdRuntimeState(
    val running: Boolean = false,
    val authorizationState: TdAuthorizationState = TdAuthorizationState.LOGGED_OUT,
    val connectionState: TdConnectionState = TdConnectionState.STOPPED,
    val syncing: Boolean = false,
    val transportLabel: String = "stub",
    val details: String = "TDLib core is idle",
    val proxyConfig: ProxyConfigSnapshot = ProxyConfigSnapshot(),
    val lastUpdatedAt: Long = 0L,
)

data class RemoteDialog(
    val chatId: Long,
    val title: String,
    val unreadCount: Int,
    val lastMessagePreview: String,
    val lastMessageAt: Long,
    val peerType: PeerType = PeerType.UNKNOWN,
)

data class IncomingMessageEvent(
    val messageId: Long,
    val chatId: Long,
    val senderUserId: Long,
    val text: String,
    val createdAt: Long,
    val chatTitle: String = "",
    val senderName: String = "",
    val peerType: PeerType = PeerType.UNKNOWN,
    val silent: Boolean = false,
    val mentioned: Boolean = false,
)

class StubTdLibGateway : TdLibGateway {
    private val incomingMessages = MutableSharedFlow<IncomingMessageEvent>(extraBufferCapacity = 64)
    private val runtimeState = MutableStateFlow(TdRuntimeState())
    private var activeProxy: ProxyConfigSnapshot = ProxyConfigSnapshot()

    override fun observeIncomingMessages(): Flow<IncomingMessageEvent> = incomingMessages.asSharedFlow()

    override fun observeRuntimeState(): Flow<TdRuntimeState> = runtimeState.asStateFlow()

    override suspend fun start() {
        runtimeState.value = runtimeState.value.copy(
            running = true,
            authorizationState = TdAuthorizationState.WAITING,
            connectionState = TdConnectionState.FAILED,
            transportLabel = transportLabel(activeProxy),
            details = unavailableDetails(activeProxy),
            proxyConfig = activeProxy,
            lastUpdatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun stop() {
        runtimeState.value = runtimeState.value.copy(
            running = false,
            authorizationState = TdAuthorizationState.LOGGED_OUT,
            connectionState = TdConnectionState.STOPPED,
            syncing = false,
            details = "TDLib core stopped",
            lastUpdatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun setProxy(config: ProxyConfigSnapshot) {
        activeProxy = config
        runtimeState.value = runtimeState.value.copy(
            transportLabel = transportLabel(config),
            details = if (runtimeState.value.running) unavailableDetails(config) else "TDLib core is idle",
            proxyConfig = config,
            lastUpdatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun fetchDialogs(limit: Int): List<RemoteDialog> = emptyList()

    override suspend fun sendMessage(chatId: Long, text: String): Long {
        runtimeState.value = runtimeState.value.copy(
            details = "Send requested for chat $chatId, but TDLib backend is unavailable",
            lastUpdatedAt = System.currentTimeMillis(),
        )
        return System.currentTimeMillis()
    }

    override suspend fun markRead(chatId: Long, messageId: Long) {
        runtimeState.value = runtimeState.value.copy(
            details = "Mark read requested for chat $chatId, but TDLib backend is unavailable",
            lastUpdatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun synchronize(reason: String) {
        runtimeState.value = runtimeState.value.copy(
            syncing = false,
            connectionState = TdConnectionState.FAILED,
            details = "Sync skipped ($reason): TDLib backend is unavailable",
            lastUpdatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun measureTelegramLatency(config: ProxyConfigSnapshot): Double? = null

    private fun unavailableDetails(config: ProxyConfigSnapshot): String {
        return when {
            config.enabled && config.type == ProxyType.MTPROTO -> {
                "TDLib backend is unavailable, so MTProto transport cannot be validated in this build"
            }
            config.enabled -> {
                "TDLib backend is unavailable in this build (${transportLabel(config)})"
            }
            else -> "TDLib backend is unavailable in this build"
        }
    }

    private fun transportLabel(config: ProxyConfigSnapshot): String {
        if (!config.enabled || config.type == ProxyType.DIRECT) return "direct"
        return when (config.type) {
            ProxyType.HTTP -> "http"
            ProxyType.SOCKS5 -> "socks5"
            ProxyType.MTPROTO -> "mtproto"
            ProxyType.DIRECT -> "direct"
        }
    }
}
