package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * СУНДУК ТАЙНИКА.
 *
 * Прежняя редакция была набором прямоугольников: короб, крышка-дуга,
 * кружок замка. Формы без материала и движение без веса - оттого и
 * дёшево. Здесь переделано и то, и другое.
 *
 * МАТЕРИАЛ. Сундук собран из досок: каждая со своим тоном, между ними
 * тёмные швы, поверх идут волокна. Железо отдельным слоем: два обруча с
 * заклёпками, угловые накладки, петли, замочная плата с дужкой. Свет
 * падает слева сверху - оттуда светлые грани, справа тени. Крышка имеет
 * толщину и внутреннюю сторону: когда откидывается, видно изнанку и
 * пустое нутро, а не голый фон.
 *
 * ДВИЖЕНИЕ. Ни одного линейного «от 0 до 1»:
 *  - крышка ходит на ПРУЖИНЕ с затуханием (перелёт и возврат), поэтому
 *    открывается тяжело и садится мягко;
 *  - предметы летят по НАСТОЯЩЕЙ баллистике: своя начальная скорость,
 *    ускорение вниз, сопротивление воздуха по горизонтали, своя угловая
 *    скорость. Ни один не повторяет другой;
 *  - отказ - затухающие колебания короба, а не рывок в сторону;
 *  - искры от дужки летят с гравитацией и гаснут.
 *
 * СОСТОЯНИЯ (у каждого свой смысл, а не просто другой цвет):
 *  CLOSED   - покой: дыхание скважины, пыль, редкий щелчок язычка;
 *  TOUCHED  - набирают пароль: крышка подпрыгивает и садится пружиной;
 *  STRAINING- проверяется секрет: крышку тянет вверх, щель светится
 *             сильнее, пыль засасывает внутрь. Это честный отклик на
 *             полторы секунды scrypt, а не безмолвное ожидание;
 *  DENIED   - не подошло: крышка бьёт по коробу, искры, красная вспышка;
 *  OPENING  - подошло: дужка отлетает, крышка распахивается, изнутри
 *             бьёт свет и вылетают свитки, письма и записки.
 *
 * Границы: вьюха живёт в пакете vault и ничего не знает о ядре шагомера.
 * Кадры: полная частота только пока идёт номер, в покое - редко.
 */
class VaultChestView(context: Context) : View(context) {

    enum class Mood { CLOSED, TOUCHED, STRAINING, DENIED, OPENING }

    private val d = resources.displayMetrics.density
    private var mood = Mood.CLOSED
    private var moodAt = 0L
    private var touchedAt = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lidPath = Path()
    private val itemPath = Path()
    private var ironW = -1f

    // ------------------------------------------------------------- состояния

    /** Набирают пароль. Можно звать часто - пружина сама справится. */
    fun touched() {
        touchedAt = System.currentTimeMillis()
        if (mood == Mood.CLOSED) invalidate()
    }

    /** Секрет проверяется: крышку тянет, но замок держит. */
    fun straining() {
        mood = Mood.STRAINING
        moodAt = System.currentTimeMillis()
        invalidate()
    }

    fun denied() {
        mood = Mood.DENIED
        moodAt = System.currentTimeMillis()
        invalidate()
    }

    fun opening() {
        mood = Mood.OPENING
        moodAt = System.currentTimeMillis()
        invalidate()
    }

    // ------------------------------------------------------------- механика

    /**
     * Пружина с затуханием: подходит и для подпрыгивающей крышки, и для
     * дрожащего короба. Один закон на все упругие движения - иначе
     * каждое движение живёт по своей выдумке и они не сходятся.
     *
     * @param t доля времени 0..1, @param freq колебаний за этот отрезок
     */
    private fun spring(t: Float, freq: Float, damp: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 0f
        return (exp(-damp * t.toDouble()) *
            sin(t * freq * 2.0 * Math.PI)).toFloat()
    }

    /** Устойчивый шум: один и тот же предмет летит одинаково каждый раз. */
    private fun rnd(i: Int): Float {
        var z = (i * 6364136223846793005L) + 1442695040888963407L
        z = (z xor (z ushr 33)) * -0x7ee3623a03d3c83fL
        return ((z ushr 40).toInt() and 0xFFFF) / 65535f
    }

    private fun shade(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xFF) * k).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()
        val t = (now - moodAt).toFloat()

        // --- фазы состояний ---
        val openRaw = if (mood == Mood.OPENING) (t / OPEN_MS).coerceIn(0f, 1f) else 0f
        // Крышка идёт с перелётом: тяжёлая створка не останавливается
        // ровно там, где надо.
        val open = if (openRaw <= 0f) 0f else
            (1f - exp(-4.2 * openRaw.toDouble()).toFloat()) +
                0.10f * spring(openRaw, 1.1f, 4.5f)
        val denyK = if (mood == Mood.DENIED) (1f - t / DENY_MS).coerceIn(0f, 1f) else 0f
        if (mood == Mood.DENIED && denyK <= 0f) mood = Mood.CLOSED
        val strain = if (mood == Mood.STRAINING) ((t / 300f).coerceAtMost(1f)) else 0f
        val touch = (1f - (now - touchedAt).toFloat() / TOUCH_MS).coerceIn(0f, 1f)

        // Геометрия. Сундук стоит чуть ниже центра: над ним место для
        // того, что вылетает.
        val cx = w / 2f
        val bw = minOf(w * 0.30f, h * 0.62f)
        val bh = bw * 0.62f
        val by = h * 0.78f
        val lidH = bw * 0.46f
        val topY = by - bh

        // Дрожь короба при отказе - затухающие колебания, не рывок.
        val shake = denyK * 7f * d * spring(1f - denyK, 3.2f, 1.4f)
        canvas.save()
        canvas.translate(shake, 0f)

        // --- свет: сначала ореол под сундуком, он всегда позади ---
        val breath = 0.5f + 0.5f * sin(now * 0.0013).toFloat()
        val lightK = 0.25f + 0.20f * breath + strain * 0.55f + open * 1.2f
        glowP.color = if (denyK > 0f) 0xFFFF3B3B.toInt() else 0xFFE8A33D.toInt()
        var g = 0
        while (g < 4) {
            // Ореол еле заметен: на тёмном фоне даже слабая заливка
            // рисует видимый эллипс, и сундук оказывался в «блюдце».
            glowP.alpha = ((11 - g * 2) * (lightK + denyK * 1.4f)).toInt().coerceIn(0, 255)
            canvas.drawOval(cx - bw * (1.25f + 0.4f * g), topY - lidH * (0.6f + 0.5f * g),
                cx + bw * (1.25f + 0.4f * g), by + bh * 0.35f * (1f + 0.2f * g), glowP)
            g++
        }

        // --- столб света из щели: виден, когда крышка идёт вверх ---
        if (open > 0.02f || strain > 0.02f) {
            val k = (open * 0.9f + strain * 0.25f).coerceAtMost(1f)
            glowP.color = 0xFFFFD98A.toInt()
            var i = 0
            while (i < 3) {
                glowP.alpha = ((30 - i * 9) * k).toInt().coerceIn(0, 255)
                // Уже и мягче прежнего: широкий конус читался как
                // нарисованный треугольник, а не как свет.
                val spread = bw * (0.28f + 0.30f * i) * (0.4f + 0.6f * k)
                itemPath.reset()
                itemPath.moveTo(cx - bw * 0.82f, topY)
                itemPath.lineTo(cx + bw * 0.82f, topY)
                itemPath.lineTo(cx + spread * 1.6f, topY - h * 0.55f * k)
                itemPath.lineTo(cx - spread * 1.6f, topY - h * 0.55f * k)
                itemPath.close()
                canvas.drawPath(itemPath, glowP)
                i++
            }
        }

        // --- содержимое вылетает: настоящая баллистика ---
        if (open > 0f) {
            drawFlyingItems(canvas, cx, topY, bw, h, openRaw)
        }

        // --- нутро сундука: видно, когда крышка поднялась ---
        if (open > 0.05f) {
            fill.color = 0xFF120C06.toInt(); fill.alpha = 255
            canvas.drawRect(cx - bw * 0.92f, topY - bh * 0.10f * open,
                cx + bw * 0.92f, topY + bh * 0.32f, fill)
            // Свет изнутри ложится на заднюю стенку.
            fill.color = 0xFFFFC257.toInt()
            fill.alpha = (110f * open).toInt().coerceIn(0, 255)
            canvas.drawRect(cx - bw * 0.86f, topY, cx + bw * 0.86f, topY + bh * 0.16f, fill)
            fill.alpha = 255
        }

        drawBox(canvas, cx, by, bw, bh, denyK)
        drawLid(canvas, cx, topY, bw, lidH, open, touch, strain, denyK)
        if (open < 0.35f) drawLock(canvas, cx, topY, by, bw, bh, open, strain, denyK, breath)

        // --- искры от дужки при отказе: с весом, гаснут на лету ---
        if (denyK > 0f) {
            var i = 0
            while (i < 7) {
                val k = (1f - denyK) * (0.7f + 0.5f * rnd(i))
                val a = -2.5f + rnd(i + 11) * 2.0f
                val sx = cx + cos(a.toDouble()).toFloat() * bw * 0.9f * k
                val sy = topY + bh * 0.1f + sin(a.toDouble()).toFloat() * bw * 0.7f * k +
                    bw * 1.6f * k * k
                fill.color = if (i % 2 == 0) 0xFFFFD79A.toInt() else 0xFFFF6B4B.toInt()
                fill.alpha = (240f * denyK * (1f - k)).toInt().coerceIn(0, 255)
                canvas.drawCircle(sx, sy, (1.9f - 1.1f * k) * d, fill)
                i++
            }
            fill.alpha = 255
        }

        // --- пыль: в покое всплывает, при натуге затягивается в щель ---
        var i = 0
        while (i < 8) {
            val ph = now * 0.0006f * (0.7f + 0.2f * (i % 4)) + rnd(i + 40) * 6.3f
            val bob = (sin(ph.toDouble()).toFloat() + 1f) * 0.5f
            val dx0 = cx + (rnd(i + 50) - 0.5f) * bw * 2.6f
            val dy0 = topY - lidH * 0.4f - h * 0.16f * bob
            // Натуга: пылинку тянет к щели над замком.
            val dx = dx0 + (cx - dx0) * strain * 0.55f
            val dy = dy0 + (topY - dy0) * strain * 0.45f
            fill.color = 0xFFE8A33D.toInt()
            fill.alpha = (105f * (0.4f + 0.6f * sin((ph * 1.7f).toDouble()).toFloat()) *
                (1f - open * 0.7f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(dx, dy, (1.1f + 0.5f * bob) * d, fill)
            i++
        }
        fill.alpha = 255

        canvas.restore()

        val busy = (mood == Mood.OPENING && openRaw < 1f) || denyK > 0f ||
            touch > 0f || mood == Mood.STRAINING
        if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(140)
    }

    // ------------------------------------------------------------- части

    /** Короб: доски со швами и волокном, железо, угловые накладки. */
    private fun drawBox(c: Canvas, cx: Float, by: Float, bw: Float, bh: Float, deny: Float) {
        val left = cx - bw
        val right = cx + bw
        val top = by - bh
        val bot = by + bh * 0.34f

        // Доски: каждая своим тоном, свет слева.
        val planks = 5
        var i = 0
        while (i < planks) {
            val x0 = left + (right - left) * i / planks
            val x1 = left + (right - left) * (i + 1) / planks
            val lightK = 1.18f - 0.34f * (i / (planks - 1f))
            fill.color = shade(WOOD, lightK)
            fill.alpha = 255
            c.drawRect(x0, top, x1, bot, fill)
            // Шов между досками - тёмная щель, а не линия поверх.
            fill.color = shade(WOOD, 0.45f)
            c.drawRect(x1 - 0.9f * d, top, x1 + 0.9f * d, bot, fill)
            // Волокно: две-три дуги на доску.
            line.color = shade(WOOD, lightK * 0.72f)
            line.alpha = 190
            line.strokeWidth = 1.1f * d
            var k = 0
            while (k < 3) {
                val gy = top + (bot - top) * (0.22f + 0.28f * k) + rnd(i * 7 + k) * bh * 0.10f
                itemPath.reset()
                itemPath.moveTo(x0 + 1.5f * d, gy)
                itemPath.quadTo((x0 + x1) / 2f, gy + bh * (0.06f * (rnd(i + k) - 0.5f)),
                    x1 - 1.5f * d, gy + bh * 0.02f)
                c.drawPath(itemPath, line)
                k++
            }
            i++
        }

        // Тень внутри у нижней кромки: короб стоит, а не висит.
        fill.color = 0xFF000000.toInt(); fill.alpha = 70
        c.drawRect(left, bot - bh * 0.14f, right, bot, fill)
        fill.alpha = 255

        // Железо: два обруча с заклёпками.
        i = 0
        while (i < 2) {
            val bx = cx + (if (i == 0) -1f else 1f) * bw * 0.55f
            drawIronBand(c, bx, top, bot, bw * 0.10f)
            i++
        }
        // Угловые накладки.
        drawCorner(c, left, top, bw * 0.26f, bh * 0.34f, 1f, 1f)
        drawCorner(c, right, top, bw * 0.26f, bh * 0.34f, -1f, 1f)
        drawCorner(c, left, bot, bw * 0.26f, bh * 0.34f, 1f, -1f)
        drawCorner(c, right, bot, bw * 0.26f, bh * 0.34f, -1f, -1f)

        // Кант короба.
        line.color = shade(IRON, 1.25f); line.alpha = 255; line.strokeWidth = 1.8f * d
        c.drawRect(left, top, right, bot, line)
        if (deny > 0f) {
            fill.color = 0xFFFF3B3B.toInt()
            fill.alpha = (70f * deny).toInt().coerceIn(0, 255)
            c.drawRect(left, top, right, bot, fill)
            fill.alpha = 255
        }
    }

    /** Полоса железа с заклёпками: градиент даёт округлость. */
    private fun drawIronBand(c: Canvas, x: Float, top: Float, bot: Float, halfW: Float) {
        if (ironW != halfW) {
            ironW = halfW
            fill.shader = LinearGradient(-halfW, 0f, halfW, 0f,
                intArrayOf(shade(IRON, 0.55f), shade(IRON, 1.35f), shade(IRON, 0.70f)),
                floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
        }
        c.save()
        c.translate(x, 0f)
        fill.alpha = 255
        c.drawRect(-halfW, top, halfW, bot, fill)
        c.restore()
        fill.shader = null
        // Заклёпки: светлая шапка и тень снизу.
        var i = 0
        while (i < 3) {
            val ry = top + (bot - top) * (0.18f + 0.32f * i)
            fill.color = shade(IRON, 0.45f)
            c.drawCircle(x, ry + 0.8f * d, halfW * 0.42f, fill)
            fill.color = shade(IRON, 1.45f)
            c.drawCircle(x - halfW * 0.10f, ry - 0.4f * d, halfW * 0.36f, fill)
            i++
        }
    }

    /** Угловая накладка: две полосы, сходящиеся в углу. */
    private fun drawCorner(c: Canvas, x: Float, y: Float, lw: Float, lh: Float,
                           sx: Float, sy: Float) {
        fill.color = shade(IRON, 1.15f); fill.alpha = 255
        c.drawRect(minOf(x, x + sx * lw), minOf(y, y + sy * 3.2f * d),
            maxOf(x, x + sx * lw), maxOf(y, y + sy * 3.2f * d), fill)
        c.drawRect(minOf(x, x + sx * 3.2f * d), minOf(y, y + sy * lh),
            maxOf(x, x + sx * 3.2f * d), maxOf(y, y + sy * lh), fill)
        fill.color = shade(IRON, 1.5f)
        c.drawCircle(x + sx * lw * 0.55f, y + sy * 3.0f * d, 1.5f * d, fill)
        c.drawCircle(x + sx * 3.0f * d, y + sy * lh * 0.55f, 1.5f * d, fill)
    }

    /**
     * КРЫШКА ОТКРЫВАЕТСЯ РАКУРСОМ, А НЕ ПОВОРОТОМ ХОЛСТА.
     *
     * Что было не так. Крышку я разворачивал на 104 градуса вокруг
     * передней кромки. В трёхмерном сундуке это верно, но мы смотрим
     * СПЕРЕДИ и рисуем плоско: поворот плоской фигуры вокруг ГОРИЗОНТАЛЬНОЙ
     * оси нельзя изобразить поворотом холста - холст вращает только вокруг
     * оси, перпендикулярной экрану. Оттого крышка и уезжала через весь
     * сундук наискось, как отдельная доска.
     *
     * Как правильно. При взгляде спереди открывающаяся крышка не едет вбок
     * - она СЖИМАЕТСЯ по высоте (ракурс) и уходит вверх-назад. За 90
     * градусов лицевая сторона схлопывается в линию, дальше показывается
     * изнанка, растущая обратно. Считается через косинус угла - ровно так,
     * как проекция и работает.
     *
     * Мелкие движения - подпрыгивание от набора, натуга, удар при отказе -
     * остаются поворотом: там углы малые, и поворот их изображает верно.
     */
    private fun drawLid(c: Canvas, cx: Float, topY: Float, bw: Float, lidH: Float,
                        open: Float, touch: Float, strain: Float, deny: Float) {
        val jump = touch * 3.2f * spring(1f - touch, 1.6f, 2.2f)
        val pull = strain * (1.6f + 1.0f * sin((System.currentTimeMillis() * 0.006).toDouble())
            .toFloat())
        val slam = deny * 2.6f * spring(1f - deny, 3.2f, 1.6f)

        // Угол раскрытия и его проекция. cos < 0 - смотрим на изнанку.
        val phi = (108f * open) * Math.PI.toFloat() / 180f
        val proj = cos(phi.toDouble()).toFloat()
        val inside = proj < 0f
        val faceH = lidH * abs(proj)
        // Подъём и отход назад: чем шире открыта, тем выше кромка и тем
        // уже крышка - дальний край всегда мельче.
        val lift = lidH * 0.55f * sin(phi.toDouble()).toFloat()
        val narrow = 1f - 0.10f * sin(phi.toDouble()).toFloat()

        c.save()
        c.rotate(-jump - pull + slam, cx, topY)
        c.translate(0f, -lift)

        val lw = bw * narrow
        val faceTop = topY - faceH

        // Купол крышки строится под текущий ракурс: дуга сплющивается
        // вместе с ней, а не остаётся прежней.
        lidPath.reset()
        lidPath.moveTo(cx - lw, topY)
        lidPath.lineTo(cx - lw, faceTop + faceH * 0.66f)
        lidPath.quadTo(cx, faceTop - faceH * 0.30f, cx + lw, faceTop + faceH * 0.66f)
        lidPath.lineTo(cx + lw, topY)
        lidPath.close()

        c.save()
        c.clipPath(lidPath)
        if (inside) {
            // Изнанка: тёмное дерево, поперечные рейки и полоса света с
            // той стороны, где сейчас нутро.
            fill.color = shade(WOOD, 0.50f); fill.alpha = 255
            c.drawRect(cx - lw, faceTop - faceH, cx + lw, topY, fill)
            line.color = shade(WOOD, 0.34f); line.alpha = 220; line.strokeWidth = 1.3f * d
            var k = 0
            while (k < 3) {
                val gy = topY - faceH * (0.25f + 0.26f * k)
                c.drawLine(cx - lw * 0.9f, gy, cx + lw * 0.9f, gy, line)
                k++
            }
            fill.color = 0xFFFFC257.toInt(); fill.alpha = 60
            c.drawRect(cx - lw, topY - faceH * 0.22f, cx + lw, topY, fill)
            fill.alpha = 255
        } else {
            val planks = 4
            var k = 0
            while (k < planks) {
                val x0 = cx - lw + 2 * lw * k / planks
                val x1 = cx - lw + 2 * lw * (k + 1) / planks
                fill.color = shade(WOOD, 1.22f - 0.30f * (k / (planks - 1f)))
                fill.alpha = 255
                c.drawRect(x0, faceTop - faceH, x1, topY, fill)
                fill.color = shade(WOOD, 0.45f)
                c.drawRect(x1 - 0.9f * d, faceTop - faceH, x1 + 0.9f * d, topY, fill)
                k++
            }
            // Блик по дуге: сплющивается вместе с крышкой.
            fill.color = 0xFFFFFFFF.toInt(); fill.alpha = 40
            c.drawOval(cx - lw * 0.72f, faceTop - faceH * 0.10f, cx + lw * 0.16f,
                faceTop + faceH * 0.45f, fill)
            fill.alpha = 255
        }
        c.restore()

        // Торец крышки: тонкая полоса, которая ВИДНА тем сильнее, чем
        // ближе крышка к вертикали. Она и даёт толщину.
        val edgeH = lidH * 0.16f * abs(sin(phi.toDouble()).toFloat())
        if (edgeH > 0.5f * d) {
            fill.color = shade(WOOD, 0.72f); fill.alpha = 255
            c.drawRect(cx - lw, faceTop - faceH - edgeH, cx + lw, faceTop - faceH + 1f, fill)
        }

        // Железо крышки: те же обручи, тоже под ракурсом.
        if (!inside) {
            var k = 0
            while (k < 2) {
                val bx = cx + (if (k == 0) -1f else 1f) * lw * 0.55f
                fill.color = shade(IRON, if (k == 0) 1.3f else 0.85f); fill.alpha = 255
                c.drawRect(bx - lw * 0.10f, faceTop - faceH * 0.05f, bx + lw * 0.10f,
                    topY, fill)
                fill.color = shade(IRON, 1.5f)
                c.drawCircle(bx, topY - faceH * 0.45f, lw * 0.040f, fill)
                k++
            }
        }
        line.color = shade(IRON, 1.2f); line.strokeWidth = 1.8f * d; line.alpha = 255
        c.drawPath(lidPath, line)
        c.restore()

        // Петли остаются на кромке короба: крышка ходит вокруг них.
        var k2 = 0
        while (k2 < 2) {
            val hx = cx + (if (k2 == 0) -1f else 1f) * bw * 0.78f
            fill.color = shade(IRON, 1.35f); fill.alpha = 255
            c.drawCircle(hx, topY, 3.4f * d, fill)
            fill.color = shade(IRON, 0.5f)
            c.drawCircle(hx, topY, 1.5f * d, fill)
            k2++
        }
    }

    /** Замочная плата с дужкой: дужка отлетает при открытии. */
    private fun drawLock(c: Canvas, cx: Float, topY: Float, by: Float, bw: Float, bh: Float,
                         open: Float, strain: Float, deny: Float, breath: Float) {
        val ly = topY + bh * 0.42f
        val pw = bw * 0.20f
        val ph = bh * 0.52f
        // Плата.
        fill.color = shade(IRON, 1.05f); fill.alpha = 255
        c.drawRoundRect(cx - pw, ly - ph * 0.55f, cx + pw, ly + ph * 0.55f,
            3f * d, 3f * d, fill)
        fill.color = shade(IRON, 1.45f)
        c.drawRoundRect(cx - pw, ly - ph * 0.55f, cx + pw * 0.25f, ly + ph * 0.10f,
            3f * d, 3f * d, fill)
        // Дужка: при открытии отлетает и падает.
        c.save()
        val swing = -70f * (open / 0.35f).coerceAtMost(1f)
        c.rotate(swing, cx - pw * 0.7f, ly - ph * 0.55f)
        line.color = shade(IRON, 1.3f); line.strokeWidth = 2.6f * d; line.alpha = 255
        c.drawArc(cx - pw * 0.75f, ly - ph * 1.15f, cx + pw * 0.75f, ly - ph * 0.15f,
            185f, 170f, false, line)
        c.restore()
        // Скважина: дышит светом, при натуге разгорается и дрожит.
        val kg = 0.45f + 0.55f * breath + strain * 0.6f
        fill.color = if (deny > 0f) 0xFFFF3B3B.toInt() else 0xFFFFC257.toInt()
        fill.alpha = ((90f + 150f * kg) * (1f - open * 3f).coerceIn(0f, 1f))
            .toInt().coerceIn(0, 255)
        val jitter = strain * 0.8f * d *
            sin((System.currentTimeMillis() * 0.02).toDouble()).toFloat()
        c.drawCircle(cx + jitter, ly - ph * 0.05f, pw * 0.34f, fill)
        itemPath.reset()
        itemPath.moveTo(cx - pw * 0.16f + jitter, ly - ph * 0.05f)
        itemPath.lineTo(cx + pw * 0.16f + jitter, ly - ph * 0.05f)
        itemPath.lineTo(cx + pw * 0.10f + jitter, ly + ph * 0.34f)
        itemPath.lineTo(cx - pw * 0.10f + jitter, ly + ph * 0.34f)
        itemPath.close()
        c.drawPath(itemPath, fill)
        fill.alpha = 255
    }

    /**
     * Вылетающее содержимое.
     *
     * Физика настоящая и у каждого предмета своя: угол и сила броска,
     * ускорение вниз, сопротивление воздуха по горизонтали, угловая
     * скорость. Поэтому один вылетает свечкой и падает обратно, другой
     * уходит вбок и кувыркается - как и бывает, когда из сундука
     * вырывается то, что в нём лежало.
     */
    private fun drawFlyingItems(c: Canvas, cx: Float, topY: Float, bw: Float, h: Float,
                                openRaw: Float) {
        var i = 0
        while (i < 9) {
            // Разлёт не одновременный: сначала верхние, потом со дна.
            val delay = i * 0.045f
            val tt = ((openRaw - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (tt <= 0f) { i++; continue }
            val tsec = tt * (OPEN_MS / 1000f) * 2.6f

            val ang = -1.9f + rnd(i) * 1.4f            // радианы, вверх
            val v0 = bw * (5.2f + 3.4f * rnd(i + 3))   // px/с
            val vx0 = cos(ang.toDouble()).toFloat() * v0
            val vy0 = sin(ang.toDouble()).toFloat() * v0
            // Сопротивление воздуха: горизонталь гаснет, вертикаль нет -
            // лист бумаги теряет ход вбок быстрее, чем падает.
            // Затухание скорости по горизонтали: путь = v0*(1-e^-kt)/k.
            // Всё считаем во Float: смешение Float и Double здесь дало бы
            // Double там, где холст ждёт Float.
            val drag = (1f - exp((-DRAG * tsec).toDouble()).toFloat()) / DRAG
            val x = cx + vx0 * drag
            val y = topY - bw * 0.15f + vy0 * tsec + 0.5f * GRAV * bw * tsec * tsec

            val fade = (1f - tt * tt).coerceIn(0f, 1f)
            if (fade <= 0.02f) { i++; continue }
            val spin = (rnd(i + 7) - 0.5f) * 900f * tsec
            val sz = bw * (0.20f + 0.10f * rnd(i + 5))

            c.save()
            c.translate(x, y)
            c.rotate(spin)
            when (i % 3) {
                0 -> drawScroll(c, sz, fade)
                1 -> drawLetter(c, sz, fade)
                else -> drawNote(c, sz, fade)
            }
            c.restore()
            i++
        }
        fill.alpha = 255
        line.alpha = 255
    }

    private fun drawScroll(c: Canvas, sz: Float, fade: Float) {
        val a = (245f * fade).toInt().coerceIn(0, 255)
        // Полотно с тенью снизу - у листа есть толщина.
        fill.color = 0xFF9A8A66.toInt(); fill.alpha = (a * 0.5f).toInt()
        c.drawRect(-sz * 0.70f, -sz * 0.26f + 1.2f * d, sz * 0.70f, sz * 0.30f + 1.2f * d, fill)
        fill.color = 0xFFEADFC2.toInt(); fill.alpha = a
        c.drawRect(-sz * 0.70f, -sz * 0.28f, sz * 0.70f, sz * 0.28f, fill)
        line.color = 0xFF8A7A56.toInt(); line.alpha = (a * 0.7f).toInt()
        line.strokeWidth = 1.1f * d
        c.drawLine(-sz * 0.5f, -sz * 0.10f, sz * 0.42f, -sz * 0.08f, line)
        c.drawLine(-sz * 0.5f, sz * 0.08f, sz * 0.28f, sz * 0.10f, line)
        // Валики с торцевыми кольцами.
        var k = 0
        while (k < 2) {
            val bx = if (k == 0) -sz * 0.82f else sz * 0.82f
            fill.color = 0xFF8A6636.toInt(); fill.alpha = a
            c.drawRoundRect(bx - sz * 0.11f, -sz * 0.38f, bx + sz * 0.11f, sz * 0.38f,
                sz * 0.11f, sz * 0.11f, fill)
            fill.color = 0xFFC79B5C.toInt()
            c.drawCircle(bx, -sz * 0.32f, sz * 0.09f, fill)
            c.drawCircle(bx, sz * 0.32f, sz * 0.09f, fill)
            k++
        }
    }

    private fun drawLetter(c: Canvas, sz: Float, fade: Float) {
        val a = (245f * fade).toInt().coerceIn(0, 255)
        fill.color = 0xFF9A8A66.toInt(); fill.alpha = (a * 0.5f).toInt()
        c.drawRect(-sz * 0.66f, -sz * 0.42f + 1.2f * d, sz * 0.66f, sz * 0.46f + 1.2f * d, fill)
        fill.color = 0xFFF4EEDE.toInt(); fill.alpha = a
        c.drawRect(-sz * 0.66f, -sz * 0.44f, sz * 0.66f, sz * 0.44f, fill)
        // Клапан конверта.
        itemPath.reset()
        itemPath.moveTo(-sz * 0.66f, -sz * 0.44f)
        itemPath.lineTo(0f, sz * 0.02f)
        itemPath.lineTo(sz * 0.66f, -sz * 0.44f)
        itemPath.close()
        fill.color = 0xFFE6DCC4.toInt(); fill.alpha = a
        c.drawPath(itemPath, fill)
        line.color = 0xFF9A8A66.toInt(); line.alpha = a; line.strokeWidth = 1.2f * d
        c.drawPath(itemPath, line)
        // Сургучная печать - красная точка, за которую цепляется глаз.
        fill.color = 0xFFB3242C.toInt(); fill.alpha = a
        c.drawCircle(0f, sz * 0.02f, sz * 0.13f, fill)
        fill.color = 0xFF7C161C.toInt(); fill.alpha = a
        c.drawCircle(sz * 0.03f, sz * 0.05f, sz * 0.07f, fill)
    }

    private fun drawNote(c: Canvas, sz: Float, fade: Float) {
        val a = (240f * fade).toInt().coerceIn(0, 255)
        itemPath.reset()
        itemPath.moveTo(-sz * 0.56f, -sz * 0.48f)
        itemPath.lineTo(sz * 0.58f, -sz * 0.40f)
        itemPath.lineTo(sz * 0.46f, sz * 0.50f)
        itemPath.lineTo(-sz * 0.62f, sz * 0.38f)
        itemPath.close()
        fill.color = 0xFF9A8A66.toInt(); fill.alpha = (a * 0.45f).toInt()
        c.save(); c.translate(1.2f * d, 1.2f * d); c.drawPath(itemPath, fill); c.restore()
        fill.color = 0xFFEAE4D4.toInt(); fill.alpha = a
        c.drawPath(itemPath, fill)
        // Рваный край: зубцы по левой стороне.
        fill.color = 0xFFDAD2BE.toInt(); fill.alpha = a
        var k = 0
        while (k < 4) {
            val yy = -sz * 0.40f + sz * 0.24f * k
            c.drawCircle(-sz * 0.58f, yy, sz * 0.055f, fill)
            k++
        }
        line.color = 0xFF8A8474.toInt(); line.alpha = (a * 0.8f).toInt()
        line.strokeWidth = 1.1f * d
        c.drawLine(-sz * 0.34f, -sz * 0.14f, sz * 0.30f, -sz * 0.08f, line)
        c.drawLine(-sz * 0.34f, sz * 0.06f, sz * 0.16f, sz * 0.12f, line)
        c.drawLine(-sz * 0.34f, sz * 0.24f, sz * 0.02f, sz * 0.28f, line)
    }

    private companion object {
        const val OPEN_MS = 1100f
        const val DENY_MS = 620f
        const val TOUCH_MS = 420f
        /** Ускорение свободного падения в долях ширины сундука за с². */
        const val GRAV = 11.5f
        /** Сопротивление воздуха по горизонтали, 1/с. */
        const val DRAG = 2.1f
        val WOOD = 0xFF6B4A28.toInt()
        val IRON = 0xFF7A6A55.toInt()
    }
}
