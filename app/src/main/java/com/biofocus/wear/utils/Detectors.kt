package com.biofocus.wear.utils

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Tunables for stress and fatigue detection. Defaults are conservative starting points: the goal
 * is "rarely wrong" over "never misses". Tune against your own data (see README).
 */
data class DetectorConfig(
    /** Personal baseline is learned during the first minutes of every focus session. */
    val calibrationMs: Long = 3 * 60_000L,
    /** Ignore the first seconds: optical HR needs time to lock on. */
    val settleMs: Long = 30_000L,
    /** Minimum number of 10-s evaluation ticks with usable data before leaving calibration. */
    val minCalibrationTicks: Int = 6,
    val hrWindowMs: Long = 60_000L,
    val rrWindowMs: Long = 60_000L,
    val minHrSamplesPerWindow: Int = 5,
    /** After steps / strong motion, HR stays up for a while: suppress stress during this time. */
    val motionGateMs: Long = 3 * 60_000L,
    val movingMotionStd: Double = 1.0,
    val stillMotionStd: Double = 0.35,
    /** Slow drift of the baseline so it follows you through a long session. */
    val baselineTauMs: Long = 20 * 60_000L,
    val minSdBpm: Double = 2.0,

    // ---- Stress (HRV drop) ----
    /** Beat-to-beat mode: RMSSD at or below 60 % of baseline (= a 40 % drop)… */
    val stressRmssdRatio: Double = 0.6,
    /** …accompanied by at least this HR rise (filters out breathing artefacts). */
    val stressHrRiseWithBeats: Double = 3.0,
    /** HR-proxy mode: HR rise of max(12 bpm, 2.5 SD) over baseline while not moving. */
    val stressHrRiseMinBpm: Double = 12.0,
    val stressHrRiseSd: Double = 2.5,
    val stressSustainMs: Long = 45_000L,
    val stressCooldownMs: Long = 15 * 60_000L,

    // ---- Fatigue (HR / SpO2 drop) ----
    val fatigueWindowMs: Long = 2 * 60_000L,
    val fatigueHrDropMinBpm: Double = 8.0,
    val fatigueHrDropSd: Double = 2.0,
    val fatigueNoStepsMs: Long = 5 * 60_000L,
    val fatigueSpo2Threshold: Double = 92.0,
    val fatigueSustainMs: Long = 3 * 60_000L,
    val fatigueCooldownMs: Long = 20 * 60_000L,

    val retentionMs: Long = 5 * 60_000L,
)

enum class Phase { CALIBRATING, MONITORING }

/** Where the "HRV" number comes from. */
enum class HrvMode {
    /** Real RMSSD from Sensor.TYPE_HEART_BEAT beat timestamps. */
    BEAT_TO_BEAT,

    /** No beat-to-beat data on this watch: stress inferred from HR elevation at rest. */
    HR_PROXY,
}

enum class FatigueReason { HR_DROP, SPO2_LOW }

data class Assessment(
    val atMs: Long,
    val phase: Phase,
    val mode: HrvMode,
    val hr: Double?,
    val rmssd: Double?,
    val baselineHr: Double?,
    val baselineRmssd: Double?,
    val spo2: Double?,
    val moving: Boolean,
    /** 0–100, ~70 = alert threshold. Null while calibrating or without data. */
    val stressIndex: Int?,
    val stressAlert: Boolean,
    val fatigueAlert: Boolean,
    val fatigueReason: FatigueReason?,
)

private class Sample(val t: Long, val v: Double)

/**
 * Real-time analysis of one focus session. Feed raw samples with add*() and call [evaluate]
 * periodically (every ~10 s). All timestamps are wall-clock ms.
 */
class SessionAnalyzer(
    private val sessionStartMs: Long,
    private val cfg: DetectorConfig = DetectorConfig(),
) {
    private val hr = ArrayDeque<Sample>()
    private val beats = ArrayDeque<Long>()
    private val motion = ArrayDeque<Sample>()
    private val spo2 = ArrayDeque<Sample>()
    private var lastStepMs: Long? = null

    private val calHr = ArrayList<Double>()
    private val calRmssd = ArrayList<Double>()
    private var hrBase: EwStats? = null
    private var rmssdBase: EwStats? = null

    private var stressSince: Long? = null
    private var fatigueSince: Long? = null
    private var lastStressAlert: Long? = null
    private var lastFatigueAlert: Long? = null
    private var lastEvalMs: Long? = null

    val phase: Phase get() = if (hrBase == null) Phase.CALIBRATING else Phase.MONITORING

    fun addHr(tMs: Long, bpm: Double) {
        if (bpm < 30.0 || bpm > 220.0) return
        hr.addLast(Sample(tMs, bpm))
    }

    fun addBeat(tMs: Long) {
        val last = beats.lastOrNull()
        if (last != null && tMs <= last) return
        beats.addLast(tMs)
    }

    fun addMotion(tMs: Long, std: Double) = motion.addLast(Sample(tMs, std))

    fun addStep(tMs: Long) {
        lastStepMs = max(lastStepMs ?: tMs, tMs)
    }

    fun addSpo2(tMs: Long, pct: Double) {
        if (pct < 70.0 || pct > 100.0) return
        spo2.addLast(Sample(tMs, pct))
    }

    fun evaluate(nowMs: Long): Assessment {
        prune(nowMs - cfg.retentionMs)
        val dt = lastEvalMs?.let { (nowMs - it).coerceIn(0L, 60_000L) } ?: 0L
        lastEvalMs = nowMs

        val hr60 = meanSince(hr, nowMs - cfg.hrWindowMs, cfg.minHrSamplesPerWindow)
        val rr = BioMath.rrFromBeats(beats.filter { it >= nowMs - cfg.rrWindowMs })
        val rmssd = BioMath.rmssd(rr)
        val spo2Now = meanSince(spo2, nowMs - cfg.fatigueWindowMs, 3)
        val moving = isMoving(nowMs)

        // Learn the RMSSD baseline whenever beat data shows up and we're at rest.
        if (rmssd != null && !moving && rmssdBase == null &&
            nowMs - sessionStartMs >= cfg.settleMs
        ) {
            calRmssd.add(rmssd)
            if (calRmssd.size >= cfg.minCalibrationTicks) {
                rmssdBase = EwStats(BioMath.median(calRmssd), 0.0)
            }
        }

        // ---------------- Calibration ----------------
        val base = hrBase
        if (base == null) {
            if (nowMs - sessionStartMs >= cfg.settleMs && !moving && hr60 != null) calHr.add(hr60)
            if (nowMs - sessionStartMs >= cfg.calibrationMs && calHr.size >= cfg.minCalibrationTicks) {
                val m = BioMath.mean(calHr)!!
                val s = max(BioMath.sd(calHr) ?: 0.0, cfg.minSdBpm)
                hrBase = EwStats(m, s * s)
            }
            return Assessment(
                atMs = nowMs, phase = Phase.CALIBRATING,
                mode = if (rmssd != null) HrvMode.BEAT_TO_BEAT else HrvMode.HR_PROXY,
                hr = hr60, rmssd = rmssd, baselineHr = null, baselineRmssd = null,
                spo2 = spo2Now, moving = moving, stressIndex = null,
                stressAlert = false, fatigueAlert = false, fatigueReason = null,
            )
        }

        // ---------------- Monitoring ----------------
        val baseSd = max(base.sd, cfg.minSdBpm)
        val rBase = rmssdBase
        val mode = if (rmssd != null && rBase != null) HrvMode.BEAT_TO_BEAT else HrvMode.HR_PROXY

        // Stress
        var stressCond = false
        var stressIndex: Int? = null
        if (hr60 != null) {
            if (mode == HrvMode.BEAT_TO_BEAT) {
                val ratio = rmssd!! / max(rBase!!.mean, 1.0)
                stressIndex = scale((1.0 - ratio) / (1.0 - cfg.stressRmssdRatio))
                stressCond = ratio <= cfg.stressRmssdRatio && hr60 >= base.mean + cfg.stressHrRiseWithBeats
            } else {
                val threshold = max(cfg.stressHrRiseMinBpm, cfg.stressHrRiseSd * baseSd)
                stressIndex = scale((hr60 - base.mean) / threshold)
                stressCond = hr60 >= base.mean + threshold
            }
            if (moving) stressCond = false
        }

        // Fatigue
        val hrLong = meanSince(hr, nowMs - cfg.fatigueWindowMs, cfg.minHrSamplesPerWindow * 2)
        val dropThreshold = max(cfg.fatigueHrDropMinBpm, cfg.fatigueHrDropSd * baseSd)
        val hrDrop = hrLong != null && hrLong <= base.mean - dropThreshold
        val spo2Low = spo2Now != null && spo2Now < cfg.fatigueSpo2Threshold
        val fatigueReason = when {
            hrDrop && isStill(nowMs) -> FatigueReason.HR_DROP
            spo2Low && !moving -> FatigueReason.SPO2_LOW
            else -> null
        }
        val fatigueCond = fatigueReason != null

        // Sustain + cooldown
        var stressAlert = false
        if (stressCond) {
            val since = stressSince ?: nowMs.also { stressSince = it }
            if (nowMs - since >= cfg.stressSustainMs && cooled(lastStressAlert, nowMs, cfg.stressCooldownMs)) {
                stressAlert = true
                lastStressAlert = nowMs
                stressSince = null
            }
        } else {
            stressSince = null
        }

        var fatigueAlert = false
        if (fatigueCond) {
            val since = fatigueSince ?: nowMs.also { fatigueSince = it }
            if (nowMs - since >= cfg.fatigueSustainMs && cooled(lastFatigueAlert, nowMs, cfg.fatigueCooldownMs)) {
                fatigueAlert = true
                lastFatigueAlert = nowMs
                fatigueSince = null
            }
        } else {
            fatigueSince = null
        }

        // Let the baseline follow slow drift, but never learn from the states we're trying to catch.
        if (!moving && !stressCond && !fatigueCond && stressSince == null && fatigueSince == null) {
            val alpha = BioMath.alphaFor(dt, cfg.baselineTauMs)
            if (hr60 != null) base.update(hr60, alpha)
            if (rmssd != null) rBase?.update(rmssd, alpha)
        }

        return Assessment(
            atMs = nowMs, phase = Phase.MONITORING, mode = mode,
            hr = hr60, rmssd = rmssd, baselineHr = base.mean, baselineRmssd = rBase?.mean,
            spo2 = spo2Now, moving = moving, stressIndex = stressIndex,
            stressAlert = stressAlert, fatigueAlert = fatigueAlert,
            fatigueReason = if (fatigueAlert) fatigueReason else null,
        )
    }

    private fun isMoving(nowMs: Long): Boolean {
        val step = lastStepMs
        if (step != null && nowMs - step < cfg.motionGateMs) return true
        return motion.any { it.t >= nowMs - 60_000L && it.v >= cfg.movingMotionStd }
    }

    /** Very still: no steps for 5 min and (if we have an accelerometer) almost no wrist movement. */
    private fun isStill(nowMs: Long): Boolean {
        val step = lastStepMs
        if (step != null && nowMs - step < cfg.fatigueNoStepsMs) return false
        return motion.none { it.t >= nowMs - cfg.fatigueWindowMs && it.v > cfg.stillMotionStd }
    }

    private fun prune(cutoff: Long) {
        while (hr.isNotEmpty() && hr.first().t < cutoff) hr.removeFirst()
        while (beats.isNotEmpty() && beats.first() < cutoff) beats.removeFirst()
        while (motion.isNotEmpty() && motion.first().t < cutoff) motion.removeFirst()
        while (spo2.isNotEmpty() && spo2.first().t < cutoff) spo2.removeFirst()
    }

    private fun meanSince(buf: ArrayDeque<Sample>, from: Long, minCount: Int): Double? {
        var n = 0
        var sum = 0.0
        for (s in buf) if (s.t >= from) { n++; sum += s.v }
        return if (n >= minCount) sum / n else null
    }

    private fun cooled(last: Long?, now: Long, cooldown: Long) = last == null || now - last >= cooldown

    /** Maps 0..1 (1 = threshold) to 0..70 and lets it run to 100 above threshold. */
    private fun scale(x: Double): Int = (x * 70.0).coerceIn(0.0, 100.0).roundToInt()
}
