package com.biofocus.wear.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.biofocus.wear.utils.Prefs

/**
 * Receives the sedentary alarm and the notification action buttons.
 * Not exported: only our own PendingIntents can reach it.
 */
class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SEDENTARY_ALARM -> {
                Log.d(TAG, "sedentary alarm fired")
                BioTrackingService.send(context, BioTrackingService.ACTION_SEDENTARY_CHECK)
            }
            ACTION_SNOOZE_SEDENTARY -> {
                Prefs(context).snoozeUntil = System.currentTimeMillis() + SNOOZE_MS
                Alerts.cancel(context, Alerts.ID_SEDENTARY)
                BioTrackingService.send(context, BioTrackingService.ACTION_SETTINGS_CHANGED)
            }
            ACTION_DISMISS -> {
                val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (id != -1) Alerts.cancel(context, id)
            }
        }
    }

    companion object {
        private const val TAG = "BioFocus"
        const val ACTION_SEDENTARY_ALARM = "com.biofocus.wear.SEDENTARY_ALARM"
        const val ACTION_SNOOZE_SEDENTARY = "com.biofocus.wear.SNOOZE_SEDENTARY"
        const val ACTION_DISMISS = "com.biofocus.wear.DISMISS"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val SNOOZE_MS = 30 * 60_000L

        private const val RC_ALARM = 100

        fun pending(ctx: Context, action: String, notificationId: Int): PendingIntent {
            val i = Intent(ctx, NotificationReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            // Distinct request code per (action, id) so extras don't overwrite each other.
            val rc = action.hashCode() * 31 + notificationId
            return PendingIntent.getBroadcast(ctx, rc, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun alarmIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, NotificationReceiver::class.java).setAction(ACTION_SEDENTARY_ALARM)
            return PendingIntent.getBroadcast(ctx, RC_ALARM, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** One exact, doze-proof alarm at the moment the sedentary alert becomes due. No polling. */
        fun scheduleSedentaryAlarm(ctx: Context, atMs: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarmIntent(ctx))
            Log.d(TAG, "sedentary check scheduled in ${(atMs - System.currentTimeMillis()) / 1000}s")
        }

        fun cancelSedentaryAlarm(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx))
        }
    }
}
