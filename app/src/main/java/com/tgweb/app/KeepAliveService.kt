package com.tgweb.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore
import com.tgweb.core.sync.SyncScheduler
import com.tgweb.core.tdlib.TdAuthorizationState
import com.tgweb.core.tdlib.TdConnectionState
import com.tgweb.core.tdlib.TdRuntimeState
import com.tgweb.core.webbridge.BackgroundStateSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KeepAliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyListenerDisposer: (() -> Unit)? = null
    private var backgroundStateListenerDisposer: (() -> Unit)? = null
    private var runtimeCollectorJob: Job? = null
    private var startupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(AppRepositories.getBackgroundState()))
        SyncScheduler.schedulePeriodic(this)
        AppRepositories.postKeepAliveState(true)
        startBackgroundCore()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isEnabled(this)) {
            start(this)
        }
    }

    override fun onDestroy() {
        proxyListenerDisposer?.invoke()
        proxyListenerDisposer = null
        backgroundStateListenerDisposer?.invoke()
        backgroundStateListenerDisposer = null
        runtimeCollectorJob?.cancel()
        runtimeCollectorJob = null
        startupJob?.cancel()
        startupJob = null
        runBlocking(Dispatchers.IO) {
            if (AppRepositories.isInitialized()) {
                runCatching { AppRepositories.tdLibGateway.stop() }
                    .onFailure { DebugLogStore.logError("TDLIB", "Failed to stop background core", it) }
            }
            AppRepositories.postBackgroundState(
                BackgroundStateSnapshot(
                    running = false,
                    connected = false,
                    authorized = false,
                    syncing = false,
                    transportLabel = "stopped",
                    details = "Background core stopped",
                    lastEventAt = System.currentTimeMillis(),
                )
            )
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBackgroundCore() {
        if (!AppRepositories.isInitialized()) {
            DebugLogStore.log("TDLIB", "KeepAliveService start skipped: repositories not initialized")
            return
        }

        backgroundStateListenerDisposer?.invoke()
        backgroundStateListenerDisposer = AppRepositories.registerBackgroundStateListener { snapshot ->
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(snapshot))
        }

        runtimeCollectorJob?.cancel()
        runtimeCollectorJob = serviceScope.launch {
            AppRepositories.tdLibGateway.observeRuntimeState().collectLatest { runtime ->
                val snapshot = runtime.toBackgroundSnapshot()
                DebugLogStore.log(
                    "TDLIB",
                    "Runtime state running=${snapshot.running} connected=${snapshot.connected} authorized=${snapshot.authorized} syncing=${snapshot.syncing} transport=${snapshot.transportLabel} details=${snapshot.details}",
                )
                AppRepositories.postBackgroundState(snapshot)
            }
        }

        proxyListenerDisposer?.invoke()
        proxyListenerDisposer = AppRepositories.registerProxyStateListener { proxy ->
            serviceScope.launch {
                DebugLogStore.log(
                    "TDLIB",
                    "Applying proxy to background core: enabled=${proxy.enabled} type=${proxy.type} host=${proxy.host}:${proxy.port}",
                )
                runCatching { AppRepositories.tdLibGateway.setProxy(proxy) }
                    .onFailure { DebugLogStore.logError("TDLIB", "Proxy apply to background core failed", it) }
            }
        }

        startupJob?.cancel()
        startupJob = serviceScope.launch {
            runCatching {
                DebugLogStore.log("TDLIB", "Starting background core from KeepAliveService")
                AppRepositories.tdLibGateway.start()
                AppRepositories.chatRepository.syncNow(reason = "keep_alive_start")
                DebugLogStore.log("TDLIB", "Background core initial sync done")
            }.onFailure {
                DebugLogStore.logError("TDLIB", "Background core start failed", it)
                AppRepositories.postBackgroundState(
                    BackgroundStateSnapshot(
                        running = true,
                        connected = false,
                        authorized = false,
                        syncing = false,
                        transportLabel = "error",
                        details = it.message ?: "Background core start failed",
                        lastEventAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    private fun buildNotification(state: BackgroundStateSnapshot): Notification {
        val tint = contextColor(this)
        val title = when {
            !state.running -> "FlyGram background core stopped"
            !state.authorized -> "FlyGram background core starting"
            state.syncing -> "FlyGram background core syncing"
            state.connected -> "FlyGram background core connected"
            else -> "FlyGram background core reconnecting"
        }
        val text = state.details.ifBlank {
            when {
                state.connected -> "Native message sync is active"
                state.running -> "Background keep-alive is active"
                else -> "Background sync is idle"
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(tint)
            .setColorized(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Keep alive",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps FlyGram background core alive for sync and notification reliability."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun TdRuntimeState.toBackgroundSnapshot(): BackgroundStateSnapshot {
        return BackgroundStateSnapshot(
            running = running,
            connected = connectionState == TdConnectionState.CONNECTED,
            authorized = authorizationState == TdAuthorizationState.READY,
            syncing = syncing,
            transportLabel = transportLabel,
            details = details,
            lastEventAt = lastUpdatedAt,
        )
    }

    companion object {
        const val PREFS = "tgweb_runtime"
        const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
        const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
        const val KEY_USE_BUNDLED_WEBK = "use_bundled_webk"
        const val KEY_STATUS_BAR_COLOR = "status_bar_color"
        const val KEY_NAV_BAR_COLOR = "nav_bar_color"
        const val KEY_NOTIFICATION_COLOR = "notification_color"
        const val KEY_PUSH_PERMISSION_PROMPTED = "push_permission_prompted"
        const val KEY_HIDE_STORIES = "hide_stories"
        const val KEY_MD3_EFFECTS = "md3_effects"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_MD3_CONTAINER_STYLE = "md3_container_style"
        const val KEY_MD3_HIDE_BASE_PLATES = "md3_hide_base_plates"
        const val KEY_MENU_HIDE_MORE = "menu_hide_more"
        const val KEY_MENU_SHOW_DOWNLOADS = "menu_show_downloads"
        const val KEY_MENU_SHOW_MOD_SETTINGS = "menu_show_mod_settings"
        const val KEY_MENU_SHOW_DIVIDERS = "menu_show_dividers"
        const val KEY_MENU_DOWNLOADS_POSITION = "menu_downloads_position"
        const val KEY_REPLY_AUTO_FOCUS = "reply_auto_focus"
        const val KEY_PENDING_WEB_RELOAD = "pending_web_reload"

        private const val CHANNEL_ID = "tgweb_keep_alive"
        private const val NOTIFICATION_ID = 10102

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEP_ALIVE_ENABLED, true)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled)
                .apply()
        }

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        fun contextColor(context: Context): Int {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_NOTIFICATION_COLOR, "#3390EC")
                .orEmpty()
            return runCatching { Color.parseColor(raw) }.getOrDefault(Color.parseColor("#3390EC"))
        }
    }
}
