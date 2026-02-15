package com.tgweb.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tgweb.core.sync.SyncScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            SyncScheduler.schedulePeriodic(context)
        }
    }
}
