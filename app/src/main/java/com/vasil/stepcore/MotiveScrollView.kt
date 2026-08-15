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

    /**
     * X линии разлома номер i на доле высоты u.
     *
     * Лёд не рассыпается салютом одинаковых ромбов - он ЛОМАЕТСЯ ПО
     * ЛИНИЯМ. Линии заданы один раз (детерминированно от сида строки) и
     * работают дважды: сначала по ним ползут трещины в целом полотне,
     * потом ровно по ним полотно распадается на куски. Осколок - это
     * настоящий кусок листа, а не абстрактная фигура рядом.
     */
    private fun riftX(i: Int, u: Float, leftX: Float, span: Float): Float {
        val base = leftX + span * (i.toFloat() / ICE_PIECES)
        if (i == 0) return leftX
        if (i == ICE_PIECES) return leftX + span
        // Ломаная из трёх колен: наклон свой у каждой линии, изгиб - свой
        // у каждого колена. Ровная вертикаль читалась бы как разрез.
        val tilt = (rnd(i * 13 + 300) - 0.5f) * span * 0.16f
        val knee = sin((u * 3.1f + rnd(i * 7 + 310) * 6.28f)) * span * 0.045f
        return base + tilt * (u - 0.5f) * 2f + knee
    }

    /** Кромка горения на доле высоты u: та же ломаная, что ест полотно. */
    private fun burnEdgeX(u: Float, burnX: Float, h: Float): Float {
        val i = (u * 8f).toInt()
        val a = rnd(i + 400) - 0.45f
        val b = rnd(i + 401) - 0.45f
        val f = u * 8f - i
        return burnX + h * 0.075f * (a + (b - a) * f)
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

        // Лёд ломается раньше, чем полотно догорает до нуля: с этого
        // мига целого листа больше нет, есть куски.
        val iceBroken = !byFire && alive < ICE_BREAK

        // ================= ПОЛОТНО =================
        if (openC > 0.01f && alive > 0.001f && !iceBroken) {
            val burnX = if (byFire && alive < 1f) leftX + (rightX - leftX) * alive else rightX
            val span = (rightX - leftX).coerceAtLeast(1f)
            body.reset()
            if (byFire && alive < 1f) {
                // Кромка горения - одна ломаная на всех: по ней обрезано
                // полотно, по ней же встают языки пламени и лежит уголь.
                // Раньше кромка и огонь считались отдельно, и пламя висело
                // рядом с краем, а не НА нём.
                body.moveTo(leftX, top)
                var i = 0
                while (i <= BURN_SEGS) {
                    val u = i.toFloat() / BURN_SEGS
                    body.lineTo(burnEdgeX(u, burnX, h), top + (bottom - top) * u)
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

                // ФРОНТ ПРОМЕРЗАНИЯ идёт слева направо одной полосой.
                // Прежняя версия заливала весь лист голубым и сыпала по
                // нему четырнадцать одинаковых снежинок - сразу везде и
                // потому нигде. Мороз должен ПРИХОДИТЬ.
                val fx0 = leftX + span * (fr * 1.15f).coerceAtMost(1f)
                fx.color = 0xFFBFE3FF.toInt()
                fx.alpha = (95f * fr).toInt().coerceIn(0, 255)
                canvas.drawRect(leftX, top - h * 0.2f, fx0, bottom + h * 0.2f, fx)
                // Сам фронт: узкая яркая полоса с иглами инея.
                fxLine.color = 0xFFEAF6FF.toInt()
                fxLine.strokeWidth = 1.6f * d
                fxLine.alpha = (210f * (1f - fr * 0.4f)).toInt().coerceIn(0, 255)
                canvas.drawLine(fx0, top, fx0, bottom, fxLine)
                fxLine.strokeWidth = 1.1f * d
                for (k in 0 until 9) {
                    val ny = top + (bottom - top) * (k + 0.5f) / 9f
                    val nl = h * 0.055f * (0.4f + rnd(k + 60))
                    val nd = if (k % 2 == 0) -1f else 1f
                    canvas.drawLine(fx0, ny, fx0 - nl, ny + nl * 0.55f * nd, fxLine)
                    canvas.drawLine(fx0 - nl * 0.55f, ny + nl * 0.30f * nd,
                        fx0 - nl * 0.30f, ny + nl * 0.85f * nd, fxLine)
                }

                // ТРЕЩИНЫ РАСТУТ ПО БУДУЩИМ ЛИНИЯМ РАЗЛОМА - лист
                // заранее показывает, где сломается.
                val ck = ((fr - 0.30f) / 0.70f).coerceIn(0f, 1f)
                if (ck > 0f) {
                    for (r in 1 until ICE_PIECES) {
                        tmp.reset()
                        var first = true
                        var s = 0
                        while (s <= 10) {
                            val u = s / 10f
                            if (u > ck) break
                            val x = riftX(r, u, leftX, span)
                            val y2 = top + (bottom - top) * u
                            if (first) { tmp.moveTo(x, y2); first = false } else tmp.lineTo(x, y2)
                            s++
                        }
                        fxLine.color = 0xFFFFFFFF.toInt()
                        fxLine.strokeWidth = 2.4f * d
                        fxLine.alpha = (70f * ck).toInt().coerceIn(0, 255)
                        canvas.drawPath(tmp, fxLine)
                        fxLine.strokeWidth = 1.1f * d
                        fxLine.alpha = (235f * ck).toInt().coerceIn(0, 255)
                        canvas.drawPath(tmp, fxLine)
                    }
                }
            }

            // Помеха живёт на самом полотне - под клипом, чтобы не вылезала
            // за края свитка.
            if (glitchK > 0.01f) drawGlitch(canvas, leftX, top, rightX, bottom, h, glitchK, now)
            canvas.restore()
            canvas.drawPath(body, edge)

            // ОГОНЬ. Было: девять одинаковых язычков, двенадцать круглых
            // искр и пять круглых клубов дыма - много мелкого мусора и ни
            // одной запоминающейся формы. Стало три слоя, крупных и
            // разных: уголь по кромке, четыре больших языка, редкие
            // чешуйки пепла. Меньше элементов - больше картинки.
            if (byFire && alive < 1f) {
                val bh = bottom - top

                // 1. УГОЛЬ. Полоса вдоль ломаной кромки: снаружи чёрная,
                // изнутри раскалённая. Ширина дышит.
                tmp.reset()
                tmp.moveTo(burnEdgeX(0f, burnX, h) - h * 0.055f, top)
                var i2 = 1
                while (i2 <= BURN_SEGS) {
                    val u = i2.toFloat() / BURN_SEGS
                    tmp.lineTo(burnEdgeX(u, burnX, h) - h * 0.055f, top + bh * u)
                    i2++
                }
                i2 = BURN_SEGS
                while (i2 >= 0) {
                    val u = i2.toFloat() / BURN_SEGS
                    tmp.lineTo(burnEdgeX(u, burnX, h) + h * 0.012f, top + bh * u)
                    i2--
                }
                tmp.close()
                fx.color = 0xFF160D06.toInt(); fx.alpha = 245
                canvas.drawPath(tmp, fx)
                fxLine.color = 0xFFFFC257.toInt()
                fxLine.strokeWidth = 2.2f * d + 1.2f * d * sin((now * 0.009f).toFloat())
                fxLine.alpha = 250
                tmp.reset()
                i2 = 0
                while (i2 <= BURN_SEGS) {
                    val u = i2.toFloat() / BURN_SEGS
                    val x = burnEdgeX(u, burnX, h)
                    if (i2 == 0) tmp.moveTo(x, top) else tmp.lineTo(x, top + bh * u)
                    i2++
                }
                canvas.drawPath(tmp, fxLine)

                // 2. ЯЗЫКИ. Четыре крупных, каждый со своей фазой и своей
                // высотой; растут ИЗ кромки, а не рядом с ней.
                for (k in 0 until 4) {
                    val u = (k + 0.5f) / 4f
                    val ly = top + bh * u
                    val lx = burnEdgeX(u, burnX, h)
                    val fl = 0.45f + 0.55f *
                        (0.5f + 0.5f * sin((now * 0.0075f + k * 1.9f).toFloat()))
                    val lh = bh * (0.34f + 0.30f * fl)
                    val lean = h * 0.16f * fl
                    tmp.reset()
                    tmp.moveTo(lx - h * 0.03f, ly + bh * 0.10f)
                    tmp.cubicTo(lx + lean * 0.4f, ly - lh * 0.25f,
                        lx - lean * 0.2f, ly - lh * 0.60f,
                        lx + lean, ly - lh)
                    tmp.cubicTo(lx + lean * 1.2f, ly - lh * 0.45f,
                        lx + h * 0.11f, ly - lh * 0.15f,
                        lx + h * 0.05f, ly + bh * 0.10f)
                    tmp.close()
                    fx.color = 0xFFD8371A.toInt()
                    fx.alpha = (180f + 60f * fl).toInt().coerceIn(0, 255)
                    canvas.drawPath(tmp, fx)
                    // Ядро языка: тот же силуэт, сжатый к основанию.
                    canvas.save()
                    canvas.translate(lx, ly)
                    canvas.scale(0.55f, 0.55f)
                    canvas.translate(-lx, -ly)
                    fx.color = 0xFFFFD25A.toInt(); fx.alpha = 235
                    canvas.drawPath(tmp, fx)
                    canvas.restore()
                }

                // 3. ПЕПЕЛ. Редкие чешуйки: вытянутые, кувыркаются и
                // гаснут. Круглых искр нет - зола не шарик.
                for (k in 0 until 7) {
                    var g = (rnd(k + 5) + (1f - alive) * 1.15f) % 1f
                    if (g < 0f) g += 1f
                    val ax = burnX + (rnd(k + 15) - 0.4f) * h * 0.30f + g * h * 0.30f
                    val ay = bottom - g * bh * 1.7f
                    canvas.save()
                    canvas.translate(ax, ay)
                    canvas.rotate(g * 520f * (if (k % 2 == 0) 1f else -1f))
                    fx.color = if (k % 3 == 0) 0xFFFFB347.toInt() else 0xFF7A6A5C.toInt()
                    fx.alpha = (225f * (1f - g)).toInt().coerceIn(0, 255)
                    val s = (1.5f + 2.2f * (1f - g)) * d
                    canvas.drawRect(-s * 1.6f, -s * 0.45f, s * 1.6f, s * 0.45f, fx)
                    canvas.restore()
                }
            }
        }

        // ================= ЛЁД: ОСКОЛКИ =================
        // Куски НАСТОЯЩЕГО листа, отломанные по тем же линиям, вдоль
        // которых только что росли трещины: тот же пергамент, тот же
        // голубой налёт, тот же кант. Их пять, а не двенадцать - глаз
        // успевает прочитать каждый. Падают с ускорением, вращаются
        // вокруг собственного центра и тают.
        if (!byFire && t > PHASE_DEATH * ICE_BREAK_T && t < PHASE_GLITCH) {
            val k = ((t - PHASE_DEATH * ICE_BREAK_T) /
                (PHASE_GLITCH - PHASE_DEATH * ICE_BREAK_T)).coerceIn(0f, 1f)
            val span = fullR - fullL
            val bh = bottom - top
            for (p in 0 until ICE_PIECES) {
                tmp.reset()
                var s = 0
                while (s <= 8) {
                    val u = s / 8f
                    val x = riftX(p, u, fullL, span)
                    if (s == 0) tmp.moveTo(x, top) else tmp.lineTo(x, top + bh * u)
                    s++
                }
                s = 8
                while (s >= 0) {
                    val u = s / 8f
                    tmp.lineTo(riftX(p + 1, u, fullL, span), top + bh * u)
                    s--
                }
                tmp.close()

                // Разлёт: наружные куски уходят дальше, средние почти
                // падают отвесно - так ломается лист, а не взрывается.
                val mid = (p + 0.5f) / ICE_PIECES - 0.5f
                val dx = mid * span * 0.55f * k
                val dy = bh * 1.9f * k * k - bh * 0.10f * k
                val cxp = fullL + span * ((p + 0.5f) / ICE_PIECES)
                val cyp = (top + bottom) / 2f
                val a = 46f * k * mid * 4f

                canvas.save()
                canvas.translate(dx, dy)
                canvas.rotate(a, cxp, cyp)
                canvas.save()
                canvas.clipPath(tmp)
                val al = (1f - k * k)
                parchment.alpha = (255f * al).toInt().coerceIn(0, 255)
                canvas.drawRect(fullL, top, fullR, bottom, parchment)
                fx.color = 0xFFBFE3FF.toInt(); fx.alpha = (150f * al).toInt().coerceIn(0, 255)
                canvas.drawRect(fullL, top, fullR, bottom, fx)
                // Блик по грани скола: кусок ловит свет, пока летит.
                fx.color = 0xFFFFFFFF.toInt(); fx.alpha = (120f * al * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawRect(cxp - span * 0.03f, top, cxp + span * 0.02f, bottom, fx)
                canvas.restore()
                fxLine.color = 0xFFEAF6FF.toInt()
                fxLine.strokeWidth = 1.3f * d
                fxLine.alpha = (215f * al).toInt().coerceIn(0, 255)
                canvas.drawPath(tmp, fxLine)
                canvas.restore()
            }
            parchment.alpha = 255

            // Крупинки: то, что осталось от кромок. Пять штук, не рой.
            for (p in 0 until 5) {
                val g = (k + rnd(p + 200)) % 1f
                val gx = fullL + span * rnd(p + 210)
                val gy = (top + bottom) / 2f + bh * 1.6f * g * g
                fx.color = 0xFFDCEEFF.toInt()
                fx.alpha = (200f * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawCircle(gx, gy, (1.4f - 0.8f * k) * d, fx)
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
     * Ветер вместо помехи.
     *
     * Прежняя помеха была набором прямоугольных срезов: на волнистом
     * полотне прямой срез читается как чужой объект, а не как состояние
     * самого свитка. Прямоугольник убран целиком.
     *
     * Теперь полотно РВЁТ ВЕТРОМ: изогнутые полосы сдуваются в сторону
     * по той же волне, что и лист, кромки размываются струями, в воздухе
     * летит цветная пыль. Сила эффекта - тот же k, что и раньше, поэтому
     * хореография номера не изменилась ни на кадр.
     */
    private fun drawGlitch(
        c: Canvas, l: Float, t0: Float, r: Float, b: Float,
        h: Float, k: Float, now: Long
    ) {
        val kk = k.coerceIn(0f, 1f)
        val span = (r - l).coerceAtLeast(1f)
        val hh = (b - t0).coerceAtLeast(1f)
        val tt = now * 0.001f

        // Полосы сдуваемого полотна: у каждой свой изгиб и свой снос.
        for (i in 0 until 5) {
            val n = rnd(i * 5 + 200)
            var g = tt * (0.55f + 0.18f * i) + n
            g -= Math.floor(g.toDouble()).toFloat()
            val y = t0 + hh * ((0.10f + 0.19f * i) % 1f)
            val bandH = hh * (0.06f + 0.10f * n) * kk
            val dx = (0.35f + 0.65f * g) * span * 0.5f * kk * (if (i % 2 == 0) 1f else -1f)
            val sag = hh * 0.10f * kk * sin((tt * 2.1f + i).toFloat())
            tmp.reset()
            tmp.moveTo(l + dx, y)
            tmp.quadTo(l + dx + span * 0.5f, y + sag, l + dx + span, y + sag * 0.3f)
            tmp.lineTo(l + dx + span, y + sag * 0.3f + bandH)
            tmp.quadTo(l + dx + span * 0.5f, y + sag + bandH, l + dx, y + bandH)
            tmp.close()
            fx.color = when (i % 3) {
                0 -> TINT_MAGENTA
                1 -> TINT_CYAN
                else -> TINT_AMBER
            }
            val fade = sin(g * Math.PI.toFloat())
            fx.alpha = (150f * kk * fade).toInt().coerceIn(0, 255)
            c.drawPath(tmp, fx)
        }

        // Струи ветра поперёк листа: тонкие дуги, гаснут к концам пролёта.
        for (i in 0 until 6) {
            var g = tt * (0.7f + 0.13f * i) + i * 0.166f
            g -= Math.floor(g.toDouble()).toFloat()
            val y = t0 + hh * (((i * 37) % 100) / 100f)
            val x0 = l - span * 0.3f + g * span * 1.6f
            val len = span * 0.35f
            val sag = hh * 0.07f * sin((tt * 1.9f + i).toFloat())
            tmp.reset()
            tmp.moveTo(x0, y)
            tmp.quadTo(x0 + len * 0.5f, y + sag, x0 + len, y + sag * 0.4f)
            fxLine.color = if (i % 2 == 0) TINT_CYAN else TINT_MAGENTA
            fxLine.strokeWidth = (0.8f + 1.4f * kk) * d
            fxLine.alpha = (170f * kk * sin(g * Math.PI.toFloat())).toInt().coerceIn(0, 255)
            c.drawLine(x0, y, x0 + len, y + sag * 0.4f, fxLine)
        }

        // Цветная пыль, которую несёт тем же ветром.
        for (i in 0 until 12) {
            var g = tt * (0.9f + 0.11f * (i % 4)) + i * 0.083f
            g -= Math.floor(g.toDouble()).toFloat()
            val x = l - span * 0.1f + g * span * 1.2f
            val y = t0 + hh * (((i * 61) % 100) / 100f) +
                hh * 0.06f * sin((tt * 2.6f + i).toFloat())
            fx.color = if (i % 3 == 0) TINT_AMBER else TINT_CYAN
            fx.alpha = (200f * kk * sin(g * Math.PI.toFloat())).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, (0.8f + 1.2f * (1f - g)) * d, fx)
        }
        fx.alpha = 255
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
        // Гибель во льду: на сколько кусков ломается лист и когда.
        const val ICE_PIECES = 5
        /** Доля фазы гибели, на которой лист уже раскололся. */
        const val ICE_BREAK_T = 0.62f
        /**
         * Остаток жизни листа, ниже которого целого полотна уже нет.
         * 1 - smooth(0.62) = 0.32; взято 0.30, чтобы кадр-другой первые
         * куски отходили от ещё живого листа - скол не бывает мгновенным.
         */
        const val ICE_BREAK = 0.30f
        /** Сегментов в ломаной кромке горения. */
        const val BURN_SEGS = 9
        // Волна полотна.
        const val WAVE_SEGMENTS = 26
        const val WAVES = 1.6f
    }
}
