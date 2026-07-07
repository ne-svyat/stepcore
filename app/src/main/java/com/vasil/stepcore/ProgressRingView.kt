package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Кольцо прогресса с пятью слоями цели (V9.13).
 *
 * Основное толстое кольцо = ТЕКУЩИЙ слой: дуга ходьбы (синий) + бега
 * (красный), длина = доля текущей цели. Оттенок кольца усиливается с
 * номером слоя (слой 1 базовый -> слой 5 самый насыщенный, HSV).
 *
 * Завершённые слои показаны РЯДОМ ТОЧЕК под центром (2 цели из 5 взято) -
 * в отличие от прежней "подложки полного круга" (V8.14), точки не
 * сливаются с текущей дугой и явно читаются.
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var walk = 0
    private var run = 0
    private var goal = 10000
    private val d = resources.displayMetrics.density
    private val stroke = 18f * d

    private val baseBlue = ContextCompat.getColor(context, R.color.accent_blue)
    private val baseRed = ContextCompat.getColor(context, R.color.accent_red)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.surface2)
    }
    private val walkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val runPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        color = ContextCompat.getColor(context, R.color.surface2)
    }
    private val rect = RectF()
    private val hsv = FloatArray(3)

    fun setData(walk: Int, run: Int, goal: Int) {
        this.walk = walk; this.run = run; this.goal = if (goal > 0) goal else 10000
        invalidate()
    }

    private fun lapColor(base: Int, lap: Int): Int {
        if (lap <= 0) return base
        Color.colorToHSV(base, hsv)
        hsv[1] = (hsv[1] + 0.11f * lap).coerceAtMost(1f)
        hsv[2] = (hsv[2] + 0.07f * lap).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = stroke / 2f + 3f * d
        rect.set(pad, pad, width - pad, height - pad)

        val total = walk + run
        val p = if (total <= 0) 0f else total.toFloat() / goal
        val lap = p.toInt().coerceAtMost(4)
        val frac = if (p >= 5f) 1f else p - lap
        val wShare = if (total > 0) walk.toFloat() / total else 0f
        val rShare = if (total > 0) run.toFloat() / total else 0f

        canvas.drawArc(rect, 0f, 360f, false, bgPaint)

        if (total > 0 && frac > 0f) {
            walkPaint.color = lapColor(baseBlue, lap)
            runPaint.color = lapColor(baseRed, lap)
            val walkSweep = frac * 360f * wShare
            val runSweep = frac * 360f * rShare
            canvas.drawArc(rect, -90f, walkSweep, false, walkPaint)
            canvas.drawArc(rect, -90f + walkSweep, runSweep, false, runPaint)
        }

        drawLapDots(canvas, lap, frac)
    }

    private fun drawLapDots(canvas: Canvas, lap: Int, frac: Float) {
        val r = 4f * d
        val gap = 16f * d
        val totalW = 5 * gap - (gap - 2 * r)
        var x = width / 2f - totalW / 2f + r
        val y = height / 2f + height * 0.20f
        for (i in 0 until 5) {
            when {
                i < lap -> {
                    dotPaint.color = lapColor(baseBlue, i)
                    canvas.drawCircle(x, y, r, dotPaint)
                }
                i == lap && frac > 0f -> {
                    dotPaint.color = lapColor(baseRed, i)
                    dotPaint.alpha = 130
                    canvas.drawCircle(x, y, r, dotPaint)
                    dotPaint.alpha = 255
                }
                else -> canvas.drawCircle(x, y, r, dotRing)
            }
            x += gap
        }
    }
}
