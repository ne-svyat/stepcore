package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Пергаментный свиток с «мыслью» приложения.
 *
 * Смена строки сделана не растворением, а маленьким номером: старый свиток
 * ГИБНЕТ (через раз в огне или во льду), из печати и пара рождается новый,
 * раскрывается, и лишь затем проступает текст. Цикл замкнут: каждая новая
 * реплика приходит тем же путём, поэтому смена никогда не выглядит обрывом.
 *
 * Вид не решает, ЧТО показывать и КОГДА - строку присылает экран. Здесь
 * только отрисовка и хореография смены.
 *
 * Кадры тратятся ТОЛЬКО во время номера (около двух секунд). В покое
 * свиток статичен: ни одной лишней перерисовки.
 */
class MotiveScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density

    private var current = ""
    private var pending: String? = null
    private var seqStart = 0L
    private var byFire = true          // способ гибели чередуется
    private var seed = 12345L

    private val parchment = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFE8DCC0.toInt()
    }
    private val scorch = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
    private val shard = Path()

    /** Показать новую строку: старый свиток гибнет, новый рождается. */
    fun show(line: String) {
        if (line == current && pending == null && current.isNotEmpty()) return
        pending = line
        if (current.isEmpty()) {          // самый первый показ - без гибели
            current = line; pending = null
            seqStart = System.currentTimeMillis() - PHASE_UNROLL.toLong()
        } else {
            seqStart = System.currentTimeMillis()
            byFire = !byFire
        }
        seed = (line.hashCode().toLong() and 0xFFFF) + 7
        invalidate()
    }

    private fun rnd(i: Int): Float {
        var z = seed * 6364136223846793005L + i * 1442695040888963407L
        z = (z xor (z ushr 33)) * -0x7ee3623a03d3c83fL
        return ((z ushr 40).toInt() and 0xFFFF) / 65535f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = (System.currentTimeMillis() - seqStart).toFloat()
        val running = t < PHASE_TOTAL

        val rollW = h * 0.30f
        val top = h * 0.16f
        val bottom = h * 0.84f
        val midX = w / 2f

        // Насколько свиток раскрыт и сколько полотна ещё цело.
        var open = 1f
        var alive = 1f
        var textAlpha = 0f
        when {
            t < PHASE_DEATH -> { alive = 1f - t / PHASE_DEATH }
            t < PHASE_REMAINS -> { alive = 0f; open = 0f }
            t < PHASE_SEAL -> { alive = 0f; open = 0f }
            t < PHASE_UNROLL -> {
                val k = (t - PHASE_SEAL) / (PHASE_UNROLL - PHASE_SEAL)
                open = k * k * (3f - 2f * k)
            }
            t < PHASE_TOTAL -> {
                textAlpha = (t - PHASE_UNROLL) / (PHASE_TOTAL - PHASE_UNROLL)
                if (pending != null) { current = pending!!; pending = null }
            }
            else -> textAlpha = 1f
        }
        if (t >= PHASE_UNROLL && pending != null) { current = pending!!; pending = null }

        val leftX = midX - (midX - rollW * 0.55f) * open
        val rightX = midX + (midX - rollW * 0.55f) * open

        // ---- Полотно ----
        if (open > 0.01f && alive > 0f) {
            val burnX = if (byFire) leftX + (rightX - leftX) * alive else rightX
            body.reset()
            body.addRoundRect(leftX, top, if (byFire) burnX else rightX, bottom,
                3f * d, 3f * d, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(body)
            canvas.drawRect(leftX, top, rightX, bottom, parchment)

            // Следы прошлых смертей: подпалины и морозные пятна.
            for (i in 0 until 4) {
                scorch.color = if (i % 2 == 0) 0xFFB79A63.toInt() else 0xFFD8E4EE.toInt()
                scorch.alpha = 55
                canvas.drawCircle(leftX + (rightX - leftX) * (0.12f + 0.24f * i),
                    top + (bottom - top) * (0.25f + 0.5f * rnd(i)),
                    h * (0.05f + 0.05f * rnd(i + 9)), scorch)
            }
            var y = top + (bottom - top) * 0.32f
            while (y < bottom - 2f * d) {
                canvas.drawLine(leftX + 6f * d, y, rightX - 6f * d, y, fiber)
                y += (bottom - top) * 0.34f
            }

            // Лёд: полотно схватывает инеем и трещинами.
            if (!byFire && alive < 1f) {
                val fr = 1f - alive
                fx.color = 0xFFBFE3FF.toInt(); fx.alpha = (150f * fr).toInt().coerceIn(0, 255)
                canvas.drawRect(leftX, top, rightX, bottom, fx)
                fxLine.color = 0xFFFFFFFF.toInt()
                fxLine.alpha = (200f * fr).toInt().coerceIn(0, 255)
                fxLine.strokeWidth = 1.4f * d
                for (i in 0 until 5) {
                    val cx = leftX + (rightX - leftX) * (0.15f + 0.18f * i)
                    canvas.drawLine(cx, top, cx + h * 0.22f * (rnd(i) - 0.5f), bottom, fxLine)
                    canvas.drawLine(cx - h * 0.18f, top + (bottom - top) * 0.5f,
                        cx + h * 0.18f, top + (bottom - top) * (0.35f + 0.3f * rnd(i + 3)), fxLine)
                }
            }
            canvas.restore()
            canvas.drawPath(body, edge)

            // Огонь: раскалённая кромка горения и искры.
            if (byFire && alive < 1f) {
                fx.color = 0xFF2A1C0E.toInt(); fx.alpha = 235
                canvas.drawRect(burnX - 3f * d, top, burnX, bottom, fx)
                fxLine.color = 0xFFFF9A2E.toInt(); fxLine.alpha = 235
                fxLine.strokeWidth = 3f * d
                canvas.drawLine(burnX, top + 1f * d, burnX, bottom - 1f * d, fxLine)
                for (i in 0 until 7) {
                    val g = (rnd(i) + (1f - alive) * 1.6f) % 1f
                    fx.color = if (i % 2 == 0) 0xFFFFC94D.toInt() else 0xFFE2521F.toInt()
                    fx.alpha = (235f * (1f - g)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(burnX + (rnd(i + 5) - 0.3f) * h * 0.25f,
                        bottom - g * (bottom - top) * 1.5f, (1.4f + 1.6f * (1f - g)) * d, fx)
                }
            }
        }

        // ---- Лёд: осколки разлетаются ----
        if (!byFire && t < PHASE_REMAINS && t >= PHASE_DEATH * 0.75f) {
            val k = ((t - PHASE_DEATH * 0.75f) / (PHASE_REMAINS - PHASE_DEATH * 0.75f)).coerceIn(0f, 1f)
            for (i in 0 until 7) {
                val a = Math.PI * (0.15 + 0.7 * i / 6.0)
                val dx = cos(a).toFloat() * w * 0.55f * k
                val dy = -sin(a).toFloat() * h * 0.9f * k + h * 1.6f * k * k
                val sx = leftX + (rightX - leftX) * (0.1f + 0.13f * i)
                val sy = (top + bottom) / 2f
                canvas.save()
                canvas.translate(sx + dx, sy + dy)
                canvas.rotate(360f * k * (if (i % 2 == 0) 1f else -1f))
                shard.reset()
                shard.moveTo(0f, -h * 0.10f); shard.lineTo(h * 0.09f, 0f)
                shard.lineTo(0f, h * 0.11f); shard.lineTo(-h * 0.08f, 0f); shard.close()
                fx.color = 0xFFDCEEFF.toInt(); fx.alpha = (235f * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawPath(shard, fx)
                canvas.restore()
            }
        }

        // ---- Остаток: угли или пар ----
        if (t in PHASE_DEATH..PHASE_SEAL) {
            val k = (t - PHASE_DEATH) / (PHASE_SEAL - PHASE_DEATH)
            for (i in 0 until 6) {
                val gx = w * (0.2f + 0.6f * rnd(i + 20))
                val gy = (top + bottom) / 2f - k * h * 0.7f - rnd(i) * h * 0.2f
                if (byFire) {
                    fx.color = if (i % 2 == 0) 0xFFFFB347.toInt() else 0xFF8A3B12.toInt()
                    fx.alpha = (190f * (1f - k)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(gx, gy, (1.6f + 1.4f * (1f - k)) * d, fx)
                } else {
                    fx.color = 0xFFD8E8F5.toInt()
                    fx.alpha = (110f * (1f - k)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(gx, gy, h * (0.08f + 0.16f * k), fx)
                }
            }
        }

        // ---- Печать и клуб пара: из них рождается новый свиток ----
        if (t in (PHASE_REMAINS - 60f)..(PHASE_UNROLL + 40f)) {
            val k = ((t - (PHASE_REMAINS - 60f)) / ((PHASE_UNROLL + 40f) - (PHASE_REMAINS - 60f)))
                .coerceIn(0f, 1f)
            val pop = if (k < 0.35f) k / 0.35f else 1f
            val fade = if (k > 0.55f) (1f - (k - 0.55f) / 0.45f) else 1f
            val r = h * 0.20f * pop
            fx.color = 0xFFE23636.toInt()
            fx.alpha = (90f * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(midX, (top + bottom) / 2f, r * 2.1f, fx)
            fx.alpha = (215f * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(midX, (top + bottom) / 2f, r, fx)
            fxLine.color = 0xFFFFE9C9.toInt()
            fxLine.alpha = (235f * fade).toInt().coerceIn(0, 255)
            fxLine.strokeWidth = 1.8f * d
            canvas.drawLine(midX - r * 0.55f, (top + bottom) / 2f - r * 0.2f,
                midX + r * 0.55f, (top + bottom) / 2f - r * 0.2f, fxLine)
            canvas.drawLine(midX, (top + bottom) / 2f - r * 0.6f,
                midX, (top + bottom) / 2f + r * 0.5f, fxLine)
            // пар расходится кольцом
            for (i in 0 until 6) {
                val a = Math.toRadians((i * 60f).toDouble())
                val rr = r * (1.2f + 2.4f * k)
                fx.color = 0xFFDCE6F0.toInt()
                fx.alpha = (120f * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawCircle(midX + rr * cos(a).toFloat(),
                    (top + bottom) / 2f + rr * 0.5f * sin(a).toFloat(),
                    h * 0.10f * (1f - 0.4f * k), fx)
            }
        }

        // ---- Валики: резные кольца и колпачки ----
        if (open > 0.01f) {
            for (cx in floatArrayOf(leftX - rollW * 0.02f, rightX + rollW * 0.02f)) {
                canvas.drawRoundRect(cx - rollW * 0.42f, top - h * 0.10f,
                    cx + rollW * 0.42f, bottom + h * 0.10f, rollW * 0.4f, rollW * 0.4f, roller)
                canvas.drawRoundRect(cx - rollW * 0.20f, top - h * 0.07f,
                    cx + rollW * 0.04f, bottom + h * 0.07f, rollW * 0.3f, rollW * 0.3f, rollerLit)
                // колпачки и три резных кольца
                canvas.drawRoundRect(cx - rollW * 0.48f, top - h * 0.14f,
                    cx + rollW * 0.48f, top - h * 0.02f, rollW * 0.2f, rollW * 0.2f, rollerDark)
                canvas.drawRoundRect(cx - rollW * 0.48f, bottom + h * 0.02f,
                    cx + rollW * 0.48f, bottom + h * 0.14f, rollW * 0.2f, rollW * 0.2f, rollerDark)
                for (i in 1..3) {
                    val ry = top + (bottom - top) * (i / 4f)
                    canvas.drawRect(cx - rollW * 0.42f, ry - 0.9f * d,
                        cx + rollW * 0.42f, ry + 0.9f * d, rollerDark)
                }
            }
        }

        // ---- Текст: проступает после раскрытия ----
        if (textAlpha > 0.01f && current.isNotEmpty()) {
            val maxW = (rightX - leftX) - 12f * d
            val maxH = (bottom - top) - 4f * d
            var size = h * 0.30f
            var lines: List<String> = emptyList()
            while (size > 6.5f * d) {
                text.textSize = size
                lines = wrapLines(current, maxW, MAX_LINES)
                val fm = text.fontMetrics
                val lineH = (fm.descent - fm.ascent) * 0.96f
                if (lines.size <= MAX_LINES && lines.all { text.measureText(it) <= maxW } &&
                    lines.size * lineH <= maxH) break
                size -= 0.5f * d
            }
            text.textSize = size
            val k = 0.5f + 0.5f * sin(
                ((System.currentTimeMillis() % 6000L) / 6000.0 * 2.0 * Math.PI)).toFloat()
            text.color = Color.rgb(
                (0x4A + (0x6B - 0x4A) * k).toInt(),
                (0x3B + (0x50 - 0x3B) * k).toInt(),
                (0x22 + (0x2E - 0x22) * k).toInt())
            text.alpha = (238f * textAlpha).toInt().coerceIn(0, 255)
            val fm = text.fontMetrics
            val lineH = (fm.descent - fm.ascent) * 0.96f
            var y2 = (top + bottom) / 2f - lines.size * lineH / 2f - fm.ascent * 0.96f
            for (ln in lines) { canvas.drawText(ln, midX, y2, text); y2 += lineH }
        }

        // Кадры тратятся только во время номера; в покое - редкий перелив.
        if (running) postInvalidateOnAnimation() else postInvalidateDelayed(220)
    }

    /** Перенос по словам: длинное слово не рвётся. */
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
        // Хореография смены, мс от начала номера.
        const val PHASE_DEATH = 520f     // гибель полотна
        const val PHASE_REMAINS = 780f   // угли или пар
        const val PHASE_SEAL = 1120f     // печать и клуб пара
        const val PHASE_UNROLL = 1620f   // новый свиток раскрывается
        const val PHASE_TOTAL = 1980f    // текст проступил
    }
}
