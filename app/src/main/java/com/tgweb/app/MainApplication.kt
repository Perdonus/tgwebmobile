package com.tgweb.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.ChatRepositoryImpl
import com.tgweb.core.db.TelegramDatabaseFactory
import com.tgweb.core.media.MediaCacheManager
import com.tgweb.core.media.MediaRepositoryImpl
import com.tgweb.core.notifications.AndroidNotificationService
import com.tgweb.core.sync.SyncScheduler
import com.tgweb.core.tdlib.StubTdLibGateway
import com.tgweb.core.webbridge.BridgeCommandTypes
import com.tgweb.core.webbridge.CachedMediaSnapshot
import com.tgweb.core.webbridge.WebBootstrapSnapshot
import com.tgweb.core.webbridge.WebBridgeBus
import com.tgweb.core.webbridge.WebDialogSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

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
                            AppRepositories.postDownloadProgress(fileId = fileId, percent = 100)
                        }
                    }
                    BridgeCommandTypes.DOWNLOAD_MEDIA -> {
                        val fileId = command.payload["fileId"].orEmpty()
                        if (fileId.isBlank()) return@launch

                        val export = command.payload["export"].orEmpty().equals("true", ignoreCase = true)
                        if (export) {
                            mediaRepository.downloadToPublicStorage(fileId, command.payload["targetCollection"] ?: "downloads")
                        } else {
                            mediaRepository.getMediaFile(fileId)
                        }
                    }
                }
            }
        }

        AppRepositories.chatRepository = chatRepository
        AppRepositories.mediaRepository = mediaRepository
        AppRepositories.notificationService = notificationService
        AppRepositories.webBridge = webBridge
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
            val lastSyncAt = database.syncStateDao().get("last_sync")?.updatedAt ?: 0L
            WebBootstrapSnapshot(
                dialogs = dialogs,
                unread = dialogs.sumOf { it.unreadCount },
                cachedMedia = cachedMedia,
                lastSyncAt = lastSyncAt,
            )
        }

        SyncScheduler.schedulePeriodic(this)
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
