package com.mrmzone.idm.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mrmzone.idm.data.DownloadDb
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.data.Prefs
import com.mrmzone.idm.data.Status
import com.mrmzone.idm.service.DownloadService
import com.mrmzone.idm.service.Notifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Central download manager: queue, concurrency, persistence and listeners. */
object DM {
    lateinit var app: Context; private set
    lateinit var db: DownloadDb; private set
    lateinit var prefs: Prefs; private set
    private val items = mutableListOf<DownloadItem>()
    private val tasks = ConcurrentHashMap<Long, DownloadTask>()
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    val completeListeners = CopyOnWriteArrayList<(DownloadItem) -> Unit>()
    private var lastNotify = 0L
    private var pendingNotify = false

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        db = DownloadDb(app)
        prefs = Prefs(app)
        SpeedLimiter.global.limitBytesPerSec = prefs.speedLimitKb * 1024L
        synchronized(items) {
            items += db.all()
            // downloads interrupted by process death
            for (d in items) if (d.status.isActive) { d.status = if (prefs.autoResume) Status.WAITING else Status.PAUSED; db.update(d) }
        }
        schedule()
    }

    fun snapshot(): List<DownloadItem> = synchronized(items) { items.toList() }
    fun get(id: Long): DownloadItem? = synchronized(items) { items.firstOrNull { it.id == id } }
    fun runningCount() = tasks.size
    fun task(id: Long) = tasks[id]

    fun totalSpeed() = tasks.values.sumOf { it.item.speed }

    fun add(item: DownloadItem, startNow: Boolean): DownloadItem {
        item.status = if (startNow) Status.WAITING else if (item.inQueue) Status.QUEUED else Status.PAUSED
        db.insert(item)
        synchronized(items) { items.add(0, item) }
        if (startNow) schedule()
        notifyChanged(true)
        return item
    }

    fun start(id: Long) {
        val d = get(id) ?: return
        if (d.status == Status.COMPLETED || tasks.containsKey(id)) return
        d.status = Status.WAITING; d.error = null
        db.update(d)
        schedule()
        notifyChanged(true)
    }

    fun pause(id: Long) {
        val t = tasks[id]
        if (t != null) { t.cancel(); return }
        val d = get(id) ?: return
        if (d.status == Status.WAITING || d.status == Status.QUEUED) {
            d.status = Status.PAUSED; db.update(d); notifyChanged(true)
        }
    }

    fun pauseAll() {
        snapshot().forEach { if (it.status == Status.WAITING) { it.status = Status.PAUSED; db.update(it) } }
        tasks.values.forEach { it.cancel() }
        notifyChanged(true)
    }

    fun resumeAll() { snapshot().filter { it.status == Status.PAUSED || it.status == Status.ERROR }.forEach { start(it.id) } }

    fun startQueue() {
        val q = snapshot().filter { it.inQueue && it.status != Status.COMPLETED }
        q.sortedBy { it.createdAt }.forEach { start(it.id) }
    }

    fun stopQueue() { snapshot().filter { it.inQueue }.forEach { pause(it.id) } }

    fun setInQueue(id: Long, q: Boolean) {
        val d = get(id) ?: return
        d.inQueue = q
        if (q && d.status == Status.PAUSED) d.status = Status.QUEUED
        if (!q && d.status == Status.QUEUED) d.status = Status.PAUSED
        db.update(d); notifyChanged(true)
    }

    fun delete(id: Long, withFile: Boolean) {
        val d = get(id) ?: return
        tasks[id]?.cancel()
        Thread {
            tasks[id]?.join(4000)
            FileStore.tempFile(app, d).delete()
            if (withFile) FileStore.delete(app, d.finalPath)
        }.start()
        db.delete(id)
        synchronized(items) { items.removeAll { it.id == id } }
        Notifier.cancel(app, id)
        notifyChanged(true)
    }

    fun deleteCompleted() { snapshot().filter { it.status == Status.COMPLETED }.forEach { delete(it.id, false) } }

    fun redownload(id: Long) {
        val d = get(id) ?: return
        tasks[id]?.cancel()
        FileStore.tempFile(app, d).delete()
        d.segments = null; d.downloaded = 0; d.finalPath = null; d.finishedAt = 0
        d.status = Status.PAUSED
        db.update(d)
        start(id)
    }

    fun update(d: DownloadItem) { db.update(d); notifyChanged(true) }

    fun setGlobalLimit(kb: Int) { prefs.speedLimitKb = kb; SpeedLimiter.global.limitBytesPerSec = kb * 1024L }

    fun setItemLimit(id: Long, kb: Int) {
        val d = get(id) ?: return
        d.speedLimitKb = kb
        tasks[id]?.limiter?.limitBytesPerSec = kb * 1024L
        db.update(d)
    }

    @Synchronized fun schedule() {
        val max = prefs.maxSimultaneous.coerceAtLeast(1)
        val waiting = snapshot().filter { it.status == Status.WAITING }.sortedBy { it.createdAt }
        if (waiting.isEmpty()) return
        if (prefs.wifiOnly && !onWifi()) {
            waiting.forEach { it.error = "Waiting for Wi-Fi" }
            notifyChanged(true); return
        }
        for (d in waiting) {
            if (tasks.size >= max) break
            if (tasks.containsKey(d.id)) continue
            val t = DownloadTask(app, d, prefs.retries, { notifyChanged(false) }, ::onTaskFinished) { db.update(it) }
            tasks[d.id] = t
            t.start()
        }
        DownloadService.ensureRunning(app)
    }

    private fun onTaskFinished(d: DownloadItem) {
        tasks.remove(d.id)
        db.update(d)
        main.post {
            if (d.status == Status.COMPLETED) {
                Notifier.completed(app, d)
                completeListeners.forEach { it(d) }
            } else if (d.status == Status.ERROR) Notifier.failed(app, d)
            schedule()
            notifyChanged(true)
        }
    }

    fun onWifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Throttled UI notifications (max ~3/sec) unless forced. */
    fun notifyChanged(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastNotify < 350) {
            if (!pendingNotify) { pendingNotify = true; main.postDelayed({ pendingNotify = false; notifyChanged(true) }, 350) }
            return
        }
        lastNotify = now
        main.post { listeners.forEach { it() } }
    }
}
