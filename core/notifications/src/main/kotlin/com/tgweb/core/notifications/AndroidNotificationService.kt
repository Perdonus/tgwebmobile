package com.tgweb.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.security.NetworkSecurityPolicy
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore
import com.tgweb.core.data.MessageItem
import com.tgweb.core.data.NotificationService
import com.tgweb.core.data.PushDebugResult
import com.tgweb.core.webbridge.ProxyType
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume

class AndroidNotificationService(
    private val context: Context,
) : NotificationService {

    private val deviceId: String by lazy {
        val secureId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        secureId ?: UUID.randomUUID().toString()
    }

    private val backendBaseUrls: List<String> by lazy {
        val override = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_BASE_URL, null)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
        val urls = linkedSetOf<String>()
        if (override.isNotBlank()) {
            urls += override
        } else {
            urls += BuildConfig.DEFAULT_PUSH_BACKEND_URL.trim().trimEnd('/')
            val fallback = BuildConfig.DEFAULT_PUSH_BACKEND_FALLBACK_URL.trim().trimEnd('/')
            if (fallback.isNotBlank()) {
                urls += fallback
            }
        }
        urls.filter { it.isNotBlank() }
    }

    override suspend fun registerDeviceToken(fcmToken: String) {
        if (backendBaseUrls.isEmpty()) return
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("unknown")
        val body = JSONObject()
            .put("userId", 0)
            .put("deviceId", deviceId)
            .put("fcmToken", fcmToken)
            .put("appVersion", appVersion)
            .put("locale", context.resources.configuration.locales[0].toLanguageTag())
            .put("capabilities", listOf("webk", "offline_r1", "proxy", "background_push"))
            .toString()

        DebugLogStore.log(
            "PUSH_REG",
            "Register device token requested: deviceId=$deviceId tokenPrefix=${fcmToken.take(16)}... backendCount=${backendBaseUrls.size}",
        )
        postJson(path = "/v1/devices/register", body = body)
    }

    override suspend fun handlePush(payload: Map<String, String>) {
        DebugLogStore.log("PUSH_RECV", "Incoming FCM payload keys=${payload.keys.joinToString(",")}")
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

    override suspend fun sendServerTestPush(): PushDebugResult {
        if (backendBaseUrls.isEmpty()) {
            return PushDebugResult(
                success = false,
                title = "Backend URL is not configured",
                details = "No backend base URLs are available.",
            )
        }

        val logs = StringBuilder()
        fun append(line: String) {
            logs.appendLine(line)
            DebugLogStore.log("PUSH_TEST", line)
        }

        append("Start server-side push test")
        append("Device: $deviceId")
        append("Backends: ${backendBaseUrls.joinToString(", ")}")
        append("Proxy: ${proxyDebugSummary(AppRepositories.getProxyState())}")

        val fcmToken = fetchFcmTokenOrNull()
        if (!fcmToken.isNullOrBlank()) {
            append("FCM token acquired: ${fcmToken.take(20)}...")
            runCatching { registerDeviceToken(fcmToken) }
                .onSuccess { append("Device re-registration: OK") }
                .onFailure { append("Device re-registration failed: ${it.message}") }
        } else {
            append("FCM token unavailable. Sending by known deviceId only.")
            append("Note: until Firebase token exists on device, real push delivery is impossible.")
        }

        val messageId = System.currentTimeMillis().toString()
        val preview = "FlyGram debug push ${UUID.randomUUID().toString().take(8)}"
        val body = JSONObject()
            .put("deviceIds", org.json.JSONArray().put(deviceId))
            .put("priority", "high")
            .put(
                "data",
                JSONObject()
                    .put("chat_id", "777000")
                    .put("message_id", messageId)
                    .put("text", preview),
            )
            .toString()
        append("Request body prepared: message_id=$messageId")

        val trace = postJsonWithTrace(path = "/v1/push/send", body = body, tag = "PUSH_TEST")
        append("Final result: success=${trace.success} url=${trace.url}")
        append("HTTP code: ${trace.code ?: -1}")
        if (!trace.responseBody.isNullOrBlank()) {
            append("Response body: ${trace.responseBody}")
        }
        if (!trace.error.isNullOrBlank()) {
            append("Error: ${trace.error}")
        }
        append("Done")

        return PushDebugResult(
            success = trace.success,
            title = if (trace.success) "Server push request sent" else "Server push request failed",
            details = logs.toString().trim(),
        )
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
            .setColor(getNotificationColor())
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(event.chatId.toInt(), notification)
    }

    private suspend fun sendAck(messageId: String) {
        if (backendBaseUrls.isEmpty()) return
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("messageId", messageId)
            .put("deliveredAtEpochMs", System.currentTimeMillis())
            .toString()
        postJson(path = "/v1/push/ack", body = body)
    }

    private suspend fun postJson(path: String, body: String) {
        postJsonWithTrace(path = path, body = body, tag = "PUSH_HTTP")
    }

    private suspend fun postJsonWithTrace(path: String, body: String, tag: String): PostTrace {
        var lastTrace = PostTrace(success = false, url = "", code = null, responseBody = "", error = "No backend URLs")
        val proxyState = AppRepositories.getProxyState()
        for (base in backendBaseUrls) {
            val fullUrl = "$base$path"
            val startedAt = System.currentTimeMillis()
            val scheme = runCatching { URL(fullUrl).protocol.lowercase() }.getOrDefault("")
            val host = runCatching { URL(fullUrl).host }.getOrDefault("")
            if (scheme == "http" && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) {
                val reason = "Cleartext HTTP traffic to $host is not permitted by app network policy"
                DebugLogStore.log(tag, "Skip $fullUrl: $reason")
                continue
            }
            val trace = runCatching {
                val connection = openConnection(fullUrl, proxyState)
                connection.requestMethod = "POST"
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty(HEADER_APP_ID, HEADER_APP_ID_VALUE)
                val secret = BuildConfig.PUSH_SHARED_SECRET.trim()
                val hasSecret = secret.isNotBlank()
                if (hasSecret) {
                    connection.setRequestProperty(HEADER_SHARED_SECRET, secret)
                }
                connection.doOutput = true
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(body)
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = runCatching {
                    stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
                connection.disconnect()
                val elapsed = System.currentTimeMillis() - startedAt
                DebugLogStore.log(
                    tag,
                    "POST $fullUrl -> code=$code ${elapsed}ms headers(app=$HEADER_APP_ID_VALUE,auth=${if (hasSecret) "on" else "off"}) proxy=${proxyDebugSummary(proxyState)}",
                )
                PostTrace(
                    success = code in 200..299,
                    url = fullUrl,
                    code = code,
                    responseBody = response.take(1200),
                    error = if (code in 200..299) null else "HTTP $code",
                )
            }.getOrElse { error ->
                val elapsed = System.currentTimeMillis() - startedAt
                DebugLogStore.log(
                    tag,
                    "POST $fullUrl failed in ${elapsed}ms: ${error::class.java.simpleName}: ${error.message} proxy=${proxyDebugSummary(proxyState)}",
                )
                PostTrace(
                    success = false,
                    url = fullUrl,
                    code = null,
                    responseBody = "",
                    error = "${error::class.java.simpleName}: ${error.message}",
                )
            }
            lastTrace = trace
            if (trace.success) return trace
        }
        return lastTrace
    }

    private fun openConnection(url: String, proxyState: com.tgweb.core.webbridge.ProxyConfigSnapshot): HttpURLConnection {
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

    private fun proxyDebugSummary(proxyState: com.tgweb.core.webbridge.ProxyConfigSnapshot): String {
        if (!proxyState.enabled || proxyState.type == ProxyType.DIRECT) return "direct"
        val hasAuth = !proxyState.username.isNullOrBlank() || !proxyState.password.isNullOrBlank()
        val hasSecret = !proxyState.secret.isNullOrBlank()
        return "${proxyState.type.name.lowercase()}://${proxyState.host}:${proxyState.port} auth=$hasAuth mtprotoSecret=$hasSecret"
    }

    private suspend fun fetchFcmTokenOrNull(): String? {
        return suspendCancellableCoroutine { continuation ->
            runCatching {
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (!continuation.isCompleted) continuation.resume(token)
                    }
                    .addOnFailureListener {
                        if (!continuation.isCompleted) continuation.resume(null)
                    }
            }.onFailure {
                if (!continuation.isCompleted) continuation.resume(null)
            }
        }
    }

    private fun getNotificationColor(): Int {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NOTIFICATION_COLOR, "#3390EC")
            .orEmpty()
        return runCatching { Color.parseColor(raw) }.getOrDefault(Color.parseColor("#3390EC"))
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CHANNEL_MESSAGES, "FlyGram Messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_SILENT, "FlyGram Silent", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_DOWNLOADS, "FlyGram Downloads", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannels(channels)
    }

    companion object {
        private const val PREFS = "tgweb_runtime"
        // Hidden runtime override key (no user-facing setting).
        private const val KEY_BACKEND_BASE_URL = "push_backend_url"
        private const val HEADER_SHARED_SECRET = "X-FlyGram-Key"
        private const val HEADER_APP_ID = "X-FlyGram-App"
        private const val HEADER_APP_ID_VALUE = "android"
        private const val KEY_NOTIFICATION_COLOR = "notification_color"
        const val CHANNEL_MESSAGES = "flygram_messages"
        const val CHANNEL_SILENT = "flygram_silent"
        const val CHANNEL_DOWNLOADS = "flygram_downloads"
    }

    private data class PostTrace(
        val success: Boolean,
        val url: String,
        val code: Int?,
        val responseBody: String,
        val error: String?,
    )
}
