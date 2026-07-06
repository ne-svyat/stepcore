package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Кольцо: длина дуги = прогресс к цели, цвет делится на ходьбу (синяя)
 * и бег (красный) пропорционально шагам. Цель взята -> фон-кольцо зелёное.
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var walk = 0
    private var run = 0
    private var goal = 10000
    private val d = resources.displayMetrics.density
    private val stroke = 16f * d

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val walkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_blue)
    }
    private val runPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_red)
    }
    private val rect = RectF()

    fun setData(walk: Int, run: Int, goal: Int) {
        this.walk = walk; this.run = run; this.goal = if (goal > 0) goal else 10000
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = stroke / 2f + 2f * d
        rect.set(pad, pad, width - pad, height - pad)

        val total = walk + run
        val reached = total >= goal
        bgPaint.color = ContextCompat.getColor(
            this@ProgressRingView.context,
            if (reached) R.color.hm5 else R.color.surface2
        )
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)

        if (total <= 0) return
        val frac = (total.toFloat() / goal).coerceAtMost(1f)
        val walkSweep = frac * 360f * (walk.toFloat() / total)
        val runSweep = frac * 360f * (run.toFloat() / total)
        // ходьба от верха по часовой, бег — продолжением
        canvas.drawArc(rect, -90f, walkSweep, false, walkPaint)
        canvas.drawArc(rect, -90f + walkSweep, runSweep, false, runPaint)
    }
}
