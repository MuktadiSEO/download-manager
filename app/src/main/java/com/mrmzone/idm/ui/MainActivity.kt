package com.mrmzone.idm.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.data.Status
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.FileStore
import com.mrmzone.idm.util.Fmt
import com.mrmzone.idm.util.UrlUtil

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: DownloadAdapter
    private lateinit var tabs: TabLayout
    private lateinit var statusBar: TextView
    private lateinit var empty: View
    private var tab = 0
    private var category: Category? = null
    private var query = ""
    private var sort = 0 // 0 date, 1 name, 2 size, 3 status
    private val listener: () -> Unit = { refresh() }

    private val tabNames = listOf("ALL", "ACTIVE", "COMPLETED", "ERROR", "UNFINISHED", "QUEUE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        statusBar = findViewById(R.id.statusBar)
        empty = findViewById(R.id.empty)

        tabs = findViewById(R.id.tabs)
        tabNames.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { tab = t.position; refresh() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        val chips = findViewById<ChipGroup>(R.id.chips)
        fun addChip(label: String, cat: Category?, checked: Boolean) {
            val c = Chip(this).apply {
                text = label; isCheckable = true; isChecked = checked; id = View.generateViewId()
                setOnCheckedChangeListener { _, on -> if (on) { category = cat; refresh() } }
            }
            chips.addView(c)
        }
        addChip("All types", null, true)
        Category.entries.forEach { addChip(it.label, it, false) }

        adapter = DownloadAdapter(::onItemClick, ::toggleSelect) { d -> onItemAction(d) }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            itemAnimator = null
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { openAdd(null) }
        buildCommandBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.selectionMode) { adapter.selected.clear(); adapter.notifyDataSetChanged(); refresh() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        askPermissions()
        handleTabIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTabIntent(intent)
    }

    private fun handleTabIntent(i: Intent?) {
        val t = i?.getIntExtra("tab", -1) ?: -1
        if (t in tabNames.indices) tabs.getTabAt(t)?.select()
    }

    private fun openBrowser() = startActivity(Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

    override fun onStart() { super.onStart(); DM.listeners.add(listener); refresh() }
    override fun onStop() { DM.listeners.remove(listener); super.onStop() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    // ---------------- command bar (IDM toolbar) ----------------
    private fun buildCommandBar() {
        val bar = findViewById<LinearLayout>(R.id.commandBar)
        fun cmd(icon: Int, label: String, tint: Int = R.color.brand_blue, action: () -> Unit) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_command, bar, false)
            v.findViewById<ImageView>(R.id.icon).apply { setImageResource(icon); imageTintList = ContextCompat.getColorStateList(context, tint) }
            v.findViewById<TextView>(R.id.label).text = label
            v.setOnClickListener { action() }
            bar.addView(v)
        }
        cmd(R.drawable.ic_add, "Add URL", R.color.brand_orange) { openAdd(null) }
        cmd(R.drawable.ic_play, "Resume", R.color.ok) { withSelection { it.forEach { d -> DM.start(d.id) } } }
        cmd(R.drawable.ic_pause, "Stop") { withSelection { it.forEach { d -> DM.pause(d.id) } } }
        cmd(R.drawable.ic_stop, "Stop All", R.color.err) { DM.pauseAll() }
        cmd(R.drawable.ic_delete, "Delete", R.color.err) { withSelection { confirmDelete(it) } }
        cmd(R.drawable.ic_delete_sweep, "Delete Completed") { confirmDeleteCompleted() }
        cmd(R.drawable.ic_settings, "Options") { startActivity(Intent(this, SettingsActivity::class.java)) }
        cmd(R.drawable.ic_schedule, "Scheduler") { startActivity(Intent(this, SchedulerActivity::class.java)) }
        cmd(R.drawable.ic_queue_play, "Start Queue", R.color.ok) { DM.startQueue(); toast("Queue started") }
        cmd(R.drawable.ic_queue_stop, "Stop Queue") { DM.stopQueue(); toast("Queue stopped") }
        cmd(R.drawable.ic_grabber, "Grabber") { startActivity(Intent(this, GrabberActivity::class.java)) }
        cmd(R.drawable.ic_batch, "Batch") { Dialogs.batch(this) }
        cmd(R.drawable.ic_globe, "Browser") { openBrowser() }
        cmd(R.drawable.ic_share, "Tell a Friend") { tellFriend() }
    }

    private fun withSelection(block: (List<DownloadItem>) -> Unit) {
        val sel = DM.snapshot().filter { it.id in adapter.selected }
        if (sel.isEmpty()) { toast("Long-press downloads to select them first"); return }
        block(sel)
        adapter.selected.clear(); adapter.notifyDataSetChanged(); refresh()
    }

    // ---------------- list ----------------
    private fun refresh() {
        var list = DM.snapshot()
        list = when (tab) {
            1 -> list.filter { it.status.isActive }
            2 -> list.filter { it.status == Status.COMPLETED }
            3 -> list.filter { it.status == Status.ERROR }
            4 -> list.filter { it.status != Status.COMPLETED }
            5 -> list.filter { it.inQueue }
            else -> list
        }
        category?.let { c -> list = list.filter { it.category == c } }
        if (query.isNotBlank()) list = list.filter { it.fileName.contains(query, true) || it.url.contains(query, true) }
        list = when (sort) {
            1 -> list.sortedBy { it.fileName.lowercase() }
            2 -> list.sortedByDescending { it.totalSize }
            3 -> list.sortedBy { it.status.ordinal }
            else -> list
        }
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val all = DM.snapshot()
        val active = all.count { it.status == Status.DOWNLOADING || it.status == Status.CONNECTING }
        statusBar.text = if (adapter.selectionMode) "${adapter.selected.size} selected — use Resume / Stop / Delete above (Back to cancel)"
        else "Speed: ${Fmt.speed(DM.totalSpeed())}   •   Active: $active   •   Total: ${all.size}   •   Done: ${all.count { it.status == Status.COMPLETED }}"
    }

    private fun toggleSelect(d: DownloadItem) {
        if (!adapter.selected.add(d.id)) adapter.selected.remove(d.id)
        adapter.notifyDataSetChanged()
        refresh()
    }

    private fun onItemClick(d: DownloadItem) {
        if (adapter.selectionMode) { toggleSelect(d); return }
        if (d.status == Status.COMPLETED) completedMenu(d)
        else startActivity(Intent(this, ProgressActivity::class.java).putExtra("id", d.id))
    }

    private fun onItemAction(d: DownloadItem) {
        when {
            d.status == Status.COMPLETED -> open(d)
            d.status.isActive -> DM.pause(d.id)
            else -> DM.start(d.id)
        }
    }

    private fun open(d: DownloadItem) {
        val i = FileStore.openIntent(this, d)
        if (i == null || !FileStore.exists(this, d.finalPath)) { toast("File not found. It may have been moved or deleted."); return }
        try { startActivity(Intent.createChooser(i, "Open with")) } catch (e: Exception) { toast("No app can open this file") }
    }

    private fun completedMenu(d: DownloadItem) {
        val opts = arrayOf("Open", "Share", "Open folder", "Properties", "Copy link", "Re-download", "Delete from list", "Delete with file")
        MaterialAlertDialogBuilder(this).setTitle(d.fileName).setItems(opts) { _, w ->
            when (w) {
                0 -> open(d)
                1 -> FileStore.shareIntent(this, d)?.let { runCatching { startActivity(Intent.createChooser(it, "Share")) } }
                2 -> openFolder(d)
                3 -> Dialogs.properties(this, d)
                4 -> copy(d.url)
                5 -> DM.redownload(d.id)
                6 -> DM.delete(d.id, false)
                7 -> DM.delete(d.id, true)
            }
        }.show()
    }

    fun openFolder(d: DownloadItem?) {
        try { startActivity(FileStore.folderIntent(d)) } catch (e: Exception) {
            try { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { toast("Files are saved in Download/MRM Zone") }
        }
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", s))
        DM.prefs.lastClip = s
        toast("Link copied")
    }

    private fun confirmDelete(list: List<DownloadItem>) {
        val cb = CheckBox(this).apply { text = "Also delete file(s) from storage"; setPadding(24, 24, 24, 24) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${list.size} download(s)?")
            .setView(cb)
            .setPositiveButton("Delete") { _, _ -> list.forEach { DM.delete(it.id, cb.isChecked) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDeleteCompleted() {
        MaterialAlertDialogBuilder(this).setTitle("Delete completed")
            .setMessage("Remove all completed downloads from the list? Files stay on your phone.")
            .setPositiveButton("Delete") { _, _ -> DM.deleteCompleted() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun tellFriend() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "I use MRM Zone Internet Download Manager — fast multi-connection downloads with resume, scheduler and video grabber!")
        startActivity(Intent.createChooser(i, "Tell a friend"))
    }

    private fun openAdd(url: String?) {
        startActivity(Intent(this, AddDownloadActivity::class.java).apply { if (url != null) putExtra("url", url) })
    }

    // ---------------- clipboard monitoring ----------------
    private fun checkClipboard() {
        if (!DM.prefs.clipboardMonitor) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return
        val url = UrlUtil.extractUrl(text) ?: return
        if (url == DM.prefs.lastClip) return
        DM.prefs.lastClip = url
        if (DM.snapshot().any { it.url == url }) return
        Snackbar.make(findViewById(R.id.list), "Download link from clipboard?\n$url", Snackbar.LENGTH_LONG)
            .setAction("Download") { openAdd(url) }.show()
    }

    // ---------------- menu ----------------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        (menu.findItem(R.id.m_search).actionView as SearchView).setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean { query = q.orEmpty(); refresh(); return true }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_browser -> openBrowser()
            R.id.m_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.m_sort -> MaterialAlertDialogBuilder(this).setTitle("Sort by")
                .setSingleChoiceItems(arrayOf("Date added", "Name", "Size", "Status"), sort) { dlg, w -> sort = w; refresh(); dlg.dismiss() }.show()
            R.id.m_batch -> Dialogs.batch(this)
            R.id.m_grabber -> startActivity(Intent(this, GrabberActivity::class.java))
            R.id.m_scheduler -> startActivity(Intent(this, SchedulerActivity::class.java))
            R.id.m_start_queue -> { DM.startQueue(); toast("Queue started") }
            R.id.m_stop_queue -> { DM.stopQueue(); toast("Queue stopped") }
            R.id.m_resume_all -> DM.resumeAll()
            R.id.m_pause_all -> DM.pauseAll()
            R.id.m_delete_completed -> confirmDeleteCompleted()
            R.id.m_folder -> openFolder(null)
            R.id.m_share -> tellFriend()
            R.id.m_about -> Dialogs.about(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
