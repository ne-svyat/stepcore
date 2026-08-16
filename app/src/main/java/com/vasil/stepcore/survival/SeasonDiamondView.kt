package com.vasil.stepcore.survival

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.vasil.stepcore.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * КОЛЕСО СЕЗОНОВ.
 *
 * Было: ромб из четырёх одинаковых треугольников, один из них залит
 * янтарём. Он сообщал ровно один бит - «сейчас вот этот сектор», - и
 * ничего не говорил о том, ЧТО это за сезон. Янтарное лето и янтарная
 * зима отличались только положением, то есть требовали помнить порядок.
 *
 * Стало: у каждого сезона свой цвет и свой знак, и знак живёт:
 *   ЗИМА  - снежинки медленно опускаются, лучи инея по кромке;
 *   ВЕСНА - росток тянется вверх и раскрывает лист, вокруг летит пыльца;
 *   ЛЕТО  - солнце с лучами разной длины, над травой дрожит воздух;
 *   ОСЕНЬ - лист срывается, кружится и падает, за ним следующий.
 *
 * Живёт только ТЕКУЩИЙ сектор. Остальные три притушены и неподвижны: если
 * шевелится всё, не видно ничего, а знак на 52dp читается лишь когда он
 * один. Это же экономит кадры - в покое рисуется один маленький знак.
 *
 * Смена сезона - не подмена цвета: по кромке колеса от старого сектора к
 * новому бежит огонёк, и новый сектор наливается ему вслед. Природа не
 * переключается мгновенно, и колесо не должно.
 *
 * Аллокаций в кадре нет: пути и массивы живут в полях.
 */
class SeasonDiamondView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var season = -1          // -1 = нет активной экспедиции
    private var prevSeason = -1
    private var switchedAt = 0L
    private val d = resources.displayMetrics.density

    private val dimFill = ContextCompat.getColor(context, R.color.surface2)
    private val dimStroke = ContextCompat.getColor(context, R.color.axis_dim)

    /** Тон сезона: холодный, живой, жаркий, увядающий. */
    private val seasonFill = intArrayOf(
        0xFF2E4A63.toInt(), 0xFF2C5A38.toInt(), 0xFF6B5320.toInt(), 0xFF5E3320.toInt())
    private val seasonEdge = intArrayOf(
        0xFFAFE0FF.toInt(), 0xFF6FE08A.toInt(), 0xFFFFD05A.toInt(), 0xFFFF9A4D.toInt())

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * d
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val quadPath = Path()
    private val markPath = Path()
    private val vTop = FloatArray(2)
    private val vRight = FloatArray(2)
    private val vBottom = FloatArray(2)
    private val vLeft = FloatArray(2)

    /** @param s 0=зима 1=весна 2=лето 3=осень, -1=нет активной экспедиции. */
    fun setSeason(s: Int) {
        if (s == season) return
        prevSeason = season
        season = s
        switchedAt = System.currentTimeMillis()
        invalidate()
    }

    private fun rnd(i: Int): Float {
        var z = (i * 6364136223846793005L) + 1442695040888963407L
        z = (z xor (z ushr 33)) * -0x7ee3623a03d3c83fL
        return ((z ushr 40).toInt() and 0xFFFF) / 65535f
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val now = System.currentTimeMillis()
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 2f * d

        vTop[0] = cx; vTop[1] = cy - r
        vRight[0] = cx + r; vRight[1] = cy
        vBottom[0] = cx; vBottom[1] = cy + r
        vLeft[0] = cx - r; vLeft[1] = cy

        // Переход: 0 - только что сменили, 1 - новый сезон полностью вступил.
        val turn = if (switchedAt == 0L) 1f
        else ((now - switchedAt).toFloat() / TURN_MS).coerceIn(0f, 1f)

        drawQuadrant(canvas, 0, cx, cy, r, vLeft, vTop, turn, now)
        drawQuadrant(canvas, 1, cx, cy, r, vTop, vRight, turn, now)
        drawQuadrant(canvas, 2, cx, cy, r, vRight, vBottom, turn, now)
        drawQuadrant(canvas, 3, cx, cy, r, vBottom, vLeft, turn, now)

        // Огонёк по кромке: бежит от прежнего сектора к нынешнему.
        if (turn < 1f && season >= 0 && prevSeason >= 0) {
            val from = quadCenterAngle(prevSeason)
            var delta = quadCenterAngle(season) - from
            // Идём коротким путём: колесо не должно объезжать круг.
            if (delta > Math.PI) delta -= 2.0 * Math.PI
            if (delta < -Math.PI) delta += 2.0 * Math.PI
            val a = from + delta * turn
            val px = cx + (cos(a) * r * 0.86).toFloat()
            val py = cy + (sin(a) * r * 0.86).toFloat()
            glowPaint.shader = RadialGradient(px, py, r * 0.42f,
                seasonEdge[season], 0x00000000, Shader.TileMode.CLAMP)
            glowPaint.alpha = (255f * (1f - turn * 0.4f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(px, py, r * 0.42f, glowPaint)
            glowPaint.shader = null
        }

        // Кадры тратятся, только пока есть что показывать.
        if (season >= 0 || turn < 1f) postInvalidateDelayed(90)
    }

    /** Угол середины сектора: зима слева-сверху и дальше по часовой. */
    private fun quadCenterAngle(idx: Int): Double = when (idx) {
        0 -> Math.PI * 1.25
        1 -> Math.PI * 1.75
        2 -> Math.PI * 0.25
        else -> Math.PI * 0.75
    }

    private fun drawQuadrant(
        canvas: Canvas, idx: Int, cx: Float, cy: Float, r: Float,
        a: FloatArray, b: FloatArray, turn: Float, now: Long,
    ) {
        val active = idx == season
        val leaving = idx == prevSeason && turn < 1f
        // Новый сектор наливается, прежний гаснет - оба в одном кадре.
        val k = when {
            active -> turn
            leaving -> 1f - turn
            else -> 0f
        }

        quadPath.reset()
        quadPath.moveTo(cx, cy)
        quadPath.lineTo(a[0], a[1])
        quadPath.lineTo(b[0], b[1])
        quadPath.close()

        fillPaint.shader = null
        fillPaint.color = dimFill
        fillPaint.alpha = 255
        canvas.drawPath(quadPath, fillPaint)
        if (k > 0f) {
            fillPaint.color = seasonFill[idx]
            fillPaint.alpha = (255f * k).toInt().coerceIn(0, 255)
            canvas.drawPath(quadPath, fillPaint)
        }
        strokePaint.color = if (k > 0.5f) seasonEdge[idx] else dimStroke
        strokePaint.alpha = 255
        canvas.drawPath(quadPath, strokePaint)

        if (k <= 0.02f) return

        // Знак сезона живёт внутри своего сектора и никуда из него не
        // вылезает: клип по тому же пути, что и заливка.
        canvas.save()
        canvas.clipPath(quadPath)
        val mx = cx + (cos(quadCenterAngle(idx)) * r * 0.52).toFloat()
        val my = cy + (sin(quadCenterAngle(idx)) * r * 0.52).toFloat()
        val ms = r * 0.34f
        markPaint.alpha = (255f * k).toInt().coerceIn(0, 255)
        markPaint.color = seasonEdge[idx]
        when (idx) {
            0 -> drawWinter(canvas, mx, my, ms, now, k)
            1 -> drawSpring(canvas, mx, my, ms, now, k)
            2 -> drawSummer(canvas, mx, my, ms, now, k)
            else -> drawAutumn(canvas, mx, my, ms, now, k)
        }
        canvas.restore()
    }

    /** Зима: снежинки опускаются, каждая своим ходом. */
    private fun drawWinter(c: Canvas, x: Float, y: Float, s: Float, now: Long, k: Float) {
        markPaint.strokeWidth = 1.1f * d
        var i = 0
        while (i < 4) {
            var t = (now * 0.00016f * (0.7f + 0.5f * rnd(i)) + rnd(i + 9)) % 1f
            if (t < 0f) t += 1f
            val fx = x + s * (rnd(i + 3) - 0.5f) * 1.6f +
                s * 0.12f * sin((now * 0.0012 + i).toDouble()).toFloat()
            val fy = y - s * 0.9f + s * 1.8f * t
            val rr = s * (0.12f + 0.07f * rnd(i + 5))
            markPaint.alpha = (235f * k * (1f - t * 0.6f)).toInt().coerceIn(0, 255)
            var b = 0
            while (b < 3) {
                val a = Math.PI * b / 3.0
                c.drawLine(fx - (cos(a) * rr).toFloat(), fy - (sin(a) * rr).toFloat(),
                    fx + (cos(a) * rr).toFloat(), fy + (sin(a) * rr).toFloat(), markPaint)
                b++
            }
            i++
        }
    }

    /** Весна: росток тянется и раскрывает лист, летит пыльца. */
    private fun drawSpring(c: Canvas, x: Float, y: Float, s: Float, now: Long, k: Float) {
        val cyc = ((now % 5200L).toFloat() / 5200f)
        val up = (cyc / 0.7f).coerceAtMost(1f)
        markPaint.strokeWidth = 1.6f * d
        markPaint.alpha = (255f * k).toInt().coerceIn(0, 255)
        markPath.reset()
        markPath.moveTo(x, y + s * 0.8f)
        markPath.quadTo(x + s * 0.10f, y + s * (0.8f - 0.8f * up),
            x, y + s * (0.8f - 1.5f * up))
        c.drawPath(markPath, markPaint)
        // Лист раскрывается только когда стебель дорос.
        if (up > 0.55f) {
            val open = ((up - 0.55f) / 0.45f).coerceIn(0f, 1f)
            markPath.reset()
            markPath.moveTo(x, y + s * (0.8f - 1.1f * up))
            markPath.quadTo(x + s * 0.55f * open, y + s * (0.55f - 1.2f * up),
                x + s * 0.06f, y + s * (0.25f - 1.2f * up))
            c.drawPath(markPath, markPaint)
        }
        // Пыльца: точки всплывают и гаснут.
        var i = 0
        while (i < 3) {
            var t = (now * 0.00022f + rnd(i + 20)) % 1f
            if (t < 0f) t += 1f
            markPaint.alpha = (200f * k * (1f - t)).toInt().coerceIn(0, 255)
            c.drawPoint(x + s * (rnd(i + 30) - 0.5f) * 1.4f, y + s * 0.8f - s * 1.7f * t,
                markPaint)
            i++
        }
    }

    /** Лето: солнце с лучами разной длины, над травой дрожит воздух. */
    private fun drawSummer(c: Canvas, x: Float, y: Float, s: Float, now: Long, k: Float) {
        val pulse = 0.5f + 0.5f * sin((now * 0.0016).toDouble()).toFloat()
        glowPaint.shader = RadialGradient(x, y - s * 0.15f, s * 1.2f,
            seasonEdge[2] and 0x66FFFFFF, 0x00000000, Shader.TileMode.CLAMP)
        glowPaint.alpha = (170f * k).toInt().coerceIn(0, 255)
        c.drawCircle(x, y - s * 0.15f, s * 1.2f, glowPaint)
        glowPaint.shader = null
        fillPaint.color = seasonEdge[2]
        fillPaint.alpha = (255f * k).toInt().coerceIn(0, 255)
        c.drawCircle(x, y - s * 0.15f, s * (0.28f + 0.03f * pulse), fillPaint)
        markPaint.strokeWidth = 1.3f * d
        markPaint.alpha = (235f * k).toInt().coerceIn(0, 255)
        var i = 0
        while (i < 8) {
            val a = Math.PI * 2.0 * i / 8.0 + now * 0.00012
            val len = s * (0.45f + 0.16f * (if (i % 2 == 0) 1f else 0.5f)) *
                (0.9f + 0.2f * sin((now * 0.0022 + i).toDouble()).toFloat())
            c.drawLine(x + (cos(a) * s * 0.36).toFloat(), y - s * 0.15f + (sin(a) * s * 0.36).toFloat(),
                x + (cos(a) * len).toFloat(), y - s * 0.15f + (sin(a) * len).toFloat(), markPaint)
            i++
        }
    }

    /** Осень: лист срывается, кружится и падает, за ним следующий. */
    private fun drawAutumn(c: Canvas, x: Float, y: Float, s: Float, now: Long, k: Float) {
        var i = 0
        while (i < 3) {
            var t = (now * 0.00018f + i * 0.34f) % 1f
            if (t < 0f) t += 1f
            val lx = x + s * (rnd(i + 40) - 0.5f) * 1.2f +
                s * 0.45f * sin((t * 6.0 + i).toDouble()).toFloat()
            val ly = y - s * 0.9f + s * 1.9f * t * t
            c.save()
            c.translate(lx, ly)
            c.rotate(t * 420f * (if (i % 2 == 0) 1f else -1f))
            fillPaint.color = seasonEdge[3]
            fillPaint.alpha = (240f * k * (1f - t * 0.5f)).toInt().coerceIn(0, 255)
            markPath.reset()
            markPath.moveTo(0f, -s * 0.20f)
            markPath.quadTo(s * 0.22f, 0f, 0f, s * 0.20f)
            markPath.quadTo(-s * 0.22f, 0f, 0f, -s * 0.20f)
            markPath.close()
            c.drawPath(markPath, fillPaint)
            markPaint.strokeWidth = 0.9f * d
            markPaint.alpha = (200f * k).toInt().coerceIn(0, 255)
            c.drawLine(0f, -s * 0.20f, 0f, s * 0.20f, markPaint)
            c.restore()
            i++
        }
    }

    private companion object {
        /** Смена сезона, мс: огонёк успевает добежать по кромке. */
        const val TURN_MS = 900f
    }
}
