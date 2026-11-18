package com.mrmzone.idm.ui

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mrmzone.idm.data.Status
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.service.Scheduler
import com.mrmzone.idm.util.Fmt

/** IDM Scheduler: start / stop the main queue at given times on chosen days. */
class SchedulerActivity : AppCompatActivity() {
    private lateinit var next: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = DM.prefs
        val f = Form(this, "Scheduler")
        f.note("The scheduler starts the Main Queue automatically — perfect for night packages / off-peak internet.")
        f.header("MAIN QUEUE")
        f.switch("Enable scheduler", p.schedEnabled) { p.schedEnabled = it; apply() }

        fun timeBtn(label: String, get: () -> Int, set: (Int) -> Unit): MaterialButton {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            fun upd() { b.text = "$label: " + String.format("%02d:%02d", get() / 60, get() % 60) }
            upd()
            b.setOnClickListener {
                TimePickerDialog(this, { _, h, m -> set(h * 60 + m); upd(); apply() }, get() / 60, get() % 60, false).show()
            }
            f.add(b); return b
        }
        timeBtn("Start download at", { p.schedStartMin }) { p.schedStartMin = it }
        f.switch("Stop download at a set time", p.schedStopEnabled) { p.schedStopEnabled = it; apply() }
        timeBtn("Stop download at", { p.schedStopMin }) { p.schedStopMin = it }

        f.header("DAYS")
        val days = ChipGroup(this)
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { i, d ->
            days.addView(Chip(this).apply {
                text = d; isCheckable = true; isChecked = p.schedDays and (1 shl i) != 0
                setOnCheckedChangeListener { _, on -> p.schedDays = if (on) p.schedDays or (1 shl i) else p.schedDays and (1 shl i).inv(); apply() }
            })
        }
        f.add(days)

        f.header("STATUS")
        next = f.note("")
        val q = DM.snapshot().filter { it.inQueue && it.status != Status.COMPLETED }
        f.note("Files in queue: ${q.size}")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(MaterialButton(this).apply { text = "Start now"; setOnClickListener { DM.startQueue(); finish() } })
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Stop queue"; setOnClickListener { DM.stopQueue() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 16 })
        f.add(row)

        if (Build.VERSION.SDK_INT >= 31 && !(getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()) {
            f.add(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Allow exact alarms (more precise timing)"
                setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } }
            })
        }
        f.show()
        apply()
    }

    private fun apply() {
        Scheduler.apply(this)
        val t = Scheduler.nextStartTime(this)
        if (::next.isInitialized) next.text = if (t == null) "Scheduler is off" else "Next start: ${Fmt.date(t)}"
    }
}
