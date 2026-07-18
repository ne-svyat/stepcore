package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Пергаментный свиток с «мыслью» приложения.
 *
 * Смена строки - это маленький фильм: старый свиток гибнет (через раз в
 * огне или во льду), из печати и пара рождается новый, раскрывается, и
 * лишь затем построчно проступает текст. Номер длится около четырёх с
 * половиной секунд: быстрая смена читалась дёшево, медленная даёт время
 * разглядеть и огонь, и иней.
 *
 * Кадры тратятся только во время номера. В покое свиток статичен, идёт
 * лишь редкий перелив чернил.
 */
class MotiveScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density

    private var current = ""
    private var pending: String? = null
    private var seqStart = 0L
    private var byFire = true
    private var seed = 12345L

    private val parchment = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFE8DCC0.toInt()
    }
    private val stain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val roller = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF7A5A32.toInt()
    }
    private val rollerLit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFB08A50.toInt()
    }
    private val rollerDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF4E3A20.toInt()
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = 0xFF6B5C3C.toInt()
    }
    private val fiber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
        color = 0xFFC9BC98.toInt(); alpha = 150
    }
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF4A3B22.toInt()
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
    }
    private val body = Path()
    private val tmp = Path()

    fun show(line: String) {
        if (line == current && pending == null && current.isNotEmpty()) return
        pending = line
        if (current.isEmpty()) {
            current = line; pending = null
            seqStart = System.currentTimeMillis() - PHASE_BIRTH.toLong()
        } else {
            seqStart = System.currentTimeMillis()
            byFire = !byFire
        }
        seed = (line.hashCode().toLong() and 0xFFFF) + 7
        invalidate()
    }

    /** Устойчивый шум: узор гибели одинаков в каждом кадре одного номера. */
    private fun rnd(i: Int): Float {
        var z = seed * 6364136223846793005L + i * 1442695040888963407L
        z = (z xor (z ushr 33)) * -0x7ee3623a03d3c83fL
        return ((z ushr 40).toInt() and 0xFFFF) / 65535f
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()
        val t = (now - seqStart).toFloat()
        val running = t < PHASE_TOTAL

        val rollW = h * 0.20f
        val top = h * 0.13f
        val bottom = h * 0.87f
        val midX = w / 2f
        val fullL = rollW * 0.55f
        val fullR = w - rollW * 0.55f

        var open = 1f
        var alive = 1f
        var textK = 0f
        when {
            t < PHASE_DEATH -> alive = 1f - smooth((t / PHASE_DEATH).coerceIn(0f, 1f))
            t < PHASE_SEAL -> { alive = 0f; open = 0f }
            t < PHASE_BIRTH -> {
                alive = 0f
                val k = ((t - PHASE_SEAL) / (PHASE_BIRTH - PHASE_SEAL)).coerceIn(0f, 1f)
                // упругий довод: валики чуть проскакивают и возвращаются
                open = smooth(k) * (1f + 0.06f * sin((k * Math.PI * 2.0).toFloat()))
                if (pending != null) { current = pending!!; pending = null }
            }
            t < PHASE_TOTAL -> textK = (t - PHASE_BIRTH) / (PHASE_TOTAL - PHASE_BIRTH)
            else -> textK = 1f
        }
        val leftX = midX - (midX - fullL) * open.coerceIn(0f, 1.1f)
        val rightX = midX + (fullR - midX) * open.coerceIn(0f, 1.1f)

        // ================= ПОЛОТНО =================
        if (open > 0.01f && alive > 0.001f) {
            val burnX = if (byFire) leftX + (rightX - leftX) * alive else rightX
            body.reset()
            if (byFire) {
                // Рваная кромка горения, а не прямой срез.
                body.moveTo(leftX, top)
                body.lineTo(burnX + h * 0.03f * (rnd(1) - 0.5f), top)
                var y = top
                var i = 0
                while (y < bottom) {
                    val jag = h * 0.055f * (rnd(i + 2) - 0.4f)
                    y += (bottom - top) / 7f
                    body.lineTo(burnX + jag, y.coerceAtMost(bottom))
                    i++
                }
                body.lineTo(leftX, bottom)
            } else {
                body.addRoundRect(leftX, top, rightX, bottom, 3f * d, 3f * d, Path.Direction.CW)
            }
            body.close()

            canvas.save()
            canvas.clipPath(body)
            canvas.drawRect(leftX, top, rightX, bottom, parchment)
            for (i in 0 until 5) {
                stain.color = if (i % 2 == 0) 0xFFB79A63.toInt() else 0xFFD8E4EE.toInt()
                stain.alpha = 50
                canvas.drawCircle(leftX + (rightX - leftX) * (0.10f + 0.2f * i),
                    top + (bottom - top) * (0.2f + 0.6f * rnd(i)),
                    h * (0.04f + 0.05f * rnd(i + 9)), stain)
            }
            var fy = top + (bottom - top) * 0.22f
            while (fy < bottom - 2f * d) {
                canvas.drawLine(leftX + 7f * d, fy, rightX - 7f * d, fy, fiber)
                fy += (bottom - top) * 0.19f
            }

            if (!byFire) {
                val fr = 1f - alive
                // Иней ползёт от обоих краёв кристаллами.
                fx.color = 0xFFBFE3FF.toInt(); fx.alpha = (120f * fr).toInt().coerceIn(0, 255)
                canvas.drawRect(leftX, top, rightX, bottom, fx)
                fxLine.color = 0xFFFFFFFF.toInt(); fxLine.strokeWidth = 1.3f * d
                for (i in 0 until 14) {
                    val side = if (i % 2 == 0) 0f else 1f
                    val reach = (rightX - leftX) * 0.5f * fr
                    val cx = if (side == 0f) leftX + reach * rnd(i + 20)
                             else rightX - reach * rnd(i + 30)
                    val cy = top + (bottom - top) * rnd(i + 40)
                    fxLine.alpha = (200f * fr).toInt().coerceIn(0, 255)
                    val rr = h * 0.06f * (0.5f + rnd(i + 50))
                    for (k in 0 until 3) {
                        val a = Math.toRadians((k * 60f + 15f).toDouble())
                        canvas.drawLine(cx - rr * cos(a).toFloat(), cy - rr * sin(a).toFloat(),
                            cx + rr * cos(a).toFloat(), cy + rr * sin(a).toFloat(), fxLine)
                    }
                }
                // Ветвящиеся трещины прорастают по мере промерзания.
                if (fr > 0.45f) {
                    val ck = ((fr - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    fxLine.color = 0xFFEAF6FF.toInt(); fxLine.alpha = 235
                    fxLine.strokeWidth = 1.8f * d
                    for (i in 0 until 4) {
                        var x = leftX + (rightX - leftX) * (0.2f + 0.2f * i)
                        var y2 = top
                        val steps = (6 * ck).toInt().coerceAtLeast(1)
                        for (s in 0 until steps) {
                            val nx = x + h * 0.10f * (rnd(i * 7 + s) - 0.5f)
                            val ny = y2 + (bottom - top) / 6f
                            canvas.drawLine(x, y2, nx, ny, fxLine)
                            if (s == 2) canvas.drawLine(nx, ny, nx + h * 0.16f, ny + h * 0.10f, fxLine)
                            x = nx; y2 = ny
                        }
                    }
                }
            }
            canvas.restore()
            canvas.drawPath(body, edge)

            // Огонь: обугливание, языки пламени, дым, искры.
            if (byFire && alive < 1f) {
                fx.color = 0xFF1C1208.toInt(); fx.alpha = 240
                canvas.drawRect(burnX - h * 0.05f, top, burnX + h * 0.02f, bottom, fx)
                for (i in 0 until 9) {
                    val ly = top + (bottom - top) * (i / 8f)
                    val fl = 0.55f + 0.45f * sin((now * 0.012f + i).toFloat())
                    tmp.reset()
                    tmp.moveTo(burnX - h * 0.02f, ly)
                    tmp.quadTo(burnX + h * 0.10f * fl, ly - h * 0.10f * fl,
                        burnX + h * 0.04f, ly - h * 0.20f * fl)
                    tmp.quadTo(burnX + h * 0.02f * fl, ly - h * 0.06f, burnX - h * 0.02f, ly)
                    fx.color = if (i % 2 == 0) 0xFFFF9A2E.toInt() else 0xFFE2521F.toInt()
                    fx.alpha = 225
                    canvas.drawPath(tmp, fx)
                }
                fxLine.color = 0xFFFFE08A.toInt(); fxLine.alpha = 245; fxLine.strokeWidth = 2.6f * d
                canvas.drawLine(burnX, top + 2f * d, burnX, bottom - 2f * d, fxLine)
                for (i in 0 until 12) {
                    val g = (rnd(i) + (1f - alive) * 1.3f) % 1f
                    fx.color = if (i % 3 == 0) 0xFFFFF0B0.toInt() else 0xFFFFB347.toInt()
                    fx.alpha = (240f * (1f - g)).toInt().coerceIn(0, 255)
                    val ex = burnX + (rnd(i + 5) - 0.35f) * h * 0.5f + g * h * 0.25f
                    val ey = bottom - g * (bottom - top) * 1.9f + h * 0.35f * g * g
                    canvas.drawCircle(ex, ey, (1.2f + 1.8f * (1f - g)) * d, fx)
                }
                for (i in 0 until 5) {
                    val g = (rnd(i + 60) + (1f - alive) * 0.9f) % 1f
                    fx.color = 0xFF6B6459.toInt()
                    fx.alpha = (90f * (1f - g)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(burnX + (rnd(i + 70) - 0.5f) * h * 0.4f,
                        top - g * h * 0.7f, h * (0.10f + 0.22f * g), fx)
                }
            }
        }

        // ================= ЛЁД: ОСКОЛКИ =================
        if (!byFire && t in (PHASE_DEATH * 0.82f)..PHASE_SEAL) {
            val k = ((t - PHASE_DEATH * 0.82f) / (PHASE_SEAL - PHASE_DEATH * 0.82f)).coerceIn(0f, 1f)
            for (i in 0 until 12) {
                val a = Math.PI * (0.08 + 0.84 * i / 11.0)
                val sp = 0.6f + 0.5f * rnd(i + 80)
                val dx = cos(a).toFloat() * w * 0.6f * k * sp
                val dy = -sin(a).toFloat() * h * 1.0f * k * sp + h * 2.0f * k * k
                canvas.save()
                canvas.translate(fullL + (fullR - fullL) * (0.06f + 0.08f * i) + dx,
                    (top + bottom) / 2f + dy)
                canvas.rotate(420f * k * (if (i % 2 == 0) 1f else -1f))
                val s = h * (0.07f + 0.05f * rnd(i + 90))
                tmp.reset()
                tmp.moveTo(0f, -s); tmp.lineTo(s * 0.8f, -s * 0.1f)
                tmp.lineTo(s * 0.25f, s); tmp.lineTo(-s * 0.7f, s * 0.2f); tmp.close()
                fx.color = 0xFFDCEEFF.toInt(); fx.alpha = (240f * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawPath(tmp, fx)
                fx.color = 0xFFFFFFFF.toInt(); fx.alpha = (200f * (1f - k)).toInt().coerceIn(0, 255)
                tmp.reset()
                tmp.moveTo(0f, -s); tmp.lineTo(s * 0.8f, -s * 0.1f); tmp.lineTo(0f, 0f); tmp.close()
                canvas.drawPath(tmp, fx)
                canvas.restore()
            }
            for (i in 0 until 6) {
                fx.color = 0xFFD8E8F5.toInt()
                fx.alpha = (90f * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawCircle(w * (0.15f + 0.14f * i), bottom - k * h * 0.5f,
                    h * (0.08f + 0.20f * k), fx)
            }
        }

        // ================= ПЕЧАТЬ И ПАР =================
        if (t in (PHASE_DEATH + 120f)..(PHASE_BIRTH - 80f)) {
            val k = ((t - (PHASE_DEATH + 120f)) / ((PHASE_BIRTH - 80f) - (PHASE_DEATH + 120f)))
                .coerceIn(0f, 1f)
            val pop = if (k < 0.30f) smooth(k / 0.30f) else 1f
            val fade = if (k > 0.62f) (1f - (k - 0.62f) / 0.38f) else 1f
            val cy = (top + bottom) / 2f
            val r = h * 0.26f * pop * (if (k > 0.62f) (1f - (k - 0.62f) * 0.6f) else 1f)
            fx.color = 0xFFE23636.toInt(); fx.alpha = (80f * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(midX, cy, r * 2.3f, fx)
            fxLine.color = 0xFFE23636.toInt(); fxLine.alpha = (240f * fade).toInt().coerceIn(0, 255)
            fxLine.strokeWidth = 2.4f * d
            canvas.save(); canvas.rotate(t * 0.06f, midX, cy)
            canvas.drawCircle(midX, cy, r, fxLine)
            fxLine.strokeWidth = 1.4f * d
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45f).toDouble())
                canvas.drawLine(midX + r * 0.72f * cos(a).toFloat(), cy + r * 0.72f * sin(a).toFloat(),
                    midX + r * 1.02f * cos(a).toFloat(), cy + r * 1.02f * sin(a).toFloat(), fxLine)
            }
            canvas.restore()
            canvas.save(); canvas.rotate(-t * 0.09f, midX, cy)
            fxLine.strokeWidth = 1.8f * d
            canvas.drawCircle(midX, cy, r * 0.62f, fxLine)
            canvas.restore()
            fxLine.color = 0xFFFFE9C9.toInt(); fxLine.alpha = (250f * fade).toInt().coerceIn(0, 255)
            fxLine.strokeWidth = 2f * d
            canvas.drawLine(midX - r * 0.42f, cy - r * 0.16f, midX + r * 0.42f, cy - r * 0.16f, fxLine)
            canvas.drawLine(midX, cy - r * 0.52f, midX, cy + r * 0.42f, fxLine)
            for (i in 0 until 10) {
                val a = Math.toRadians((i * 36f + k * 40f).toDouble())
                val rr = r * (1.1f + 3.0f * k)
                fx.color = 0xFFDCE6F0.toInt()
                fx.alpha = (130f * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawCircle(midX + rr * cos(a).toFloat(), cy + rr * 0.55f * sin(a).toFloat(),
                    h * 0.11f * (1f - 0.35f * k), fx)
            }
            for (i in 0 until 4) {
                val g = (k * 1.4f + i * 0.25f) % 1f
                fx.alpha = (90f * (1f - g) * fade).toInt().coerceIn(0, 255)
                canvas.drawCircle(midX + (rnd(i + 100) - 0.5f) * h * 0.5f, cy - g * h * 1.1f,
                    h * (0.09f + 0.16f * g), fx)
            }
        }

        // ================= ВАЛИКИ =================
        if (open > 0.01f) {
            for (cx in floatArrayOf(leftX - rollW * 0.02f, rightX + rollW * 0.02f)) {
                canvas.drawRoundRect(cx - rollW * 0.42f, top - h * 0.06f,
                    cx + rollW * 0.42f, bottom + h * 0.06f, rollW * 0.4f, rollW * 0.4f, roller)
                canvas.drawRoundRect(cx - rollW * 0.20f, top - h * 0.04f,
                    cx + rollW * 0.04f, bottom + h * 0.04f, rollW * 0.3f, rollW * 0.3f, rollerLit)
                canvas.drawRoundRect(cx - rollW * 0.50f, top - h * 0.10f,
                    cx + rollW * 0.50f, top - h * 0.01f, rollW * 0.2f, rollW * 0.2f, rollerDark)
                canvas.drawRoundRect(cx - rollW * 0.50f, bottom + h * 0.01f,
                    cx + rollW * 0.50f, bottom + h * 0.10f, rollW * 0.2f, rollW * 0.2f, rollerDark)
                for (i in 1..3) {
                    val ry = top + (bottom - top) * (i / 4f)
                    canvas.drawRect(cx - rollW * 0.42f, ry - 0.9f * d,
                        cx + rollW * 0.42f, ry + 0.9f * d, rollerDark)
                }
            }
            // Блик пробегает по свежему полотну сразу после раскрытия.
            if (t in PHASE_SEAL..(PHASE_BIRTH + 260f)) {
                val k = ((t - PHASE_SEAL) / ((PHASE_BIRTH + 260f) - PHASE_SEAL)).coerceIn(0f, 1f)
                fx.color = 0xFFFFFFFF.toInt(); fx.alpha = (70f * (1f - k)).toInt().coerceIn(0, 255)
                val gx = leftX + (rightX - leftX) * k
                canvas.drawRect(gx - h * 0.10f, top, gx + h * 0.10f, bottom, fx)
            }
        }

        // ================= ТЕКСТ =================
        if (textK > 0.01f && current.isNotEmpty()) {
            val maxW = (rightX - leftX) - 14f * d
            val maxH = (bottom - top) - 6f * d
            var size = h * 0.26f
            var lines: List<String> = emptyList()
            while (size > 6.5f * d) {
                text.textSize = size
                lines = wrapLines(current, maxW, MAX_LINES)
                val fm = text.fontMetrics
                val lineH = (fm.descent - fm.ascent) * 0.94f
                if (lines.size <= MAX_LINES && lines.all { text.measureText(it) <= maxW } &&
                    lines.size * lineH <= maxH) break
                size -= 0.5f * d
            }
            text.textSize = size
            val shim = 0.5f + 0.5f * sin(((now % 6000L) / 6000.0 * 2.0 * Math.PI)).toFloat()
            text.color = Color.rgb(
                (0x4A + (0x6B - 0x4A) * shim).toInt(),
                (0x3B + (0x50 - 0x3B) * shim).toInt(),
                (0x22 + (0x2E - 0x22) * shim).toInt())
            val fm = text.fontMetrics
            val lineH = (fm.descent - fm.ascent) * 0.94f
            var y2 = (top + bottom) / 2f - lines.size * lineH / 2f - fm.ascent * 0.94f
            // Строки проявляются снизу вверх - как проступающие чернила.
            for ((i, ln) in lines.withIndex()) {
                val share = (lines.size - i).toFloat() / lines.size
                val a = ((textK - (1f - share) * 0.5f) / 0.5f).coerceIn(0f, 1f)
                text.alpha = (238f * a).toInt().coerceIn(0, 255)
                canvas.drawText(ln, midX, y2, text)
                y2 += lineH
            }
        }

        if (running) postInvalidateOnAnimation() else postInvalidateDelayed(220)
    }

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
        const val MAX_LINES = 5
        // Хореография номера, мс. Медленнее прежнего вчетверо.
        const val PHASE_DEATH = 1500f
        const val PHASE_SEAL = 2400f
        const val PHASE_BIRTH = 3500f
        const val PHASE_TOTAL = 4400f
    }
}
