package com.tgweb.app

import android.app.Application
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.ChatRepositoryImpl
import com.tgweb.core.db.TelegramDatabaseFactory
import com.tgweb.core.media.MediaCacheManager
import com.tgweb.core.media.MediaRepositoryImpl
import com.tgweb.core.notifications.AndroidNotificationService
import com.tgweb.core.sync.SyncScheduler
import com.tgweb.core.tdlib.StubTdLibGateway

class MainApplication : Application() {
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

        AppRepositories.chatRepository = chatRepository
        AppRepositories.mediaRepository = mediaRepository
        AppRepositories.notificationService = notificationService

        SyncScheduler.schedulePeriodic(this)
    }
}
