package com.vasil.stepcore.vault

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan

/**
 * Подсветка найденного.
 *
 * ЗАЧЕМ РАЗНЫЕ ТОНА
 * -----------------
 * Поиск может расшириться сам: не нашлось по началу слова - ищем внутри
 * слов. Одинаковая подсветка скрывала бы это, и человек думал бы, что
 * нашлось ровно то, что он просил. Тон отвечает на вопрос «насколько это
 * то самое» раньше, чем человек начнёт вчитываться.
 *
 * ПОЧЕМУ РАМКА НЕ ВЕЗДЕ
 * ---------------------
 * Рамка рисуется своим отрисовщиком, а он НЕ УМЕЕТ переноса строки: кусок
 * с пробелом, попав на край, либо уедет за поле, либо пропадёт. Поэтому
 * рамка достаётся только сплошным кускам без пробелов - то есть словам, -
 * а точная фраза красится обычной заливкой. Правило простое и проверяемо,
 * в отличие от «обычно помещается».
 */
object VaultMark {

    /** Точная фраза из кавычек: просили дословно и нашли дословно. */
    const val C_PHRASE = 0xFFB9A6E8.toInt()

    /** Слово совпало целиком. */
    const val C_EXACT = 0xFF9FD9A8.toInt()

    /** Совпало начало слова: падеж, число, форма. */
    const val C_PREFIX = 0xFFE0C08A.toInt()

    /** Совпала середина: поиск расширился, и это видно. */
    const val C_INSIDE = 0xFF8FC4D8.toInt()

    fun colorOf(kind: Int): Int = when (kind) {
        VaultQuery.MARK_PHRASE -> C_PHRASE
        VaultQuery.MARK_EXACT -> C_EXACT
        VaultQuery.MARK_INSIDE -> C_INSIDE
        else -> C_PREFIX
    }

    fun nameOf(kind: Int): String = when (kind) {
        VaultQuery.MARK_PHRASE -> "точная фраза"
        VaultQuery.MARK_EXACT -> "слово целиком"
        VaultQuery.MARK_INSIDE -> "внутри слова"
        else -> "начало слова"
    }

    /**
     * Разложить подсветку по готовому тексту.
     *
     * @param offset сдвиг, если текст встроен в строку побольше
     */
    fun apply(sb: SpannableStringBuilder, source: String, offset: Int,
              q: VaultQuery.Parsed, o: VaultQuery.Options, density: Float) {
        for (sp in VaultQuery.spans(source, q, o)) {
            val from = offset + sp[0]
            val to = offset + sp[1]
            if (from < 0 || to > sb.length || from >= to) continue
            val color = colorOf(sp[2])
            val piece = source.substring(sp[0], sp[1])
            if (piece.any { it.isWhitespace() }) {
                // С пробелом - только заливка: такой кусок может
                // перенестись, а отрисовщик рамки переноса не переживает.
                sb.setSpan(BackgroundColorSpan((color and 0xFFFFFF) or 0x55000000),
                    from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.setSpan(Framed(color, density), from, to,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), from, to,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Живая рамка для перехода из поиска.
     *
     * ПОЧЕМУ ОТДЕЛЬНЫЙ КЛАСС, А НЕ ЦВЕТ ФОНА
     * --------------------------------------
     * Обычная рамка рисуется отрисовщиком ПОВЕРХ фона, и любая заливка под
     * ней не видна вовсе. Пульсация фоном была не видна именно поэтому:
     * её закрашивала рамка. Пульсировать должна сама рамка.
     *
     * Яркость меняется снаружи, а вьюха перерисовывается по требованию:
     * отрезок сам себя перерисовать не может.
     */
    class Pulse(private val color: Int, private val density: Float) : ReplacementSpan() {

        /** 0 - погасла совсем, 1 - полная яркость. */
        var k: Float = 1f

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
                             fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return (paint.measureText(text, start, end) + density * 6).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
                          x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val w = paint.measureText(text, start, end)
            val r = RectF(x, top + density, x + w + density * 6, bottom - density)
            val old = paint.color
            val oldStyle = paint.style
            val oldWidth = paint.strokeWidth

            val a = k.coerceIn(0f, 1f)
            paint.style = Paint.Style.FILL
            paint.color = ((a * 0x55).toInt() shl 24) or (color and 0xFFFFFF)
            canvas.drawRoundRect(r, 5 * density, 5 * density, paint)

            // Рамка толстеет вместе с яркостью: одного цвета мало, когда
            // страница пёстрая, а толщина заметна боковым зрением.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density * (1f + a)
            paint.color = ((0x40 + a * 0xBF).toInt() shl 24) or (color and 0xFFFFFF)
            canvas.drawRoundRect(r, 5 * density, 5 * density, paint)

            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(text, start, end, x + density * 3, y.toFloat(), paint)

            paint.color = old
            paint.style = oldStyle
            paint.strokeWidth = oldWidth
        }
    }

    /** Слово в рамке своего тона. Только для кусков без пробелов. */
    private class Framed(private val color: Int, private val density: Float) : ReplacementSpan() {

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
                             fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return (paint.measureText(text, start, end) + pad() * 2).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
                          x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val w = paint.measureText(text, start, end)
            val r = RectF(x, top + density, x + w + pad() * 2, bottom - density)
            val old = paint.color
            val oldStyle = paint.style

            paint.style = Paint.Style.FILL
            paint.color = (color and 0xFFFFFF) or 0x33000000
            canvas.drawRoundRect(r, 4 * density, 4 * density, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = color
            canvas.drawRoundRect(r, 4 * density, 4 * density, paint)

            paint.style = Paint.Style.FILL
            paint.color = 0xFFF2F0F7.toInt()
            canvas.drawText(text, start, end, x + pad(), y.toFloat(), paint)

            paint.color = old
            paint.style = oldStyle
        }

        private fun pad() = 3 * density
    }
}
