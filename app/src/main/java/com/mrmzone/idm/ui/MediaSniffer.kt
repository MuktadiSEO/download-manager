package com.mrmzone.idm.ui

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import com.mrmzone.idm.engine.AdBlock
import com.mrmzone.idm.engine.Http
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Finds videos playing on any web page (like IDM's "Download this video" panel):
 * direct files (.mp4 …), HLS streams (.m3u8 with all qualities), DASH (.mpd) and
 * unlabelled media requests (detected by the player's Range requests + content type).
 * Works for players embedded from other sites (iframes) too, because every request is seen.
 */
class MediaSniffer(private val ua: () -> String, private val onChange: () -> Unit) {

    enum class Kind { FILE, HLS, DASH }

    class Variant(val height: Int, val bandwidth: Long, val url: String) {
        var size = -1L
        val label get() = if (height > 0) "${height}p" else if (bandwidth > 0) "${bandwidth / 1000} kbps" else "Default"
    }

    class Media(val url: String, val referer: String?, var kind: Kind) {
        @Volatile var ok: Boolean? = null          // null = still checking
        @Volatile var size = -1L
        @Volatile var type: String? = null
        @Volatile var duration = 0.0
        @Volatile var variants: List<Variant> = emptyList()
        val time = System.currentTimeMillis()
    }

    val items = ConcurrentHashMap<String, Media>()
    private val variantUrls = ConcurrentHashMap.newKeySet<String>()
    private val exec = Executors.newFixedThreadPool(2)

    fun clear() { items.clear(); variantUrls.clear() }

    /** Ready media, newest first, without the sub-playlists of a master playlist. */
    fun ready(): List<Media> = items.values.filter { it.ok == true && it.url !in variantUrls }.sortedByDescending { it.time }

    fun checking() = items.values.count { it.ok == null }

    private val segmentExts = setOf("ts", "m4s", "m4a", "aac", "key", "vtt", "srt", "webvtt", "cmfv", "cmfa")
    private val staticExts = setOf("js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2", "ttf", "otf", "json", "html", "htm", "php", "txt", "xml")
    private val fileExts = setOf("mp4", "webm", "mkv", "mov", "flv", "avi", "3gp", "m4v", "wmv", "mpg", "mpeg", "ogv", "mp3", "wav", "flac", "ogg", "opus")

    /** Called from WebViewClient.shouldInterceptRequest (background thread). */
    fun onRequest(req: WebResourceRequest, pageUrl: String?) {
        val u = req.url.toString()
        if (!u.startsWith("http") || items.containsKey(u) || items.size > 60) return
        val lower = u.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        val hasRange = req.requestHeaders.keys.any { it.equals("Range", true) }
        val kind = when {
            ext == "m3u8" || path.endsWith("/m3u8") || (lower.contains(".m3u8") && ext !in segmentExts) -> Kind.HLS
            ext == "mpd" -> Kind.DASH
            ext in segmentExts || ext in staticExts -> return
            ext in fileExts -> Kind.FILE
            hasRange && !req.isForMainFrame -> Kind.FILE        // media element streaming an unlabelled URL
            lower.contains("mime=video") || lower.contains("/video/") && lower.contains("token") -> Kind.FILE
            else -> return
        }
        if (AdBlock.isAd(u)) return
        val referer = req.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: pageUrl
        val m = Media(u, referer, kind)
        if (items.putIfAbsent(u, m) != null) return
        exec.execute { runCatching { probe(m) }.onFailure { m.ok = false }; onChange() }
    }

    /** Adds a media URL found in the page DOM (<video src>). */
    fun addFromDom(u: String, pageUrl: String?) {
        if (!u.startsWith("http") || items.containsKey(u)) return
        val kind = if (u.contains(".m3u8")) Kind.HLS else if (u.lowercase().substringBefore('?').endsWith(".mpd")) Kind.DASH else Kind.FILE
        val m = Media(u, pageUrl, kind)
        if (items.putIfAbsent(u, m) != null) return
        exec.execute { runCatching { probe(m) }.onFailure { m.ok = false }; onChange() }
    }

    private fun open(url: String, referer: String?, range: Boolean): HttpURLConnection =
        Http.open(url, ua(), referer, CookieManager.getInstance().getCookie(url), if (range) 0 else -1, if (range) 0 else -1)

    private fun readText(url: String, referer: String?): Pair<String, String> {
        val c = open(url, referer, false)
        try {
            if (c.responseCode >= 400) throw java.io.IOException("HTTP ${c.responseCode}")
            val txt = c.inputStream.use { it.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8) }
            return c.url.toString() to txt
        } finally { c.disconnect() }
    }

    private fun probe(m: Media) {
        when (m.kind) {
            Kind.HLS -> probeHls(m)
            Kind.DASH -> { m.ok = true }
            Kind.FILE -> {
                val c = open(m.url, m.referer, true)
                try {
                    val code = c.responseCode
                    val type = c.contentType?.lowercase().orEmpty()
                    val size = if (code == 206) c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
                               else c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
                    m.type = type; m.size = size
                    when {
                        code >= 400 -> m.ok = false
                        type.contains("mpegurl") -> { m.kind = Kind.HLS; c.disconnect(); probeHls(m) }
                        type.contains("dash+xml") -> { m.kind = Kind.DASH; m.ok = true }
                        type.startsWith("video/") || type.startsWith("audio/") -> m.ok = size < 0 || size > 300_000
                        type.contains("octet-stream") || type.isEmpty() -> m.ok = size > 3_000_000
                        else -> m.ok = false
                    }
                } finally { c.disconnect() }
            }
        }
    }

    private fun attrs(line: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(line.substringAfter(':')).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim('"')
        }
        return out
    }

    private fun mediaDuration(text: String) = Regex("""#EXTINF:([\d.]+)""").findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }

    private fun probeHls(m: Media) {
        val (base, text) = readText(m.url, m.referer)
        if (!text.trimStart().startsWith("#EXTM3U")) { m.ok = false; return }
        val lines = text.lines()
        val vars = mutableListOf<Variant>()
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val a = attrs(l)
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
            val abs = runCatching { URL(URL(base), uri).toString() }.getOrNull() ?: continue
            val h = a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0
            val bw = (a["AVERAGE-BANDWIDTH"] ?: a["BANDWIDTH"])?.toLongOrNull() ?: 0
            vars += Variant(h, bw, abs)
            variantUrls += abs
        }
        if (vars.isEmpty()) {
            // a single-quality media playlist
            m.duration = mediaDuration(text)
            m.ok = m.duration > 20 || text.contains("#EXT-X-ENDLIST").not()
            return
        }
        // one variant playlist gives the duration → size estimate for every quality
        m.duration = runCatching { mediaDuration(readText(vars.first().url, m.referer).second) }.getOrDefault(0.0)
        val best = vars.groupBy { it.height }.map { (_, v) -> v.maxBy { it.bandwidth } }.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })
        if (m.duration > 0) best.forEach { v -> if (v.bandwidth > 0) v.size = (v.bandwidth / 8.0 * m.duration).toLong() }
        m.variants = best
        m.ok = true
    }
}
