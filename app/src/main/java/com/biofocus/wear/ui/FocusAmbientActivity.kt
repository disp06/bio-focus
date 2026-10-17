package com.biofocus.wear.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.wear.ambient.AmbientModeSupport
import com.biofocus.wear.R
import com.biofocus.wear.service.BioTrackingService
import com.biofocus.wear.service.LiveSnapshot
import com.biofocus.wear.service.LiveState
import com.biofocus.wear.utils.HrvMode
import com.biofocus.wear.utils.Phase
import kotlin.random.Random

/**
 * The live focus-session screen. Stays on the wrist in ambient (low-power) mode: when the display
 * dims, colours drop to white-on-black, the button and progress bar disappear, and the timer
 * updates once a minute (onUpdateAmbient) instead of every second.
 */
class FocusAmbientActivity : FragmentActivity(), AmbientModeSupport.AmbientCallbackProvider {

    private lateinit var ambientController: AmbientModeSupport.AmbientController
    private lateinit var content: View
    private lateinit var tvTimer: TextView
    private lateinit var tvHr: TextView
    private lateinit var tvStress: TextView
    private lateinit var tvMode: TextView
    private lateinit var stressBar: ProgressBar
    private lateinit var btnStop: Button

    private val createdAt = System.currentTimeMillis()
    private var ambient = false
    private var lowBit = false
    private var burnIn = false

    private val handler = Handler(Looper.getMainLooper())
    private val secondTicker = object : Runnable {
        override fun run() {
            render()
            if (!ambient) handler.postDelayed(this, 1_000L)
        }
    }
    private val liveListener: (LiveSnapshot) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_focus)
        content = findViewById(R.id.content)
        tvTimer = findViewById(R.id.tvTimer)
        tvHr = findViewById(R.id.tvHr)
        tvStress = findViewById(R.id.tvStress)
        tvMode = findViewById(R.id.tvMode)
        stressBar = findViewById(R.id.stressBar)
        btnStop = findViewById(R.id.btnStop)

        ambientController = AmbientModeSupport.attach(this)

        btnStop.setOnClickListener {
            if (LiveState.snapshot.sessionEndAt > System.currentTimeMillis()) {
                BioTrackingService.send(this, BioTrackingService.ACTION_STOP_SESSION)
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        LiveState.observe(liveListener)
        handler.removeCallbacks(secondTicker)
        if (!ambient) handler.post(secondTicker)
    }

    override fun onPause() {
        LiveState.remove(liveListener)
        handler.removeCallbacks(secondTicker)
        super.onPause()
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback = object : AmbientModeSupport.AmbientCallback() {
        override fun onEnterAmbient(ambientDetails: Bundle?) {
            ambient = true
            lowBit = ambientDetails?.getBoolean(EXTRA_LOWBIT_AMBIENT, false) ?: false
            burnIn = ambientDetails?.getBoolean(EXTRA_BURN_IN_PROTECTION, false) ?: false
            handler.removeCallbacks(secondTicker)
            applyStyle()
            render()
        }

        override fun onUpdateAmbient() {
            // Called about once a minute while ambient. Nudge pixels to avoid OLED burn-in.
            if (burnIn) {
                content.translationX = Random.nextInt(-6, 7).toFloat()
                content.translationY = Random.nextInt(-6, 7).toFloat()
            }
            render()
        }

        override fun onExitAmbient() {
            ambient = false
            content.translationX = 0f
            content.translationY = 0f
            applyStyle()
            handler.removeCallbacks(secondTicker)
            handler.post(secondTicker)
        }
    }

    private fun applyStyle() {
        val primary = ContextCompat.getColor(this, if (ambient) R.color.ambient_text else R.color.text_primary)
        val accent = ContextCompat.getColor(this, if (ambient) R.color.ambient_text else R.color.accent)
        val secondary = ContextCompat.getColor(this, if (ambient) R.color.ambient_dim else R.color.text_secondary)
        tvTimer.setTextColor(primary)
        tvHr.setTextColor(accent)
        tvStress.setTextColor(primary)
        tvMode.setTextColor(secondary)
        val antiAlias = !(ambient && lowBit)
        listOf(tvTimer, tvHr, tvStress, tvMode).forEach { it.paint.isAntiAlias = antiAlias }
        btnStop.visibility = if (ambient) View.GONE else View.VISIBLE
        stressBar.visibility = if (ambient) View.GONE else View.VISIBLE
    }

    private fun render() {
        val s = LiveState.snapshot
        val now = System.currentTimeMillis()
        val remainingMs = s.sessionEndAt - now

        if (s.sessionEndAt == 0L && now - createdAt < STARTUP_GRACE_MS) {
            tvTimer.text = "…" // service is still starting the session
            return
        }
        if (s.sessionEndAt == 0L || remainingMs <= 0) {
            tvTimer.text = getString(R.string.focus_done)
            tvTimer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            tvHr.text = ""
            tvStress.text = ""
            tvMode.text = s.problem ?: ""
            stressBar.visibility = View.GONE
            btnStop.setText(R.string.btn_close)
            return
        }

        tvTimer.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_timer))
        val totalSec = remainingMs / 1000
        tvTimer.text = if (ambient) {
            "${(totalSec + 59) / 60} min"
        } else {
            "%d:%02d".format(totalSec / 60, totalSec % 60)
        }

        val a = s.assessment
        tvHr.text = a?.hr?.let { getString(R.string.hr_value, it.toInt()) } ?: getString(R.string.hr_none)

        when {
            a == null || a.phase == Phase.CALIBRATING -> {
                tvStress.text = getString(R.string.focus_calibrating)
                stressBar.progress = 0
            }
            a.moving -> {
                tvStress.text = getString(R.string.focus_moving)
                stressBar.progress = 0
            }
            else -> {
                val idx = a.stressIndex ?: 0
                tvStress.text = getString(R.string.focus_stress, idx)
                stressBar.progress = idx
                val color = when {
                    idx >= 70 -> R.color.alert
                    idx >= 45 -> R.color.warn
                    else -> R.color.accent
                }
                stressBar.progressTintList = ContextCompat.getColorStateList(this, color)
            }
        }
        tvMode.text = when (a?.mode) {
            HrvMode.BEAT_TO_BEAT -> getString(R.string.focus_mode_beat)
            HrvMode.HR_PROXY -> getString(R.string.focus_mode_proxy)
            null -> ""
        }
        btnStop.setText(R.string.btn_stop)
        if (!ambient) stressBar.visibility = View.VISIBLE
    }

    private companion object {
        // Keys of the ambientDetails bundle (same values as AmbientModeSupport's constants).
        const val EXTRA_LOWBIT_AMBIENT = "com.google.android.wearable.compat.extra.LOWBIT_AMBIENT"
        const val EXTRA_BURN_IN_PROTECTION = "com.google.android.wearable.compat.extra.BURN_IN_PROTECTION"
        const val STARTUP_GRACE_MS = 3_000L
    }
}
