package com.biofocus.wear.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SedentaryRuleTest {

    private val utc = ZoneOffset.UTC
    private fun at(h: Int, m: Int, day: Int = 19) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, utc).toInstant().toEpochMilli()

    private fun input(
        lastMove: Long = 0L,
        lastAlert: Long = 0L,
        snooze: Long = 0L,
        start: Int = 9 * 60,
        end: Int = 19 * 60,
    ) = SedentaryRule.Input(lastMove, lastAlert, snooze, start, end, utc)

    @Test
    fun movedRecently_noAlert_dueInFortyFiveMinutes() {
        val i = input(lastMove = at(9, 30))
        assertFalse(SedentaryRule.shouldAlert(at(10, 0), i))
        assertEquals(at(10, 15), SedentaryRule.dueAt(at(10, 0), i))
    }

    @Test
    fun sittingFortyFiveMinutes_alerts() {
        assertTrue(SedentaryRule.shouldAlert(at(10, 0), input(lastMove = at(9, 0))))
    }

    @Test
    fun windowStartsAtActiveHours_notAtMidnight() {
        // Never moved today; at 09:10 nothing is due before 09:45.
        val i = input()
        assertFalse(SedentaryRule.shouldAlert(at(9, 10), i))
        assertEquals(at(9, 45), SedentaryRule.dueAt(at(9, 10), i))
    }

    @Test
    fun outsideActiveHours_neverAlerts_nextDueTomorrowMorning() {
        val i = input(lastMove = at(12, 0))
        assertFalse(SedentaryRule.shouldAlert(at(20, 0), i))
        assertEquals(at(9, 45, day = 20), SedentaryRule.dueAt(at(20, 0), i))
    }

    @Test
    fun overnightActiveHours_wrapMidnight() {
        val i = input(start = 22 * 60, end = 2 * 60)
        assertTrue(SedentaryRule.isActive(at(1, 0), i))
        assertFalse(SedentaryRule.isActive(at(3, 0), i))
        assertTrue(SedentaryRule.shouldAlert(at(1, 0), i))
    }

    @Test
    fun snooze_postponesAlert() {
        val i = input(lastMove = at(9, 0), snooze = at(10, 30))
        assertFalse(SedentaryRule.shouldAlert(at(10, 0), i))
        assertEquals(at(10, 30), SedentaryRule.dueAt(at(10, 0), i))
    }

    @Test
    fun afterAlert_repeatsOnlyAfterAnotherWindow() {
        val i = input(lastMove = at(9, 0), lastAlert = at(9, 50))
        assertFalse(SedentaryRule.shouldAlert(at(10, 20), i))
        assertEquals(at(10, 35), SedentaryRule.dueAt(at(10, 20), i))
    }
}
