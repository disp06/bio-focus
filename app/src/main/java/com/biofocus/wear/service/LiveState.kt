package com.biofocus.wear.service

import android.os.Handler
import android.os.Looper
import com.biofocus.wear.utils.Assessment
import java.util.concurrent.CopyOnWriteArraySet

data class LiveSnapshot(
    val tracking: Boolean = false,
    val stepSensor: Boolean = true,
    val hrSensor: Boolean = true,
    val beatSensor: Boolean = false,
    val spo2Sensor: Boolean = false,
    val sessionStartAt: Long = 0L,
    val sessionEndAt: Long = 0L,
    val lastMoveAt: Long = 0L,
    val nextSedentaryCheckAt: Long = 0L,
    val assessment: Assessment? = null,
    val problem: String? = null,
)

/** In-process bus from the service to whatever screen is open. Listeners run on the main thread. */
object LiveState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(LiveSnapshot) -> Unit>()

    @Volatile
    var snapshot = LiveSnapshot()
        private set

    fun update(transform: (LiveSnapshot) -> LiveSnapshot) {
        val s = transform(snapshot)
        snapshot = s
        main.post { listeners.forEach { it(s) } }
    }

    fun observe(listener: (LiveSnapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun remove(listener: (LiveSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}
