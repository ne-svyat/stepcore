package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

    // Столбцы живые: вода колышется, пламя пляшет. Подписка на тот же
    // механизм, что крутит дудл-сцены - отдельного таймера не заводим.
    private val onTick: () -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BoilClock.register(onTick)
    }

    override fun onDetachedFromWindow() {
        BoilClock.unregister(onTick)
        super.onDetachedFromWindow()
    }


    /**
     * Сегмент столбца. days - доля суток, покрытая столбцом, для масштаба
     * базового обмена (час = 1/24, день = 1, неделя = 7, месяц = N дней).
     */
    /**
     * Сегмент столбца. activeDays - сколько РЕАЛЬНЫХ дней с активностью
     * покрывает столбец (час = 1/24 если активен, день = 1 если активен,
     * сегодня = доля прошедших суток, неделя/месяц = сумма таких дней).
     * Покой (BMR) считается по activeDays, а не по календарным слотам -
     * чтобы не начислять базовый расход за пустые/будущие дни (V9.17).
     */
    data class Seg(
        val walk: Int, val run: Int, val label: String,
        val activeDays: Float = 1f,
        val kcalA: Int = -1, val kcalB: Int = -1, val distM: Int = -1,
    )

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

    private val waterEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_blue)
    }
    private val waterSurface = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_blue_bright)
    }
    private val waterHatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
        color = ContextCompat.getColor(context, R.color.accent_blue); alpha = 120
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
        color = ContextCompat.getColor(context, R.color.accent_blue_bright)
    }
    private val fireEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent_red)
    }
    private val fireHatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
        color = ContextCompat.getColor(context, R.color.accent_red); alpha = 120
    }
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
                // Ходьба - ВОДА (снизу, наполняет столб), бег - ОГОНЬ (сверху,
                // рвётся вверх). Порядок не декоративный: вода лежит, пламя
                // поднимается, поэтому бег может быть только НАД ходьбой.
                if (seg.walk > 0) drawWater(canvas, left, top + runH, right, bottom, i)
                if (seg.run > 0) drawFire(canvas, left, top, right, top + runH, i)
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

    /** Ходьба = вода: штриховка, колышущаяся поверхность, всплывающие пузырьки. */
    private fun drawWater(c: Canvas, left: Float, top: Float, right: Float,
                          bottom: Float, idx: Int) {
        val ph = BoilClock.phase
        val w = Wobble(1000L + idx * 17L + BoilClock.frame)
        val body = Path()
        Doodle.hatch(body, left, top + 3f * d, right, bottom, 6f * d, 62f, 1.5f * d, w)
        c.drawPath(body, waterHatch)

        val surf = Path()
        for (k in 0..12) {
            val u = k / 12f
            val x = left + (right - left) * u
            val y = top + 2.5f * d *
                kotlin.math.sin((u * Math.PI * 2.2 + ph * 2.4).toDouble()).toFloat() + w.j(0.6f)
            if (k == 0) surf.moveTo(x, y) else surf.lineTo(x, y)
        }
        val walls = Path()
        Doodle.line(walls, left, top, left, bottom, 1.0f, 8, w)
        Doodle.line(walls, right, top, right, bottom, 1.0f, 8, w)
        Doodle.line(walls, left, bottom, right, bottom, 0.8f, 5, w)
        c.drawPath(walls, waterEdge)
        c.drawPath(surf, waterSurface)

        // Пузырьки поднимаются со дна и исчезают у поверхности.
        val span = bottom - top - 4f * d
        if (span > 8f * d) {
            for (b in 0 until 3) {
                val t = ((ph * 0.5f + b * 0.33f) % 1f)
                val bx = left + (right - left) * (0.25f + 0.25f * b)
                val by = bottom - t * span
                bubblePaint.alpha = (150 * (1f - t)).toInt()
                c.drawCircle(bx, by, (1.4f + b * 0.4f) * d, bubblePaint)
            }
        }
    }

    /** Бег = огонь: штриховка и пляшущие язычки вместо ровной крышки. */
    private fun drawFire(c: Canvas, left: Float, top: Float, right: Float,
                         bottom: Float, idx: Int) {
        val ph = BoilClock.phase
        val w = Wobble(2000L + idx * 23L + BoilClock.frame)
        val body = Path()
        Doodle.hatch(body, left, top + 2f * d, right, bottom, 6f * d, -60f, 1.5f * d, w)
        c.drawPath(body, fireHatch)

        val tongues = Path()
        tongues.moveTo(left, bottom)
        val n = 5
        for (k in 0..n) {
            val u = k / n.toFloat()
            val x = left + (right - left) * u
            val flick = 0.5f + 0.5f *
                kotlin.math.sin((ph * 6.0 + u * 7.0).toDouble()).toFloat()
            val tall = if (k % 2 == 1) 1f else 0.45f
            val y = top - (3f * d + 8f * d * flick) * tall + w.j(0.8f)
            tongues.lineTo(x, y)
        }
        tongues.lineTo(right, bottom)
        c.drawPath(tongues, fireEdge)

        val walls = Path()
        Doodle.line(walls, left, top + 2f * d, left, bottom, 1.0f, 6, w)
        Doodle.line(walls, right, top + 2f * d, right, bottom, 1.0f, 6, w)
        c.drawPath(walls, fireEdge)
    }

    private fun compact(n: Int): String =
        if (n >= 1000) "%.1fk".format(n / 1000f) else n.toString()
}
