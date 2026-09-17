package com.xudong.suotu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the watcher after a reboot. Scheduled jobs do not survive a restart, so
 * without this the auto-shrink would silently stop working until the app was opened.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings(context).autoShrinkEnabled) {
            ScreenshotWatcherJob.schedule(context)
        }
    }
}
