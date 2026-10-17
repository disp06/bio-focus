package com.biofocus.wear.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.biofocus.wear.R
import com.biofocus.wear.service.SensorCatalog

/**
 * Diagnostics: every sensor the watch exposes, with the ones Bio-Focus uses on top. Use this first
 * on a new watch to see whether real beat-to-beat HRV and SpO2 are available.
 */
class SensorsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensors)

        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val yes = "✓"
        val no = "✗"
        fun mark(b: Boolean) = if (b) yes else no
        val summary = getString(
            R.string.sensors_summary,
            mark(sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null),
            mark(sm.getDefaultSensor(Sensor.TYPE_HEART_BEAT) != null),
            mark(SensorCatalog.findSpo2(sm) != null),
            mark(sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null),
        )

        val rows = ArrayList<Row>()
        rows.add(Row(summary, "", highlight = true))
        SensorCatalog.list(sm).forEach { info ->
            val sub = buildString {
                append(info.role ?: info.stringType)
                append(" · ").append(info.vendor)
                if (info.wakeUp) append(" · wake-up")
                if (info.fifo > 0) append(" · fifo ").append(info.fifo)
            }
            rows.add(Row(info.name, sub, highlight = info.role != null))
        }

        val list = findViewById<WearableRecyclerView>(R.id.list)
        list.setEdgeItemsCenteringEnabled(true)
        list.layoutManager = WearableLinearLayoutManager(this)
        list.adapter = Adapter(rows)
        list.requestFocus() // rotary input
    }

    private data class Row(val title: String, val sub: String, val highlight: Boolean)

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val sub: TextView = v.findViewById(R.id.tvSub)
    }

    private class Adapter(private val rows: List<Row>) : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sensor, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = rows[position]
            holder.title.text = r.title
            holder.sub.text = r.sub
            holder.sub.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
            val color = if (r.highlight) R.color.accent else R.color.text_primary
            holder.title.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
        }
    }
}
