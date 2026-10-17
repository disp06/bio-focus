package com.biofocus.wear.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.biofocus.wear.R
import com.biofocus.wear.service.Alerts

/**
 * "Breathe with me for 1 minute": 6 cycles of 4 s in / 6 s out (≈6 breaths/min, the slow-breathing
 * rate that raises HRV). A circle grows and shrinks; a light tick marks each phase change so you
 * can do it with eyes closed.
 */
class BreathingActivity : FragmentActivity() {

    private lateinit var circle: View
    private lateinit var tvPhase: TextView
    private lateinit var tvLeft: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var finished = false

    private val countdown = object : Runnable {
        override fun run() {
            val left = ((TOTAL_MS - (SystemClock.elapsedRealtime() - startedAt)) / 1000).coerceAtLeast(0)
            tvLeft.text = getString(R.string.seconds_left, left.toInt())
            if (left > 0) handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_breathing)
        circle = findViewById(R.id.circle)
        tvPhase = findViewById(R.id.tvPhase)
        tvLeft = findViewById(R.id.tvLeft)

        Alerts.cancel(this, Alerts.ID_STRESS)
        startedAt = SystemClock.elapsedRealtime()
        handler.post(countdown)
        inhale()
    }

    override fun onDestroy() {
        circle.animate().cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAt

    private fun inhale() {
        if (isFinishing || finished) return
        if (elapsed() >= TOTAL_MS - 500) return done()
        tvPhase.setText(R.string.breathe_in)
        Alerts.tick(this)
        circle.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(INHALE_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { exhale() }
            .start()
    }

    private fun exhale() {
        if (isFinishing || finished) return
        tvPhase.setText(R.string.breathe_out)
        Alerts.tick(this)
        circle.animate()
            .scaleX(MIN_SCALE).scaleY(MIN_SCALE)
            .setDuration(EXHALE_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { inhale() }
            .start()
    }

    private fun done() {
        finished = true
        tvPhase.setText(R.string.breathe_done)
        tvLeft.text = ""
        handler.postDelayed({ finish() }, 2_500L)
    }

    private companion object {
        const val INHALE_MS = 4_000L
        const val EXHALE_MS = 6_000L
        const val TOTAL_MS = 60_000L
        const val MIN_SCALE = 0.55f
    }
}
