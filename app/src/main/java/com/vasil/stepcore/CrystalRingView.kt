package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Кристалл прогресса (V13.0) — замена ProgressRingView на главном экране.
 *
 * Круг в квадратной области теряет ~21% площади по углам; вытянутый
 * гранёный кристалл встаёт в ту же область плотнее и освобождает место
 * сбоку под текстовый блок (было: цифры теснились ВНУТРИ круга и
 * переполняли его при росте чисел).
 *
 * Данные и их смысл НЕ меняются относительно ProgressRingView: ходьба
 * (синяя часть) + бег (красная часть) делят текущую сумму по доле,
 * прогресс считается относительно дневной цели, до 5 витков цели
 * учитывается. Меняется только ПРЕДСТАВЛЕНИЕ: прежде витки читались по
 * насыщенности цвета и ряду точек под центром - сравнивать оттенки на
 * глаз не всегда очевидно. Здесь виток = горизонтальный ярус, который
 * заливается снизу вверх - заполненный уровень читается однозначно,
 * без сравнения цветов.
 *
 * Дополнение: пунктирная линия внутри кристалла - вчерашний итог на
 * той же вертикальной шкале (0 .. 5 целей). Заливка выше линии -
 * сегодня уже обогнали вчера на эту же минуту.
 */
class CrystalRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // Посчитано один раз в setData(), onDraw() только читает - единый
    // источник истины для отрисовки И для currentLap() снаружи.
    private var storedLap = 0        // 0..4: сколько ярусов залито ПОЛНОСТЬЮ
    private var storedFrac = 0f      // 0..1: заливка текущего яруса
    private var storedWShare = 0f    // 0..1: доля ходьбы в сегодняшней сумме
    private var storedTotal = 0
    private var storedGoal = 10000
    private var storedYesterday = 0
    private var badgeLap = 0         // целых целей пройдено сегодня (для бейджа "×N")

    private val d = resources.displayMetrics.density
    private val outlineW = 2f * d

    private val baseBlue = ContextCompat.getColor(context, R.color.accent_blue)
    private val baseRed = ContextCompat.getColor(context, R.color.accent_red)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = outlineW
        color = ContextCompat.getColor(context, R.color.surface2)
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * d
        color = ContextCompat.getColor(context, R.color.text_main)
        alpha = 140
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 5f * d), 0f)
    }

    private val gemPath = Path()
    private val tierRect = RectF()
    private var bodyTop = 0f
    private var bodyBottom = 0f

    fun setData(walk: Int, run: Int, goal: Int, yesterdayTotal: Int) {
        storedTotal = walk + run
        storedGoal = if (goal > 0) goal else 10000
        storedYesterday = yesterdayTotal
        val p = if (storedTotal <= 0) 0f else storedTotal.toFloat() / storedGoal
        badgeLap = p.toInt()
        val pCapped = p.coerceAtMost(5f)
        storedLap = pCapped.toInt().coerceAtMost(4)
        storedFrac = if (pCapped >= 5f) 1f else pCapped - storedLap
        storedWShare = if (storedTotal > 0) walk.toFloat() / storedTotal else 0f
        invalidate()
    }

    /** Сколько целей ПОЛНОСТЬЮ пройдено сегодня. 0 = цель ещё не закрыта ни разу. */
    fun currentLap(): Int = badgeLap

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val pad = outlineW + 2f * d
        val w2 = w / 2f
        bodyTop = pad
        bodyBottom = h - pad
        // Пропорции грани подобраны визуально под вытянутый кристалл -
        // это форма, не измеренный порог; в отличие от порогов детектора
        // здесь нет "правильного" числа, есть только силуэт.
        val upperY = pad + (h - 2 * pad) * 0.24f
        val lowerY = pad + (h - 2 * pad) * 0.86f
        val halfW = w2 - pad
        val gx = floatArrayOf(w2, w2 + halfW, w2 + halfW * 0.86f, w2, w2 - halfW * 0.86f, w2 - halfW)
        val gy = floatArrayOf(bodyTop, upperY, lowerY, bodyBottom, lowerY, upperY)
        gemPath.reset()
        gemPath.moveTo(gx[0], gy[0])
        for (i in 1 until 6) gemPath.lineTo(gx[i], gy[i])
        gemPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val bodyH = bodyBottom - bodyTop
        val tierH = bodyH / 5f

        canvas.save()
        canvas.clipPath(gemPath)

        if (storedTotal > 0) {
            for (i in 0..storedLap) {
                val fillFrac = if (i < storedLap) 1f else storedFrac
                if (fillFrac <= 0f) continue
                val tierBottom = bodyBottom - i * tierH
                val tierTop = tierBottom - tierH * fillFrac
                val splitX = width * storedWShare
                tierRect.set(0f, tierTop, splitX, tierBottom)
                fillPaint.color = baseBlue
                canvas.drawRect(tierRect, fillPaint)
                tierRect.set(splitX, tierTop, width.toFloat(), tierBottom)
                fillPaint.color = baseRed
                canvas.drawRect(tierRect, fillPaint)
            }
        }

        if (storedYesterday > 0) {
            val totalGoal5 = storedGoal * 5
            val yFrac = (storedYesterday.toFloat() / totalGoal5).coerceIn(0f, 1f)
            val yY = bodyBottom - bodyH * yFrac
            canvas.drawLine(0f, yY, width.toFloat(), yY, ghostPaint)
        }

        canvas.restore()
        canvas.drawPath(gemPath, outlinePaint)
    }
}
