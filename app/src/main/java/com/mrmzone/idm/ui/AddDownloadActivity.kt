package com.mrmzone.idm.ui

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.FileStore
import com.mrmzone.idm.engine.Http
import com.mrmzone.idm.engine.Ytdlp
import com.mrmzone.idm.util.Fmt
import com.mrmzone.idm.util.UrlUtil

class AddDownloadActivity : AppCompatActivity() {
    private lateinit var url: TextInputEditText
    private lateinit var name: TextInputEditText
    private lateinit var cat: AutoCompleteTextView
    private lateinit var info: TextView
    private lateinit var saveTo: TextView
    private lateinit var probing: ProgressBar
    private var size = -1L
    private var resumable = false
    private var nameEdited = false
    private var selCat = Category.OTHER
    private var referer: String? = null
    private var ua: String? = null
    private var cookies: String? = null
    private val h = Handler(Looper.getMainLooper())
    private var probeSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shared from Facebook / YouTube / Instagram…: show the small quality popup right over that app
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            val links = UrlUtil.extractAll(intent.getStringExtra(Intent.EXTRA_TEXT))
            val v = links.firstOrNull { Ytdlp.isVideoSite(it) }
            if (v != null && links.size == 1) {
                startActivity(Intent(this, VideoQualityActivity::class.java).putExtra("url", v).putExtra("fallback", true).putExtra("popup", true))
                finish(); overridePendingTransition(0, 0); return
            }
        }
        setContentView(R.layout.activity_add)
        url = findViewById(R.id.url); name = findViewById(R.id.name); cat = findViewById(R.id.category)
        info = findViewById(R.id.probeInfo); probing = findViewById(R.id.probing); saveTo = findViewById(R.id.saveTo)
        val conn = findViewById<Slider>(R.id.conn)
        val connLabel = findViewById<TextView>(R.id.connLabel)
        conn.value = DM.prefs.maxConnections.coerceIn(1, 32).toFloat()
        connLabel.text = "Connections: ${conn.value.toInt()}"
        conn.addOnChangeListener { _, v, _ -> connLabel.text = "Connections: ${v.toInt()}" }

        cat.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, Category.entries.map { it.label }))
        cat.setOnItemClickListener { _, _, pos, _ -> selCat = Category.entries[pos]; updateSaveTo() }
        setCategory(Category.OTHER)

        findViewById<TextInputLayout>(R.id.urlLayout).setEndIconOnClickListener {
            val t = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            UrlUtil.extractUrl(t)?.let { url.setText(it) } ?: toast("No link in clipboard")
        }
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (name.hasFocus()) { nameEdited = true; setCategory(Category.fromFileName(s.toString())) }
            }
        })
        url.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { h.removeCallbacksAndMessages(null); h.postDelayed({ probe() }, 700) }
        })
        findViewById<MaterialButton>(R.id.btnProbe).setOnClickListener { probe() }
        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { finish() }
        val queue = findViewById<CheckBox>(R.id.queue)
        val desc = findViewById<TextInputEditText>(R.id.desc)
        fun add(start: Boolean) {
            val u = url.text.toString().trim()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) { toast("Please enter a valid http/https link"); return }
            var n = Http.sanitize(name.text.toString().ifBlank { Http.fileNameFrom(null, u, null) })
            val item = DownloadItem(url = u, fileName = n, category = selCat, totalSize = size, resumable = resumable,
                connections = conn.value.toInt(), referer = referer, userAgent = ua ?: DM.prefs.userAgent, cookies = cookies,
                inQueue = queue.isChecked || !start, description = desc.text?.toString())
            DM.add(item, start)
            toast(if (start) "Download started" else "Added to queue")
            if (start && DM.prefs.showStartDialog) startActivity(Intent(this, ProgressActivity::class.java).putExtra("id", item.id))
            finish()
        }
        findViewById<MaterialButton>(R.id.btnLater).setOnClickListener { add(false) }
        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { add(true) }

        handleIntent(intent)
    }

    private fun handleIntent(i: Intent) {
        referer = i.getStringExtra("referer"); ua = i.getStringExtra("ua"); cookies = i.getStringExtra("cookies")
        i.getStringExtra("name")?.let { name.setText(it); nameEdited = true; setCategory(Category.fromFileName(it)) }
        val link: String? = when (i.action) {
            Intent.ACTION_SEND -> {
                val all = UrlUtil.extractAll(i.getStringExtra(Intent.EXTRA_TEXT))
                if (all.size > 1) { askAddAll(all); all.first() } else all.firstOrNull()
            }
            Intent.ACTION_VIEW -> i.dataString
            else -> i.getStringExtra("url")
        }
        if (link != null) url.setText(link)
        else UrlUtil.extractUrl((getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString())?.let { url.setText(it) }
    }

    private fun askAddAll(all: List<String>) {
        MaterialAlertDialogBuilder(this).setTitle("${all.size} links found")
            .setMessage("Add all links to the download queue?")
            .setPositiveButton("Add all") { _, _ -> Dialogs.addMany(this, all, start = false); finish() }
            .setNegativeButton("Only first", null).show()
    }

    private fun setCategory(c: Category) { selCat = c; cat.setText(c.label, false); updateSaveTo() }
    private fun updateSaveTo() { saveTo.text = "Save to: Download/${FileStore.ROOT}/${selCat.folder}" }

    private fun openVideoPicker(u: String, fallback: Boolean) {
        startActivity(Intent(this, VideoQualityActivity::class.java).putExtra("url", u).putExtra("fallback", fallback))
        finish()
    }

    private fun probe() {
        val u = url.text.toString().trim()
        if (!u.startsWith("http", true)) return
        // YouTube, Facebook, Instagram, TikTok… → quality list
        if (Ytdlp.isVideoSite(u)) { openVideoPicker(u, fallback = true); return }
        val seq = ++probeSeq
        probing.visibility = View.VISIBLE
        info.text = "Getting file info…"
        Thread {
            val r = runCatching { Http.probe(u, ua ?: DM.prefs.userAgent, referer, cookies) }
            runOnUiThread {
                if (seq != probeSeq || isFinishing) return@runOnUiThread
                probing.visibility = View.GONE
                r.onSuccess { p ->
                    if (p.contentType?.contains("text/html", true) == true) {
                        // a web page, not a file: try video extraction, else open the browser to list downloadable files
                        info.text = "This is a web page — looking for videos / files on it…"
                        openVideoPicker(p.finalUrl, fallback = true)
                        return@runOnUiThread
                    }
                    size = p.size; resumable = p.resumable
                    if (!nameEdited) { name.setText(p.fileName); setCategory(Category.fromFileName(p.fileName)) }
                    info.text = "Size: ${Fmt.size(p.size)}  •  Resume: ${if (p.resumable) "Yes ✔" else "No"}" + (p.contentType?.let { "  •  $it" } ?: "")
                }.onFailure { e ->
                    info.text = "Could not get info: ${e.message ?: "error"} (you can still try downloading)"
                    if (!nameEdited && name.text.isNullOrBlank()) name.setText(Http.fileNameFrom(null, u, null))
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
