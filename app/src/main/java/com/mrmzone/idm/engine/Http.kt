package com.mrmzone.idm.engine

import android.webkit.URLUtil
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.data.Prefs
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class ProbeResult(
    val finalUrl: String,
    val size: Long,
    val resumable: Boolean,
    val fileName: String,
    val contentType: String?,
)

object Http {
    const val TIMEOUT = 20_000

    /** Opens a connection following redirects (including http<->https). end < 0 means open-ended range. */
    fun open(
        url: String, ua: String?, referer: String?, cookies: String?,
        rangeStart: Long = -1, rangeEnd: Long = -1, method: String = "GET",
    ): HttpURLConnection {
        var current = url
        repeat(10) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = TIMEOUT
            c.readTimeout = TIMEOUT
            c.requestMethod = method
            c.setRequestProperty("User-Agent", ua ?: Prefs.DEFAULT_UA)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
            if (!cookies.isNullOrBlank()) c.setRequestProperty("Cookie", cookies)
            if (rangeStart >= 0) c.setRequestProperty("Range", "bytes=$rangeStart-" + (if (rangeEnd >= 0) rangeEnd.toString() else ""))
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                current = URL(URL(current), loc).toString()
                c.disconnect()
                return@repeat
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    fun open(item: DownloadItem, start: Long, end: Long) =
        open(item.url, item.userAgent, item.referer, item.cookies, start, end)

    fun probe(url: String, ua: String?, referer: String?, cookies: String?): ProbeResult {
        val c = open(url, ua, referer, cookies, 0, -1)
        try {
            val code = c.responseCode
            if (code >= 400) throw IOException("HTTP $code ${c.responseMessage ?: ""}".trim())
            val finalUrl = c.url.toString()
            val type = c.contentType
            var size = -1L
            var resumable = false
            if (code == 206) {
                val cr = c.getHeaderField("Content-Range") // bytes 0-0/1234
                size = cr?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1
                resumable = size > 0
            } else {
                size = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
                resumable = c.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true && size > 0
            }
            val name = fileNameFrom(c.getHeaderField("Content-Disposition"), finalUrl, type)
            return ProbeResult(finalUrl, size, resumable, name, type)
        } finally {
            c.disconnect()
        }
    }

    fun fileNameFrom(disposition: String?, url: String, mime: String?): String {
        var name: String? = null
        if (disposition != null) {
            Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(disposition)?.let {
                name = runCatching { URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrNull()
            }
            if (name == null) Regex("filename=\"?([^\";]+)\"?").find(disposition)?.let { name = it.groupValues[1] }
        }
        if (name.isNullOrBlank()) {
            val seg = runCatching { URLDecoder.decode(URL(url).path.substringAfterLast('/'), "UTF-8") }.getOrNull()
            if (!seg.isNullOrBlank() && seg.contains('.') && seg.substringAfterLast('.').length in 1..5) name = seg
            // "files-5GB-zip" → "files-5GB.zip"
            if (name.isNullOrBlank() && !seg.isNullOrBlank())
                Regex("^(.+)[-_](zip|rar|7z|pdf|mp4|mkv|mp3|apk|exe|iso|tar|gz|jpg|png|docx?|xlsx?)$", RegexOption.IGNORE_CASE).find(seg)?.let { m ->
                    name = m.groupValues[1] + "." + m.groupValues[2].lowercase()
                }
        }
        if (name.isNullOrBlank()) name = URLUtil.guessFileName(url, disposition, mime)
        return sanitize(name ?: "download.bin")
    }

    fun sanitize(n: String): String {
        val s = n.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('.')
        return s.ifBlank { "download.bin" }.take(200)
    }
}
