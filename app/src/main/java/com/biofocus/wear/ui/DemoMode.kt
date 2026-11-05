package com.biofocus.wear.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.biofocus.wear.service.Alerts
import com.biofocus.wear.service.BioTrackingService
import com.biofocus.wear.service.LiveState
import com.biofocus.wear.utils.Assessment
import com.biofocus.wear.utils.HrvMode
import com.biofocus.wear.utils.Phase
import com.biofocus.wear.utils.Prefs

/**
 * Debug-only hook for screenshots / demos. Only works in debuggable builds:
 *
 *   adb shell am start -n com.biofocus.wear/.ui.MainActivity --es demo <screen>
 *
 * screen = track | focus_calm | focus_stress | focus_calibrating | breathing | sensors |
 *          alert_sedentary | alert_stress | alert_fatigue
 */
object DemoMode {

    const val EXTRA = "demo"

    /** @return true if the intent was a demo request and has been handled. */
    fun handle(activity: Activity, intent: Intent?): Boolean {
        val screen = intent?.getStringExtra(EXTRA) ?: return false
        if ((activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return false
        intent.removeExtra(EXTRA)
        val now = System.currentTimeMillis()
        val prefs = Prefs(activity)
        when (screen) {
            "track" -> {
                prefs.trackingEnabled = true
                prefs.lastMoveAt = now - 23 * 60_000L
                BioTrackingService.send(activity, BioTrackingService.ACTION_START_TRACKING)
            }
            "focus_calm" -> focus(activity, now, stressIndex = 31, hr = 72.0, phase = Phase.MONITORING)
            "focus_stress" -> focus(activity, now, stressIndex = 84, hr = 91.0, phase = Phase.MONITORING)
            "focus_calibrating" -> focus(activity, now, stressIndex = null, hr = 76.0, phase = Phase.CALIBRATING)
            "breathing" -> activity.startActivity(Intent(activity, BreathingActivity::class.java))
            "sensors" -> activity.startActivity(Intent(activity, SensorsActivity::class.java))
            "alert_sedentary" -> { Alerts.showSedentary(activity); activity.finish() }
            "alert_stress" -> { Alerts.showStress(activity); activity.finish() }
            "alert_fatigue" -> { Alerts.showFatigue(activity); activity.finish() }
            else -> return false
        }
        return true
    }

    private fun focus(activity: Activity, now: Long, stressIndex: Int?, hr: Double, phase: Phase) {
        val a = Assessment(
            atMs = now, phase = phase, mode = HrvMode.HR_PROXY,
            hr = hr, rmssd = null,
            baselineHr = if (phase == Phase.MONITORING) 68.0 else null, baselineRmssd = null,
            spo2 = null, moving = false, stressIndex = stressIndex,
            stressAlert = false, fatigueAlert = false, fatigueReason = null,
        )
        LiveState.update {
            it.copy(
                tracking = true,
                sessionStartAt = now - 12 * 60_000L - 36_000L,
                sessionEndAt = now + 37 * 60_000L + 24_000L,
                assessment = a,
                problem = null,
            )
        }
        activity.startActivity(Intent(activity, FocusAmbientActivity::class.java))
    }
}
