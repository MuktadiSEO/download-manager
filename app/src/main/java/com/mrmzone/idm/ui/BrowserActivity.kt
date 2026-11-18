package com.mrmzone.idm.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.Http

/** Built-in browser with IDM-style "Download this video" panel and download interception. */
class BrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var bar: ProgressBar
    private val media = linkedSetOf<String>()
    private val mediaExts = Category.VIDEO.exts + Category.MUSIC.exts

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(ContextCompat.getColor(context, R.color.bar))
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fun btn(icon: Int, a: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon); setColorFilter(0xFFFFFFFF.toInt()); background = null
            setOnClickListener { a() }
        }.also { top.addView(it, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())) }
        btn(R.drawable.ic_close) { finish() }
        address = EditText(this).apply {
            setSingleLine(); textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9AA3AF.toInt())
            hint = "Search or type web address"; imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_GO || e?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false
            }
        }
        top.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        btn(R.drawable.ic_back) { if (web.canGoBack()) web.goBack() }
        btn(R.drawable.ic_forward) { if (web.canGoForward()) web.goForward() }
        btn(R.drawable.ic_refresh) { web.reload() }
        btn(R.drawable.ic_home) { go(DM.prefs.homePage) }
        root.addView(top)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar, LinearLayout.LayoutParams(-1, (3 * dp).toInt()))

        val frame = FrameLayout(this)
        web = WebView(this)
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        fab = ExtendedFloatingActionButton(this).apply {
            text = "Download this video"; setIconResource(R.drawable.ic_download)
            setBackgroundColor(ContextCompat.getColor(context, R.color.brand_orange))
            setTextColor(0xFFFFFFFF.toInt()); iconTint = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            visibility = View.GONE
            setOnClickListener { showMedia() }
        }
        frame.addView(fab, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
            setMargins(0, 0, (16 * dp).toInt(), (24 * dp).toInt())
        })
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false; mediaPlaybackRequiresUserGesture = true
            userAgentString = DM.prefs.userAgent
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { bar.progress = p; bar.visibility = if (p >= 100) View.INVISIBLE else View.VISIBLE }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                address.setText(url); media.clear(); updateFab()
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (!u.startsWith("http")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                    return true
                }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                val ext = u.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
                if (ext in mediaExts || u.contains("videoplayback", true) || u.contains("mime=video", true)) {
                    runOnUiThread { if (media.add(u)) updateFab() }
                }
                return null
            }
        }
        web.setDownloadListener { url, ua, disposition, mime, _ ->
            val name = Http.sanitize(URLUtil.guessFileName(url, disposition, mime))
            startAdd(url, name)
        }
        web.setOnLongClickListener {
            val r = web.hitTestResult
            val link = r.extra
            if (link != null && (r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE || r.type == WebView.HitTestResult.IMAGE_TYPE)) {
                MaterialAlertDialogBuilder(this).setTitle(link.take(120))
                    .setItems(arrayOf("Download with MRM Zone IDM", "Open link", "Grab all files from this page")) { _, w ->
                        when (w) {
                            0 -> startAdd(link, null)
                            1 -> web.loadUrl(link)
                            2 -> startActivity(Intent(this, GrabberActivity::class.java).putExtra("url", web.url))
                        }
                    }.show()
                true
            } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        go(intent.getStringExtra("url") ?: DM.prefs.homePage)
    }

    private fun go(input: String) {
        val t = input.trim()
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(t)
        }
        web.loadUrl(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        web.requestFocus()
    }

    private fun updateFab() {
        fab.visibility = if (media.isEmpty()) View.GONE else View.VISIBLE
        fab.text = if (media.size <= 1) "Download this video" else "Download media (${media.size})"
    }

    private fun showMedia() {
        val list = media.toList()
        if (list.size == 1) { startAdd(list[0], null); return }
        MaterialAlertDialogBuilder(this).setTitle("Media found on this page")
            .setItems(list.map { it.substringBefore('?').substringAfterLast('/').ifBlank { it }.take(70) }.toTypedArray()) { _, w -> startAdd(list[w], null) }
            .show()
    }

    private fun startAdd(url: String, name: String?) {
        val title = web.title
        val guessed = name ?: run {
            val n = Http.fileNameFrom(null, url, null)
            if (n.endsWith(".bin") && !title.isNullOrBlank()) Http.sanitize(title) + ".mp4" else n
        }
        startActivity(Intent(this, AddDownloadActivity::class.java)
            .putExtra("url", url).putExtra("name", guessed)
            .putExtra("referer", web.url).putExtra("ua", web.settings.userAgentString)
            .putExtra("cookies", CookieManager.getInstance().getCookie(url)))
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
