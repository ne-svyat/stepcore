package com.vasil.stepcore.survival

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.vasil.stepcore.R
import com.vasil.stepcore.survival.engine.Compass
import com.vasil.stepcore.survival.engine.RadarModel
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Радар: лагерь в центре, кольца дальности, конус запаха, метки зверей.
 *
 * ЦВЕТ НЕСЁТ СМЫСЛ, а не украшает:
 *   яркость       = свежесть сведений (сегодня — в полную силу, неделю
 *                   назад — призрак);
 *   ширина ореола = неопределённость (точка против размытой дуги);
 *   красный       = требует внимания СЕЙЧАС (зверь у лагеря или под ветром,
 *                   то есть он тебя уже чует);
 *   палитра       = та же, что в журнале: зверь фиолетовый, след бирюзовый,
 *                   погода синяя, важное янтарное.
 *
 * Радиальная шкала — корневая. При линейной первый километр (там, где вся
 * жизнь) сжимался в точку у центра, а пустая даль занимала полэкрана.
 *
 * Дрожание линии сеяно НОМЕРОМ ДНЯ: экран не дёргается при перерисовке,
 * но каждый день выглядит чуть иначе — как страница, нарисованная заново.
 */
class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var recon: RadarModel.Recon? = null
    private val dm = resources.displayMetrics.density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.4f * dm
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(7f * dm, BlurMaskFilter.Blur.NORMAL)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * dm
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val cAxis = ContextCompat.getColor(context, R.color.axis_dim)
    private val cDim = ContextCompat.getColor(context, R.color.text_dim)
    private val cMain = ContextCompat.getColor(context, R.color.text_main)
    private val cWind = ContextCompat.getColor(context, R.color.accent_blue_bright)
    private val cScent = ContextCompat.getColor(context, R.color.accent_amber)
    private val cAnimal = ContextCompat.getColor(context, R.color.accent_violet_bright)
    private val cTrack = ContextCompat.getColor(context, R.color.accent_teal_bright)
    private val cHot = ContextCompat.getColor(context, R.color.accent_red_bright)
    private val cHear = ContextCompat.getColor(context, R.color.accent_blue)

    private var noise = 0L

    init {
        // BlurMaskFilter не живёт на аппаратном слое — рисуем программно.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setRecon(r: RadarModel.Recon) {
        recon = r
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    // ---------- сеяный шум ----------

    private fun seed(s: Long) {
        noise = s * -0x61c8864680b583ebL + 0x9E3779B9L
    }

    private fun j(amp: Float): Float {
        noise = noise * 6364136223846793005L + 1442695040888963407L
        val u = ((noise ushr 33).toInt() and 0xFFFF) / 65535f
        return (u - 0.5f) * 2f * amp * dm
    }

    // ---------- геометрия ----------

    /** Экранный радиус для расстояния в км. Корень: ближнее не слипается. */
    private fun rpx(km: Double, rad: Float): Float {
        val k = min(max(km, 0.0), RadarModel.MAX_KM) / RadarModel.MAX_KM
        return (rad * sqrt(k)).toFloat()
    }

    /** Румб -> угол на экране. Север вверху. */
    private fun ang(sector: Double): Double = (sector * 45.0 - 90.0) * PI / 180.0

    private fun withAlpha(color: Int, a: Double): Int {
        val v = (255.0 * a).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (v shl 24)
    }

    private fun ring(p: Path, cx: Float, cy: Float, r: Float, amp: Float) {
        val n = 40
        for (i in 0..n) {
            val a = i * 2.0 * PI / n
            val x = cx + (r * cos(a)).toFloat() + j(amp)
            val y = cy + (r * sin(a)).toFloat() + j(amp)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    /** Сектор кольца: от r0 до r1, от угла a0 до a1. Основа конуса и ореола. */
    private fun wedge(
        p: Path, cx: Float, cy: Float,
        r0: Float, r1: Float, a0: Double, a1: Double, amp: Float,
    ) {
        val n = 16
        for (i in 0..n) {
            val a = a0 + (a1 - a0) * i / n
            val x = cx + (r1 * cos(a)).toFloat() + j(amp)
            val y = cy + (r1 * sin(a)).toFloat() + j(amp)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        for (i in n downTo 0) {
            val a = a0 + (a1 - a0) * i / n
            p.lineTo(cx + (r0 * cos(a)).toFloat() + j(amp),
                cy + (r0 * sin(a)).toFloat() + j(amp))
        }
        p.close()
    }

    // ---------- отрисовка ----------

    override fun onDraw(canvas: Canvas) {
        val r = recon ?: return
        seed(7717L + r.day * 31L)

        val cx = width / 2f
        val cy = height / 2f
        val rad = min(cx, cy) - 24f * dm
        if (rad <= 0f) return

        drawScent(canvas, r, cx, cy, rad)
        drawSenses(canvas, r, cx, cy, rad)
        drawGrid(canvas, cx, cy, rad)
        if (r.hasWorld) drawWind(canvas, r, cx, cy, rad)
        for (m in r.marks.reversed()) drawMark(canvas, m, cx, cy, rad)
        drawCamp(canvas, cx, cy)
    }

    /**
     * Конус запаха. Ровно тот, по которому живёт зверь: подветренный румб
     * плюс-минус один. Зверь в этом конусе и ближе scentKm ЗНАЕТ о лагере.
     * В штиль сектора нет — запах стоит лужей вокруг костра.
     */
    private fun drawScent(c: Canvas, r: RadarModel.Recon, cx: Float, cy: Float, rad: Float) {
        if (!r.hasWorld || r.scentKm <= 0.05) return
        val rr = rpx(r.scentKm, rad)
        val p = Path()
        if (r.calm) {
            ring(p, cx, cy, rr, 1.2f)
        } else {
            val mid = ang(r.downwind.toDouble())
            val half = 67.5 * PI / 180.0   // три румба: сам и два соседних
            wedge(p, cx, cy, 0f, rr, mid - half, mid + half, 1.2f)
        }
        fill.color = withAlpha(cScent, 0.10)
        fill.maskFilter = null
        c.drawPath(p, fill)
        stroke.color = withAlpha(cScent, 0.45)
        stroke.strokeWidth = 1.2f * dm
        c.drawPath(p, stroke)

        label.color = withAlpha(cScent, 0.9)
        label.textSize = 11f * dm
        val la = if (r.calm) ang(4.0) else ang(r.downwind.toDouble())
        val lx = cx + (rr * 0.62f * cos(la)).toFloat()
        val ly = cy + (rr * 0.62f * sin(la)).toFloat()
        c.drawText("запах " + RadarModel.kmRu(r.scentKm), lx, ly, label)
    }

    /**
     * ГРАНИЦА ЗНАНИЯ. Два предела, за которыми человек сегодня слеп и глух.
     *
     * Зрение — плотное кольцо. Слух — лепесток: против ветра слышно далеко,
     * по ветру почти никак. Это зеркало конуса запаха, и потому лепесток
     * смотрит ровно в противоположную сторону: запах уносит ОТ тебя, звук
     * приносит К тебе.
     *
     * Зверь за обоими пределами существует — просто ты о нём не узнаешь.
     */
    private fun drawSenses(c: Canvas, r: RadarModel.Recon, cx: Float, cy: Float, rad: Float) {
        if (!r.hasWorld) return
        val wind = if (r.calm) 0 else 2

        // лепесток слуха
        val ear = Path()
        val n = 48
        for (i in 0..n) {
            val sec = i * 8.0 / n
            val km = RadarModel.hearAt(r, sec, wind)
            val a = ang(sec)
            val rr = rpx(km, rad)
            val x = cx + (rr * cos(a)).toFloat()
            val y = cy + (rr * sin(a)).toFloat()
            if (i == 0) ear.moveTo(x, y) else ear.lineTo(x, y)
        }
        ear.close()
        fill.maskFilter = null
        fill.color = withAlpha(cHear, 0.05)
        c.drawPath(ear, fill)
        stroke.color = withAlpha(cHear, 0.40)
        stroke.strokeWidth = 1.1f * dm
        c.drawPath(ear, stroke)

        // кольцо зрения
        val eye = Path()
        ring(eye, cx, cy, rpx(r.sightKm, rad), 0.8f)
        stroke.color = withAlpha(cMain, 0.55)
        stroke.strokeWidth = 1.5f * dm
        c.drawPath(eye, stroke)

        label.textSize = 10f * dm
        label.color = withAlpha(cMain, 0.75)
        val ea = ang(1.0)
        c.drawText("видно " + RadarModel.kmRu(r.sightKm),
            cx + (rpx(r.sightKm, rad) * cos(ea)).toFloat(),
            cy + (rpx(r.sightKm, rad) * sin(ea)).toFloat() - 4f * dm, label)
        label.color = withAlpha(cHear, 0.85)
        val ha = ang(r.windDir.toDouble())
        val hr = rpx(RadarModel.hearAt(r, r.windDir.toDouble(), wind), rad)
        c.drawText("слышно", cx + (hr * 0.72f * cos(ha)).toFloat(),
            cy + (hr * 0.72f * sin(ha)).toFloat(), label)
    }

    private fun drawGrid(c: Canvas, cx: Float, cy: Float, rad: Float) {
        stroke.strokeWidth = 1.1f * dm
        for (km in RadarModel.RINGS) {
            val p = Path()
            ring(p, cx, cy, rpx(km, rad), 1.0f)
            stroke.color = withAlpha(cAxis, 0.55)
            c.drawPath(p, stroke)
        }
        // подписи колец — по одной, вдоль юго-западного луча, чтобы не мешать
        label.textSize = 10f * dm
        label.color = withAlpha(cAxis, 0.95)
        for (km in RadarModel.RINGS) {
            val a = ang(5.0)
            val rr = rpx(km, rad)
            c.drawText(
                (if (km >= 1.0) km.toInt().toString() else km.toString()) + " км",
                cx + (rr * cos(a)).toFloat(), cy + (rr * sin(a)).toFloat() - 3f * dm, label)
        }
        // восемь лучей и буквы румбов
        stroke.color = withAlpha(cAxis, 0.35)
        for (s in 0 until 8) {
            val a = ang(s.toDouble())
            val p = Path()
            p.moveTo(cx + (rpx(0.6, rad) * cos(a)).toFloat(),
                cy + (rpx(0.6, rad) * sin(a)).toFloat())
            p.lineTo(cx + (rad * cos(a)).toFloat() + j(1.5f),
                cy + (rad * sin(a)).toFloat() + j(1.5f))
            c.drawPath(p, stroke)
        }
        label.textSize = 12f * dm
        label.color = cDim
        for (s in 0 until 8) {
            val a = ang(s.toDouble())
            val rr = rad + 13f * dm
            c.drawText(Compass.RU[s],
                cx + (rr * cos(a)).toFloat(),
                cy + (rr * sin(a)).toFloat() + 4f * dm, label)
        }
    }

    /** Стрелка ветра: летит ОТТУДА, ОТКУДА дует, через лагерь. */
    private fun drawWind(c: Canvas, r: RadarModel.Recon, cx: Float, cy: Float, rad: Float) {
        val a = ang(r.windDir.toDouble())
        val x0 = cx + (rad * 0.98f * cos(a)).toFloat()
        val y0 = cy + (rad * 0.98f * sin(a)).toFloat()
        val x1 = cx + (rpx(1.2, rad) * cos(a)).toFloat()
        val y1 = cy + (rpx(1.2, rad) * sin(a)).toFloat()
        stroke.color = withAlpha(cWind, 0.85)
        stroke.strokeWidth = 2.2f * dm
        val p = Path()
        p.moveTo(x0 + j(1.5f), y0 + j(1.5f))
        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        p.quadTo(mx + j(4f), my + j(4f), x1, y1)
        c.drawPath(p, stroke)
        // остриё смотрит в лагерь
        val head = 7f * dm
        val back = a + PI
        for (k in intArrayOf(-1, 1)) {
            val ha = back + k * 0.42
            val q = Path()
            q.moveTo(x1, y1)
            q.lineTo(x1 + (head * cos(ha)).toFloat(), y1 + (head * sin(ha)).toFloat())
            c.drawPath(q, stroke)
        }
    }

    /**
     * Метка зверя. Точка — где его ВИДЕЛИ. Ореол — где он может быть сейчас.
     * Чем старше сведения, тем шире ореол и тем бледнее всё вместе.
     */
    private fun drawMark(c: Canvas, m: RadarModel.Mark, cx: Float, cy: Float, rad: Float) {
        val col = when {
            m.attention -> cHot
            m.source == com.vasil.stepcore.survival.engine.Obs.TRACK -> cTrack
            else -> cAnimal
        }
        val a = ang(m.sector.toDouble())
        val rIn = rpx(max(0.15, m.distKm - m.uncertaintyKm), rad)
        val rOut = rpx(m.distKm + m.uncertaintyKm, rad)
        // Угловая неопределённость — следствие линейной: зверь, ушедший на
        // u километров от точки в трёх километрах, мог уйти вбок так же,
        // как вперёд. Чем ближе метка, тем шире раскрывается сомнение.
        // Ошибка стороны (слух в туман, гул ветра) раскрывает ореол вбок.
        // Радар не ставит ложных меток — он честно расширяет сомнение.
        val bearing = m.bearingErr * 22.5 * PI / 180.0
        val half = min(1.5, atan(m.uncertaintyKm / max(0.5, m.distKm)) + bearing)

        val p = Path()
        wedge(p, cx, cy, rIn, rOut, a - half, a + half, 1.0f)
        haze.color = withAlpha(col, 0.06 + 0.16 * m.freshness)
        c.drawPath(p, haze)

        val mx = cx + (rpx(m.distKm, rad) * cos(a)).toFloat()
        val my = cy + (rpx(m.distKm, rad) * sin(a)).toFloat()
        fill.maskFilter = null
        fill.color = withAlpha(col, 0.35 + 0.65 * m.freshness)
        c.drawCircle(mx, my, 4.5f * dm, fill)
        if (m.attention) {
            stroke.color = withAlpha(col, 0.9)
            stroke.strokeWidth = 1.6f * dm
            val ringP = Path()
            ring(ringP, mx, my, 9f * dm, 0.8f)
            c.drawPath(ringP, stroke)
        }

        label.textSize = 11f * dm
        label.color = withAlpha(col, 0.45 + 0.55 * m.freshness)
        val ly = if (my > cy) my + 18f * dm else my - 11f * dm
        c.drawText(RadarModel.kindShort(m.kind), mx, ly, label)
    }

    /** Лагерь: палатка от руки. Центр всего, что здесь нарисовано. */
    private fun drawCamp(c: Canvas, cx: Float, cy: Float) {
        val h = 11f * dm
        val w = 12f * dm
        stroke.color = cMain
        stroke.strokeWidth = 1.8f * dm
        val p = Path()
        p.moveTo(cx - w + j(0.6f), cy + h)
        p.lineTo(cx + j(0.6f), cy - h)
        p.lineTo(cx + w + j(0.6f), cy + h)
        p.close()
        c.drawPath(p, stroke)
        val d = Path()
        d.moveTo(cx, cy - h * 0.55f)
        d.lineTo(cx - w * 0.28f + j(0.5f), cy + h)
        d.moveTo(cx, cy - h * 0.55f)
        d.lineTo(cx + w * 0.28f + j(0.5f), cy + h)
        stroke.strokeWidth = 1.2f * dm
        c.drawPath(d, stroke)
    }
}
