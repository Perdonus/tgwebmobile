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
import com.tgweb.core.sync.SyncScheduler

class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        SyncScheduler.schedulePeriodic(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isEnabled(this)) {
            start(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val tint = contextColor(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FlyGram keep alive")
            .setContentText("Background sync and push relay are active")
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
            description = "Keeps FlyGram background service alive for sync/push reliability."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
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
