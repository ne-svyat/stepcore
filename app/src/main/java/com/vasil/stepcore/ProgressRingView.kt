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
 * Кольцо прогресса с мультикругами (V8.14).
 *
 * < 100%: как раньше - дуга ходьбы (синий) + бега (красный) на
 * нейтральном фоне, длина = доля цели.
 *
 * >= 100%: прогресс делится на круги. Подложка - ПОЛНЫЙ круг в палитре
 * предыдущего круга (с той же пропорцией ходьба/бег), поверх - дуга
 * текущей доли в палитре текущего круга. Палитра каждого круга
 * вычисляется из базовых accent_blue/accent_red программно
 * (HSV: +0.10 насыщенности и +0.08 яркости на круг) - оттенки остаются
 * "теми же" синим и красным, только мощнее; новые ресурсы не нужны.
 * Максимум 5 кругов (>=500% рисуется как полный пятый).
 *
 * Зелёный фон достижения цели (V8.5) сознательно заменён самой
 * механикой: достижение видно как заполненная подложка + бейдж xN
 * в строке процента (MainActivity).
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var walk = 0
    private var run = 0
    private var goal = 10000
    private val d = resources.displayMetrics.density
    private val stroke = 16f * d

    private val baseBlue = ContextCompat.getColor(context, R.color.accent_blue)
    private val baseRed = ContextCompat.getColor(context, R.color.accent_red)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val walkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val runPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val hsv = FloatArray(3)

    fun setData(walk: Int, run: Int, goal: Int) {
        this.walk = walk; this.run = run; this.goal = if (goal > 0) goal else 10000
        invalidate()
    }

    /** Палитра круга lap (0..4): базовый цвет, усиленный по HSV. */
    private fun lapColor(base: Int, lap: Int): Int {
        if (lap <= 0) return base
        Color.colorToHSV(base, hsv)
        hsv[1] = (hsv[1] + 0.10f * lap).coerceAtMost(1f)
        hsv[2] = (hsv[2] + 0.08f * lap).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = stroke / 2f + 2f * d
        rect.set(pad, pad, width - pad, height - pad)

        val total = walk + run
        val p = if (total <= 0) 0f else total.toFloat() / goal
        // Индекс текущего рисуемого круга: 0..4; >=500% - полный пятый.
        val lap = p.toInt().coerceAtMost(4)
        val frac = if (p >= 5f) 1f else p - lap

        val wShare = if (total > 0) walk.toFloat() / total else 0f
        val rShare = if (total > 0) run.toFloat() / total else 0f

        if (lap == 0) {
            // Первый круг: нейтральный фон, как раньше.
            bgPaint.color = ContextCompat.getColor(context, R.color.surface2)
            canvas.drawArc(rect, 0f, 360f, false, bgPaint)
        } else {
            // Подложка = полный предыдущий круг в его палитре.
            walkPaint.color = lapColor(baseBlue, lap - 1)
            runPaint.color = lapColor(baseRed, lap - 1)
            canvas.drawArc(rect, -90f, 360f * wShare, false, walkPaint)
            canvas.drawArc(rect, -90f + 360f * wShare, 360f * rShare, false, runPaint)
        }

        if (total <= 0 || frac <= 0f) return
        walkPaint.color = lapColor(baseBlue, lap)
        runPaint.color = lapColor(baseRed, lap)
        val walkSweep = frac * 360f * wShare
        val runSweep = frac * 360f * rShare
        // ходьба от верха по часовой, бег - продолжением
        canvas.drawArc(rect, -90f, walkSweep, false, walkPaint)
        canvas.drawArc(rect, -90f + walkSweep, runSweep, false, runPaint)
    }
}
