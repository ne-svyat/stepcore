package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.exp
import kotlin.math.sin

/**
 * КНОПКА ВХОДА В ТАЙНИК.
 *
 * Обычная кнопка на этом экране проигрывает сундуку: он живёт, она нет, и
 * взгляд перестаёт их считать одной вещью. Здесь кнопка сделана как
 * ЗАМОЧНАЯ ПЛАСТИНА того же сундука: то же железо, тот же тёплый свет.
 *
 * Что живёт:
 *  - по пластине раз в несколько секунд пробегает блик - железо ловит свет;
 *  - в покое края тихо дышат;
 *  - нажатие вдавливает пластину (сжатие и потемнение) - палец чувствует
 *    отклик глазом, а не только вибрацией;
 *  - во время проверки секрета по кругу бежит дуга: работа идёт, и видно,
 *    что приложение не зависло;
 *  - отказ - затухающая дрожь и красный тон, тот же язык, что у сундука.
 *
 * Текст рисуется сама вьюха: отдельный TextView внутри дал бы вторую точку
 * правды о надписи.
 */
class VaultOpenButton(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = 0xFF17111F.toInt()
    }
    private val arc = Path()

    private var label = "Открыть"
    /**
     * Прежняя надпись и время смены: текст не подменяется рывком, а
     * УХОДИТ и на его место ПРИХОДИТ новый. Подмена в один кадр читается
     * как сбой отрисовки, особенно на кнопке, которую в этот миг нажали.
     */
    private var prevLabel = ""
    private var labelAt = 0L
    private var busy = false
    private var pressK = 0f
    private var deniedAt = 0L
    private var shaderW = -1f

    /** Надпись. Единственный источник правды о том, что на кнопке. */
    fun setLabel(s: String) {
        if (label == s) return
        prevLabel = label
        label = s
        labelAt = System.currentTimeMillis()
        invalidate()
    }

    /** Идёт проверка: по кнопке бежит дуга, нажатие не принимается. */
    fun setBusy(b: Boolean) {
        if (busy == b) return
        busy = b
        invalidate()
    }

    /** Не подошло: короткая дрожь. */
    fun denied() {
        deniedAt = System.currentTimeMillis()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressK = 1f; invalidate(); return true }
            MotionEvent.ACTION_CANCEL -> { pressK = 0f; invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                pressK = 0f
                invalidate()
                // Отпустили В ПРЕДЕЛАХ кнопки - это нажатие. Ушли за край -
                // передумали, и это тоже надо уважать.
                val inside = event.x >= 0 && event.x <= width &&
                    event.y >= 0 && event.y <= height
                if (inside && !busy) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** Смена надписи, мс. Короче - рывок, длиннее - кнопка тормозит. */
        const val LABEL_MS = 240f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()

        val denyAge = (now - deniedAt).toFloat()
        val deny = if (deniedAt == 0L) 0f else (1f - denyAge / 620f).coerceIn(0f, 1f)
        val shake = if (deny > 0f)
            deny * 7f * d * (exp(-1.6 * (1f - deny).toDouble()) *
                sin((1f - deny) * 3.2 * 2.0 * Math.PI)).toFloat() else 0f

        canvas.save()
        canvas.translate(shake, 0f)
        // Вдавливание: пластина уходит внутрь, а не просто темнеет.
        val press = pressK
        val inset = press * 2.5f * d
        val l = inset
        val t = inset * 0.7f
        val r = w - inset
        val b = h - inset * 0.7f
        val rad = h * 0.34f

        // Тень под пластиной пропадает при нажатии - она села на место.
        fill.color = 0xFF000000.toInt()
        fill.alpha = (70 * (1f - press)).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(l, t + 3f * d, r, b + 3f * d, rad, rad, fill)

        if (shaderW != w) {
            shaderW = w
            fill.shader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(0xFFD9C7F5.toInt(), 0xFFB79BE8.toInt(), 0xFF8E74C4.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        fill.alpha = 255
        canvas.drawRoundRect(l, t, r, b, rad, rad, fill)
        fill.shader = null

        if (press > 0f) {
            // Вдавливание темнеет НЕРАВНОМЕРНО: сильнее там, где палец.
            // Ровная заливка поверх кнопки выглядит плёнкой.
            fill.shader = android.graphics.RadialGradient((l + r) / 2f, (t + b) / 2f,
                (r - l) * 0.7f, 0x66000000, 0x14000000, Shader.TileMode.CLAMP)
            fill.alpha = (255 * press).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(l, t, r, b, rad, rad, fill)
            fill.shader = null
            fill.alpha = 255
        }
        if (deny > 0f) {
            fill.color = 0xFFFF3B3B.toInt(); fill.alpha = (110 * deny).toInt()
            canvas.drawRoundRect(l, t, r, b, rad, rad, fill)
        }

        // БЛИК РАСТЯЖКОЙ, А НЕ ПОЛОСОЙ.
        //
        // Прежде блик был прямоугольником с ровными краями: по кнопке
        // ехал светлый брусок, и это читалось именно как брусок. У
        // растяжки края нет - есть только свет, гаснущий в обе стороны.
        // Он же слегка наклонён: вертикальный блик по горизонтальной
        // кнопке выглядит приклеенным.
        val sweep = ((now % 5200L) / 5200f)
        if (sweep < 0.34f) {
            val k = sweep / 0.34f
            val gx = l + (r - l + w * 0.5f) * k - w * 0.25f
            val band = w * 0.20f
            canvas.save()
            arc.reset()
            arc.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW)
            canvas.clipPath(arc)
            fill.shader = LinearGradient(gx - band, t, gx + band, b,
                intArrayOf(0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            fill.alpha = (255f * sin((k * Math.PI).toFloat())).toInt().coerceIn(0, 255)
            canvas.drawRect(l, t, r, b, fill)
            fill.shader = null
            fill.alpha = 255
            canvas.restore()
        }

        // Кант: дышит в покое, гаснет при нажатии.
        val breath = 0.5f + 0.5f * sin(now * 0.0016).toFloat()
        line.color = 0xFFF0E6FF.toInt()
        line.alpha = ((70f + 60f * breath) * (1f - press * 0.6f)).toInt().coerceIn(0, 255)
        line.strokeWidth = 1.6f * d
        canvas.drawRoundRect(l, t, r, b, rad, rad, line)

        // Надпись: прежняя уходит вверх и тает, новая приходит снизу.
        // Обе рисуются в одном кадре, поэтому кнопка не мигает пустотой.
        text.textSize = h * 0.30f
        val fm = text.fontMetrics
        val baseY = (t + b) / 2f - (fm.ascent + fm.descent) / 2f
        val swap = if (labelAt == 0L) 1f
        else ((now - labelAt).toFloat() / LABEL_MS).coerceIn(0f, 1f)
        // Замедление к концу: буквы садятся мягко, а не тормозят стеной.
        val ease = 1f - (1f - swap) * (1f - swap)
        if (swap < 1f && prevLabel.isNotEmpty()) {
            text.alpha = (255f * (1f - swap)).toInt().coerceIn(0, 255)
            canvas.drawText(prevLabel, (l + r) / 2f, baseY - h * 0.30f * ease, text)
        }
        text.alpha = (255f * ease).toInt().coerceIn(0, 255)
        canvas.drawText(label, (l + r) / 2f, baseY + h * 0.30f * (1f - ease), text)
        text.alpha = 255

        // Работа: дуга бежит по канту. Видно, что замок проверяется.
        if (busy) {
            val p = ((now % 1100L) / 1100f)
            arc.reset()
            arc.addRoundRect(l + 2f * d, t + 2f * d, r - 2f * d, b - 2f * d,
                rad, rad, Path.Direction.CW)
            val pm = android.graphics.PathMeasure(arc, false)
            val len = pm.length
            val seg = len * 0.22f
            val head = len * p
            arc.reset()
            pm.getSegment(head - seg, head, arc, true)
            // Хвост дуги гаснет: три прохода уменьшающейся толщины и
            // прозрачности вместо одного ровного обрубка.
            var q = 0
            while (q < 3) {
                line.color = 0xFF17111F.toInt()
                line.alpha = (190 - q * 55).coerceIn(0, 255)
                line.strokeWidth = (2.9f - q * 0.7f) * d
                canvas.drawPath(arc, line)
                q++
            }
        }

        canvas.restore()

        val swapping = labelAt != 0L && (now - labelAt) < LABEL_MS
        if (busy || deny > 0f || press > 0f || swapping) postInvalidateOnAnimation()
        else postInvalidateDelayed(120)
    }
}
