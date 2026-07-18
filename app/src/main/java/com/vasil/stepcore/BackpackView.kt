package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Рюкзак-индикатор груза.
 *
 * Вес читается мгновенно и без цифр: чем тяжелее, тем крупнее и «горячее»
 * рюкзак. Пороги взяты человеческие, а не абстрактные: до 5 кг переноска
 * почти не ощущается (зелёный), 5-8 кг уже заметна (жёлтый), 8-10 кг -
 * тяжело (оранжевый), выше 10 кг - перегруз (красный + тревожная
 * пульсация, будто швы вот-вот разойдутся).
 *
 * Анимация идёт ТОЛЬКО когда груз включён: при нуле вид статичный, и
 * лишних перерисовок нет.
 */
class BackpackView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var kg = 0f
    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    fun setLoad(value: Float) {
        if (kg == value) return
        kg = value
        invalidate()
    }

    private fun tone(): Int = when {
        kg <= 0f -> 0xFF6C7480.toInt()
        kg <= 5f -> 0xFF19D45C.toInt()
        kg <= 8f -> 0xFFEFC02A.toInt()
        kg <= 10f -> 0xFFEF7C1F.toInt()
        else -> 0xFFE23636.toInt()
    }

    private fun darker(c: Int, t: Float) = Color.argb(255,
        (Color.red(c) * (1 - t)).toInt(),
        (Color.green(c) * (1 - t)).toInt(),
        (Color.blue(c) * (1 - t)).toInt())

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val col = tone()
        val over = kg > 10f
        val ph = BoilClock.phase

        // Раздувание: плавно растёт до 14 кг, дальше упирается в потолок.
        val grow = 0.72f + 0.30f * (kg.coerceAtMost(14f) / 14f)
        val breath = if (kg <= 0f) 0f else
            (if (over) 0.075f else 0.022f) *
                kotlin.math.sin((ph * (if (over) 7.5f else 2.2f)).toDouble()).toFloat()
        val sx = grow * (1f + breath)
        val sy = grow * (1f - breath * 0.55f)

        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.scale(sx, sy)
        if (over) canvas.rotate(1.6f * kotlin.math.sin((ph * 11f).toDouble()).toFloat())

        val bw = w * 0.62f; val bh = h * 0.60f
        // Лямки.
        line.color = darker(col, 0.55f); line.strokeWidth = 3f * d
        canvas.drawLine(-bw * 0.28f, -bh * 0.62f, -bw * 0.34f, bh * 0.30f, line)
        canvas.drawLine(bw * 0.28f, -bh * 0.62f, bw * 0.34f, bh * 0.30f, line)
        // Корпус.
        val body = Path()
        body.addRoundRect(-bw / 2f, -bh * 0.52f, bw / 2f, bh * 0.62f,
            bw * 0.26f, bw * 0.26f, Path.Direction.CW)
        fill.color = col; fill.alpha = 235
        canvas.drawPath(body, fill)
        // Клапан и пряжка.
        fill.color = darker(col, 0.35f); fill.alpha = 245
        val flap = Path()
        flap.addRoundRect(-bw * 0.46f, -bh * 0.52f, bw * 0.46f, -bh * 0.02f,
            bw * 0.22f, bw * 0.22f, Path.Direction.CW)
        canvas.drawPath(flap, fill)
        fill.color = 0xFF1A1A1A.toInt(); fill.alpha = 235
        canvas.drawRect(-bw * 0.10f, -bh * 0.12f, bw * 0.10f, bh * 0.06f, fill)
        // Карман.
        line.color = darker(col, 0.45f); line.strokeWidth = 2f * d
        canvas.drawLine(-bw * 0.30f, bh * 0.30f, bw * 0.30f, bh * 0.30f, line)
        // Контур.
        line.color = 0xFF0A0D12.toInt(); line.strokeWidth = 2.4f * d
        canvas.drawPath(body, line)

        // Перегруз: трещины по швам, чтобы «вот-вот лопнет» читалось.
        if (over) {
            val fl = 0.5f + 0.5f * kotlin.math.sin((ph * 9f).toDouble()).toFloat()
            line.color = 0xFFFFE0A0.toInt()
            line.alpha = (110f + 120f * fl).toInt().coerceIn(0, 255)
            line.strokeWidth = 1.6f * d
            canvas.drawLine(-bw * 0.42f, bh * 0.10f, -bw * 0.24f, bh * 0.24f, line)
            canvas.drawLine(bw * 0.40f, -bh * 0.06f, bw * 0.22f, bh * 0.12f, line)
            line.alpha = 255
        }
        canvas.restore()

        if (kg > 0f) postInvalidateDelayed(60)
    }
}
