package com.mrmzone.idm.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.mrmzone.idm.R
import com.mrmzone.idm.data.Segment

/** Visualises IDM-style download segments: each connection's downloaded part of the file. */
class SegmentBarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val bg = Paint().apply { color = ContextCompat.getColor(ctx, R.color.seg_bg) }
    private val fill = Paint().apply { color = ContextCompat.getColor(ctx, R.color.brand_blue) }
    private val head = Paint().apply { color = ContextCompat.getColor(ctx, R.color.brand_orange) }
    private val sep = Paint().apply { color = 0x55FFFFFF; strokeWidth = 2f }
    private var segs: List<Segment> = emptyList()
    private var total = -1L
    private var completed = false
    private val r = RectF()

    fun set(segments: List<Segment>, totalSize: Long, done: Boolean) {
        segs = segments; total = totalSize; completed = done; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h); c.drawRoundRect(r, 6f, 6f, bg)
        if (completed) { c.drawRoundRect(r, 6f, 6f, fill); return }
        if (total <= 0) {
            val d = segs.sumOf { it.done }
            if (d > 0) { r.set(0f, 0f, w * 0.5f, h); c.drawRoundRect(r, 6f, 6f, fill) }
            return
        }
        for (s in segs) {
            val x0 = s.start * w / total
            val x1 = (s.start + s.done).coerceAtMost(total) * w / total
            if (x1 > x0) c.drawRect(x0, 0f, x1, h, fill)
            if (!s.finished && s.done > 0) c.drawRect((x1 - 3f).coerceAtLeast(x0), 0f, x1, h, head)
            if (s.start > 0) c.drawLine(x0, 0f, x0, h, sep)
        }
    }
}
