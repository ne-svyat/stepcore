package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Кольцо прогресса к дневной цели. progress 0..1 (и выше).
 * Красное, при достижении цели (>=1) зелёное. Без внешних зависимостей.
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var progress = 0f
    private val d = resources.displayMetrics.density
    private val stroke = 14f * d

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.surface2)
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    fun setProgress(p: Float) { progress = p.coerceAtLeast(0f); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val pad = stroke / 2f + 2f * d
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)
        val reached = progress >= 1f
        fgPaint.color = ContextCompat.getColor(
            context, if (reached) R.color.hm5 else R.color.accent_red
        )
        canvas.drawArc(rect, -90f, progress.coerceAtMost(1f) * 360f, false, fgPaint)
    }
}
