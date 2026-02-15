package com.tgweb.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.MessageItem
import com.tgweb.core.data.NotificationService
import com.tgweb.core.webbridge.ProxyType
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.UUID

class AndroidNotificationService(
    private val context: Context,
) : NotificationService {

    private val deviceId: String by lazy {
        val secureId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        secureId ?: UUID.randomUUID().toString()
    }

    private val backendBaseUrl: String by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_BASE_URL, null)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
    }

    override suspend fun registerDeviceToken(fcmToken: String) {
        if (backendBaseUrl.isBlank()) return
        val body = JSONObject()
            .put("userId", 0)
            .put("deviceId", deviceId)
            .put("fcmToken", fcmToken)
            .put("appVersion", "0.1.0")
            .put("locale", context.resources.configuration.locales[0].toLanguageTag())
            .put("capabilities", listOf("webk", "offline_r1", "proxy", "background_push"))
            .toString()

        postJson("$backendBaseUrl/v1/devices/register", body)
    }

    override suspend fun handlePush(payload: Map<String, String>) {
        val chatId = payload["chat_id"]?.toLongOrNull() ?: 0L
        val text = payload["text"].orEmpty()
        val messageId = payload["message_id"]?.toLongOrNull() ?: System.currentTimeMillis()
        if (chatId != 0L && text.isNotBlank()) {
            AppRepositories.chatRepository.ingestPushMessage(chatId = chatId, messageId = messageId, preview = text)
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
            sendAck(messageId = messageId.toString())
        }
    }

    override fun showMessageNotification(event: MessageItem) {
        ensureChannels()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

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

    private suspend fun sendAck(messageId: String) {
        if (backendBaseUrl.isBlank()) return
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("messageId", messageId)
            .put("deliveredAtEpochMs", System.currentTimeMillis())
            .toString()
        postJson("$backendBaseUrl/v1/push/ack", body)
    }

    private suspend fun postJson(url: String, body: String) {
        runCatching {
            val connection = openConnection(url)
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(body)
            }
            connection.inputStream.close()
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val proxyState = AppRepositories.getProxyState()
        val proxy = when {
            !proxyState.enabled -> Proxy.NO_PROXY
            proxyState.type == ProxyType.HTTP -> {
                Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyState.host, proxyState.port))
            }
            proxyState.type == ProxyType.SOCKS5 -> {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyState.host, proxyState.port))
            }
            else -> Proxy.NO_PROXY
        }
        return URL(url).openConnection(proxy) as HttpURLConnection
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
        private const val PREFS = "tgweb_runtime"
        private const val KEY_BACKEND_BASE_URL = "push_backend_url"
        const val CHANNEL_MESSAGES = "tgweb_messages"
        const val CHANNEL_SILENT = "tgweb_silent"
        const val CHANNEL_DOWNLOADS = "tgweb_downloads"
    }
}
