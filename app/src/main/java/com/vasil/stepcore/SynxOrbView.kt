package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * SYNX — визуальный вход в модуль обучения (v195).
 *
 * Рисует ГОТОВУЮ картинку (главный вихрь или одну из четырёх стихий) и
 * оживляет её поверх кодом: мягкая пульсация свечения и лёгкое дыхание
 * масштаба. Сами картинки — художественные ассеты в res/drawable, код их
 * только анимирует. Никаких полигонов: рисование лиц/вихрей отдано
 * художнику, движение — коду.
 *
 * Два режима:
 *   HERO   — главный лого (все стихии в вихре). Для угла главного экрана.
 *            Пульс тихий: лого и так насыщенный.
 *   ELEMENT— одна стихия по состоянию обучения (вода=покой,
 *            электричество=зовёт, огонь=тревога, лёд=пауза). Для экрана
 *            SYNX. Пульс живее, добавляется медленное вращение.
 *
 * Данные и счёт шагов НЕ трогает — это только визуал.
 */
class SynxOrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Element { WATER, ELECTRIC, FIRE, ICE }
    enum class Mode { HERO, ELEMENT }

    private val d = resources.displayMetrics.density
    private var mode = Mode.HERO
    private var element = Element.WATER
    private var intensity = 0.35f
    private var phase = 0f
    private var spin = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var drawable: Drawable? = null

    init {
        loadDrawable()
    }

    /** Главный лого для угла главного экрана. */
    fun setHero() {
        mode = Mode.HERO
        loadDrawable()
        invalidate()
    }

    /** Одна стихия по состоянию обучения (для экрана SYNX). */
    fun setElement(e: Element, intensity01: Float) {
        mode = Mode.ELEMENT
        element = e
        intensity = intensity01.coerceIn(0f, 1f)
        loadDrawable()
        invalidate()
    }

    private fun loadDrawable() {
        val res = when (mode) {
            Mode.HERO -> R.drawable.synx_main
            Mode.ELEMENT -> when (element) {
                Element.WATER -> R.drawable.synx_water
                Element.ELECTRIC -> R.drawable.synx_electric
                Element.FIRE -> R.drawable.synx_fire
                Element.ICE -> R.drawable.synx_ice
            }
        }
        drawable = ContextCompat.getDrawable(context, res)
    }

    /** Цвет свечения-ореола под стихией. */
    private fun glowColor(): Int = when {
        mode == Mode.HERO -> 0xFF4A9EDB.toInt()
        element == Element.WATER -> 0xFF2DAFEB.toInt()
        element == Element.ELECTRIC -> 0xFF7A5CFF.toInt()
        element == Element.FIRE -> 0xFFFF7A2D.toInt()
        else -> 0xFF8CDEFF.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f
        if (r <= 0) return

        // фаза: в HERO тихо, в ELEMENT живее (и тем живее, чем настойчивее)
        val speed = if (mode == Mode.HERO) 0.012f else 0.02f + 0.05f * intensity
        phase += speed
        val breath = 0.5f + 0.5f * sin(phase.toDouble()).toFloat()

        // ореол-свечение под картинкой (дышит)
        val glow = glowColor()
        val haloAlpha = if (mode == Mode.HERO) (30 + 30 * breath) else (45 + 55 * breath)
        paint.shader = RadialGradient(
            cx, cy, r * 1.15f,
            intArrayOf(argb(haloAlpha.toInt(), glow), Color.TRANSPARENT),
            floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP
        )
        paint.colorFilter = null
        canvas.drawCircle(cx, cy, r * 1.15f, paint)
        paint.shader = null

        // сама картинка с лёгким дыханием масштаба
        val dr = drawable ?: return
        val pulse = 1f + (if (mode == Mode.HERO) 0.015f else 0.03f) * breath
        val half = (r * pulse).toInt()

        canvas.save()
        // медленное вращение только для стихий (не для главного лого)
        if (mode == Mode.ELEMENT) {
            spin += 0.15f + 0.35f * intensity
            canvas.rotate(spin, cx, cy)
        }
        dr.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        dr.draw(canvas)
        canvas.restore()

        postInvalidateOnAnimation()
    }

    private fun argb(alpha: Int, c: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}

