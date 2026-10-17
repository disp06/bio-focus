package com.biofocus.wear.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.biofocus.wear.utils.Assessment
import com.biofocus.wear.utils.MotionAccumulator
import com.biofocus.wear.utils.SessionAnalyzer

/**
 * Focus-session sensors: heart rate (always), beat-to-beat (if the watch exposes it), accelerometer
 * (motion gating) and a vendor SpO2 sensor (if found). Runs only while a session is active.
 */
class HeartMonitor(
    context: Context,
    private val handler: Handler,
    sessionStartMs: Long,
    private val onAssessment: (Assessment) -> Unit,
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val hrSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    val beatSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_HEART_BEAT)
    private val accelSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val spo2Sensor: Sensor? = SensorCatalog.findSpo2(sm)

    private val analyzer = SessionAnalyzer(sessionStartMs)
    private val motion = MotionAccumulator()

    /** Fixed elapsedRealtime→wall offset for the session, so beat intervals have no conversion jitter. */
    private val clockOffsetMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private val tick = object : Runnable {
        override fun run() {
            onAssessment(analyzer.evaluate(System.currentTimeMillis()))
            handler.postDelayed(this, EVAL_INTERVAL_MS)
        }
    }

    fun start(): Boolean {
        val hr = hrSensor ?: return false
        val ok = sm.registerListener(this, hr, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US, handler)
        beatSensor?.let { safeRegister(it, SensorManager.SENSOR_DELAY_FASTEST, BATCH_US) }
        accelSensor?.let { safeRegister(it, ACCEL_PERIOD_US, ACCEL_BATCH_US) }
        spo2Sensor?.let { safeRegister(it, SensorManager.SENSOR_DELAY_NORMAL, 0) }
        Log.d(TAG, "HR registered=$ok beat=${beatSensor != null} spo2=${spo2Sensor?.name}")
        handler.postDelayed(tick, EVAL_INTERVAL_MS)
        return ok
    }

    fun stop() {
        sm.unregisterListener(this)
        handler.removeCallbacks(tick)
    }

    fun onSteps(wallMs: Long) = analyzer.addStep(wallMs)

    private fun safeRegister(s: Sensor, periodUs: Int, latencyUs: Int) {
        try {
            sm.registerListener(this, s, periodUs, latencyUs, handler)
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to ${s.name}: ${e.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val now = System.currentTimeMillis()
        var t = e.timestamp / 1_000_000L + clockOffsetMs
        if (t > now + 5_000L || t < now - 10 * 60_000L) t = now // HAL with a non-standard clock
        when (e.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                if (e.accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT && e.values[0] > 0f) {
                    analyzer.addHr(t, e.values[0].toDouble())
                }
            }
            Sensor.TYPE_HEART_BEAT -> if (e.values[0] >= MIN_BEAT_CONFIDENCE) analyzer.addBeat(t)
            Sensor.TYPE_ACCELEROMETER ->
                motion.add(t, e.values[0], e.values[1], e.values[2])?.let { (tm, std) -> analyzer.addMotion(tm, std) }
            else -> if (e.sensor == spo2Sensor && e.values.isNotEmpty()) analyzer.addSpo2(t, e.values[0].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "BioFocus"
        const val EVAL_INTERVAL_MS = 10_000L
        const val BATCH_US = 5 * 1_000_000
        const val ACCEL_PERIOD_US = 40_000          // 25 Hz is plenty for "is the wrist moving"
        const val ACCEL_BATCH_US = 10 * 1_000_000
        const val MIN_BEAT_CONFIDENCE = 0.5f
    }
}
