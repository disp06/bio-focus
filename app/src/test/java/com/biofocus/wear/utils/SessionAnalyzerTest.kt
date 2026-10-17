package com.biofocus.wear.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAnalyzerTest {

    /** Feeds 1 Hz HR (± 1 bpm jitter) and evaluates every 10 s, like the service does. */
    private fun run(
        a: SessionAnalyzer,
        toMs: Long,
        hr: (Long) -> Double,
        step: (Long) -> Boolean = { false },
    ): List<Assessment> {
        val out = ArrayList<Assessment>()
        var t = 0L
        while (t < toMs) {
            a.addHr(t, hr(t))
            if (step(t)) a.addStep(t)
            if (t % 10_000L == 0L) out.add(a.evaluate(t))
            t += 1_000L
        }
        return out
    }

    private fun jitter(t: Long, base: Double) = base + if ((t / 1000) % 2 == 0L) 1.0 else -1.0

    @Test
    fun calibratesBaselineAfterThreeMinutes() {
        val a = SessionAnalyzer(0L)
        val out = run(a, 400_000L, { jitter(it, 70.0) })
        assertEquals(Phase.CALIBRATING, out.first { it.atMs == 180_000L }.phase)
        val firstMonitoring = out.first { it.phase == Phase.MONITORING }
        assertEquals(190_000L, firstMonitoring.atMs)
        assertEquals(70.0, firstMonitoring.baselineHr!!, 0.5)
        assertTrue(out.none { it.stressAlert || it.fatigueAlert })
    }

    @Test
    fun hrProxy_sustainedRiseAtRest_firesOneStressAlert() {
        val a = SessionAnalyzer(0L)
        val out = run(a, 600_000L, { if (it < 240_000L) jitter(it, 70.0) else jitter(it, 90.0) })
        val alerts = out.filter { it.stressAlert }
        assertEquals(1, alerts.size)
        assertTrue(alerts[0].atMs in 270_000L..360_000L)
        assertEquals(HrvMode.HR_PROXY, alerts[0].mode)
        assertTrue(out.none { it.fatigueAlert })
    }

    @Test
    fun hrRiseWhileWalking_isNotStress() {
        val a = SessionAnalyzer(0L)
        val out = run(
            a, 600_000L,
            hr = { if (it < 240_000L) jitter(it, 70.0) else jitter(it, 90.0) },
            step = { it >= 240_000L && it % 20_000L == 0L },
        )
        assertTrue(out.none { it.stressAlert })
    }

    @Test
    fun sustainedHrDropWhileStill_firesFatigue() {
        val a = SessionAnalyzer(0L)
        val out = run(a, 900_000L, { if (it < 240_000L) jitter(it, 70.0) else jitter(it, 58.0) })
        val alerts = out.filter { it.fatigueAlert }
        assertEquals(1, alerts.size) // 20-min cooldown
        assertTrue(alerts[0].atMs in 400_000L..560_000L)
        assertEquals(FatigueReason.HR_DROP, alerts[0].fatigueReason)
        assertTrue(out.none { it.stressAlert })
    }

    @Test
    fun flatSignal_neverAlerts() {
        val a = SessionAnalyzer(0L)
        val out = run(a, 1_800_000L, { jitter(it, 70.0) })
        assertTrue(out.none { it.stressAlert || it.fatigueAlert })
    }

    @Test
    fun beatToBeat_rmssdCollapse_firesStress() {
        val a = SessionAnalyzer(0L)
        val out = ArrayList<Assessment>()
        var t = 0L
        var i = 0
        var nextHr = 0L
        while (t < 600_000L) {
            val stressed = t >= 240_000L
            val rr = if (stressed) 650L + (if (i % 2 == 1) 8 else -8) else 800L + (if (i % 2 == 1) 40 else -40)
            t += rr
            i++
            a.addBeat(t)
            while (nextHr <= t) {
                a.addHr(nextHr, 60_000.0 / rr)
                if (nextHr % 10_000L == 0L) out.add(a.evaluate(nextHr))
                nextHr += 1_000L
            }
        }
        val calm = out.first { it.atMs == 200_000L }
        assertEquals(HrvMode.BEAT_TO_BEAT, calm.mode)
        assertEquals(80.0, calm.rmssd!!, 1.0)
        val alerts = out.filter { it.stressAlert }
        assertEquals(1, alerts.size)
        assertEquals(HrvMode.BEAT_TO_BEAT, alerts[0].mode)
    }
}
