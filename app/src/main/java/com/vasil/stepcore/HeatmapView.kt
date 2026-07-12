package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.time.LocalDate

/**
 * Тепловая карта года ОДНИМ холстом (V14.6).
 *
 * Раньше карта была сеткой из ~365 отдельных View. Для статичных квадратов
 * это работало, но анимировать их нельзя в принципе: 365 View, каждая
 * перерисовывается 20 раз в секунду - экран умрёт. Один холст рисует всю
 * карту за один проход и стоит столько же, сколько один средний виджет.
 *
 * Уровни несут смысл, а не просто цвет:
 *   0 - нет данных (пустая клетка, только намёк контура)
 *   1 - слабый день: клетка ДЁРГАЕТСЯ (глитч) - день сбоит
 *   2..4 - рабочие дни, спокойная штриховка
 *   5 - сильный день: клетка СВЕТИТСЯ (пульсирующий ореол)
 */
class HeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Один день: уровень 0..5 и число шагов (для тапа). */
    data class Day(val date: LocalDate, val level: Int, val steps: Int)

    private val d = resources.displayMetrics.density
    private val cell = 13f * d
    private val gap = 3f * d
    private var weeks = 0

    private var days: List<Day> = emptyList()
    private var onDayTap: ((Day) -> Unit)? = null

    private val levelColors = intArrayOf(
        ContextCompat.getColor(context, R.color.hm_empty),
        ContextCompat.getColor(context, R.color.hm1),
        ContextCompat.getColor(context, R.color.hm2),
        ContextCompat.getColor(context, R.color.hm3),
        ContextCompat.getColor(context, R.color.hm4),
        ContextCompat.getColor(context, R.color.hm5),
    )

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
    }

    private val onTick: () -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BoilClock.register(onTick)
    }

    override fun onDetachedFromWindow() {
        BoilClock.unregister(onTick)
        super.onDetachedFromWindow()
    }

    fun setData(list: List<Day>, weeksCount: Int, tap: (Day) -> Unit) {
        days = list
        weeks = weeksCount
        onDayTap = tap
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (weeks * (cell + gap) + gap).toInt().coerceAtLeast(1)
        val h = (7 * (cell + gap) + gap).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val col = ((event.x - gap) / (cell + gap)).toInt()
            val row = ((event.y - gap) / (cell + gap)).toInt()
            val idx = col * 7 + row
            if (col in 0 until weeks && row in 0..6 && idx in days.indices) {
                onDayTap?.invoke(days[idx])
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val ph = BoilClock.phase
        // Пульс общий для всех сильных дней: они дышат в такт, как огни.
        val glowPulse = 0.55f + 0.45f *
            (0.5f + 0.5f * kotlin.math.sin((ph * 2.1f).toDouble()).toFloat())

        for ((i, day) in days.withIndex()) {
            val col = i / 7
            val row = i % 7
            var x = gap + col * (cell + gap)
            val y = gap + row * (cell + gap)
            val w = Wobble(7000L + i * 31L + BoilClock.frame)

            if (day.level == 0) {
                // Пустой день: только бледный намёк, чтобы сетка читалась.
                strokePaint.color = levelColors[0]
                strokePaint.alpha = 90
                strokePaint.strokeWidth = 1f * d
                val p = Path()
                Doodle.roundRect(p, x, y, cell, cell, 2f * d, 0.8f, w)
                canvas.drawPath(p, strokePaint)
                continue
            }

            val color = levelColors[day.level]

            // Слабый день ГЛЮЧИТ: клетка дёргается вбок рывками, а не плавно -
            // сбой должен читаться как сбой, а не как дыхание.
            if (day.level == 1) {
                val g = kotlin.math.sin((ph * 9.0 + i).toDouble()).toFloat()
                if (g > 0.75f) x += 1.6f * d * (if (i % 2 == 0) 1f else -1f)
            }

            // Сильный день СВЕТИТСЯ: пульсирующий ореол вокруг клетки.
            if (day.level == 5) {
                strokePaint.color = color
                strokePaint.alpha = (110 * glowPulse).toInt()
                strokePaint.strokeWidth = 2.5f * d
                val halo = Path()
                Doodle.roundRect(halo, x - 2f * d, y - 2f * d,
                    cell + 4f * d, cell + 4f * d, 3f * d, 1.2f, w)
                canvas.drawPath(halo, strokePaint)
            }

            // Заливка - ШТРИХОВКОЙ, а не сплошной: ровный прямоугольник рядом
            // с дрожащим контуром выдал бы машину.
            hatchPaint.color = color
            hatchPaint.alpha = if (day.level == 5) (200 * glowPulse).toInt() else 170
            val fillPath = Path()
            Doodle.hatch(fillPath, x, y, x + cell, y + cell, 3.5f * d, 55f, 1f * d, w)
            canvas.drawPath(fillPath, hatchPaint)

            strokePaint.color = color
            strokePaint.alpha = 255
            strokePaint.strokeWidth = if (day.level >= 4) 1.8f * d else 1.3f * d
            val box = Path()
            Doodle.roundRect(box, x, y, cell, cell, 2f * d, 1f, w)
            canvas.drawPath(box, strokePaint)
        }
    }
}
