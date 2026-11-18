package com.mrmzone.idm.engine

/** Simple token-bucket limiter. limitBytesPerSec <= 0 means unlimited. */
class SpeedLimiter(@Volatile var limitBytesPerSec: Long = 0) {
    private var windowStart = System.nanoTime()
    private var bytesInWindow = 0L

    fun acquire(n: Int) {
        val limit = limitBytesPerSec
        if (limit <= 0) return
        var sleepMs: Long
        synchronized(this) {
            val now = System.nanoTime()
            if (now - windowStart > 1_000_000_000L) { windowStart = now; bytesInWindow = 0 }
            bytesInWindow += n
            val expectedNs = bytesInWindow * 1_000_000_000L / limit
            sleepMs = (expectedNs - (now - windowStart)) / 1_000_000L
        }
        if (sleepMs > 0) try { Thread.sleep(sleepMs.coerceAtMost(2000)) } catch (_: InterruptedException) {}
    }

    companion object { val global = SpeedLimiter() }
}
