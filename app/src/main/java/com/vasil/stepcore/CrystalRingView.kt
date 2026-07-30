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
    private val trailX = FloatArray(6)
    private val trailY = FloatArray(6)
    private val branchPath = Path()
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val gemBody = ArrayList<Path>()
    private val gemDark = ArrayList<Path>()
    private val gemGlint = ArrayList<Path>()
    // Пять алмазов - пять целей (×1..×5). Число вынесено, чтобы шкала
    // достижений и ярусы заливки никогда не разъехались.
    private val GEM_COUNT = 5
    private val sparkX = FloatArray(GEM_COUNT)
    private val sparkY = FloatArray(GEM_COUNT)
    private val gemCx = FloatArray(GEM_COUNT)
    private val gemCy = FloatArray(GEM_COUNT)
    private val gemSz = FloatArray(GEM_COUNT)
    private val gemDeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF39435A.toInt()
    }
    private val gemGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val crownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val bloodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ================== ×5 «Кровавый венец» ==================
    // Состояние включается ТОЛЬКО когда взяты все пять целей. Всё, что гора
    // рисовала до этого (тропа, ступени, молния по тропе, ветви, алмазы,
    // снежная шапка, вчерашний уровень, искры), продолжает рисоваться -
    // здесь только перекраска и добавленные слои.
    // Числа ниже - интенсивности картинки, а не измеренные пороги.
    private val plasmaLit = 0xFF8A4FE8.toInt()
    private val plasmaMid = 0xFF632EC4.toInt()
    private val plasmaShadow = 0xFF3A1886.toInt()
    private val plasmaDark = 0xFF240E5C.toInt()
    private val magenta = 0xFFFF4BC8.toInt()
    private val hotCore = 0xFFFFE8FF.toInt()
    private val bloodHi = 0xFFE02B33.toInt()
    private val bloodMid = 0xFFB6262B.toInt()
    private val bloodLo = 0xFF6B1014.toInt()
    private val glitchCyan = 0xFF3AE8E0.toInt()
    private val veinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
    }
    private val boltPath = Path()
    private val eyeAlmond = Path()
    private val eyePupil = Path()
    private val bloodTrail = Path()
    private val haloPath = Path()
    private val tonguePath = Path()
    private val tipX = FloatArray(7)
    private val tipY = FloatArray(7)
    private val auroraPath = Path()
    private var peakXf = 0f
    private var peakYf = 0f
    private var moonCx = 0f
    private var moonCy = 0f
    private var moonR = 0f
    private val moonPath = Path()
    private val moonCraters = Path()
    private val moonCraterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFD8C08A.toInt(); alpha = 170
    }
    private val gemLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF9FC0FF.toInt() }
    private val gemDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF3D7EFF.toInt() }
    private val gemGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val gemEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0xFF0B1730.toInt() }
    private val moonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFF1CF.toInt() }
    private val moonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = 0xFFD9B45F.toInt() }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }

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

    private fun addGem(cx: Float, cy: Float, sz: Float, idx: Int) {
        val top = floatArrayOf(cx, cy - sz)
        val right = floatArrayOf(cx + sz * 0.72f, cy - sz * 0.12f)
        val bot = floatArrayOf(cx, cy + sz)
        val left = floatArrayOf(cx - sz * 0.72f, cy - sz * 0.12f)
        gemBody.add(facet(top, right, bot, left))
        gemDark.add(facet(top, right, bot))
        gemGlint.add(facet(top, left, floatArrayOf(cx - sz * 0.15f, cy - sz * 0.25f)))
        sparkX[idx] = cx + sz * 1.05f
        sparkY[idx] = cy - sz * 1.05f
    }

    private fun sparkle(canvas: Canvas, x: Float, y: Float, s: Float) {
        val p = Path()
        p.moveTo(x, y - s)
        p.lineTo(x + s * 0.28f, y - s * 0.28f); p.lineTo(x + s, y)
        p.lineTo(x + s * 0.28f, y + s * 0.28f); p.lineTo(x, y + s)
        p.lineTo(x - s * 0.28f, y + s * 0.28f); p.lineTo(x - s, y)
        p.lineTo(x - s * 0.28f, y - s * 0.28f); p.close()
        canvas.drawPath(p, sparkPaint)
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
            trailX[k] = tx; trailY[k] = ty
            prevX = tx; prevY = ty
        }

        // Короткие ветви-искры в поворотах тропы (мерцают в onDraw).
        branchPath.reset()
        for (k in 1 until flights) {
            val bx = trailX[k]; val by = trailY[k]
            val dir = if (bx < peakX) -1f else 1f
            val bl = ww * 0.10f
            branchPath.moveTo(bx, by)
            branchPath.lineTo(bx + dir * bl * 0.6f, by - bl * 0.25f)
            branchPath.lineTo(bx + dir * bl, by - bl * 0.55f)
        }

        // Луна-полумесяц в небе над вершиной (символ «шёл весь день»).
        val mcx = ww * 0.84f; val mcy = pad + inner * 0.10f; val mr = ww * 0.095f
        moonCx = mcx; moonCy = mcy; moonR = mr
        moonPath.reset()
        moonPath.addCircle(mcx, mcy, mr, Path.Direction.CW)
        moonCraters.reset()
        moonCraters.addCircle(mcx - mr * 0.34f, mcy - mr * 0.26f, mr * 0.22f, Path.Direction.CW)
        moonCraters.addCircle(mcx + mr * 0.12f, mcy + mr * 0.32f, mr * 0.16f, Path.Direction.CW)
        moonCraters.addCircle(mcx - mr * 0.04f, mcy - mr * 0.54f, mr * 0.11f, Path.Direction.CW)

        // Глаз хищника на диске луны (используется только при ×5) и ручей
        // крови из внутреннего угла. Геометрия статична - в кадре меняются
        // только ширина зрачка и капли, поэтому в onDraw нет аллокаций.
        val ew = mr * 0.92f; val eh = mr * 0.50f
        eyeAlmond.reset()
        eyeAlmond.moveTo(mcx - ew, mcy)
        eyeAlmond.quadTo(mcx, mcy - eh * 1.30f, mcx + ew, mcy)
        eyeAlmond.quadTo(mcx, mcy + eh * 1.10f, mcx - ew, mcy)
        eyeAlmond.close()

        val sx = mcx - ew * 0.86f; val sy = mcy + eh * 0.30f
        bloodTrail.reset()
        bloodTrail.moveTo(sx, sy)
        bloodTrail.quadTo(sx - mr * 0.10f, mcy + mr * 0.70f, sx - mr * 0.04f, mcy + mr * 1.15f)
        bloodTrail.quadTo(sx + mr * 0.04f, mcy + mr * 1.40f, sx - mr * 0.02f, mcy + mr * 1.60f)

        // Алмазы целей: по одному на ярус, поочерёдно слева и справа от
        // тропы, вписаны в ширину склона на своей высоте.
        peakXf = peak[0]; peakYf = peak[1]
        gemBody.clear(); gemDark.clear(); gemGlint.clear()
        for (i in 0 until GEM_COUNT) {
            val gy = bodyBottom - (i + 0.55f) * ((bodyBottom - pad) / GEM_COUNT)
            val lx = if (gy < ls[1]) lerpX(gy, peak[0], peak[1], ls[0], ls[1])
                     else lerpX(gy, ls[0], ls[1], lb[0], lb[1])
            val rx = if (gy < rs[1]) lerpX(gy, peak[0], peak[1], rs[0], rs[1])
                     else lerpX(gy, rs[0], rs[1], rb[0], rb[1])
            val mid = (lx + rx) * 0.5f
            val halfW = (rx - lx) * 0.5f
            val side = if (i % 2 == 0) -1f else 1f
            val sz = (ww * 0.055f - i * ww * 0.004f).coerceAtMost(halfW * 0.55f)
            gemCx[i] = mid + side * halfW * 0.40f
            gemCy[i] = gy
            gemSz[i] = sz
            addGem(gemCx[i], gy, sz, i)
        }
    }


    // ---------- ×5: вспомогательное ----------

    /** Смешение двух цветов по каналам. */
    private fun blend(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * tt).toInt()
        val g = (ag + (bg - ag) * tt).toInt()
        val bl = (ab + (bb - ab) * tt).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Плазменный двойник грани камня: соответствие задано, а не вычислено. */
    private fun plasmaOf(stone: Int): Int = when (stone) {
        stoneLit -> plasmaLit
        stoneMid -> plasmaMid
        stoneShadow -> plasmaShadow
        else -> plasmaDark
    }

    /** Волна плазмы идёт снизу вверх сквозь камень. Вызывается под клипом горы. */
    private fun drawPlasmaWave(c: Canvas, tms: Long, pulse: Float) {
        val bh = bodyBottom - bodyTop
        val band = bh * 0.30f
        var g = (tms * 0.00020f) % 1.35f
        if (g < 0f) g += 1.35f
        val yc = bodyBottom - g * (bh + band)
        val steps = 14
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            val y = yc - band * 0.5f + band * t
            val s = Math.sin(t * Math.PI).toFloat()
            fillPaint.color = magenta
            fillPaint.alpha = (175f * s * s * (0.60f + 0.40f * pulse)).toInt().coerceIn(0, 255)
            c.drawRect(0f, y, width.toFloat(), y + band / steps + 2f * d, fillPaint)
        }
        fillPaint.alpha = 255
    }

    /** Жилы плазмы текут по рёбрам граней - тот же путь edges, что и в камне. */
    private fun drawVeins(c: Canvas, pulse: Float) {
        veinPaint.color = magenta
        veinPaint.strokeWidth = 6.5f * d
        veinPaint.alpha = (70f + 60f * pulse).toInt().coerceIn(0, 255)
        c.drawPath(edges, veinPaint)
        veinPaint.strokeWidth = 2.4f * d
        veinPaint.alpha = (160f + 95f * pulse).toInt().coerceIn(0, 255)
        c.drawPath(edges, veinPaint)
        veinPaint.color = hotCore
        veinPaint.strokeWidth = 1.1f * d
        veinPaint.alpha = (130f + 100f * pulse).toInt().coerceIn(0, 255)
        c.drawPath(edges, veinPaint)
    }

    /** Луна = багровый глаз хищника, из которого течёт кровь. */
    private fun drawMoonEye(c: Canvas, tms: Long, bh: Float) {
        val pulse = 0.5f + 0.5f * Math.sin(tms * 0.0016).toFloat()
        val eh = moonR * 0.50f

        // Гало.
        gemGlowPaint.color = bloodHi
        gemGlowPaint.alpha = (70f + 45f * pulse).toInt().coerceIn(0, 255)
        c.drawCircle(moonCx, moonCy, moonR * (1.45f + 0.12f * pulse), gemGlowPaint)

        // Диск: к краю темнее.
        for (i in 0 until 6) {
            moonFillPaint.color = blend(bloodLo, bloodHi, i / 5f)
            c.drawCircle(moonCx, moonCy, moonR * (1f - 0.12f * i), moonFillPaint)
        }

        // Миндаль глаза.
        moonFillPaint.color = 0xFF2A0608.toInt()
        c.drawPath(eyeAlmond, moonFillPaint)

        // Радужка лучами - только внутри миндаля.
        c.save()
        c.clipPath(eyeAlmond)
        moonStrokePaint.strokeWidth = 1.2f * d
        for (k in 0 until 24) {
            val a = k / 24f * 2f * Math.PI.toFloat()
            moonStrokePaint.color = blend(bloodMid, bloodHi, (k % 3) / 2f)
            moonStrokePaint.alpha = 165
            c.drawLine(
                moonCx, moonCy,
                moonCx + moonR * 0.95f * Math.cos(a.toDouble()).toFloat(),
                moonCy + moonR * 0.55f * Math.sin(a.toDouble()).toFloat(),
                moonStrokePaint)
        }
        c.restore()
        moonStrokePaint.alpha = 255

        // Зрачок-щель: сужается и расширяется, как у хищника.
        val pw = moonR * (0.10f + 0.07f * pulse)
        eyePupil.reset()
        eyePupil.moveTo(moonCx, moonCy - eh * 0.95f)
        eyePupil.lineTo(moonCx + pw, moonCy)
        eyePupil.lineTo(moonCx, moonCy + eh * 0.95f)
        eyePupil.lineTo(moonCx - pw, moonCy)
        eyePupil.close()
        moonFillPaint.color = 0xFF060203.toInt()
        c.drawPath(eyePupil, moonFillPaint)

        // Блик смещён от центра - глаз живой, а не нарисованный.
        bloodPaint.color = 0xFFFFE1E1.toInt()
        bloodPaint.alpha = 190
        c.drawOval(moonCx - pw * 2.0f, moonCy - eh * 0.55f, moonCx - pw * 0.5f, moonCy - eh * 0.10f, bloodPaint)
        bloodPaint.alpha = 255

        // Веко.
        moonStrokePaint.color = bloodLo
        moonStrokePaint.strokeWidth = 2.0f * d
        c.drawPath(eyeAlmond, moonStrokePaint)

        // Ручей крови из внутреннего угла.
        veinPaint.color = bloodLo
        veinPaint.strokeWidth = 5.2f * d
        veinPaint.alpha = 235
        c.drawPath(bloodTrail, veinPaint)
        veinPaint.color = bloodMid
        veinPaint.strokeWidth = 2.6f * d
        veinPaint.alpha = 255
        c.drawPath(bloodTrail, veinPaint)

        // Тяжёлые капли с хвостом срываются вниз.
        for (k in 0 until 3) {
            var g = (tms * 0.00030f) + k * 0.333f
            g -= Math.floor(g.toDouble()).toFloat()
            val dx = moonCx - moonR * 0.80f + moonR * 0.05f * k
            val dy = moonCy + moonR * 1.55f + g * bh * 0.42f
            val rr = (4.4f - 1.9f * g) * d
            bloodPaint.color = bloodMid
            bloodPaint.alpha = (240f * (1f - g * 0.85f)).toInt().coerceIn(0, 255)
            c.drawCircle(dx, dy, rr, bloodPaint)
            c.drawOval(dx - rr * 0.55f, dy - rr * 2.6f, dx + rr * 0.55f, dy, bloodPaint)
        }
    }

    /** Вершина: живая энергия. Ни одной окружности - ядро рваное, ореол гуляет. */
    private fun drawSummitEnergy(c: Canvas, tms: Long) {
        val bh = bodyBottom - bodyTop
        val ph = (tms % 100000L) * 0.001f
        val pulse = 0.5f + 0.5f * Math.sin((ph * 3.2f).toDouble()).toFloat()

        // Ореол: 20 точек с гуляющим радиусом вместо круга.
        haloPath.reset()
        val n = 20
        for (i in 0 until n) {
            val a = i / n.toFloat() * 2f * Math.PI.toFloat()
            val rr = bh * (0.085f + 0.055f *
                Math.abs(Math.sin(a * 2.5 + ph * 4.0)).toFloat())
            val x = peakXf + rr * Math.cos(a.toDouble()).toFloat()
            val y = peakYf + rr * Math.sin(a.toDouble()).toFloat() * 0.85f
            if (i == 0) haloPath.moveTo(x, y) else haloPath.lineTo(x, y)
        }
        haloPath.close()
        gemGlowPaint.color = magenta
        gemGlowPaint.alpha = (95f + 65f * pulse).toInt().coerceIn(0, 255)
        c.drawPath(haloPath, gemGlowPaint)

        // Семь языков плазмы, каждый дышит в своей фазе.
        for (k in 0 until 7) {
            val a = -Math.PI.toFloat() / 2f + (k - 3) * 0.42f
            val br = 0.5f + 0.5f * Math.sin((ph * 5.4f + k * 1.3f).toDouble()).toFloat()
            val len = bh * (0.10f + 0.11f * br)
            val tx = peakXf + len * Math.cos(a.toDouble()).toFloat() * 1.15f
            val ty = peakYf + len * Math.sin(a.toDouble()).toFloat()
            tipX[k] = tx; tipY[k] = ty
            val mx = (peakXf + tx) * 0.5f + ((k * 37 % 7) - 3) * 1.6f * d
            val my = (peakYf + ty) * 0.5f
            tonguePath.reset()
            tonguePath.moveTo(peakXf, peakYf)
            tonguePath.quadTo(mx, my, tx, ty)
            crownPaint.color = magenta
            crownPaint.strokeWidth = 6f * d
            crownPaint.alpha = (80f + 70f * br).toInt().coerceIn(0, 255)
            c.drawPath(tonguePath, crownPaint)
            crownPaint.strokeWidth = 2.4f * d
            crownPaint.alpha = (185f + 70f * br).toInt().coerceIn(0, 255)
            c.drawPath(tonguePath, crownPaint)
            crownPaint.color = hotCore
            crownPaint.strokeWidth = 1.2f * d
            crownPaint.alpha = (160f + 95f * br).toInt().coerceIn(0, 255)
            c.drawPath(tonguePath, crownPaint)
        }

        // Разряд между кончиками: вспыхивает, а не горит постоянно.
        val fr = tms / 70L
        if ((fr % 4L) == 0L) {
            val i = ((fr / 4L) % 7L).toInt()
            val j = ((fr / 4L + 3L) % 7L).toInt()
            crownPaint.color = hotCore
            crownPaint.strokeWidth = 1.6f * d
            crownPaint.alpha = 215
            val mx = (tipX[i] + tipX[j]) * 0.5f + 5f * d
            val my = (tipY[i] + tipY[j]) * 0.5f - 4f * d
            tonguePath.reset()
            tonguePath.moveTo(tipX[i], tipY[i])
            tonguePath.quadTo(mx, my, tipX[j], tipY[j])
            c.drawPath(tonguePath, crownPaint)
        }

        // Ядро: рваный многоугольник, а не точка.
        haloPath.reset()
        for (i in 0 until 9) {
            val a = i / 9f * 2f * Math.PI.toFloat()
            val rr = bh * (0.030f + 0.012f * Math.abs(Math.sin(a * 3.0 + ph * 8.0)).toFloat())
            val x = peakXf + rr * Math.cos(a.toDouble()).toFloat()
            val y = peakYf + rr * Math.sin(a.toDouble()).toFloat() * 0.8f
            if (i == 0) haloPath.moveTo(x, y) else haloPath.lineTo(x, y)
        }
        haloPath.close()
        bloodPaint.color = hotCore
        bloodPaint.alpha = 240
        c.drawPath(haloPath, bloodPaint)
        bloodPaint.alpha = 255

        // Угли уходят вверх.
        for (k in 0 until 9) {
            var g = ph * 1.6f + k / 9f
            g -= Math.floor(g.toDouble()).toFloat()
            val ex = peakXf + (((k * 53) % 21) - 10) / 10f * bh * 0.10f
            val ey = peakYf - g * bh * 0.22f
            val s = (2.6f - 1.7f * g) * d
            bloodPaint.color = blend(hotCore, magenta, g)
            bloodPaint.alpha = (230f * (1f - g)).toInt().coerceIn(0, 255)
            c.drawCircle(ex, ey, s, bloodPaint)
        }
        bloodPaint.alpha = 255
    }

    /**
     * Мелкий глитч: помеха в сигнале, не украшение. Срабатывает редко и
     * живёт пару кадров - если он заметен постоянно, он сломан.
     */
    private fun drawGlitch(c: Canvas, tms: Long) {
        val slot = tms / 140L
        if ((slot % 7L) != 0L) return
        val r = (slot * 1103515245L + 12345L) ushr 16
        val off = (if ((r and 1L) == 0L) 1f else -1f) * 2.5f * d

        // Расслоение контура по цвету.
        glitchPaint.style = Paint.Style.STROKE
        glitchPaint.strokeWidth = 1.8f * d
        glitchPaint.color = magenta; glitchPaint.alpha = 120
        c.save(); c.translate(off, 0f); c.drawPath(mountainPath, glitchPaint); c.restore()
        glitchPaint.color = glitchCyan; glitchPaint.alpha = 120
        c.save(); c.translate(-off, 0f); c.drawPath(mountainPath, glitchPaint); c.restore()

        // Срезы - только по телу горы.
        c.save()
        c.clipPath(mountainPath)
        glitchPaint.style = Paint.Style.FILL
        for (i in 0 until 3) {
            val rr = (r shr (i * 5)) and 0xFFL
            val yy = bodyTop + (bodyBottom - bodyTop) * (rr.toFloat() / 255f)
            val hh = (2f + (rr % 6L).toFloat()) * d
            val dxs = (if ((rr and 2L) == 0L) 1f else -1f) * (4f + (rr % 11L).toFloat()) * d
            glitchPaint.color = if ((rr and 4L) == 0L) magenta else glitchCyan
            glitchPaint.alpha = 45 + (rr % 50L).toInt()
            c.drawRect(dxs, yy, width.toFloat() + dxs, yy + hh, glitchPaint)
        }
        c.restore()
    }

    /**
     * Разряд бьёт в гору. Включается с ПЕРВОЙ взятой цели - гора ожила.
     * Раз в 3.2 с и виден 260 мс: это вспышка, а не постоянное горение,
     * поэтому не мозолит глаз и не жрёт кадры.
     * Бьёт в узел тропы - в то место, куда человек уже дошёл.
     */
    private fun drawStrike(c: Canvas, tms: Long, maxed: Boolean) {
        val period = 3200L
        val phase = (tms % period).toFloat()
        if (phase > 260f) return
        val slot = tms / period
        val r = (slot * 6364136223846793005L) ushr 24
        val k = phase / 260f
        // Резкий фронт и спад - как у настоящей вспышки, а не ровное свечение.
        val br = if (k < 0.12f) k / 0.12f else (1f - (k - 0.12f) / 0.88f).coerceAtLeast(0f)

        val node = (r % 5L).toInt() + 1
        val tx = trailX[node]; val ty = trailY[node]
        val sx = tx + (((r shr 8) % 100L).toFloat() / 100f - 0.5f) * width * 0.5f

        boltPath.reset()
        boltPath.moveTo(sx, 0f)
        val segs = 6
        for (i in 1..segs) {
            val f = i / segs.toFloat()
            val jx = (((r shr (i * 4)) % 100L).toFloat() / 100f - 0.5f) * width * 0.14f * (1f - f)
            boltPath.lineTo(sx + (tx - sx) * f + jx, ty * f)
        }

        val col = if (maxed) magenta else kantColor
        lightPaint.color = col
        lightPaint.strokeWidth = 9f * d
        lightPaint.alpha = (70f * br).toInt().coerceIn(0, 255)
        c.drawPath(boltPath, lightPaint)
        lightPaint.strokeWidth = 3.5f * d
        lightPaint.alpha = (150f * br).toInt().coerceIn(0, 255)
        c.drawPath(boltPath, lightPaint)
        lightPaint.color = 0xFFFFFFFF.toInt()
        lightPaint.strokeWidth = 1.6f * d
        lightPaint.alpha = (235f * br).toInt().coerceIn(0, 255)
        c.drawPath(boltPath, lightPaint)

        // Удар: короткие лучи из точки попадания. Кружка нет намеренно.
        crownPaint.color = if (maxed) hotCore else 0xFFFFFFFF.toInt()
        crownPaint.strokeWidth = 2f * d
        crownPaint.alpha = (210f * br).toInt().coerceIn(0, 255)
        for (i in 0 until 6) {
            val a = Math.toRadians(i * 60.0 + (r % 30L).toDouble())
            val r0 = 3f * d
            val r1 = (10f + 16f * br) * d
            c.drawLine(
                tx + r0 * Math.cos(a).toFloat(), ty + r0 * Math.sin(a).toFloat(),
                tx + r1 * Math.cos(a).toFloat(), ty + r1 * Math.sin(a).toFloat(),
                crownPaint)
        }

        // Гора на мгновение подсвечивается изнутри.
        c.save()
        c.clipPath(mountainPath)
        fillPaint.color = col
        fillPaint.alpha = (55f * br).toInt().coerceIn(0, 255)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        fillPaint.alpha = 255
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val bodyH = bodyBottom - bodyTop
        val tierH = bodyH / 5f

        val prog = if (storedGoal > 0) storedTotal.toFloat() / storedGoal else 0f
        val tms = System.currentTimeMillis()
        val maxed = prog >= 5f

        // Северное сияние за горой: разгорается по мере прогресса - мир
        // отзывается на пройденный путь.
        val auroraK = (prog / 5f).coerceIn(0f, 1f)
        if (auroraK > 0.02f) {
            for (b2 in 0 until 3) {
                auroraPath.reset()
                val yBase = bodyTop + bodyH * (0.06f + 0.07f * b2)
                val amp = bodyH * (0.035f + 0.02f * b2)
                var x = 0f
                auroraPath.moveTo(0f, yBase)
                while (x <= width) {
                    val k = x / width
                    val y = yBase + amp * Math.sin((k * 6.0 + tms * 0.0007 + b2)).toFloat()
                    auroraPath.lineTo(x, y)
                    x += 6f * d
                }
                // При ×5 ленты кровавые и плотнее: мир отзывается иначе.
                auroraPaint.color = if (maxed) {
                    if (b2 == 1) bloodHi else bloodMid
                } else {
                    if (b2 == 1) 0xFF35D6A0.toInt() else 0xFF7B6BFF.toInt()
                }
                auroraPaint.strokeWidth = (if (maxed) 8.5f - b2 * 1.6f else 7f - b2 * 1.6f) * d
                auroraPaint.alpha = ((if (maxed) 165f else 70f) * auroraK *
                    (1f - b2 * 0.20f)).toInt().coerceIn(0, 255)
                canvas.drawPath(auroraPath, auroraPaint)
            }
        }

        // Луна за горой. При закрытых пяти целях багровеет, и с неё
        // срываются тяжёлые капли - у рекорда есть цена.
        if (maxed) {
            // Кратеры при ×5 не рисуем: кратер на глазу читался бы как
            // повреждение, а не как взгляд.
            drawMoonEye(canvas, tms, bodyH)
        } else {
            moonFillPaint.color = 0xFFFFF1CF.toInt()
            moonStrokePaint.color = 0xFFD9B45F.toInt()
            moonCraterPaint.color = 0xFFD8C08A.toInt()
            canvas.drawPath(moonPath, moonFillPaint)
            canvas.drawPath(moonCraters, moonCraterPaint)
            canvas.drawPath(moonPath, moonStrokePaint)
        }

        canvas.save()
        canvas.clipPath(mountainPath)

        // Камень (цел-шейдинг граней). При ×5 камень уходит в фиолетовую
        // плазму и дышит; силуэт, грани и рёбра остаются те же.
        val plasmaPulse = 0.5f + 0.5f * Math.sin(tms * 0.0021).toFloat()
        for ((path, col) in facets) {
            fillPaint.color = if (maxed) blend(col, plasmaOf(col), 0.86f + 0.14f * plasmaPulse) else col
            fillPaint.alpha = 255
            canvas.drawPath(path, fillPaint)
        }
        if (maxed) {
            drawPlasmaWave(canvas, tms, plasmaPulse)
            drawVeins(canvas, plasmaPulse)
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
                stairPaint.color = if (maxed) 0xFFE7D6FF.toInt() else stairLit
                canvas.drawPath(trailPath, stairPaint)
                tickPaint.color = stairLit
                canvas.drawPath(tickPath, tickPaint)

                // Молния «светит путь»: слоистое свечение + белое ядро (пульс).
                val tms = System.currentTimeMillis()
                val pulse = 0.55f + 0.45f * Math.sin(tms * 0.006).toFloat()
                lightPaint.color = if (maxed) magenta else 0xFFFFC94D.toInt()
                lightPaint.strokeWidth = 7f * d; lightPaint.alpha = (55f * pulse).toInt().coerceIn(0, 255)
                canvas.drawPath(trailPath, lightPaint)
                lightPaint.strokeWidth = 4f * d; lightPaint.alpha = (100f * pulse).toInt().coerceIn(0, 255)
                canvas.drawPath(trailPath, lightPaint)
                lightPaint.color = if (maxed) hotCore else 0xFFFFFFFF.toInt(); lightPaint.strokeWidth = 1.8f * d
                lightPaint.alpha = (150f + 90f * (pulse - 0.55f)).toInt().coerceIn(0, 255)
                canvas.drawPath(trailPath, lightPaint)
                var flick = Math.sin(tms * 0.02).toFloat(); if (flick < 0f) flick = 0f
                lightPaint.color = if (maxed) 0xFFFF9AE0.toInt() else 0xFFFFD98A.toInt(); lightPaint.strokeWidth = 1.6f * d
                lightPaint.alpha = (50f + 130f * flick).toInt().coerceIn(0, 255)
                canvas.drawPath(branchPath, lightPaint)
                canvas.restore()
            }
        }
        // Алмазы целей: спящий камень, взятая цель или ближайшая (пульс).
        for (i in gemBody.indices) {
            val lit = prog >= (i + 1)
            val next = !lit && prog >= i
            if (lit) {
                val gl = 0.55f + 0.45f * Math.sin(tms * 0.0035 + i * 1.7).toFloat()
                gemGlowPaint.color = if (maxed) magenta else 0xFF7FB2FF.toInt()
                gemGlowPaint.alpha = (60f + 60f * gl).toInt().coerceIn(0, 255)
                canvas.drawCircle(gemCx[i], gemCy[i], gemSz[i] * (2.1f + 0.35f * gl), gemGlowPaint)
                canvas.drawPath(gemBody[i], gemLightPaint)
                canvas.drawPath(gemDark[i], gemDarkPaint)
                canvas.drawPath(gemGlint[i], gemGlintPaint)
            } else {
                canvas.drawPath(gemBody[i], gemDeadPaint)
                if (next) {
                    // Ближайшая цель тихо дышит - видно, к чему идёшь.
                    val pl = 0.5f + 0.5f * Math.sin(tms * 0.0022 + i).toFloat()
                    gemGlowPaint.color = 0xFF7FB2FF.toInt()
                    gemGlowPaint.alpha = (28f + 42f * pl).toInt().coerceIn(0, 255)
                    canvas.drawCircle(gemCx[i], gemCy[i], gemSz[i] * (1.5f + 0.30f * pl), gemGlowPaint)
                }
            }
            canvas.drawPath(gemBody[i], gemEdgePaint)
        }
        // Снежная шапка.
        fillPaint.color = if (maxed) 0xFFF0E4FF.toInt() else snowColor
        canvas.drawPath(snowPath, fillPaint)

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
        // С первой взятой цели в гору бьют разряды.
        if (prog >= 1f) drawStrike(canvas, tms, maxed)
        if (maxed) drawGlitch(canvas, tms)

        // Искры вспыхивают только у ВЗЯТЫХ целей - награда, а не украшение.
        for (i in sparkX.indices) {
            if (prog < (i + 1)) continue
            var tw = Math.sin(tms * 0.004 + i * 2.1).toFloat(); tw = (tw + 1f) * 0.5f
            sparkPaint.alpha = (70f + 160f * tw).toInt().coerceIn(0, 255)
            sparkle(canvas, sparkX[i], sparkY[i], (2.5f + 1.5f * tw) * d)
        }
        sparkPaint.alpha = 255

        // Вершина при ×5: живая энергия вместо круга со спицами.
        if (maxed) drawSummitEnergy(canvas, tms)

        // Живая молния: перерисовка ~14 к/с, только пока есть заряд (батарея).
        if (storedTotal > 0) postInvalidateDelayed(70)
    }
}
