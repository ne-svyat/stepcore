package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Пергаментный свиток с «мыслью» приложения.
 *
 * Смена строки - маленький фильм: старый свиток гибнет (через раз в огне
 * или во льду), рассыпается помехой, и из этой помехи разворачивается
 * новый, после чего построчно проступает текст.
 *
 * Хореография:
 *   0 .. DEATH    гибель: огонь или лёд
 *   .. GLITCH     полотно теряет сигнал: срезы, расслоение по цвету
 *   .. BIRTH      валики расходятся, полотно разворачивается ВОЛНОЙ
 *   .. TOTAL      текст проступает волной, буква за буквой
 *
 * v394 - волна как основа облика:
 *  - Полотно больше не прямоугольник. Верхняя и нижняя кромки идут одной
 *    и той же синусоидой: ткань, а не бумажка. Амплитуда велика в момент
 *    раскрытия и затухает по easeOut; в покое остаётся «дыхание» около
 *    0.6dp с периодом 7 с - глазом читается как живое полотно, а кадр в
 *    покое по-прежнему редкий.
 *  - По полотну идёт затенение ПО ТОЙ ЖЕ волне (провисание ткани):
 *    столбцы прозрачной тени, яркость которых берётся из производной
 *    волны. Это даёт объём без единой картинки.
 *  - Текст проявляется бегущей по строке волной: каждая буква всплывает
 *    и наливается чернилами со сдвигом фазы по x. Прежнее построчное
 *    проявление сохранено как огибающая, буквенная волна идёт поверх.
 *  - Валики получили продольный градиент и торцевые шайбы: круглые, а не
 *    плоские полоски.
 *  - Под свитком мягкая тень, по кромкам полотна - виньетка.
 *
 * Что было сломано до v276 (уроки сохранены):
 *  - кривая раскрытия имела ПРОВАЛ: множитель (1 + 0.06·sin(k·2π)) на
 *    k≈0.75 давал −1, и свиток откатывался назад перед финалом. Заменено
 *    на честный easeOutBack - один перелёт в самом конце и возврат.
 *  - первый показ стартовал с t = BIRTH, то есть раскрытие пропускалось
 *    целиком и свиток просто возникал. Теперь первый показ начинается с
 *    фазы рождения и разворачивается как все прочие.
 *  - блик по полотну шёл, пока полотно ещё закрыто. Теперь строго после
 *    полного раскрытия.
 *
 * Кадры тратятся только во время номера. В покое свиток почти статичен.
 */
class MotiveScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density

    private var current = ""
    private var pending: String? = null
    private var seqStart = 0L
    private var byFire = true
    private var seed = 12345L

    private val parchment = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFE8DCC0.toInt()
    }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x33000000
    }
    private val stain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val roller = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rollerLit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFC79B5C.toInt()
    }
    private val rollerDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF4E3A20.toInt()
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = 0xFF6B5C3C.toInt()
    }
    private val fiber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
        color = 0xFFC9BC98.toInt(); alpha = 150
    }
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF4A3B22.toInt()
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
    }
    private val body = Path()
    private val tmp = Path()

    /** Ширина валика, для которой построен градиент. Пересобираем при смене. */
    private var rollerShaderW = -1f

    fun show(line: String) {
        if (line == current && pending == null && current.isNotEmpty()) return
        pending = line
        if (current.isEmpty()) {
            // Первый показ: гибели нет, но раскрытие обязано быть - иначе
            // свиток «возникает», а не разворачивается.
            current = line; pending = null
            seqStart = System.currentTimeMillis() - PHASE_GLITCH.toLong()
        } else {
            seqStart = System.currentTimeMillis()
            byFire = !byFire
        }
        seed = (line.hashCode().toLong() and 0xFFFF) + 7
        invalidate()
    }

    /** Устойчивый шум: узор гибели одинаков в каждом кадре одного номера. */
    private fun rnd(i: Int): Float {
        var z = seed * 6364136223846793005L + i * 1442695040888963407L
        z = (z xor (z ushr 33)) * -0x7ee3623a03d3c83fL
        return ((z ushr 40).toInt() and 0xFFFF) / 65535f
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    /**
     * Упругий выход: один перелёт в САМОМ конце и возврат к единице.
     * Именно этого не хватало прежней кривой - она проваливалась в
     * середине и доходила до края уже без всякой упругости.
     */
    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val k = (x - 1f)
        return 1f + c3 * k * k * k + c1 * k * k
    }

    /**
     * Смещение кромки полотна по волне. Одна функция на верх, низ,
     * затенение и текст - иначе слои разъедутся и обман развалится.
     *
     * u - доля вдоль полотна [0..1], amp - амплитуда в пикселях,
     * phase - фаза бегущей волны.
     */
    private fun wave(u: Float, amp: Float, phase: Float): Float {
        if (amp <= 0f) return 0f
        val a = sin(u * WAVES * 2f * Math.PI.toFloat() + phase)
        val b = 0.45f * sin(u * WAVES * 3.1f * Math.PI.toFloat() - phase * 0.7f)
        return amp * (a + b) / 1.45f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()
        val t = (now - seqStart).toFloat()
        val running = t < PHASE_TOTAL

        val rollW = h * 0.20f
        val top = h * 0.13f
        val bottom = h * 0.87f
        val midX = w / 2f
        val fullL = rollW * 0.55f
        val fullR = w - rollW * 0.55f

        var open = 1f
        var alive = 1f
        var textK = 0f
        var glitchK = 0f
        var birthK = 1f
        when {
            t < PHASE_DEATH -> alive = 1f - smooth((t / PHASE_DEATH).coerceIn(0f, 1f))
            t < PHASE_GLITCH -> {
                alive = 0f; open = 0f
                glitchK = ((t - PHASE_DEATH) / (PHASE_GLITCH - PHASE_DEATH)).coerceIn(0f, 1f)
            }
            t < PHASE_BIRTH -> {
                alive = 1f
                val k = ((t - PHASE_GLITCH) / (PHASE_BIRTH - PHASE_GLITCH)).coerceIn(0f, 1f)
                open = easeOutBack(k)
                birthK = k
                // Остаточная помеха гаснет по мере раскрытия.
                glitchK = (1f - k) * 0.55f
                if (pending != null) { current = pending!!; pending = null }
            }
            t < PHASE_TOTAL -> {
                textK = (t - PHASE_BIRTH) / (PHASE_TOTAL - PHASE_BIRTH)
                birthK = 1f + (t - PHASE_BIRTH) / (PHASE_TOTAL - PHASE_BIRTH)
            }
            else -> { textK = 1f; birthK = 2f }
        }
        val openC = open.coerceIn(0f, 1.08f)
        val leftX = midX - (midX - fullL) * openC
        val rightX = midX + (fullR - midX) * openC

        // Амплитуда волны: велика в раскрытии, затухает после него,
        // в покое остаётся дыхание. Затухание квадратичное - ткань
        // успокаивается быстро, но не обрывом.
        val settle = (2f - birthK).coerceIn(0f, 1f)
        val waveAmp = h * (0.005f + 0.085f * settle * settle)
        val phase = (now % 4200L) / 4200f * 2f * Math.PI.toFloat() * -1f

        // ================= ПОЛОТНО =================
        if (openC > 0.01f && alive > 0.001f) {
            val burnX = if (byFire && alive < 1f) leftX + (rightX - leftX) * alive else rightX
            val span = (rightX - leftX).coerceAtLeast(1f)
            body.reset()
            if (byFire && alive < 1f) {
                // Рваная кромка горения, а не прямой срез.
                body.moveTo(leftX, top)
                body.lineTo(burnX + h * 0.03f * (rnd(1) - 0.5f), top)
                var y = top
                var i = 0
                while (y < bottom) {
                    val jag = h * 0.055f * (rnd(i + 2) - 0.4f)
                    y += (bottom - top) / 7f
                    body.lineTo(burnX + jag, y.coerceAtMost(bottom))
                    i++
                }
                body.lineTo(leftX, bottom)
                body.close()
            } else {
                // Волнистое полотно: верх и низ идут одной волной,
                // поэтому высота листа постоянна - он гнётся, а не тянется.
                body.moveTo(leftX, top + wave(0f, waveAmp, phase))
                for (i in 1..WAVE_SEGMENTS) {
                    val u = i.toFloat() / WAVE_SEGMENTS
                    body.lineTo(leftX + span * u, top + wave(u, waveAmp, phase))
                }
                body.lineTo(rightX, bottom + wave(1f, waveAmp, phase))
                for (i in WAVE_SEGMENTS - 1 downTo 0) {
                    val u = i.toFloat() / WAVE_SEGMENTS
                    body.lineTo(leftX + span * u, bottom + wave(u, waveAmp, phase))
                }
                body.close()
            }

            // Мягкая тень под полотном - свиток отрывается от фона.
            if (alive > 0.99f) {
                canvas.save()
                canvas.translate(0f, 3f * d)
                canvas.drawPath(body, shadow)
                canvas.restore()
            }

            canvas.save()
            canvas.clipPath(body)
            canvas.drawRect(leftX, top - h * 0.2f, rightX, bottom + h * 0.2f, parchment)
            for (i in 0 until 5) {
                stain.color = if (i % 2 == 0) 0xFFB79A63.toInt() else 0xFFD8E4EE.toInt()
                stain.alpha = 50
                canvas.drawCircle(leftX + span * (0.10f + 0.2f * i),
                    top + (bottom - top) * (0.2f + 0.6f * rnd(i)),
                    h * (0.04f + 0.05f * rnd(i + 9)), stain)
            }
            // Волокна идут по волне: прямая линия на волнистом листе
            // выдала бы плоскость.
            var fk = 0.22f
            while (fk < 1f) {
                val fy = top + (bottom - top) * fk
                var px = leftX + 7f * d
                var py = fy + wave(7f * d / span, waveAmp, phase)
                var i = 1
                while (i <= WAVE_SEGMENTS) {
                    val u = (7f * d + (span - 14f * d) * i / WAVE_SEGMENTS) / span
                    val nx = leftX + span * u
                    val ny = fy + wave(u, waveAmp, phase)
                    canvas.drawLine(px, py, nx, ny, fiber)
                    px = nx; py = ny
                    i++
                }
                fk += 0.19f
            }

            // Затенение по волне: там, где ткань уходит от света, ложится
            // тень. Яркость берём из производной волны - получается
            // объём без единого растрового ресурса.
            if (waveAmp > 0.6f * d) {
                for (i in 0 until WAVE_SEGMENTS) {
                    val u = i.toFloat() / WAVE_SEGMENTS
                    val slope = (wave(u + 0.02f, waveAmp, phase) -
                            wave(u - 0.02f, waveAmp, phase)) / (0.04f * span)
                    val a = (slope * 110f)
                    if (a > 0f) {
                        shade.color = 0xFF3A2C14.toInt()
                        shade.alpha = a.coerceIn(0f, 70f).toInt()
                    } else {
                        shade.color = 0xFFFFF6DC.toInt()
                        shade.alpha = (-a).coerceIn(0f, 70f).toInt()
                    }
                    canvas.drawRect(leftX + span * u, top - h * 0.2f,
                        leftX + span * (u + 1f / WAVE_SEGMENTS) + 0.6f, bottom + h * 0.2f, shade)
                }
            }
            // Виньетка по кромкам: край листа всегда темнее середины.
            shade.color = 0xFF6B5C3C.toInt()
            for (i in 0 until 6) {
                shade.alpha = 26 - i * 4
                canvas.drawRect(leftX + i * 1.4f * d, top - h * 0.2f,
                    leftX + (i + 1) * 1.4f * d, bottom + h * 0.2f, shade)
                canvas.drawRect(rightX - (i + 1) * 1.4f * d, top - h * 0.2f,
                    rightX - i * 1.4f * d, bottom + h * 0.2f, shade)
            }

            if (!byFire && alive < 1f) {
                val fr = 1f - alive
                // Иней ползёт от обоих краёв кристаллами.
                fx.color = 0xFFBFE3FF.toInt(); fx.alpha = (120f * fr).toInt().coerceIn(0, 255)
                canvas.drawRect(leftX, top, rightX, bottom, fx)
                fxLine.color = 0xFFFFFFFF.toInt(); fxLine.strokeWidth = 1.3f * d
                for (i in 0 until 14) {
                    val side = if (i % 2 == 0) 0f else 1f
                    val reach = (rightX - leftX) * 0.5f * fr
                    val cx = if (side == 0f) leftX + reach * rnd(i + 20)
                             else rightX - reach * rnd(i + 30)
                    val cy = top + (bottom - top) * rnd(i + 40)
                    fxLine.alpha = (200f * fr).toInt().coerceIn(0, 255)
                    val rr = h * 0.06f * (0.5f + rnd(i + 50))
                    for (k in 0 until 3) {
                        val a = Math.toRadians((k * 60f + 15f).toDouble())
                        canvas.drawLine(cx - rr * cos(a).toFloat(), cy - rr * sin(a).toFloat(),
                            cx + rr * cos(a).toFloat(), cy + rr * sin(a).toFloat(), fxLine)
                    }
                }
                // Ветвящиеся трещины прорастают по мере промерзания.
                if (fr > 0.45f) {
                    val ck = ((fr - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    fxLine.color = 0xFFEAF6FF.toInt(); fxLine.alpha = 235
                    fxLine.strokeWidth = 1.8f * d
                    for (i in 0 until 4) {
                        var x = leftX + (rightX - leftX) * (0.2f + 0.2f * i)
                        var y2 = top
                        val steps = (6 * ck).toInt().coerceAtLeast(1)
                        for (s in 0 until steps) {
                            val nx = x + h * 0.10f * (rnd(i * 7 + s) - 0.5f)
                            val ny = y2 + (bottom - top) / 6f
                            canvas.drawLine(x, y2, nx, ny, fxLine)
                            if (s == 2) canvas.drawLine(nx, ny, nx + h * 0.16f, ny + h * 0.10f, fxLine)
                            x = nx; y2 = ny
                        }
                    }
                }
            }

            // Помеха живёт на самом полотне - под клипом, чтобы не вылезала
            // за края свитка.
            if (glitchK > 0.01f) drawGlitch(canvas, leftX, top, rightX, bottom, h, glitchK, now)
            canvas.restore()
            canvas.drawPath(body, edge)

            // Огонь: обугливание, языки пламени, дым, искры.
            if (byFire && alive < 1f) {
                fx.color = 0xFF1C1208.toInt(); fx.alpha = 240
                canvas.drawRect(burnX - h * 0.05f, top, burnX + h * 0.02f, bottom, fx)
                for (i in 0 until 9) {
                    val ly = top + (bottom - top) * (i / 8f)
                    val fl = 0.55f + 0.45f * sin((now * 0.012f + i).toFloat())
                    tmp.reset()
                    tmp.moveTo(burnX - h * 0.02f, ly)
                    tmp.quadTo(burnX + h * 0.10f * fl, ly - h * 0.10f * fl,
                        burnX + h * 0.04f, ly - h * 0.20f * fl)
                    tmp.quadTo(burnX + h * 0.02f * fl, ly - h * 0.06f, burnX - h * 0.02f, ly)
                    fx.color = if (i % 2 == 0) 0xFFFF9A2E.toInt() else 0xFFE2521F.toInt()
                    fx.alpha = 225
                    canvas.drawPath(tmp, fx)
                }
                fxLine.color = 0xFFFFE08A.toInt(); fxLine.alpha = 245; fxLine.strokeWidth = 2.6f * d
                canvas.drawLine(burnX, top + 2f * d, burnX, bottom - 2f * d, fxLine)
                for (i in 0 until 12) {
                    val g = (rnd(i) + (1f - alive) * 1.3f) % 1f
                    fx.color = if (i % 3 == 0) 0xFFFFF0B0.toInt() else 0xFFFFB347.toInt()
                    fx.alpha = (240f * (1f - g)).toInt().coerceIn(0, 255)
                    val ex = burnX + (rnd(i + 5) - 0.35f) * h * 0.5f + g * h * 0.25f
                    val ey = bottom - g * (bottom - top) * 1.9f + h * 0.35f * g * g
                    canvas.drawCircle(ex, ey, (1.2f + 1.8f * (1f - g)) * d, fx)
                }
                for (i in 0 until 5) {
                    val g = (rnd(i + 60) + (1f - alive) * 0.9f) % 1f
                    fx.color = 0xFF6B6459.toInt()
                    fx.alpha = (90f * (1f - g)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(burnX + (rnd(i + 70) - 0.5f) * h * 0.4f,
                        top - g * h * 0.7f, h * (0.10f + 0.22f * g), fx)
                }
            }
        }

        // ================= ЛЁД: ОСКОЛКИ =================
        if (!byFire && t in (PHASE_DEATH * 0.82f)..PHASE_GLITCH) {
            val k = ((t - PHASE_DEATH * 0.82f) / (PHASE_GLITCH - PHASE_DEATH * 0.82f)).coerceIn(0f, 1f)
            for (i in 0 until 12) {
                val a = Math.PI * (0.08 + 0.84 * i / 11.0)
                val sp = 0.6f + 0.5f * rnd(i + 80)
                val dx = cos(a).toFloat() * w * 0.6f * k * sp
                val dy = -sin(a).toFloat() * h * 1.0f * k * sp + h * 2.0f * k * k
                canvas.save()
                canvas.translate(fullL + (fullR - fullL) * (0.06f + 0.08f * i) + dx,
                    (top + bottom) / 2f + dy)
                canvas.rotate(420f * k * (if (i % 2 == 0) 1f else -1f))
                val s = h * (0.07f + 0.05f * rnd(i + 90))
                tmp.reset()
                tmp.moveTo(0f, -s); tmp.lineTo(s * 0.8f, -s * 0.1f)
                tmp.lineTo(s * 0.25f, s); tmp.lineTo(-s * 0.7f, s * 0.2f); tmp.close()
                fx.color = 0xFFDCEEFF.toInt(); fx.alpha = (240f * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawPath(tmp, fx)
                fx.color = 0xFFFFFFFF.toInt(); fx.alpha = (200f * (1f - k)).toInt().coerceIn(0, 255)
                tmp.reset()
                tmp.moveTo(0f, -s); tmp.lineTo(s * 0.8f, -s * 0.1f); tmp.lineTo(0f, 0f); tmp.close()
                canvas.drawPath(tmp, fx)
                canvas.restore()
            }
        }

        // ================= ПОМЕХА В ПУСТОТЕ =================
        // Между гибелью и рождением остаётся сам сигнал: узкая полоса
        // помехи там, где сейчас свёрнут свиток.
        if (openC <= 0.01f && glitchK > 0.01f) {
            val cy = (top + bottom) / 2f
            val half = h * (0.03f + 0.20f * glitchK)
            drawGlitch(canvas, midX - h * 0.34f, cy - half, midX + h * 0.34f, cy + half,
                h, glitchK, now)
            fxLine.color = TINT_CYAN
            fxLine.alpha = (200f * glitchK).toInt().coerceIn(0, 255)
            fxLine.strokeWidth = 1.6f * d
            canvas.drawLine(midX - h * 0.34f, cy, midX + h * 0.34f, cy, fxLine)
        }

        // ================= ВАЛИКИ =================
        if (openC > 0.01f) {
            if (rollerShaderW != rollW) {
                rollerShaderW = rollW
                roller.shader = LinearGradient(
                    -rollW * 0.42f, 0f, rollW * 0.42f, 0f,
                    intArrayOf(0xFF4A3418.toInt(), 0xFFB0854A.toInt(),
                        0xFF8A6636.toInt(), 0xFF3E2C13.toInt()),
                    floatArrayOf(0f, 0.32f, 0.62f, 1f), Shader.TileMode.CLAMP)
            }
            for (cx in floatArrayOf(leftX - rollW * 0.02f, rightX + rollW * 0.02f)) {
                canvas.save()
                canvas.translate(cx, 0f)
                canvas.drawRoundRect(-rollW * 0.42f, top - h * 0.06f,
                    rollW * 0.42f, bottom + h * 0.06f, rollW * 0.4f, rollW * 0.4f, roller)
                canvas.restore()
                // Торцевые шайбы: валик обязан быть круглым на концах.
                canvas.drawOval(cx - rollW * 0.50f, top - h * 0.12f,
                    cx + rollW * 0.50f, top - h * 0.01f, rollerDark)
                canvas.drawOval(cx - rollW * 0.50f, bottom + h * 0.01f,
                    cx + rollW * 0.50f, bottom + h * 0.12f, rollerDark)
                canvas.drawOval(cx - rollW * 0.30f, top - h * 0.095f,
                    cx + rollW * 0.30f, top - h * 0.035f, rollerLit)
                canvas.drawOval(cx - rollW * 0.30f, bottom + h * 0.035f,
                    cx + rollW * 0.30f, bottom + h * 0.095f, rollerLit)
                // Продольный блик - тонкая светлая жила по оси валика.
                fx.color = 0xFFE8C58B.toInt(); fx.alpha = 90
                canvas.drawRoundRect(cx - rollW * 0.10f, top - h * 0.03f,
                    cx - rollW * 0.02f, bottom + h * 0.03f, rollW * 0.1f, rollW * 0.1f, fx)
            }
            // Блик пробегает по полотну ПОСЛЕ полного раскрытия: раньше он
            // шёл по ещё закрытому свитку и читался как мусор.
            if (t in PHASE_BIRTH..(PHASE_BIRTH + 420f)) {
                val k = ((t - PHASE_BIRTH) / 420f).coerceIn(0f, 1f)
                fx.color = 0xFFFFFFFF.toInt(); fx.alpha = (85f * (1f - k)).toInt().coerceIn(0, 255)
                val gx = leftX + (rightX - leftX) * k
                canvas.drawRect(gx - h * 0.10f, top, gx + h * 0.10f, bottom, fx)
            }
        }

        // ================= ТЕКСТ =================
        if (textK > 0.01f && current.isNotEmpty()) {
            val span = (rightX - leftX).coerceAtLeast(1f)
            val maxW = span - 14f * d
            val maxH = (bottom - top) - 6f * d
            var size = h * 0.26f
            var lines: List<String> = emptyList()
            while (size > 6.5f * d) {
                text.textSize = size
                lines = wrapLines(current, maxW, MAX_LINES)
                val fm = text.fontMetrics
                val lineH = (fm.descent - fm.ascent) * 0.94f
                if (lines.size <= MAX_LINES && lines.all { text.measureText(it) <= maxW } &&
                    lines.size * lineH <= maxH) break
                size -= 0.5f * d
            }
            text.textSize = size
            val shim = 0.5f + 0.5f * sin(((now % 6000L) / 6000.0 * 2.0 * Math.PI)).toFloat()
            val inkColor = Color.rgb(
                (0x4A + (0x6B - 0x4A) * shim).toInt(),
                (0x3B + (0x50 - 0x3B) * shim).toInt(),
                (0x22 + (0x2E - 0x22) * shim).toInt())
            val fm = text.fontMetrics
            val lineH = (fm.descent - fm.ascent) * 0.94f
            var y2 = (top + bottom) / 2f - lines.size * lineH / 2f - fm.ascent * 0.94f
            // Строки проявляются снизу вверх - как проступающие чернила,
            // а внутри строки бежит буквенная волна.
            for ((i, ln) in lines.withIndex()) {
                val share = (lines.size - i).toFloat() / lines.size
                val lineA = ((textK - (1f - share) * 0.5f) / 0.5f).coerceIn(0f, 1f)
                if (lineA <= 0.001f) { y2 += lineH; continue }
                val lw = text.measureText(ln)
                var cxPen = midX - lw / 2f
                for (ci in ln.indices) {
                    val ch = ln.substring(ci, ci + 1)
                    val cw = text.measureText(ch)
                    val u = ((cxPen + cw / 2f) - leftX) / span
                    // Волна проявления бежит слева направо: буква
                    // сначала всплывает, потом наливается чернилами.
                    val ca = ((lineA - 0.35f * (1f - u)) / 0.65f).coerceIn(0f, 1f)
                    if (ca > 0.004f) {
                        // Буква лежит на полотне: та же волна, что у листа.
                        val ride = wave(u, waveAmp, phase) * 0.8f
                        val lift = (1f - ca) * lineH * 0.55f
                        val by = y2 + ride + lift
                        if (ca < 0.92f && ((now / 90L + i) % 11L) == 0L) {
                            text.color = if ((now / 90L) % 2L == 0L) TINT_MAGENTA else TINT_CYAN
                            text.alpha = (110f * ca).toInt().coerceIn(0, 255)
                            canvas.drawText(ch, cxPen + 2.2f * d, by, text)
                        }
                        text.color = inkColor
                        text.alpha = (238f * ca).toInt().coerceIn(0, 255)
                        canvas.drawText(ch, cxPen, by, text)
                    }
                    cxPen += cw
                }
                y2 += lineH
            }
        }

        // В покое кадры редкие: живёт только медленное дыхание волны.
        if (running) postInvalidateOnAnimation() else postInvalidateDelayed(140)
    }

    /**
     * Помеха: горизонтальные срезы уезжают в стороны, поверх идёт
     * расслоение по цвету и редкие строки развёртки. Оттенки взяты из
     * палитры приложения, чтобы помеха принадлежала этому экрану, а не
     * выглядела чужим эффектом.
     */
    private fun drawGlitch(
        c: Canvas, l: Float, t0: Float, r: Float, b: Float,
        h: Float, k: Float, now: Long
    ) {
        val kk = k.coerceIn(0f, 1f)
        val frame = (now / 70L).toInt()
        val slices = 7
        for (i in 0 until slices) {
            val n = rnd(frame * 13 + i * 5 + 200)
            if (n < 0.35f) continue
            val y = t0 + (b - t0) * rnd(frame * 7 + i + 210)
            val hh = (b - t0) * (0.02f + 0.10f * n) * kk
            val dx = (rnd(frame * 3 + i + 220) - 0.5f) * (r - l) * 0.55f * kk
            fx.color = when (i % 3) {
                0 -> TINT_MAGENTA
                1 -> TINT_CYAN
                else -> TINT_AMBER
            }
            fx.alpha = (170f * kk * (0.45f + 0.55f * n)).toInt().coerceIn(0, 255)
            c.drawRect(l + dx, y, r + dx, y + hh, fx)
        }
        // Расслоение по цвету: два полупрозрачных дубля полотна со сдвигом.
        val off = 3.2f * d * kk
        fx.color = TINT_MAGENTA; fx.alpha = (55f * kk).toInt().coerceIn(0, 255)
        c.drawRect(l - off, t0, r - off, b, fx)
        fx.color = TINT_CYAN; fx.alpha = (55f * kk).toInt().coerceIn(0, 255)
        c.drawRect(l + off, t0, r + off, b, fx)
        // Строки развёртки.
        fxLine.color = 0xFF000000.toInt()
        fxLine.alpha = (40f * kk).toInt().coerceIn(0, 255)
        fxLine.strokeWidth = 1f * d
        var sy = t0 + (frame % 4) * 1.5f * d
        while (sy < b) {
            c.drawLine(l, sy, r, sy, fxLine)
            sy += 4f * d
        }
    }

    private fun wrapLines(src: String, maxW: Float, limit: Int): List<String> {
        val words = src.split(' ')
        val out = ArrayList<String>()
        var line = StringBuilder()
        for (word in words) {
            val probe = if (line.isEmpty()) word else line.toString() + " " + word
            if (text.measureText(probe) <= maxW || line.isEmpty()) {
                line = StringBuilder(probe)
            } else {
                out.add(line.toString())
                line = StringBuilder(word)
                if (out.size == limit) break
            }
        }
        if (out.size < limit && line.isNotEmpty()) out.add(line.toString())
        return out
    }

    private companion object {
        const val MAX_LINES = 5
        // Оттенки помехи - из палитры приложения.
        val TINT_MAGENTA = 0xFFFF4BC8.toInt()
        val TINT_CYAN = 0xFF3AE8E0.toInt()
        val TINT_AMBER = 0xFFEF9F27.toInt()
        // Хореография номера, мс.
        const val PHASE_DEATH = 1400f
        const val PHASE_GLITCH = 2150f
        const val PHASE_BIRTH = 3150f
        const val PHASE_TOTAL = 4300f
        // Волна полотна.
        const val WAVE_SEGMENTS = 26
        const val WAVES = 1.6f
    }
}
