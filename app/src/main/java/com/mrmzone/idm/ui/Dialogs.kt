package com.mrmzone.idm.ui

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.data.Status
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.FileStore
import com.mrmzone.idm.engine.Http
import com.mrmzone.idm.util.Fmt

object Dialogs {
    private fun pad(a: Activity) = (18 * a.resources.displayMetrics.density).toInt()

    private fun field(a: Activity, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(a).apply { this.hint = hint; setText(value); inputType = type }

    /** Adds many URLs at once (used by batch, grabber and multi-link share). */
    fun addMany(a: Activity, urls: List<String>, start: Boolean, referer: String? = null, cookies: ((String) -> String?)? = null, ua: String? = null) {
        val names = mutableSetOf<String>()
        urls.forEach { u ->
            var n = Http.fileNameFrom(null, u, null)
            var i = 1
            val base = n.substringBeforeLast('.'); val ext = n.substringAfterLast('.', "")
            while (!names.add(n)) { n = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"; i++ }
            DM.add(DownloadItem(url = u, fileName = n, category = Category.fromFileName(n), connections = DM.prefs.maxConnections,
                userAgent = ua ?: DM.prefs.userAgent, referer = referer, cookies = cookies?.invoke(u), inQueue = true), start)
        }
        Toast.makeText(a, "${urls.size} download(s) " + if (start) "started" else "added to queue", Toast.LENGTH_SHORT).show()
    }

    /** IDM "Add batch download": http://site.com/file*.zip with numbers or letters. */
    fun batch(a: Activity) {
        val p = pad(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0) }
        box.addView(TextView(a).apply { text = "Use * as wildcard, e.g. https://site.com/photo*.jpg"; textSize = 13f })
        val url = field(a, "Address with *", "", InputType.TYPE_TEXT_VARIATION_URI)
        val from = field(a, "From (number or letter)", "1")
        val to = field(a, "To", "10")
        val width = field(a, "Wildcard size (zero padding, e.g. 2 → 01)", "1", InputType.TYPE_CLASS_NUMBER)
        listOf(url, from, to, width).forEach { box.addView(it) }
        MaterialAlertDialogBuilder(a).setTitle("Add batch download").setView(box)
            .setPositiveButton("Add to queue") { _, _ ->
                val list = expand(url.text.toString().trim(), from.text.toString().trim(), to.text.toString().trim(), width.text.toString().toIntOrNull() ?: 1)
                if (list.isEmpty()) Toast.makeText(a, "Invalid pattern. Include * and a valid range.", Toast.LENGTH_LONG).show()
                else confirmList(a, list)
            }
            .setNegativeButton("Cancel", null).show()
    }

    fun expand(pattern: String, from: String, to: String, width: Int): List<String> {
        if (!pattern.contains('*') || !pattern.startsWith("http", true)) return emptyList()
        val f = from.toIntOrNull(); val t = to.toIntOrNull()
        val values: List<String> = if (f != null && t != null && t >= f && t - f <= 5000) (f..t).map { it.toString().padStart(width, '0') }
        else if (from.length == 1 && to.length == 1 && from[0].isLetter() && to[0] >= from[0]) (from[0]..to[0]).map { it.toString() }
        else return emptyList()
        return values.map { pattern.replace("*", it) }
    }

    private fun confirmList(a: Activity, list: List<String>) {
        MaterialAlertDialogBuilder(a).setTitle("${list.size} files")
            .setMessage(list.take(5).joinToString("\n") + if (list.size > 5) "\n…" else "")
            .setPositiveButton("Start now") { _, _ -> addMany(a, list, true) }
            .setNeutralButton("Queue only") { _, _ -> addMany(a, list, false) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Properties, with the ability to refresh an expired download address (like IDM). */
    fun properties(a: Activity, d: DownloadItem) {
        val p = pad(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0) }
        val done = d.status == Status.COMPLETED
        val name = field(a, "File name", d.fileName).apply { isEnabled = !done }
        val url = field(a, "Address (URL) — paste a fresh link if it expired", d.url, InputType.TYPE_TEXT_VARIATION_URI)
        val desc = field(a, "Description", d.description.orEmpty())
        box.addView(name); box.addView(url); box.addView(desc)
        box.addView(TextView(a).apply {
            textSize = 13f; setPadding(0, p / 2, 0, 0)
            text = buildString {
                appendLine("Category: ${d.category.label}")
                appendLine("Size: ${Fmt.size(d.totalSize)}")
                appendLine("Downloaded: ${Fmt.size(d.downloaded)}")
                appendLine("Status: ${d.status.name.lowercase()}" + (d.error?.let { " ($it)" } ?: ""))
                appendLine("Resume support: ${if (d.resumable) "Yes" else "No"}")
                appendLine("Connections: ${d.connections}")
                appendLine("Saved to: Download/${FileStore.ROOT}/${d.category.folder}")
                appendLine("Added: ${Fmt.date(d.createdAt)}")
                appendLine("Last try: ${Fmt.date(d.lastTry)}")
                if (done) appendLine("Completed: ${Fmt.date(d.finishedAt)}")
                d.referer?.let { appendLine("Referer: $it") }
            }
        })
        MaterialAlertDialogBuilder(a).setTitle("Properties").setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (DM.task(d.id) != null) DM.pause(d.id)
                val nu = url.text.toString().trim()
                if (nu.startsWith("http", true)) d.url = nu
                if (!done) { d.fileName = Http.sanitize(name.text.toString()); d.category = Category.fromFileName(d.fileName) }
                d.description = desc.text.toString()
                DM.update(d)
            }
            .setNegativeButton("Close", null).show()
    }

    fun about(a: Activity) {
        MaterialAlertDialogBuilder(a)
            .setIcon(com.mrmzone.idm.R.drawable.logo_mrm)
            .setTitle("MRM Zone IDM")
            .setMessage("MRM Zone Internet Download Manager\nVersion 1.0.0\n\n" +
                "• Up to 32 connections per file with dynamic segmentation\n" +
                "• Pause / resume, auto-retry, resume after restart\n" +
                "• Queue & scheduler, speed limiter\n" +
                "• Built-in browser with video/audio grabber\n" +
                "• Site grabber & batch downloads\n" +
                "• Clipboard monitoring & share-to-download\n\n" +
                "Files are saved in Download/MRM Zone")
            .setPositiveButton("OK", null)
            .setNeutralButton("Share") { _, _ ->
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Try MRM Zone Internet Download Manager for Android!"), "Share"))
            }.show()
    }
}
