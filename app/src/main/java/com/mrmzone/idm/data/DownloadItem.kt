package com.mrmzone.idm.data

enum class Status { QUEUED, WAITING, CONNECTING, DOWNLOADING, PAUSED, COMPLETED, ERROR, MOVING;
    val isActive get() = this == CONNECTING || this == DOWNLOADING || this == MOVING || this == WAITING
}

/** One byte range of a download, handled by one connection. end is inclusive. */
class Segment(var start: Long, @Volatile var end: Long, @Volatile var done: Long = 0) {
    val position get() = start + done
    val remaining get() = if (end == UNKNOWN) Long.MAX_VALUE else end - position + 1
    val finished get() = position > end
    override fun toString() = "$start:$end:$done"
    companion object {
        const val UNKNOWN = Long.MAX_VALUE
        fun parseList(s: String?): MutableList<Segment> =
            s.orEmpty().split(';').filter { it.isNotBlank() }.mapNotNull {
                val p = it.split(':')
                if (p.size == 3) Segment(p[0].toLong(), p[1].toLong(), p[2].toLong()) else null
            }.toMutableList()
    }
}

data class DownloadItem(
    var id: Long = 0,
    var url: String,
    var fileName: String,
    var category: Category = Category.OTHER,
    var totalSize: Long = -1,
    var downloaded: Long = 0,
    var status: Status = Status.PAUSED,
    var connections: Int = 8,
    var resumable: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var finishedAt: Long = 0,
    var lastTry: Long = 0,
    var error: String? = null,
    var segments: String? = null,
    var tempPath: String? = null,
    var finalPath: String? = null,
    var referer: String? = null,
    var userAgent: String? = null,
    var cookies: String? = null,
    var inQueue: Boolean = false,
    var description: String? = null,
    var speedLimitKb: Int = 0,
    /** null = direct HTTP download, "ytdlp" = video page handled by yt-dlp */
    var kind: String? = null,
    /** yt-dlp format selector, e.g. "137+140" */
    var formatSpec: String? = null,
    /** yt-dlp extra: merge container ("mp4"/"webm"/"mkv") or "mp3" for audio extraction */
    var extra: String? = null,
    /** folder chosen by the user (SAF tree uri); null = Download/MRM Zone/<category> */
    var saveDir: String? = null,
) {
    val isYtdlp get() = kind == "ytdlp"

    // runtime only
    @Transient var speed: Long = 0
    @Transient var liveSegments: List<Segment> = emptyList()
    @Transient var activeConnections: Int = 0

    val progress: Int get() = if (totalSize > 0) ((downloaded * 100) / totalSize).toInt().coerceIn(0, 100) else 0
    val timeLeftSec: Long get() = if (speed > 0 && totalSize > 0) (totalSize - downloaded) / speed else -1
}
