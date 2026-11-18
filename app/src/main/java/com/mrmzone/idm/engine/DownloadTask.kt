package com.mrmzone.idm.engine

import android.content.Context
import com.mrmzone.idm.data.DownloadItem
import com.mrmzone.idm.data.Segment
import com.mrmzone.idm.data.Status
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * IDM-style multi-connection downloader with dynamic file segmentation:
 * the file is split into N parts, each downloaded on its own connection; when one
 * connection finishes, the largest remaining part is split in half and a new
 * connection takes over the second half.
 */
class DownloadTask(
    private val ctx: Context,
    val item: DownloadItem,
    private val maxRetries: Int,
    private val onUpdate: (DownloadItem) -> Unit,
    private val onFinished: (DownloadItem) -> Unit,
    private val onSave: (DownloadItem) -> Unit = {},
) : Thread("dl-${item.id}") {

    @Volatile var cancelled = false; private set
    @Volatile private var fatal: String? = null
    private val segs = mutableListOf<Segment>()
    private val workers = ConcurrentHashMap<Segment, Worker>()
    private lateinit var channel: FileChannel
    private lateinit var raf: RandomAccessFile
    val limiter = SpeedLimiter(item.speedLimitKb * 1024L)

    fun cancel() {
        cancelled = true
        workers.values.forEach { it.abort() }
        if (item.isYtdlp) runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        interrupt()
    }

    private val processId get() = "mrm-${item.id}"

    override fun run() {
        if (item.isYtdlp) { runYtdlp(); return }
        try {
            item.lastTry = System.currentTimeMillis()
            item.error = null
            item.status = Status.CONNECTING
            onUpdate(item)
            prepare()
            if (cancelled) return finish(Status.PAUSED)
            item.status = Status.DOWNLOADING
            item.segments = synchronized(segs) { segs.joinToString(";") }
            onSave(item)
            onUpdate(item)
            loop()
            closeFile()
            when {
                fatal != null -> { item.error = fatal; finish(Status.ERROR) }
                cancelled -> finish(Status.PAUSED)
                else -> {
                    item.downloaded = segs.sumOf { it.done }
                    if (item.totalSize <= 0) item.totalSize = item.downloaded
                    item.status = Status.MOVING; onUpdate(item)
                    val temp = FileStore.tempFile(ctx, item)
                    item.finalPath = FileStore.publish(ctx, temp, item)
                    item.segments = null
                    item.finishedAt = System.currentTimeMillis()
                    finish(Status.COMPLETED)
                }
            }
        } catch (e: Exception) {
            closeFile()
            if (cancelled) finish(Status.PAUSED)
            else { item.error = e.message ?: e.javaClass.simpleName; finish(Status.ERROR) }
        }
    }

    /** Video sites (YouTube etc.): yt-dlp downloads the chosen quality, ffmpeg merges video+audio. */
    private fun runYtdlp() {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "yt/${item.id}").apply { mkdirs() }
        try {
            item.lastTry = System.currentTimeMillis()
            item.error = null
            item.status = Status.CONNECTING
            onUpdate(item)
            Ytdlp.ensureInit(ctx)
            if (cancelled) return finish(Status.PAUSED)
            val req = YoutubeDLRequest(item.url)
                .addOption("--no-playlist")
                .addOption("-f", item.formatSpec ?: "bv*+ba/b")
                .addOption("-o", File(dir, "media.%(ext)s").absolutePath)
                .addOption("--no-mtime")
                .addOption("--continue")
                .addOption("--newline")
                .addOption("--http-chunk-size", "10M")
                .addOption("-N", item.connections.coerceIn(1, 16))
                .addOption("--retries", DM.prefs.retries.coerceAtLeast(1))
                .addOption("--fragment-retries", DM.prefs.retries.coerceAtLeast(1))
            when (val ex = item.extra) {
                "mp3" -> { req.addOption("-x"); req.addOption("--audio-format", "mp3"); req.addOption("--audio-quality", "0") }
                "mp4", "webm", "mkv" -> req.addOption("--merge-output-format", ex)
            }
            val limitKb = listOf(item.speedLimitKb, DM.prefs.speedLimitKb).filter { it > 0 }.minOrNull()
            if (limitKb != null) req.addOption("-r", "${limitKb}K")
            if (!item.userAgent.isNullOrBlank()) req.addOption("--user-agent", item.userAgent!!)

            val prog = Ytdlp.Progress()
            var lastEmit = 0L
            item.status = Status.DOWNLOADING
            onUpdate(item)
            YoutubeDL.getInstance().execute(req, processId) { pct, _, line ->
                val merging = Ytdlp.parseLine(line, pct, prog) { done, _, speed ->
                    item.downloaded = if (item.totalSize > 0) minOf(done, item.totalSize) else done
                    if (speed >= 0) item.speed = speed
                }
                if (merging && item.status != Status.MOVING) { item.status = Status.MOVING; item.speed = 0; onUpdate(item) }
                val now = System.currentTimeMillis()
                if (now - lastEmit > 400) { lastEmit = now; item.activeConnections = 1; onUpdate(item) }
            }
            if (cancelled) return finish(Status.PAUSED)
            // pick the finished media file
            val outFile = dir.listFiles().orEmpty()
                .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".part-") && !it.name.endsWith(".temp") }
                .maxByOrNull { it.length() } ?: throw java.io.IOException("Downloaded file not found")
            val ext = outFile.extension.ifBlank { "mp4" }
            val base = item.fileName.substringBeforeLast('.', item.fileName)
            item.fileName = Http.sanitize("$base.$ext")
            item.totalSize = outFile.length()
            item.downloaded = outFile.length()
            item.status = Status.MOVING; onUpdate(item)
            val temp = FileStore.tempFile(ctx, item)
            temp.delete()
            if (!outFile.renameTo(temp)) { outFile.copyTo(temp, true); outFile.delete() }
            item.finalPath = FileStore.publish(ctx, temp, item)
            dir.deleteRecursively()
            item.finishedAt = System.currentTimeMillis()
            finish(Status.COMPLETED)
        } catch (e: YoutubeDL.CanceledException) {
            finish(Status.PAUSED)
        } catch (e: Exception) {
            if (cancelled) finish(Status.PAUSED)
            else {
                val msg = (e.message ?: e.javaClass.simpleName).lines().lastOrNull { it.contains("ERROR") } ?: e.message?.lines()?.lastOrNull { it.isNotBlank() }
                item.error = (msg ?: "yt-dlp error").removePrefix("ERROR:").trim().take(300)
                finish(Status.ERROR)
            }
        }
    }

    private fun finish(s: Status) {
        item.status = s
        item.speed = 0
        item.activeConnections = 0
        if (s != Status.COMPLETED && !item.isYtdlp) item.segments = synchronized(segs) { segs.joinToString(";") }
        onFinished(item)
    }

    private fun prepare() {
        val temp = FileStore.tempFile(ctx, item)
        segs.clear()
        segs += Segment.parseList(item.segments)
        val haveProgress = segs.isNotEmpty() && temp.exists() && item.resumable && item.totalSize > 0
        if (!haveProgress) {
            var lastErr: Exception? = null
            var probe: ProbeResult? = null
            for (i in 0..3) {
                if (cancelled) return
                try { probe = Http.probe(item.url, item.userAgent, item.referer, item.cookies); break }
                catch (e: Exception) { lastErr = e; sleepQuiet(1500L * (i + 1)) }
            }
            val p = probe ?: throw lastErr ?: IOException("Cannot connect")
            item.totalSize = p.size
            item.resumable = p.resumable
            segs.clear()
            temp.delete()
            if (p.resumable && p.size > 0) {
                val n = item.connections.coerceIn(1, 32).toLong()
                val part = p.size / n
                for (i in 0 until n) {
                    val s = i * part
                    val e = if (i == n - 1) p.size - 1 else s + part - 1
                    if (e >= s) segs += Segment(s, e)
                }
            } else {
                segs += Segment(0, if (p.size > 0) p.size - 1 else Segment.UNKNOWN)
            }
        }
        temp.parentFile?.mkdirs()
        raf = RandomAccessFile(temp, "rw")
        if (item.totalSize > 0 && raf.length() < item.totalSize) runCatching { raf.setLength(item.totalSize) }
        channel = raf.channel
        item.downloaded = segs.sumOf { it.done }
        item.liveSegments = segs.toList()
    }

    private fun closeFile() {
        runCatching { if (::channel.isInitialized) channel.close() }
        runCatching { if (::raf.isInitialized) raf.close() }
    }

    private fun loop() {
        var lastBytes = item.downloaded
        var lastTime = System.currentTimeMillis()
        var lastSave = lastTime
        var ema = 0.0
        while (!cancelled && fatal == null) {
            synchronized(segs) {
                // launch workers for idle unfinished segments
                for (s in segs) if (!s.finished && workers[s] == null && workers.size < item.connections.coerceAtLeast(1)) startWorker(s)
                // dynamic segmentation: split largest running part when a connection slot is free
                if (item.resumable && workers.size < item.connections) {
                    val victim = workers.keys.filter { it.remaining > MIN_SPLIT * 2 }.maxByOrNull { it.remaining }
                    if (victim != null) {
                        var ns: Segment? = null
                        synchronized(victim) {
                            val rem = victim.remaining
                            if (rem > MIN_SPLIT * 2) {
                                val mid = victim.position + rem / 2
                                ns = Segment(mid, victim.end)
                                victim.end = mid - 1
                            }
                        }
                        ns?.let { segs += it; segs.sortBy { s -> s.start }; startWorker(it) }
                    }
                }
                if (segs.all { it.finished } && workers.isEmpty()) {
                    item.downloaded = segs.sumOf { it.done }
                    return
                }
            }
            sleepQuiet(400)
            val now = System.currentTimeMillis()
            val bytes = segs.sumOf { it.done }
            val dt = (now - lastTime).coerceAtLeast(1)
            val inst = (bytes - lastBytes) * 1000.0 / dt
            ema = if (ema == 0.0) inst else ema * 0.7 + inst * 0.3
            lastBytes = bytes; lastTime = now
            item.downloaded = bytes
            item.speed = ema.toLong()
            item.activeConnections = workers.size
            synchronized(segs) { item.liveSegments = segs.map { Segment(it.start, it.end, it.done) } }
            if (now - lastSave > 3000) {
                item.segments = synchronized(segs) { segs.joinToString(";") }
                runCatching { onSave(item) }
                lastSave = now
            }
            onUpdate(item)
        }
        workers.values.forEach { it.abort() }
        workers.values.forEach { runCatching { it.join(3000) } }
    }

    private fun startWorker(s: Segment) {
        val w = Worker(s)
        workers[s] = w
        w.start()
    }

    private inner class Worker(val seg: Segment) : Thread("dl-${item.id}-w") {
        @Volatile private var conn: HttpURLConnection? = null
        @Volatile private var input: InputStream? = null
        fun abort() { runCatching { input?.close() }; runCatching { conn?.disconnect() } }

        override fun run() {
            var attempts = 0
            val buf = ByteArray(64 * 1024)
            try {
                while (!cancelled && fatal == null && !seg.finished) {
                    try {
                        if (!item.resumable && seg.done > 0) { seg.done = 0 } // must restart from zero
                        val endParam = if (seg.end == Segment.UNKNOWN) -1 else seg.end
                        val c = Http.open(item, if (item.resumable || seg.position > 0) seg.position else -1, endParam)
                        conn = c
                        val code = c.responseCode
                        if (code >= 400) {
                            if (code == 416 && seg.finished) break
                            throw HttpError(code, "HTTP $code ${c.responseMessage ?: ""}".trim())
                        }
                        if (code == 200 && seg.position > 0) {
                            if (item.resumable) throw HttpError(-1, "Server does not support resume any more")
                        }
                        val ins = c.inputStream
                        input = ins
                        var stop = false
                        while (!stop && !cancelled) {
                            val n = ins.read(buf)
                            if (n < 0) {
                                if (seg.end == Segment.UNKNOWN) synchronized(seg) { seg.end = seg.position - 1 }
                                else if (!seg.finished) throw IOException("Connection closed early")
                                break
                            }
                            synchronized(seg) {
                                val w = minOf(n.toLong(), seg.remaining)
                                if (w <= 0) { stop = true } else {
                                    val bb = ByteBuffer.wrap(buf, 0, w.toInt())
                                    var pos = seg.position
                                    while (bb.hasRemaining()) pos += channel.write(bb, pos)
                                    seg.done += w
                                    if (w < n) stop = true
                                }
                            }
                            SpeedLimiter.global.acquire(n)
                            limiter.acquire(n)
                            attempts = 0
                        }
                    } catch (e: HttpError) {
                        if (cancelled) break
                        attempts++
                        if (e.code == -1 || (e.code in 400..499 && e.code != 408 && e.code != 429) || attempts > maxRetries) { fatal = e.message; break }
                        sleepQuiet(minOf(30_000L, 2000L * attempts))
                    } catch (e: IOException) {
                        if (cancelled) break
                        attempts++
                        if (attempts > maxRetries) { fatal = e.message ?: "Network error"; break }
                        sleepQuiet(minOf(30_000L, 1500L * attempts))
                    } finally {
                        runCatching { input?.close() }; runCatching { conn?.disconnect() }
                    }
                }
            } finally {
                workers.remove(seg)
            }
        }
    }

    private class HttpError(val code: Int, msg: String) : IOException(msg)

    private fun sleepQuiet(ms: Long) { try { sleep(ms) } catch (_: InterruptedException) {} }

    companion object { const val MIN_SPLIT = 256 * 1024L }
}
