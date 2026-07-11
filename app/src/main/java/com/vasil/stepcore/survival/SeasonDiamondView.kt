package com.vasil.stepcore.survival

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.vasil.stepcore.R

/**
 * Значок сезона экспедиции (V13.0, фаза 2).
 *
 * Ромб (квадрат, развёрнутый на 45°) из 4 треугольных секторов - зима,
 * весна, лето, осень, по часовой стрелке от левого верхнего. Активный
 * сезон залит и светится ярче, остальные - тусклый контур. Сезон -
 * центральный параметр мира Survival Mode, поэтому это единственная
 * тема, которая совпадает с механикой режима, а не просто украшение.
 *
 * Живёт в survival-пакете, а не в core UI: сезон - понятие режима, не
 * шагомера. MainActivity получает готовый номер сезона (0..3, либо -1
 * для "нет активной экспедиции") через setSeason() и ничего не знает
 * о том, как он был посчитан.
 */
class SeasonDiamondView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var season = -1 // -1 = нет активного сезона (нет активной экспедиции)
    private val d = resources.displayMetrics.density

    private val dimFill = ContextCompat.getColor(context, R.color.surface2)
    private val dimStroke = ContextCompat.getColor(context, R.color.axis_dim)
    private val activeFill = ContextCompat.getColor(context, R.color.accent_amber)
    private val activeStroke = ContextCompat.getColor(context, R.color.accent_amber_bright)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * d
    }
    private val quadPath = Path()

    /** @param s 0=зима 1=весна 2=лето 3=осень, -1=нет активной экспедиции. */
    fun setSeason(s: Int) {
        season = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 2f * d

        val top = floatArrayOf(cx, cy - r)
        val right = floatArrayOf(cx + r, cy)
        val bottom = floatArrayOf(cx, cy + r)
        val left = floatArrayOf(cx - r, cy)

        drawQuadrant(canvas, 0, cx, cy, left, top)    // зима: слева-сверху
        drawQuadrant(canvas, 1, cx, cy, top, right)   // весна: сверху-справа
        drawQuadrant(canvas, 2, cx, cy, right, bottom) // лето: справа-снизу
        drawQuadrant(canvas, 3, cx, cy, bottom, left)  // осень: снизу-слева
    }

    private fun drawQuadrant(
        canvas: Canvas, idx: Int, cx: Float, cy: Float, a: FloatArray, b: FloatArray,
    ) {
        val active = idx == season
        quadPath.reset()
        quadPath.moveTo(cx, cy)
        quadPath.lineTo(a[0], a[1])
        quadPath.lineTo(b[0], b[1])
        quadPath.close()

        fillPaint.color = if (active) activeFill else dimFill
        canvas.drawPath(quadPath, fillPaint)
        strokePaint.color = if (active) activeStroke else dimStroke
        canvas.drawPath(quadPath, strokePaint)
    }
}
