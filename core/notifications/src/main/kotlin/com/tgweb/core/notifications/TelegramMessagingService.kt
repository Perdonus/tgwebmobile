package com.tgweb.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TelegramMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        if (!AppRepositories.isInitialized()) return
        scope.launch {
            AppRepositories.notificationService.registerDeviceToken(token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val payload = remoteMessage.data
        if (payload.isNotEmpty()) {
            SyncScheduler.schedulePushSync(applicationContext, payload)
        }
    }
}
