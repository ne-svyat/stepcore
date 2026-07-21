package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SYNX — сфера-ядро модуля обучения (v193, «Вход в SYNX»).
 *
 * Стеклянный шар со стихией внутри. Стихия отражает СОСТОЯНИЕ обучения
 * (навигатор по нехватке данных, как договорено в концепт-документе):
 *   WATER   — покой: модель сыта, потоки текут медленно;
 *   ELECTRIC— зовёт: есть что улучшить, разряды пляшут;
 *   FIRE    — тревога: не пройдена калибровка / пуст профиль;
 *   ICE     — пауза обучения.
 *
 * Вокруг — японский обод со шкалой-засечками и рунами по сторонам света,
 * плюс наклонённая орбита частиц. Всё живёт: вода вращается, орбита
 * бежит, ядро дышит. Тем ярче/быстрее, чем нужнее человек (intensity).
 *
 * Рисование чистым Canvas в стиле CrystalRingView. Данные и счёт шагов
 * НЕ трогает — это только визуальный вход в модуль.
 */
class SynxOrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Element { WATER, ELECTRIC, FIRE, ICE }

    private val d = resources.displayMetrics.density
    private var element = Element.WATER
    /** 0..1 — насколько настойчиво зовёт (влияет на скорость и яркость). */
    private var intensity = 0.35f
    private var phase = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun setElement(e: Element, intensity01: Float) {
        element = e
        intensity = intensity01.coerceIn(0f, 1f)
        invalidate()
    }

    // палитра по стихии: tint (объём), glow (ободок/линии), hot (ядро/пики)
    private fun palette(): Triple<Int, Int, Int> = when (element) {
        Element.WATER    -> Triple(0xFF124070.toInt(), 0xFF2DAFEB.toInt(), 0xFFA5F0FF.toInt())
        Element.ELECTRIC -> Triple(0xFF264287.toInt(), 0xFF5FA5FF.toInt(), 0xFFCDE8FF.toInt())
        Element.FIRE     -> Triple(0xFF782A14.toInt(), 0xFFFF8C2D.toInt(), 0xFFFFE87D.toInt())
        Element.ICE      -> Triple(0xFF3A708E.toInt(), 0xFF8CDEFF.toInt(), 0xFFE8FAFF.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = (minOf(w, h) / 2f) - 14f * d
        if (r <= 0) return
        val (tint, glow, hot) = palette()

        // фаза бежит; скорость растёт с настойчивостью
        phase += 0.02f + 0.05f * intensity
        val breath = 0.5f + 0.5f * sin(phase.toDouble()).toFloat()

        drawHalo(canvas, cx, cy, r, glow, breath)
        drawRimAndRunes(canvas, cx, cy, r, glow, hot)
        drawSphere(canvas, cx, cy, r, tint)
        canvas.save()
        clipCircle(canvas, cx, cy, r)
        when (element) {
            Element.WATER -> drawWater(canvas, cx, cy, r, glow, hot)
            Element.ELECTRIC -> drawElectric(canvas, cx, cy, r, glow, hot)
            Element.FIRE -> drawFire(canvas, cx, cy, r, glow, hot)
            Element.ICE -> drawIce(canvas, cx, cy, r, glow, hot)
        }
        canvas.restore()
        drawRimLine(canvas, cx, cy, r, glow, breath)
        drawGloss(canvas, cx, cy, r)
        drawOrbit(canvas, cx, cy, r, glow, hot)

        postInvalidateOnAnimation()
    }

    private fun clipCircle(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        c.clipPath(p)
    }

    private fun drawHalo(c: Canvas, cx: Float, cy: Float, r: Float, glow: Int, breath: Float) {
        paint.style = Paint.Style.FILL
        val a = (40 + 40 * breath).toInt()
        paint.shader = RadialGradient(
            cx, cy, r * 1.5f,
            intArrayOf(argb(a, glow), Color.TRANSPARENT),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 1.5f, paint)
        paint.shader = null
    }

    private fun drawSphere(c: Canvas, cx: Float, cy: Float, r: Float, tint: Int) {
        paint.style = Paint.Style.FILL
        // объём: свет сверху-слева -> центр градиента смещён
        paint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
            intArrayOf(lerp(tint, Color.WHITE, 0.35f), tint, darken(tint, 0.35f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }

    private fun drawWater(c: Canvas, cx: Float, cy: Float, r: Float, glow: Int, hot: Int) {
        stroke.strokeWidth = 3f * d
        val arms = 6
        for (k in 0 until arms) {
            val a0 = k * (6.2832f / arms) + phase * 0.4f
            val path = Path()
            var first = true
            var i = 0
            while (i <= 46) {
                val t = i / 46f
                val ang = a0 + t * 4.2f
                val rr = t * (r - 20f * d)
                val x = cx + cos(ang.toDouble()).toFloat() * rr
                val y = cy + sin(ang.toDouble()).toFloat() * rr * 0.92f
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                i++
            }
            stroke.color = lerp(glow, hot, k / arms.toFloat())
            c.drawPath(path, stroke)
        }
        // пузырьки
        stroke.strokeWidth = 1.5f * d
        stroke.color = hot
        var b = 0
        while (b < 22) {
            val ang = (b * 2.39f + phase * 0.3f)
            val rr = (r - 24f * d) * ((b * 7 % 100) / 100f)
            val bx = cx + cos(ang.toDouble()).toFloat() * rr
            val by = cy + sin(ang.toDouble()).toFloat() * rr
            c.drawCircle(bx, by, (2 + b % 4) * d, stroke)
            b++
        }
    }

    private fun drawElectric(c: Canvas, cx: Float, cy: Float, r: Float, glow: Int, hot: Int) {
        // ядро
        paint.style = Paint.Style.FILL; paint.color = hot
        c.drawCircle(cx, cy, 20f * d * (0.85f + 0.15f * sin(phase.toDouble() * 3).toFloat()), paint)
        // плавные дуги-разряды
        stroke.strokeWidth = 2f * d
        stroke.color = lerp(glow, hot, 0.5f)
        for (k in 0 until 8) {
            val a0 = k / 8f * 6.2832f + phase
            val path = Path(); var first = true
            var i = 0
            while (i <= 40) {
                val a = a0 + (1.1f) * i / 40f
                val rr = r * 0.62f * (1f + 0.06f * sin((a * 3).toDouble()).toFloat())
                val x = cx + cos(a.toDouble()).toFloat() * rr
                val y = cy + sin(a.toDouble()).toFloat() * rr
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                i++
            }
            c.drawPath(path, stroke)
        }
    }

    private fun drawFire(c: Canvas, cx: Float, cy: Float, r: Float, glow: Int, hot: Int) {
        paint.style = Paint.Style.FILL
        var f = 0
        while (f < 40) {
            val bx0 = cx + ((f * 53 % 140) - 70) * d
            val hh = (40 + (f * 37 % 110)) * d
            var i = 0f
            var bx = bx0
            while (i < hh) {
                val t = i / hh
                val yy = cy + 40f * d - i + phase * 6f % (20f * d)
                val col = if (t < 0.7f) lerp(glow, hot, t) else lerp(hot, Color.WHITE, (t - 0.7f) / 0.3f)
                paint.color = darken(col, t * 0.3f)
                val rr = maxOf(1f, 7f * (1 - t)) * d
                c.drawCircle(bx, yy, rr, paint)
                bx += ((f + i.toInt()) % 3 - 1) * d
                i += 6f * d
            }
            f++
        }
    }

    private fun drawIce(c: Canvas, cx: Float, cy: Float, r: Float, glow: Int, hot: Int) {
        stroke.strokeWidth = 3f * d
        stroke.color = lerp(glow, hot, 0.5f)
        for (k in 0 until 10) {
            val a = k / 10f * 6.2832f + phase * 0.1f
            val L = (60 + (k * 13 % 80)) * d
            val ex = cx + cos(a.toDouble()).toFloat() * L
            val ey = cy + sin(a.toDouble()).toFloat() * L
            c.drawLine(cx, cy, ex, ey, stroke)
        }
        paint.style = Paint.Style.FILL; paint.color = hot
        c.drawCircle(cx, cy, 16f * d, paint)
    }

    private fun drawRimAndRunes(c: Canvas, cx: Float, cy: Float, r: Float, glow: Int, hot: Int) {
        stroke.color = lerp(glow, Color.WHITE, 0.2f)
        stroke.strokeWidth = 1.5f * d
        c.drawCircle(cx, cy, r + 20f * d, stroke)
        // засечки-шкала
        for (i in 0 until 48) {
            val a = i / 48f * 6.2832f
            val r1 = r + 13f * d
            val r2 = if (i % 4 == 0) r + 26f * d else r + 20f * d
            stroke.strokeWidth = if (i % 4 == 0) 3f * d else 2f * d
            stroke.color = lerp(glow, Color.WHITE, 0.3f)
            c.drawLine(
                cx + cos(a.toDouble()).toFloat() * r1, cy + sin(a.toDouble()).toFloat() * r1,
                cx + cos(a.toDouble()).toFloat() * r2, cy + sin(a.toDouble()).toFloat() * r2, stroke
            )
        }
        // руны по 4 сторонам
        stroke.strokeWidth = 2f * d
        stroke.color = lerp(glow, hot, 0.5f)
        for (k in 0 until 4) {
            val a = k * 1.5708f - 1.5708f
            val rr = r + 42f * d
            val rx = cx + cos(a.toDouble()).toFloat() * rr
            val ry = cy + sin(a.toDouble()).toFloat() * rr
            for (seg in 0 until 3) {
                val yy = ry - 8f * d + seg * 8f * d
                c.drawLine(rx - 9f * d, yy, rx + 9f * d, yy, stroke)
            }
            c.drawLine(rx, ry - 12f * d, rx, ry + 12f * d, stroke)
        }
    }

    private fun drawRimLine(c: Canvas, cx: Float, cy: Float, r: Float, glow: Int, breath: Float) {
        stroke.color = glow
        stroke.strokeWidth = (5f + 3f * breath) * d
        c.drawCircle(cx, cy, r, stroke)
        stroke.color = lerp(glow, Color.WHITE, 0.5f)
        stroke.strokeWidth = 2f * d
        c.drawCircle(cx, cy, r, stroke)
    }

    private fun drawGloss(c: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.45f, r * 0.5f,
            intArrayOf(argb(120, Color.WHITE), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx - r * 0.3f, cy - r * 0.45f, r * 0.5f, paint)
        paint.shader = null
        paint.color = Color.WHITE
        c.drawCircle(cx - r * 0.34f, cy - r * 0.42f, 5f * d, paint)
    }

    private fun drawOrbit(c: Canvas, cx: Float, cy: Float, r: Float, glow: Int, hot: Int) {
        paint.style = Paint.Style.FILL
        val n = 40
        for (i in 0 until n) {
            val a = i / n.toFloat() * 6.2832f + phase * 0.5f
            val x = cx + cos(a.toDouble()).toFloat() * (r + 34f * d)
            val y = cy + sin(a.toDouble()).toFloat() * (r + 34f * d) * 0.42f
            // передняя дуга (sin>0) ярче — перспектива
            val front = sin(a.toDouble()) > -0.2
            paint.color = if (front) lerp(glow, hot, (i % 5) / 5f) else darken(glow, 0.5f)
            c.drawCircle(x, y, (if (front) 2.2f else 1.3f) * d, paint)
        }
    }

    // --- утилиты цвета ---
    private fun lerp(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt()
        )
    }
    private fun darken(a: Int, t: Float) = lerp(a, Color.BLACK, t)
    private fun argb(alpha: Int, c: Int) = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
}

