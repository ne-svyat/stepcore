package com.vasil.stepcore

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * Кнопка «Старт / Стоп».
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ КЛАСС. Пользователь обновил приложение, забыл нажать
 * «Старт» и потерял сорок минут ходьбы. Приложение, которое молча не
 * считает шаги, не выполняет свою единственную работу. Кнопка обязана
 * кричать о том, что счёт не идёт, и молчать, когда всё в порядке.
 *
 * ПРИНЦИП: тревожит только НЕрабочее состояние.
 *   - счёт не идёт -> зелёная «Старт»: тело дышит, внутри бегут жилы
 *     разряда, наружу расходятся две волны сияния;
 *   - счёт идёт -> красная «Стоп», ровная, без анимации. Нажатие здесь
 *     останавливает работу, поэтому цвет предупреждающий, а не зовущий.
 *
 * ВИД. Кнопка живёт в том же каменном языке, что и плиты: двойной контур
 * (тёмная основа + яркий кант), резной бевел и кованые гвозди по углам.
 * Раньше она была просто скруглённым прямоугольником и выбивалась.
 *
 * Сияние рисуется ВНУТРЬ границ: фон кнопки обрезается по её краям, и
 * волна, растущая наружу, была бы срезана. Поэтому тело вписано с
 * отступом, а волна расходится в этот отступ.
 *
 * Анимация живёт только пока кнопка видима и пока счёт не идёт:
 * остановленный ValueAnimator не тратит ни кадра.
 */
class PulseButtonDrawable(
    private val density: Float,
    private val colorGo: Int,       // зелёный: можно и нужно нажать
    private val colorStop: Int,     // красный: нажатие остановит счёт
    private val fillGo: Int,
    private val fillStop: Int,
) : Drawable() {

    private var running = false
    private var pulse = 0f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF07090D.toInt()
    }
    private val bevelHi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * density; alpha = 165
    }
    private val bevelSh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.7f * density
        color = Color.BLACK; alpha = 190
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
    }
    private val vein = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rivetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF6B7488.toInt()
    }
    private val rivetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF07090D.toInt()
    }
    private val rivetHi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFAEB8C8.toInt(); alpha = 205
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1700L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidateSelf()
        }
    }

    /** Дрожание линии, но СТАБИЛЬНОЕ: кнопка не должна дёргаться каждый кадр. */
    private var seed = 0x9E3779B9L
    private fun j(amp: Float): Float {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        val u = ((seed ushr 33).toInt() and 0xFFFF) / 65535f
        return (u - 0.5f) * 2f * amp * density
    }

    init {
        // Стартовое состояние - «счёт не идёт», и пульс должен идти СРАЗУ.
        syncAnimation()
    }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        syncAnimation()
        invalidateSelf()
    }

    private fun syncAnimation() {
        val shouldRun = !running && isVisible
        if (shouldRun && !animator.isStarted) animator.start()
        if (!shouldRun && animator.isStarted) {
            animator.cancel()
            pulse = 0f
        }
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        syncAnimation()
        return changed
    }

    private fun lighten(c: Int, t: Float): Int = Color.argb(255,
        (Color.red(c) + (255 - Color.red(c)) * t).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * t).toInt())

    private fun roundPath(r: RectF, rad: Float, amp: Float): Path {
        val p = Path()
        p.moveTo(r.left + rad + j(amp), r.top + j(amp))
        p.lineTo(r.right - rad + j(amp), r.top + j(amp))
        p.quadTo(r.right + j(amp), r.top + j(amp), r.right + j(amp), r.top + rad + j(amp))
        p.lineTo(r.right + j(amp), r.bottom - rad + j(amp))
        p.quadTo(r.right + j(amp), r.bottom + j(amp), r.right - rad + j(amp), r.bottom + j(amp))
        p.lineTo(r.left + rad + j(amp), r.bottom + j(amp))
        p.quadTo(r.left + j(amp), r.bottom + j(amp), r.left + j(amp), r.bottom - rad + j(amp))
        p.lineTo(r.left + j(amp), r.top + rad + j(amp))
        p.quadTo(r.left + j(amp), r.top + j(amp), r.left + rad + j(amp), r.top + j(amp))
        p.close()
        return p
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        seed = 0x9E3779B9L // одно и то же дрожание каждый кадр: линия не «кипит»

        val inset = 9f * density
        // Дыхание тела: пока счёт не идёт, кнопка чуть «набирает воздух».
        val breath = if (running) 0f
            else 1.2f * density * kotlin.math.sin((pulse * 2.0 * Math.PI)).toFloat()
        val body = RectF(
            b.left + inset - breath, b.top + inset - breath * 0.55f,
            b.right - inset + breath, b.bottom - inset + breath * 0.55f,
        )
        val rad = body.height() / 2f

        val line = if (running) colorStop else colorGo
        val bg = if (running) fillStop else fillGo

        // Сияние: две волны, вторая отстаёт на полфазы - дыхание, а не мигание.
        if (!running) {
            for (k in 0..1) {
                val ph = (pulse + k * 0.5f) % 1f
                val grow = inset * ph
                halo.color = line
                halo.alpha = ((1f - ph) * 175).toInt().coerceIn(0, 255)
                val r = RectF(
                    body.left - grow, body.top - grow,
                    body.right + grow, body.bottom + grow,
                )
                canvas.drawPath(roundPath(r, rad + grow, 0.5f), halo)
            }
        }

        val shape = roundPath(body, rad, 0.6f)
        // Резной бевел: тень снизу-справа, светлый кант сверху-слева.
        canvas.save(); canvas.translate(1.6f * density, 1.6f * density)
        canvas.drawPath(shape, bevelSh); canvas.restore()
        bevelHi.color = lighten(line, 0.55f)
        canvas.save(); canvas.translate(-1.6f * density, -1.6f * density)
        canvas.drawPath(shape, bevelHi); canvas.restore()

        fill.color = bg
        canvas.drawPath(shape, fill)

        // Жилы разряда внутри - только в зовущем состоянии.
        if (!running) {
            canvas.save()
            canvas.clipPath(shape)
            val glow = 0.45f + 0.55f * kotlin.math.sin((pulse * 2.0 * Math.PI)).toFloat()
            vein.color = lighten(line, 0.35f)
            vein.alpha = (70f + 110f * glow).toInt().coerceIn(0, 255)
            val midY = body.centerY()
            for (s in -1..1 step 2) {
                val vp = Path()
                val x0 = body.left + body.width() * 0.16f
                val x1 = body.right - body.width() * 0.16f
                vp.moveTo(x0, midY + s * rad * 0.45f)
                vp.lineTo(x0 + (x1 - x0) * 0.28f, midY + s * rad * 0.16f)
                vp.lineTo(x0 + (x1 - x0) * 0.52f, midY + s * rad * 0.52f)
                vp.lineTo(x0 + (x1 - x0) * 0.78f, midY + s * rad * 0.20f)
                vp.lineTo(x1, midY + s * rad * 0.44f)
                canvas.drawPath(vp, vein)
            }
            canvas.restore()
        }

        // Двойной контур: тёмная основа + яркий кант.
        canvas.drawPath(shape, base)
        stroke.color = line
        stroke.alpha = 255
        canvas.drawPath(roundPath(body, rad, 0.9f), stroke)

        // Кованые гвозди по углам - как на плитах.
        val rr = 3.4f * density
        val ix = body.left + rad * 0.72f
        val ax = body.right - rad * 0.72f
        val iy = body.top + rr * 2.1f
        val ay = body.bottom - rr * 2.1f
        for (p in arrayOf(
            floatArrayOf(ix, iy), floatArrayOf(ax, iy),
            floatArrayOf(ix, ay), floatArrayOf(ax, ay),
        )) {
            canvas.drawCircle(p[0], p[1], rr, rivetRing)
            canvas.drawCircle(p[0], p[1], rr * 0.82f, rivetFill)
            canvas.drawCircle(p[0] - rr * 0.3f, p[1] - rr * 0.3f, rr * 0.30f, rivetHi)
        }
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {
        stroke.colorFilter = cf; fill.colorFilter = cf; halo.colorFilter = cf
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
