package com.mrmzone.idm.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mrmzone.idm.R
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.engine.FileStore
import com.mrmzone.idm.ui.MainActivity
import com.mrmzone.idm.util.Fmt

object Notifier {
    const val CH_PROGRESS = "progress"
    const val CH_DONE = "done"
    const val FG_ID = 1

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Active downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun can(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun mainPi(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    fun completed(ctx: Context, d: DownloadItem) {
        if (!can(ctx)) return
        val open = FileStore.openIntent(ctx, d)
        val pi = if (open != null) PendingIntent.getActivity(ctx, d.id.toInt(), Intent.createChooser(open, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE) else mainPi(ctx)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle("Download complete")
            .setContentText(d.fileName + " • " + Fmt.size(d.totalSize))
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1000 + d.id.toInt(), n) }
    }

    fun failed(ctx: Context, d: DownloadItem) {
        if (!can(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download failed")
            .setContentText(d.fileName + ": " + (d.error ?: "error"))
            .setContentIntent(mainPi(ctx)).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1000 + d.id.toInt(), n) }
    }

    fun cancel(ctx: Context, id: Long) = NotificationManagerCompat.from(ctx).cancel(1000 + id.toInt())

    fun progress(ctx: Context, active: List<DownloadItem>, speed: Long): android.app.Notification {
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(mainPi(ctx))
        val pauseAll = PendingIntent.getService(ctx, 1, Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL), PendingIntent.FLAG_IMMUTABLE)
        b.addAction(0, "Pause all", pauseAll)
        if (active.size == 1) {
            val d = active[0]
            b.setContentTitle(d.fileName)
            b.setContentText("${d.progress}% • ${Fmt.size(d.downloaded)} / ${Fmt.size(d.totalSize)} • ${Fmt.speed(d.speed)}")
            if (d.totalSize > 0) b.setProgress(100, d.progress, false) else b.setProgress(0, 0, true)
        } else {
            b.setContentTitle("Downloading ${active.size} files")
            b.setContentText(Fmt.speed(speed))
            val total = active.sumOf { it.totalSize.coerceAtLeast(0) }
            val done = active.sumOf { it.downloaded }
            if (total > 0) b.setProgress(100, (done * 100 / total).toInt(), false) else b.setProgress(0, 0, true)
            val style = NotificationCompat.InboxStyle()
            active.take(6).forEach { style.addLine("${it.progress}%  ${it.fileName}") }
            b.setStyle(style)
        }
        return b.build()
    }
}
