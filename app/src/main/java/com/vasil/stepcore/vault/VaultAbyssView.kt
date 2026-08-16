package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * БЕЗДНА ПОД ВХОДОМ.
 *
 * Половина экрана входа была пустым чёрным полем. Пустота сама по себе не
 * порок - в Тайнике она даже уместна, - но пустота НЕОБЪЯСНЁННАЯ читается
 * как недоделка. Здесь она объяснена: под сундуком не пол, а провал, и в
 * нём кто-то есть.
 *
 * Три слоя, ни один не повторяет другой по природе:
 *
 * 1. ЗАТМЕНИЕ. Чёрный диск, окружённый венцом ТЁМНОГО пламени: языки
 *    багровые у основания и почти чёрные на концах, они живут каждый
 *    своим периодом и медленно вращаются. Диск не рисуется заливкой -
 *    он вырезан светом вокруг, поэтому и читается как дыра, а не как
 *    круг.
 *
 * 2. СИЛУЭТЫ. Две-три фигуры в тени по краям провала. Они почти не видны
 *    и почти не двигаются - только дышат и чуть покачиваются. Показывать
 *    их отчётливо было бы дешевле: страшнее то, что едва различимо.
 *
 * 3. ШЁПОТ. Из темноты изредка проступают слова и снова тают. Каждое
 *    появляется в своём месте, живёт около трёх секунд и уходит; порядок
 *    и место выведены из времени, а не из случайности в кадре, поэтому
 *    слово не мигает и не прыгает.
 *
 * Границы: пакет vault, ядро шагомера не задето. Аллокаций в кадре нет.
 * Кадры редкие: всё здесь движется медленно, и частота ни к чему.
 */
class VaultAbyssView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
    }
    private val flame = Path()
    private val figure = Path()

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
        val cx = w / 2f
        val cy = h * 0.42f
        val r = minOf(w * 0.22f, h * 0.34f)

        // --- ВЕНЕЦ ТЁМНОГО ПЛАМЕНИ ---
        // Языки рисуются ДО диска: диск потом закроет их основания, и
        // пламя окажется позади затмения, как и положено короне.
        var i = 0
        while (i < 26) {
            val a = i / 26f * 2f * Math.PI.toFloat() + (now % 240000L) * 0.0000045f
            val ph = now * 0.0016f * (0.6f + 0.5f * rnd(i)) + i
            val len = r * (0.22f + 0.42f * (0.5f + 0.5f * sin(ph.toDouble()).toFloat()))
            val wdt = 0.055f + 0.03f * rnd(i + 40)
            val ca = cos(a.toDouble()).toFloat()
            val sa = sin(a.toDouble()).toFloat()
            val c1 = cos((a - wdt).toDouble()).toFloat()
            val s1 = sin((a - wdt).toDouble()).toFloat()
            val c2 = cos((a + wdt).toDouble()).toFloat()
            val s2 = sin((a + wdt).toDouble()).toFloat()
            flame.reset()
            flame.moveTo(cx + c1 * r * 0.94f, cy + s1 * r * 0.94f)
            flame.quadTo(cx + ca * (r + len * 0.55f) - sa * len * 0.22f,
                cy + sa * (r + len * 0.55f) + ca * len * 0.22f,
                cx + ca * (r + len), cy + sa * (r + len))
            flame.quadTo(cx + ca * (r + len * 0.45f) + sa * len * 0.20f,
                cy + sa * (r + len * 0.45f) - ca * len * 0.20f,
                cx + c2 * r * 0.94f, cy + s2 * r * 0.94f)
            flame.close()
            // Багровое основание, чёрные концы: пламя не светит, а гасит.
            fill.shader = null
            fill.color = 0xFF6E1024.toInt()
            fill.alpha = (150f * (0.4f + 0.6f * (len / (r * 0.64f)))).toInt().coerceIn(0, 255)
            canvas.drawPath(flame, fill)
            fill.color = 0xFF17060B.toInt()
            fill.alpha = 190
            canvas.drawPath(flame, fill)
            i++
        }

        // Тонкое кольцо света у самой кромки - то, что не закрыто диском.
        soft.shader = RadialGradient(cx, cy, r * 1.55f,
            intArrayOf(0x00000000, 0x59D6564E, 0x00000000),
            floatArrayOf(0.60f, 0.72f, 1f), Shader.TileMode.CLAMP)
        soft.alpha = 255
        canvas.drawCircle(cx, cy, r * 1.55f, soft)
        soft.shader = null

        // --- ДИСК ЗАТМЕНИЯ ---
        fill.shader = null
        fill.color = 0xFF07050A.toInt()
        fill.alpha = 255
        canvas.drawCircle(cx, cy, r, fill)
        // Едва различимый холодный отсвет по нижнему краю диска: без него
        // круг сливается с фоном и перестаёт быть предметом.
        soft.shader = RadialGradient(cx, cy + r * 0.55f, r * 0.9f,
            0x2B6E5A8C, 0x00000000, Shader.TileMode.CLAMP)
        soft.alpha = 255
        canvas.drawCircle(cx, cy + r * 0.4f, r * 0.9f, soft)
        soft.shader = null

        // --- СИЛУЭТЫ ---
        var s = 0
        while (s < 3) {
            val bx = cx + (s - 1) * w * 0.30f + w * 0.02f * sin((now * 0.0004 + s).toDouble())
                .toFloat()
            val bh2 = h * (0.30f + 0.06f * rnd(s + 7))
            val by2 = h * 0.98f
            val breath = 1f + 0.02f * sin((now * 0.0011 + s * 1.7).toDouble()).toFloat()
            figure.reset()
            // Капюшон и плечи одной кривой: узнаётся фигура, а не мешок.
            figure.moveTo(bx - bh2 * 0.30f, by2)
            figure.lineTo(bx - bh2 * 0.26f, by2 - bh2 * 0.52f * breath)
            figure.quadTo(bx - bh2 * 0.22f, by2 - bh2 * 0.92f * breath,
                bx, by2 - bh2 * 0.95f * breath)
            figure.quadTo(bx + bh2 * 0.22f, by2 - bh2 * 0.92f * breath,
                bx + bh2 * 0.26f, by2 - bh2 * 0.52f * breath)
            figure.lineTo(bx + bh2 * 0.30f, by2)
            figure.close()
            fill.color = 0xFF0B0910.toInt()
            fill.alpha = if (s == 1) 235 else 200
            canvas.drawPath(figure, fill)
            // Глаза: две точки, зажигающиеся редко и ненадолго.
            val eye = ((now / 1000L + s * 3L) % 11L) == 0L
            if (eye) {
                fill.color = 0xFF8E1220.toInt()
                fill.alpha = (120f + 100f * sin((now % 1000L) / 1000f * Math.PI.toFloat()))
                    .toInt().coerceIn(0, 255)
                canvas.drawCircle(bx - bh2 * 0.09f, by2 - bh2 * 0.80f, 1.7f * d, fill)
                canvas.drawCircle(bx + bh2 * 0.09f, by2 - bh2 * 0.80f, 1.7f * d, fill)
            }
            s++
        }

        // --- ШЁПОТ ---
        val slot = (now / WHISPER_MS).toInt()
        val f = (now % WHISPER_MS).toFloat() / WHISPER_MS
        // Слово живёт две трети окна, треть темнота молчит: без пауз
        // шёпот превращается в бегущую строку.
        if (f < 0.72f) {
            val k = sin((f / 0.72f * Math.PI).toFloat())
            val word = WHISPERS[Math.floorMod(slot * 7 + 3, WHISPERS.size)]
            val wx = w * (0.18f + 0.64f * rnd(slot + 11))
            val wy = h * (0.20f + 0.62f * rnd(slot + 29)) - h * 0.04f * (1f - k)
            text.textSize = h * (0.075f + 0.02f * rnd(slot + 5))
            text.color = 0xFFB9A8C6.toInt()
            text.alpha = (150f * k).toInt().coerceIn(0, 255)
            canvas.drawText(word, wx, wy, text)
        }

        postInvalidateDelayed(110)
    }

    private companion object {
        /** Окно одного слова, мс. */
        const val WHISPER_MS = 4200L
        /**
         * Шёпот из темноты. Вопросы, а не угрозы: угроза требует ответа,
         * вопрос оставляет неловкость - и это неуютнее.
         */
        val WHISPERS = arrayOf(
            "что это?", "чего ты пришёл?", "думаешь, знаешь?",
            "знать? не нужно", "ты уверен?", "кто тебя послал?",
            "тут пусто", "вспомнил?", "не открывай", "ещё раз?",
            "оно тебя помнит", "тише")
    }
}
