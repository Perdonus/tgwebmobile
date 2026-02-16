package com.tgweb.core.webbridge

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

data class BridgeCommand(
    val type: String,
    val payload: Map<String, String> = emptyMap(),
)

data class BridgeEvent(
    val type: String,
    val payload: Map<String, String> = emptyMap(),
)

data class WebDialogSnapshot(
    val chatId: Long,
    val title: String,
    val lastMessagePreview: String,
    val unreadCount: Int,
    val lastMessageAt: Long,
)

data class CachedMediaSnapshot(
    val fileId: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class WebMessageSnapshot(
    val messageId: Long,
    val chatId: Long,
    val text: String,
    val status: String,
    val createdAt: Long,
)

enum class ProxyType {
    HTTP,
    SOCKS5,
    MTPROTO,
    DIRECT,
}

data class ProxyConfigSnapshot(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.DIRECT,
    val host: String = "",
    val port: Int = 0,
    val username: String? = null,
    val password: String? = null,
    val secret: String? = null,
)

data class WebBootstrapSnapshot(
    val dialogs: List<WebDialogSnapshot> = emptyList(),
    val recentMessages: List<WebMessageSnapshot> = emptyList(),
    val unread: Int = 0,
    val cachedMedia: List<CachedMediaSnapshot> = emptyList(),
    val lastSyncAt: Long = 0L,
    val proxyState: ProxyConfigSnapshot = ProxyConfigSnapshot(),
)

object BridgeCommandTypes {
    const val DOWNLOAD_MEDIA = "DOWNLOAD_MEDIA"
    const val PIN_MEDIA = "PIN_MEDIA"
    const val GET_OFFLINE_STATUS = "GET_OFFLINE_STATUS"
    const val REQUEST_PUSH_PERMISSION = "REQUEST_PUSH_PERMISSION"
    const val OPEN_MOD_SETTINGS = "OPEN_MOD_SETTINGS"
    const val SET_PROXY = "SET_PROXY"
    const val GET_PROXY_STATUS = "GET_PROXY_STATUS"
    const val SET_SYSTEM_UI_STYLE = "SET_SYSTEM_UI_STYLE"
    const val SET_INTERFACE_SCALE = "SET_INTERFACE_SCALE"
    const val SET_KEEP_ALIVE = "SET_KEEP_ALIVE"
    const val GET_KEEP_ALIVE_STATE = "GET_KEEP_ALIVE_STATE"
    const val OPEN_DOWNLOADS = "OPEN_DOWNLOADS"
    const val OPEN_SESSION_TOOLS = "OPEN_SESSION_TOOLS"
}

object BridgeEventTypes {
    const val PUSH_MESSAGE_RECEIVED = "PUSH_MESSAGE_RECEIVED"
    const val DOWNLOAD_PROGRESS = "DOWNLOAD_PROGRESS"
    const val NETWORK_STATE = "NETWORK_STATE"
    const val SYNC_STATE = "SYNC_STATE"
    const val PROXY_STATE = "PROXY_STATE"
    const val PUSH_PERMISSION_STATE = "PUSH_PERMISSION_STATE"
    const val KEEP_ALIVE_STATE = "KEEP_ALIVE_STATE"
    const val INTERFACE_SCALE_STATE = "INTERFACE_SCALE_STATE"
}

interface WebBridgeContract {
    fun postToWeb(event: BridgeEvent)
    fun onFromWeb(command: BridgeCommand)
    fun registerCommandHandler(handler: (BridgeCommand) -> Unit)
    fun clearCommandHandlers()
    fun setWebEventSink(sink: ((BridgeEvent) -> Unit)?)
}

class WebBridgeBus : WebBridgeContract {
    private val commandHandlers = CopyOnWriteArrayList<(BridgeCommand) -> Unit>()
    private val pendingEvents = ConcurrentLinkedQueue<BridgeEvent>()

    @Volatile
    private var webEventSink: ((BridgeEvent) -> Unit)? = null

    override fun postToWeb(event: BridgeEvent) {
        val sink = webEventSink
        if (sink == null) {
            pendingEvents.add(event)
            return
        }
        sink(event)
    }

    override fun onFromWeb(command: BridgeCommand) {
        commandHandlers.forEach { it(command) }
    }

    override fun registerCommandHandler(handler: (BridgeCommand) -> Unit) {
        commandHandlers.add(handler)
    }

    override fun clearCommandHandlers() {
        commandHandlers.clear()
    }

    override fun setWebEventSink(sink: ((BridgeEvent) -> Unit)?) {
        webEventSink = sink
        if (sink == null) return

        while (true) {
            val event = pendingEvents.poll() ?: break
            sink(event)
        }
    }
}
