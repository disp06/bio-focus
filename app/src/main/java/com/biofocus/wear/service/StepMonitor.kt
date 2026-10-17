package com.biofocus.wear.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.biofocus.wear.utils.BioMath

/**
 * Hardware step counter with FIFO batching: the sensor hub counts steps while the CPU sleeps and
 * delivers them in one burst every [SLOW_LATENCY_US]. During a focus session we switch to a short
 * latency because "did you just walk?" gates the stress detector.
 *
 * @param onCounter called on [handler] for every event: cumulative total, steps since the previous
 *                  event (0 for the first reading), and the event's wall-clock time.
 */
class StepMonitor(
    context: Context,
    private val handler: Handler,
    initialTotal: Long,
    private val onCounter: (total: Long, delta: Long, wallMs: Long) -> Unit,
) : SensorEventListener2 {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var lastTotal = initialTotal
    private var fast = false
    private val pendingFlush = ArrayList<() -> Unit>()
    private val flushTimeout = Runnable { drainFlush() }

    var running = false
        private set

    val available: Boolean get() = sensor != null

    fun start(fast: Boolean): Boolean {
        val s = sensor ?: return false
        this.fast = fast
        val latency = if (fast) FAST_LATENCY_US else SLOW_LATENCY_US
        running = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, latency, handler)
        Log.d(TAG, "step counter registered=$running fast=$fast fifo=${s.fifoMaxEventCount}")
        return running
    }

    fun setFast(fast: Boolean) {
        if (running && fast == this.fast) return
        sm.unregisterListener(this)
        running = false
        start(fast)
    }

    fun stop() {
        sm.unregisterListener(this)
        running = false
        drainFlush()
    }

    /** Ask the sensor hub to deliver every batched step now, then run [done]. */
    fun flush(done: () -> Unit) {
        if (!running) {
            done()
            return
        }
        pendingFlush.add(done)
        handler.removeCallbacks(flushTimeout)
        handler.postDelayed(flushTimeout, FLUSH_TIMEOUT_MS)
        if (!sm.flush(this)) drainFlush()
    }

    override fun onFlushCompleted(sensor: Sensor?) {
        handler.post { drainFlush() }
    }

    private fun drainFlush() {
        handler.removeCallbacks(flushTimeout)
        if (pendingFlush.isEmpty()) return
        val callbacks = pendingFlush.toList()
        pendingFlush.clear()
        callbacks.forEach { it() }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val total = e.values[0].toLong()
        val wall = BioMath.sensorTimeToWallMs(e.timestamp, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        val prev = lastTotal
        lastTotal = total
        val delta = when {
            prev < 0 -> 0L            // first reading ever: nothing to compare with
            total < prev -> total     // counter restarted (reboot)
            else -> total - prev
        }
        onCounter(total, delta, wall)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "BioFocus"
        const val SLOW_LATENCY_US = 5 * 60 * 1_000_000   // 5 min
        const val FAST_LATENCY_US = 10 * 1_000_000       // 10 s
        const val FLUSH_TIMEOUT_MS = 3_000L
    }
}
