package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Полоса состава пути: сколько пройдено к цели и из чего это сложилось.
 *
 * Две отдельные строки легенды с квадратиками занимали место и требовали
 * читать цифры, чтобы понять соотношение. Полоса показывает то же самое
 * мгновенно: синяя часть - ходьба, красная - бег, длина заполнения - доля
 * дневной цели. Риски отмечают каждую пятую часть цели, поэтому «половина
 * дня» видна без вычислений.
 *
 * По заполненной части медленно пробегает блик - признак того, что счёт
 * идёт. При нуле шагов анимации нет вовсе.
 */
class StrideBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density
    private var walk = 0
    private var run = 0
    private var goal = 10000

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF1E222B.toInt()
    }
    private val walkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val runPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF39404E.toInt()
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF0A0D12.toInt()
    }
    private val clip = Path()
    private val rect = RectF()

    init {
        walkPaint.color = androidx.core.content.ContextCompat.getColor(context, R.color.accent_blue)
        runPaint.color = androidx.core.content.ContextCompat.getColor(context, R.color.accent_red)
    }

    fun setData(walkSteps: Int, runSteps: Int, dayGoal: Int) {
        if (walk == walkSteps && run == runSteps && goal == dayGoal) return
        walk = walkSteps; run = runSteps; goal = if (dayGoal > 0) dayGoal else 10000
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f

        rect.set(0f, 0f, w, h)
        clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.drawPath(clip, track)

        canvas.save()
        canvas.clipPath(clip)

        val total = walk + run
        val frac = (total.toFloat() / goal).coerceIn(0f, 1f)
        val filled = w * frac
        if (total > 0 && filled > 0.5f) {
            val walkW = filled * (walk.toFloat() / total)
            canvas.drawRect(0f, 0f, walkW, h, walkPaint)
            canvas.drawRect(walkW, 0f, filled, h, runPaint)

            // Блик пробегает по заполненной части: счёт идёт.
            val t = (System.currentTimeMillis() % 2600L) / 2600f
            val gx = filled * t
            glowPaint.color = 0xFFFFFFFF.toInt()
            glowPaint.alpha = 55
            canvas.drawRect(gx - h * 0.9f, 0f, gx + h * 0.9f, h, glowPaint)
        }

        // Риски: каждая пятая часть цели - «сколько дня уже сделано».
        tickPaint.strokeWidth = 1.4f * d
        for (k in 1..4) {
            val x = w * k / 5f
            canvas.drawLine(x, h * 0.18f, x, h * 0.82f, tickPaint)
        }
        canvas.restore()

        edgePaint.strokeWidth = 1.6f * d
        canvas.drawPath(clip, edgePaint)

        if (walk + run > 0) postInvalidateDelayed(80)
    }
}
