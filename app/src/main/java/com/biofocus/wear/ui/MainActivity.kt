package com.biofocus.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.biofocus.wear.R
import com.biofocus.wear.service.Alerts
import com.biofocus.wear.service.BioTrackingService
import com.biofocus.wear.service.LiveSnapshot
import com.biofocus.wear.service.LiveState
import com.biofocus.wear.utils.Prefs
import com.biofocus.wear.utils.SedentaryRule

/** Setup: permissions, tracking on/off, focus-session length, active hours, diagnostics. */
class MainActivity : FragmentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvStatus: TextView
    private lateinit var tvLen: TextView
    private lateinit var tvStart: TextView
    private lateinit var tvEnd: TextView
    private lateinit var btnTracking: Button
    private lateinit var btnFocus: Button

    private val handler = Handler(Looper.getMainLooper())
    private val liveListener: (LiveSnapshot) -> Unit = { render() }

    /** What to do once BODY_SENSORS is granted. */
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val next = afterPermission
            afterPermission = null
            if (granted) next?.invoke() else render(problem = getString(R.string.problem_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Alerts.ensureChannels(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvLen = findViewById(R.id.tvLen)
        tvStart = findViewById(R.id.tvStart)
        tvEnd = findViewById(R.id.tvEnd)
        btnTracking = findViewById(R.id.btnTracking)
        btnFocus = findViewById(R.id.btnFocus)

        btnFocus.setOnClickListener { onFocusClicked() }
        btnTracking.setOnClickListener {
            if (prefs.trackingEnabled) {
                BioTrackingService.send(this, BioTrackingService.ACTION_STOP_TRACKING)
                prefs.trackingEnabled = false
            } else {
                prefs.trackingEnabled = true
                BioTrackingService.send(this, BioTrackingService.ACTION_START_TRACKING)
            }
            render()
        }

        findViewById<Button>(R.id.btnLenMinus).setOnClickListener { changeLen(-5) }
        findViewById<Button>(R.id.btnLenPlus).setOnClickListener { changeLen(+5) }
        findViewById<Button>(R.id.btnStartMinus).setOnClickListener { changeHours(start = -60) }
        findViewById<Button>(R.id.btnStartPlus).setOnClickListener { changeHours(start = +60) }
        findViewById<Button>(R.id.btnEndMinus).setOnClickListener { changeHours(end = -60) }
        findViewById<Button>(R.id.btnEndPlus).setOnClickListener { changeHours(end = +60) }
        findViewById<Button>(R.id.btnSensors).setOnClickListener {
            startActivity(Intent(this, SensorsActivity::class.java))
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testAlerts() }

        // Rotary crown / bezel scrolling on Wear OS 2 needs the scroll view to hold focus.
        findViewById<ScrollView>(R.id.scroll).requestFocus()

        // First launch: ask for body sensors right away so a focus session "just works" later.
        if (!hasBodySensors()) permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        // Keep the service alive across app restarts if tracking was on.
        if (prefs.trackingEnabled) BioTrackingService.send(this, BioTrackingService.ACTION_START_TRACKING)
    }

    override fun onResume() {
        super.onResume()
        LiveState.observe(liveListener)
    }

    override fun onPause() {
        LiveState.remove(liveListener)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun onFocusClicked() {
        if (prefs.sessionActive()) {
            startActivity(Intent(this, FocusAmbientActivity::class.java))
            return
        }
        val start = {
            BioTrackingService.send(this, BioTrackingService.ACTION_START_SESSION) {
                putExtra(BioTrackingService.EXTRA_MINUTES, prefs.sessionMinutes)
            }
            startActivity(Intent(this, FocusAmbientActivity::class.java))
        }
        if (hasBodySensors()) {
            start()
        } else {
            afterPermission = start
            permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
    }

    private fun changeLen(delta: Int) {
        prefs.sessionMinutes = prefs.sessionMinutes + delta
        render()
    }

    private fun changeHours(start: Int = 0, end: Int = 0) {
        if (start != 0) prefs.activeStartMin = prefs.activeStartMin + start
        if (end != 0) prefs.activeEndMin = prefs.activeEndMin + end
        if (prefs.trackingEnabled) BioTrackingService.send(this, BioTrackingService.ACTION_SETTINGS_CHANGED)
        render()
    }

    /** Fires all three interventions a few seconds apart so you can feel the vibrations on the wrist. */
    private fun testAlerts() {
        Alerts.showSedentary(this)
        handler.postDelayed({ Alerts.showStress(this) }, 5_000L)
        handler.postDelayed({ Alerts.showFatigue(this) }, 10_000L)
    }

    private fun hasBodySensors() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    private fun render(problem: String? = null) {
        val s = LiveState.snapshot
        val now = System.currentTimeMillis()
        val tracking = prefs.trackingEnabled

        btnTracking.setText(if (tracking) R.string.btn_tracking_off else R.string.btn_tracking_on)
        btnFocus.setText(if (prefs.sessionActive(now)) R.string.btn_focus_open else R.string.btn_focus_start)
        tvLen.text = getString(R.string.label_minutes, prefs.sessionMinutes)
        tvStart.text = "${getString(R.string.label_from)} ${SedentaryRule.formatMinuteOfDay(prefs.activeStartMin)}"
        tvEnd.text = "${getString(R.string.label_to)} ${SedentaryRule.formatMinuteOfDay(prefs.activeEndMin)}"

        val lines = ArrayList<String>()
        when {
            !tracking -> lines.add(getString(R.string.status_off))
            !s.stepSensor -> lines.add(getString(R.string.problem_no_steps))
            prefs.lastMoveAt > 0 ->
                lines.add(getString(R.string.status_moved, ((now - prefs.lastMoveAt) / 60_000L).toInt()))
            else -> lines.add(getString(R.string.status_tracking))
        }
        s.assessment?.hr?.let { lines.add(getString(R.string.hr_value, it.toInt())) }
        (problem ?: s.problem)?.let { lines.add(it) }
        tvStatus.text = lines.joinToString("\n")
    }
}
