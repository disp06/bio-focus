package com.biofocus.wear.service

import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * What this particular watch actually exposes. Wear OS 2 has no public SpO2 or HRV API, so we look
 * for the optional pieces at runtime and degrade gracefully.
 */
object SensorCatalog {

    private val SPO2_HINTS = listOf("spo2", "oxygen", "oximet", "blood_o2", "bloodo2")

    /** A vendor sensor that looks like SpO2 (e.g. Mobvoi's). Experimental: semantics are undocumented. */
    fun findSpo2(sm: SensorManager): Sensor? =
        sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { s ->
            // Vendor-private sensor types start at TYPE_DEVICE_PRIVATE_BASE (65536).
            if (s.type < Sensor.TYPE_DEVICE_PRIVATE_BASE) return@firstOrNull false
            val key = "${s.name} ${s.stringType}".lowercase()
            SPO2_HINTS.any { key.contains(it) }
        }

    data class Info(
        val name: String,
        val vendor: String,
        val stringType: String,
        val wakeUp: Boolean,
        val fifo: Int,
        val role: String?,
    )

    fun list(sm: SensorManager): List<Info> {
        val spo2 = findSpo2(sm)
        return sm.getSensorList(Sensor.TYPE_ALL)
            .map { s ->
                val role = when {
                    s.type == Sensor.TYPE_HEART_RATE -> "Heart rate → stress & fatigue"
                    s.type == Sensor.TYPE_HEART_BEAT -> "Beat-to-beat → real HRV (RMSSD)"
                    s.type == Sensor.TYPE_STEP_COUNTER -> "Step counter → sedentary alert"
                    s.type == Sensor.TYPE_ACCELEROMETER -> "Motion gating"
                    s == spo2 -> "SpO2 candidate (experimental)"
                    else -> null
                }
                Info(s.name, s.vendor, s.stringType ?: "type ${s.type}", s.isWakeUpSensor, s.fifoMaxEventCount, role)
            }
            .sortedWith(compareBy({ it.role == null }, { it.name }))
    }
}
