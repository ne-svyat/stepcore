package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Полоса активности: столбик = отрезок времени. Высота ∝ шагам,
 * низ синий (ходьба), верх красный (бег). Ширина под число столбцов,
 * оборачивается в HorizontalScrollView при необходимости.
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Seg(val walk: Int, val run: Int, val label: String)

    private var segs: List<Seg> = emptyList()
    private var maxTotal = 1
    private var labelEvery = 1
    private val d = resources.displayMetrics.density
    private val barW = 22f * d
    private val gap = 6f * d
    private val labelH = 22f * d
    private val topPad = 8f * d

    private val walkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_blue)
    }
    private val runPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_red)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface2)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        textSize = 10f * d
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setSegments(list: List<Seg>, labelEveryNth: Int = 1) {
        segs = list
        maxTotal = (list.maxOfOrNull { it.walk + it.run } ?: 1).coerceAtLeast(1)
        labelEvery = labelEveryNth.coerceAtLeast(1)
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (segs.size * (barW + gap) + gap).toInt().coerceAtLeast(1)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (segs.isEmpty()) return
        val chartH = height - labelH - topPad
        val radius = 4f * d
        segs.forEachIndexed { i, seg ->
            val left = gap + i * (barW + gap)
            val right = left + barW
            val total = seg.walk + seg.run
            if (total <= 0) {
                val y = height - labelH - 3f * d
                rect.set(left, y - 3f * d, right, y)
                canvas.drawRoundRect(rect, radius, radius, emptyPaint)
            } else {
                val h = (total.toFloat() / maxTotal) * chartH
                val bottom = height - labelH
                val top = bottom - h
                val runH = h * seg.run / total
                // бег сверху
                if (seg.run > 0) {
                    rect.set(left, top, right, top + runH)
                    canvas.drawRoundRect(rect, radius, radius, runPaint)
                }
                // ходьба снизу
                if (seg.walk > 0) {
                    rect.set(left, top + runH, right, bottom)
                    canvas.drawRoundRect(rect, radius, radius, walkPaint)
                }
            }
            if (i % labelEvery == 0) {
                canvas.drawText(seg.label, (left + right) / 2f, height - 6f * d, textPaint)
            }
        }
    }
}
