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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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

class MainActivity : ComponentActivity() {
    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView

    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var loadingJob: Job? = null
    private var startedAtElapsedMs: Long = 0L

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
                showLoadingOverlay()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
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
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return super.shouldInterceptRequest(view, request)
                if (!request.isForMainFrame) return super.shouldInterceptRequest(view, request)

                if (!isNetworkAvailable() && (url.scheme == "http" || url.scheme == "https")) {
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
                ),
            )
        }

        webView.addJavascriptInterface(
            AndroidBridgeJsApi { command ->
                when (command.type) {
                    BridgeCommandTypes.REQUEST_PUSH_PERMISSION -> requestNotificationPermission()
                    BridgeCommandTypes.SET_PROXY -> handleProxyCommand(command)
                    BridgeCommandTypes.GET_PROXY_STATUS -> AppRepositories.postProxyState()
                    BridgeCommandTypes.SET_SYSTEM_UI_STYLE -> applySystemUiStyle(command.payload)
                    BridgeCommandTypes.SET_INTERFACE_SCALE -> applyInterfaceScale(command.payload["scalePercent"])
                    BridgeCommandTypes.SET_KEEP_ALIVE -> setKeepAliveEnabled(command.payload["enabled"])
                    BridgeCommandTypes.GET_KEEP_ALIVE_STATE -> AppRepositories.postKeepAliveState(KeepAliveService.isEnabled(this))
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
        if (bundledUrl != null && runtimePrefs.getBoolean(KeepAliveService.KEY_USE_BUNDLED_WEBK, true)) {
            webView.loadUrl(bundledUrl)
            return
        }
        if (!isNetworkAvailable()) {
            webView.loadUrl(OFFLINE_URL)
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
        val keepAliveEnabled = KeepAliveService.isEnabled(this)
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
                var scale = p / 100;
                document.documentElement.style.setProperty('--tgweb-mobile-scale', String(scale));
                if (document.body) { document.body.style.zoom = String(scale); }
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

              var switchRegex = /(switch\s+to\s+[ak]\s+version|version\s*[ak]|versio?n\s*[ak]|versao\s*[ak])/i;
              var removeSwitchLinks = function() {
                document.querySelectorAll('a,button,span,div').forEach(function(el) {
                  var text = (el.textContent || '').trim();
                  if (!text || text.length > 96) { return; }
                  if (switchRegex.test(text)) {
                    el.style.display = 'none';
                    el.style.visibility = 'hidden';
                  }
                });
              };

              var panel = document.createElement('div');
              panel.style.position = 'fixed';
              panel.style.right = '14px';
              panel.style.bottom = '18px';
              panel.style.zIndex = '2147483646';
              panel.style.display = 'flex';
              panel.style.flexDirection = 'column';
              panel.style.alignItems = 'flex-end';
              panel.style.gap = '8px';

              var actionBox = document.createElement('div');
              actionBox.style.display = 'none';
              actionBox.style.background = 'rgba(17, 27, 39, 0.92)';
              actionBox.style.border = '1px solid rgba(112, 144, 180, 0.35)';
              actionBox.style.borderRadius = '12px';
              actionBox.style.padding = '10px';
              actionBox.style.backdropFilter = 'blur(8px)';
              actionBox.style.minWidth = '220px';
              actionBox.style.color = '#d8e6ff';
              actionBox.style.fontSize = '13px';

              var header = document.createElement('div');
              header.textContent = 'Mobile tools';
              header.style.fontWeight = '700';
              header.style.marginBottom = '8px';
              actionBox.appendChild(header);

              var scaleLabel = document.createElement('div');
              scaleLabel.style.marginBottom = '4px';
              actionBox.appendChild(scaleLabel);

              var scaleInput = document.createElement('input');
              scaleInput.type = 'range';
              scaleInput.min = '75';
              scaleInput.max = '140';
              scaleInput.value = String(applyScale(${scale}));
              scaleInput.style.width = '100%';
              scaleInput.oninput = function() {
                var p = applyScale(scaleInput.value);
                scaleLabel.textContent = 'Scale: ' + p + '%';
                send('${BridgeCommandTypes.SET_INTERFACE_SCALE}', { scalePercent: String(p) });
              };
              actionBox.appendChild(scaleInput);
              scaleLabel.textContent = 'Scale: ' + scaleInput.value + '%';

              var buttonsWrap = document.createElement('div');
              buttonsWrap.style.display = 'flex';
              buttonsWrap.style.flexWrap = 'wrap';
              buttonsWrap.style.gap = '6px';
              buttonsWrap.style.marginTop = '10px';
              actionBox.appendChild(buttonsWrap);

              var makeBtn = function(label, click) {
                var btn = document.createElement('button');
                btn.textContent = label;
                btn.style.background = '#2f6ea3';
                btn.style.color = 'white';
                btn.style.border = '0';
                btn.style.borderRadius = '8px';
                btn.style.padding = '6px 10px';
                btn.style.fontSize = '12px';
                btn.style.cursor = 'pointer';
                btn.onclick = click;
                return btn;
              };

              var keepAliveEnabled = ${if (keepAliveEnabled) "true" else "false"};
              var keepAliveBtn = makeBtn('KeepAlive: ' + (keepAliveEnabled ? 'ON' : 'OFF'), function() {
                keepAliveEnabled = !keepAliveEnabled;
                keepAliveBtn.textContent = 'KeepAlive: ' + (keepAliveEnabled ? 'ON' : 'OFF');
                send('${BridgeCommandTypes.SET_KEEP_ALIVE}', { enabled: String(keepAliveEnabled) });
              });
              buttonsWrap.appendChild(keepAliveBtn);

              buttonsWrap.appendChild(makeBtn('Push perm', function() {
                send('${BridgeCommandTypes.REQUEST_PUSH_PERMISSION}', {});
              }));

              buttonsWrap.appendChild(makeBtn('Proxy', function() {
                var type = (prompt('Proxy type: HTTP / SOCKS5 / MTPROTO / DIRECT', 'SOCKS5') || '').trim().toUpperCase();
                if (!type || type === 'DIRECT') {
                  send('${BridgeCommandTypes.SET_PROXY}', { enabled: 'false' });
                  return;
                }
                var host = (prompt('Proxy host', '') || '').trim();
                var port = (prompt('Proxy port', '1080') || '').trim();
                var payload = { enabled: 'true', type: type, host: host, port: port };
                if (type === 'MTPROTO') {
                  payload.secret = (prompt('MTProto secret', '') || '').trim();
                } else {
                  payload.username = (prompt('Username (optional)', '') || '').trim();
                  payload.password = (prompt('Password (optional)', '') || '').trim();
                }
                send('${BridgeCommandTypes.SET_PROXY}', payload);
              }));

              var fab = document.createElement('button');
              fab.textContent = 'Menu';
              fab.style.width = '46px';
              fab.style.height = '46px';
              fab.style.borderRadius = '12px';
              fab.style.border = '0';
              fab.style.background = '#3390ec';
              fab.style.color = 'white';
              fab.style.fontSize = '26px';
              fab.style.lineHeight = '1';
              fab.style.boxShadow = '0 8px 20px rgba(6, 18, 34, 0.45)';
              fab.style.cursor = 'pointer';
              fab.onclick = function() {
                actionBox.style.display = actionBox.style.display === 'none' ? 'block' : 'none';
              };

              panel.appendChild(actionBox);
              panel.appendChild(fab);
              document.documentElement.appendChild(panel);

              var startX = 0, startY = 0, startTs = 0;
              document.addEventListener('touchstart', function(e) {
                if (!e.touches || e.touches.length !== 1) { return; }
                startX = e.touches[0].clientX;
                startY = e.touches[0].clientY;
                startTs = Date.now();
              }, { passive: true });

              document.addEventListener('touchend', function(e) {
                if (!e.changedTouches || e.changedTouches.length !== 1) { return; }
                var dx = e.changedTouches[0].clientX - startX;
                var dy = e.changedTouches[0].clientY - startY;
                var dt = Date.now() - startTs;
                if (dt > 900) { return; }
                if (Math.abs(dx) < 72 || Math.abs(dx) < Math.abs(dy) * 1.15) { return; }

                if (startX < 24 && dx > 0) {
                  history.back();
                  return;
                }

                var mediaOpen = !!document.querySelector('[class*="media"][class*="viewer"], [class*="MediaViewer"], [aria-label*="media"], [aria-label*="Media"]');
                if (mediaOpen) {
                  var key = dx < 0 ? 'ArrowRight' : 'ArrowLeft';
                  window.dispatchEvent(new KeyboardEvent('keydown', { key: key, bubbles: true }));
                }
              }, { passive: true });

              removeSwitchLinks();
              syncSystemBars();
              setInterval(removeSwitchLinks, 2200);
              setInterval(syncSystemBars, 2000);

              send('${BridgeCommandTypes.GET_PROXY_STATUS}', {});
              send('${BridgeCommandTypes.GET_KEEP_ALIVE_STATE}', {});
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
                { AppRepositories.postProxyState(proxyState) },
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
            { AppRepositories.postProxyState(proxyState) },
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
        private const val BUNDLED_WEBK_SRC_PUBLIC_URL = "file:///android_asset/webapp/webk-src/public/index.html"
        private const val REMOTE_WEBK_URL = "https://web.telegram.org/k/"
        private const val OFFLINE_URL = "file:///android_asset/webapp/offline.html"
    }
}
