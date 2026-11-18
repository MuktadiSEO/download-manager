package com.mrmzone.idm.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mrmzone.idm.data.Status
import com.mrmzone.idm.engine.DM

/** Keeps the process alive while downloads run and shows the ongoing notification. */
class DownloadService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val tick = object : Runnable {
        override fun run() {
            val active = DM.snapshot().filter { it.status == Status.DOWNLOADING || it.status == Status.CONNECTING || it.status == Status.MOVING }
            if (DM.runningCount() == 0) { stopSelfNow(); return }
            runCatching { NotificationManagerCompat.from(this@DownloadService).notify(Notifier.FG_ID, Notifier.progress(this@DownloadService, active, DM.totalSpeed())) }
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DM.init(this)
        val n = Notifier.progress(this, emptyList(), 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notifier.FG_ID, n)
        runCatching {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mrmidm:dl").apply { acquire(6 * 60 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mrmidm:wifi").apply { acquire() }
        }
        h.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> DM.pauseAll()
            ACTION_START_QUEUE -> DM.startQueue()
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        h.removeCallbacks(tick)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "pause_all"
        const val ACTION_START_QUEUE = "start_queue"
        fun ensureRunning(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }
        }
    }
}
