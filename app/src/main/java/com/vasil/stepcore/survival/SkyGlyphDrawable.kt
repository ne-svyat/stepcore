package com.vasil.stepcore.survival

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Погодный значок дня в стиле «дудл»: солнце, облако, осадки, туман, ветер.
 *
 * Рисуется от руки — с сеяным дрожанием линии (SplitMix-подобный генератор),
 * поэтому два одинаковых по погоде дня выглядят чуть по-разному, но КАЖДЫЙ
 * день всегда выглядит одинаково: дрожание детерминировано номером дня,
 * а не временем отрисовки. Иначе значок дёргался бы при каждой перерисовке
 * списка.
 *
 * Статичен: ни таймеров, ни подписок. В журнале сотня таких значков —
 * анимировать их означало бы сотню подписчиков на общий механизм.
 */
class SkyGlyphDrawable(
    private val cloud: Int,   // 0 ясно - 1 переменная - 2 пасмурно
    private val wind: Int,    // 0 тихо - 1 ветрено - 2 сильный - 3 буря
    private val precip: Int,  // 0 нет - 1 дождь - 2 ливень - 3 мокрый снег - 4 снег
    private val fog: Boolean,
    private val color: Int,
    private val density: Float,
    private val seed: Long,
    private val sizeDp: Float = 30f,
) : Drawable() {

    private val px = sizeDp * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        color = this@SkyGlyphDrawable.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = this@SkyGlyphDrawable.color
    }
    private var state = seed * -0x61c8864680b583ebL + 0x9E3779B9L

    override fun getIntrinsicWidth(): Int = px.toInt()
    override fun getIntrinsicHeight(): Int = px.toInt()

    /** Сеяный шум: одинаков для одного и того же дня, разный для разных. */
    private fun j(amp: Float): Float {
        state = state * 6364136223846793005L + 1442695040888963407L
        val u = ((state ushr 33).toInt() and 0xFFFF) / 65535f
        return (u - 0.5f) * 2f * amp * density
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, amp: Float = 0.7f) {
        val p = Path()
        p.moveTo(x1 + j(amp), y1 + j(amp))
        val mx = (x1 + x2) / 2f; val my = (y1 + y2) / 2f
        p.quadTo(mx + j(amp * 1.6f), my + j(amp * 1.6f), x2 + j(amp), y2 + j(amp))
        c.drawPath(p, paint)
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path()
        var a = 0.0
        val steps = 14
        while (a < 2 * Math.PI + 0.01) {
            val rr = r + j(0.8f)
            val x = cx + (rr * Math.cos(a)).toFloat()
            val y = cy + (rr * Math.sin(a)).toFloat()
            if (a == 0.0) p.moveTo(x, y) else p.lineTo(x, y)
            a += 2 * Math.PI / steps
        }
        p.close()
        c.drawPath(p, paint)
    }

    private fun cloudShape(c: Canvas, cx: Float, cy: Float, w: Float) {
        val h = w * 0.42f
        val p = Path()
        p.moveTo(cx - w / 2 + j(0.6f), cy + h / 2 + j(0.6f))
        p.quadTo(cx - w * 0.62f + j(1f), cy - h * 0.2f, cx - w * 0.22f + j(0.8f), cy - h * 0.45f)
        p.quadTo(cx - w * 0.05f + j(1f), cy - h * 1.05f, cx + w * 0.20f + j(0.8f), cy - h * 0.5f)
        p.quadTo(cx + w * 0.60f + j(1f), cy - h * 0.55f, cx + w * 0.52f + j(0.8f), cy + h * 0.2f)
        p.quadTo(cx + w * 0.58f + j(1f), cy + h * 0.6f, cx + w / 2 + j(0.6f), cy + h / 2 + j(0.6f))
        p.close()
        c.drawPath(p, paint)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = px * 0.18f

        if (fog) {
            // Туман: три плывущие горизонтали. Всё остальное в нём тонет.
            for (i in 0..2) {
                val y = cy - px * 0.14f + i * px * 0.16f
                line(canvas, cx - px * 0.34f, y, cx + px * 0.34f, y, 1.1f)
            }
            return
        }

        // Небо
        when (cloud) {
            0 -> {
                ring(canvas, cx, cy - px * 0.05f, r * 1.1f)
                // лучи
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    val x1 = cx + (r * 1.7f * Math.cos(a)).toFloat()
                    val y1 = cy - px * 0.05f + (r * 1.7f * Math.sin(a)).toFloat()
                    val x2 = cx + (r * 2.3f * Math.cos(a)).toFloat()
                    val y2 = cy - px * 0.05f + (r * 2.3f * Math.sin(a)).toFloat()
                    line(canvas, x1, y1, x2, y2, 0.5f)
                }
            }
            1 -> {
                ring(canvas, cx + px * 0.14f, cy - px * 0.18f, r * 0.8f)
                cloudShape(canvas, cx - px * 0.04f, cy + px * 0.02f, px * 0.56f)
            }
            else -> cloudShape(canvas, cx, cy - px * 0.06f, px * 0.62f)
        }

        // Осадки
        if (precip in 1..2) {
            val n = if (precip == 2) 4 else 3
            for (i in 0 until n) {
                val x = cx - px * 0.20f + i * px * 0.13f
                line(canvas, x, cy + px * 0.16f, x - px * 0.05f, cy + px * 0.36f, 0.6f)
            }
        } else if (precip >= 3) {
            val n = if (precip == 4) 4 else 3
            for (i in 0 until n) {
                val x = cx - px * 0.20f + i * px * 0.13f
                val y = cy + px * 0.24f + j(1.2f)
                canvas.drawCircle(x, y, 1.3f * density, fill)
            }
        }

        // Ветер: штрихи сбоку. Буря — длиннее и с загибом.
        if (wind >= 2) {
            val n = if (wind == 3) 3 else 2
            for (i in 0 until n) {
                val y = cy - px * 0.16f + i * px * 0.13f
                line(canvas, cx - px * 0.42f, y, cx - px * 0.16f, y, 0.9f)
            }
            if (wind == 3) {
                line(canvas, cx + px * 0.24f, cy + px * 0.30f, cx + px * 0.40f, cy + px * 0.18f, 0.9f)
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
