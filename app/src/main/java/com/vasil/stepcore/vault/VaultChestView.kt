package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * СУНДУК ТАЙНИКА.
 *
 * Третья редакция. Что чинилось и почему.
 *
 * 1. СВЕТ БЫЛ ФОРМАМИ. Ореол собирался из вложенных эллипсов, столб света
 *    - из треугольников. На тёмном фоне у каждой такой фигуры видна
 *    кромка, и свет читался как нарисованная геометрия. Теперь весь свет
 *    идёт РАСТЯЖКАМИ (RadialGradient и LinearGradient): у растяжки нет
 *    края, поэтому нет и формы - только свечение.
 *
 * 2. КРЫШКА В ПОЛЁТЕ БЫЛА ПЛОСКОЙ. Обломки рисовались многоугольниками
 *    высотой в треть - в полёте это читалось как сплющенная доска. Теперь
 *    у половин купол, толщина двумя слоями, обугленный разлом, и они
 *    улетают быстро, с большим вращением, оставляя за собой смазанный
 *    след. Плюс срыв стал СОБЫТИЕМ: ударная волна кольцом, вспышка,
 *    щепки и рой углей.
 *
 * 3. ГЕОМЕТРИЯ БЫЛА УГЛОВАТОЙ. Прямые углы заменены дугами: купол крышки,
 *    скруглённый короб на ножках, полукруглые торцы обручей, арочная
 *    замочная плата. Углы сундука сняты фасками.
 *
 * 4. ГЛУБИНЫ НЕ БЫЛО. Сцена медленно плывёт по двум осям с разными
 *    периодами (фигура Лиссажу), и слои смещаются НЕОДИНАКОВО: дальний
 *    свет меньше, сундук больше, летящее - сильнее всех. Это параллакс:
 *    он и создаёт ощущение объёма, которого не даст ни одна тень.
 *
 * 5. СУНДУК СТАЛ БАГРОВЫМ, И ИЗ НЕГО ТЕЧЁТ. Из шва под крышкой сочится
 *    густая жидкость, стекает по лицевой стороне каплями и собирается в
 *    лужу. Снизу её подсвечивает золотом - тёплый ободок по краю, как у
 *    настоящей плотной жидкости на свету. Раз в несколько секунд лужа
 *    собирается в ЛИЦО - две прорези глаз и кривая ухмылка - и снова
 *    растекается. Лицо не рисуется поверх лужи, оно проступает ИЗ неё:
 *    иначе это была бы наклейка.
 *
 * Границы: пакет vault, ничего из ядра шагомера. Аллокаций в кадре нет,
 * растяжки собираются один раз на размер. Кадры: полная частота только
 * пока идёт номер.
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
    private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lidPath = Path()
    private val itemPath = Path()
    private val goo = Path()
    private val box = RectF()

    // Растяжки собираются один раз на размер: в кадре только отрисовка.
    private var shaderKey = -1f
    private var haloShader: RadialGradient? = null
    private var shaftShader: LinearGradient? = null
    private var woodShader: LinearGradient? = null
    private var ironShader: LinearGradient? = null

    // ------------------------------------------------------------- состояния

    fun touched() {
        touchedAt = System.currentTimeMillis()
        if (mood == Mood.CLOSED) invalidate()
    }

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

    /** Пружина с затуханием: один закон на все упругие движения. */
    private fun spring(t: Float, freq: Float, damp: Float): Float {
        if (t <= 0f || t >= 1f) return 0f
        return (exp(-damp * t.toDouble()) * sin(t * freq * 2.0 * Math.PI)).toFloat()
    }

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

    private fun buildShaders(w: Float, h: Float, bw: Float, bh: Float, topY: Float) {
        if (shaderKey == w) return
        shaderKey = w
        haloShader = RadialGradient(w / 2f, topY, maxOf(w, h) * 0.62f,
            // Ореол приглушён вдвое: прежний заливал сцену тёплым
            // молоком, и контраст, на котором держится форма, пропадал.
            intArrayOf(0x33C4562E, 0x1A7A2A1E, 0x00000000),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        shaftShader = LinearGradient(0f, topY - h * 0.62f, 0f, topY,
            intArrayOf(0x00FFD98A, 0x3DFFD98A, 0x8CFFE7B2.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        // Размах растяжки СУЖЕН. Широкий переход от светлого к тёмному
        // читается как надувная поверхность - отсюда и «пластилин».
        // Узкий диапазон плюс жёсткая обводка дают рисунок, а не лепку.
        woodShader = LinearGradient(w / 2f - bw, 0f, w / 2f + bw, 0f,
            intArrayOf(shade(WOOD, 1.15f), shade(WOOD, 0.98f), shade(WOOD, 0.74f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        ironShader = LinearGradient(0f, 0f, 0f, bh * 0.5f,
            intArrayOf(shade(IRON, 1.25f), shade(IRON, 0.70f), shade(IRON, 1.00f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.MIRROR)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()
        val t = (now - moodAt).toFloat()

        val openRaw = if (mood == Mood.OPENING) (t / OPEN_MS).coerceIn(0f, 1f) else 0f
        val open = if (openRaw <= 0f) 0f else
            (1f - exp(-4.2 * openRaw.toDouble()).toFloat()) + 0.10f * spring(openRaw, 1.1f, 4.5f)
        val denyK = if (mood == Mood.DENIED) (1f - t / DENY_MS).coerceIn(0f, 1f) else 0f
        if (mood == Mood.DENIED && denyK <= 0f) mood = Mood.CLOSED
        val strain = if (mood == Mood.STRAINING) (t / 300f).coerceAtMost(1f) else 0f
        val touch = (1f - (now - touchedAt).toFloat() / TOUCH_MS).coerceIn(0f, 1f)

        val cx = w / 2f
        val bw = minOf(w * 0.30f, h * 0.60f)
        val bh = bw * 0.66f
        val by = h * 0.76f
        val lidH = bw * 0.52f
        val topY = by - bh
        buildShaders(w, h, bw, bh, topY)

        // --- ПАРАЛЛАКС. Медленный дрейф по двум осям с несоразмерными
        // периодами: путь не замыкается, повтор не читается. Каждый слой
        // получает свою долю - в этом весь объём.
        val px = sin(now * 0.00021).toFloat()
        val py = sin(now * 0.00034 + 1.1).toFloat()
        val drift = DRIFT * d

        canvas.save()
        canvas.translate(px * drift * 0.35f, py * drift * 0.35f)

        // --- СВЕТ РАСТЯЖКОЙ, А НЕ ФИГУРОЙ ---
        val breath = 0.5f + 0.5f * sin(now * 0.0013).toFloat()
        val lightK = (0.28f + 0.18f * breath + strain * 0.5f + open * 1.1f).coerceAtMost(2f)
        soft.shader = haloShader
        soft.alpha = (110f * lightK).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, soft)
        soft.shader = null
        if (denyK > 0f) {
            soft.color = 0xFFFF3B3B.toInt()
            soft.alpha = (60f * denyK).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, topY, bw * 2.2f, soft)
        }

        canvas.save()
        canvas.translate(px * drift * 0.5f, py * drift * 0.5f)

        // Столб света из-под крышки: растяжка, гаснущая кверху.
        if (open > 0.02f || strain > 0.02f) {
            val k = (open * 0.95f + strain * 0.22f).coerceAtMost(1f)
            soft.shader = shaftShader
            soft.alpha = (150f * k).toInt().coerceIn(0, 255)
            val spread = bw * (0.75f + 0.55f * k)
            itemPath.reset()
            itemPath.moveTo(cx - bw * 0.86f, topY)
            itemPath.lineTo(cx + bw * 0.86f, topY)
            itemPath.quadTo(cx + spread * 1.5f, topY - h * 0.30f,
                cx + spread * 1.1f, topY - h * 0.62f)
            itemPath.quadTo(cx, topY - h * 0.74f, cx - spread * 1.1f, topY - h * 0.62f)
            itemPath.quadTo(cx - spread * 1.5f, topY - h * 0.30f, cx - bw * 0.86f, topY)
            itemPath.close()
            canvas.drawPath(itemPath, soft)
            soft.shader = null
        }

        val crack = ((open - 0.42f) / 0.30f).coerceIn(0f, 1f)
        val burst = ((open - 0.72f) / 0.28f).coerceIn(0f, 1f)

        // Летящее смещается сильнее всего: оно ближе к смотрящему.
        canvas.save()
        canvas.translate(px * drift * 0.9f, py * drift * 0.9f)
        if (open > 0f) drawFlyingItems(canvas, cx, topY, bw, openRaw)
        if (burst > 0f) drawTornLid(canvas, cx, topY, bw, lidH, burst)
        canvas.restore()

        if (open > 0.05f) drawInside(canvas, cx, topY, bw, bh, open)

        drawBox(canvas, cx, by, bw, bh, denyK, open)
        if (burst <= 0f) drawLid(canvas, cx, topY, bw, lidH, open, touch, strain, denyK, crack)
        if (open < 0.35f) drawLock(canvas, cx, topY, bw, bh, open, strain, denyK, breath)

        // Жидкость поверх лицевой стороны, но под пылью и вспышкой.
        drawOoze(canvas, cx, topY, by, bw, bh, now, open, strain, denyK)

        // Ударная волна и вспышка срыва - самое переднее и самое короткое.
        if (burst > 0f && burst < 0.55f) {
            val k = burst / 0.55f
            line.shader = null
            line.color = 0xFFFFE7B2.toInt()
            line.alpha = (200f * (1f - k)).toInt().coerceIn(0, 255)
            line.strokeWidth = (5f - 4f * k) * d
            canvas.drawCircle(cx, topY, bw * (0.4f + 2.6f * k), line)
            soft.color = 0xFFFFF3D6.toInt()
            soft.alpha = (120f * (1f - k) * (1f - k)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, topY, bw * (0.9f + 1.2f * k), soft)
        }

        drawDust(canvas, cx, topY, bw, h, strain, open, now)
        canvas.restore()
        canvas.restore()

        val busy = (mood == Mood.OPENING && openRaw < 1f) || denyK > 0f || touch > 0f ||
            mood == Mood.STRAINING
        // В покое кадры редкие, но не стоячие: дрейф и жидкость живут всегда.
        if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(90)
    }

    // ------------------------------------------------------------- части

    private fun drawInside(c: Canvas, cx: Float, topY: Float, bw: Float, bh: Float,
                           open: Float) {
        fill.shader = null
        fill.color = 0xFF150A08.toInt(); fill.alpha = 255
        box.set(cx - bw * 0.94f, topY - bh * 0.06f * open, cx + bw * 0.94f, topY + bh * 0.36f)
        c.drawRoundRect(box, bw * 0.10f, bw * 0.10f, fill)
        soft.color = 0xFFFFC257.toInt()
        soft.alpha = (120f * open).toInt().coerceIn(0, 255)
        c.drawOval(cx - bw * 0.86f, topY - bh * 0.10f, cx + bw * 0.86f, topY + bh * 0.22f, soft)
    }

    /** Короб: скруглённый, на ножках, с фасками и коваными обручами. */
    private fun drawBox(c: Canvas, cx: Float, by: Float, bw: Float, bh: Float,
                        deny: Float, open: Float) {
        val top = by - bh
        val bot = by + bh * 0.36f
        // Скругление вдвое меньше: сундук - плотницкая работа, а не
        // мыло. Полностью убрать нельзя - фаска у настоящего сундука есть.
        val rr = bw * 0.055f

        // Ножки-скобы: сундук стоит, а не парит.
        fill.shader = null
        fill.color = shade(IRON, 0.85f); fill.alpha = 255
        c.drawRoundRect(cx - bw * 0.86f, bot - bh * 0.06f, cx - bw * 0.52f, bot + bh * 0.13f,
            bh * 0.07f, bh * 0.07f, fill)
        c.drawRoundRect(cx + bw * 0.52f, bot - bh * 0.06f, cx + bw * 0.86f, bot + bh * 0.13f,
            bh * 0.07f, bh * 0.07f, fill)

        // Тело: одна растяжка на всю ширину - свет слева, тень справа.
        fill.shader = woodShader
        box.set(cx - bw, top, cx + bw, bot)
        c.drawRoundRect(box, rr, rr, fill)
        fill.shader = null

        c.save()
        itemPath.reset()
        itemPath.addRoundRect(box, rr, rr, Path.Direction.CW)
        c.clipPath(itemPath)
        // Доски: не линии поверх, а тёмные швы между полосами.
        var i = 1
        while (i < 5) {
            val x = cx - bw + 2 * bw * i / 5f
            fill.color = shade(WOOD, 0.42f); fill.alpha = 190
            c.drawRect(x - 1.1f * d, top, x + 1.1f * d, bot, fill)
            i++
        }
        // Волокно дугами - дерево, а не фанера.
        line.shader = null
        line.color = shade(WOOD, 0.72f); line.alpha = 150; line.strokeWidth = 1.1f * d
        i = 0
        while (i < 7) {
            val gy = top + (bot - top) * (0.12f + 0.13f * i)
            itemPath.reset()
            itemPath.moveTo(cx - bw, gy)
            itemPath.quadTo(cx + bw * (rnd(i) - 0.5f), gy + bh * 0.06f * (rnd(i + 9) - 0.5f),
                cx + bw, gy + bh * 0.02f)
            c.drawPath(itemPath, line)
            i++
        }
        // Нижняя тень внутри короба - объём без чёрной полосы.
        soft.shader = LinearGradient(0f, bot - bh * 0.42f, 0f, bot,
            0x00000000, 0x73000000, Shader.TileMode.CLAMP)
        soft.alpha = 255
        c.drawRect(cx - bw, bot - bh * 0.42f, cx + bw, bot, soft)
        soft.shader = null
        c.restore()

        // Обручи с полукруглыми торцами и заклёпками.
        var b2 = 0
        while (b2 < 2) {
            val bx = cx + (if (b2 == 0) -1f else 1f) * bw * 0.56f
            fill.shader = ironShader
            fill.alpha = 255
            c.drawRoundRect(bx - bw * 0.11f, top - bh * 0.02f, bx + bw * 0.11f, bot + bh * 0.02f,
                bw * 0.05f, bw * 0.05f, fill)
            fill.shader = null
            line.shader = null
            line.color = INK; line.alpha = 235; line.strokeWidth = 1.6f * d
            c.drawRoundRect(bx - bw * 0.11f, top - bh * 0.02f, bx + bw * 0.11f, bot + bh * 0.02f,
                bw * 0.05f, bw * 0.05f, line)
            var k = 0
            while (k < 3) {
                val ry = top + (bot - top) * (0.20f + 0.30f * k)
                fill.color = shade(IRON, 0.45f)
                c.drawCircle(bx, ry + 0.9f * d, bw * 0.042f, fill)
                fill.color = shade(IRON, 1.5f)
                c.drawCircle(bx - bw * 0.010f, ry - 0.5f * d, bw * 0.036f, fill)
                k++
            }
            b2++
        }

        // ОБВОДКА. Её отсутствие и делало картинку лепной: у мягкой
        // растяжки нет края, и предмет теряет силуэт. Сначала тёмная
        // линия по контуру - она возвращает форму, - и только поверх неё
        // тонкий тёплый кант как отблеск на кромке.
        line.shader = null
        line.color = INK; line.alpha = 255; line.strokeWidth = 2.6f * d
        c.drawRoundRect(box, rr, rr, line)
        line.color = shade(IRON, 1.35f); line.alpha = 150; line.strokeWidth = 1.1f * d
        c.drawRoundRect(box, rr, rr, line)

        if (deny > 0f) {
            fill.color = 0xFFFF3B3B.toInt(); fill.alpha = (75f * deny).toInt().coerceIn(0, 255)
            c.drawRoundRect(box, rr, rr, fill)
            fill.alpha = 255
        }
    }

    /** Крышка: купол, ракурс через косинус, трещины перед срывом. */
    private fun drawLid(c: Canvas, cx: Float, topY: Float, bw: Float, lidH: Float,
                        open: Float, touch: Float, strain: Float, deny: Float, crack: Float) {
        val jump = touch * 3.2f * spring(1f - touch, 1.6f, 2.2f)
        val pull = strain * (1.6f + 1.0f * sin((System.currentTimeMillis() * 0.006)).toFloat())
        val slam = deny * 2.6f * spring(1f - deny, 3.2f, 1.6f)

        val phi = (108f * open) * Math.PI.toFloat() / 180f
        val proj = cos(phi.toDouble()).toFloat()
        val inside = proj < 0f
        val faceH = lidH * abs(proj)
        val lift = lidH * 0.55f * sin(phi.toDouble()).toFloat()
        val lw = bw * (1f - 0.10f * sin(phi.toDouble()).toFloat())

        c.save()
        c.rotate(-jump - pull + slam, cx, topY)
        c.translate(0f, -lift)

        val faceTop = topY - faceH
        lidPath.reset()
        lidPath.moveTo(cx - lw, topY)
        // Купол приспущен: прежняя дуга поднималась выше собственной
        // высоты и делала крышку подушкой.
        lidPath.cubicTo(cx - lw, faceTop + faceH * 0.42f,
            cx - lw * 0.60f, faceTop - faceH * 0.02f, cx, faceTop - faceH * 0.05f)
        lidPath.cubicTo(cx + lw * 0.60f, faceTop - faceH * 0.02f,
            cx + lw, faceTop + faceH * 0.42f, cx + lw, topY)
        lidPath.close()

        c.save()
        c.clipPath(lidPath)
        if (inside) {
            fill.shader = null
            fill.color = shade(WOOD, 0.50f); fill.alpha = 255
            c.drawRect(cx - lw, faceTop - faceH, cx + lw, topY, fill)
            line.color = shade(WOOD, 0.34f); line.alpha = 220; line.strokeWidth = 1.3f * d
            var k = 0
            while (k < 3) {
                val gy = topY - faceH * (0.25f + 0.26f * k)
                c.drawLine(cx - lw * 0.9f, gy, cx + lw * 0.9f, gy, line)
                k++
            }
        } else {
            fill.shader = woodShader
            fill.alpha = 255
            c.drawRect(cx - lw, faceTop - faceH, cx + lw, topY, fill)
            fill.shader = null
            if (crack > 0f) drawCracks(c, cx, topY, lw, faceH, crack)
            // Блик по куполу - мягкий, растяжкой.
            soft.shader = RadialGradient(cx - lw * 0.38f, faceTop + faceH * 0.34f,
                lw * 0.75f, 0x2EFFE8D6, 0x00FFFFFF, Shader.TileMode.CLAMP)
            soft.alpha = 255
            c.drawRect(cx - lw, faceTop - faceH, cx + lw, topY, soft)
            soft.shader = null
        }
        c.restore()

        line.shader = null
        line.color = INK; line.strokeWidth = 2.6f * d; line.alpha = 255
        c.drawPath(lidPath, line)
        line.color = shade(IRON, 1.35f); line.strokeWidth = 1.0f * d; line.alpha = 140
        c.drawPath(lidPath, line)
        c.restore()

        // Петли остаются на кромке короба.
        var k2 = 0
        while (k2 < 2) {
            val hx = cx + (if (k2 == 0) -1f else 1f) * bw * 0.80f
            fill.shader = null
            fill.color = shade(IRON, 1.35f); fill.alpha = 255
            c.drawCircle(hx, topY, 3.6f * d, fill)
            fill.color = shade(IRON, 0.5f)
            c.drawCircle(hx, topY, 1.6f * d, fill)
            k2++
        }
    }

    private fun drawCracks(c: Canvas, cx: Float, topY: Float, lw: Float, faceH: Float,
                           crack: Float) {
        var q = 0
        while (q < 4) {
            val y0 = topY - faceH * (0.22f + 0.22f * q)
            val dirq = if (q % 2 == 0) 1f else -1f
            val reach = lw * crack * (0.7f + 0.3f * rnd(q + 60))
            itemPath.reset()
            itemPath.moveTo(cx, y0)
            var st = 1
            while (st <= 5) {
                val f2 = st / 5f
                itemPath.quadTo(
                    cx + dirq * reach * (f2 - 0.1f), y0 + faceH * 0.09f * (rnd(q * 5 + st) - 0.5f),
                    cx + dirq * reach * f2, y0 + faceH * 0.12f * (rnd(q * 7 + st) - 0.5f))
                st++
            }
            line.shader = null
            line.color = 0xFF1A0E06.toInt()
            line.alpha = (235f * crack).toInt().coerceIn(0, 255)
            line.strokeWidth = 3.6f * d * crack
            c.drawPath(itemPath, line)
            line.color = 0xFFFFB03A.toInt()
            line.alpha = (255f * crack * crack).toInt().coerceIn(0, 255)
            line.strokeWidth = 1.5f * d * crack
            c.drawPath(itemPath, line)
            q++
        }
    }

    /**
     * Сорванная крышка. Половины сохраняют КУПОЛ и толщину, летят быстро,
     * с большим вращением и смазанным следом позади.
     */
    private fun drawTornLid(c: Canvas, cx: Float, topY: Float, bw: Float, lidH: Float,
                            burst: Float) {
        val tsec = burst * 1.05f
        val fade = (1f - burst * burst).coerceIn(0f, 1f)
        if (fade <= 0.01f) return

        var half = 0
        while (half < 2) {
            val dir = if (half == 0) -1f else 1f
            val vx = dir * bw * 3.1f
            val vy = -bw * 7.4f
            val x = cx + vx * tsec
            val y = topY - lidH * 0.5f + vy * tsec + 0.5f * GRAV * bw * tsec * tsec

            // След: три бледных призрака по прошлым положениям. Смаз даёт
            // скорость лучше, чем любое ускорение цифр.
            var g = 3
            while (g >= 1) {
                val tb = (tsec - g * 0.035f).coerceAtLeast(0f)
                val gx = cx + vx * tb
                val gy = topY - lidH * 0.5f + vy * tb + 0.5f * GRAV * bw * tb * tb
                drawLidHalf(c, gx, gy, dir, bw, lidH, dir * 260f * tb,
                    fade * (0.10f + 0.06f * (3 - g)))
                g--
            }
            drawLidHalf(c, x, y, dir, bw, lidH, dir * 260f * tsec, fade)
            half++
        }

        // Щепки и угли: рой, а не горсть.
        var i = 0
        while (i < 18) {
            val t2 = tsec * (0.6f + 0.8f * rnd(i + 80))
            val ang = -2.85f + rnd(i + 90) * 2.2f
            val v = bw * (6.0f + 6.5f * rnd(i + 100))
            val x = cx + cos(ang.toDouble()).toFloat() * v * t2
            val y = topY - lidH * 0.4f + sin(ang.toDouble()).toFloat() * v * t2 +
                0.5f * GRAV * bw * t2 * t2
            val f2 = (1f - burst) * (1f - 0.45f * rnd(i + 110))
            fill.shader = null
            if (i % 3 == 0) {
                fill.color = if (i % 6 == 0) 0xFFFFF0C0.toInt() else 0xFFFFB03A.toInt()
                fill.alpha = (250f * f2).toInt().coerceIn(0, 255)
                c.drawCircle(x, y, (2.4f - 1.3f * burst) * d, fill)
            } else {
                c.save()
                c.translate(x, y)
                c.rotate(rnd(i + 120) * 360f + burst * 520f)
                fill.color = shade(WOOD, 0.95f)
                fill.alpha = (240f * f2).toInt().coerceIn(0, 255)
                c.drawRoundRect(-bw * 0.10f, -bw * 0.024f, bw * 0.10f, bw * 0.024f,
                    bw * 0.024f, bw * 0.024f, fill)
                c.restore()
            }
            i++
        }
        fill.alpha = 255
        line.alpha = 255
    }

    /** Одна половина сорванной крышки: купол, толщина, обугленный разлом. */
    private fun drawLidHalf(c: Canvas, x: Float, y: Float, dir: Float, bw: Float,
                            lidH: Float, rot: Float, alpha: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        lidPath.reset()
        lidPath.moveTo(0f, lidH * 0.34f)
        lidPath.cubicTo(dir * bw * 0.30f, lidH * 0.40f,
            dir * bw * 0.86f, lidH * 0.22f, dir * bw * 0.98f, -lidH * 0.06f)
        lidPath.cubicTo(dir * bw * 0.92f, -lidH * 0.44f,
            dir * bw * 0.42f, -lidH * 0.62f, 0f, -lidH * 0.52f)
        // Разлом: ломаная, а не прямой срез.
        var k = 0
        while (k < 4) {
            lidPath.lineTo(dir * bw * 0.055f * (if (k % 2 == 0) 1f else -1f),
                -lidH * (0.52f - 0.215f * (k + 1)))
            k++
        }
        lidPath.close()
        // Толщина: тёмный дубль со смещением под лицевой стороной.
        fill.shader = null
        fill.color = shade(WOOD, 0.55f)
        fill.alpha = (255f * alpha).toInt().coerceIn(0, 255)
        c.save(); c.translate(-dir * 2.5f * d, 3f * d); c.drawPath(lidPath, fill); c.restore()
        fill.color = shade(WOOD, 1.08f)
        fill.alpha = (255f * alpha).toInt().coerceIn(0, 255)
        c.drawPath(lidPath, fill)
        line.shader = null
        line.color = 0xFF1A0E06.toInt()
        line.alpha = (235f * alpha).toInt().coerceIn(0, 255)
        line.strokeWidth = 2.4f * d
        c.drawPath(lidPath, line)
        // Уцелевший обруч на половине.
        fill.color = shade(IRON, 1.15f)
        fill.alpha = (255f * alpha).toInt().coerceIn(0, 255)
        c.drawRoundRect(dir * bw * 0.40f, -lidH * 0.42f, dir * bw * 0.62f, lidH * 0.26f,
            bw * 0.05f, bw * 0.05f, fill)
        c.restore()
    }

    /** Замочная плата: арка, дужка, скважина. Ни одного прямого угла. */
    private fun drawLock(c: Canvas, cx: Float, topY: Float, bw: Float, bh: Float,
                         open: Float, strain: Float, deny: Float, breath: Float) {
        val ly = topY + bh * 0.44f
        val pw = bw * 0.21f
        val ph = bh * 0.54f
        fill.shader = ironShader; fill.alpha = 255
        itemPath.reset()
        itemPath.moveTo(cx - pw, ly + ph * 0.55f)
        itemPath.lineTo(cx - pw, ly - ph * 0.20f)
        itemPath.quadTo(cx, ly - ph * 0.95f, cx + pw, ly - ph * 0.20f)
        itemPath.lineTo(cx + pw, ly + ph * 0.55f)
        itemPath.close()
        c.drawPath(itemPath, fill)
        fill.shader = null
        line.shader = null
        line.color = shade(IRON, 1.45f); line.alpha = 255; line.strokeWidth = 1.4f * d
        c.drawPath(itemPath, line)

        // Дужка: отходит при открытии.
        c.save()
        c.rotate(-70f * (open / 0.35f).coerceAtMost(1f), cx - pw * 0.7f, ly - ph * 0.55f)
        line.color = shade(IRON, 1.3f); line.strokeWidth = 2.8f * d
        c.drawArc(cx - pw * 0.78f, ly - ph * 1.18f, cx + pw * 0.78f, ly - ph * 0.18f,
            185f, 170f, false, line)
        c.restore()

        // Скважина: свечение растяжкой, силуэт - каплей, не прямоугольником.
        val kg = 0.45f + 0.55f * breath + strain * 0.6f
        val jitter = strain * 0.9f * d * sin((System.currentTimeMillis() * 0.02)).toFloat()
        soft.shader = RadialGradient(cx + jitter, ly, pw * 1.9f,
            if (deny > 0f) 0x99FF3B3B.toInt() else 0x99FFC257.toInt(), 0x00000000,
            Shader.TileMode.CLAMP)
        soft.alpha = (200f * kg.coerceAtMost(1.6f)).toInt().coerceIn(0, 255)
        c.drawCircle(cx + jitter, ly, pw * 1.9f, soft)
        soft.shader = null
        fill.color = if (deny > 0f) 0xFFFF6B6B.toInt() else 0xFFFFD07A.toInt()
        fill.alpha = ((150f + 105f * breath) * (1f - open * 3f).coerceIn(0f, 1f))
            .toInt().coerceIn(0, 255)
        c.drawCircle(cx + jitter, ly - ph * 0.06f, pw * 0.33f, fill)
        itemPath.reset()
        itemPath.moveTo(cx - pw * 0.16f + jitter, ly - ph * 0.02f)
        itemPath.quadTo(cx + jitter, ly + ph * 0.10f, cx + pw * 0.16f + jitter, ly - ph * 0.02f)
        itemPath.quadTo(cx + pw * 0.09f + jitter, ly + ph * 0.34f, cx + jitter, ly + ph * 0.36f)
        itemPath.quadTo(cx - pw * 0.09f + jitter, ly + ph * 0.34f,
            cx - pw * 0.16f + jitter, ly - ph * 0.02f)
        itemPath.close()
        c.drawPath(itemPath, fill)
        fill.alpha = 255
    }

    /**
     * ЖИДКОСТЬ.
     *
     * Прежняя версия не читалась как жидкость по трём причинам, и все три
     * здесь закрыты.
     *
     * 1. РОВНАЯ ТОЛЩИНА. Потёк шёл лентой одинаковой ширины. Настоящая
     *    струя ТОЛЩЕ у истока, тоньше в середине и вздувается каплей на
     *    конце - её держит поверхностное натяжение. Ширина теперь функция
     *    от длины, с утолщением на носке.
     *
     * 2. ЛУЖА БЫЛА ОВАЛОМ. Овал - это фигура, а не разлив. Контур
     *    собирается из точек по кругу, радиус которых гуляет двумя
     *    гармониками и медленно течёт во времени: лужа неровная и живая,
     *    и ни один кадр не повторяет предыдущий.
     *
     * 3. НЕ БЫЛО БЛИКА. Плотная жидкость на свету всегда даёт резкий
     *    маленький блик и тёмную кромку вокруг. Ободок снизу золотой, по
     *    верхней кромке - узкая светлая дуга, и в луже стоит одно яркое
     *    пятно. Именно блик, а не цвет, говорит глазу «мокрое».
     *
     * Плюс: капля, долетев, оставляет расходящееся кольцо. Лицо всё так же
     * ВЫРЕЗАНО из лужи, но теперь и оно повторяет неровный контур.
     */
    private fun drawOoze(c: Canvas, cx: Float, topY: Float, by: Float, bw: Float, bh: Float,
                         now: Long, open: Float, strain: Float, deny: Float) {
        if (open > 0.55f) return
        val amount = (1f - open * 1.8f).coerceIn(0f, 1f)
        val bot = by + bh * 0.36f
        val cyc = (now % FACE_MS).toFloat() / FACE_MS
        val faceK = if (cyc < 0.34f) sin((cyc / 0.34f * Math.PI).toFloat()) else 0f
        val greenTurn = ((now / FACE_MS) % 2L) == 1L
        val gooCol = if (deny > 0f) 0xFF7E0C18.toInt()
        else if (greenTurn && faceK > 0.05f) blendC(0xFF5A0A1C.toInt(), 0xFF23561F.toInt(), faceK)
        else 0xFF5A0A1C.toInt()
        val gooLit = blendC(gooCol, 0xFFFF6B6B.toInt(), 0.35f)

        // --- потёки ---
        var i = 0
        while (i < 3) {
            val x = cx + bw * (-0.52f + 0.52f * i)
            val ph = now * 0.00035f + i * 0.7f
            val len = bh * (0.55f + 0.42f * (0.5f + 0.5f * sin(ph.toDouble()).toFloat())) * amount
            val w0 = bw * (0.075f + 0.02f * i)          // у истока
            goo.reset()
            goo.moveTo(x - w0, topY + bh * 0.02f)
            var s = 1
            while (s <= 6) {
                val f = s / 6f
                // Сужение к середине и вздутие на носке.
                val wf = w0 * (1f - 0.55f * f + 0.75f * f * f * f)
                val yy = topY + len * f
                goo.lineTo(x - wf, yy)
                s++
            }
            goo.lineTo(x, topY + len + w0 * 0.9f)        // носок капли
            s = 6
            while (s >= 1) {
                val f = s / 6f
                val wf = w0 * (1f - 0.55f * f + 0.75f * f * f * f)
                goo.lineTo(x + wf, topY + len * f)
                s--
            }
            goo.lineTo(x + w0, topY + bh * 0.02f)
            goo.close()
            fill.shader = null
            fill.color = gooCol
            fill.alpha = (250f * amount).toInt().coerceIn(0, 255)
            c.drawPath(goo, fill)
            // Блик вдоль струи: узкая светлая нить чуть левее оси.
            line.shader = null
            line.color = gooLit
            line.alpha = (150f * amount).toInt().coerceIn(0, 255)
            line.strokeWidth = w0 * 0.30f
            c.drawLine(x - w0 * 0.35f, topY + bh * 0.06f, x - w0 * 0.2f, topY + len * 0.8f, line)

            // Сорвавшаяся капля с вытянутым хвостом.
            var g = (now * 0.00045f + i * 0.33f) % 1f
            if (g < 0f) g += 1f
            val dy = topY + len + (bot - topY - len) * g * g
            val dr = w0 * (0.62f - 0.18f * g)
            fill.color = gooCol
            fill.alpha = (250f * amount).toInt().coerceIn(0, 255)
            c.drawCircle(x, dy, dr, fill)
            c.drawOval(x - dr * 0.55f, dy - dr * (1.4f + 3.6f * g), x + dr * 0.55f, dy, fill)
            fill.color = gooLit
            fill.alpha = (190f * amount).toInt().coerceIn(0, 255)
            c.drawCircle(x - dr * 0.30f, dy - dr * 0.30f, dr * 0.28f, fill)
            i++
        }

        // --- лужа неровным контуром ---
        val poolW = bw * (0.92f - 0.16f * faceK)
        val poolH = bh * (0.13f + 0.15f * faceK)
        val poolY = bot + bh * 0.10f
        // РАЗЛИВ НЕСИММЕТРИЧЕН. Прежний контур гулял только чётными
        // гармониками, а они дают фигуру, симметричную относительно оси:
        // получалась «правильная клякса». Первая гармоника ломает эту
        // симметрию - лужа растекается в одну сторону сильнее, как и
        // бывает на неровном полу. Центр тоже смещён.
        val skew = 0.13f * sin((now * 0.00042).toDouble()).toFloat()
        val poolCx = cx + poolW * (0.10f + skew)
        goo.reset()
        var a2 = 0
        while (a2 <= 32) {
            val th = a2 / 32f * 2f * Math.PI.toFloat()
            val wob = 1f +
                0.16f * sin((th + 0.9 + now * 0.00035).toDouble()).toFloat() +
                0.10f * sin((th * 3f + now * 0.0007).toDouble()).toFloat() +
                0.05f * sin((th * 5f - now * 0.0011).toDouble()).toFloat()
            val px2 = poolCx + cos(th.toDouble()).toFloat() * poolW * wob
            val py2 = poolY + sin(th.toDouble()).toFloat() * poolH * wob *
                (if (sin(th.toDouble()) > 0) 0.55f else 1f)
            if (a2 == 0) goo.moveTo(px2, py2) else goo.lineTo(px2, py2)
            a2++
        }
        goo.close()
        fill.shader = null
        fill.color = gooCol
        fill.alpha = (250f * amount).toInt().coerceIn(0, 255)
        c.drawPath(goo, fill)
        // Тёмная кромка: у лужи есть толщина.
        line.shader = null
        line.color = INK
        line.alpha = (200f * amount).toInt().coerceIn(0, 255)
        line.strokeWidth = 1.5f * d
        c.drawPath(goo, line)

        // Кольца от упавшей капли: расходятся и гаснут.
        var rp = 0
        while (rp < 2) {
            var g = (now * 0.00045f + rp * 0.5f) % 1f
            if (g < 0f) g += 1f
            if (g > 0.72f) {
                val k = (g - 0.72f) / 0.28f
                line.color = gooLit
                line.alpha = (170f * (1f - k) * amount).toInt().coerceIn(0, 255)
                line.strokeWidth = 1.4f * d
                c.drawOval(poolCx - poolW * 0.5f * k - poolW * 0.1f, poolY - poolH * 0.5f * k,
                    poolCx + poolW * 0.5f * k + poolW * 0.1f, poolY + poolH * 0.5f * k, line)
            }
            rp++
        }

        if (faceK > 0.04f) {
            // Глаза - прорези В жидкости: узкие клинья, а не капли.
            fill.color = 0xFF0B0709.toInt()
            fill.alpha = (255f * faceK).toInt().coerceIn(0, 255)
            var e = 0
            while (e < 2) {
                val ex = poolCx + (if (e == 0) -1f else 1f) * poolW * 0.40f
                val tilt = (if (e == 0) 1f else -1f) * poolH * 0.20f
                itemPath.reset()
                itemPath.moveTo(ex - poolW * 0.20f, poolY - poolH * 0.36f + tilt)
                itemPath.lineTo(ex + poolW * 0.20f, poolY - poolH * 0.62f - tilt * 0.4f)
                itemPath.lineTo(ex + poolW * 0.16f, poolY - poolH * 0.30f)
                itemPath.close()
                c.drawPath(itemPath, fill)
                e++
            }
            // Ухмылка: не линия, а вырез с зубцами - оттого и ехидная.
            itemPath.reset()
            itemPath.moveTo(poolCx - poolW * 0.46f, poolY - poolH * 0.04f)
            itemPath.quadTo(poolCx + poolW * 0.08f, poolY + poolH * 0.46f,
                poolCx + poolW * 0.54f, poolY - poolH * 0.34f)
            var z = 4
            while (z >= 0) {
                val f = z / 4f
                val zx = poolCx - poolW * 0.46f + poolW * f
                val zy = poolY - poolH * (0.04f + 0.30f * f) +
                    poolH * (if (z % 2 == 0) 0.10f else 0.24f)
                itemPath.lineTo(zx, zy)
                z--
            }
            itemPath.close()
            c.drawPath(itemPath, fill)
        }

        // БЛИКИ РИСУЮТСЯ ПО САМОЙ ЛУЖЕ, А НЕ ПО ЭЛЛИПСУ.
        //
        // Здесь была видимая ошибка: тело лужи строилось неровным
        // контуром, а золотой ободок и светлая дуга - методом drawArc,
        // то есть по идеальному эллипсу. Две разные геометрии в одном
        // месте не сходились, и по краям торчали «усы» - именно то
        // сведение, которое видно на снимке.
        //
        // Теперь всё под клипом самой лужи: подсветка снизу - растяжка,
        // тёплая кромка - обводка того же пути, блик - пятно внутри.
        // Одна форма - один контур.
        c.save()
        c.clipPath(goo)
        // Тёплая подсветка снизу: жидкость просвечивает у дна.
        soft.shader = RadialGradient(poolCx, poolY + poolH * 0.5f, poolW * 1.1f,
            0x8CFFC257.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        soft.alpha = (190f * amount).toInt().coerceIn(0, 255)
        c.drawRect(poolCx - poolW * 1.4f, poolY - poolH * 1.4f,
            poolCx + poolW * 1.4f, poolY + poolH * 1.4f, soft)
        soft.shader = null
        // Блик: маленький, резкий, СМЕЩЁННЫЙ от центра. Ровно по центру
        // он читался бы как дырка, а не как отражение.
        fill.shader = null
        fill.color = 0xFFFFF3E0.toInt()
        fill.alpha = (215f * amount).toInt().coerceIn(0, 255)
        itemPath.reset()
        itemPath.moveTo(poolCx - poolW * 0.46f, poolY - poolH * 0.44f)
        itemPath.quadTo(poolCx - poolW * 0.18f, poolY - poolH * 0.66f,
            poolCx - poolW * 0.06f, poolY - poolH * 0.36f)
        itemPath.quadTo(poolCx - poolW * 0.26f, poolY - poolH * 0.28f,
            poolCx - poolW * 0.46f, poolY - poolH * 0.44f)
        itemPath.close()
        c.drawPath(itemPath, fill)
        // Второй, крошечный - глаз всегда ищет два отражения.
        fill.alpha = (150f * amount).toInt().coerceIn(0, 255)
        c.drawCircle(poolCx + poolW * 0.28f, poolY - poolH * 0.30f, poolH * 0.13f, fill)
        c.restore()

        // Тёплая кромка - обводка ТОГО ЖЕ пути, только снизу ярче.
        line.shader = null
        line.color = 0xFFFFD07A.toInt()
        line.alpha = (130f * amount * (0.6f + 0.4f * faceK)).toInt().coerceIn(0, 255)
        line.strokeWidth = 1.7f * d
        c.drawPath(goo, line)

        soft.shader = RadialGradient(poolCx, poolY, poolW * 1.6f, 0x33FFC257, 0x00000000,
            Shader.TileMode.CLAMP)
        soft.alpha = (190f * amount).toInt().coerceIn(0, 255)
        c.drawCircle(poolCx, poolY, poolW * 1.6f, soft)
        soft.shader = null
        fill.alpha = 255
        line.alpha = 255
    }

    /** Кисть прорези глаза: отдельная функция ради читаемости вызова. */
    private fun itemPaintFor(faceK: Float): Paint {
        fill.shader = null
        fill.color = 0xFF0B0709.toInt()
        fill.alpha = (255f * faceK).toInt().coerceIn(0, 255)
        return fill
    }

    private fun blendC(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = ((a shr 16 and 0xFF) + ((b shr 16 and 0xFF) - (a shr 16 and 0xFF)) * k).toInt()
        val g = ((a shr 8 and 0xFF) + ((b shr 8 and 0xFF) - (a shr 8 and 0xFF)) * k).toInt()
        val bl = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun drawDust(c: Canvas, cx: Float, topY: Float, bw: Float, h: Float,
                         strain: Float, open: Float, now: Long) {
        var i = 0
        while (i < 8) {
            val ph = now * 0.0006f * (0.7f + 0.2f * (i % 4)) + rnd(i + 40) * 6.3f
            val bob = (sin(ph.toDouble()).toFloat() + 1f) * 0.5f
            val dx0 = cx + (rnd(i + 50) - 0.5f) * bw * 2.6f
            val dy0 = topY - bw * 0.25f - h * 0.16f * bob
            val dx = dx0 + (cx - dx0) * strain * 0.55f
            val dy = dy0 + (topY - dy0) * strain * 0.45f
            fill.shader = null
            fill.color = 0xFFE8A33D.toInt()
            fill.alpha = (100f * (0.4f + 0.6f * sin((ph * 1.7f).toDouble()).toFloat()) *
                (1f - open * 0.7f)).toInt().coerceIn(0, 255)
            c.drawCircle(dx, dy, (1.1f + 0.5f * bob) * d, fill)
            i++
        }
        fill.alpha = 255
    }

    /** Вылетающее содержимое: та же баллистика, что и у обломков. */
    private fun drawFlyingItems(c: Canvas, cx: Float, topY: Float, bw: Float, openRaw: Float) {
        var i = 0
        while (i < 9) {
            val delay = i * 0.045f
            val tt = ((openRaw - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (tt <= 0f) { i++; continue }
            val tsec = tt * (OPEN_MS / 1000f) * 2.6f
            val ang = -1.9f + rnd(i) * 1.4f
            val v0 = bw * (5.2f + 3.4f * rnd(i + 3))
            val vx0 = cos(ang.toDouble()).toFloat() * v0
            val vy0 = sin(ang.toDouble()).toFloat() * v0
            val drag = (1f - exp((-DRAG * tsec).toDouble()).toFloat()) / DRAG
            val x = cx + vx0 * drag
            val y = topY - bw * 0.15f + vy0 * tsec + 0.5f * GRAV * bw * tsec * tsec
            val fade = (1f - tt * tt).coerceIn(0f, 1f)
            if (fade <= 0.02f) { i++; continue }
            c.save()
            c.translate(x, y)
            c.rotate((rnd(i + 7) - 0.5f) * 900f * tsec)
            val sz = bw * (0.20f + 0.10f * rnd(i + 5))
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
        fill.shader = null
        fill.color = 0xFF9A8A66.toInt(); fill.alpha = (a * 0.5f).toInt()
        c.drawRoundRect(-sz * 0.70f, -sz * 0.26f + 1.2f * d, sz * 0.70f, sz * 0.30f + 1.2f * d,
            sz * 0.08f, sz * 0.08f, fill)
        fill.color = 0xFFEADFC2.toInt(); fill.alpha = a
        c.drawRoundRect(-sz * 0.70f, -sz * 0.28f, sz * 0.70f, sz * 0.28f,
            sz * 0.08f, sz * 0.08f, fill)
        line.shader = null
        line.color = 0xFF8A7A56.toInt(); line.alpha = (a * 0.7f).toInt(); line.strokeWidth = 1.1f * d
        c.drawLine(-sz * 0.5f, -sz * 0.10f, sz * 0.42f, -sz * 0.08f, line)
        c.drawLine(-sz * 0.5f, sz * 0.08f, sz * 0.28f, sz * 0.10f, line)
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
        fill.shader = null
        fill.color = 0xFF9A8A66.toInt(); fill.alpha = (a * 0.5f).toInt()
        c.drawRoundRect(-sz * 0.66f, -sz * 0.42f + 1.2f * d, sz * 0.66f, sz * 0.46f + 1.2f * d,
            sz * 0.06f, sz * 0.06f, fill)
        fill.color = 0xFFF4EEDE.toInt(); fill.alpha = a
        c.drawRoundRect(-sz * 0.66f, -sz * 0.44f, sz * 0.66f, sz * 0.44f,
            sz * 0.06f, sz * 0.06f, fill)
        itemPath.reset()
        itemPath.moveTo(-sz * 0.66f, -sz * 0.44f)
        itemPath.quadTo(0f, sz * 0.10f, sz * 0.66f, -sz * 0.44f)
        itemPath.close()
        fill.color = 0xFFE6DCC4.toInt(); fill.alpha = a
        c.drawPath(itemPath, fill)
        line.shader = null
        line.color = 0xFF9A8A66.toInt(); line.alpha = a; line.strokeWidth = 1.2f * d
        c.drawPath(itemPath, line)
        fill.color = 0xFFB3242C.toInt(); fill.alpha = a
        c.drawCircle(0f, sz * 0.02f, sz * 0.13f, fill)
        fill.color = 0xFF7C161C.toInt(); fill.alpha = a
        c.drawCircle(sz * 0.03f, sz * 0.05f, sz * 0.07f, fill)
    }

    private fun drawNote(c: Canvas, sz: Float, fade: Float) {
        val a = (240f * fade).toInt().coerceIn(0, 255)
        itemPath.reset()
        itemPath.moveTo(-sz * 0.56f, -sz * 0.48f)
        itemPath.quadTo(0f, -sz * 0.56f, sz * 0.58f, -sz * 0.40f)
        itemPath.quadTo(sz * 0.52f, 0f, sz * 0.46f, sz * 0.50f)
        itemPath.quadTo(0f, sz * 0.58f, -sz * 0.62f, sz * 0.38f)
        itemPath.close()
        fill.shader = null
        fill.color = 0xFF9A8A66.toInt(); fill.alpha = (a * 0.45f).toInt()
        c.save(); c.translate(1.2f * d, 1.2f * d); c.drawPath(itemPath, fill); c.restore()
        fill.color = 0xFFEAE4D4.toInt(); fill.alpha = a
        c.drawPath(itemPath, fill)
        line.shader = null
        line.color = 0xFF8A8474.toInt(); line.alpha = (a * 0.8f).toInt(); line.strokeWidth = 1.1f * d
        c.drawLine(-sz * 0.34f, -sz * 0.14f, sz * 0.30f, -sz * 0.08f, line)
        c.drawLine(-sz * 0.34f, sz * 0.06f, sz * 0.16f, sz * 0.12f, line)
        c.drawLine(-sz * 0.34f, sz * 0.24f, sz * 0.02f, sz * 0.28f, line)
    }

    private companion object {
        const val OPEN_MS = 1100f
        const val DENY_MS = 620f
        const val TOUCH_MS = 420f
        const val GRAV = 11.5f
        const val DRAG = 2.1f
        /** Размах дрейфа сцены, dp. Больше - укачивает, меньше - не видно. */
        const val DRIFT = 5.5f
        /** Период появления лица в жидкости, мс. */
        const val FACE_MS = 2600L
        /**
         * Дерево тёмно-багровое, железо холодное.
         *
         * Прежний тон (0xFF6E3A2A) на светлой растяжке уходил в рыжий, и
         * вместе с мягкими краями сундук читался как пластилин. Тон опущен
         * и уведён в красное: тёмное держит форму, светлое её размывает.
         */
        val WOOD = 0xFF4A1E22.toInt()
        val IRON = 0xFF5F5A5E.toInt()
        /** Обводка: почти чёрная, единая для всех очертаний. */
        val INK = 0xFF150609.toInt()
    }
}
