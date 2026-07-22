package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Профиль прогулок за день: ломаная слева направо.
 *
 * Ключевое решение: ось X - НАКОПЛЕННЫЕ шаги (или время). Она движется
 * только вперёд, поэтому линии физически не могут пересечься - это свойство
 * оси, а не аккуратной раскладки. Ось Y - накопленная относительная высота:
 * "в гору" ведёт вверх, "с горы" вниз, "ровно" горизонтально.
 *
 * Отсюда честное поведение при хождении туда-сюда: поднялся и вернулся -
 * вышел треугольник; сделал пять раз - пила из пяти зубцов. Знать, где было
 * "вперёд", а где "назад", не нужно: это не карта местности, а профиль
 * набора высоты по ходу прогулки.
 *
 * ЧЕСТНАЯ ГРАНИЦА: крутизна символическая. Барометра нет, набор высоты в
 * метрах неизвестен - показываем только факт и длительность подъёма/спуска.
 * Когда появится калибровка по склону, крутизну можно будет уточнить.
 */
class DayProfileView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Seg(
        val label: String,
        val steps: Int,
        val durationMs: Long,
        val startMs: Long
    )

    private var segs: List<Seg> = emptyList()
    private var byTime = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#5A5A5A")
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ADADAD")
        textSize = 26f
    }
    private val path = Path()

    fun setData(list: List<Seg>, byTimeAxis: Boolean) {
        segs = list
        byTime = byTimeAxis
        invalidate()
    }

    private fun colorOf(l: String): Int = when (l) {
        "UP" -> Color.parseColor("#EF9F27")     // как кнопка "в гору"
        "DOWN" -> Color.parseColor("#3D7EFF")   // как кнопка "с горы"
        "FLAT" -> Color.parseColor("#19D45C")   // как кнопка "ровно"
        else -> Color.parseColor("#5A5A5A")     // не отмечено - тихая серая
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 12f * d
        val padR = 12f * d
        val padV = 18f * d

        if (segs.isEmpty()) {
            label.textSize = 14f * d
            canvas.drawText("Нет прогулок за этот день", padL, h / 2f, label)
            return
        }

        // Вес отрезка по выбранной оси. Ноль-длина ломает масштаб - страхуем.
        fun weight(s: Seg): Float =
            if (byTime) (s.durationMs / 1000f).coerceAtLeast(1f)
            else s.steps.toFloat().coerceAtLeast(1f)

        val total = segs.sumOf { weight(it).toDouble() }.toFloat()
        if (total <= 0f) return

        // Первый проход: накопленная высота, чтобы знать её размах.
        var y = 0f
        var minY = 0f
        var maxY = 0f
        val ys = FloatArray(segs.size + 1)
        ys[0] = 0f
        for (i in segs.indices) {
            val s = segs[i]
            val dy = when (s.label) {
                "UP" -> weight(s)
                "DOWN" -> -weight(s)
                else -> 0f
            }
            y += dy
            ys[i + 1] = y
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val span = (maxY - minY).coerceAtLeast(1f)

        val plotW = w - padL - padR
        val plotH = h - padV * 2f

        fun px(acc: Float) = padL + plotW * (acc / total)
        fun py(v: Float) = padV + plotH * (1f - (v - minY) / span)

        // Ось нулевой высоты - опора для глаза.
        val zeroY = py(0f)
        canvas.drawLine(padL, zeroY, w - padR, zeroY, axis)

        // Второй проход: каждый отрезок своим цветом.
        var acc = 0f
        for (i in segs.indices) {
            val s = segs[i]
            val wgt = weight(s)
            val x0 = px(acc)
            val x1 = px(acc + wgt)
            val y0 = py(ys[i])
            val y1 = py(ys[i + 1])
            line.color = colorOf(s.label)
            line.strokeWidth = if (s.label == "NONE" || s.label == "") 2f * d else 4f * d
            path.reset()
            path.moveTo(x0, y0)
            path.lineTo(x1, y1)
            canvas.drawPath(path, line)
            acc += wgt
        }
    }
}
