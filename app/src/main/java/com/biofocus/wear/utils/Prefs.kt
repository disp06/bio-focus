package com.biofocus.wear.utils

import android.content.Context
import android.content.SharedPreferences
import java.time.ZoneId

/** Small persisted state. Everything survives process death and reboots. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("biofocus", Context.MODE_PRIVATE)

    var trackingEnabled: Boolean
        get() = sp.getBoolean(K_TRACKING, false)
        set(v) = sp.edit().putBoolean(K_TRACKING, v).apply()

    var activeStartMin: Int
        get() = sp.getInt(K_ACTIVE_START, 9 * 60)
        set(v) = sp.edit().putInt(K_ACTIVE_START, v.mod(24 * 60)).apply()

    var activeEndMin: Int
        get() = sp.getInt(K_ACTIVE_END, 19 * 60)
        set(v) = sp.edit().putInt(K_ACTIVE_END, v.mod(24 * 60)).apply()

    var sessionMinutes: Int
        get() = sp.getInt(K_SESSION_MIN, 50)
        set(v) = sp.edit().putInt(K_SESSION_MIN, v.coerceIn(5, 240)).apply()

    var sessionStartAt: Long
        get() = sp.getLong(K_SESSION_START, 0L)
        set(v) = sp.edit().putLong(K_SESSION_START, v).apply()

    var sessionEndAt: Long
        get() = sp.getLong(K_SESSION_END, 0L)
        set(v) = sp.edit().putLong(K_SESSION_END, v).apply()

    var lastMoveAt: Long
        get() = sp.getLong(K_LAST_MOVE, 0L)
        set(v) = sp.edit().putLong(K_LAST_MOVE, v).apply()

    /** Last seen value of the cumulative step counter, -1 = unknown. */
    var lastStepTotal: Long
        get() = sp.getLong(K_STEP_TOTAL, -1L)
        set(v) = sp.edit().putLong(K_STEP_TOTAL, v).apply()

    var lastSedentaryAlertAt: Long
        get() = sp.getLong(K_LAST_SED_ALERT, 0L)
        set(v) = sp.edit().putLong(K_LAST_SED_ALERT, v).apply()

    var snoozeUntil: Long
        get() = sp.getLong(K_SNOOZE, 0L)
        set(v) = sp.edit().putLong(K_SNOOZE, v).apply()

    fun sessionActive(now: Long = System.currentTimeMillis()) = sessionEndAt > now

    fun sedentaryInput() = SedentaryRule.Input(
        lastMoveMs = lastMoveAt,
        lastAlertMs = lastSedentaryAlertAt,
        snoozeUntilMs = snoozeUntil,
        activeStartMin = activeStartMin,
        activeEndMin = activeEndMin,
        zone = ZoneId.systemDefault(),
    )

    private companion object {
        const val K_TRACKING = "tracking_enabled"
        const val K_ACTIVE_START = "active_start_min"
        const val K_ACTIVE_END = "active_end_min"
        const val K_SESSION_MIN = "session_minutes"
        const val K_SESSION_START = "session_start_at"
        const val K_SESSION_END = "session_end_at"
        const val K_LAST_MOVE = "last_move_at"
        const val K_STEP_TOTAL = "last_step_total"
        const val K_LAST_SED_ALERT = "last_sedentary_alert_at"
        const val K_SNOOZE = "snooze_until"
    }
}
