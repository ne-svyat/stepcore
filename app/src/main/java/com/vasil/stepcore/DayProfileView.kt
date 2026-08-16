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
        val startMs: Long,
        val reliable: Boolean = true
    )

    private var segs: List<Seg> = emptyList()
    private var byTime = false
    private var selectedIndex = -1

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
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * ПРОФИЛЬ ПРОЧЕРЧИВАЕТСЯ, А НЕ ВОЗНИКАЕТ.
     *
     * Это единственный график приложения, который появлялся целиком и
     * мгновенно. Разница не косметическая: линия профиля - это ПУТЬ, и
     * прочерченная слева направо она сразу говорит, в какую сторону
     * читается время. Возникшая целиком - выглядит схемой, и направление
     * приходится выяснять по подписям, которых здесь нет.
     *
     * Впереди линии идёт светлая головка - точка, которая её и рисует.
     */
    private var bornAt = 0L

    fun setData(list: List<Seg>, byTimeAxis: Boolean) {
        segs = list
        byTime = byTimeAxis
        selectedIndex = -1
        bornAt = System.currentTimeMillis()
        invalidate()
    }

    fun select(index: Int) {
        selectedIndex = index
        invalidate()
    }

    companion object {
        /** Прочерчивание профиля, мс. */
        const val REVEAL_MS = 760f

        /** Единая палитра меток: те же цвета у линии, у плиты интервала и у
         *  кнопок на главном экране. Один смысл - один цвет. */
        fun colorFor(label: String): Int = when (label) {
            "UP" -> Color.parseColor("#EF9F27")
            "DOWN" -> Color.parseColor("#3D7EFF")
            "FLAT" -> Color.parseColor("#19D45C")
            // Транспорт - свой цвет, не спорит ни с одной меткой уклона.
            "TRANSPORT" -> Color.parseColor("#9B7EDE")
            else -> Color.parseColor("#5A5A5A")
        }
    }

    private fun colorOf(l: String): Int = colorFor(l)

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

        if (bornAt == 0L) bornAt = System.currentTimeMillis()
        val raw = ((System.currentTimeMillis() - bornAt).toFloat() / REVEAL_MS)
            .coerceIn(0f, 1f)
        // Замедление к концу: перо не втыкается в правый край.
        val grow = 1f - (1f - raw) * (1f - raw)
        // Граница прочерченного, в долях ширины графика.
        val frontier = padL + plotW * grow

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
            val sel = i == selectedIndex
            line.color = colorOf(s.label)
            line.strokeWidth = when {
                sel -> 9f * d
                s.label == "NONE" || s.label == "" -> 2f * d
                s.label == "TRANSPORT" -> 3f * d
                !s.reliable -> 2.5f * d          // короткий отрезок - тоньше
                else -> 4f * d
            }
            line.alpha = if (selectedIndex >= 0 && !sel) 90 else 255

            // Отрезок целиком за границей прочерченного - его ещё нет.
            if (x0 > frontier) { acc += wgt; continue }
            // Отрезок пересечён границей - рисуем его ЧАСТЬ, а высоту
            // берём линейной долей: перо не может опередить само себя.
            var ex = x1
            var ey = y1
            if (x1 > frontier) {
                val f = ((frontier - x0) / (x1 - x0).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                ex = x0 + (x1 - x0) * f
                ey = y0 + (y1 - y0) * f
            }
            path.reset()
            path.moveTo(x0, y0)
            path.lineTo(ex, ey)
            canvas.drawPath(path, line)

            // Головка пера: светится на самом кончике линии.
            if (grow < 1f && x1 >= frontier && x0 <= frontier) {
                head.color = colorOf(s.label)
                head.alpha = 70
                canvas.drawCircle(ex, ey, 7f * d, head)
                head.alpha = 255
                canvas.drawCircle(ex, ey, 2.6f * d, head)
            }
            if (sel && x1 <= frontier) {
                // Отбивки по краям: видно, где именно этот отрезок на ленте.
                axis.color = colorOf(s.label)
                canvas.drawLine(x0, padV, x0, h - padV, axis)
                canvas.drawLine(x1, padV, x1, h - padV, axis)
                axis.color = Color.parseColor("#5A5A5A")
            }
            line.alpha = 255
            acc += wgt
        }

        // Кадры тратятся только пока перо идёт. Готовый профиль статичен:
        // ему движение не нужно, а батарее оно стоит.
        if (grow < 1f) postInvalidateOnAnimation()
    }
}
