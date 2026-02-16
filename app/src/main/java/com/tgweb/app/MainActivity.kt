package com.tgweb.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView

    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var loadingJob: Job? = null
    private var startedAtElapsedMs: Long = 0L
    private val offlineCacheFile by lazy { File(cacheDir, OFFLINE_CACHE_FILE_NAME) }
    private val offlineArchiveFile by lazy { File(cacheDir, OFFLINE_ARCHIVE_FILE_NAME) }

    private val runtimePrefs by lazy {
        getSharedPreferences(KeepAliveService.PREFS, Context.MODE_PRIVATE)
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser ?: return@registerForActivityResult
            pendingFileChooser = null

            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            if (uris != null) {
                callback.onReceiveValue(uris)
                return@registerForActivityResult
            }

            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val singleUri = data?.data
                val clip = data?.clipData
                val list = mutableListOf<Uri>()
                if (singleUri != null) {
                    list += singleUri
                }
                if (clip != null) {
                    for (index in 0 until clip.itemCount) {
                        clip.getItemAt(index)?.uri?.let(list::add)
                    }
                }
                callback.onReceiveValue(if (list.isEmpty()) null else list.toTypedArray())
            } else {
                callback.onReceiveValue(null)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppRepositories.postPushPermissionState(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedAtElapsedMs = SystemClock.elapsedRealtime()

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)

        initViews()
        configureWebView()
        attachBridgeSink()
        applyPersistedSystemUi()
        loadWebBundle()
        handleLaunchIntent(intent)
        bindBackPressedBehavior()

        AppRepositories.postPushPermissionState(isPushPermissionGranted())
        AppRepositories.postKeepAliveState(KeepAliveService.isEnabled(this))
        AppRepositories.postInterfaceScaleState(currentScalePercent())
        maybeRequestPushPermissionOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyInterfaceScale(currentScalePercent().toString())
        webView.settings.cacheMode = if (isNetworkAvailable()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        lifecycleScope.launch {
            applyProxyToWebView(AppRepositories.getProxyState())
            AppRepositories.postProxyState()
            AppRepositories.postKeepAliveState(KeepAliveService.isEnabled(this@MainActivity))
        }
    }

    override fun onDestroy() {
        loadingJob?.cancel()
        AppRepositories.webBridge.setWebEventSink(null)
        webView.removeJavascriptInterface(JS_BRIDGE_NAME)
        webView.destroy()
        super.onDestroy()
    }

    private fun initViews() {
        rootLayout = FrameLayout(this)
        webView = WebView(this)
        loadingOverlay = createLoadingOverlay()

        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        rootLayout.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(rootLayout)
    }

    private fun createLoadingOverlay(): View {
        val container = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0E1621"))
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val title = TextView(this).apply {
            text = "Telegram Web"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 8)
        }
        loadingText = TextView(this).apply {
            text = "Loading."
            textSize = 14f
            setTextColor(Color.parseColor("#A7B8CE"))
            gravity = Gravity.CENTER
        }

        container.addView(spinner)
        container.addView(title)
        container.addView(loadingText)
        return container
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
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = if (isNetworkAvailable()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null) return false
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback

                val chooserIntent = runCatching {
                    fileChooserParams?.createIntent()?.apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                    } ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }.getOrNull()

                if (chooserIntent == null) {
                    pendingFileChooser = null
                    return false
                }

                return runCatching {
                    filePickerLauncher.launch(Intent.createChooser(chooserIntent, "Select file"))
                    true
                }.getOrElse {
                    pendingFileChooser = null
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                startedAtElapsedMs = SystemClock.elapsedRealtime()
                AppRepositories.postNetworkState(isNetworkAvailable())
                view?.settings?.cacheMode = if (isNetworkAvailable()) {
                    WebSettings.LOAD_DEFAULT
                } else {
                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
                showLoadingOverlay()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cacheCurrentMainFrameHtml(url)
                injectBootstrap()
                injectMobileEnhancements()
                applyProxyToWebView(AppRepositories.getProxyState())
                hideLoadingOverlayWithMinimumDuration()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "tgweb" && uri.host == "auth") {
                    handleAuthHandoff(uri)
                    return true
                }
                if (ProxyLinkParser.parse(uri) != null) {
                    handleIncomingProxyUri(uri, showFeedback = true)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return super.shouldInterceptRequest(view, request)
                if (!request.isForMainFrame) return super.shouldInterceptRequest(view, request)

                if (!isNetworkAvailable() && (url.scheme == "http" || url.scheme == "https")) {
                    return buildOfflineResponseFromCache()
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
                        "export" to "false",
                        "targetCollection" to "tgweb_downloads",
                    ),
                ),
            )
        }

        webView.addJavascriptInterface(
            AndroidBridgeJsApi { command ->
                when (command.type) {
                    BridgeCommandTypes.REQUEST_PUSH_PERMISSION -> requestNotificationPermission()
                    BridgeCommandTypes.OPEN_MOD_SETTINGS -> openModSettings()
                    BridgeCommandTypes.SET_PROXY -> handleProxyCommand(command)
                    BridgeCommandTypes.GET_PROXY_STATUS -> AppRepositories.postProxyState()
                    BridgeCommandTypes.SET_SYSTEM_UI_STYLE -> applySystemUiStyle(command.payload)
                    BridgeCommandTypes.SET_INTERFACE_SCALE -> applyInterfaceScale(command.payload["scalePercent"])
                    BridgeCommandTypes.SET_KEEP_ALIVE -> setKeepAliveEnabled(command.payload["enabled"])
                    BridgeCommandTypes.GET_KEEP_ALIVE_STATE -> AppRepositories.postKeepAliveState(KeepAliveService.isEnabled(this))
                    BridgeCommandTypes.OPEN_DOWNLOADS -> openDownloadsManager()
                    else -> AppRepositories.webBridge.onFromWeb(command)
                }
            },
            JS_BRIDGE_NAME,
        )
    }

    private fun attachBridgeSink() {
        AppRepositories.webBridge.setWebEventSink { event ->
            runOnUiThread { emitBridgeEvent(event) }
        }
    }

    private fun bindBackPressedBehavior() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        moveTaskToBack(true)
                    }
                }
            },
        )
    }

    private fun loadWebBundle() {
        val bundledUrl = resolveBundledWebKUrl()
        if (!isNetworkAvailable() && bundledUrl != null) {
            webView.loadUrl(bundledUrl)
            return
        }
        if (bundledUrl != null && runtimePrefs.getBoolean(KeepAliveService.KEY_USE_BUNDLED_WEBK, true)) {
            webView.loadUrl(bundledUrl)
            return
        }
        if (!isNetworkAvailable()) {
            if (offlineArchiveFile.exists()) {
                webView.loadUrl("file://${offlineArchiveFile.absolutePath}")
                return
            }
            webView.loadUrl(REMOTE_WEBK_URL)
            return
        }
        webView.loadUrl(REMOTE_WEBK_URL)
    }

    private fun resolveBundledWebKUrl(): String? {
        val candidates = listOf(
            "webapp/webk/index.html" to BUNDLED_WEBK_URL,
            "webapp/webk-src/public/index.html" to BUNDLED_WEBK_SRC_PUBLIC_URL,
        )
        for ((assetPath, assetUrl) in candidates) {
            val exists = runCatching {
                assets.open(assetPath).use { }
                true
            }.getOrDefault(false)
            if (exists) return assetUrl
        }
        return null
    }

    private fun maybeRequestPushPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isPushPermissionGranted()) return

        val prompted = runtimePrefs.getBoolean(KeepAliveService.KEY_PUSH_PERMISSION_PROMPTED, false)
        if (prompted) return

        runtimePrefs.edit().putBoolean(KeepAliveService.KEY_PUSH_PERMISSION_PROMPTED, true).apply()
        requestNotificationPermission()
    }

    private fun applyPersistedSystemUi() {
        val statusRaw = runtimePrefs.getString(KeepAliveService.KEY_STATUS_BAR_COLOR, "#0E1621").orEmpty()
        val navRaw = runtimePrefs.getString(KeepAliveService.KEY_NAV_BAR_COLOR, statusRaw).orEmpty()
        val statusColor = runCatching { Color.parseColor(statusRaw) }.getOrDefault(Color.parseColor("#0E1621"))
        val navColor = runCatching { Color.parseColor(navRaw) }.getOrDefault(statusColor)
        window.statusBarColor = statusColor
        window.navigationBarColor = navColor
    }

    private fun colorToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    private fun showLoadingOverlay() {
        loadingOverlay.visibility = View.VISIBLE
        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch {
            val frames = listOf("Loading.", "Loading..", "Loading...")
            var index = 0
            while (true) {
                loadingText.text = frames[index % frames.size]
                index++
                delay(380)
            }
        }
    }

    private fun hideLoadingOverlayWithMinimumDuration(minDurationMs: Long = 700L) {
        lifecycleScope.launch {
            val elapsed = SystemClock.elapsedRealtime() - startedAtElapsedMs
            if (elapsed < minDurationMs) delay(minDurationMs - elapsed)
            loadingJob?.cancel()
            loadingOverlay.visibility = View.GONE
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val dataUri = intent?.data
        if (dataUri != null && handleIncomingProxyUri(dataUri, showFeedback = true)) {
            return
        }

        val chatId = intent?.getLongExtra("chat_id", 0L) ?: 0L
        val messageId = intent?.getLongExtra("message_id", 0L) ?: 0L
        val preview = intent?.getStringExtra("preview").orEmpty()
        if (chatId > 0L) {
            AppRepositories.postPushMessageReceived(chatId = chatId, messageId = messageId, preview = preview)
        }
    }

    private fun handleIncomingProxyUri(uri: Uri, showFeedback: Boolean): Boolean {
        val parsed = ProxyLinkParser.parse(uri) ?: return false
        lifecycleScope.launch {
            AppRepositories.updateProxyState(parsed)
            applyProxyToWebView(parsed)
            if (showFeedback) {
                val typeTitle = when (parsed.type) {
                    ProxyType.HTTP -> "HTTP"
                    ProxyType.SOCKS5 -> "SOCKS5"
                    ProxyType.MTPROTO -> "MTProto"
                    ProxyType.DIRECT -> "DIRECT"
                }
                Toast.makeText(
                    this@MainActivity,
                    "Proxy applied: $typeTitle ${parsed.host}:${parsed.port}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        return true
    }

    private fun openModSettings() {
        startActivity(Intent(this, ModSettingsActivity::class.java))
    }

    private fun openDownloadsManager() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    private fun cacheCurrentMainFrameHtml(url: String?) {
        val currentUrl = url.orEmpty()
        if (!currentUrl.startsWith("http")) return
        if (!isNetworkAvailable()) return

        runCatching {
            offlineArchiveFile.parentFile?.mkdirs()
            webView.saveWebArchive(offlineArchiveFile.absolutePath, false) { savedPath ->
                if (savedPath.isNullOrBlank()) {
                    Log.w(TAG, "Web archive path is empty")
                }
            }
        }.onFailure {
            Log.w(TAG, "Unable to save web archive", it)
        }

        webView.evaluateJavascript(
            "(function(){return document.documentElement.outerHTML || '';})();",
        ) { raw ->
            val html = decodeEvaluateJavascriptResult(raw) ?: return@evaluateJavascript
            if (html.length < 2048) return@evaluateJavascript
            runCatching {
                offlineCacheFile.parentFile?.mkdirs()
                offlineCacheFile.writeText(html)
            }.onFailure {
                Log.w(TAG, "Unable to cache offline HTML", it)
            }
        }
    }

    private fun decodeEvaluateJavascriptResult(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrNull()
    }

    private fun buildOfflineResponseFromCache(): WebResourceResponse? {
        if (!offlineCacheFile.exists()) return null
        val bytes = runCatching { offlineCacheFile.readBytes() }.getOrNull() ?: return null
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(bytes),
        )
    }

    private fun handleAuthHandoff(uri: Uri) {
        val token = uri.getQueryParameter("token").orEmpty()
        if (token.isBlank()) return
        AppRepositories.postSyncState(lastSyncAt = System.currentTimeMillis(), unreadCount = 0)
    }

    private fun handleProxyCommand(command: BridgeCommand) {
        val proxyConfig = parseProxyConfig(command.payload)
        if (proxyConfig == null) {
            AppRepositories.postProxyState()
            return
        }
        lifecycleScope.launch {
            AppRepositories.updateProxyState(proxyConfig)
            applyProxyToWebView(proxyConfig)
        }
    }

    private fun applyInterfaceScale(rawScale: String?) {
        val scalePercent = rawScale?.toIntOrNull()?.coerceIn(75, 140) ?: return
        runtimePrefs.edit().putInt(KeepAliveService.KEY_UI_SCALE_PERCENT, scalePercent).apply()
        AppRepositories.postInterfaceScaleState(scalePercent)
        injectScale(scalePercent)
    }

    private fun currentScalePercent(): Int {
        return runtimePrefs.getInt(KeepAliveService.KEY_UI_SCALE_PERCENT, 100).coerceIn(75, 140)
    }

    private fun injectScale(scalePercent: Int) {
        val script = """
            (function() {
              try {
                var scale = Math.max(0.75, Math.min(1.4, ${scalePercent} / 100));
                document.documentElement.style.setProperty('--tgweb-mobile-scale', String(scale));
                if (document.body) {
                  document.body.style.zoom = String(scale);
                }
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun setKeepAliveEnabled(rawEnabled: String?) {
        val enabled = rawEnabled?.equals("true", ignoreCase = true) == true
        KeepAliveService.setEnabled(this, enabled)
        if (enabled) {
            KeepAliveService.start(this)
        } else {
            KeepAliveService.stop(this)
        }
        AppRepositories.postKeepAliveState(enabled)
    }

    private fun applySystemUiStyle(payload: Map<String, String>) {
        val statusColor = parseHexColor(payload["statusBarColor"]) ?: Color.parseColor("#0E1621")
        val navColor = parseHexColor(payload["navBarColor"]) ?: statusColor
        val lightStatusIcons = payload["lightStatusIcons"].toBooleanSafe(defaultValue = false)
        val lightNavIcons = payload["lightNavIcons"].toBooleanSafe(defaultValue = false)

        runtimePrefs.edit()
            .putString(KeepAliveService.KEY_STATUS_BAR_COLOR, colorToHex(statusColor))
            .putString(KeepAliveService.KEY_NAV_BAR_COLOR, colorToHex(navColor))
            .putString(KeepAliveService.KEY_NOTIFICATION_COLOR, colorToHex(statusColor))
            .apply()

        window.statusBarColor = statusColor
        window.navigationBarColor = navColor
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightStatusIcons
            isAppearanceLightNavigationBars = lightNavIcons
        }
    }

    private fun parseHexColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Color.parseColor(raw) }.getOrNull()
    }

    private fun String?.toBooleanSafe(defaultValue: Boolean): Boolean {
        return when (this?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> defaultValue
        }
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
                            .put("lastMessageAt", dialog.lastMessageAt),
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
                            .put("createdAt", message.createdAt),
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
                            .put("sizeBytes", media.sizeBytes),
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

    private fun injectMobileEnhancements() {
        val scale = currentScalePercent()
        val script = """
            (function() {
              if (window.__tgwebMobileEnhancerInstalled) { return; }
              window.__tgwebMobileEnhancerInstalled = true;

              var send = function(type, payload) {
                try {
                  if (window.AndroidBridge && window.AndroidBridge.send) {
                    window.AndroidBridge.send(JSON.stringify({ type: type, payload: payload || {} }));
                  }
                } catch (e) {}
              };

              var clamp = function(v, min, max) { return Math.max(min, Math.min(max, v)); };
              var applyScale = function(value) {
                var p = clamp(Number(value) || 100, 75, 140);
                var zoom = p / 100;
                document.documentElement.style.setProperty('--tgweb-mobile-scale', String(zoom));
                if (document.body) {
                  document.body.style.zoom = String(zoom);
                }
                return p;
              };

              var colorFromCss = function(cssColor) {
                var m = String(cssColor || '').match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i);
                if (!m) { return '#0e1621'; }
                var r = Number(m[1]), g = Number(m[2]), b = Number(m[3]);
                var toHex = function(v) { return Math.max(0, Math.min(255, v)).toString(16).padStart(2, '0'); };
                return '#' + toHex(r) + toHex(g) + toHex(b);
              };

              var luminance = function(hex) {
                var c = String(hex || '').replace('#', '');
                if (c.length !== 6) { return 0; }
                var r = parseInt(c.slice(0, 2), 16);
                var g = parseInt(c.slice(2, 4), 16);
                var b = parseInt(c.slice(4, 6), 16);
                return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
              };

              var syncSystemBars = function() {
                var styles = getComputedStyle(document.body || document.documentElement);
                var bg = colorFromCss(styles.backgroundColor || '#0e1621');
                var isLight = luminance(bg) > 0.62;
                send('${BridgeCommandTypes.SET_SYSTEM_UI_STYLE}', {
                  statusBarColor: bg,
                  navBarColor: bg,
                  lightStatusIcons: String(isLight),
                  lightNavIcons: String(isLight)
                });
              };

              var switchRegexes = [
                /switch\s+to\s+a\s+version/i,
                /switch\s+to\s+version\s+a/i,
                /переключиться\s+на\s+версию\s*a/i,
                /cambiar.*versi[oó]n\s*a/i,
                /changer.*version\s*a/i
              ];
              var hasSwitchText = function(text) {
                var value = String(text || '').trim();
                if (!value || value.length > 128) { return false; }
                for (var i = 0; i < switchRegexes.length; i++) {
                  if (switchRegexes[i].test(value)) { return true; }
                }
                return false;
              };
              var hideNode = function(el) {
                if (!el || !el.style) { return; }
                el.style.display = 'none';
                el.style.visibility = 'hidden';
              };
              var removeSwitchLinks = function() {
                document.querySelectorAll('a[href*="web.telegram.org/a"],a[href*="/a/"],a[href*="/a?"]').forEach(function(node) {
                  hideNode(node.closest('.row,li,div') || node);
                });
                document.querySelectorAll('a,button,span,div').forEach(function(node) {
                  if (hasSwitchText(node.textContent || '')) {
                    hideNode(node.closest('.row,li,div') || node);
                  }
                });
              };

              var ensureModSettingsEntry = function() {
                var buttonsRoot = document.querySelector('.settings-container .profile-buttons');
                if (!buttonsRoot) { return; }
                if (buttonsRoot.querySelector('.tgweb-mod-settings-row')) { return; }

                var row = document.createElement('div');
                row.className = 'row no-subtitle row-with-icon row-with-padding tgweb-mod-settings-row';

                var icon = document.createElement('span');
                icon.className = 'tgico tgico-settings row-icon';

                var title = document.createElement('div');
                title.className = 'row-title';
                title.textContent = 'Настройки мода';

                var right = document.createElement('div');
                right.className = 'row-title row-title-right row-title-right-secondary';
                right.textContent = 'Android';

                row.append(icon, title, right);
                row.addEventListener('click', function(e) {
                  e.preventDefault();
                  e.stopPropagation();
                  send('${BridgeCommandTypes.OPEN_MOD_SETTINGS}', {});
                }, { passive: false });

                buttonsRoot.appendChild(row);
              };

              var ensureDownloadsButton = function() {
                var header = document.querySelector('.left-header, .Topbar, .topbar');
                if (!header) { return; }
                if (header.querySelector('.tgweb-downloads-button')) { return; }

                var button = document.createElement('button');
                button.className = 'btn-icon tgweb-downloads-button';
                button.type = 'button';
                button.setAttribute('aria-label', 'Downloads');
                button.textContent = '⬇';
                button.style.marginInlineStart = '6px';
                button.style.fontSize = '18px';
                button.style.lineHeight = '18px';
                button.style.width = '34px';
                button.style.height = '34px';
                button.style.border = '0';
                button.style.borderRadius = '10px';
                button.style.background = 'transparent';
                button.style.color = 'inherit';
                button.style.cursor = 'pointer';
                button.addEventListener('click', function(e) {
                  e.preventDefault();
                  e.stopPropagation();
                  send('${BridgeCommandTypes.OPEN_DOWNLOADS}', {});
                }, { passive: false });
                header.appendChild(button);
              };

              var isInteractiveElement = function(el) {
                if (!el || !el.closest) { return false; }
                return !!el.closest('a,button,input,textarea,select,label,[role=\"button\"],[contenteditable=\"true\"]');
              };

              var findBubbleForReaction = function(node) {
                var el = node;
                if (el && el.nodeType !== 1) {
                  el = el.parentElement;
                }
                if (!el || !el.closest) { return null; }
                return el.closest('.bubble,[data-mid]');
              };

              var findChatCloseButton = function() {
                return document.querySelector(
                  '.topbar .sidebar-close-button:not(.hide), ' +
                  '.chat-info-container .sidebar-close-button:not(.hide), ' +
                  '.sidebar-header .sidebar-close-button:not(.hide)'
                );
              };

              var closeActiveChatIfPossible = function() {
                var btn = findChatCloseButton();
                if (btn && typeof btn.click === 'function') {
                  btn.click();
                  return true;
                }
                return false;
              };

              var hasOpenDialog = function() {
                return !!findChatCloseButton();
              };

              var isMediaViewerOpen = function() {
                return !!document.querySelector(
                  '.media-viewer-whole, .media-viewer, .media-viewer-movers, ' +
                  '[class*=\"media-viewer\"], [class*=\"MediaViewer\"], ' +
                  '[class*=\"avatar-viewer\"], [class*=\"AvatarViewer\"]'
                );
              };

              var swipeMedia = function(next) {
                var key = next ? 'ArrowRight' : 'ArrowLeft';
                var keyCode = next ? 39 : 37;
                var event = new KeyboardEvent('keydown', {
                  key: key,
                  code: key,
                  keyCode: keyCode,
                  which: keyCode,
                  bubbles: true
                });
                document.dispatchEvent(event);
                window.dispatchEvent(event);
              };

              var findClickable = function(node) {
                if (!node) { return null; }
                if (node.matches && node.matches('button,[role=\"button\"]')) {
                  return node;
                }
                return node.closest ? node.closest('button,[role=\"button\"]') : null;
              };

              var openMainMenuFromSwipe = function() {
                var selectors = [
                  'button[aria-label*=\"More\"]',
                  'button[aria-label*=\"Menu\"]',
                  '.left-header .tgico-more',
                  '.Topbar .tgico-more',
                  '.left-header .tgico-menu',
                  '.Topbar .tgico-menu',
                  '.btn-menu'
                ];
                for (var i = 0; i < selectors.length; i++) {
                  var node = document.querySelector(selectors[i]);
                  var clickable = findClickable(node);
                  if (clickable && typeof clickable.click === 'function') {
                    clickable.click();
                    return true;
                  }
                }
                return false;
              };

              var isMainListScreen = function() {
                return !hasOpenDialog();
              };

              var lastTapTs = 0;
              var lastTapX = 0;
              var lastTapY = 0;
              var lastTapBubble = null;
              var touchStartX = 0;
              var touchStartY = 0;
              var touchStartTs = 0;

              document.addEventListener('touchstart', function(e) {
                if (!e.touches || e.touches.length !== 1) { return; }
                touchStartX = e.touches[0].clientX;
                touchStartY = e.touches[0].clientY;
                touchStartTs = Date.now();
              }, { passive: true });

              document.addEventListener('touchend', function(e) {
                if (!e.changedTouches || e.changedTouches.length !== 1) { return; }
                var touch = e.changedTouches[0];
                var now = Date.now();
                var tapDx = touch.clientX - touchStartX;
                var tapDy = touch.clientY - touchStartY;
                var tapDistance = Math.sqrt((tapDx * tapDx) + (tapDy * tapDy));
                var tapDt = now - touchStartTs;
                var bubble = findBubbleForReaction(e.target);

                if (tapDt < 350 && tapDistance < 16 && bubble && !isInteractiveElement(e.target)) {
                  var sameBubble = !!lastTapBubble &&
                    (lastTapBubble === bubble || lastTapBubble.contains(bubble) || bubble.contains(lastTapBubble));
                  var isFast = (now - lastTapTs) <= 320;
                  var isNear = Math.abs(touch.clientX - lastTapX) <= 28 && Math.abs(touch.clientY - lastTapY) <= 28;
                  if (sameBubble && isFast && isNear) {
                    ['mousedown', 'mouseup', 'click', 'dblclick'].forEach(function(type) {
                      bubble.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
                    });
                    lastTapTs = 0;
                    lastTapBubble = null;
                    return;
                  }
                  lastTapTs = now;
                  lastTapX = touch.clientX;
                  lastTapY = touch.clientY;
                  lastTapBubble = bubble;
                } else if ((now - lastTapTs) > 600) {
                  lastTapBubble = null;
                }

                var dx = touch.clientX - touchStartX;
                var dy = touch.clientY - touchStartY;
                var dt = Date.now() - touchStartTs;
                if (dt > 900) { return; }
                if (Math.abs(dx) < 56 || Math.abs(dx) < Math.abs(dy) * 1.1) { return; }

                if (isMediaViewerOpen()) {
                  swipeMedia(dx < 0);
                  return;
                }

                var fromRightEdge = touchStartX >= (window.innerWidth - 36);
                if (fromRightEdge && dx < -56) {
                  if (closeActiveChatIfPossible()) {
                    return;
                  }
                }

                var fromLeftEdge = touchStartX <= 36;
                if (dx > 56 && (fromLeftEdge || isMainListScreen())) {
                  if (hasOpenDialog() && closeActiveChatIfPossible()) {
                    return;
                  }
                  openMainMenuFromSwipe();
                }
              }, { passive: true });

              window.addEventListener('tgweb-native', function(event) {
                var detail = event && event.detail ? event.detail : null;
                if (!detail || !detail.type) { return; }
                if (detail.type === 'INTERFACE_SCALE_STATE') {
                  var payload = detail.payload || {};
                  applyScale(payload.scalePercent);
                }
              });

              applyScale(${scale});
              removeSwitchLinks();
              syncSystemBars();
              ensureModSettingsEntry();
              ensureDownloadsButton();

              setInterval(removeSwitchLinks, 1800);
              setInterval(syncSystemBars, 2000);
              setInterval(ensureModSettingsEntry, 1200);
              setInterval(ensureDownloadsButton, 1500);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
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
        if (!enabled) return ProxyConfigSnapshot(enabled = false, type = ProxyType.DIRECT)

        val typeRaw = payload["type"]?.uppercase().orEmpty()
        val type = when (typeRaw) {
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

    private fun applyProxyToWebView(proxyState: ProxyConfigSnapshot) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            AppRepositories.postProxyState(proxyState)
            return
        }
        val executor = ContextCompat.getMainExecutor(this)

        if (!proxyState.enabled || proxyState.type == ProxyType.DIRECT) {
            ProxyController.getInstance().clearProxyOverride(
                executor,
                { AppRepositories.postProxyState(proxyState) },
            )
            return
        }

        val proxyRule = buildWebViewProxyRule(proxyState)
        if (proxyRule == null) {
            ProxyController.getInstance().clearProxyOverride(
                executor,
                { AppRepositories.postProxyState(proxyState) },
            )
            return
        }

        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule(proxyRule)
            .build()

        ProxyController.getInstance().setProxyOverride(
            proxyConfig,
            executor,
            { AppRepositories.postProxyState(proxyState) },
        )
    }

    private fun buildWebViewProxyRule(proxyState: ProxyConfigSnapshot): String? {
        val scheme = when (proxyState.type) {
            ProxyType.HTTP -> "http"
            ProxyType.SOCKS5 -> "socks"
            ProxyType.MTPROTO -> "socks" // compatibility fallback for WebView transport
            ProxyType.DIRECT -> return null
        }

        val username = proxyState.username.orEmpty()
        val password = proxyState.password.orEmpty()
        val credentials = if (username.isBlank() && password.isBlank()) {
            ""
        } else {
            "${Uri.encode(username)}:${Uri.encode(password)}@"
        }
        return "$scheme://$credentials${proxyState.host}:${proxyState.port}"
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
        private const val BUNDLED_WEBK_SRC_PUBLIC_URL = "file:///android_asset/webapp/webk-src/public/index.html"
        private const val REMOTE_WEBK_URL = "https://web.telegram.org/k/"
        private const val OFFLINE_CACHE_FILE_NAME = "tgweb_offline_main.html"
        private const val OFFLINE_ARCHIVE_FILE_NAME = "tgweb_offline_page.mht"
    }
}
