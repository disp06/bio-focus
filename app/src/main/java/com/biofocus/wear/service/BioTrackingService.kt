package com.biofocus.wear.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.biofocus.wear.R
import com.biofocus.wear.utils.Assessment
import com.biofocus.wear.utils.Phase
import com.biofocus.wear.utils.Prefs
import com.biofocus.wear.utils.SedentaryRule
import kotlin.math.max

/**
 * Sticky foreground service = the app's brain.
 *
 *  - Always (while tracking is on): batched hardware step counter + one exact alarm for the
 *    sedentary rule. The CPU sleeps between batches; there is no polling loop.
 *  - During a focus session: heart rate / beat-to-beat / accelerometer / SpO2 via [HeartMonitor],
 *    evaluated every 10 s. A partial wake lock (bounded by the session length) keeps evaluation
 *    real-time even with the screen off.
 */
class BioTrackingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var steps: StepMonitor
    private var heart: HeartMonitor? = null
    private var sessionLock: PowerManager.WakeLock? = null

    // Step events arrive in bursts; persist + reschedule once per burst.
    private var pendingTotal = -1L
    private var pendingMoveAt = 0L
    private val commitStepsRunnable = Runnable { commitSteps() }

    private val sessionEnd = Runnable { finishSession(completed = true) }
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshForeground()
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Alerts.ensureChannels(this)
        pendingTotal = prefs.lastStepTotal
        pendingMoveAt = prefs.lastMoveAt
        steps = StepMonitor(this, handler, prefs.lastStepTotal, ::onStepCounter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called within 5 s of startForegroundService(), on every start.
        startForeground(Alerts.ID_FOREGROUND, Alerts.foreground(this, statusText(), prefs.sessionActive()))

        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action")
        when (action) {
            ACTION_STOP_TRACKING -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_START_TRACKING -> prefs.trackingEnabled = true
            ACTION_START_SESSION -> {
                prefs.trackingEnabled = true
                startSession(intent?.getIntExtra(EXTRA_MINUTES, prefs.sessionMinutes) ?: prefs.sessionMinutes)
            }
            ACTION_STOP_SESSION -> finishSession(completed = false)
            // ACTION_SETTINGS_CHANGED and null (sticky restart) are handled by ensureRunning().
        }

        // Stale alarm / restart after the user switched tracking off.
        if (!prefs.trackingEnabled) {
            shutdown()
            return START_NOT_STICKY
        }
        ensureRunning()
        if (action == ACTION_SEDENTARY_CHECK) runSedentaryCheck()
        return START_STICKY
    }

    override fun onDestroy() {
        steps.stop()
        heart?.stop()
        heart = null
        releaseLock()
        handler.removeCallbacksAndMessages(null)
        LiveState.update { it.copy(tracking = false, sessionEndAt = 0L, assessment = null) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun ensureRunning() {
        if (!steps.running && steps.available) steps.start(fast = heart != null)
        if (prefs.sessionActive() && heart == null) startHeart()
        scheduleSedentaryCheck()
        handler.removeCallbacks(statusTicker)
        handler.post(statusTicker)
        publish()
    }

    private fun shutdown() {
        prefs.trackingEnabled = false
        finishSession(completed = false)
        steps.stop()
        NotificationReceiver.cancelSedentaryAlarm(this)
        handler.removeCallbacksAndMessages(null)
        LiveState.update { it.copy(tracking = false, sessionEndAt = 0L, assessment = null) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ focus session

    private fun startSession(minutes: Int) {
        heart?.stop()
        heart = null
        val now = System.currentTimeMillis()
        prefs.sessionMinutes = minutes
        prefs.sessionStartAt = now
        prefs.sessionEndAt = now + minutes * 60_000L
        LiveState.update { it.copy(assessment = null, problem = null) }
        // ensureRunning() starts the heart monitor.
    }

    private fun startHeart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            LiveState.update { it.copy(problem = getString(R.string.problem_permission)) }
            prefs.sessionEndAt = 0L
            return
        }
        val monitor = HeartMonitor(this, handler, prefs.sessionStartAt) { a -> onAssessment(a) }
        if (!monitor.start()) {
            LiveState.update { it.copy(problem = getString(R.string.problem_no_hr), hrSensor = false) }
            prefs.sessionEndAt = 0L
            return
        }
        heart = monitor
        steps.setFast(true)
        val remaining = max(prefs.sessionEndAt - System.currentTimeMillis(), 0L)
        acquireLock(remaining + 60_000L)
        handler.removeCallbacks(sessionEnd)
        handler.postDelayed(sessionEnd, remaining)
        LiveState.update {
            it.copy(
                problem = null, hrSensor = true,
                beatSensor = monitor.beatSensor != null, spo2Sensor = monitor.spo2Sensor != null,
            )
        }
        refreshForeground()
    }

    private fun finishSession(completed: Boolean) {
        handler.removeCallbacks(sessionEnd)
        val wasRunning = heart != null
        heart?.stop()
        heart = null
        if (completed && wasRunning) Alerts.showSessionDone(this, prefs.sessionMinutes)
        prefs.sessionEndAt = 0L
        releaseLock()
        if (steps.running) steps.setFast(false)
        LiveState.update { it.copy(sessionEndAt = 0L, assessment = null) }
        refreshForeground()
    }

    private fun onAssessment(a: Assessment) {
        LiveState.update { it.copy(assessment = a) }
        if (a.stressAlert) {
            Log.i(TAG, "STRESS alert hr=${a.hr} rmssd=${a.rmssd} base=${a.baselineHr} mode=${a.mode}")
            Alerts.showStress(this)
        }
        if (a.fatigueAlert) {
            Log.i(TAG, "FATIGUE alert hr=${a.hr} base=${a.baselineHr} spo2=${a.spo2} reason=${a.fatigueReason}")
            Alerts.showFatigue(this)
        }
    }

    // ------------------------------------------------------------------ steps & sedentary

    private fun onStepCounter(total: Long, delta: Long, wallMs: Long) {
        pendingTotal = total
        if (delta > 0) {
            pendingMoveAt = max(pendingMoveAt, wallMs)
            heart?.onSteps(wallMs)
        }
        handler.removeCallbacks(commitStepsRunnable)
        handler.postDelayed(commitStepsRunnable, 1_500L)
    }

    private fun commitSteps() {
        handler.removeCallbacks(commitStepsRunnable)
        if (pendingTotal >= 0) prefs.lastStepTotal = pendingTotal
        if (pendingMoveAt > prefs.lastMoveAt) {
            prefs.lastMoveAt = pendingMoveAt
            Alerts.cancel(this, Alerts.ID_SEDENTARY) // you got up: the nudge is obsolete
            scheduleSedentaryCheck()
            publish()
        }
    }

    private fun runSedentaryCheck() {
        // Hold the CPU just long enough for the sensor hub to hand over its batched steps.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BioFocus:sedentary").acquire(10_000L)
        steps.flush { evaluateSedentary() }
    }

    private fun evaluateSedentary() {
        commitSteps()
        val now = System.currentTimeMillis()
        val input = prefs.sedentaryInput()
        if (SedentaryRule.shouldAlert(now, input)) {
            Log.i(TAG, "SEDENTARY alert, last move ${(now - prefs.lastMoveAt) / 60_000} min ago")
            Alerts.showSedentary(this)
            prefs.lastSedentaryAlertAt = now
        }
        scheduleSedentaryCheck()
        publish()
    }

    private fun scheduleSedentaryCheck() {
        if (!steps.available) return
        val now = System.currentTimeMillis()
        val due = max(SedentaryRule.dueAt(now, prefs.sedentaryInput()), now + 5_000L)
        NotificationReceiver.scheduleSedentaryAlarm(this, due)
        LiveState.update { it.copy(nextSedentaryCheckAt = due) }
    }

    // ------------------------------------------------------------------ helpers

    private fun acquireLock(timeoutMs: Long) {
        releaseLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        sessionLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BioFocus:session").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    private fun releaseLock() {
        sessionLock?.let { if (it.isHeld) it.release() }
        sessionLock = null
    }

    private fun publish() {
        LiveState.update {
            it.copy(
                tracking = prefs.trackingEnabled,
                stepSensor = steps.available,
                sessionStartAt = if (prefs.sessionActive()) prefs.sessionStartAt else 0L,
                sessionEndAt = if (prefs.sessionActive()) prefs.sessionEndAt else 0L,
                lastMoveAt = prefs.lastMoveAt,
            )
        }
    }

    private fun refreshForeground() {
        if (!prefs.trackingEnabled) return
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        nm.notify(Alerts.ID_FOREGROUND, Alerts.foreground(this, statusText(), prefs.sessionActive()))
    }

    private fun statusText(): String {
        val now = System.currentTimeMillis()
        if (prefs.sessionActive(now)) {
            val left = ((prefs.sessionEndAt - now) / 60_000L).coerceAtLeast(0)
            val a = LiveState.snapshot.assessment
            val hr = a?.hr?.let { " · ${it.toInt()} bpm" } ?: ""
            val phase = if (a?.phase == Phase.CALIBRATING) " · calibrating" else ""
            return getString(R.string.status_session, left) + hr + phase
        }
        if (!steps.available) return getString(R.string.problem_no_steps)
        val last = prefs.lastMoveAt
        return if (last == 0L) {
            getString(R.string.status_tracking)
        } else {
            getString(R.string.status_moved, ((now - last) / 60_000L).coerceAtLeast(0))
        }
    }

    companion object {
        private const val TAG = "BioFocus"

        const val ACTION_START_TRACKING = "com.biofocus.wear.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.biofocus.wear.STOP_TRACKING"
        const val ACTION_START_SESSION = "com.biofocus.wear.START_SESSION"
        const val ACTION_STOP_SESSION = "com.biofocus.wear.STOP_SESSION"
        const val ACTION_SEDENTARY_CHECK = "com.biofocus.wear.SEDENTARY_CHECK"
        const val ACTION_SETTINGS_CHANGED = "com.biofocus.wear.SETTINGS_CHANGED"
        const val EXTRA_MINUTES = "minutes"

        fun send(ctx: Context, action: String, configure: Intent.() -> Unit = {}) {
            val i = Intent(ctx, BioTrackingService::class.java).setAction(action).apply(configure)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
