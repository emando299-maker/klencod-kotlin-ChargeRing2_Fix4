package com.floatingchargering

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts the background service after the phone boots (and after this app is updated),
 * but only if the ring window or an alert is switched on, so the user's choice is respected.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(FloatingRingService.PREFS, Context.MODE_PRIVATE)
        if (!FloatingRingService.isWanted(prefs)) return
        val service = Intent(context, FloatingRingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
        } catch (e: Exception) {
            // The system refused to start it in the background; it will start the next time the app is opened.
        }
    }
}
