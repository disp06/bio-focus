package com.biofocus.wear.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BioMathTest {

    @Test
    fun rmssd_matchesHandComputedValue() {
        val rr = listOf(800.0, 810.0, 790.0, 800.0, 820.0, 800.0, 790.0, 810.0, 800.0, 790.0, 800.0, 810.0)
        assertEquals(14.46, BioMath.rmssd(rr)!!, 0.01)
    }

    @Test
    fun rmssd_ignoresMissedBeatAndItsNeighbourPairs() {
        // 1600 ms = one missed beat. Without filtering RMSSD would explode to ~300 ms.
        val rr = listOf(800.0, 810.0, 790.0, 800.0, 820.0, 800.0, 1600.0, 790.0, 810.0, 800.0, 790.0, 800.0, 810.0)
        assertEquals(14.83, BioMath.rmssd(rr)!!, 0.01)
    }

    @Test
    fun rmssd_nullWhenTooFewPairs() {
        assertNull(BioMath.rmssd(listOf(800.0, 810.0, 790.0)))
    }

    @Test
    fun rrFromBeats_diffsTimestamps() {
        assertEquals(listOf(800.0, 750.0), BioMath.rrFromBeats(listOf(1000L, 1800L, 2550L)))
    }

    @Test
    fun meanSdMedian() {
        assertEquals(2.0, BioMath.mean(listOf(1.0, 2.0, 3.0))!!, 1e-9)
        assertEquals(1.0, BioMath.sd(listOf(1.0, 2.0, 3.0))!!, 1e-9)
        assertEquals(2.5, BioMath.median(listOf(4.0, 1.0, 3.0, 2.0)), 1e-9)
    }

    @Test
    fun sensorTime_batchedEventIsBackdated() {
        val now = 1_000_000L
        val elapsedNs = 50_000_000_000L
        val eventNs = elapsedNs - 120_000_000_000L // event is 120 s old
        assertEquals(now - 120_000L, BioMath.sensorTimeToWallMs(eventNs, now, elapsedNs))
    }

    @Test
    fun sensorTime_garbageFallsBackToNow() {
        assertEquals(5_000L, BioMath.sensorTimeToWallMs(0L, 5_000L, 999_000_000_000_000L))
    }

    @Test
    fun ewStats_movesTowardsSamples() {
        val s = EwStats(70.0, 4.0)
        repeat(200) { s.update(80.0, 0.05) }
        assertEquals(80.0, s.mean, 0.01)
    }

    @Test
    fun motionAccumulator_restingWristIsQuiet_walkingIsNot() {
        val rest = MotionAccumulator(5_000L)
        var restStd: Double? = null
        for (i in 0..300) {
            val z = 9.81f + if (i % 2 == 0) 0.02f else -0.02f
            rest.add(i * 20L, 0f, 0f, z)?.let { restStd = it.second }
        }
        assertNotNull(restStd)
        assertTrue(restStd!! < 0.1)

        val walk = MotionAccumulator(5_000L)
        var walkStd: Double? = null
        for (i in 0..300) {
            val z = 9.81f + 3f * kotlin.math.sin(i / 5.0).toFloat()
            walk.add(i * 20L, 0f, 0f, z)?.let { walkStd = it.second }
        }
        assertTrue(walkStd!! > 1.0)
    }
}
