package com.tgweb.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore
import com.tgweb.core.data.MessageItem
import com.tgweb.core.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TelegramMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        DebugLogStore.log("PUSH_RECV", "onNewToken received: prefix=${token.take(16)}...")
        if (!AppRepositories.isInitialized()) return
        scope.launch {
            runCatching { AppRepositories.notificationService.registerDeviceToken(token) }
                .onFailure {
                    DebugLogStore.log(
                        "PUSH_RECV",
                        "registerDeviceToken failed: ${it::class.java.simpleName}: ${it.message}",
                    )
                }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val payload = remoteMessage.data
        val notificationTitle = remoteMessage.notification?.title.orEmpty()
        val notificationBody = remoteMessage.notification?.body.orEmpty()
        DebugLogStore.log(
            "PUSH_RECV",
            "onMessageReceived dataKeys=${payload.keys.joinToString(",")} title=${notificationTitle.take(48)} body=${notificationBody.take(64)}",
        )

        if (payload.isEmpty() && notificationBody.isBlank() && notificationTitle.isBlank()) {
            return
        }

        scope.launch {
            if (AppRepositories.isInitialized()) {
                if (payload.isNotEmpty()) {
                    runCatching { AppRepositories.notificationService.handlePush(payload) }
                        .onFailure {
                            DebugLogStore.log(
                                "PUSH_RECV",
                                "handlePush failed: ${it::class.java.simpleName}: ${it.message}",
                            )
                            showFallbackNotification(payload, notificationTitle, notificationBody)
                        }
                } else {
                    showFallbackNotification(payload, notificationTitle, notificationBody)
                }
                // Reconcile unread/dialog state without re-triggering duplicate local notify.
                SyncScheduler.schedulePushSync(applicationContext, emptyMap())
            } else {
                DebugLogStore.log("PUSH_RECV", "AppRepositories not initialized yet, using fallback local notification")
                showFallbackNotification(payload, notificationTitle, notificationBody)
                // Keep original payload so worker can ingest it later.
                SyncScheduler.schedulePushSync(applicationContext, payload)
            }
        }
    }

    private fun showFallbackNotification(
        payload: Map<String, String>,
        notificationTitle: String,
        notificationBody: String,
    ) {
        val body = payload["text"].orEmpty()
            .ifBlank { payload["message"].orEmpty() }
            .ifBlank { payload["body"].orEmpty() }
            .ifBlank { notificationBody }
            .ifBlank { notificationTitle }
        if (body.isBlank()) return

        val chatId = payload["chat_id"]?.toLongOrNull() ?: 777000L
        val messageId = payload["message_id"]?.toLongOrNull() ?: System.currentTimeMillis()
        val notificationService = AndroidNotificationService(applicationContext)
        notificationService.ensureChannels()
        notificationService.showMessageNotification(
            MessageItem(
                messageId = messageId,
                chatId = chatId,
                senderUserId = 0L,
                text = body,
                status = "received",
                createdAt = System.currentTimeMillis(),
            ),
        )
    }
}
