package com.tgweb.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.ChatRepositoryImpl
import com.tgweb.core.db.TelegramDatabaseFactory
import com.tgweb.core.db.entity.SyncStateEntity
import com.tgweb.core.media.MediaCacheManager
import com.tgweb.core.media.MediaRepositoryImpl
import com.tgweb.core.notifications.AndroidNotificationService
import com.tgweb.core.sync.SyncScheduler
import com.tgweb.core.tdlib.StubTdLibGateway
import com.tgweb.core.webbridge.BridgeCommandTypes
import com.tgweb.core.webbridge.CachedMediaSnapshot
import com.tgweb.core.webbridge.ProxyConfigSnapshot
import com.tgweb.core.webbridge.ProxyType
import com.tgweb.core.webbridge.WebBootstrapSnapshot
import com.tgweb.core.webbridge.WebBridgeBus
import com.tgweb.core.webbridge.WebDialogSnapshot
import com.tgweb.core.webbridge.WebMessageSnapshot
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        runCatching { SessionBackupManager.applyPendingImportIfNeeded(this) }

        val database = TelegramDatabaseFactory.create(this, passphrase = "qa-only-passphrase")
        val tdLibGateway = StubTdLibGateway()
        val chatRepository = ChatRepositoryImpl(
            chatDao = database.chatDao(),
            messageDao = database.messageDao(),
            syncStateDao = database.syncStateDao(),
            tdLibGateway = tdLibGateway,
        )

        val cacheManager = MediaCacheManager(
            context = this,
            mediaDao = database.mediaDao(),
        )
        val mediaRepository = MediaRepositoryImpl(this, cacheManager)

        val notificationService = AndroidNotificationService(this)
        notificationService.ensureChannels()
        val webBridge = WebBridgeBus()

        webBridge.registerCommandHandler { command ->
            appScope.launch {
                when (command.type) {
                    BridgeCommandTypes.GET_OFFLINE_STATUS -> {
                        AppRepositories.postNetworkState(isNetworkAvailable())
                    }
                    BridgeCommandTypes.REQUEST_PUSH_PERMISSION -> {
                        AppRepositories.postSyncState(
                            lastSyncAt = System.currentTimeMillis(),
                            unreadCount = 0,
                        )
                    }
                    BridgeCommandTypes.PIN_MEDIA -> {
                        val fileId = command.payload["fileId"].orEmpty()
                        if (fileId.isNotBlank()) {
                            cacheManager.pin(fileId)
                            AppRepositories.postDownloadProgress(fileId = fileId, percent = 100)
                        }
                    }
                    BridgeCommandTypes.DOWNLOAD_MEDIA -> {
                        val fileId = command.payload["fileId"].orEmpty()
                        if (fileId.isBlank()) return@launch
                        val url = command.payload["url"].orEmpty()
                        val mime = command.payload["mime"].orEmpty()
                        val name = command.payload["name"]

                        mediaRepository.cacheRemoteFile(
                            fileId = fileId,
                            url = url,
                            mimeType = mime,
                            fileName = name,
                        )

                        val export = command.payload["export"].orEmpty().equals("true", ignoreCase = true)
                        if (export) {
                            mediaRepository.downloadToPublicStorage(
                                fileId,
                                command.payload["targetCollection"] ?: "tgweb_downloads",
                            )
                        } else {
                            mediaRepository.getMediaFile(fileId)
                        }
                    }
                    BridgeCommandTypes.SET_PROXY -> {
                        val proxyState = parseProxyConfig(command.payload) ?: return@launch
                        AppRepositories.updateProxyState(proxyState)
                    }
                    BridgeCommandTypes.GET_PROXY_STATUS -> {
                        AppRepositories.postProxyState()
                    }
                }
            }
        }

        AppRepositories.chatRepository = chatRepository
        AppRepositories.mediaRepository = mediaRepository
        AppRepositories.notificationService = notificationService
        AppRepositories.webBridge = webBridge
        AppRepositories.persistProxyState = { proxyState ->
            database.syncStateDao().upsert(
                SyncStateEntity(
                    key = KEY_PROXY_STATE,
                    value = proxyToJson(proxyState),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
        AppRepositories.webBootstrapProvider = {
            val dialogs = database.chatDao().listForBootstrap(limit = 100).map {
                WebDialogSnapshot(
                    chatId = it.chatId,
                    title = it.title,
                    lastMessagePreview = it.lastMessagePreview,
                    unreadCount = it.unreadCount,
                    lastMessageAt = it.lastMessageAt,
                )
            }
            val cachedMedia = database.mediaDao().listRecent(limit = 300).map {
                CachedMediaSnapshot(
                    fileId = it.fileId,
                    localPath = it.localPath,
                    mimeType = it.mimeType,
                    sizeBytes = it.sizeBytes,
                )
            }
            val recentMessages = database.messageDao().listRecentForBootstrap(limit = 300).map {
                WebMessageSnapshot(
                    messageId = it.messageId,
                    chatId = it.chatId,
                    text = it.text,
                    status = it.status,
                    createdAt = it.createdAt,
                )
            }
            val lastSyncAt = database.syncStateDao().get("last_sync")?.updatedAt ?: 0L
            val persistedProxy = database.syncStateDao().get(KEY_PROXY_STATE)?.value
                ?.let(::proxyFromJson)
                ?: ProxyConfigSnapshot()
            WebBootstrapSnapshot(
                dialogs = dialogs,
                recentMessages = recentMessages,
                unread = dialogs.sumOf { it.unreadCount },
                cachedMedia = cachedMedia,
                lastSyncAt = lastSyncAt,
                proxyState = persistedProxy,
            )
        }

        appScope.launch {
            val profileBackedProxy = ProxyProfilesStore.resolveActiveConfig(this@MainApplication)
            val persistedProxy = database.syncStateDao().get(KEY_PROXY_STATE)?.value
                ?.let(::proxyFromJson)
                ?: ProxyConfigSnapshot()
            val proxyToApply = if (profileBackedProxy.enabled) {
                profileBackedProxy
            } else {
                persistedProxy
            }
            AppRepositories.updateProxyState(proxyToApply)
            AppRepositories.postInterfaceScaleState(
                getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
                    .getInt(KeepAliveService.KEY_UI_SCALE_PERCENT, 100)
            )
            AppRepositories.postKeepAliveState(KeepAliveService.isEnabled(this@MainApplication))
        }

        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    appScope.launch {
                        runCatching { AppRepositories.notificationService.registerDeviceToken(token) }
                    }
                }
        }

        SyncScheduler.schedulePeriodic(this)
        if (KeepAliveService.isEnabled(this)) {
            KeepAliveService.start(this)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun parseProxyConfig(payload: Map<String, String>): ProxyConfigSnapshot? {
        val enabled = payload["enabled"].orEmpty().equals("true", ignoreCase = true)
        if (!enabled) return ProxyConfigSnapshot(enabled = false, type = ProxyType.DIRECT)

        val type = when (payload["type"]?.uppercase()) {
            ProxyType.HTTP.name -> ProxyType.HTTP
            ProxyType.SOCKS5.name -> ProxyType.SOCKS5
            "SOCKS" -> ProxyType.SOCKS5
            ProxyType.MTPROTO.name -> ProxyType.MTPROTO
            else -> null
        } ?: return null

        val host = payload["host"].orEmpty().trim()
        val port = payload["port"]?.toIntOrNull() ?: 0
        if (host.isBlank() || port !in 1..65535) return null
        val secret = payload["secret"]?.takeIf { it.isNotBlank() }
        if (type == ProxyType.MTPROTO && secret.isNullOrBlank()) return null

        return ProxyConfigSnapshot(
            enabled = true,
            type = type,
            host = host,
            port = port,
            username = payload["username"]?.takeIf { it.isNotBlank() },
            password = payload["password"]?.takeIf { it.isNotBlank() },
            secret = secret,
        )
    }

    private fun proxyToJson(proxy: ProxyConfigSnapshot): String {
        return JSONObject()
            .put("enabled", proxy.enabled)
            .put("type", proxy.type.name)
            .put("host", proxy.host)
            .put("port", proxy.port)
            .put("username", proxy.username ?: "")
            .put("password", proxy.password ?: "")
            .put("secret", proxy.secret ?: "")
            .toString()
    }

    private fun proxyFromJson(raw: String): ProxyConfigSnapshot {
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val type = runCatching { ProxyType.valueOf(root.optString("type", ProxyType.DIRECT.name)) }
            .getOrDefault(ProxyType.DIRECT)
        return ProxyConfigSnapshot(
            enabled = root.optBoolean("enabled", false),
            type = type,
            host = root.optString("host", ""),
            port = root.optInt("port", 0),
            username = root.optString("username", "").ifBlank { null },
            password = root.optString("password", "").ifBlank { null },
            secret = root.optString("secret", "").ifBlank { null },
        )
    }

    companion object {
        private const val KEY_PROXY_STATE = "proxy_state"
    }
}
