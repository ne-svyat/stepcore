package com.vasil.stepcore

import android.animation.ValueAnimator
import android.graphics.Canvas
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
 * «Старт» и потерял сорок минут ходьбы. Это не косметика: приложение,
 * которое молча не считает шаги, не выполняет свою единственную работу.
 * Кнопка обязана кричать о том, что счёт не идёт, — и молчать, когда всё
 * в порядке.
 *
 * ПРИНЦИП: тревожит только НЕрабочее состояние.
 *   - счёт не идёт -> зелёная «Старт» + пульсирующее сияние. Пропустить
 *     это взглядом невозможно, и палец сам тянется нажать;
 *   - счёт идёт -> красная «Стоп», ровная, без анимации. Нажатие здесь
 *     ОСТАНАВЛИВАЕТ работу, поэтому цвет предупреждающий, а не зовущий.
 *
 * Пульс рисуется ВНУТРЬ границ: фон кнопки обрезается по её краям, и сияние,
 * растущее наружу, было бы просто срезано. Поэтому тело кнопки вписано
 * с отступом, а волна расходится в этот отступ.
 *
 * Анимация живёт только пока кнопка видима и пока счёт не идёт: остановленный
 * ValueAnimator не тратит ни кадра.
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
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
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
        // Стартовое состояние — «счёт не идёт», и пульс должен идти СРАЗУ.
        // Без этого он бы включался только при первой смене состояния — то
        // есть ровно тогда, когда предупреждать уже поздно.
        syncAnimation()
    }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        syncAnimation()
        invalidateSelf()
    }

    private fun syncAnimation() {
        // Пульсируем только тогда, когда счёт НЕ идёт: это и есть сигнал.
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
        val body = RectF(
            b.left + inset, b.top + inset,
            b.right - inset, b.bottom - inset,
        )
        val rad = body.height() / 2f

        val line = if (running) colorStop else colorGo
        val bg = if (running) fillStop else fillGo

        // Сияние: две волны, расходящиеся в отступ. Вторая отстаёт на полфазы,
        // поэтому пульс читается как дыхание, а не как мигание лампочки.
        if (!running) {
            for (k in 0..1) {
                val ph = (pulse + k * 0.5f) % 1f
                val grow = inset * ph
                val alpha = ((1f - ph) * 150).toInt().coerceIn(0, 255)
                halo.color = line
                halo.alpha = alpha
                val r = RectF(
                    body.left - grow, body.top - grow,
                    body.right + grow, body.bottom + grow,
                )
                canvas.drawPath(roundPath(r, rad + grow, 0.5f), halo)
            }
        }

        fill.color = bg
        canvas.drawPath(roundPath(body, rad, 0.6f), fill)
        stroke.color = line
        stroke.alpha = 255
        canvas.drawPath(roundPath(body, rad, 0.9f), stroke)
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) {
        stroke.colorFilter = cf; fill.colorFilter = cf; halo.colorFilter = cf
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
