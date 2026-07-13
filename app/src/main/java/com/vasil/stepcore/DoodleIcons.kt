package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * Иконки-дудлы (V14.8).
 *
 * Сделаны как Drawable, а не как View: тогда их можно вешать прямо на
 * обычные кнопки через setCompoundDrawables, не перестраивая разметку.
 * Кнопка остаётся кнопкой (клики, доступность, состояния), а слева от
 * текста появляется рисунок от руки.
 *
 * Вся геометрия сперва отрисована в PNG и проверена глазами.
 */
class DoodleIconDrawable(
    private val type: Int,
    private val color: Int,
    private val density: Float,
    private val sizeDp: Float = 22f,
) : Drawable() {

    companion object {
        const val SNOWFLAKE = 0   // зима
        const val LEAF = 1        // весна
        const val SUN = 2         // лето
        const val AUTUMN = 3      // осень (лист с черешком - падает)
        const val FOOTPRINTS = 4  // темп: шаги
        const val FLAG = 5        // план: цель
        const val BACKPACK = 6    // снаряжение
        const val CHEST = 7       // архив
        // v114: значки действий. Каждый обязан читаться на бегу, поэтому
        // ни один не сложнее трёх штрихов.
        const val COMPASS = 8     // окрестности: где я и что вокруг
        const val REFRESH = 9     // обновить: догнать мир
        const val COPY = 10       // скопировать: два листа
        const val SHARE = 11      // поделиться: три узла и связи
        const val BACK = 12       // назад
        const val CROSS = 13      // завершить: необратимо
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = this@DoodleIconDrawable.color
    }
    private val path = Path()
    private var built = false

    private val px get() = sizeDp * density

    override fun getIntrinsicWidth(): Int = px.toInt()
    override fun getIntrinsicHeight(): Int = px.toInt()

    private fun build() {
        if (built) return
        built = true
        val w = Wobble(type * 977L + 13L)
        val cx = px / 2f
        val cy = px / 2f
        val r = px * 0.38f
        path.reset()
        when (type) {
            SNOWFLAKE -> snowflake(cx, cy, r, w)
            LEAF -> leaf(cx, cy, r, w, false)
            AUTUMN -> leaf(cx, cy, r, w, true)
            SUN -> Doodle.sun(path, cx, cy, r * 0.55f, w)
            FOOTPRINTS -> footprints(cx, cy, r, w)
            FLAG -> flag(cx, cy, r, w)
            BACKPACK -> backpack(cx, cy, r, w)
            CHEST -> chest(cx, cy, r, w)
            COMPASS -> compass(cx, cy, r, w)
            REFRESH -> refresh(cx, cy, r, w)
            COPY -> copyIcon(cx, cy, r, w)
            SHARE -> share(cx, cy, r, w)
            BACK -> back(cx, cy, r, w)
            CROSS -> cross(cx, cy, r, w)
        }
        paint.strokeWidth = 1.6f * density
    }

    private fun snowflake(cx: Float, cy: Float, r: Float, w: Wobble) {
        for (k in 0 until 6) {
            val a = Math.toRadians(60.0 * k)
            Doodle.line(path, cx, cy,
                cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), 0.7f, 4, w)
            val mx = cx + r * 0.6f * cos(a).toFloat()
            val my = cy + r * 0.6f * sin(a).toFloat()
            for (s in intArrayOf(-1, 1)) {
                val b = a + s * Math.toRadians(35.0)
                Doodle.line(path, mx, my,
                    mx + r * 0.32f * cos(b).toFloat(), my + r * 0.32f * sin(b).toFloat(),
                    0.5f, 3, w)
            }
        }
    }

    private fun leaf(cx: Float, cy: Float, r: Float, w: Wobble, autumn: Boolean) {
        var first = true
        for (k in 0..18) {
            val t = k / 18f
            val wv = r * 0.62f * sin(Math.PI * t).toFloat()
            val x = cx - r * 0.9f + 1.8f * r * t + w.j(0.6f)
            val y = cy - wv + w.j(0.6f)
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        for (k in 0..18) {
            val t = 1f - k / 18f
            val wv = r * 0.62f * sin(Math.PI * t).toFloat()
            path.lineTo(cx - r * 0.9f + 1.8f * r * t, cy + wv)
        }
        path.close()
        Doodle.line(path, cx - r * 0.9f, cy, cx + r * 0.9f, cy, 0.6f, 5, w)
        for (k in 0 until 3) {
            val t = 0.3f + k * 0.2f
            val x = cx - r * 0.9f + 1.8f * r * t
            Doodle.line(path, x, cy, x + r * 0.25f, cy - r * 0.3f, 0.5f, 3, w)
        }
        // Осенний лист - с черешком вниз: он падает, а не растёт.
        if (autumn) {
            Doodle.line(path, cx - r * 0.9f, cy, cx - r * 1.3f, cy + r * 0.5f, 0.6f, 3, w)
        }
    }

    private fun footprints(cx: Float, cy: Float, r: Float, w: Wobble) {
        val offs = arrayOf(floatArrayOf(-r * 0.5f, r * 0.3f), floatArrayOf(r * 0.4f, -r * 0.3f))
        for (o in offs) {
            oval(cx + o[0], cy + o[1], r * 0.28f, r * 0.42f, w)
            oval(cx + o[0], cy + o[1] - r * 0.56f, r * 0.16f, r * 0.10f, w)
        }
    }

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float, w: Wobble) {
        for (k in 0..16) {
            val a = Math.toRadians(360.0 * k / 16)
            val x = cx + rx * cos(a).toFloat() + w.j(0.5f)
            val y = cy + ry * sin(a).toFloat() + w.j(0.5f)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    private fun flag(cx: Float, cy: Float, r: Float, w: Wobble) {
        Doodle.line(path, cx - r * 0.5f, cy + r, cx - r * 0.5f, cy - r, 0.7f, 5, w)
        Doodle.poly(path, floatArrayOf(
            cx - r * 0.5f, cy - r, cx + r * 0.9f, cy - r * 0.6f, cx - r * 0.5f, cy - r * 0.2f
        ), 0.8f, 5, w, true)
    }

    private fun backpack(cx: Float, cy: Float, r: Float, w: Wobble) {
        Doodle.roundRect(path, cx - r * 0.7f, cy - r * 0.55f, r * 1.4f, r * 1.5f,
            r * 0.25f, 0.9f, w)
        Doodle.line(path, cx - r * 0.7f, cy + r * 0.1f, cx + r * 0.7f, cy + r * 0.1f, 0.7f, 5, w)
        for (s in intArrayOf(-1, 1)) {
            Doodle.line(path, cx + s * r * 0.35f, cy - r * 0.55f,
                cx + s * r * 0.5f, cy - r * 1.0f, 0.7f, 4, w)
        }
        Doodle.roundRect(path, cx - r * 0.3f, cy + r * 0.35f, r * 0.6f, r * 0.45f,
            r * 0.1f, 0.7f, w)
    }

    /**
     * Компас: кольцо и стрелка на север. Знак «осмотреться».
     * Стрелка НЕсимметрична — длинный север, короткий хвост: симметричный
     * ромб читался как глаз, а не как стрелка (проверено отрисовкой).
     */
    private fun compass(cx: Float, cy: Float, r: Float, w: Wobble) {
        Doodle.arc(path, cx, cy, r * 0.98f, 0f, 180f, 0.6f, w)
        Doodle.arc(path, cx, cy, r * 0.98f, 180f, 360f, 0.6f, w)
        Doodle.poly(path, floatArrayOf(
            cx, cy - r * 0.80f,
            cx + r * 0.30f, cy + r * 0.22f,
            cx, cy + r * 0.02f,
            cx - r * 0.30f, cy + r * 0.22f,
        ), 0.6f, 3, w, true)
        Doodle.line(path, cx, cy + r * 0.02f, cx, cy + r * 0.55f, 0.5f, 3, w)
    }

    /**
     * Обновить: почти замкнутая дуга со стрелкой — время пошло дальше.
     * Наконечник строится ПО КАСАТЕЛЬНОЙ к дуге: прямые штрихи «в сторону»
     * отваливались от кольца и выглядели мусором.
     */
    private fun refresh(cx: Float, cy: Float, r: Float, w: Wobble) {
        Doodle.arc(path, cx, cy, r * 0.85f, 45f, 225f, 0.7f, w)
        Doodle.arc(path, cx, cy, r * 0.85f, 225f, 340f, 0.7f, w)
        val a = Math.toRadians(340.0)
        val hx = cx + r * 0.85f * cos(a).toFloat()
        val hy = cy + r * 0.85f * sin(a).toFloat()
        val tx = -sin(a).toFloat()
        val ty = cos(a).toFloat()
        for (sgn in intArrayOf(150, -150)) {
            val b = Math.toRadians(sgn.toDouble())
            val bx = tx * cos(b).toFloat() - ty * sin(b).toFloat()
            val by = tx * sin(b).toFloat() + ty * cos(b).toFloat()
            Doodle.line(path, hx, hy, hx + r * 0.42f * bx, hy + r * 0.42f * by, 0.5f, 3, w)
        }
    }

    /** Скопировать: лист поверх листа. */
    private fun copyIcon(cx: Float, cy: Float, r: Float, w: Wobble) {
        Doodle.roundRect(path, cx - r * 0.9f, cy - r * 0.55f, r * 1.15f, r * 1.4f,
            r * 0.12f, 0.7f, w)
        Doodle.roundRect(path, cx - r * 0.25f, cy - r * 0.95f, r * 1.15f, r * 1.4f,
            r * 0.12f, 0.7f, w)
    }

    /** Поделиться: три узла и связи между ними. */
    private fun share(cx: Float, cy: Float, r: Float, w: Wobble) {
        val ax = cx - r * 0.6f; val ay = cy
        val bx = cx + r * 0.6f; val by = cy - r * 0.75f
        val dx = cx + r * 0.6f; val dy = cy + r * 0.75f
        Doodle.line(path, ax, ay, bx, by, 0.6f, 4, w)
        Doodle.line(path, ax, ay, dx, dy, 0.6f, 4, w)
        oval(ax, ay, r * 0.22f, r * 0.22f, w)
        oval(bx, by, r * 0.22f, r * 0.22f, w)
        oval(dx, dy, r * 0.22f, r * 0.22f, w)
    }

    /** Назад: стрелка влево. */
    private fun back(cx: Float, cy: Float, r: Float, w: Wobble) {
        Doodle.line(path, cx + r * 0.85f, cy, cx - r * 0.8f, cy, 0.6f, 5, w)
        Doodle.line(path, cx - r * 0.8f, cy, cx - r * 0.2f, cy - r * 0.5f, 0.6f, 3, w)
        Doodle.line(path, cx - r * 0.8f, cy, cx - r * 0.2f, cy + r * 0.5f, 0.6f, 3, w)
    }

    /** Завершить: крест. Единственное необратимое действие в режиме. */
    private fun cross(cx: Float, cy: Float, r: Float, w: Wobble) {
        Doodle.line(path, cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, 0.8f, 5, w)
        Doodle.line(path, cx + r * 0.7f, cy - r * 0.7f, cx - r * 0.7f, cy + r * 0.7f, 0.8f, 5, w)
    }

    private fun chest(cx: Float, cy: Float, r: Float, w: Wobble) {
        Doodle.roundRect(path, cx - r * 0.8f, cy - r * 0.1f, r * 1.6f, r * 0.8f,
            r * 0.1f, 0.9f, w)
        var first = true
        for (k in 0..12) {
            val a = Math.PI + Math.PI * k / 12
            val x = cx + r * 0.8f * cos(a).toFloat()
            val y = cy - r * 0.1f + r * 0.55f * sin(a).toFloat()
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        Doodle.roundRect(path, cx - r * 0.12f, cy - r * 0.18f, r * 0.24f, r * 0.42f,
            r * 0.05f, 0.5f, w)
    }

    override fun draw(canvas: Canvas) {
        build()
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Полоса прогресса "от руки": дорожка + заливка ШТРИХОВКОЙ (не сплошняком -
 * ровный прямоугольник рядом с дрожащим контуром смотрелся бы инородно).
 * Подписана на общий механизм, поэтому линия дышит вместе со всем экраном.
 */
class DoodleProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density
    private var pct = 0f
    private var color = ContextCompat.getColor(context, R.color.accent_amber)
    private var seed = 1L

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        this.color = ContextCompat.getColor(context, R.color.axis_dim)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    private val onTick: () -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BoilClock.register(onTick)
    }

    override fun onDetachedFromWindow() {
        BoilClock.unregister(onTick)
        super.onDetachedFromWindow()
    }

    fun setProgress(fraction: Float, colorInt: Int, seedValue: Long) {
        pct = fraction.coerceIn(0f, 1f)
        color = colorInt
        seed = seedValue
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val rr = h / 2f
        val wob = Wobble(seed * 41L + BoilClock.frame)

        val trackPath = Path()
        Doodle.roundRect(trackPath, 1f * d, 1f * d, w - 2f * d, h - 2f * d, rr, 0.7f, wob)
        canvas.drawPath(trackPath, track)

        val fw = (w - 2f * d) * pct
        if (fw > 3f * d) {
            val hatchPath = Path()
            Doodle.hatch(hatchPath, 1f * d, 1f * d, 1f * d + fw, h - 1f * d, 4f * d, 60f, 1f, wob)
            fill.color = color
            fill.alpha = 170
            canvas.drawPath(hatchPath, fill)

            val edgePath = Path()
            Doodle.roundRect(edgePath, 1f * d, 1f * d, fw, h - 2f * d,
                kotlin.math.min(rr, fw / 2f), 0.7f, wob)
            edge.color = color
            canvas.drawPath(edgePath, edge)
        }
    }
}
