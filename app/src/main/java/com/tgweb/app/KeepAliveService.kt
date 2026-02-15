package com.tgweb.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TGWeb keep alive")
            .setContentText("Background sync and push relay are active")
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
            description = "Keeps TGWeb background service alive for sync/push reliability."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val PREFS = "tgweb_runtime"
        const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
        const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
        const val KEY_USE_BUNDLED_WEBK = "use_bundled_webk"

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
    }
}
