package com.mrmzone.idm.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.Http
import com.mrmzone.idm.engine.Ytdlp
import com.mrmzone.idm.util.Fmt
import java.net.URL

/**
 * Shows title, thumbnail and every available quality (144p … 4K/8K, audio, MP3) of a
 * YouTube / video-site page, powered by yt-dlp. If the page is not a supported video
 * page and "fallback" is set, the link is opened in the built-in browser instead.
 */
class VideoQualityActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private var url = ""
    private var fallbackBrowser = false
    private var popup = false
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra("url").orEmpty()
        fallbackBrowser = intent.getBooleanExtra("fallback", false)
        popup = intent.getBooleanExtra("popup", false)
        val p = (18 * dp).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p / 2) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.logo_mrm) }, LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()))
        head.addView(TextView(this).apply {
            text = "Video Download"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(p / 2, 0, 0, 0)
        })
        root.addView(head)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, 0) }
        spinner = ProgressBar(this)
        row.addView(spinner, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
        status = TextView(this).apply { textSize = 13f; setPadding(p / 2, 0, 0, 0); setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) }
        row.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val close = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "Close"; setOnClickListener { finish() } }
        root.addView(close, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        setContentView(root)
        if (popup) {
            // bottom sheet over the app the link was shared from (Facebook, YouTube…)
            val r = 22 * dp
            window.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@VideoQualityActivity, R.color.surface))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            })
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(resources.displayMetrics.widthPixels, (resources.displayMetrics.heightPixels * 0.62).toInt())
            window.setWindowAnimations(android.R.style.Animation_InputMethod)
            setFinishOnTouchOutside(true)
        } else
            window.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.heightPixels * 0.85).toInt())
        load()
    }

    private fun load() {
        body.removeAllViews()
        Ytdlp.cached(url)?.let { spinner.visibility = View.GONE; show(it); return }
        spinner.visibility = View.VISIBLE
        status.text = "Finding all qualities…"
        Thread {
            val r = runCatching { Ytdlp.get(this, url) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                spinner.visibility = View.GONE
                r.onSuccess { show(it) }.onFailure { fail(it) }
            }
        }.start()
    }

    private fun fail(e: Throwable) {
        val msg = (e.message ?: "").lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim() ?: e.message ?: "Unknown error"
        if (fallbackBrowser && (msg.contains("Unsupported URL", true) || msg.contains("No video", true) || msg.contains("Unable to extract", true))) {
            Toast.makeText(this, "Opening in browser to find downloadable files…", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish(); return
        }
        val needLogin = msg.contains("not a bot", true) || msg.contains("Sign in", true) || msg.contains("cookies", true) || msg.contains("login", true)
        status.text = if (needLogin)
            "YouTube asks you to sign in. Tap \"Sign in to YouTube\" below, log in once in the MRM Zone browser, then come back and tap \"Try again\" — your login will be used automatically."
        else "Could not read this video:\n$msg"
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (12 * dp).toInt(), 0, 0) }
        fun btn(t: String, a: () -> Unit) = box.addView(MaterialButton(this).apply { text = t; setOnClickListener { a() } })
        if (needLogin) btn("Sign in to YouTube") {
            startActivity(Intent(this, BrowserActivity::class.java)
                .putExtra("url", "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F")
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        btn("Try again") { Ytdlp.invalidate(url); load() }
        btn("Update video engine & retry") {
            status.text = "Updating yt-dlp…"; spinner.visibility = View.VISIBLE
            Ytdlp.invalidate(url)
            Thread { val s = runCatching { Ytdlp.update(this) }.getOrElse { "Update failed: ${it.message}" }
                runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); load() } }.start()
        }
        btn("Open in browser") {
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); finish()
        }
        body.addView(box)
    }

    private fun show(info: Ytdlp.Info) {
        status.text = "${info.qualities.size} options found — tap one to download"
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF000000.toInt()) }
        if (!info.thumbnail.isNullOrBlank()) body.addView(thumb, LinearLayout.LayoutParams(-1, (180 * dp).toInt()).apply { topMargin = (10 * dp).toInt() })
        info.thumbnail?.let { t ->
            Thread {
                val bmp = runCatching { URL(t).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
                runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) }
            }.start()
        }
        body.addView(TextView(this).apply {
            text = info.title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); maxLines = 3
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        val meta = listOfNotNull(info.uploader, if (info.duration > 0) Fmt.time(info.duration.toLong()) else null).joinToString("  •  ")
        if (meta.isNotBlank()) body.addView(TextView(this).apply { text = meta; textSize = 12f; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) })
        val queue = CheckBox(this).apply { text = "Add to queue (download later)" }

        var lastAudio = false
        var videoHeader = false
        for (q in info.qualities) {
            if (q.audioOnly && !lastAudio) {
                body.addView(TextView(this).apply {
                    text = "AUDIO ONLY"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.brand_orange)); setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
                })
            } else if (!q.audioOnly && !videoHeader) {
                videoHeader = true
                body.addView(TextView(this).apply {
                    text = "VIDEO"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.brand_orange)); setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
                })
            }
            lastAudio = q.audioOnly
            body.addView(row(q) { start(info, q, queue.isChecked) })
        }
        body.addView(queue)
    }

    private fun row(q: Ytdlp.Quality, onClick: () -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = 10 * dp; cardElevation = 0f; strokeWidth = (1 * dp).toInt()
            strokeColor = ContextCompat.getColor(context, R.color.seg_bg)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            isClickable = true; setOnClickListener { onClick() }
        }
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt()) }
        l.addView(ImageView(this).apply {
            setImageResource(if (q.audioOnly) R.drawable.ic_cat_music else R.drawable.ic_cat_video)
            setColorFilter(ContextCompat.getColor(context, if (q.audioOnly) R.color.cat_music else R.color.cat_video))
        }, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        texts.addView(TextView(this).apply { text = q.label; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        texts.addView(TextView(this).apply { text = q.detail; textSize = 12f; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) })
        l.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        l.addView(TextView(this).apply { text = if (q.size > 0) (if (q.approx) "~" else "") + Fmt.size(q.size) else ""; textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
        l.addView(ImageView(this).apply { setImageResource(R.drawable.ic_download); setColorFilter(ContextCompat.getColor(context, R.color.brand_blue)) },
            LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply { marginStart = (10 * dp).toInt() })
        card.addView(l)
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() }
        return card
    }

    private fun start(info: Ytdlp.Info, q: Ytdlp.Quality, queueOnly: Boolean) {
        val suffix = if (q.audioOnly) "" else " [${q.label}]"
        val name = Http.sanitize(info.title.take(150) + suffix + "." + q.ext)
        val item = DownloadItem(
            url = info.pageUrl, fileName = name,
            category = if (q.audioOnly) Category.MUSIC else Category.VIDEO,
            totalSize = q.size, resumable = true, connections = DM.prefs.maxConnections.coerceAtMost(16),
            userAgent = null, inQueue = queueOnly, description = info.title,
            kind = "ytdlp", formatSpec = q.formatSpec, extra = q.extra,
        )
        DM.add(item, !queueOnly)
        Toast.makeText(this, if (queueOnly) "Added to queue" else if (popup) "MRM Zone IDM: downloading ${q.label} — see notification" else "Downloading ${q.label}…", Toast.LENGTH_SHORT).show()
        if (!queueOnly && !popup && DM.prefs.showStartDialog) startActivity(Intent(this, ProgressActivity::class.java).putExtra("id", item.id))
        finish()
    }
}
