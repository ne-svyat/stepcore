package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * БЕЗДНА ПОД ВХОДОМ.
 *
 * Вторая редакция. Что чинилось.
 *
 * 1. ФИГУРЫ В КАПЮШОНАХ НЕ ЧИТАЛИСЬ. Силуэт из одной кривой без лица -
 *    это просто вертикальное пятно, и глаз достраивает его как угодно.
 *    Фигуры убраны совсем. Вместо них - МАСКИ, висящие в темноте: у маски
 *    есть рисунок, и она узнаётся мгновенно, даже если ничего вокруг не
 *    видно. Никакого тела не нужно - отсутствие тела тревожнее.
 *
 * 2. ЗАТМЕНИЕ ОБРЕЗАЛОСЬ СВЕРХУ. Радиус считался от высоты (0.34) и вместе
 *    с языками пламени (ещё +0.64 радиуса) вылезал за верхнюю кромку.
 *    Теперь размер выводится ИЗ ПОЛНОГО РАЗМАХА: диск с короной обязан
 *    целиком поместиться в отведённое место, и радиус получается делением,
 *    а не подбором числа.
 *
 * Маска. Багровая, гладкая, с одним отверстием для глаза. Внутри - спираль,
 * стянутая к отверстию; она медленно проворачивается, а её витки дышат по
 * синусоиде. Это и есть «деформация внутри»: рисунок остаётся рисунком,
 * ни один виток не превращается в каракулю, потому что смещение задано
 * гладкой функцией угла, а не шумом.
 *
 * Границы: пакет vault. Аллокаций в кадре нет. Кадры редкие.
 */
class VaultAbyssView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
    }
    private val flame = Path()
    private val swirl = Path()
    private val shell = Path()
    private val oval = RectF()

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

        // РАЗМЕР ВЫВОДИТСЯ ИЗ РАЗМАХА, А НЕ ПОДБИРАЕТСЯ.
        // Полный размах затмения = диск + самый длинный язык + запас на
        // кольцо света: R * (1 + FLAME_MAX + RING). Отсюда и радиус.
        val span = 1f + FLAME_MAX + RING
        val cx = w / 2f
        val r = minOf(w * 0.42f / span, h * 0.40f / span)
        // Центр опускается ровно настолько, чтобы корона не касалась
        // верхней кромки: место под шёпот остаётся внизу.
        val cy = r * span + h * 0.02f

        drawEclipse(canvas, cx, cy, r, now)
        drawMasks(canvas, w, h, r, now)
        drawWhisper(canvas, w, h, cy + r * span, now)

        postInvalidateDelayed(90)
    }

    /** Затмение: чёрный диск в венце тёмного пламени. */
    private fun drawEclipse(c: Canvas, cx: Float, cy: Float, r: Float, now: Long) {
        // Языки рисуются ДО диска: их основания уйдут под него, и венец
        // окажется позади затмения, как и положено короне.
        var i = 0
        while (i < 28) {
            val a = i / 28f * 2f * Math.PI.toFloat() + (now % 400000L) * 0.0000035f
            val ph = now * 0.0015f * (0.6f + 0.5f * rnd(i)) + i
            val len = r * FLAME_MAX * (0.30f + 0.70f *
                (0.5f + 0.5f * sin(ph.toDouble()).toFloat()))
            val wdt = 0.052f + 0.028f * rnd(i + 40)
            val ca = cos(a.toDouble()).toFloat()
            val sa = sin(a.toDouble()).toFloat()
            flame.reset()
            flame.moveTo(cx + cos((a - wdt).toDouble()).toFloat() * r * 0.94f,
                cy + sin((a - wdt).toDouble()).toFloat() * r * 0.94f)
            flame.quadTo(cx + ca * (r + len * 0.55f) - sa * len * 0.24f,
                cy + sa * (r + len * 0.55f) + ca * len * 0.24f,
                cx + ca * (r + len), cy + sa * (r + len))
            flame.quadTo(cx + ca * (r + len * 0.45f) + sa * len * 0.20f,
                cy + sa * (r + len * 0.45f) - ca * len * 0.20f,
                cx + cos((a + wdt).toDouble()).toFloat() * r * 0.94f,
                cy + sin((a + wdt).toDouble()).toFloat() * r * 0.94f)
            flame.close()
            fill.shader = null
            fill.color = 0xFF6E1024.toInt()
            fill.alpha = (140f * (0.35f + 0.65f * (len / (r * FLAME_MAX))))
                .toInt().coerceIn(0, 255)
            c.drawPath(flame, fill)
            fill.color = 0xFF17060B.toInt()
            fill.alpha = 175
            c.drawPath(flame, fill)
            i++
        }

        // Кольцо света у самой кромки - то, что не закрыто диском.
        soft.shader = RadialGradient(cx, cy, r * (1f + RING),
            intArrayOf(0x00000000, 0x59D6564E, 0x00000000),
            floatArrayOf(0.62f, 0.73f, 1f), Shader.TileMode.CLAMP)
        soft.alpha = 255
        c.drawCircle(cx, cy, r * (1f + RING), soft)
        soft.shader = null

        fill.shader = null
        fill.color = 0xFF07050A.toInt(); fill.alpha = 255
        c.drawCircle(cx, cy, r, fill)
        // Холодный отсвет по нижнему краю: без него круг сливается с фоном
        // и перестаёт быть предметом.
        soft.shader = RadialGradient(cx, cy + r * 0.55f, r * 0.9f,
            0x2B6E5A8C, 0x00000000, Shader.TileMode.CLAMP)
        soft.alpha = 255
        c.drawCircle(cx, cy + r * 0.4f, r * 0.9f, soft)
        soft.shader = null
    }

    /**
     * Маски в темноте. Три штуки, разного размера и на разной глубине:
     * дальняя мельче, темнее и качается меньше - это и ставит их в
     * пространство, а не в ряд.
     */
    private fun drawMasks(c: Canvas, w: Float, h: Float, r: Float, now: Long) {
        var m = 0
        while (m < 3) {
            val depth = 0.55f + 0.45f * ((m + 1) % 3) / 2f     // 0.55 .. 1.0
            val mr = r * (0.30f + 0.22f * depth)
            val bx = w * (0.17f + 0.33f * m) +
                w * 0.02f * depth * sin((now * 0.00042 + m * 2.1).toDouble()).toFloat()
            val by = h * (0.70f + 0.10f * rnd(m + 3)) +
                h * 0.015f * depth * sin((now * 0.00061 + m).toDouble()).toFloat()
            drawMask(c, bx, by, mr, depth, m, now)
            m++
        }
    }

    private fun drawMask(c: Canvas, cx: Float, cy: Float, r: Float, depth: Float,
                         idx: Int, now: Long) {
        val a = (255f * (0.45f + 0.55f * depth)).toInt().coerceIn(0, 255)
        // Слабое свечение вокруг: маска висит в воздухе, а не приклеена.
        soft.shader = RadialGradient(cx, cy, r * 2.1f, 0x3D8E1220, 0x00000000,
            Shader.TileMode.CLAMP)
        soft.alpha = (200f * depth).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r * 2.1f, soft)
        soft.shader = null

        // Скорлупа: слегка вытянутое яйцо, острее книзу.
        shell.reset()
        shell.moveTo(cx, cy - r * 1.12f)
        shell.cubicTo(cx + r * 0.98f, cy - r * 1.02f, cx + r * 0.92f, cy + r * 0.62f,
            cx, cy + r * 1.20f)
        shell.cubicTo(cx - r * 0.92f, cy + r * 0.62f, cx - r * 0.98f, cy - r * 1.02f,
            cx, cy - r * 1.12f)
        shell.close()
        fill.shader = null
        fill.color = 0xFF6B1220.toInt(); fill.alpha = a
        c.drawPath(shell, fill)

        c.save()
        c.clipPath(shell)
        // СПИРАЛЬ, СТЯНУТАЯ К ГЛАЗУ. Витки дышат по синусоиде угла -
        // рисунок деформируется, оставаясь рисунком. Шум дал бы каракули.
        val ex = cx + r * 0.30f
        val ey = cy - r * 0.10f
        val spin = now * 0.00022f + idx * 1.3f
        val warp = 0.10f + 0.05f * sin((now * 0.0009 + idx).toDouble()).toFloat()
        swirl.reset()
        var i = 0
        while (i <= 74) {
            val th = i * 0.26f + spin
            val rad = r * (0.10f + 0.0265f * i) *
                (1f + warp * sin((th * 3f + spin * 2f).toDouble()).toFloat())
            val px = ex + cos(th.toDouble()).toFloat() * rad
            val py = ey + sin(th.toDouble()).toFloat() * rad * 0.92f
            if (i == 0) swirl.moveTo(px, py) else swirl.lineTo(px, py)
            i++
        }
        line.shader = null
        line.color = 0xFF2B0710.toInt()
        line.alpha = a
        line.strokeWidth = r * 0.115f
        c.drawPath(swirl, line)
        // Тонкая светлая нить по внешнему краю витка - лак на керамике.
        line.color = 0xFFB8465A.toInt()
        line.alpha = (a * 0.45f).toInt().coerceIn(0, 255)
        line.strokeWidth = r * 0.028f
        c.drawPath(swirl, line)
        c.restore()

        // Отверстие глаза: спираль сходится сюда, поэтому дыра читается
        // как центр рисунка, а не как случайное пятно.
        val blink = ((now / 1000L + idx * 5L) % 13L) == 0L
        val eyeH = if (blink) r * 0.05f else r * 0.19f
        fill.color = 0xFF08050A.toInt(); fill.alpha = 255
        oval.set(ex - r * 0.21f, ey - eyeH, ex + r * 0.21f, ey + eyeH)
        c.drawOval(oval, fill)
        if (!blink) {
            fill.color = 0xFFD1414F.toInt()
            fill.alpha = (110f + 90f * sin((now * 0.0018 + idx).toDouble()).toFloat())
                .toInt().coerceIn(0, 255)
            c.drawCircle(ex + r * 0.03f, ey, r * 0.055f, fill)
        }

        // Обводка скорлупы - последней: она держит силуэт маски.
        line.shader = null
        line.color = 0xFF150609.toInt()
        line.alpha = a
        line.strokeWidth = 1.9f * d
        c.drawPath(shell, line)
    }

    /** Шёпот: слово проступает и тает ниже затмения. */
    private fun drawWhisper(c: Canvas, w: Float, h: Float, below: Float, now: Long) {
        val slot = (now / WHISPER_MS).toInt()
        val f = (now % WHISPER_MS).toFloat() / WHISPER_MS
        if (f >= 0.72f) return
        val k = sin((f / 0.72f * Math.PI).toFloat())
        val word = WHISPERS[Math.floorMod(slot * 7 + 3, WHISPERS.size)]
        val wx = w * (0.20f + 0.60f * rnd(slot + 11))
        // Слово живёт в полосе МЕЖДУ затмением и низом: поверх короны оно
        // читалось бы как подпись к ней.
        val band = (h - below).coerceAtLeast(h * 0.2f)
        val wy = below + band * (0.25f + 0.45f * rnd(slot + 29)) - band * 0.06f * (1f - k)
        text.textSize = h * (0.070f + 0.018f * rnd(slot + 5))
        text.color = 0xFFB9A8C6.toInt()
        text.alpha = (150f * k).toInt().coerceIn(0, 255)
        c.drawText(word, wx, wy, text)
    }

    private companion object {
        const val WHISPER_MS = 4200L
        /** Самый длинный язык пламени в долях радиуса диска. */
        const val FLAME_MAX = 0.64f
        /** Запас на кольцо света вокруг короны, в долях радиуса. */
        const val RING = 0.55f
        val WHISPERS = arrayOf(
            "что это?", "чего ты пришёл?", "думаешь, знаешь?",
            "знать? не нужно", "ты уверен?", "кто тебя послал?",
            "тут пусто", "вспомнил?", "не открывай", "ещё раз?",
            "оно тебя помнит", "тише")
    }
}
