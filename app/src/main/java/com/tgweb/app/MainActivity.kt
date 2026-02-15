package com.tgweb.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.webbridge.BridgeCommand
import com.tgweb.core.webbridge.BridgeCommandTypes
import com.tgweb.core.webbridge.BridgeEvent
import com.tgweb.core.webbridge.ProxyConfigSnapshot
import com.tgweb.core.webbridge.ProxyType
import com.tgweb.core.webbridge.WebBootstrapSnapshot
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppRepositories.postPushPermissionState(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)

        webView = WebView(this)
        setContentView(webView)

        configureWebView()
        attachBridgeSink()
        loadWebBundle()
        handleLaunchIntent(intent)

        AppRepositories.postPushPermissionState(isPushPermissionGranted())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onDestroy() {
        AppRepositories.webBridge.setWebEventSink(null)
        webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(false)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                AppRepositories.postNetworkState(isNetworkAvailable())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                injectBootstrap()
                applyProxyToWebView(AppRepositories.getProxyState())
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "tgweb" && uri.host == "auth") {
                    handleAuthHandoff(uri)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (!isNetworkAvailable() && request?.isForMainFrame == true) {
                    return WebResourceResponse("text/html", "UTF-8", assets.open("webapp/offline.html"))
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            AppRepositories.webBridge.onFromWeb(
                BridgeCommand(
                    type = BridgeCommandTypes.DOWNLOAD_MEDIA,
                    payload = mapOf(
                        "fileId" to url.hashCode().toString(),
                        "url" to url,
                        "mime" to (mimeType ?: "application/octet-stream"),
                        "name" to fileName,
                        "export" to "true",
                        "targetCollection" to "downloads",
                    ),
                )
            )
        }

        webView.addJavascriptInterface(
            AndroidBridgeJsApi { command ->
                when (command.type) {
                    BridgeCommandTypes.REQUEST_PUSH_PERMISSION -> {
                        requestNotificationPermission()
                    }
                    BridgeCommandTypes.SET_PROXY -> {
                        val proxyConfig = parseProxyConfig(command.payload)
                        if (proxyConfig == null) {
                            AppRepositories.postProxyState()
                        } else {
                            lifecycleScope.launch {
                                AppRepositories.updateProxyState(proxyConfig)
                                applyProxyToWebView(proxyConfig)
                            }
                        }
                    }
                    BridgeCommandTypes.GET_PROXY_STATUS -> {
                        AppRepositories.postProxyState()
                    }
                    else -> {
                        AppRepositories.webBridge.onFromWeb(command)
                    }
                }
            },
            JS_BRIDGE_NAME,
        )
    }

    private fun attachBridgeSink() {
        AppRepositories.webBridge.setWebEventSink { event ->
            runOnUiThread {
                emitBridgeEvent(event)
            }
        }
    }

    private fun loadWebBundle() {
        if (hasBundledWebK()) {
            webView.loadUrl(BUNDLED_WEBK_URL)
            return
        }
        webView.loadUrl(REMOTE_WEBK_URL)
    }

    private fun hasBundledWebK(): Boolean {
        return runCatching {
            assets.open("webapp/webk/index.html").use { }
            true
        }.getOrDefault(false)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val chatId = intent?.getLongExtra("chat_id", 0L) ?: 0L
        val messageId = intent?.getLongExtra("message_id", 0L) ?: 0L
        val preview = intent?.getStringExtra("preview").orEmpty()
        if (chatId > 0L) {
            AppRepositories.postPushMessageReceived(chatId = chatId, messageId = messageId, preview = preview)
        }
    }

    private fun handleAuthHandoff(uri: Uri) {
        val token = uri.getQueryParameter("token").orEmpty()
        if (token.isBlank()) return
        AppRepositories.postSyncState(lastSyncAt = System.currentTimeMillis(), unreadCount = 0)
    }

    private fun injectBootstrap() {
        lifecycleScope.launch {
            val snapshot = runCatching { AppRepositories.webBootstrapProvider() }
                .getOrElse {
                    Log.w(TAG, "Unable to load bootstrap snapshot", it)
                    WebBootstrapSnapshot()
                }

            val dialogsJson = JSONArray().apply {
                snapshot.dialogs.forEach { dialog ->
                    put(
                        JSONObject()
                            .put("chatId", dialog.chatId)
                            .put("title", dialog.title)
                            .put("lastMessagePreview", dialog.lastMessagePreview)
                            .put("unreadCount", dialog.unreadCount)
                            .put("lastMessageAt", dialog.lastMessageAt)
                    )
                }
            }

            val messagesJson = JSONArray().apply {
                snapshot.recentMessages.forEach { message ->
                    put(
                        JSONObject()
                            .put("messageId", message.messageId)
                            .put("chatId", message.chatId)
                            .put("text", message.text)
                            .put("status", message.status)
                            .put("createdAt", message.createdAt)
                    )
                }
            }

            val mediaJson = JSONArray().apply {
                snapshot.cachedMedia.forEach { media ->
                    put(
                        JSONObject()
                            .put("fileId", media.fileId)
                            .put("localPath", media.localPath)
                            .put("mimeType", media.mimeType)
                            .put("sizeBytes", media.sizeBytes)
                    )
                }
            }

            val proxyJson = JSONObject()
                .put("enabled", snapshot.proxyState.enabled)
                .put("type", snapshot.proxyState.type.name)
                .put("host", snapshot.proxyState.host)
                .put("port", snapshot.proxyState.port)
                .put("username", snapshot.proxyState.username ?: "")
                .put("secret", snapshot.proxyState.secret ?: "")

            val bootstrapJson = JSONObject()
                .put("dialogs", dialogsJson)
                .put("recentMessages", messagesJson)
                .put("unread", snapshot.unread)
                .put("cachedMedia", mediaJson)
                .put("lastSyncAt", snapshot.lastSyncAt)
                .put("proxyState", proxyJson)

            val quoted = JSONObject.quote(bootstrapJson.toString())
            val script =
                "window.__TGWEB_BOOTSTRAP__ = JSON.parse($quoted);" +
                    "window.dispatchEvent(new CustomEvent('tgweb-native-bootstrap', { detail: window.__TGWEB_BOOTSTRAP__ }));"

            webView.evaluateJavascript(script, null)
        }
    }

    private fun emitBridgeEvent(event: BridgeEvent) {
        val eventJson = JSONObject()
            .put("type", event.type)
            .put("payload", JSONObject(event.payload))
        val quoted = JSONObject.quote(eventJson.toString())
        val script =
            "window.dispatchEvent(new CustomEvent('tgweb-native', { detail: JSON.parse($quoted) }));"
        webView.evaluateJavascript(script, null)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AppRepositories.postPushPermissionState(true)
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            AppRepositories.postPushPermissionState(true)
            return
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isPushPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun parseProxyConfig(payload: Map<String, String>): ProxyConfigSnapshot? {
        val enabled = payload["enabled"].orEmpty().equals("true", ignoreCase = true)
        if (!enabled) {
            return ProxyConfigSnapshot(enabled = false, type = ProxyType.DIRECT)
        }

        val type = when (payload["type"]?.uppercase()) {
            ProxyType.HTTP.name -> ProxyType.HTTP
            ProxyType.SOCKS5.name -> ProxyType.SOCKS5
            ProxyType.MTPROTO.name -> ProxyType.MTPROTO
            else -> null
        } ?: return null

        val host = payload["host"].orEmpty().trim()
        val port = payload["port"]?.toIntOrNull() ?: 0
        if (host.isBlank() || port !in 1..65535) return null

        return ProxyConfigSnapshot(
            enabled = true,
            type = type,
            host = host,
            port = port,
            username = payload["username"]?.takeIf { it.isNotBlank() },
            password = payload["password"]?.takeIf { it.isNotBlank() },
            secret = payload["secret"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun applyProxyToWebView(proxyState: ProxyConfigSnapshot) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            AppRepositories.postProxyState(proxyState)
            return
        }
        val executor = ContextCompat.getMainExecutor(this)

        if (!proxyState.enabled || proxyState.type == ProxyType.DIRECT || proxyState.type == ProxyType.MTPROTO) {
            ProxyController.getInstance().clearProxyOverride(
                executor,
                {
                    AppRepositories.postProxyState(proxyState)
                },
            )
            return
        }

        val scheme = if (proxyState.type == ProxyType.SOCKS5) "socks" else "http"
        val proxyRule = "$scheme://${proxyState.host}:${proxyState.port}"
        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule(proxyRule)
            .build()

        ProxyController.getInstance().setProxyOverride(
            proxyConfig,
            executor,
            {
                AppRepositories.postProxyState(proxyState)
            },
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private class AndroidBridgeJsApi(
        private val commandConsumer: (BridgeCommand) -> Unit,
    ) {
        @JavascriptInterface
        fun send(payloadJson: String) {
            runCatching {
                val root = JSONObject(payloadJson)
                val type = root.optString("type")
                if (type.isBlank()) return

                val payload = mutableMapOf<String, String>()
                val payloadObj = root.optJSONObject("payload")
                if (payloadObj != null) {
                    val keys = payloadObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        payload[key] = payloadObj.opt(key)?.toString().orEmpty()
                    }
                }

                commandConsumer(BridgeCommand(type = type, payload = payload))
            }.onFailure {
                Log.w(TAG, "Malformed bridge command: $payloadJson", it)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val JS_BRIDGE_NAME = "AndroidBridge"
        private const val BUNDLED_WEBK_URL = "file:///android_asset/webapp/webk/index.html"
        private const val REMOTE_WEBK_URL = "https://web.telegram.org/k/"
    }
}
