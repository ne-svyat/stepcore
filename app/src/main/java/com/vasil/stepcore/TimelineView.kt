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
 * низ синий (ходьба), верх красный (бег). Значение над подписанными
 * столбцами, тап по столбцу -> onBarTap (детали интервала).
 * Ось Y рисуется снаружи (TimelineActivity), тут только бары.
 *
 * V9.8: корректная обработка тача внутри HorizontalScrollView -
 * requestDisallowInterceptTouchEvent на DOWN, различение тап/скролл
 * по смещению (>12dp = скролл, отдаём жест родителю).
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /**
     * Сегмент столбца. days - доля суток, покрытая столбцом, для масштаба
     * базового обмена (час = 1/24, день = 1, неделя = 7, месяц = N дней).
     */
    data class Seg(val walk: Int, val run: Int, val label: String, val days: Float = 1f)

    private var segs: List<Seg> = emptyList()
    private var maxTotal = 1
    private var labelEvery = 1
    private val d = resources.displayMetrics.density
    private val barW = 22f * d
    private val gap = 6f * d
    private val labelH = 22f * d
    private val topPad = 20f * d

    val maxSegTotal: Int get() = maxTotal
    var onBarTap: ((Int) -> Unit)? = null

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
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_main)
        textSize = 9f * d
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setSegments(list: List<Seg>, labelEveryNth: Int = 1) {
        segs = list
        maxTotal = (list.maxOfOrNull { it.walk + it.run } ?: 1).coerceAtLeast(1)
        labelEvery = labelEveryNth.coerceAtLeast(1)
        requestLayout(); invalidate()
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > 12f * d) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.abs(event.x - downX) > 12f * d ||
                        kotlin.math.abs(event.y - downY) > 12f * d
                if (!moved && segs.isNotEmpty()) {
                    val idx = ((downX - gap) / (barW + gap)).toInt()
                    if (idx in segs.indices) { onBarTap?.invoke(idx); performClick() }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

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
                if (seg.run > 0) {
                    rect.set(left, top, right, top + runH)
                    canvas.drawRoundRect(rect, radius, radius, runPaint)
                }
                if (seg.walk > 0) {
                    rect.set(left, top + runH, right, bottom)
                    canvas.drawRoundRect(rect, radius, radius, walkPaint)
                }
            }
            if (i % labelEvery == 0) {
                canvas.drawText(seg.label, (left + right) / 2f, height - 6f * d, textPaint)
                if (total > 0) {
                    canvas.drawText(compact(total), (left + right) / 2f,
                        (height - labelH - (total.toFloat() / maxTotal) * chartH) - 5f * d,
                        valuePaint)
                }
            }
        }
    }

    private fun compact(n: Int): String =
        if (n >= 1000) "%.1fk".format(n / 1000f) else n.toString()
}
