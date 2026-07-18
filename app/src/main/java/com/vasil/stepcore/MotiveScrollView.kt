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

        // Кегль подбирается под ДЛИНУ реплики: короткая идёт крупно, длинная
        // мельче, но целиком. Переносим по словам, максимум три строки.
        val maxW = w - rollW * 2.4f
        val maxH = (bottom - top) - 4f * d
        var size = h * 0.42f
        var lines: List<String> = emptyList()
        while (size > 6.5f * d) {
            text.textSize = size
            lines = wrapLines(current, maxW, MAX_LINES)
            val fm = text.fontMetrics
            val lineH = (fm.descent - fm.ascent) * 0.96f
            val fits = lines.size <= MAX_LINES &&
                lines.all { text.measureText(it) <= maxW } &&
                lines.size * lineH <= maxH
            if (fits) break
            size -= 0.5f * d
        }
        text.textSize = size

        // Чернила тихо переливаются между двумя читаемыми оттенками.
        val k = 0.5f + 0.5f * kotlin.math.sin(
            (System.currentTimeMillis() % 6000L) / 6000.0 * 2.0 * Math.PI).toFloat()
        val cr = (0x4A + (0x6B - 0x4A) * k).toInt()
        val cg = (0x3B + (0x50 - 0x3B) * k).toInt()
        val cb = (0x22 + (0x2E - 0x22) * k).toInt()
        text.color = android.graphics.Color.rgb(cr, cg, cb)
        text.alpha = (238f * alpha).toInt().coerceIn(0, 255)

        val fm = text.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 0.96f
        val block = lines.size * lineH
        var y2 = (top + bottom) / 2f - block / 2f - fm.ascent * 0.96f
        for (ln in lines) {
            canvas.drawText(ln, w / 2f, y2, text)
            y2 += lineH
        }
        // Перелив медленный: обновляем не чаще пяти раз в секунду.
        postInvalidateDelayed(200)
    }

    /** Перенос по словам: длинное слово не рвётся, лишнее уходит в остаток. */
    private fun wrapLines(src: String, maxW: Float, limit: Int): List<String> {
        val words = src.split(' ')
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in words) {
            val probe = if (line.isEmpty()) word else line.toString() + " " + word
            if (text.measureText(probe) <= maxW || line.isEmpty()) {
                line = StringBuilder(probe)
            } else {
                out.add(line.toString())
                line = StringBuilder(word)
                if (out.size == limit) break
            }
        }
        if (out.size < limit && line.isNotEmpty()) out.add(line.toString())
        return out
    }

    private companion object {
        const val MAX_LINES = 3
    }
}
