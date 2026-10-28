package com.ismail.screenshotclipboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(ScreenshotWatcherService.PREFS_NAME, Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean(ScreenshotWatcherService.KEY_SERVICE_ENABLED, true)
            if (isEnabled) {
                val serviceIntent = Intent(context, ScreenshotWatcherService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
