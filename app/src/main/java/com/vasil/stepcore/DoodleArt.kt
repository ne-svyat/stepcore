package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Дудл-графика StepCore (V14.0).
 *
 * Идея: рукописный контур - это не кривая, а ЛОМАНАЯ, у которой каждая
 * промежуточная точка сдвинута случайным шумом. Ключевая деталь: шум
 * СЕЯНЫЙ (детерминированный по сиду элемента), а не свежий на каждый
 * кадр. Свежий шум на каждой перерисовке = дрожащая, вибрирующая
 * картинка, от которой рябит в глазах. Сеяный = линия кривая, но
 * СТАБИЛЬНО кривая, как настоящий рисунок от руки.
 *
 * Вся математика этого файла сначала отрисована в PNG и проверена
 * глазами, и только потом портирована сюда - координаты 1:1.
 */

/** Тот же SplitMix64, что в движке Survival: быстрый, детерминированный. */
internal class Wobble(seed: Long) {
    private var s = seed
    private fun next(): Long {
        s += -0x61c8864680b583ebL
        var z = s
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
    /** Равномерно в [-a, a]. */
    fun j(a: Float): Float {
        val u = (next() ushr 11).toDouble() / (1L shl 53).toDouble()
        return ((u * 2.0 - 1.0) * a).toFloat()
    }
}

/** Утилиты рисования дрожащих фигур. Общие для рамок и сцен. */
internal object Doodle {

    /** Прямая -> ломаная с шумом. */
    fun line(p: Path, x0: Float, y0: Float, x1: Float, y1: Float,
             jit: Float, seg: Int, w: Wobble) {
        p.moveTo(x0, y0)
        for (i in 1..seg) {
            val t = i.toFloat() / seg
            var x = x0 + (x1 - x0) * t
            var y = y0 + (y1 - y0) * t
            if (i < seg) { x += w.j(jit); y += w.j(jit) }
            p.lineTo(x, y)
        }
    }

    /** Замкнутый (или нет) многоугольник дрожащими сторонами. */
    fun poly(p: Path, pts: FloatArray, jit: Float, seg: Int, w: Wobble, close: Boolean) {
        val n = pts.size / 2
        val last = if (close) n else n - 1
        for (i in 0 until last) {
            val a = i * 2
            val b = ((i + 1) % n) * 2
            line(p, pts[a], pts[a + 1], pts[b], pts[b + 1], jit, seg, w)
        }
    }

    /** Скруглённый прямоугольник от руки - основа рамки карточки. */
    fun roundRect(p: Path, x: Float, y: Float, ww: Float, hh: Float, r: Float,
                  jit: Float, w: Wobble) {
        line(p, x + r, y, x + ww - r, y, jit, 16, w)
        line(p, x + ww, y + r, x + ww, y + hh - r, jit, 16, w)
        line(p, x + ww - r, y + hh, x + r, y + hh, jit, 16, w)
        line(p, x, y + hh - r, x, y + r, jit, 16, w)
        arc(p, x + r, y + r, r, 180f, 270f, jit, w)
        arc(p, x + ww - r, y + r, r, 270f, 360f, jit, w)
        arc(p, x + ww - r, y + hh - r, r, 0f, 90f, jit, w)
        arc(p, x + r, y + hh - r, r, 90f, 180f, jit, w)
    }

    fun arc(p: Path, cx: Float, cy: Float, r: Float, a0: Float, a1: Float,
            jit: Float, w: Wobble) {
        for (k in 0..8) {
            val a = Math.toRadians((a0 + (a1 - a0) * k / 8f).toDouble())
            var x = cx + r * cos(a).toFloat()
            var y = cy + r * sin(a).toFloat()
            if (k in 1..7) { x += w.j(jit); y += w.j(jit) }
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
    }

    /** Четырёхлучевая искра - фирменный элемент стиля. */
    fun star(p: Path, cx: Float, cy: Float, r: Float, w: Wobble) {
        val k = r * 0.32f
        poly(p, floatArrayOf(
            cx, cy - r, cx + k, cy - k, cx + r, cy, cx + k, cy + k,
            cx, cy + r, cx - k, cy + k, cx - r, cy, cx - k, cy - k
        ), 0.8f, 4, w, true)
    }

    /** Ёлка: три яруса + ствол + травинки. */
    fun fir(p: Path, x: Float, baseY: Float, h: Float, w: Wobble) {
        val ww = h * 0.42f
        for (i in 0 until 3) {
            val ly = baseY - h * (0.30f + 0.28f * i)
            val lw = ww * (1f - 0.22f * i)
            poly(p, floatArrayOf(x, ly - h * 0.30f, x + lw / 2, ly, x - lw / 2, ly),
                1.2f, 6, w, true)
        }
        line(p, x, baseY, x, baseY - h * 0.32f, 1.0f, 4, w)
        line(p, x - 6f, baseY, x - 10f, baseY - 6f, 0.8f, 3, w)
        line(p, x + 6f, baseY, x + 10f, baseY - 6f, 0.8f, 3, w)
    }

    /** Палатка: скаты, вход, оттяжки. */
    fun tent(p: Path, cx: Float, baseY: Float, ww: Float, h: Float, w: Wobble) {
        poly(p, floatArrayOf(cx - ww / 2, baseY, cx + ww * 0.05f, baseY - h,
            cx + ww / 2, baseY), 1.4f, 8, w, true)
        poly(p, floatArrayOf(cx - ww * 0.10f, baseY, cx + ww * 0.03f, baseY - h * 0.62f,
            cx + ww * 0.16f, baseY), 1.2f, 6, w, true)
        line(p, cx + ww * 0.05f, baseY - h, cx + ww * 0.62f, baseY - h * 0.08f, 1.0f, 6, w)
        line(p, cx - ww * 0.52f, baseY - h * 0.06f, cx - ww / 2, baseY, 1.0f, 4, w)
    }

    /** Горный хребет со снежными шапками. */
    fun mountains(p: Path, snow: Path, x0: Float, baseY: Float, ww: Float, h: Float, w: Wobble) {
        val px = floatArrayOf(x0 + ww * 0.22f, x0 + ww * 0.52f, x0 + ww * 0.80f)
        val py = floatArrayOf(baseY - h * 0.72f, baseY - h, baseY - h * 0.60f)
        val pts = ArrayList<Float>()
        pts.add(x0); pts.add(baseY)
        for (i in 0 until 3) {
            pts.add(px[i]); pts.add(py[i])
            pts.add(px[i] + ww * 0.10f); pts.add(baseY - h * 0.22f)
        }
        pts.add(x0 + ww); pts.add(baseY)
        poly(p, pts.toFloatArray(), 1.6f, 8, w, false)
        line(p, x0, baseY, x0 + ww, baseY, 1.4f, 14, w)
        for (i in 0 until 3) {
            poly(snow, floatArrayOf(
                px[i] - 9f, py[i] + 13f, px[i], py[i], px[i] + 9f, py[i] + 13f,
                px[i] + 4f, py[i] + 9f, px[i] - 2f, py[i] + 14f
            ), 0.8f, 4, w, false)
        }
    }

    /** Облако одной волнистой дугой. */
    fun cloud(p: Path, cx: Float, cy: Float, s: Float, w: Wobble) {
        var first = true
        for (k in 0..24) {
            val t = k / 24f
            val a = Math.PI * (1f - t)
            val bump = 1f + 0.35f * sin(t * Math.PI * 3).toFloat()
            val x = cx + s * 1.4f * cos(a).toFloat() + w.j(1f)
            val y = cy - s * 0.55f * sin(a).toFloat() * bump + w.j(1f)
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
        line(p, cx - s * 1.4f, cy, cx + s * 1.4f, cy, 1.0f, 8, w)
    }

    /** Полумесяц: внешняя дуга + вырез. */
    fun moon(p: Path, cx: Float, cy: Float, r: Float, w: Wobble) {
        var first = true
        for (k in 0..20) {
            val a = Math.toRadians((40 + 280 * k / 20f).toDouble())
            val x = cx + r * cos(a).toFloat() + w.j(0.8f)
            val y = cy + r * sin(a).toFloat() + w.j(0.8f)
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
        first = true
        for (k in 0..16) {
            val a = Math.toRadians((60 + 240 * k / 16f).toDouble())
            val x = cx + r * 0.45f + r * 0.85f * cos(a).toFloat() + w.j(0.8f)
            val y = cy + r * 0.85f * sin(a).toFloat() + w.j(0.8f)
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
    }

    /** Солнце: круг + лучи. */
    fun sun(p: Path, cx: Float, cy: Float, r: Float, w: Wobble) {
        var first = true
        for (k in 0..24) {
            val a = Math.toRadians((360 * k / 24f).toDouble())
            val x = cx + r * cos(a).toFloat() + w.j(1f)
            val y = cy + r * sin(a).toFloat() + w.j(1f)
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
        for (k in 0 until 8) {
            val a = Math.toRadians((45.0 * k + 10.0))
            line(p, cx + r * 1.45f * cos(a).toFloat(), cy + r * 1.45f * sin(a).toFloat(),
                cx + r * 2.05f * cos(a).toFloat(), cy + r * 2.05f * sin(a).toFloat(),
                0.8f, 3, w)
        }
    }

    /** Костёр: поленья + языки пламени. */
    fun fire(logs: Path, flame: Path, cx: Float, baseY: Float, s: Float, w: Wobble) {
        line(logs, cx - s, baseY, cx + s, baseY - s * 0.35f, 1.0f, 5, w)
        line(logs, cx - s, baseY - s * 0.35f, cx + s, baseY, 1.0f, 5, w)
        var first = true
        for (k in 0..12) {
            val a = Math.toRadians((-90 + 180 * (k / 12f)).toDouble())
            val rr = s * (if (k % 2 == 0) 1.5f else 1.0f)
            val x = cx + rr * 0.55f * cos(a).toFloat() + w.j(1f)
            val y = baseY - s * 0.5f - rr * 0.9f * abs(sin(a).toFloat()) + w.j(1f)
            if (first) { flame.moveTo(x, y); first = false } else flame.lineTo(x, y)
        }
    }
}

/**
 * Рамка карточки "от руки". Вешается фоном на любую View вместо
 * shape-drawable: заливка + дрожащий контур нужного цвета.
 *
 * Сид привязан к переданному значению, поэтому одна и та же карточка
 * всегда выглядит одинаково, а РАЗНЫЕ карточки кривятся по-разному -
 * как несколько раз нарисованный от руки прямоугольник.
 */
class DoodleBorderDrawable(
    private val strokeColor: Int,
    private val fillColor: Int,
    private val seed: Long,
    density: Float,
) : Drawable() {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = strokeColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val d = density
    private val path = Path()
    private var builtFor = Rect()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds == builtFor || bounds.isEmpty) return
        builtFor = Rect(bounds)
        val w = Wobble(seed)
        path.reset()
        val inset = 2f * d
        Doodle.roundRect(path, inset, inset,
            bounds.width() - 2 * inset, bounds.height() - 2 * inset,
            14f * d, 1.5f * d, w)
    }

    override fun draw(canvas: Canvas) {
        if (fillColor != Color.TRANSPARENT) canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) { strokePaint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {
        strokePaint.colorFilter = cf
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Дудл-сцена: декоративный слой (пейзаж/ночь/день/лагерь). Чисто
 * визуальный, не перехватывает касания - кладётся ПОД контент.
 */
class DoodleSceneView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val HEADER = 0      // горы, ёлки, луна, облака, звёзды
        const val NIGHT = 1       // луна + звёзды (карточка ВЧЕРА)
        const val DAY = 2         // солнце + ёлки (карточка СЕГОДНЯ)
        const val EXPEDITION = 3  // палатка, костёр, лес, горы, тропа

        /** Прозрачность декора: читаемость текста важнее насыщенности. */
        private const val DECOR_ALPHA = 130
    }

    private var scene = HEADER
    private val d = resources.displayMetrics.density

    private val violet = ContextCompat.getColor(context, R.color.accent_violet)
    private val violetBr = ContextCompat.getColor(context, R.color.accent_violet_bright)
    private val teal = ContextCompat.getColor(context, R.color.accent_teal)
    private val tealBr = ContextCompat.getColor(context, R.color.accent_teal_bright)
    private val amber = ContextCompat.getColor(context, R.color.accent_amber)
    private val amberBr = ContextCompat.getColor(context, R.color.accent_amber_bright)
    private val blueBr = ContextCompat.getColor(context, R.color.accent_blue_bright)

    /**
     * Декор ПОЛУПРОЗРАЧЕН намеренно. Первая версия рисовала сцены в полную
     * силу - луна и ёлки перекрывали цифры, экран стало невозможно читать.
     * Декор - это фон, а не контент: он обязан уступать тексту.
     */
    private fun stroke(c: Int, wpx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = c; alpha = DECOR_ALPHA
        strokeWidth = wpx * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; alpha = DECOR_ALPHA
    }

    fun setScene(s: Int) { scene = s; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Сид зависит от сцены -> каждая сцена кривится по-своему, но
        // стабильно между перерисовками.
        val rng = Wobble(9001L + scene * 733L)
        when (scene) {
            HEADER -> drawHeader(canvas, w, h, rng)
            NIGHT -> drawNight(canvas, w, h, rng)
            DAY -> drawDay(canvas, w, h, rng)
            EXPEDITION -> drawExpedition(canvas, w, h, rng)
        }
    }

    /**
     * Полоса-пейзаж НАД кристаллом (не слой под цифрами, как было в
     * первой версии - там горы перечёркивали текст). В границах этой
     * View текста нет вообще, поэтому перекрывать нечего.
     */
    private fun drawHeader(c: Canvas, w: Float, h: Float, r: Wobble) {
        val base = h * 0.95f
        val mt = Path(); val snow = Path()
        Doodle.mountains(mt, snow, w * 0.30f, base, w * 0.40f, h * 0.62f, r)
        c.drawPath(mt, stroke(violet, 2f))
        c.drawPath(snow, stroke(violetBr, 1f))
        val trees = Path()
        Doodle.fir(trees, w * 0.06f, base, h * 0.52f, r)
        Doodle.fir(trees, w * 0.14f, base, h * 0.40f, r)
        Doodle.fir(trees, w * 0.72f, base, h * 0.45f, r)
        Doodle.fir(trees, w * 0.78f, base, h * 0.34f, r)
        c.drawPath(trees, stroke(teal, 2f))
        val sky = Path()
        Doodle.cloud(sky, w * 0.24f, h * 0.22f, h * 0.13f, r)
        Doodle.cloud(sky, w * 0.60f, h * 0.16f, h * 0.10f, r)
        c.drawPath(sky, stroke(violet, 2f))
        val mn = Path()
        Doodle.moon(mn, w * 0.92f, h * 0.28f, h * 0.16f, r)
        c.drawPath(mn, stroke(amberBr, 2f))
        val st = Path()
        Doodle.star(st, w * 0.42f, h * 0.18f, h * 0.08f, r)
        Doodle.star(st, w * 0.86f, h * 0.68f, h * 0.06f, r)
        c.drawPath(st, stroke(blueBr, 2f))
        dotPaint.color = amber
        c.drawCircle(w * 0.52f, h * 0.30f, 2f * d, dotPaint)
        dotPaint.color = tealBr
        c.drawCircle(w * 0.18f, h * 0.62f, 2f * d, dotPaint)
    }

    /**
     * NIGHT/DAY рисуются в ПРАВОМ ВЕРХНЕМ углу карточки - единственной
     * зоне, свободной от текста (цифры и подписи идут слева вниз).
     * В первой версии луна и солнце сидели по центру прямо на цифрах.
     */
    private fun drawNight(c: Canvas, w: Float, h: Float, r: Wobble) {
        val mn = Path()
        Doodle.moon(mn, w * 0.80f, h * 0.16f, h * 0.11f, r)
        c.drawPath(mn, stroke(violetBr, 2f))
        val st = Path()
        Doodle.star(st, w * 0.68f, h * 0.09f, h * 0.045f, r)
        Doodle.star(st, w * 0.92f, h * 0.36f, h * 0.04f, r)
        c.drawPath(st, stroke(blueBr, 2f))
        dotPaint.color = violetBr
        c.drawCircle(w * 0.70f, h * 0.30f, 1.8f * d, dotPaint)
    }

    private fun drawDay(c: Canvas, w: Float, h: Float, r: Wobble) {
        val sn = Path()
        Doodle.sun(sn, w * 0.82f, h * 0.15f, h * 0.06f, r)
        c.drawPath(sn, stroke(amber, 2f))
        val trees = Path()
        Doodle.fir(trees, w * 0.93f, h * 0.98f, h * 0.20f, r)
        c.drawPath(trees, stroke(tealBr, 2f))
        dotPaint.color = amberBr
        c.drawCircle(w * 0.62f, h * 0.10f, 1.8f * d, dotPaint)
    }

    /**
     * Лагерь живёт в ПРАВОЙ части карточки: слева ромб сезона и текст.
     * В первой версии палатка стояла прямо на ромбе и на заголовке.
     */
    private fun drawExpedition(c: Canvas, w: Float, h: Float, r: Wobble) {
        val base = h * 0.90f
        val tn = Path()
        Doodle.tent(tn, w * 0.62f, base, w * 0.13f, h * 0.42f, r)
        c.drawPath(tn, stroke(violetBr, 2f))
        val logs = Path(); val flame = Path()
        Doodle.fire(logs, flame, w * 0.72f, base, h * 0.09f, r)
        c.drawPath(logs, stroke(0xFF966E46.toInt(), 2f))
        c.drawPath(flame, stroke(amber, 2f))
        val trees = Path()
        Doodle.fir(trees, w * 0.55f, base, h * 0.30f, r)
        Doodle.fir(trees, w * 0.97f, base, h * 0.28f, r)
        c.drawPath(trees, stroke(teal, 2f))
        val mt = Path(); val snow = Path()
        Doodle.mountains(mt, snow, w * 0.78f, base, w * 0.18f, h * 0.36f, r)
        c.drawPath(mt, stroke(amberBr, 2f))
        c.drawPath(snow, stroke(amberBr, 1f))
        val trail = Path()
        Doodle.line(trail, w * 0.66f, base + 3f * d, w * 0.74f, base - 2f * d, 1.2f, 6, r)
        Doodle.line(trail, w * 0.74f, base - 2f * d, w * 0.80f, base + 2f * d, 1.2f, 6, r)
        c.drawPath(trail, stroke(amber, 1f))
        val st = Path()
        Doodle.star(st, w * 0.88f, h * 0.16f, h * 0.10f, r)
        c.drawPath(st, stroke(amberBr, 2f))
    }
}
