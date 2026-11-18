package com.mrmzone.idm.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.data.Status
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.Http
import com.mrmzone.idm.engine.Ytdlp
import org.json.JSONArray
import org.json.JSONTokener

/**
 * Built-in multi-tab browser:
 *  • YouTube / video sites → "Download video" shows every quality (yt-dlp)
 *  • any page → lists all downloadable files & media found on it
 *  • Downloads button (with active count) jumps to the download list
 */
class BrowserActivity : AppCompatActivity() {
    private class Tab(val web: WebView) {
        val media = linkedSetOf<String>()
        var files: List<String> = emptyList()
        var desktop = false
    }

    private val tabs = mutableListOf<Tab>()
    private var cur = -1
    private val tab get() = tabs[cur]
    private lateinit var address: EditText
    private lateinit var content: FrameLayout
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var bar: ProgressBar
    private lateinit var tabCount: TextView
    private lateinit var dlBadge: TextView
    private val dp by lazy { resources.displayMetrics.density }
    private val mediaExts = Category.VIDEO.exts + Category.MUSIC.exts
    private val fileExts by lazy { Category.downloadableExts - setOf("php", "asp", "aspx", "jsp") }
    private val dmListener: () -> Unit = { updateBadge() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val barColor = ContextCompat.getColor(this, R.color.bar)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- top: close, address, refresh
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(barColor); gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        }
        top.addView(iconBtn(R.drawable.ic_close) { finish() })
        address = EditText(this).apply {
            setSingleLine(); textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9AA3AF.toInt())
            hint = "Search or type web address"; imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI; setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_GO || e?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false
            }
        }
        top.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconBtn(R.drawable.ic_refresh) { tab.web.reload() })
        root.addView(top)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar, LinearLayout.LayoutParams(-1, (3 * dp).toInt()))

        // ---- content
        content = FrameLayout(this)
        fab = ExtendedFloatingActionButton(this).apply {
            setIconResource(R.drawable.ic_download)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_orange))
            setTextColor(0xFFFFFFFF.toInt()); iconTint = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            visibility = View.GONE
            setOnClickListener { onFab() }
        }
        content.addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (16 * dp).toInt(), (16 * dp).toInt())
        })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- bottom: back, forward, home, tabs, downloads, menu
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(barColor); gravity = Gravity.CENTER }
        fun slot(v: View) = bottom.addView(v, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f))
        slot(iconBtn(R.drawable.ic_back) { if (tab.web.canGoBack()) tab.web.goBack() })
        slot(iconBtn(R.drawable.ic_forward) { if (tab.web.canGoForward()) tab.web.goForward() })
        slot(iconBtn(R.drawable.ic_home) { go(DM.prefs.homePage) })
        tabCount = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { setStroke((2 * dp).toInt(), 0xFFFFFFFF.toInt()); cornerRadius = 5 * dp }
        }
        val tabBox = FrameLayout(this).apply { setOnClickListener { showTabs() }; contentDescription = "Tabs" }
        tabBox.addView(tabCount, FrameLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt(), Gravity.CENTER))
        slot(tabBox)
        val dlBox = FrameLayout(this).apply { setOnClickListener { openDownloads() }; contentDescription = "Downloads" }
        dlBox.addView(ImageView(this).apply { setImageResource(R.drawable.ic_cloud_download); setColorFilter(0xFFFFFFFF.toInt()) },
            FrameLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt(), Gravity.CENTER))
        dlBadge = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 10f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(context, R.color.brand_orange)) }
        }
        dlBox.addView(dlBadge, FrameLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt(), Gravity.CENTER).apply {
            leftMargin = (14 * dp).toInt(); bottomMargin = (14 * dp).toInt()
        })
        slot(dlBox)
        val menuBtn = iconBtn(R.drawable.ic_more) {}
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        slot(menuBtn)
        root.addView(bottom)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    tab.web.canGoBack() -> tab.web.goBack()
                    tabs.size > 1 -> closeTab(cur)
                    else -> finish()
                }
            }
        })
        newTab(intent.getStringExtra("url") ?: DM.prefs.homePage)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("url")?.let { newTab(it) }
    }

    override fun onStart() { super.onStart(); DM.listeners.add(dmListener); updateBadge() }
    override fun onStop() { DM.listeners.remove(dmListener); super.onStop() }

    private fun iconBtn(icon: Int, a: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setColorFilter(0xFFFFFFFF.toInt()); background = null
        setOnClickListener { a() }
        layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
    }

    private fun updateBadge() {
        val n = DM.snapshot().count { it.status.isActive }
        dlBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
        dlBadge.text = if (n > 9) "9+" else n.toString()
    }

    private fun openDownloads() {
        startActivity(Intent(this, MainActivity::class.java).putExtra("tab", 0).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // ---------------- tabs ----------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): Tab {
        val web = WebView(this)
        val t = Tab(web)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false; mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
            userAgentString = DM.prefs.userAgent
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (view != tab.web) return
                bar.progress = p; bar.visibility = if (p >= 100) View.INVISIBLE else View.VISIBLE
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val nt = createWebView()
                tabs.add(nt); switchTo(tabs.size - 1)
                (resultMsg.obj as WebView.WebViewTransport).webView = nt.web
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { tabs.indexOfFirst { it.web == window }.takeIf { it >= 0 }?.let { closeTab(it) } }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.media.clear(); t.files = emptyList()
                if (view == tab.web) { address.setText(url); updateFab() }
            }
            override fun onPageFinished(view: WebView, url: String) { scan(t) }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // single-page sites (YouTube) change URL without reloading
                if (view == tab.web && !address.hasFocus()) address.setText(url)
                if (view == tab.web) updateFab()
                view.postDelayed({ scan(t) }, 1500)
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (!u.startsWith("http")) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }; return true }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                if (Ytdlp.isYouTube(u) || u.contains("googlevideo.com")) return null // handled by quality picker
                val ext = u.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
                if (ext in mediaExts) runOnUiThread { if (t.media.add(u) && tabs.getOrNull(cur) == t) updateFab() }
                return null
            }
        }
        web.setDownloadListener { url, _, disposition, mime, _ ->
            startAdd(url, Http.fileNameFrom(disposition, url, mime), t)
        }
        web.setOnLongClickListener {
            val r = web.hitTestResult
            val link = r.extra
            if (link != null && link.startsWith("http") && (r.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                    r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE || r.type == WebView.HitTestResult.IMAGE_TYPE)) {
                MaterialAlertDialogBuilder(this).setTitle(link.take(120))
                    .setItems(arrayOf("Download with MRM Zone IDM", "Open in new tab", "Open", "Copy link")) { _, w ->
                        when (w) {
                            0 -> if (Ytdlp.isVideoSite(link)) openPicker(link) else startAdd(link, null, t)
                            1 -> newTab(link)
                            2 -> web.loadUrl(link)
                            3 -> copy(link)
                        }
                    }.show()
                true
            } else false
        }
        return t
    }

    private fun newTab(url: String) {
        val t = createWebView()
        tabs.add(t)
        switchTo(tabs.size - 1)
        load(t, url)
    }

    private fun switchTo(i: Int) {
        if (cur in tabs.indices) content.removeView(tabs[cur].web)
        cur = i
        content.addView(tab.web, 0, FrameLayout.LayoutParams(-1, -1))
        tab.web.onResume()
        address.setText(tab.web.url.orEmpty())
        tabCount.text = tabs.size.toString()
        updateFab()
    }

    private fun closeTab(i: Int) {
        val t = tabs[i]
        val wasCurrent = i == cur
        if (wasCurrent) content.removeView(t.web)
        tabs.removeAt(i)
        t.web.destroy()
        if (tabs.isEmpty()) { cur = -1; newTab(DM.prefs.homePage); return }
        if (wasCurrent) { cur = -1; switchTo(minOf(i, tabs.size - 1)) }
        else { if (i < cur) cur--; tabCount.text = tabs.size.toString(); updateFab() }
    }

    private fun showTabs() {
        var dlg: AlertDialog? = null
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), 0) }
        tabs.forEachIndexed { i, t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(ContextCompat.getColor(context, if (i == cur) R.color.seg_bg else R.color.surface))
                }
                setOnClickListener { switchTo(i); dlg?.dismiss() }
            }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(this).apply { text = t.web.title?.ifBlank { null } ?: "New tab"; textSize = 15f; maxLines = 1; setTypeface(typeface, Typeface.BOLD) })
            txt.addView(TextView(this).apply { text = t.web.url.orEmpty(); textSize = 12f; maxLines = 1; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) })
            row.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_close); background = null
                setOnClickListener { closeTab(i); dlg?.dismiss(); showTabs() }
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * dp).toInt() })
        }
        dlg = MaterialAlertDialogBuilder(this).setTitle("Tabs (${tabs.size})")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("+ New tab") { _, _ -> newTab(DM.prefs.homePage) }
            .setNegativeButton("Close", null).show()
    }

    // ---------------- navigation ----------------
    private fun load(t: Tab, input: String) {
        val s = input.trim()
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains('.') && !s.contains(' ') -> "https://$s"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(s)
        }
        t.web.loadUrl(url)
    }

    private fun go(input: String) {
        load(tab, input)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus(); tab.web.requestFocus()
    }

    // ---------------- download detection ----------------
    private val scanJs = """(function(){var o=[];function a(u){if(u&&u.indexOf('blob:')!=0&&u.indexOf('data:')!=0&&u.indexOf('javascript:')!=0)o.push(u)}
document.querySelectorAll('a[href]').forEach(function(e){a(e.href)});
document.querySelectorAll('video,audio,source,track').forEach(function(e){a(e.currentSrc||e.src)});
return JSON.stringify(o.slice(0,4000));})()"""

    private fun scan(t: Tab) {
        val pageUrl = t.web.url ?: return
        if (Ytdlp.isYouTube(pageUrl)) { if (tabs.getOrNull(cur) == t) updateFab(); return }
        t.web.evaluateJavascript(scanJs) { res ->
            val links = runCatching {
                val str = JSONTokener(res).nextValue() as? String ?: return@runCatching emptyList()
                val arr = JSONArray(str)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(emptyList())
            t.files = links.filter { it.startsWith("http") && extOf(it) in fileExts }.distinct()
            if (tabs.getOrNull(cur) == t) updateFab()
        }
    }

    private fun extOf(u: String) = u.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun isVideoPage(url: String?) = Ytdlp.isYouTubeVideo(url) || (Ytdlp.isVideoSite(url) && !Ytdlp.isYouTube(url))

    private fun allFound(t: Tab) = (t.media + t.files).distinct()

    private fun updateFab() {
        if (cur !in tabs.indices) return
        val url = tab.web.url
        val found = allFound(tab).size
        val video = isVideoPage(url)
        fab.visibility = if (video || found > 0) View.VISIBLE else View.GONE
        fab.text = when {
            video && found > 0 -> "Download video • files ($found)"
            video -> "Download video"
            else -> "Downloadable files ($found)"
        }
    }

    private fun onFab() {
        val url = tab.web.url ?: return
        val found = allFound(tab)
        if (isVideoPage(url) && found.isEmpty()) { openPicker(url); return }
        showFound(url, found)
    }

    private fun showFound(pageUrl: String, found: List<String>) {
        val names = found.map { u ->
            val n = Http.fileNameFrom(null, u, null)
            "${n.take(60)}\n${runCatching { java.net.URL(u).host }.getOrDefault("")}"
        }.toTypedArray()
        val checked = BooleanArray(found.size)
        val b = MaterialAlertDialogBuilder(this).setTitle(if (found.isEmpty()) "Nothing to download found" else "Found on this page (${found.size})")
        if (found.isNotEmpty()) b.setMultiChoiceItems(names, checked) { _, i, c -> checked[i] = c }
        b.setPositiveButton("Download selected") { _, _ ->
            val sel = found.filterIndexed { i, _ -> checked[i] }
            when {
                sel.isEmpty() -> Toast.makeText(this, "Select files first", Toast.LENGTH_SHORT).show()
                sel.size == 1 -> startAdd(sel[0], null, tab)
                else -> Dialogs.addMany(this, sel, true, pageUrl, { CookieManager.getInstance().getCookie(it) }, tab.web.settings.userAgentString)
            }
        }
        b.setNeutralButton("Video qualities") { _, _ -> openPicker(pageUrl) }
        b.setNegativeButton("Close", null)
        b.show()
    }

    private fun openPicker(url: String) {
        startActivity(Intent(this, VideoQualityActivity::class.java).putExtra("url", url))
    }

    private fun startAdd(url: String, name: String?, t: Tab) {
        val title = t.web.title
        val guessed = name ?: run {
            val n = Http.fileNameFrom(null, url, null)
            if (n.endsWith(".bin") && !title.isNullOrBlank()) Http.sanitize(title) + ".mp4" else n
        }
        startActivity(Intent(this, AddDownloadActivity::class.java)
            .putExtra("url", url).putExtra("name", guessed)
            .putExtra("referer", t.web.url).putExtra("ua", t.web.settings.userAgentString)
            .putExtra("cookies", CookieManager.getInstance().getCookie(url)))
    }

    // ---------------- menu ----------------
    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, "New tab")
        m.add(0, 2, 0, "Download video (all qualities)")
        m.add(0, 3, 0, "Downloadable files on this page")
        m.add(0, 4, 0, "Site grabber (deep scan)")
        m.add(0, 5, 0, "Downloads")
        m.add(0, 6, 0, if (tab.desktop) "Mobile site" else "Desktop site")
        m.add(0, 7, 0, "Copy link")
        m.add(0, 8, 0, "Share link")
        pm.setOnMenuItemClickListener {
            val url = tab.web.url.orEmpty()
            when (it.itemId) {
                1 -> newTab(DM.prefs.homePage)
                2 -> openPicker(url)
                3 -> { scan(tab); tab.web.postDelayed({ showFound(url, allFound(tab)) }, 600) }
                4 -> startActivity(Intent(this, GrabberActivity::class.java).putExtra("url", url))
                5 -> openDownloads()
                6 -> {
                    tab.desktop = !tab.desktop
                    tab.web.settings.userAgentString = if (tab.desktop)
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
                    else DM.prefs.userAgent
                    tab.web.settings.useWideViewPort = true
                    tab.web.reload()
                }
                7 -> copy(url)
                8 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Share"))
            }
            true
        }
        pm.show()
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", s))
        DM.prefs.lastClip = s
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() { tabs.forEach { it.web.destroy() }; super.onDestroy() }

    @Suppress("unused") private val keepImports = listOf(MaterialButton::class, Status::class)
}
