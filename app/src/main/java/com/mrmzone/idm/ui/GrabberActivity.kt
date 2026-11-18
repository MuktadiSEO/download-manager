package com.mrmzone.idm.ui

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.Http
import com.mrmzone.idm.util.UrlUtil
import java.net.URL

/** Site Grabber: scans a web page and collects all links to files of chosen types. */
class GrabberActivity : AppCompatActivity() {
    private val found = mutableListOf<String>()
    private val boxes = mutableListOf<CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = Form(this, "Site Grabber")
        f.note("Enter a web page address. MRM Zone IDM will find every file link on it (videos, music, documents, archives, images…).")
        val url = EditText(this).apply { hint = "Page address (https://…)"; setText(intent.getStringExtra("url").orEmpty()) }
        f.add(url)
        val types = EditText(this).apply { hint = "File types"; setText("mp4 mkv webm mp3 m4a pdf zip rar 7z apk jpg png gif doc docx xls pptx") }
        f.add(types)
        val depth = CheckBox(this).apply { text = "Also scan pages linked from this page (same site, slower)" }
        f.add(depth)
        val prog = ProgressBar(this).apply { visibility = View.GONE }
        f.add(prog)
        val status = f.note("")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val scan = MaterialButton(this).apply { text = "Scan page" }
        f.add(scan)
        f.add(list)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        val all = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "All / none" }
        val queue = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Queue" }
        val start = MaterialButton(this).apply { text = "Download" }
        listOf(all, queue, start).forEach { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 8 }) }
        f.add(actions)

        scan.setOnClickListener {
            val page = url.text.toString().trim()
            if (!page.startsWith("http", true)) { toast("Enter a valid web address"); return@setOnClickListener }
            val exts = types.text.toString().lowercase().split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.map { it.trimStart('.') }.toSet()
            prog.visibility = View.VISIBLE; status.text = "Scanning…"; list.removeAllViews(); boxes.clear(); found.clear()
            val deep = depth.isChecked
            Thread {
                val result = runCatching { grab(page, exts, deep) }
                runOnUiThread {
                    prog.visibility = View.GONE
                    result.onSuccess { links ->
                        found += links
                        status.text = "${links.size} file(s) found"
                        links.forEach { l ->
                            val cb = CheckBox(this).apply { text = l.substringAfterLast('/').take(80) + "\n" + l; textSize = 12f; isChecked = true }
                            boxes += cb; list.addView(cb)
                        }
                        actions.visibility = if (links.isEmpty()) View.GONE else View.VISIBLE
                    }.onFailure { status.text = "Error: ${it.message}" }
                }
            }.start()
        }
        all.setOnClickListener { val v = boxes.any { !it.isChecked }; boxes.forEach { it.isChecked = v } }
        fun selected() = found.filterIndexed { i, _ -> boxes[i].isChecked }
        queue.setOnClickListener { selected().takeIf { it.isNotEmpty() }?.let { Dialogs.addMany(this, it, false, url.text.toString()); finish() } }
        start.setOnClickListener { selected().takeIf { it.isNotEmpty() }?.let { Dialogs.addMany(this, it, true, url.text.toString()); finish() } }
        f.show()
    }

    private fun fetch(u: String): Pair<String, String> {
        val c = Http.open(u, DM.prefs.userAgent, null, null)
        try {
            val type = c.contentType.orEmpty()
            if (!type.contains("html") && !type.contains("text")) return c.url.toString() to ""
            val text = c.inputStream.use { it.readBytes().take(4_000_000).toByteArray().toString(Charsets.UTF_8) }
            return c.url.toString() to text
        } finally { c.disconnect() }
    }

    private val linkRe = Regex("""(?:href|src|data-src|content)\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)

    private fun links(base: String, html: String): List<String> {
        val out = linkedSetOf<String>()
        for (m in linkRe.findAll(html)) runCatching { out += URL(URL(base), m.groupValues[1].trim().replace("&amp;", "&")).toString() }
        out += UrlUtil.extractAll(html)
        return out.filter { it.startsWith("http") }
    }

    private fun ext(u: String) = u.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun grab(page: String, exts: Set<String>, deep: Boolean): List<String> {
        val (base, html) = fetch(page)
        val all = links(base, html)
        val result = linkedSetOf<String>()
        all.filter { ext(it) in exts }.forEach { result += it }
        if (deep) {
            val host = URL(base).host
            val pages = all.filter { runCatching { URL(it).host == host }.getOrDefault(false) && ext(it) in setOf("", "html", "htm", "php", "asp", "aspx") }.distinct().take(25)
            for (p in pages) runCatching {
                val (b2, h2) = fetch(p)
                links(b2, h2).filter { ext(it) in exts }.forEach { result += it }
            }
        }
        return result.toList()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Suppress("unused") private val unusedCat = Category.OTHER
}
