package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Пергаментный свиток с одной строкой - «мысль» приложения.
 *
 * Вид не решает, ЧТО показывать и КОГДА: строку присылает экран. Так
 * правила выбора реплик остаются в одном месте, а вид занят только
 * отрисовкой и сменой.
 *
 * Перерисовка идёт ТОЛЬКО во время смены строки (растворение старой и
 * проявление новой). В покое свиток статичен и не тратит кадров.
 */
class MotiveScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density

    private var current = ""
    private var pending: String? = null
    private var fadeStart = 0L
    private val fadeMs = 420L

    private val parchment = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFE8DCC0.toInt()
    }
    private val roller = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF7A5A32.toInt()
    }
    private val rollerLit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFB08A50.toInt()
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = 0xFF6B5C3C.toInt()
    }
    private val fiber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
        color = 0xFFC9BC98.toInt(); alpha = 150
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF4A3B22.toInt()
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
    }
    private val body = Path()

    /** Показать новую строку: старая растворяется, новая проявляется. */
    fun show(line: String) {
        if (line == current && pending == null) return
        pending = line
        fadeStart = System.currentTimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        var alpha = 1f
        val p = pending
        if (p != null) {
            val t = (System.currentTimeMillis() - fadeStart).toFloat() / fadeMs
            if (t >= 1f) {
                current = p; pending = null; alpha = 1f
            } else if (t >= 0.5f) {
                if (current != p) current = p
                alpha = (t - 0.5f) / 0.5f
                postInvalidateOnAnimation()
            } else {
                alpha = 1f - t / 0.5f
                postInvalidateOnAnimation()
            }
        }

        val rollW = h * 0.30f
        val top = h * 0.16f
        val bottom = h * 0.84f

        body.reset()
        body.addRoundRect(rollW * 0.55f, top, w - rollW * 0.55f, bottom,
            3f * d, 3f * d, Path.Direction.CW)
        canvas.drawPath(body, parchment)
        var y = top + (bottom - top) * 0.32f
        while (y < bottom - 2f * d) {
            canvas.drawLine(rollW * 0.9f, y, w - rollW * 0.9f, y, fiber)
            y += (bottom - top) * 0.34f
        }
        canvas.drawPath(body, edge)

        for (cx in floatArrayOf(rollW * 0.55f, w - rollW * 0.55f)) {
            canvas.drawRoundRect(cx - rollW * 0.42f, top - h * 0.10f,
                cx + rollW * 0.42f, bottom + h * 0.10f, rollW * 0.4f, rollW * 0.4f, roller)
            canvas.drawRoundRect(cx - rollW * 0.20f, top - h * 0.07f,
                cx + rollW * 0.04f, bottom + h * 0.07f, rollW * 0.3f, rollW * 0.3f, rollerLit)
        }

        if (current.isEmpty()) return
        // Кегль подбирается так, чтобы длинная реплика влезла целиком.
        val maxW = w - rollW * 2.6f
        var size = h * 0.40f
        text.textSize = size
        while (text.measureText(current) > maxW && size > 7f * d) {
            size -= 0.5f * d
            text.textSize = size
        }
        text.alpha = (235f * alpha).toInt().coerceIn(0, 255)
        val fm = text.fontMetrics
        val baseline = (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(current, w / 2f, baseline, text)
    }
}
