package com.tgweb.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (KeepAliveService.isEnabled(context)) {
            KeepAliveService.start(context)
        }
    }
}
