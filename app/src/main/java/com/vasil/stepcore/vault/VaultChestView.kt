package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.sin

/**
 * СУНДУК ТАЙНИКА.
 *
 * Зачем. Вход в Тайник был полем ввода на пустом экране - вход в бухгалтерию,
 * а не в тайник. Между тем это самое загадочное место приложения: скрытый
 * модуль, который ещё надо уметь открыть. Экран обязан это обещать.
 *
 * Что рисуется. Сундук, закрытый. Он дышит: замочная скважина светится
 * медленным неровным светом, из щели под крышкой сочится свет, в воздухе
 * висит пыль. При наборе пароля крышка вздрагивает - сундук знает, что его
 * трогают. Неверный пароль - короткий отказ: сундук дёргается и гаснет
 * красным. Верный - крышка откидывается, и наружу вылетают свитки, письма
 * и записки, кружась и растворяясь.
 *
 * Границы. Вьюха живёт в пакете vault и не знает ничего из ядра шагомера -
 * страж границы модуля обязан молчать. Никаких Context-зависимостей, кроме
 * плотности экрана.
 *
 * Кадры. Пока сундук закрыт, перерисовка идёт редко (дыхание медленное);
 * во время открытия - каждый кадр. Когда номер кончился и сундук просто
 * закрыт, вьюха всё равно тикает медленно - это один элемент на экране,
 * а не пятнадцать плит.
 */
class VaultChestView(context: Context) : View(context) {

    enum class Mood { CLOSED, TOUCHED, DENIED, OPENING }

    private val d = resources.displayMetrics.density
    private var mood = Mood.CLOSED
    private var moodAt = 0L
    private var touchedAt = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val paper = Path()

    /** Набирают пароль: сундук вздрагивает. Вызывать можно часто. */
    fun touched() {
        touchedAt = System.currentTimeMillis()
        if (mood == Mood.CLOSED) invalidate()
    }

    /** Не подошло. */
    fun denied() {
        mood = Mood.DENIED
        moodAt = System.currentTimeMillis()
        invalidate()
    }

    /** Подошло: крышка откидывается, содержимое вылетает. */
    fun opening() {
        mood = Mood.OPENING
        moodAt = System.currentTimeMillis()
        invalidate()
    }

    private fun rnd(i: Int): Float {
        var z = (i * 6364136223846793005L) + 1442695040888963407L
        z = (z xor (z ushr 33)) * -0x7ee3623a03d3c83fL
        return ((z ushr 40).toInt() and 0xFFFF) / 65535f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()
        val t = (now - moodAt).toFloat()

        val open = when (mood) {
            Mood.OPENING -> (t / OPEN_MS).coerceIn(0f, 1f)
            else -> 0f
        }
        // Отказ: три затухающих рывка, потом сундук снова спокоен.
        val deny = if (mood == Mood.DENIED) (1f - (t / DENY_MS)).coerceIn(0f, 1f) else 0f
        if (mood == Mood.DENIED && deny <= 0f) mood = Mood.CLOSED
        // Отклик на набор: короткая дрожь крышки.
        val touch = (1f - (now - touchedAt).toFloat() / TOUCH_MS).coerceIn(0f, 1f)

        val cx = w / 2f + deny * 6f * d * sin((t * 0.055f).toDouble()).toFloat()
        val bw = w * 0.34f
        val bh = h * 0.30f
        val by = h * 0.72f
        val lidH = h * 0.26f

        // Свет из-под крышки: он тем сильнее, чем ближе к открытию.
        val glow = 0.35f + 0.35f * sin((now * 0.0013).toDouble()).toFloat() + open * 0.9f
        fill.color = if (deny > 0f) 0xFFFF4B4B.toInt() else 0xFFE8A33D.toInt()
        var g = 0
        while (g < 3) {
            fill.alpha = ((26 - g * 7) * (glow + deny)).toInt().coerceIn(0, 255)
            canvas.drawOval(cx - bw * (1.3f + 0.35f * g), by - bh - lidH * (0.5f + 0.4f * g),
                cx + bw * (1.3f + 0.35f * g), by + bh * 0.6f, fill)
            g++
        }

        // Содержимое: свитки и письма вылетают ТОЛЬКО при открытии и
        // рисуются ПОД крышкой, чтобы вылет читался изнутри.
        if (open > 0f) {
            var i = 0
            while (i < 7) {
                val delay = i * 0.055f
                val k = ((open - delay) / (1f - delay)).coerceIn(0f, 1f)
                if (k <= 0f) { i++; continue }
                val dir = (rnd(i) - 0.5f) * 2f
                val px = cx + dir * w * 0.34f * k
                val py = by - bh - h * 0.62f * (1f - (1f - k) * (1f - k))
                val fade = (1f - k * k)
                canvas.save()
                canvas.translate(px, py)
                canvas.rotate((rnd(i + 9) - 0.5f) * 90f + k * 260f * dir)
                val sz = h * (0.075f + 0.03f * rnd(i + 3))
                when (i % 3) {
                    0 -> {
                        // Свиток: полоса с валиками по краям.
                        fill.color = 0xFFEADFC2.toInt()
                        fill.alpha = (235f * fade).toInt().coerceIn(0, 255)
                        canvas.drawRect(-sz * 0.75f, -sz * 0.34f, sz * 0.75f, sz * 0.34f, fill)
                        fill.color = 0xFF8A6636.toInt()
                        fill.alpha = (235f * fade).toInt().coerceIn(0, 255)
                        canvas.drawRect(-sz * 0.92f, -sz * 0.42f, -sz * 0.66f, sz * 0.42f, fill)
                        canvas.drawRect(sz * 0.66f, -sz * 0.42f, sz * 0.92f, sz * 0.42f, fill)
                    }
                    1 -> {
                        // Письмо: конверт с клапаном.
                        fill.color = 0xFFF2ECDC.toInt()
                        fill.alpha = (235f * fade).toInt().coerceIn(0, 255)
                        canvas.drawRect(-sz * 0.72f, -sz * 0.48f, sz * 0.72f, sz * 0.48f, fill)
                        line.color = 0xFF9A8A66.toInt()
                        line.alpha = (225f * fade).toInt().coerceIn(0, 255)
                        line.strokeWidth = 1.3f * d
                        canvas.drawLine(-sz * 0.72f, -sz * 0.48f, 0f, sz * 0.05f, line)
                        canvas.drawLine(sz * 0.72f, -sz * 0.48f, 0f, sz * 0.05f, line)
                    }
                    else -> {
                        // Записка: мятый клочок с двумя строками.
                        paper.reset()
                        paper.moveTo(-sz * 0.6f, -sz * 0.5f)
                        paper.lineTo(sz * 0.62f, -sz * 0.42f)
                        paper.lineTo(sz * 0.5f, sz * 0.52f)
                        paper.lineTo(-sz * 0.66f, sz * 0.4f)
                        paper.close()
                        fill.color = 0xFFE6E1D3.toInt()
                        fill.alpha = (230f * fade).toInt().coerceIn(0, 255)
                        canvas.drawPath(paper, fill)
                        line.color = 0xFF8A8474.toInt()
                        line.alpha = (200f * fade).toInt().coerceIn(0, 255)
                        line.strokeWidth = 1.1f * d
                        canvas.drawLine(-sz * 0.4f, -sz * 0.12f, sz * 0.35f, -sz * 0.06f, line)
                        canvas.drawLine(-sz * 0.4f, sz * 0.14f, sz * 0.2f, sz * 0.18f, line)
                    }
                }
                canvas.restore()
                i++
            }
        }

        // Короб.
        fill.color = 0xFF3A2A16.toInt(); fill.alpha = 255
        canvas.drawRect(cx - bw, by - bh, cx + bw, by + bh * 0.55f, fill)
        line.color = 0xFF8A6636.toInt(); line.alpha = 255; line.strokeWidth = 2.2f * d
        canvas.drawRect(cx - bw, by - bh, cx + bw, by + bh * 0.55f, line)
        // Обручи: две вертикальные полосы, сундук читается объёмным.
        fill.color = 0xFF6B4F2A.toInt()
        canvas.drawRect(cx - bw * 0.62f, by - bh, cx - bw * 0.44f, by + bh * 0.55f, fill)
        canvas.drawRect(cx + bw * 0.44f, by - bh, cx + bw * 0.62f, by + bh * 0.55f, fill)

        // Крышка: откидывается назад вокруг задней кромки.
        canvas.save()
        val lidAngle = -102f * open - touch * 3.5f - deny * 2.5f *
            sin((t * 0.06f).toDouble()).toFloat()
        canvas.rotate(lidAngle, cx, by - bh)
        path.reset()
        path.moveTo(cx - bw, by - bh)
        path.lineTo(cx - bw, by - bh - lidH * 0.45f)
        path.quadTo(cx, by - bh - lidH * 1.25f, cx + bw, by - bh - lidH * 0.45f)
        path.lineTo(cx + bw, by - bh)
        path.close()
        fill.color = 0xFF4A3418.toInt()
        canvas.drawPath(path, fill)
        line.color = 0xFFC79B5C.toInt()
        canvas.drawPath(path, line)
        canvas.restore()

        // Замок и скважина: дышат светом, пока сундук закрыт.
        if (open < 0.25f) {
            val keyGlow = (1f - open * 4f).coerceIn(0f, 1f)
            fill.color = 0xFF2A1E10.toInt(); fill.alpha = 255
            canvas.drawRoundRect(cx - bw * 0.17f, by - bh * 0.42f,
                cx + bw * 0.17f, by + bh * 0.10f, 3f * d, 3f * d, fill)
            val kg = 0.45f + 0.55f * sin((now * 0.0021).toDouble()).toFloat()
            fill.color = if (deny > 0f) 0xFFFF4B4B.toInt() else 0xFFFFC257.toInt()
            fill.alpha = ((120f + 135f * kg) * keyGlow).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, by - bh * 0.20f, bw * 0.075f, fill)
            canvas.drawRect(cx - bw * 0.03f, by - bh * 0.20f,
                cx + bw * 0.03f, by - bh * 0.02f, fill)
        }

        // Пыль в свете: висит и всплывает. Её мало - тайник тихий.
        var i = 0
        while (i < 6) {
            val ph = now * 0.0006f * (0.7f + 0.2f * i) + rnd(i + 40) * 6.3f
            val dx = cx + (rnd(i + 50) - 0.5f) * bw * 2.4f
            val dy = by - bh * 1.1f - h * 0.18f *
                ((sin(ph.toDouble()).toFloat() + 1f) * 0.5f)
            fill.color = 0xFFE8A33D.toInt()
            fill.alpha = (110f * (0.4f + 0.6f *
                sin((ph * 1.7f).toDouble()).toFloat())).toInt().coerceIn(0, 255)
            canvas.drawCircle(dx, dy, 1.3f * d, fill)
            i++
        }
        fill.alpha = 255

        // Кадры: часто только пока идёт номер.
        val busy = mood == Mood.OPENING && open < 1f || deny > 0f || touch > 0f
        if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(140)
    }

    private companion object {
        const val OPEN_MS = 900f
        const val DENY_MS = 520f
        const val TOUCH_MS = 260f
    }
}
