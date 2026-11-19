package com.mrmzone.idm.engine

/** Steady transfer rate: bytes over a sliding ~3 second window (like IDM), not the last instant. */
class SpeedMeter(private val windowMs: Long = 3000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var last = 0L

    fun add(totalBytes: Long): Long {
        val now = System.currentTimeMillis()
        samples.addLast(now to totalBytes)
        while (samples.size > 2 && now - samples.first().first > windowMs) samples.removeFirst()
        val (t0, b0) = samples.first()
        val dt = now - t0
        if (dt >= 300) last = ((totalBytes - b0) * 1000 / dt).coerceAtLeast(0)
        return last
    }
}
