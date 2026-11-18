package com.mrmzone.idm.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun size(b: Long): String {
        if (b < 0) return "Unknown"
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0; var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.2f %s", v, u[i])
    }
    fun speed(b: Long) = size(b) + "/s"
    fun time(sec: Long): String {
        if (sec < 0) return "--"
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return when { h > 0 -> "${h}h ${m}m"; m > 0 -> "${m}m ${s}s"; else -> "${s}s" }
    }
    fun date(t: Long): String = if (t <= 0) "-" else SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(t))
}
