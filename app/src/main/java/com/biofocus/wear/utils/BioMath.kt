package com.biofocus.wear.utils

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Pure maths for Bio-Focus. No Android imports, so everything here runs in plain JVM unit tests.
 */
object BioMath {

    /** 200 bpm. */
    const val MIN_RR_MS = 300.0

    /** 30 bpm. */
    const val MAX_RR_MS = 2000.0

    /** An RR interval more than 25 % away from the window median is treated as a missed/extra beat. */
    const val MAX_DEVIATION_FROM_MEDIAN = 0.25

    /** Beat timestamps (ms, ascending) -> RR intervals (ms). */
    fun rrFromBeats(beatsMs: List<Long>): List<Double> {
        if (beatsMs.size < 2) return emptyList()
        val out = ArrayList<Double>(beatsMs.size - 1)
        for (i in 1 until beatsMs.size) out.add((beatsMs[i] - beatsMs[i - 1]).toDouble())
        return out
    }

    /**
     * RMSSD (root mean square of successive RR differences), the standard short-term HRV metric.
     *
     * Artifact handling: an interval is invalid if it is outside 30–200 bpm or deviates more than 25 %
     * from the median of the window. A successive difference is only used when *both* neighbouring
     * intervals are valid, so a missed beat never produces a fake huge difference.
     *
     * @return RMSSD in ms, or null when fewer than [minPairs] clean pairs exist.
     */
    fun rmssd(rr: List<Double>, minPairs: Int = 10): Double? {
        if (rr.size < 2) return null
        val inRange = rr.filter { it in MIN_RR_MS..MAX_RR_MS }
        if (inRange.isEmpty()) return null
        val med = median(inRange)
        val valid = BooleanArray(rr.size) { i ->
            val v = rr[i]
            v in MIN_RR_MS..MAX_RR_MS && abs(v - med) / med <= MAX_DEVIATION_FROM_MEDIAN
        }
        var sumSq = 0.0
        var n = 0
        for (i in 1 until rr.size) {
            if (valid[i] && valid[i - 1]) {
                val d = rr[i] - rr[i - 1]
                sumSq += d * d
                n++
            }
        }
        if (n < minPairs) return null
        return sqrt(sumSq / n)
    }

    fun mean(xs: Collection<Double>): Double? = if (xs.isEmpty()) null else xs.sum() / xs.size

    /** Sample standard deviation (n − 1). */
    fun sd(xs: Collection<Double>): Double? {
        if (xs.size < 2) return null
        val m = xs.sum() / xs.size
        var acc = 0.0
        for (x in xs) acc += (x - m) * (x - m)
        return sqrt(acc / (xs.size - 1))
    }

    fun median(xs: Collection<Double>): Double {
        require(xs.isNotEmpty()) { "median of empty collection" }
        val s = xs.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /** Exponential smoothing factor for a sample arriving [dtMs] after the previous one. */
    fun alphaFor(dtMs: Long, tauMs: Long): Double =
        if (dtMs <= 0) 0.0 else 1.0 - exp(-dtMs.toDouble() / tauMs.toDouble())

    /**
     * Converts a SensorEvent timestamp (ns on the elapsedRealtime clock) to wall-clock ms.
     * Batched events can be minutes old, so the event's own timestamp matters, not "now".
     * Falls back to [nowWallMs] when the timestamp is implausible (some HALs report garbage).
     */
    fun sensorTimeToWallMs(eventTimestampNs: Long, nowWallMs: Long, nowElapsedNs: Long): Long {
        val ageMs = (nowElapsedNs - eventTimestampNs) / 1_000_000L
        return if (ageMs < -1_000L || ageMs > 6L * 60 * 60 * 1000) nowWallMs else nowWallMs - maxOf(ageMs, 0L)
    }
}

/** Exponentially weighted running mean + variance (used for the drifting HR baseline). */
class EwStats(mean: Double, variance: Double) {
    var mean: Double = mean
        private set
    var variance: Double = variance
        private set

    val sd: Double get() = sqrt(variance)

    fun update(x: Double, alpha: Double) {
        if (alpha <= 0.0) return
        val d = x - mean
        mean += alpha * d
        variance = (1 - alpha) * (variance + alpha * d * d)
    }
}

/**
 * Turns a raw accelerometer stream into one "activity" value per chunk: the standard deviation of
 * the acceleration magnitude (m/s²). Gravity cancels out, so ~0.05 = wrist resting,
 * ~0.2–0.6 = typing/gesturing, >1 = walking or exercise.
 */
class MotionAccumulator(private val chunkMs: Long = 5_000L) {
    private var chunkStart = -1L
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0

    /** @return (chunkEndMs, std) when a chunk completes, else null. */
    fun add(tMs: Long, x: Float, y: Float, z: Float): Pair<Long, Double>? {
        val mag = sqrt((x * x + y * y + z * z).toDouble())
        if (chunkStart < 0) chunkStart = tMs
        var result: Pair<Long, Double>? = null
        if (tMs - chunkStart >= chunkMs && n >= 2) {
            val m = sum / n
            val variance = (sumSq / n - m * m).coerceAtLeast(0.0)
            result = tMs to sqrt(variance)
            chunkStart = tMs
            n = 0; sum = 0.0; sumSq = 0.0
        }
        n++; sum += mag; sumSq += mag * mag
        return result
    }
}
