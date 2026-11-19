package com.mrmzone.idm.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Status
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.util.Fmt

/** IDM-like "download progress" window with per-connection details. */
class ProgressActivity : AppCompatActivity() {
    private var id = 0L
    private val values = mutableMapOf<String, TextView>()
    private lateinit var title: TextView
    private lateinit var percent: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var segBar: SegmentBarView
    private lateinit var conns: LinearLayout
    private lateinit var btnPause: MaterialButton
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress)
        id = intent.getLongExtra("id", 0)
        title = findViewById(R.id.title); percent = findViewById(R.id.percent)
        progress = findViewById(R.id.progress); segBar = findViewById(R.id.segments)
        conns = findViewById(R.id.connections); btnPause = findViewById(R.id.btnPause)
        // per-connection list is hidden until "Details" is tapped
        findViewById<TextView>(R.id.detailsToggle).let { t ->
            t.setOnClickListener {
                val show = conns.visibility != android.view.View.VISIBLE
                conns.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                t.text = if (show) "Details ▴" else "Details ▾"
            }
        }
        val table = findViewById<TableLayout>(R.id.table)
        for (k in listOf("URL", "Status", "File size", "Downloaded", "Transfer rate", "Time left", "Resume capability", "Connections", "Save to")) {
            val row = TableRow(this)
            row.addView(TextView(this).apply { text = k; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)); textSize = 13f; setPadding(0, 3, 24, 3) })
            val v = TextView(this).apply { textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 3, 0, 3); maxLines = 2 }
            row.addView(v); values[k] = v
            table.addView(row)
        }
        val d = DM.get(id) ?: run { finish(); return }
        val limitOn = findViewById<CheckBox>(R.id.limitOn)
        val limitVal = findViewById<EditText>(R.id.limitVal)
        limitOn.isChecked = d.speedLimitKb > 0
        limitVal.setText(if (d.speedLimitKb > 0) d.speedLimitKb.toString() else "500")
        fun applyLimit() { DM.setItemLimit(id, if (limitOn.isChecked) limitVal.text.toString().toIntOrNull() ?: 0 else 0) }
        limitOn.setOnCheckedChangeListener { _, _ -> applyLimit() }
        limitVal.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (limitOn.isChecked) applyLimit() }
        })
        val showComplete = findViewById<CheckBox>(R.id.showComplete)
        showComplete.isChecked = DM.prefs.showCompleteDialog
        showComplete.setOnCheckedChangeListener { _, b -> DM.prefs.showCompleteDialog = b }

        findViewById<MaterialButton>(R.id.btnHide).setOnClickListener { finish() }
        btnPause.setOnClickListener {
            val cur = DM.get(id) ?: return@setOnClickListener
            if (cur.status.isActive) DM.pause(id) else DM.start(id)
        }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Cancel download?")
                .setMessage("The download will be stopped and removed from the list.")
                .setPositiveButton("Stop only") { _, _ -> DM.pause(id); finish() }
                .setNegativeButton("Remove") { _, _ -> DM.delete(id, true); finish() }
                .setNeutralButton("Back", null).show()
        }
        render()
    }

    override fun onStart() { super.onStart(); DM.listeners.add(listener); render() }
    override fun onStop() { DM.listeners.remove(listener); super.onStop() }

    private fun render() {
        val d = DM.get(id) ?: run { finish(); return }
        if (d.status == Status.COMPLETED) { finish(); return }
        title.text = d.fileName
        percent.text = if (d.totalSize > 0) "${d.progress}%" else ""
        values["URL"]?.text = d.url
        values["Status"]?.text = when (d.status) {
            Status.DOWNLOADING -> "Receiving data…"
            Status.CONNECTING -> "Connecting…"
            Status.MOVING -> "Saving file…"
            Status.WAITING -> d.error ?: "Waiting for free slot"
            Status.ERROR -> "Error: ${d.error}"
            else -> d.status.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        values["File size"]?.text = Fmt.size(d.totalSize)
        values["Downloaded"]?.text = Fmt.size(d.downloaded) + if (d.totalSize > 0) " (${d.progress}%)" else ""
        values["Transfer rate"]?.text = if (d.status == Status.DOWNLOADING) Fmt.speed(d.speed) else "-"
        values["Time left"]?.text = if (d.status == Status.DOWNLOADING) Fmt.time(d.timeLeftSec) else "-"
        values["Resume capability"]?.text = if (d.resumable) "Yes" else if (d.totalSize < 0 && d.downloaded == 0L) "Unknown" else "No"
        values["Connections"]?.text = "${d.activeConnections} active / ${d.connections} max"
        values["Save to"]?.text = "Download/MRM Zone/${d.category.folder}"
        progress.isIndeterminate = d.status == Status.CONNECTING || (d.status == Status.DOWNLOADING && d.totalSize <= 0)
        progress.progress = d.progress
        btnPause.text = if (d.status.isActive) "Pause" else "Resume"

        val segs = d.liveSegments.ifEmpty { com.mrmzone.idm.data.Segment.parseList(d.segments) }
        segBar.set(segs, d.totalSize, false)
        if (conns.visibility != android.view.View.VISIBLE) return
        conns.removeAllViews()
        segs.forEachIndexed { i, s ->
            val st = when { s.finished -> "Done"; d.status == Status.DOWNLOADING && s.done >= 0 -> "Receiving data…"; else -> "Idle" }
            conns.addView(TextView(this).apply {
                textSize = 12f
                text = String.format("#%-3d %10s   %s", i + 1, Fmt.size(s.done), st)
                typeface = Typeface.MONOSPACE
            })
        }
    }
}
