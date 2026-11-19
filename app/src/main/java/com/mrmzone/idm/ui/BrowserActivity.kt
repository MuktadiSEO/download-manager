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
import com.google.android.material.snackbar.Snackbar
import com.mrmzone.idm.engine.AdBlock
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
        lateinit var sniffer: MediaSniffer
        @Volatile var ua: String = DM.prefs.userAgent
        var files: List<String> = emptyList()
        var desktop = false
        var pendingUrl: String? = null          // restored tab, loaded when first shown
        @Volatile var pageUrl: String? = null
        @Volatile var adOn = true
        @Volatile var blocked = 0
        @Volatile var popupAt = 0L
        val url: String? get() = web.url ?: pendingUrl
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
        top.addView(iconBtn(R.drawable.ic_close) { closeCurrentTab() })
        address = EditText(this).apply {
            setSingleLine(); textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9AA3AF.toInt())
            hint = "Search or type web address"; imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI; setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_GO || e?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false
            }
        }
        top.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        shield = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 12f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xFF2E7D32.toInt()) }
            setOnClickListener { showShield() }
            contentDescription = "Ad blocker"
        }
        top.addView(shield, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (4 * dp).toInt() })
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
                // leaving the browser keeps all tabs; they are restored next time
                if (tab.web.canGoBack()) tab.web.goBack() else finish()
            }
        })
        restoreTabs()
        intent.getStringExtra("url")?.let { newTab(it) }
        if (tabs.isEmpty()) newTab(DM.prefs.homePage)
    }

    // ---------------- saved tabs ----------------
    private fun saveTabs() {
        val arr = JSONArray()
        tabs.forEach { arr.put(it.url ?: DM.prefs.homePage) }
        DM.prefs.savedTabs = arr.toString()
        DM.prefs.savedTabCur = cur.coerceAtLeast(0)
    }

    private fun restoreTabs() {
        val arr = runCatching { JSONArray(DM.prefs.savedTabs ?: return) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val t = createWebView()
            t.pendingUrl = arr.optString(i).ifBlank { DM.prefs.homePage }
            tabs.add(t)
        }
        if (tabs.isNotEmpty()) switchTo(DM.prefs.savedTabCur.coerceIn(0, tabs.size - 1))
    }

    /** ✕ button: close only the current tab (closing the last one leaves the browser). */
    private fun closeCurrentTab() {
        if (tabs.size <= 1) {
            tabs.forEach { content.removeView(it.web); it.web.destroy() }
            tabs.clear(); cur = -1
            DM.prefs.savedTabs = null
            finish()
        } else closeTab(cur)
    }

    override fun onPause() { super.onPause(); if (tabs.isNotEmpty()) saveTabs() }

    // ---------------- ad blocker UI ----------------
    private lateinit var shield: TextView

    private fun updateShield() {
        if (cur !in tabs.indices) return
        val on = tab.adOn && DM.prefs.adblock
        shield.text = if (!on) "🛡 off" else "🛡 ${tab.blocked}"
        (shield.background as GradientDrawable).setColor(if (on) 0xFF2E7D32.toInt() else 0xFF757575.toInt())
    }

    private fun showShield() {
        val url = tab.url
        val site = AdBlock.site(AdBlock.host(url)) ?: return
        val allowed = site in DM.prefs.adblockAllow
        val msg = when {
            !DM.prefs.adblock -> "Ad blocker is turned off in Options."
            allowed -> "Ad blocker is OFF for $site."
            else -> "${tab.blocked} ads / pop-ups blocked on this page."
        }
        val b = MaterialAlertDialogBuilder(this).setTitle("Ad blocker").setMessage(msg)
        if (DM.prefs.adblock) b.setPositiveButton(if (allowed) "Turn ON for $site" else "Turn OFF for $site") { _, _ ->
            DM.prefs.adblockAllow = if (allowed) DM.prefs.adblockAllow - site else DM.prefs.adblockAllow + site
            tab.adOn = AdBlock.enabledFor(DM.prefs, url); updateShield(); tab.web.reload()
        } else b.setPositiveButton("Turn ON") { _, _ -> DM.prefs.adblock = true; tab.adOn = AdBlock.enabledFor(DM.prefs, url); updateShield(); tab.web.reload() }
        b.setNegativeButton("Close", null).show()
    }

    private fun blockedSnack(text: String, url: String) {
        Snackbar.make(content, text, Snackbar.LENGTH_LONG).setAction("Open anyway") { newTab(url) }.show()
    }

    private fun countBlocked(t: Tab) {
        t.blocked++
        runOnUiThread { if (tabs.getOrNull(cur) == t) updateShield() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("url")?.let { newTab(it) }
    }

    override fun onStart() {
        super.onStart(); DM.listeners.add(dmListener); updateBadge()
        Ytdlp.onPrefetched = { if (cur in tabs.indices) updateFab() }
    }
    override fun onStop() { DM.listeners.remove(dmListener); Ytdlp.onPrefetched = null; super.onStop() }

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
        t.sniffer = MediaSniffer({ t.ua }) { runOnUiThread { if (tabs.getOrNull(cur) == t) updateFab() } }
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
                if (!isUserGesture) { if (t.adOn) countBlocked(t); return false }
                // Load the new window in a hidden throw-away WebView first, look where it goes,
                // then either open it as a real tab or silently destroy it (pop-up / pop-under ad).
                val clicked = view.hitTestResult.extra?.takeIf { it.startsWith("http") }
                t.popupAt = System.currentTimeMillis()
                val probe = WebView(this@BrowserActivity)
                var handled = false
                fun decide(u: String) {
                    if (handled) return
                    handled = true
                    probe.stopLoading()
                    probe.post { runCatching { probe.destroy() } }
                    val isAd = t.adOn && AdBlock.loaded && (
                        AdBlock.isPopupAd(u) ||
                        (clicked == null && !AdBlock.sameSite(u, t.pageUrl)) ||
                        (clicked != null && u != clicked && !AdBlock.sameSite(u, clicked) && !AdBlock.sameSite(u, t.pageUrl)))
                    if (isAd) { countBlocked(t); blockedSnack("Pop-up ad blocked", u) } else newTab(u)
                }
                probe.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        val u = r.url.toString()
                        if (u.startsWith("http")) decide(u)
                        return true
                    }
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                        if (url.startsWith("http")) decide(url)
                    }
                }
                probe.postDelayed({ if (!handled) { handled = true; runCatching { probe.destroy() } } }, 15_000)
                (resultMsg.obj as WebView.WebViewTransport).webView = probe
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { tabs.indexOfFirst { it.web == window }.takeIf { it >= 0 }?.let { closeTab(it) } }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.sniffer.clear(); t.files = emptyList()
                t.pageUrl = url; t.blocked = 0
                t.adOn = AdBlock.enabledFor(DM.prefs, url)
                if (view == tab.web) { address.setText(url); updateFab(); updateShield() }
            }
            override fun onPageFinished(view: WebView, url: String) { scan(t) }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // single-page sites (YouTube) change URL without reloading
                t.pageUrl = url
                if (view == tab.web && !address.hasFocus()) address.setText(url)
                if (view == tab.web) updateFab()
                view.postDelayed({ scan(t) }, 1500)
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (!u.startsWith("http")) {
                    // intent:// / market:// links are a common ad trick; only follow them on a real tap
                    if (req.hasGesture() && !(t.adOn && u.startsWith("intent:"))) runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                    else if (t.adOn) countBlocked(t)
                    return true
                }
                if (t.adOn && AdBlock.loaded && req.isForMainFrame && !AdBlock.sameSite(u, t.pageUrl)) {
                    // redirect to a known ad / pop-under network
                    if (AdBlock.isPopupAd(u)) { countBlocked(t); runOnUiThread { blockedSnack("Ad page blocked", u) }; return true }
                    // pop-under trick: right after opening a pop-up, the page sends this tab to another site by script
                    if (!req.hasGesture() && System.currentTimeMillis() - t.popupAt < 4000) {
                        countBlocked(t); runOnUiThread { blockedSnack("Redirect to ad blocked", u) }; return true
                    }
                }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                if (t.adOn && AdBlock.loaded && !req.isForMainFrame && AdBlock.isAd(u) && !AdBlock.sameSite(u, t.pageUrl)) {
                    countBlocked(t)
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                if (Ytdlp.isYouTube(t.pageUrl) || u.contains("googlevideo.com")) return null // handled by quality picker
                t.sniffer.onRequest(req, t.pageUrl)
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
        saveTabs()
    }

    private fun switchTo(i: Int) {
        if (cur in tabs.indices) content.removeView(tabs[cur].web)
        cur = i
        content.addView(tab.web, 0, FrameLayout.LayoutParams(-1, -1))
        tab.web.onResume()
        tab.pendingUrl?.let { p -> tab.pendingUrl = null; load(tab, p) }
        address.setText(tab.url.orEmpty())
        updateShield()
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
        saveTabs()
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
            txt.addView(TextView(this).apply { text = t.web.title?.ifBlank { null } ?: t.url ?: "New tab"; textSize = 15f; maxLines = 1; setTypeface(typeface, Typeface.BOLD) })
            txt.addView(TextView(this).apply { text = t.url.orEmpty(); textSize = 12f; maxLines = 1; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) })
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
            t.files = links.filter { it.startsWith("http") && extOf(it) in fileExts && extOf(it) !in mediaExts }.distinct()
            links.filter { it.startsWith("http") && (extOf(it) in mediaExts || it.contains(".m3u8")) }.forEach { t.sniffer.addFromDom(it, pageUrl) }
            if (tabs.getOrNull(cur) == t) updateFab()
        }
    }

    private fun extOf(u: String) = u.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun isVideoPage(url: String?) = Ytdlp.isYouTubeVideo(url) || (Ytdlp.isVideoSite(url) && !Ytdlp.isYouTube(url))

    private fun allFound(t: Tab) = t.files

    private var lastPrefetch: String? = null

    private fun maybePrefetch(url: String?) {
        if (url == null || !isVideoPage(url)) return
        val k = Ytdlp.key(url)
        if (k == lastPrefetch) return
        lastPrefetch = k
        Ytdlp.prefetch(this, url)
    }

    private fun updateFab() {
        if (cur !in tabs.indices) return
        val url = tab.web.url
        maybePrefetch(url)
        val found = allFound(tab).size
        val vids = tab.sniffer.ready().size
        if (vids > 0 && !isVideoPage(url)) {
            fab.visibility = View.VISIBLE
            fab.text = if (vids == 1) "Download this video" else "Download videos ($vids)"
            return
        }
        val video = isVideoPage(url)
        fab.visibility = if (video || found > 0) View.VISIBLE else View.GONE
        val ready = video && url != null && Ytdlp.isReady(url)
        val dv = if (ready) "Download video ✓" else "Download video"
        fab.text = when {
            video && found > 0 -> "$dv • files ($found)"
            video -> dv
            else -> "Downloadable files ($found)"
        }
    }

    private fun onFab() {
        val url = tab.web.url ?: return
        val found = allFound(tab)
        if (!isVideoPage(url) && tab.sniffer.ready().isNotEmpty()) { showMedia(tab); return }
        if (isVideoPage(url) && found.isEmpty()) { openPicker(url); return }
        showFound(url, found)
    }

    private fun showFound(pageUrl: String, found: List<String>) {
        val names = found.map { u ->
            val n = Http.fileNameFrom(null, u, null)
            "${n.take(60)}\n${runCatching { java.net.URL(u).host }.getOrDefault("")}"
        }.toTypedArray()
        val checked = BooleanArray(found.size) { found.size <= 10 }
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

    /** IDM-style "Download this video" list: every video found on the page with its qualities and sizes. */
    private fun showMedia(t: Tab) {
        val pageUrl = t.web.url.orEmpty()
        val title = Http.sanitize(Ytdlp.cleanTitle(t.web.title).take(120))
        var dlg: AlertDialog? = null
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), 0) }
        fun header(text: String) = list.addView(TextView(this).apply {
            this.text = text; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.brand_orange)); setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        })
        fun row(label: String, detail: String, size: Long, approx: Boolean, onClick: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                background = GradientDrawable().apply { cornerRadius = 10 * dp; setStroke((1 * dp).toInt(), ContextCompat.getColor(context, R.color.seg_bg)) }
                setOnClickListener { dlg?.dismiss(); onClick() }
            }
            r.addView(ImageView(this).apply { setImageResource(R.drawable.ic_cat_video); setColorFilter(ContextCompat.getColor(context, R.color.cat_video)) },
                LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
            tx.addView(TextView(this).apply { text = label; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
            tx.addView(TextView(this).apply { text = detail; textSize = 12f; maxLines = 1; setTextColor(ContextCompat.getColor(context, R.color.text_secondary)) })
            r.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(TextView(this).apply { text = if (size > 0) (if (approx) "~" else "") + com.mrmzone.idm.util.Fmt.size(size) else ""; textSize = 13f; setTypeface(typeface, Typeface.BOLD) })
            r.addView(ImageView(this).apply { setImageResource(R.drawable.ic_download); setColorFilter(ContextCompat.getColor(context, R.color.brand_blue)) },
                LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply { marginStart = (8 * dp).toInt() })
            list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })
        }
        val media = t.sniffer.ready()
        media.forEachIndexed { i, m ->
            val host = runCatching { java.net.URL(m.url).host }.getOrDefault("")
            when (m.kind) {
                MediaSniffer.Kind.HLS -> {
                    header("VIDEO ${i + 1} • HLS stream" + if (m.duration > 0) " • ${com.mrmzone.idm.util.Fmt.time(m.duration.toLong())}" else "")
                    if (m.variants.isEmpty()) row("Best quality", host, -1, false) { startStream(t, m, null, title) }
                    else m.variants.forEach { v -> row(v.label, "MP4 • $host", v.size, true) { startStream(t, m, v, title) } }
                }
                MediaSniffer.Kind.DASH -> {
                    header("VIDEO ${i + 1} • DASH stream")
                    row("Best quality", host, -1, false) { startStream(t, m, null, title) }
                }
                MediaSniffer.Kind.FILE -> {
                    header("VIDEO ${i + 1} • file")
                    val name = Http.fileNameFrom(null, m.url, m.type).let { if (it.endsWith(".bin")) "$title.mp4" else it }
                    row(name.take(40), (m.type ?: "") + " • " + host, m.size, false) {
                        startAdd(m.url, name, t, m.referer)
                    }
                }
            }
        }
        val checking = t.sniffer.checking()
        if (checking > 0) list.addView(TextView(this).apply { text = "Still checking $checking more…"; textSize = 12f; setPadding(0, (8 * dp).toInt(), 0, 0) })
        val b = MaterialAlertDialogBuilder(this).setTitle("Videos on this page")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("Close", null)
            .setNeutralButton("Smart detect") { _, _ -> openPicker(pageUrl) }
        if (t.files.isNotEmpty()) b.setPositiveButton("Other files (${t.files.size})") { _, _ -> showFound(pageUrl, t.files) }
        dlg = b.show()
    }

    /** HLS / DASH → yt-dlp downloads all pieces and joins them into one MP4. */
    private fun startStream(t: Tab, m: MediaSniffer.Media, v: MediaSniffer.Variant?, title: String) {
        val spec = when {
            v == null -> "bv*+ba/b"
            v.height > 0 -> "bv*[height<=${v.height}]+ba/b[height<=${v.height}]/b"
            else -> "best"
        }
        val label = v?.label?.let { " [$it]" } ?: ""
        val item = com.mrmzone.idm.data.DownloadItem(
            url = m.url, fileName = Http.sanitize("$title$label.mp4"), category = Category.VIDEO,
            totalSize = v?.size ?: -1, resumable = true, connections = DM.prefs.maxConnections.coerceAtMost(16),
            referer = m.referer, userAgent = t.ua, cookies = CookieManager.getInstance().getCookie(m.url),
            description = t.web.url, kind = "ytdlp", formatSpec = spec, extra = "mp4",
        )
        DM.add(item, true)
        Toast.makeText(this, "Downloading$label…", Toast.LENGTH_SHORT).show()
        if (DM.prefs.showStartDialog) startActivity(Intent(this, ProgressActivity::class.java).putExtra("id", item.id))
    }

    private fun openPicker(url: String) {
        startActivity(Intent(this, VideoQualityActivity::class.java).putExtra("url", url))
    }

    private fun startAdd(url: String, name: String?, t: Tab, referer: String? = null) {
        val title = t.web.title
        val guessed = name ?: run {
            val n = Http.fileNameFrom(null, url, null)
            if (n.endsWith(".bin") && !title.isNullOrBlank()) Http.sanitize(title) + ".mp4" else n
        }
        startActivity(Intent(this, AddDownloadActivity::class.java)
            .putExtra("url", url).putExtra("name", guessed)
            .putExtra("referer", referer ?: t.web.url).putExtra("ua", t.web.settings.userAgentString)
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
        m.add(0, 9, 0, "Ad blocker…")
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
                    tab.ua = if (tab.desktop)
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
                    else DM.prefs.userAgent
                    tab.web.settings.userAgentString = if (tab.desktop)
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
                    else DM.prefs.userAgent
                    tab.web.settings.useWideViewPort = true
                    tab.web.reload()
                }
                9 -> showShield()
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
