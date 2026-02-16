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
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

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
    private val authLogoDataUri by lazy { buildAuthLogoDataUri() }
    private var attemptedOfflineFallback: Boolean = false

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

        SessionBackupManager.consumeLastImportAppliedNotice(this)?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
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
            runCatching { applyProxyToWebView(AppRepositories.getProxyState()) }
                .onFailure { Log.w(TAG, "Unable to apply proxy on resume", it) }
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
            text = "FlyGram"
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
                attemptedOfflineFallback = false
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
                runCatching { applyProxyToWebView(AppRepositories.getProxyState()) }
                    .onFailure { Log.w(TAG, "Unable to apply proxy on page finish", it) }
                hideLoadingOverlayWithMinimumDuration()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "tgweb" && uri.host == "auth") {
                    handleAuthHandoff(uri)
                    return true
                }
                if (ProxyLinkParser.parse(uri) != null) {
                    showProxyBottomSheet(uri.toString())
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                if (attemptedOfflineFallback) return
                if (isNetworkAvailable()) return
                attemptedOfflineFallback = true
                loadOfflineFallbackPage()
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?,
            ) {
                val proxy = AppRepositories.getProxyState()
                val username = proxy.username
                val password = proxy.password
                if (
                    proxy.enabled &&
                    proxy.type != ProxyType.MTPROTO &&
                    !username.isNullOrBlank() &&
                    !password.isNullOrBlank() &&
                    !host.isNullOrBlank() &&
                    host.equals(proxy.host, ignoreCase = true)
                ) {
                    handler?.proceed(username, password)
                    return
                }
                super.onReceivedHttpAuthRequest(view, handler, host, realm)
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val stableFileId = buildStableDownloadFileId(url, fileName)
            AppRepositories.webBridge.onFromWeb(
                BridgeCommand(
                    type = BridgeCommandTypes.DOWNLOAD_MEDIA,
                    payload = mapOf(
                        "fileId" to stableFileId,
                        "url" to url,
                        "mime" to (mimeType ?: "application/octet-stream"),
                        "name" to fileName,
                        "export" to "false",
                        "targetCollection" to "flygram_downloads",
                    ),
                ),
            )
        }

        webView.addJavascriptInterface(
            AndroidBridgeJsApi { command ->
                when (command.type) {
                    BridgeCommandTypes.REQUEST_PUSH_PERMISSION -> requestNotificationPermission()
                    BridgeCommandTypes.OPEN_MOD_SETTINGS -> openModSettings()
                    BridgeCommandTypes.OPEN_SESSION_TOOLS -> openSessionTools(command.payload["mode"])
                    BridgeCommandTypes.OPEN_PROXY_PREVIEW -> showProxyBottomSheet(command.payload["url"])
                    BridgeCommandTypes.OPEN_AUTHOR_CHANNEL -> openAuthorChannel()
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
        val useBundled = runtimePrefs.getBoolean(KeepAliveService.KEY_USE_BUNDLED_WEBK, false)

        if (isNetworkAvailable()) {
            if (bundledUrl != null && useBundled) {
                webView.loadUrl(bundledUrl)
            } else {
                webView.loadUrl(REMOTE_WEBK_URL)
            }
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

    private fun loadOfflineFallbackPage() {
        val bundledUrl = resolveBundledWebKUrl()
        when {
            bundledUrl != null -> {
                webView.loadUrl(bundledUrl)
            }
            offlineArchiveFile.exists() -> {
                webView.loadUrl("file://${offlineArchiveFile.absolutePath}")
            }
            offlineCacheFile.exists() -> {
                val html = runCatching { offlineCacheFile.readText() }.getOrNull()
                if (!html.isNullOrBlank()) {
                    webView.loadDataWithBaseURL(
                        REMOTE_WEBK_URL,
                        html,
                        "text/html",
                        "UTF-8",
                        REMOTE_WEBK_URL,
                    )
                } else {
                    webView.loadUrl("file:///android_asset/webapp/offline.html")
                }
            }
            else -> {
                webView.loadUrl("file:///android_asset/webapp/offline.html")
            }
        }
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
        if (dataUri != null) {
            val parsed = ProxyLinkParser.parse(dataUri)
            if (parsed != null) {
                openProxyBottomSheet(parsed, dataUri.toString())
                return
            }
        }

        val chatId = intent?.getLongExtra("chat_id", 0L) ?: 0L
        val messageId = intent?.getLongExtra("message_id", 0L) ?: 0L
        val preview = intent?.getStringExtra("preview").orEmpty()
        if (chatId > 0L) {
            AppRepositories.postPushMessageReceived(chatId = chatId, messageId = messageId, preview = preview)
        }
    }

    private fun showProxyBottomSheet(raw: String?) {
        if (raw.isNullOrBlank()) return
        val parsed = ProxyLinkParser.parse(raw) ?: return
        openProxyBottomSheet(parsed, raw)
    }

    private fun openProxyBottomSheet(parsed: ProxyConfigSnapshot, sourceLabel: String) {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 30, 36, 30)
            setBackgroundColor(Color.parseColor("#162331"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val title = TextView(this).apply {
            text = "Proxy link"
            textSize = 18f
            setTextColor(Color.parseColor("#EAF3FF"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeTop = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#B9CCE4"))
            setOnClickListener { dialog.dismiss() }
        }
        val subtitle = TextView(this).apply {
            text = sourceLabel
            textSize = 13f
            setPadding(0, 6, 0, 12)
            setTextColor(Color.parseColor("#9BB0C9"))
        }
        val details = TextView(this).apply {
            text = buildString {
                append("Type: ${parsed.type.name}\n")
                append("Server: ${parsed.host}:${parsed.port}")
                if (!parsed.username.isNullOrBlank()) {
                    append("\nUser: ${parsed.username}")
                }
                if (!parsed.secret.isNullOrBlank()) {
                    append("\nSecret: ${parsed.secret}")
                }
            }
            textSize = 14f
            setTextColor(Color.parseColor("#DCEAFF"))
        }
        val ping = TextView(this).apply {
            text = "Ping: checking..."
            textSize = 13f
            setPadding(0, 12, 0, 14)
            setTextColor(Color.parseColor("#9BB0C9"))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val addButton = Button(this).apply {
            text = "Add proxy"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                lifecycleScope.launch {
                    val profile = ProxyProfile(
                        title = ProxyProfilesStore.defaultTitle(parsed),
                        config = parsed.copy(enabled = true),
                    )
                    ProxyProfilesStore.upsert(this@MainActivity, profile)
                    ProxyProfilesStore.setActiveProfileId(this@MainActivity, profile.id)
                    ProxyProfilesStore.setProxyEnabled(this@MainActivity, true)
                    AppRepositories.updateProxyState(parsed)
                    applyProxyToWebView(parsed)
                    Toast.makeText(
                        this@MainActivity,
                        "Proxy added: ${parsed.host}:${parsed.port}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    dialog.dismiss()
                }
            }
        }
        val closeButton = Button(this).apply {
            text = "Close"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialog.dismiss() }
        }
        buttonRow.addView(addButton)
        buttonRow.addView(closeButton)

        header.addView(title)
        header.addView(closeTop)
        container.addView(header)
        container.addView(subtitle)
        container.addView(details)
        container.addView(ping)
        container.addView(buttonRow)
        dialog.setContentView(container)
        dialog.show()

        lifecycleScope.launch {
            val latency = measureProxyLatency(parsed)
            ping.text = if (latency != null) "Ping: ${latency.toInt()} ms" else "Ping: timeout"
        }
    }

    private suspend fun measureProxyLatency(state: ProxyConfigSnapshot): Double? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val startedAt = System.nanoTime()
            runCatching {
                when (state.type) {
                    ProxyType.MTPROTO -> {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(state.host, state.port), 6_000)
                        }
                    }
                    ProxyType.SOCKS5,
                    ProxyType.HTTP,
                    -> {
                        val proxy = Proxy(
                            if (state.type == ProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
                            InetSocketAddress(state.host, state.port),
                        )
                        val connection = URL("https://web.telegram.org/").openConnection(proxy) as HttpURLConnection
                        connection.requestMethod = "HEAD"
                        connection.connectTimeout = 6_000
                        connection.readTimeout = 6_000
                        connection.connect()
                        connection.responseCode
                        connection.disconnect()
                    }
                    ProxyType.DIRECT -> return@withContext null
                }
                (System.nanoTime() - startedAt) / 1_000_000.0
            }.getOrNull()
        }
    }

    private fun openModSettings() {
        startActivity(Intent(this, ModSettingsActivity::class.java))
    }

    private fun openAuthorChannel() {
        val script = """
            (function() {
              try {
                if (location.origin.indexOf('web.telegram.org') === -1) {
                  location.href = '${REMOTE_WEBK_URL}#@plugin_ai';
                } else {
                  location.hash = '#@plugin_ai';
                }
              } catch (e) {
                location.href = '${REMOTE_WEBK_URL}#@plugin_ai';
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun openSessionTools(mode: String? = null) {
        val intent = Intent(this, SessionToolsActivity::class.java).apply {
            when (mode?.lowercase()) {
                SessionToolsActivity.ACTION_IMPORT_SESSION -> {
                    putExtra(SessionToolsActivity.EXTRA_ACTION_MODE, SessionToolsActivity.ACTION_IMPORT_SESSION)
                }
                SessionToolsActivity.ACTION_IMPORT_TDATA -> {
                    putExtra(SessionToolsActivity.EXTRA_ACTION_MODE, SessionToolsActivity.ACTION_IMPORT_TDATA)
                }
            }
        }
        startActivity(intent)
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

    private fun buildStableDownloadFileId(url: String, fileName: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null) {
            return "${url.hashCode()}_${fileName.hashCode()}"
        }
        if (uri.scheme.equals("blob", ignoreCase = true)) {
            return "blob_${fileName.hashCode()}"
        }
        val ignoredParams = setOf("offset", "range", "start", "end", "part", "chunk", "segment")
        val normalized = buildString {
            append(uri.scheme.orEmpty())
            append("://")
            append(uri.host.orEmpty())
            append(uri.path.orEmpty())
            if (uri.queryParameterNames.isNotEmpty()) {
                val query = uri.queryParameterNames
                    .sorted()
                    .filterNot { ignoredParams.contains(it.lowercase()) }
                    .joinToString("&") { key ->
                        val values = uri.getQueryParameters(key)
                        if (values.isNullOrEmpty()) key else "$key=${values.joinToString(",")}"
                    }
                if (query.isNotBlank()) {
                    append('?')
                    append(query)
                }
            }
        }
        return "${normalized.hashCode()}_${fileName.hashCode()}"
    }

    private fun buildAuthLogoDataUri(): String {
        val bytes = runCatching {
            assets.open("webapp/flygram_login_logo.png").use { it.readBytes() }
        }.getOrNull() ?: return ""
        if (bytes.isEmpty()) return ""
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/png;base64,$encoded"
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
                var root = document.getElementById('root') || document.body;
                if (root) {
                  root.style.transformOrigin = 'top left';
                  root.style.transform = 'scale(' + scale + ')';
                  root.style.width = (100 / scale) + '%';
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
        val hideStories = runtimePrefs.getBoolean(KeepAliveService.KEY_HIDE_STORIES, false)
        val md3Effects = runtimePrefs.getBoolean(KeepAliveService.KEY_MD3_EFFECTS, true)
        val dynamicColor = runtimePrefs.getBoolean(KeepAliveService.KEY_DYNAMIC_COLOR, true)
        val md3ContainerStyle = runtimePrefs
            .getString(KeepAliveService.KEY_MD3_CONTAINER_STYLE, "segmented")
            .orEmpty()
        val authLogoQuoted = JSONObject.quote(authLogoDataUri)
        val md3Accent = if (dynamicColor) {
            colorToHex(UiThemeBridge.readDynamicSurfaceColor(this))
        } else {
            runtimePrefs.getString(KeepAliveService.KEY_STATUS_BAR_COLOR, "#3390EC").orEmpty()
        }
        val script = """
            (function() {
              if (window.__tgwebMobileEnhancerInstalled) { return; }
              window.__tgwebMobileEnhancerInstalled = true;

              var custom = {
                hideStories: ${if (hideStories) "true" else "false"},
                md3Effects: ${if (md3Effects) "true" else "false"},
                md3Accent: '${md3Accent}',
                containerStyle: '${md3ContainerStyle}'
              };
              var flygramAuthLogo = $authLogoQuoted;

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
                document.documentElement.style.zoom = String(zoom);
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

              var blendHex = function(fromHex, toHex, amount) {
                var from = String(fromHex || '#0e1621').replace('#', '');
                var to = String(toHex || '#ffffff').replace('#', '');
                if (from.length !== 6 || to.length !== 6) { return fromHex || '#0e1621'; }
                var mix = clamp(Number(amount) || 0, 0, 1);
                var fromR = parseInt(from.slice(0, 2), 16);
                var fromG = parseInt(from.slice(2, 4), 16);
                var fromB = parseInt(from.slice(4, 6), 16);
                var toR = parseInt(to.slice(0, 2), 16);
                var toG = parseInt(to.slice(2, 4), 16);
                var toB = parseInt(to.slice(4, 6), 16);
                var r = Math.round(fromR + (toR - fromR) * mix).toString(16).padStart(2, '0');
                var g = Math.round(fromG + (toG - fromG) * mix).toString(16).padStart(2, '0');
                var b = Math.round(fromB + (toB - fromB) * mix).toString(16).padStart(2, '0');
                return '#' + r + g + b;
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

              var replaceVisibleBranding = function() {
                if (document.title && document.title.toLowerCase().indexOf('telegram') >= 0) {
                  document.title = document.title.replace(/telegram/gi, 'FlyGram');
                }
                var containers = [
                  document.getElementById('auth-pages'),
                  document.querySelector('.auth-pages'),
                  document.querySelector('.login-page'),
                  document.querySelector('.left-header'),
                ].filter(Boolean);
                containers.forEach(function(root) {
                  root.querySelectorAll('h1,h2,h3,.title,.subtitle,button,span,div').forEach(function(node) {
                    if (!node || !node.childNodes || node.childNodes.length !== 1) { return; }
                    var text = String(node.textContent || '').trim();
                    if (!text) { return; }
                    if (/telegram/i.test(text) && text.length <= 120) {
                      node.textContent = text
                        .replace(/telegram web/gi, 'FlyGram')
                        .replace(/telegram/gi, 'FlyGram');
                    }
                  });
                });
              };

              var applyCustomizations = function() {
                if (custom.hideStories) {
                  document.querySelectorAll('[class*=\"story\"], [class*=\"Story\"], [data-tab=\"stories\"]').forEach(function(node) {
                    hideNode(node.closest('.stories, .story, .row, .tabs-tab') || node);
                  });
                }
                if (!custom.md3Effects) { return; }
                var bodyStyles = getComputedStyle(document.body || document.documentElement);
                var rootBg = colorFromCss(bodyStyles.backgroundColor || '#0e1621');
                var isLightTheme = luminance(rootBg) > 0.62;
                var textColor = isLightTheme ? '#102030' : '#e9f3ff';
                var mutedTextColor = blendHex(textColor, rootBg, isLightTheme ? 0.45 : 0.35);
                var containerBg = blendHex(rootBg, textColor, isLightTheme ? 0.035 : 0.07);
                var dividerColor = blendHex(rootBg, textColor, isLightTheme ? 0.16 : 0.24);
                var accentColor = custom.md3Accent && custom.md3Accent.length === 7 ? custom.md3Accent : '#3390ec';
                var containerCss = custom.containerStyle === 'dividers'
                  ? 'margin:0 !important;border-radius:0 !important;border:0 !important;border-top:1px solid var(--flygram-divider) !important;'
                  : 'margin:6px 10px !important;border-radius:14px !important;border:1px solid var(--flygram-divider) !important;';

                var style = document.getElementById('tgweb-md3-patch');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'tgweb-md3-patch';
                  document.head.appendChild(style);
                }
                style.textContent =
                  ':root{' +
                    '--flygram-bg:' + rootBg + ';' +
                    '--flygram-text:' + textColor + ';' +
                    '--flygram-muted:' + mutedTextColor + ';' +
                    '--flygram-container:' + containerBg + ';' +
                    '--flygram-divider:' + dividerColor + ';' +
                    '--flygram-accent:' + accentColor + ';' +
                  '}' +
                  'body,#root,.app-wrapper,.left-sidebar,.chat,.chat-background{' +
                    'background:var(--flygram-bg) !important;' +
                    'color:var(--flygram-text) !important;' +
                  '}' +
                  '.settings-container .row,.settings-content .row,.profile-buttons .row,' +
                  '.btn-menu .btn-menu-item,.menu-item,.row.no-subtitle{' +
                    containerCss +
                    'background:var(--flygram-container) !important;' +
                    'color:var(--flygram-text) !important;' +
                  '}' +
                  '.settings-container .row-title,.settings-container .row-subtitle,' +
                  '.btn-menu-item-text,.btn-menu-item-auxiliary-text,.subtitle,.description{' +
                    'color:var(--flygram-text) !important;' +
                  '}' +
                  '.settings-container .row-subtitle,.btn-menu-item-auxiliary-text,.subtitle{' +
                    'color:var(--flygram-muted) !important;' +
                  '}' +
                  '.btn-primary,.btn-primary-transparent,.tgweb-auth-import-actions button{' +
                    'border-radius:12px !important;' +
                    'border:0 !important;' +
                    'background:linear-gradient(135deg, var(--flygram-accent), ' + blendHex(accentColor, '#ffffff', 0.20) + ') !important;' +
                    'color:#fff !important;' +
                  '}' +
                  '.btn-menu .btn-menu-item + .btn-menu-item{' +
                    'border-top:1px solid var(--flygram-divider) !important;' +
                  '}' +
                  '.popup,.chat-info,.media-viewer{' +
                    'border-radius:18px !important;' +
                  '}';
              };

              var activeDownloads = {};
              var updateDownloadsBadge = function() {
                var count = 0;
                Object.keys(activeDownloads).forEach(function(key) {
                  if (activeDownloads[key]) { count += 1; }
                });
                document.querySelectorAll('.tgweb-downloads-badge').forEach(function(node) {
                  node.textContent = String(count);
                  node.style.display = count > 0 ? 'flex' : 'none';
                });
              };

              var removeMoreMenuEntry = function() {
                document.querySelectorAll('.btn-menu-item,.menu-item,.row').forEach(function(node) {
                  var labelNode = node.querySelector ? node.querySelector('.btn-menu-item-text,.row-title,.title') : null;
                  var text = String((labelNode ? labelNode.textContent : node.textContent) || '').trim().toLowerCase();
                  if (text === 'ещё' || text === 'more' || text === 'еще') {
                    hideNode(node.closest('.btn-menu-item,.menu-item,.row') || node);
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
                icon.className = 'row-icon';
                icon.textContent = '❤';
                icon.style.fontSize = '16px';

                var title = document.createElement('div');
                title.className = 'row-title';
                title.textContent = 'Настройки FlyGram';
                title.style.fontWeight = '600';

                row.append(icon, title);
                row.addEventListener('click', function(e) {
                  e.preventDefault();
                  e.stopPropagation();
                  send('${BridgeCommandTypes.OPEN_MOD_SETTINGS}', {});
                }, { passive: false });

                buttonsRoot.insertBefore(row, buttonsRoot.firstChild || null);
              };

              var ensureAuthorChannelEntry = function() {
                var buttonsRoot = document.querySelector('.settings-container .profile-buttons');
                if (!buttonsRoot) { return; }
                if (buttonsRoot.querySelector('.tgweb-author-channel-row')) { return; }

                var row = document.createElement('div');
                row.className = 'row no-subtitle row-with-icon row-with-padding tgweb-author-channel-row';
                row.style.background = 'linear-gradient(90deg, rgba(51,144,236,.22), rgba(96,191,255,.18))';
                row.style.borderRadius = '12px';

                var icon = document.createElement('span');
                icon.className = 'row-icon';
                icon.textContent = '⭐';
                icon.style.fontSize = '16px';

                var title = document.createElement('div');
                title.className = 'row-title';
                title.textContent = 'Канал Автора';
                title.style.fontWeight = '700';

                var handleOpen = function(e) {
                  e.preventDefault();
                  e.stopPropagation();
                  send('${BridgeCommandTypes.OPEN_AUTHOR_CHANNEL}', { username: 'plugin_ai' });
                };

                row.append(icon, title);
                row.addEventListener('click', handleOpen, { passive: false });
                row.addEventListener('touchend', handleOpen, { passive: false });

                var modRow = buttonsRoot.querySelector('.tgweb-mod-settings-row');
                if (modRow && modRow.nextSibling) {
                  buttonsRoot.insertBefore(row, modRow.nextSibling);
                } else {
                  buttonsRoot.appendChild(row);
                }
              };

              var ensureDownloadsButton = function() {
                var header = document.querySelector('.left-header');
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
                button.style.position = 'relative';
                button.addEventListener('click', function(e) {
                  e.preventDefault();
                  e.stopPropagation();
                  send('${BridgeCommandTypes.OPEN_DOWNLOADS}', {});
                }, { passive: false });

                var badge = document.createElement('span');
                badge.className = 'tgweb-downloads-badge';
                badge.style.position = 'absolute';
                badge.style.top = '-4px';
                badge.style.right = '-4px';
                badge.style.width = '17px';
                badge.style.height = '17px';
                badge.style.borderRadius = '999px';
                badge.style.background = '#ff4d4f';
                badge.style.color = '#fff';
                badge.style.fontSize = '10px';
                badge.style.fontWeight = '700';
                badge.style.display = 'none';
                badge.style.alignItems = 'center';
                badge.style.justifyContent = 'center';
                badge.textContent = '0';
                button.appendChild(badge);
                header.appendChild(button);
                updateDownloadsBadge();
              };

              var ensureAuthImportButtons = function() {
                var authPages = document.getElementById('auth-pages') ||
                  document.querySelector('.auth-pages, .login-page, [class*=\"loginPage\"], [class*=\"pageSign\"]');
                var authPage = authPages && authPages.querySelector
                  ? authPages.querySelector('.page.active, .page-signIn, .page-signQR, .page-signUp, .page-authCode, .page-password, [class*=\"page-sign\"]')
                  : null;
                var existing = document.querySelector('.tgweb-auth-import-actions');
                var existingBranding = document.querySelector('.flygram-auth-branding');
                if (!authPages) {
                  if (existing) { existing.remove(); }
                  if (existingBranding) { existingBranding.remove(); }
                  return;
                }

                var isVisible = window.getComputedStyle(authPages).display !== 'none';
                var isAuthMode = document.body.classList.contains('has-auth-pages') || !!authPage;
                if (!isVisible || !isAuthMode) {
                  if (existing) { existing.remove(); }
                  if (existingBranding) { existingBranding.remove(); }
                  return;
                }

                if (existing) { return; }

                var target = (authPage && authPage.querySelector && authPage.querySelector('.container')) ||
                  authPages.querySelector('.container, .scrollable-content, .tabs-container, .auth-placeholder') ||
                  authPages;
                if (!target) { return; }

                var branding = document.querySelector('.flygram-auth-branding');
                if (!branding) {
                  branding = document.createElement('div');
                  branding.className = 'flygram-auth-branding';
                  branding.style.display = 'flex';
                  branding.style.flexDirection = 'column';
                  branding.style.alignItems = 'center';
                  branding.style.gap = '8px';
                  branding.style.margin = '6px auto 12px';

                  var logo = document.createElement('img');
                  logo.alt = 'FlyGram';
                  logo.style.width = '74px';
                  logo.style.height = '74px';
                  logo.style.objectFit = 'contain';
                  logo.style.borderRadius = '20px';
                  logo.style.filter = 'drop-shadow(0 6px 14px rgba(51,144,236,.28))';
                  if (flygramAuthLogo && flygramAuthLogo.length > 32) {
                    logo.src = flygramAuthLogo;
                  }

                  var title = document.createElement('div');
                  title.textContent = 'FlyGram';
                  title.style.fontSize = '22px';
                  title.style.fontWeight = '700';
                  title.style.letterSpacing = '.3px';

                  var subtitle = document.createElement('div');
                  subtitle.textContent = 'FlyGram Web mod for Android';
                  subtitle.style.fontSize = '13px';
                  subtitle.style.opacity = '.72';

                  branding.appendChild(logo);
                  branding.appendChild(title);
                  branding.appendChild(subtitle);
                  target.insertBefore(branding, target.firstChild || null);
                }

                var wrap = document.createElement('div');
                wrap.className = 'tgweb-auth-import-actions';
                wrap.style.display = 'flex';
                wrap.style.flexDirection = 'column';
                wrap.style.gap = '8px';
                wrap.style.width = '100%';
                wrap.style.maxWidth = '300px';
                wrap.style.margin = '18px auto 0';

                var makeButton = function(text, mode) {
                  var btn = document.createElement('button');
                  btn.type = 'button';
                  btn.textContent = text;
                  btn.style.width = '100%';
                  btn.style.height = '38px';
                  btn.style.border = '0';
                  btn.style.borderRadius = '10px';
                  btn.style.padding = '0 12px';
                  btn.style.cursor = 'pointer';
                  btn.style.fontWeight = '600';
                  btn.style.fontSize = '14px';
                  btn.style.background = 'var(--primary-color, #3390ec)';
                  btn.style.color = '#fff';
                  btn.addEventListener('click', function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    send('${BridgeCommandTypes.OPEN_SESSION_TOOLS}', { mode: mode });
                  }, { passive: false });
                  return btn;
                };

                wrap.appendChild(makeButton('Импортировать Session', '${SessionToolsActivity.ACTION_IMPORT_SESSION}'));
                wrap.appendChild(makeButton('Импортировать tdata', '${SessionToolsActivity.ACTION_IMPORT_TDATA}'));

                target.appendChild(wrap);
              };

              var bindProxyLinkIntercept = function() {
                if (window.__tgwebProxyLinkBound) { return; }
                window.__tgwebProxyLinkBound = true;
                document.addEventListener('click', function(event) {
                  var link = event.target && event.target.closest ? event.target.closest('a[href]') : null;
                  if (!link) { return; }
                  var href = String(link.getAttribute('href') || '').trim();
                  if (!href) { return; }
                  var normalized = href.toLowerCase();
                  var isProxyLink =
                    normalized.indexOf('tg://proxy?') === 0 ||
                    normalized.indexOf('tg://socks?') === 0 ||
                    normalized.indexOf('/proxy?') === 0 ||
                    normalized.indexOf('/socks?') === 0 ||
                    normalized.indexOf('https://t.me/proxy?') === 0 ||
                    normalized.indexOf('http://t.me/proxy?') === 0 ||
                    normalized.indexOf('https://t.me/socks?') === 0 ||
                    normalized.indexOf('http://t.me/socks?') === 0;
                  if (!isProxyLink) { return; }
                  if (normalized.indexOf('/proxy?') === 0 || normalized.indexOf('/socks?') === 0) {
                    href = 'https://t.me' + href;
                  }
                  event.preventDefault();
                  event.stopPropagation();
                  send('${BridgeCommandTypes.OPEN_PROXY_PREVIEW}', { url: href });
                }, true);
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
                  '.sidebar-header .sidebar-close-button:not(.hide), ' +
                  '.chat-topbar .btn-icon.tgico-left, .chat-topbar .sidebar-close-button, ' +
                  'button[aria-label*=\"Back\"], button[aria-label*=\"Назад\"], ' +
                  '.topbar button.btn-icon.tgico-left, .chat-info-container .btn-icon.tgico-left, ' +
                  '.chat-info-container .btn-icon, .topbar .btn-icon'
                );
              };

              var closeActiveChatIfPossible = function() {
                var btn = findChatCloseButton();
                if (btn && typeof btn.click === 'function') {
                  btn.click();
                  return true;
                }
                try {
                  if (window.history && window.history.length > 1) {
                    window.history.back();
                    return true;
                  }
                } catch (e) {}
                return false;
              };

              var hasOpenDialog = function() {
                if (findChatCloseButton()) { return true; }
                var hash = String(location.hash || '');
                if (hash && hash !== '#' && hash !== '#/' && (hash.indexOf('@') >= 0 || hash.indexOf('/c/') >= 0 || hash.indexOf('-100') >= 0)) {
                  return true;
                }
                return !!document.querySelector('.chat-topbar .peer-title, .chat-input .input-message-input');
              };

              var isSettingsLikeOpen = function() {
                return !!document.querySelector(
                  '.settings-container, .profile-container, .shared-media-container, ' +
                  '.btn-menu.active, .popup-peer, .sidebar-slider .sidebar-slider-item.active .settings'
                );
              };

              var isDialogScreenActive = function() {
                if (!hasOpenDialog()) { return false; }
                var hash = String(location.hash || '').toLowerCase();
                if (
                  hash.indexOf('settings') >= 0 ||
                  hash.indexOf('folders') >= 0 ||
                  hash.indexOf('contacts') >= 0 ||
                  hash.indexOf('proxy') >= 0 ||
                  hash.indexOf('downloads') >= 0 ||
                  hash.indexOf('calls') >= 0
                ) {
                  return false;
                }
                return !!document.querySelector('.chat-main .bubbles, .chat-input .input-message-input, .chat-topbar .peer-title');
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
                  '.left-header .btn-icon.tgico-menu',
                  '.left-header .btn-menu-toggle',
                  '.left-sidebar .btn-menu-toggle',
                  '.left-header .btn-icon[aria-label*=\"Menu\"]',
                  '.left-header .btn-icon[aria-label*=\"Меню\"]',
                  '.left-header button[aria-label*=\"Menu\"]',
                  '.left-header button[aria-label*=\"Меню\"]',
                  '.left-header button[aria-label*=\"More\"]',
                  'button[aria-label*=\"Menu\"]',
                  '.left-header button .tgico-menu',
                  '.left-header .tgico-more',
                  '.left-header .tgico-menu',
                  '.left-header .btn-menu',
                  '.left-sidebar .btn-menu'
                ];
                for (var i = 0; i < selectors.length; i++) {
                  var node = document.querySelector(selectors[i]);
                  var clickable = findClickable(node);
                  if (clickable && clickable.classList && clickable.classList.contains('tgweb-downloads-button')) {
                    continue;
                  }
                  if (clickable && typeof clickable.click === 'function') {
                    clickable.click();
                    return true;
                  }
                }
                var fallback = document.querySelector('.left-header button:not(.tgweb-downloads-button), .left-header .btn-icon:not(.tgweb-downloads-button)');
                var fallbackClickable = findClickable(fallback);
                if (fallbackClickable && typeof fallbackClickable.click === 'function') {
                  fallbackClickable.click();
                  return true;
                }
                return false;
              };

              var isMainListScreen = function() {
                return !isDialogScreenActive() && !isSettingsLikeOpen();
              };

              var isInsideGestureIgnoredArea = function(node) {
                return !!(node && node.closest && node.closest(
                  '.settings-container, .profile-container, .btn-menu, .popup, ' +
                  '.input-message-container, .composer, textarea, input, select'
                ));
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

                if (isInsideGestureIgnoredArea(e.target) && !isMediaViewerOpen()) { return; }

                var absDx = Math.abs(dx);
                var absDy = Math.abs(dy);
                if (absDx < 72 || absDx < absDy * 1.28) { return; }

                var speed = absDx / Math.max(dt, 1);

                if (isMediaViewerOpen()) {
                  if (dt <= 520 && absDx >= 78 && speed >= 0.2) {
                    swipeMedia(dx < 0);
                  }
                  return;
                }

                var isFastLongSwipe = absDx >= 112 && dt <= 360 && speed >= 0.30;
                if (!isFastLongSwipe) { return; }

                var fromRightEdge = touchStartX >= (window.innerWidth - 28);
                if (dx < 0 && fromRightEdge && isDialogScreenActive()) {
                  if (closeActiveChatIfPossible()) {
                    return;
                  }
                }

                var fromLeftEdge = touchStartX <= 28;
                if (dx > 0 && fromLeftEdge && isMainListScreen()) {
                  openMainMenuFromSwipe();
                }
              }, { passive: true });

              window.addEventListener('tgweb-native', function(event) {
                var detail = event && event.detail ? event.detail : null;
                if (!detail || !detail.type) { return; }
                if (detail.type === 'INTERFACE_SCALE_STATE') {
                  var payload = detail.payload || {};
                  applyScale(payload.scalePercent);
                } else if (detail.type === 'DOWNLOAD_PROGRESS') {
                  var data = detail.payload || {};
                  var id = String(data.fileId || '');
                  var percent = Number(data.percent || 0);
                  if (id) {
                    activeDownloads[id] = percent < 100 && !data.error;
                    if (percent >= 100 || data.error) {
                      delete activeDownloads[id];
                    }
                  }
                  updateDownloadsBadge();
                }
              });

              applyScale(${scale});
              removeSwitchLinks();
              removeMoreMenuEntry();
              replaceVisibleBranding();
              applyCustomizations();
              syncSystemBars();
              ensureModSettingsEntry();
              ensureAuthorChannelEntry();
              ensureDownloadsButton();
              ensureAuthImportButtons();
              bindProxyLinkIntercept();

              setInterval(removeSwitchLinks, 1800);
              setInterval(removeMoreMenuEntry, 1800);
              setInterval(replaceVisibleBranding, 2200);
              setInterval(applyCustomizations, 2400);
              setInterval(syncSystemBars, 2000);
              setInterval(ensureModSettingsEntry, 1200);
              setInterval(ensureAuthorChannelEntry, 1600);
              setInterval(ensureDownloadsButton, 1500);
              setInterval(ensureAuthImportButtons, 1200);
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
            runCatching {
                ProxyController.getInstance().clearProxyOverride(
                    executor,
                    { AppRepositories.postProxyState(proxyState) },
                )
            }.onFailure {
                Log.w(TAG, "Unable to clear proxy override", it)
            }
            return
        }

        val proxyRule = buildWebViewProxyRule(proxyState)
        if (proxyRule == null) {
            runCatching {
                ProxyController.getInstance().clearProxyOverride(
                    executor,
                    { AppRepositories.postProxyState(proxyState) },
                )
            }.onFailure {
                Log.w(TAG, "Unable to clear proxy override", it)
            }
            return
        }

        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule(proxyRule)
            .build()

        runCatching {
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                executor,
                { AppRepositories.postProxyState(proxyState) },
            )
        }.onFailure {
            Log.e(TAG, "Unable to apply WebView proxy rule: $proxyRule", it)
            runCatching {
                ProxyController.getInstance().clearProxyOverride(
                    executor,
                    { AppRepositories.postProxyState(ProxyConfigSnapshot(enabled = false, type = ProxyType.DIRECT)) },
                )
            }.onFailure { clearError ->
                Log.w(TAG, "Unable to rollback proxy override after failure", clearError)
            }
        }
    }

    private fun buildWebViewProxyRule(proxyState: ProxyConfigSnapshot): String? {
        val scheme = when (proxyState.type) {
            ProxyType.HTTP -> "http"
            ProxyType.SOCKS5 -> "socks"
            ProxyType.MTPROTO -> "socks" // compatibility fallback for WebView transport
            ProxyType.DIRECT -> return null
        }
        // WebView ProxyController rejects credentials in proxy URL.
        return "$scheme://${proxyState.host}:${proxyState.port}"
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
        private const val OFFLINE_CACHE_FILE_NAME = "flygram_offline_main.html"
        private const val OFFLINE_ARCHIVE_FILE_NAME = "flygram_offline_page.mht"
    }
}
