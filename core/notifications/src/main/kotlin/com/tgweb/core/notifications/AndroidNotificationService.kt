package com.tgweb.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.MessageItem
import com.tgweb.core.data.NotificationService

class AndroidNotificationService(
    private val context: Context,
) : NotificationService {

    override suspend fun registerDeviceToken(fcmToken: String) {
        // Placeholder: send token to /v1/devices/register backend.
    }

    override suspend fun handlePush(payload: Map<String, String>) {
        val chatId = payload["chat_id"]?.toLongOrNull() ?: 0L
        val text = payload["text"].orEmpty()
        val messageId = payload["message_id"]?.toLongOrNull() ?: System.currentTimeMillis()
        if (chatId != 0L && text.isNotBlank()) {
            AppRepositories.postPushMessageReceived(chatId = chatId, messageId = messageId, preview = text)
            showMessageNotification(
                MessageItem(
                    messageId = messageId,
                    chatId = chatId,
                    senderUserId = 0L,
                    text = text,
                    status = "received",
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    override fun showMessageNotification(event: MessageItem) {
        ensureChannels()
        val intent = Intent(context, Class.forName("com.tgweb.app.MainActivity")).apply {
            putExtra("chat_id", event.chatId)
            putExtra("message_id", event.messageId)
            putExtra("preview", event.text)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            event.chatId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Chat ${event.chatId}")
            .setContentText(event.text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(event.chatId.toInt(), notification)
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_SILENT, "Silent", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannels(channels)
    }

    companion object {
        const val CHANNEL_MESSAGES = "tgweb_messages"
        const val CHANNEL_SILENT = "tgweb_silent"
        const val CHANNEL_DOWNLOADS = "tgweb_downloads"
    }
}
