package com.biofocus.wear.utils

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "0 steps over a rolling 45-minute window during active hours".
 *
 * Pure function of a few timestamps, so the service only needs to (re)schedule one exact alarm at
 * [dueAt] instead of polling. All times are wall-clock ms; 0 means "never".
 *
 * Active hours are minutes-of-day. start == end means "always active"; start > end wraps midnight
 * (e.g. 22:00–02:00).
 */
object SedentaryRule {

    const val WINDOW_MS = 45 * 60_000L

    /** Alarms can be delivered a little late; never early. Small tolerance avoids a 10-s re-arm. */
    const val TOLERANCE_MS = 60_000L

    data class Input(
        val lastMoveMs: Long,
        val lastAlertMs: Long,
        val snoozeUntilMs: Long,
        val activeStartMin: Int,
        val activeEndMin: Int,
        val zone: ZoneId,
        val windowMs: Long = WINDOW_MS,
    )

    /** When the next alert becomes due (may be in the past = overdue). */
    fun dueAt(nowMs: Long, input: Input): Long {
        val periodStart = if (isActive(nowMs, input)) currentPeriodStart(nowMs, input) else nextPeriodStart(nowMs, input)
        return maxOf(
            input.lastMoveMs + input.windowMs,
            input.lastAlertMs + input.windowMs,
            periodStart + input.windowMs,
            input.snoozeUntilMs,
        )
    }

    fun shouldAlert(nowMs: Long, input: Input): Boolean =
        isActive(nowMs, input) && nowMs >= dueAt(nowMs, input) - TOLERANCE_MS

    fun isActive(nowMs: Long, input: Input): Boolean {
        val s = input.activeStartMin
        val e = input.activeEndMin
        if (s == e) return true
        val m = minuteOfDay(nowMs, input.zone)
        return if (s < e) m in s until e else (m >= s || m < e)
    }

    private fun currentPeriodStart(nowMs: Long, input: Input): Long {
        val s = input.activeStartMin
        val e = input.activeEndMin
        val now = zdt(nowMs, input.zone)
        if (s == e) return now.with(LocalTime.MIDNIGHT).toInstant().toEpochMilli()
        val m = now.hour * 60 + now.minute
        val day = if (s > e && m < e) now.minusDays(1) else now
        return atMinute(day, s)
    }

    private fun nextPeriodStart(nowMs: Long, input: Input): Long {
        val s = input.activeStartMin
        val now = zdt(nowMs, input.zone)
        val todayStart = atMinute(now, s)
        return if (todayStart > nowMs) todayStart else atMinute(now.plusDays(1), s)
    }

    private fun atMinute(day: ZonedDateTime, minuteOfDay: Int): Long =
        day.with(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)).toInstant().toEpochMilli()

    private fun minuteOfDay(ms: Long, zone: ZoneId): Int = zdt(ms, zone).let { it.hour * 60 + it.minute }

    private fun zdt(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    fun formatMinuteOfDay(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
}
