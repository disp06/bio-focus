package com.biofocus.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.biofocus.wear.R
import com.biofocus.wear.ui.BreathingActivity
import com.biofocus.wear.ui.FocusAmbientActivity
import com.biofocus.wear.ui.MainActivity

/**
 * All notifications are built and posted locally on the watch (setLocalOnly), no phone involved.
 */
object Alerts {

    const val CH_TRACKING = "tracking"
    const val CH_SEDENTARY = "sedentary"
    const val CH_STRESS = "stress"
    const val CH_FATIGUE = "fatigue_v1"
    const val CH_SESSION = "session"

    const val ID_FOREGROUND = 1
    const val ID_SEDENTARY = 10
    const val ID_STRESS = 11
    const val ID_FATIGUE = 12
    const val ID_SESSION_DONE = 13

    private val SEDENTARY_PATTERN = longArrayOf(0, 300, 200, 300)
    private val STRESS_PATTERN = longArrayOf(0, 120, 150, 120)

    // Fatigue = "high-intensity": long pulses at full amplitude, vibrated manually.
    private val FATIGUE_TIMINGS = longArrayOf(0, 600, 150, 600, 150, 900)
    private val FATIGUE_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val tracking = NotificationChannel(
            CH_TRACKING, ctx.getString(R.string.ch_tracking), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        val sedentary = NotificationChannel(
            CH_SEDENTARY, ctx.getString(R.string.ch_sedentary), NotificationManager.IMPORTANCE_HIGH,
        ).apply { enableVibration(true); vibrationPattern = SEDENTARY_PATTERN }
        val stress = NotificationChannel(
            CH_STRESS, ctx.getString(R.string.ch_stress), NotificationManager.IMPORTANCE_HIGH,
        ).apply { enableVibration(true); vibrationPattern = STRESS_PATTERN }
        // Vibration for fatigue is done by hand (full amplitude), so the channel itself stays silent.
        val fatigue = NotificationChannel(
            CH_FATIGUE, ctx.getString(R.string.ch_fatigue), NotificationManager.IMPORTANCE_HIGH,
        ).apply { enableVibration(false); setSound(null, null) }
        val session = NotificationChannel(
            CH_SESSION, ctx.getString(R.string.ch_session), NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannels(listOf(tracking, sedentary, stress, fatigue, session))
    }

    fun foreground(ctx: Context, text: String, sessionActive: Boolean): Notification {
        val open = if (sessionActive) {
            activityIntent(ctx, 1, Intent(ctx, FocusAmbientActivity::class.java))
        } else {
            activityIntent(ctx, 2, Intent(ctx, MainActivity::class.java))
        }
        return NotificationCompat.Builder(ctx, CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(ContextCompat.getColor(ctx, R.color.accent))
            .setContentIntent(open)
            .build()
    }

    fun showSedentary(ctx: Context) {
        val text = ctx.getString(R.string.alert_sedentary)
        val n = alertBuilder(ctx, CH_SEDENTARY, text)
            .setVibrate(SEDENTARY_PATTERN)
            .setContentIntent(activityIntent(ctx, 3, Intent(ctx, MainActivity::class.java)))
            .addAction(
                R.drawable.ic_action_snooze, ctx.getString(R.string.action_snooze),
                NotificationReceiver.pending(ctx, NotificationReceiver.ACTION_SNOOZE_SEDENTARY, ID_SEDENTARY),
            )
            .addAction(
                R.drawable.ic_action_check, ctx.getString(R.string.action_done),
                NotificationReceiver.pending(ctx, NotificationReceiver.ACTION_DISMISS, ID_SEDENTARY),
            )
            .build()
        post(ctx, ID_SEDENTARY, n)
    }

    fun showStress(ctx: Context) {
        val text = ctx.getString(R.string.alert_stress)
        val breathe = activityIntent(ctx, 4, Intent(ctx, BreathingActivity::class.java))
        val n = alertBuilder(ctx, CH_STRESS, text)
            .setVibrate(STRESS_PATTERN)
            .setContentIntent(breathe)
            .addAction(R.drawable.ic_action_breathe, ctx.getString(R.string.action_breathe), breathe)
            .addAction(
                R.drawable.ic_action_check, ctx.getString(R.string.action_dismiss),
                NotificationReceiver.pending(ctx, NotificationReceiver.ACTION_DISMISS, ID_STRESS),
            )
            .build()
        post(ctx, ID_STRESS, n)
    }

    fun showFatigue(ctx: Context) {
        val text = ctx.getString(R.string.alert_fatigue)
        val n = alertBuilder(ctx, CH_FATIGUE, text)
            .setContentIntent(activityIntent(ctx, 5, Intent(ctx, FocusAmbientActivity::class.java)))
            .addAction(
                R.drawable.ic_action_check, ctx.getString(R.string.action_done),
                NotificationReceiver.pending(ctx, NotificationReceiver.ACTION_DISMISS, ID_FATIGUE),
            )
            .build()
        post(ctx, ID_FATIGUE, n)
        if (interruptionsAllowed(ctx)) vibrate(ctx, FATIGUE_TIMINGS, FATIGUE_AMPLITUDES)
    }

    fun showSessionDone(ctx: Context, minutes: Int) {
        val n = alertBuilder(ctx, CH_SESSION, ctx.getString(R.string.session_done, minutes))
            .setContentIntent(activityIntent(ctx, 6, Intent(ctx, MainActivity::class.java)))
            .build()
        post(ctx, ID_SESSION_DONE, n)
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /** Tiny haptic tick (breathing guide). */
    fun tick(ctx: Context) {
        if (!interruptionsAllowed(ctx)) return
        vibrator(ctx)?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun alertBuilder(ctx: Context, channel: String, text: String) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setColor(ContextCompat.getColor(ctx, R.color.accent))
            .setAutoCancel(true)
            .setLocalOnly(true)

    private fun post(ctx: Context, id: Int, n: Notification) {
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
    }

    private fun activityIntent(ctx: Context, requestCode: Int, intent: Intent): PendingIntent {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Manual vibration bypasses the notification system, so respect Do Not Disturb / theater mode ourselves. */
    private fun interruptionsAllowed(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return true
        val f = nm.currentInterruptionFilter
        return f == NotificationManager.INTERRUPTION_FILTER_ALL || f == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator? = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun vibrate(ctx: Context, timings: LongArray, amplitudes: IntArray) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        v.vibrate(effect)
    }
}
