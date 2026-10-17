package com.biofocus.wear.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.biofocus.wear.utils.Prefs

/** Restarts tracking after a reboot or an app update (handy while iterating over ADB). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs(context).trackingEnabled) {
            BioTrackingService.send(context, BioTrackingService.ACTION_START_TRACKING)
        }
    }
}
