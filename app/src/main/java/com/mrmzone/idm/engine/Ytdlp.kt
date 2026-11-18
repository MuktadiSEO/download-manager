package com.mrmzone.idm.engine

import android.content.Context
import com.mrmzone.idm.data.Prefs
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import java.io.File
import java.net.URL

/** Wrapper around yt-dlp (YouTube + 1000 other video sites): info, quality list, download & self-update. */
object Ytdlp {
    @Volatile private var ready = false
    private val lock = Any()

    class Quality(
        val label: String,       // "1080p"
        val detail: String,      // "MP4 • Full HD • 60fps"
        val formatSpec: String,  // yt-dlp -f selector
        val ext: String,         // final file extension
        val extra: String?,      // merge container or "mp3"
        val size: Long,          // estimated bytes, -1 unknown
        val audioOnly: Boolean,
    )

    class Info(val title: String, val thumbnail: String?, val duration: Int, val uploader: String?, val pageUrl: String, val qualities: List<Quality>)

    private val videoHosts = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "facebook.com", "fb.watch", "fb.com", "instagram.com",
        "tiktok.com", "twitter.com", "x.com", "vimeo.com", "dailymotion.com", "dai.ly", "twitch.tv", "reddit.com",
        "redd.it", "soundcloud.com", "bilibili.com", "likee.video", "snapchat.com", "pinterest.com", "pin.it",
        "linkedin.com", "threads.net", "rumble.com", "vk.com", "ok.ru", "streamable.com", "bitchute.com",
    )

    fun isVideoSite(url: String?): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        return videoHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isYouTube(url: String?): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        return host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com")
    }

    /** YouTube page that actually shows a single video (watch / shorts / youtu.be / live). */
    fun isYouTubeVideo(url: String?): Boolean {
        if (!isYouTube(url)) return false
        val u = url!!
        return u.contains("/watch") || u.contains("/shorts/") || u.contains("youtu.be/") || u.contains("/live/") || u.contains("/embed/")
    }

    fun ensureInit(ctx: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            val app = ctx.applicationContext
            val prefs = Prefs(app)
            val vc = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionCode }.getOrDefault(0)
            if (prefs.ytBundleVc != vc) {
                // new app version: replace the installed yt-dlp with the (newer) one bundled in the APK
                File(app.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp").delete()
                prefs.ytBundleVc = vc
            }
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            ready = true
        }
    }

    fun version(ctx: Context): String = runCatching { ensureInit(ctx); YoutubeDL.getInstance().version(ctx.applicationContext) ?: "?" }.getOrDefault("?")

    /** Updates yt-dlp from GitHub. Returns a human readable status. */
    fun update(ctx: Context): String {
        ensureInit(ctx)
        val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel._STABLE)
        Prefs(ctx).ytUpdateCheck = System.currentTimeMillis()
        return when (st) {
            YoutubeDL.UpdateStatus.DONE -> "Updated to ${version(ctx)}"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date (${version(ctx)})"
            else -> "Update finished"
        }
    }

    /** Background: initialise and check for a yt-dlp update at most once a day. */
    fun warmUp(ctx: Context) {
        Thread {
            runCatching { ensureInit(ctx) }
            val p = Prefs(ctx)
            if (System.currentTimeMillis() - p.ytUpdateCheck > 24 * 3600_000L) runCatching { update(ctx) }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun VideoFormat.isVideo() = !vcodec.isNullOrBlank() && vcodec != "none" && height > 0
    private fun VideoFormat.isAudioOnly() = (vcodec.isNullOrBlank() || vcodec == "none") && !acodec.isNullOrBlank() && acodec != "none"
    private fun VideoFormat.hasAudio() = !acodec.isNullOrBlank() && acodec != "none"
    private fun VideoFormat.bytes() = if (fileSize > 0) fileSize else if (fileSizeApproximate > 0) fileSizeApproximate else -1L
    private fun VideoFormat.isStoryboard() = formatId?.startsWith("sb") == true || ext == "mhtml"

    fun fetch(ctx: Context, url: String): Info {
        ensureInit(ctx)
        val req = YoutubeDLRequest(url).addOption("--no-playlist")
        val info = YoutubeDL.getInstance().getInfo(req)
        val formats = info.formats.orEmpty().filter { !it.isStoryboard() && !it.formatId.isNullOrBlank() }
        val audios = formats.filter { it.isAudioOnly() }
        val bestM4a = audios.filter { it.ext == "m4a" }.maxByOrNull { maxOf(it.abr, it.tbr) }
        val bestWebmA = audios.filter { it.ext == "webm" }.maxByOrNull { maxOf(it.abr, it.tbr) }
        val bestAudio = audios.maxByOrNull { maxOf(it.abr, it.tbr) }

        val out = mutableListOf<Quality>()
        val byHeight = formats.filter { it.isVideo() }.groupBy { it.height }.toSortedMap(compareByDescending { it })
        for ((h, list) in byHeight) {
            // <=1080p prefer H.264 MP4 (plays everywhere); above that prefer VP9 WebM, then AV1/MP4
            fun score(f: VideoFormat): Int {
                val v = f.vcodec.orEmpty()
                val codec = if (h <= 1080) when {
                    v.startsWith("avc") -> 3; v.startsWith("vp9") || v.startsWith("vp09") -> 2; else -> 1
                } else when {
                    v.startsWith("vp9") || v.startsWith("vp09") -> 3; v.startsWith("av01") -> 2; else -> 1
                }
                return codec * 1_000_000 + f.fps * 10_000 + f.tbr
            }
            val maxFps = list.maxOf { it.fps }
            val best = list.filter { it.fps == maxFps || maxFps <= 30 }.maxByOrNull { score(it) } ?: continue
            val fpsTxt = if (best.fps > 30) "${best.fps}" else ""
            val name = when {
                h >= 4320 -> "8K"; h >= 2160 -> "4K"; h >= 1440 -> "2K"; h >= 1080 -> "Full HD"; h >= 720 -> "HD"; else -> "SD"
            }
            val codec = best.vcodec.orEmpty().let { when { it.startsWith("avc") -> "H.264"; it.startsWith("vp") -> "VP9"; it.startsWith("av01") -> "AV1"; else -> it.substringBefore('.').uppercase() } }
            if (best.hasAudio()) {
                out += Quality("${h}p$fpsTxt", "${best.ext?.uppercase()} • $name • $codec", best.formatId!!, best.ext ?: "mp4", null, best.bytes(), false)
            } else {
                val (aud, merge) = when {
                    best.ext == "mp4" && bestM4a != null -> bestM4a to "mp4"
                    best.ext == "webm" && bestWebmA != null -> bestWebmA to "webm"
                    else -> bestAudio to "mkv"
                }
                val spec = if (aud != null) "${best.formatId}+${aud.formatId}" else "${best.formatId}+bestaudio/best"
                val size = if (best.bytes() > 0) best.bytes() + (aud?.bytes()?.coerceAtLeast(0) ?: 0) else -1
                out += Quality("${h}p$fpsTxt", "${merge.uppercase()} • $name • $codec", spec, merge, merge, size, false)
            }
        }
        if (bestM4a != null) out += Quality("Audio", "M4A • ${maxOf(bestM4a.abr, bestM4a.tbr)} kbps", bestM4a.formatId!!, "m4a", null, bestM4a.bytes(), true)
        if (bestAudio != null) out += Quality("MP3", "MP3 • converted from best audio", bestAudio.formatId!!, "mp3", "mp3", bestAudio.bytes(), true)
        if (out.isEmpty()) {
            // sites without a detailed format list: let yt-dlp pick the best
            out += Quality("Best", "Best available quality", "bv*+ba/b", info.ext ?: "mp4", "mp4", info.fileSize.takeIf { it > 0 } ?: -1, false)
            out += Quality("MP3", "Audio only (MP3)", "ba/b", "mp3", "mp3", -1, true)
        }
        return Info(
            title = info.title ?: info.fulltitle ?: "video",
            thumbnail = info.thumbnail,
            duration = info.duration,
            uploader = info.uploader,
            pageUrl = info.webpageUrl ?: url,
            qualities = out,
        )
    }

    private val sizeRe = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]i?B)""")
    private val speedRe = Regex("""at\s+([\d.]+)\s*([KMGT]i?B)/s""")

    private fun toBytes(v: String, unit: String): Long {
        val n = v.toDoubleOrNull() ?: return -1
        val mul = when (unit.uppercase().replace("I", "")) { "KB" -> 1024.0; "MB" -> 1048576.0; "GB" -> 1073741824.0; "TB" -> 1099511627776.0; else -> 1.0 }
        return (n * mul).toLong()
    }

    class Progress { var phaseBase = 0L; var phaseSize = 0L; var lastDest = "" }

    /** Parses one yt-dlp output line into item progress. Returns true if merging/converting started. */
    fun parseLine(line: String, pct: Float, p: Progress, onBytes: (downloaded: Long, total: Long, speed: Long) -> Unit): Boolean {
        if (line.contains("Destination:") && line.startsWith("[download]")) {
            val dest = line.substringAfter("Destination:").trim()
            if (dest != p.lastDest) { p.phaseBase += p.phaseSize; p.phaseSize = 0; p.lastDest = dest }
        }
        sizeRe.find(line)?.let { p.phaseSize = toBytes(it.groupValues[1], it.groupValues[2]) }
        val speed = speedRe.find(line)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: -1
        if (p.phaseSize > 0 && pct >= 0) {
            val done = p.phaseBase + (p.phaseSize * (pct / 100f)).toLong()
            onBytes(done, -1, speed)
        }
        return line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") || line.startsWith("[VideoConvertor]") || line.startsWith("[FixupM")
    }
}
