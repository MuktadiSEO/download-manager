package com.mrmzone.idm.ui

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.Http
import com.mrmzone.idm.util.Fmt

/**
 * Small bottom popup for a link shared / selected in Chrome or any app:
 * file name + size, one tap on "Download" and you stay in the app you came from.
 */
class QuickDownloadActivity : AppCompatActivity() {
    private val dp by lazy { resources.displayMetrics.density }
    private var url = ""
    private var size = -1L
    private var resumable = false
    private var cat = Category.OTHER
    private lateinit var name: TextInputEditText
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var saveTo: TextView
    private var nameEdited = false
    private var referer: String? = null
    private var cookies: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra("url").orEmpty()
        if (!url.startsWith("http", true)) { finish(); return }
        val p = (18 * dp).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p / 2) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.logo_mrm) }, LinearLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt()))
        head.addView(TextView(this).apply { text = "Download file"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(p / 2, 0, 0, 0) })
        root.addView(head)

        root.addView(TextView(this).apply {
            text = url; textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary)); setPadding(0, p / 2, 0, 0)
        })

        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "File name" }
        name = TextInputEditText(til.context).apply { setSingleLine(); textSize = 15f; setText(Http.fileNameFrom(null, url, null)) }
        name.setOnFocusChangeListener { _, f -> if (f) nameEdited = true }
        til.addView(name)
        root.addView(til, LinearLayout.LayoutParams(-1, -2).apply { topMargin = p / 2 })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, 0) }
        spinner = ProgressBar(this)
        row.addView(spinner, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
        status = TextView(this).apply { textSize = 13f; text = "Getting file info…"; setPadding(p / 2, 0, 0, 0) }
        row.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        saveTo = TextView(this).apply { textSize = 12f; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)); setPadding(0, p / 3, 0, 0) }
        root.addView(saveTo)
        setCategory(Category.fromFileName(name.text.toString()))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(0, p, 0, 0) }
        btns.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "More options"
            setOnClickListener {
                startActivity(Intent(this@QuickDownloadActivity, AddDownloadActivity::class.java).putExtra("url", url)
                    .apply { if (nameEdited) putExtra("name", name.text.toString()) })
                finish()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        btns.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Later"; setOnClickListener { add(false) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = p / 2 })
        btns.addView(MaterialButton(this).apply {
            text = "Download"; setBackgroundColor(ContextCompat.getColor(context, R.color.brand_orange)); setOnClickListener { add(true) }
        })
        root.addView(btns)
        setContentView(root)

        val r = 22 * dp
        window.setBackgroundDrawable(GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@QuickDownloadActivity, R.color.surface))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        })
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(resources.displayMetrics.widthPixels, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        window.setWindowAnimations(android.R.style.Animation_InputMethod)
        setFinishOnTouchOutside(true)
        probe()
    }

    private fun setCategory(c: Category) { cat = c; saveTo.text = "Save to: Download/MRM Zone/${c.folder}" }

    private fun probe() {
        Thread {
            cookies = runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
            var res = runCatching { Http.probe(url, DM.prefs.userAgent, null, cookies) }
            // many sites block links opened outside their own pages (hotlink protection) → retry as if clicked on the site
            if (res.exceptionOrNull()?.message?.let { Regex("HTTP (401|403|406|410|451)").containsMatchIn(it) } == true) {
                val origin = runCatching { java.net.URL(url).let { "${it.protocol}://${it.host}/" } }.getOrNull()
                if (origin != null) {
                    val r2 = runCatching { Http.probe(url, DM.prefs.userAgent, origin, cookies) }
                    if (r2.isSuccess) { referer = origin; res = r2 }
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                spinner.visibility = View.GONE
                res.onSuccess { pr ->
                    if (pr.contentType?.contains("text/html", true) == true) {
                        // a web page, not a file → look for videos / files on it
                        startActivity(Intent(this, VideoQualityActivity::class.java).putExtra("url", pr.finalUrl).putExtra("fallback", true).putExtra("popup", true))
                        finish(); overridePendingTransition(0, 0); return@runOnUiThread
                    }
                    size = pr.size; resumable = pr.resumable
                    if (!nameEdited) { name.setText(pr.fileName); setCategory(Category.fromFileName(pr.fileName)) }
                    status.text = "Size: ${Fmt.size(pr.size)}   •   Resume: ${if (pr.resumable) "Yes ✔" else "No"}"
                }.onFailure { e ->
                    if (Regex("HTTP (401|403|406|410|451)").containsMatchIn(e.message.orEmpty())) {
                        // the site only gives the file to a real browser → let the built-in browser fetch it (its cookies + referer)
                        Toast.makeText(this, "This site blocks direct links — getting the file through MRM Zone browser…", Toast.LENGTH_LONG).show()
                        val origin = runCatching { java.net.URL(url).let { "${it.protocol}://${it.host}/" } }.getOrNull()
                        startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url).putExtra("referer", origin)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        finish(); return@runOnUiThread
                    }
                    status.text = "Could not read file info (${e.message ?: "error"}) — you can still download"
                }
            }
        }.start()
    }

    private fun add(start: Boolean) {
        val n = Http.sanitize(name.text.toString().ifBlank { Http.fileNameFrom(null, url, null) })
        val item = DownloadItem(url = url, fileName = n, category = if (nameEdited) Category.fromFileName(n) else cat, totalSize = size, resumable = resumable,
            connections = DM.prefs.maxConnections, userAgent = DM.prefs.userAgent, referer = referer, cookies = cookies, inQueue = !start)
        DM.add(item, start)
        Toast.makeText(this, if (start) "MRM Zone IDM: downloading $n — see notification" else "Added to queue", Toast.LENGTH_SHORT).show()
        finish()
    }
}
