package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Кристалл-гора прогресса (Этап 3a).
 *
 * Гранёная гора с цел-шейдингом: грани разной яркости (свет сверху-слева)
 * дают объём вместо плоской заливки; снежная шапка на вершине; жирный
 * двойной контур (тёмная основа + яркий кант) в анимешном ключе.
 *
 * Данные и смысл НЕ меняются: ходьба (синяя энергия) + бег (красная)
 * заряжают гору снизу вверх; прогресс учитывается до 5 целей (×5) как
 * пять ярусов высоты. Меняется только представление.
 *
 * Зигзаг-лестницы (3b), молния по ним (3c) и алмазы с бликами + луна (3d)
 * добавляются следующими под-этапами поверх этой формы.
 */
class CrystalRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var storedLap = 0
    private var storedFrac = 0f
    private var storedWShare = 0f
    private var storedTotal = 0
    private var storedGoal = 10000
    private var storedYesterday = 0
    private var badgeLap = 0

    private val d = resources.displayMetrics.density

    // Палитра камня (цел-шейдинг, свет сверху-слева -> лево светлее).
    private val stoneLit = 0xFF6D7F98.toInt()
    private val stoneMid = 0xFF58697F.toInt()
    private val stoneShadow = 0xFF33415A.toInt()
    private val stoneDark = 0xFF28344A.toInt()
    private val snowColor = 0xFFE6EEFA.toInt()
    private val edgeColor = 0xFF111C30.toInt()
    private val contourDark = 0xFF080A14.toInt()
    private val kantColor = ContextCompat.getColor(context, R.color.accent_violet_bright)
    private val energyBlue = ContextCompat.getColor(context, R.color.accent_blue)
    private val energyRed = ContextCompat.getColor(context, R.color.accent_red)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * d; color = edgeColor
        strokeJoin = Paint.Join.ROUND
    }
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5.5f * d
        strokeJoin = Paint.Join.ROUND; color = contourDark
    }
    private val kantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f * d
        strokeJoin = Paint.Join.ROUND; color = kantColor
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * d
        color = ContextCompat.getColor(context, R.color.text_main); alpha = 130
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 5f * d), 0f)
    }

    private val mountainPath = Path()
    private val snowPath = Path()
    private val edges = Path()
    private val facets = ArrayList<Pair<Path, Int>>()
    private var bodyTop = 0f
    private var bodyBottom = 0f
    private val stairDim = 0xFF454F66.toInt()
    private val stairLit = 0xFFB9C6DC.toInt()
    private val stairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND
    }
    private val trailPath = Path()
    private val tickPath = Path()
    private var yTrailTop = 0f
    private var yTrailBottom = 0f

    fun setData(walk: Int, run: Int, goal: Int, yesterdayTotal: Int) {
        storedTotal = walk + run
        storedGoal = if (goal > 0) goal else 10000
        storedYesterday = yesterdayTotal
        val p = if (storedTotal <= 0) 0f else storedTotal.toFloat() / storedGoal
        badgeLap = p.toInt()
        val pCapped = p.coerceAtMost(5f)
        storedLap = pCapped.toInt().coerceAtMost(4)
        storedFrac = if (pCapped >= 5f) 1f else pCapped - storedLap
        storedWShare = if (storedTotal > 0) walk.toFloat() / storedTotal else 0f
        invalidate()
    }

    /** Сколько целей ПОЛНОСТЬЮ пройдено сегодня (для бейджа ×N). */
    fun currentLap(): Int = badgeLap

    private fun facet(vararg pts: FloatArray): Path {
        val p = Path()
        p.moveTo(pts[0][0], pts[0][1])
        for (i in 1 until pts.size) p.lineTo(pts[i][0], pts[i][1])
        p.close()
        return p
    }

    /** X-координата ребра горы на высоте y (интерполяция по отрезку). */
    private fun lerpX(y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        if (Math.abs(by - ay) < 0.001f) return ax
        val t = ((y - ay) / (by - ay)).coerceIn(0f, 1f)
        return ax + (bx - ax) * t
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val pad = contourPaint.strokeWidth * 0.5f + 1f * d
        bodyTop = pad
        bodyBottom = h - pad
        val ww = w.toFloat(); val hh = h.toFloat()
        val inner = hh - 2 * pad
        val peakX = ww * 0.52f

        // Ключевые точки силуэта. Это ФОРМА (силуэт), а не измеренный порог:
        // «правильного» числа нет, есть только читаемая гора.
        val peak = floatArrayOf(peakX, pad)
        val ls = floatArrayOf(ww * 0.28f, pad + inner * 0.42f)
        val rs = floatArrayOf(ww * 0.74f, pad + inner * 0.34f)
        val sa = floatArrayOf(peakX, pad + inner * 0.56f)
        val lb = floatArrayOf(pad, bodyBottom)
        val rb = floatArrayOf(ww - pad, bodyBottom)
        val mb = floatArrayOf(peakX, bodyBottom)

        mountainPath.reset()
        mountainPath.moveTo(peak[0], peak[1])
        mountainPath.lineTo(rs[0], rs[1])
        mountainPath.lineTo(rb[0], rb[1])
        mountainPath.lineTo(lb[0], lb[1])
        mountainPath.lineTo(ls[0], ls[1])
        mountainPath.close()

        facets.clear()
        facets.add(facet(peak, ls, sa) to stoneLit)
        facets.add(facet(peak, sa, rs) to stoneShadow)
        facets.add(facet(ls, lb, mb, sa) to stoneMid)
        facets.add(facet(sa, mb, rb, rs) to stoneDark)

        val capL = floatArrayOf(peakX - ww * 0.10f, pad + inner * 0.13f)
        val capR = floatArrayOf(peakX + ww * 0.10f, pad + inner * 0.12f)
        snowPath.reset()
        snowPath.moveTo(peak[0], peak[1])
        snowPath.lineTo(capR[0], capR[1])
        snowPath.lineTo(peakX, pad + inner * 0.18f)
        snowPath.lineTo(capL[0], capL[1])
        snowPath.close()

        edges.reset()
        edges.moveTo(peak[0], peak[1]); edges.lineTo(sa[0], sa[1]); edges.lineTo(mb[0], mb[1])
        edges.moveTo(ls[0], ls[1]); edges.lineTo(sa[0], sa[1]); edges.lineTo(rs[0], rs[1])

        // Зигзаг-тропа: 5 пролётов (×5), сужается к вершине; со ступенями.
        val inset = ww * 0.14f
        yTrailBottom = bodyBottom - inner * 0.04f
        yTrailTop = pad + inner * 0.30f
        trailPath.reset(); tickPath.reset()
        var prevX = 0f; var prevY = 0f
        val flights = 5
        for (k in 0..flights) {
            val ty = yTrailBottom - (yTrailBottom - yTrailTop) * (k.toFloat() / flights)
            val lx = if (ty < ls[1]) lerpX(ty, peak[0], peak[1], ls[0], ls[1])
                     else lerpX(ty, ls[0], ls[1], lb[0], lb[1])
            val rx = if (ty < rs[1]) lerpX(ty, peak[0], peak[1], rs[0], rs[1])
                     else lerpX(ty, rs[0], rs[1], rb[0], rb[1])
            var leftB = lx + inset; var rightB = rx - inset
            if (rightB < leftB) { val m = (lx + rx) / 2f; leftB = m; rightB = m }
            val tx = if (k % 2 == 0) leftB else rightB
            if (k == 0) {
                trailPath.moveTo(tx, ty)
            } else {
                trailPath.lineTo(tx, ty)
                val dx = tx - prevX; val dy = ty - prevY
                val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (len > 0.001f) {
                    val nx = -dy / len; val ny = dx / len
                    val tick = 4f * d
                    for (st in 1..3) {
                        val f = st / 4f
                        val mx = prevX + dx * f; val my = prevY + dy * f
                        tickPath.moveTo(mx - nx * tick, my - ny * tick)
                        tickPath.lineTo(mx + nx * tick, my + ny * tick)
                    }
                }
            }
            prevX = tx; prevY = ty
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val bodyH = bodyBottom - bodyTop
        val tierH = bodyH / 5f

        canvas.save()
        canvas.clipPath(mountainPath)

        // Камень (цел-шейдинг граней).
        for ((path, col) in facets) {
            fillPaint.color = col; fillPaint.alpha = 255
            canvas.drawPath(path, fillPaint)
        }

        // Энергия прогресса: полупрозрачная заливка снизу вверх, ходьба
        // (синяя) слева, бег (красный) справа по доле. Камень видно сквозь.
        if (storedTotal > 0) {
            val splitX = width * storedWShare
            for (i in 0..storedLap) {
                val fillFrac = if (i < storedLap) 1f else storedFrac
                if (fillFrac <= 0f) continue
                val tb = bodyBottom - i * tierH
                val tt = tb - tierH * fillFrac
                fillPaint.color = energyBlue; fillPaint.alpha = 115
                canvas.drawRect(0f, tt, splitX, tb, fillPaint)
                fillPaint.color = energyRed; fillPaint.alpha = 115
                canvas.drawRect(splitX, tt, width.toFloat(), tb, fillPaint)
            }
            fillPaint.alpha = 255
        }

        // Рёбра граней поверх заливки (объём читается).
        canvas.drawPath(edges, edgePaint)

        // Зигзаг-лестницы ×5: тусклые по всей горе, горящие снизу до прогресса.
        run {
            val p01 = ((storedLap + storedFrac) / 5f).coerceIn(0f, 1f)
            val litY = yTrailBottom - (yTrailBottom - yTrailTop) * p01
            stairPaint.color = stairDim; stairPaint.strokeWidth = 3.5f * d
            canvas.drawPath(trailPath, stairPaint)
            tickPaint.color = stairDim
            canvas.drawPath(tickPath, tickPaint)
            if (p01 > 0f) {
                canvas.save()
                canvas.clipRect(0f, litY, width.toFloat(), bodyBottom + 2f * d)
                stairPaint.color = stairLit
                canvas.drawPath(trailPath, stairPaint)
                tickPaint.color = stairLit
                canvas.drawPath(tickPath, tickPaint)
                canvas.restore()
            }
        }
        // Снежная шапка.
        fillPaint.color = snowColor; canvas.drawPath(snowPath, fillPaint)

        // Вчерашний уровень на той же шкале (0..5 целей).
        if (storedYesterday > 0) {
            val yFrac = (storedYesterday.toFloat() / (storedGoal * 5)).coerceIn(0f, 1f)
            val yY = bodyBottom - bodyH * yFrac
            canvas.drawLine(0f, yY, width.toFloat(), yY, ghostPaint)
        }

        canvas.restore()

        // Жирный двойной контур: тёмная основа + яркий кант.
        canvas.drawPath(mountainPath, contourPaint)
        canvas.drawPath(mountainPath, kantPaint)
    }
}
