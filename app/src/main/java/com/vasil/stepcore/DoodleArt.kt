package com.vasil.stepcore

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Дудл-графика StepCore (V14.0).
 *
 * Идея: рукописный контур - это не кривая, а ЛОМАНАЯ, у которой каждая
 * промежуточная точка сдвинута случайным шумом. Ключевая деталь: шум
 * СЕЯНЫЙ (детерминированный по сиду элемента), а не свежий на каждый
 * кадр. Свежий шум на каждой перерисовке = дрожащая, вибрирующая
 * картинка, от которой рябит в глазах. Сеяный = линия кривая, но
 * СТАБИЛЬНО кривая, как настоящий рисунок от руки.
 *
 * Вся математика этого файла сначала отрисована в PNG и проверена
 * глазами, и только потом портирована сюда - координаты 1:1.
 */

/**
 * Такт "кипящей линии" (line boil) - приём классической рисованной
 * мультипликации: один и тот же кадр рисуют 2-3 раза чуть по-разному и
 * крутят по кругу, отчего контур будто дышит и рисунок оживает.
 *
 * Почему это дёшево: варианты контура считаются ОДИН раз при изменении
 * размера, дальше на каждом такте просто выбирается уже готовый Path.
 * Никакой математики в кадре - только отрисовка, которая была бы и так.
 *
 * Такт ОДИН на всё приложение, поэтому все элементы кипят синхронно (как
 * в настоящем мультфильме, где перерисовывается весь кадр целиком), и
 * тикает он, только пока есть хоть один живой подписчик: ушёл экран -
 * таймер сам остановился, батарея не тратится в фоне.
 */
internal object BoilClock {
    const val FRAMES = 3
    private const val TICK_MS = 50L        // 20 кадров/с - плавное движение
    private const val BOIL_EVERY = 14      // спокойное «дыхание» линии: ~1.4 к/с

    @Volatile var frame = 0
        private set
    /** Непрерывная фаза в секундах: для плавных петель (облака, мерцание). */
    @Volatile var phase = 0f
        private set

    private var ticks = 0L
    private val listeners = LinkedHashSet<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * СЧЁТЧИК живых экранов, а не глобальный флаг паузы.
     *
     * Первая версия (v95) держала общий флаг paused и дёргала его из
     * жизненного цикла КАЖДОГО экрана - и это ломалось при переходе между
     * вкладками. Android запускает новый экран РАНЬШЕ, чем останавливает
     * старый, поэтому порядок был такой: новый экран просит "крутись" ->
     * старый экран командует "замри" (глобально!) -> механизм глох везде.
     * Возобновлял его только главный экран, поэтому во вкладках анимация
     * была мёртвой.
     *
     * Счётчик неуязвим к этому порядку: при переходе он идёт 1 -> 2 -> 1 и
     * НИКОГДА не касается нуля. Ноль наступает, только когда закрыт
     * последний экран - тогда механизм честно замирает и не будит
     * процессор в фоне.
     */
    private var screens = 0

    private val running: Boolean
        get() = screens > 0 && listeners.isNotEmpty()

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            phase = (ticks * TICK_MS).toFloat() / 1000f
            if (ticks % BOIL_EVERY == 0L) frame = (frame + 1) % FRAMES
            for (l in ArrayList(listeners)) l()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun restartIfNeeded() {
        handler.removeCallbacks(tick)
        if (running) handler.postDelayed(tick, TICK_MS)
    }

    fun register(l: () -> Unit) {
        listeners.add(l)
        restartIfNeeded()
    }

    fun unregister(l: () -> Unit) {
        listeners.remove(l)
        restartIfNeeded()
    }

    /** Экран стал видимым (Activity.onStart). */
    fun screenStarted() {
        screens++
        restartIfNeeded()
    }

    /** Экран скрылся (Activity.onStop). */
    fun screenStopped() {
        screens--
        if (screens < 0) screens = 0
        restartIfNeeded()
    }
}

/** Тот же SplitMix64, что в движке Survival: быстрый, детерминированный. */
internal class Wobble(seed: Long) {
    private var s = seed
    private fun next(): Long {
        s += -0x61c8864680b583ebL
        var z = s
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
    /** Равномерно в [-a, a]. */
    fun j(a: Float): Float {
        val u = (next() ushr 11).toDouble() / (1L shl 53).toDouble()
        return ((u * 2.0 - 1.0) * a).toFloat()
    }
}

/** Утилиты рисования дрожащих фигур. Общие для рамок и сцен. */
internal object Doodle {

    /** Прямая -> ломаная с шумом. */
    fun line(p: Path, x0: Float, y0: Float, x1: Float, y1: Float,
             jit: Float, seg: Int, w: Wobble) {
        // Уверенная линия: один плавный прогиб перпендикулярно ходу пера
        // (перо ведёт лёгкой дугой) + едва заметная текстура. Это заменяет
        // высокочастотную случайную дрожь, из-за которой линия выглядела
        // трясущейся, «детской».
        val dx = x1 - x0; val dy = y1 - y0
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val px: Float; val py: Float
        if (len > 0.001f) { px = -dy / len; py = dx / len } else { px = 0f; py = 0f }
        val bow = w.j(jit) * 0.9f
        p.moveTo(x0, y0)
        for (i in 1..seg) {
            val t = i.toFloat() / seg
            var x = x0 + dx * t
            var y = y0 + dy * t
            if (i < seg) {
                val d = bow * sin((Math.PI * t).toFloat()) + w.j(jit * 0.16f)
                x += px * d; y += py * d
            }
            p.lineTo(x, y)
        }
    }

    /** Замкнутый (или нет) многоугольник дрожащими сторонами. */
    fun poly(p: Path, pts: FloatArray, jit: Float, seg: Int, w: Wobble, close: Boolean) {
        val n = pts.size / 2
        val last = if (close) n else n - 1
        for (i in 0 until last) {
            val a = i * 2
            val b = ((i + 1) % n) * 2
            line(p, pts[a], pts[a + 1], pts[b], pts[b + 1], jit, seg, w)
        }
    }

    /** Скруглённый прямоугольник от руки - основа рамки карточки. */
    fun roundRect(p: Path, x: Float, y: Float, ww: Float, hh: Float, r: Float,
                  jit: Float, w: Wobble) {
        line(p, x + r, y, x + ww - r, y, jit, 16, w)
        line(p, x + ww, y + r, x + ww, y + hh - r, jit, 16, w)
        line(p, x + ww - r, y + hh, x + r, y + hh, jit, 16, w)
        line(p, x, y + hh - r, x, y + r, jit, 16, w)
        arc(p, x + r, y + r, r, 180f, 270f, jit, w)
        arc(p, x + ww - r, y + r, r, 270f, 360f, jit, w)
        arc(p, x + ww - r, y + hh - r, r, 0f, 90f, jit, w)
        arc(p, x + r, y + hh - r, r, 90f, 180f, jit, w)
    }

    fun arc(p: Path, cx: Float, cy: Float, r: Float, a0: Float, a1: Float,
            jit: Float, w: Wobble) {
        for (k in 0..8) {
            val a = Math.toRadians((a0 + (a1 - a0) * k / 8f).toDouble())
            var x = cx + r * cos(a).toFloat()
            var y = cy + r * sin(a).toFloat()
            if (k in 1..7) { x += w.j(jit); y += w.j(jit) }
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
    }

    /** Четырёхлучевая искра - фирменный элемент стиля. */
    /**
     * Каллиграфический нажим (Этап 2). Копия штриха со сдвигом по оси пера:
     * линии поперёк оси выходят толще, вдоль - тоньше. Эффект широкого пера,
     * живая переменная толщина вместо ровной нитки.
     */
    fun ink(c: Canvas, path: Path, paint: Paint, nib: Float) {
        c.save(); c.translate(nib, nib); c.drawPath(path, paint); c.restore()
        c.drawPath(path, paint)
    }

    fun star(p: Path, cx: Float, cy: Float, r: Float, w: Wobble) {
        val k = r * 0.32f
        poly(p, floatArrayOf(
            cx, cy - r, cx + k, cy - k, cx + r, cy, cx + k, cy + k,
            cx, cy + r, cx - k, cy + k, cx - r, cy, cx - k, cy - k
        ), 0.8f, 4, w, true)
    }

    /** Ёлка: три яруса + ствол + травинки. */
    fun fir(p: Path, x: Float, baseY: Float, h: Float, w: Wobble) {
        val ww = h * 0.42f
        for (i in 0 until 3) {
            val ly = baseY - h * (0.30f + 0.28f * i)
            val lw = ww * (1f - 0.22f * i)
            poly(p, floatArrayOf(x, ly - h * 0.30f, x + lw / 2, ly, x - lw / 2, ly),
                1.2f, 6, w, true)
        }
        line(p, x, baseY, x, baseY - h * 0.32f, 1.0f, 4, w)
        line(p, x - 6f, baseY, x - 10f, baseY - 6f, 0.8f, 3, w)
        line(p, x + 6f, baseY, x + 10f, baseY - 6f, 0.8f, 3, w)
    }

    /** Палатка: скаты, вход, оттяжки. */
    fun tent(p: Path, cx: Float, baseY: Float, ww: Float, h: Float, w: Wobble) {
        poly(p, floatArrayOf(cx - ww / 2, baseY, cx + ww * 0.05f, baseY - h,
            cx + ww / 2, baseY), 1.4f, 8, w, true)
        poly(p, floatArrayOf(cx - ww * 0.10f, baseY, cx + ww * 0.03f, baseY - h * 0.62f,
            cx + ww * 0.16f, baseY), 1.2f, 6, w, true)
        line(p, cx + ww * 0.05f, baseY - h, cx + ww * 0.62f, baseY - h * 0.08f, 1.0f, 6, w)
        line(p, cx - ww * 0.52f, baseY - h * 0.06f, cx - ww / 2, baseY, 1.0f, 4, w)
    }

    /** Горный хребет со снежными шапками. */
    fun mountains(p: Path, snow: Path, x0: Float, baseY: Float, ww: Float, h: Float, w: Wobble) {
        val px = floatArrayOf(x0 + ww * 0.22f, x0 + ww * 0.52f, x0 + ww * 0.80f)
        val py = floatArrayOf(baseY - h * 0.72f, baseY - h, baseY - h * 0.60f)
        val pts = ArrayList<Float>()
        pts.add(x0); pts.add(baseY)
        for (i in 0 until 3) {
            pts.add(px[i]); pts.add(py[i])
            pts.add(px[i] + ww * 0.10f); pts.add(baseY - h * 0.22f)
        }
        pts.add(x0 + ww); pts.add(baseY)
        poly(p, pts.toFloatArray(), 1.6f, 8, w, false)
        line(p, x0, baseY, x0 + ww, baseY, 1.4f, 14, w)
        for (i in 0 until 3) {
            poly(snow, floatArrayOf(
                px[i] - 9f, py[i] + 13f, px[i], py[i], px[i] + 9f, py[i] + 13f,
                px[i] + 4f, py[i] + 9f, px[i] - 2f, py[i] + 14f
            ), 0.8f, 4, w, false)
        }
    }

    /** Облако одной волнистой дугой. */
    fun cloud(p: Path, cx: Float, cy: Float, s: Float, w: Wobble) {
        var first = true
        for (k in 0..24) {
            val t = k / 24f
            val a = Math.PI * (1f - t)
            val bump = 1f + 0.35f * sin(t * Math.PI * 3).toFloat()
            val x = cx + s * 1.4f * cos(a).toFloat() + w.j(1f)
            val y = cy - s * 0.55f * sin(a).toFloat() * bump + w.j(1f)
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
        line(p, cx - s * 1.4f, cy, cx + s * 1.4f, cy, 1.0f, 8, w)
    }

    /** Полумесяц: внешняя дуга + вырез. */
    fun moon(p: Path, cx: Float, cy: Float, r: Float, w: Wobble) {
        var first = true
        for (k in 0..20) {
            val a = Math.toRadians((40 + 280 * k / 20f).toDouble())
            val x = cx + r * cos(a).toFloat() + w.j(0.8f)
            val y = cy + r * sin(a).toFloat() + w.j(0.8f)
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
        first = true
        for (k in 0..16) {
            val a = Math.toRadians((60 + 240 * k / 16f).toDouble())
            val x = cx + r * 0.45f + r * 0.85f * cos(a).toFloat() + w.j(0.8f)
            val y = cy + r * 0.85f * sin(a).toFloat() + w.j(0.8f)
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
    }

    /** Солнце: круг + лучи. */
    fun sun(p: Path, cx: Float, cy: Float, r: Float, w: Wobble) {
        var first = true
        for (k in 0..24) {
            val a = Math.toRadians((360 * k / 24f).toDouble())
            val x = cx + r * cos(a).toFloat() + w.j(1f)
            val y = cy + r * sin(a).toFloat() + w.j(1f)
            if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
        }
        for (k in 0 until 8) {
            val a = Math.toRadians((45.0 * k + 10.0))
            line(p, cx + r * 1.45f * cos(a).toFloat(), cy + r * 1.45f * sin(a).toFloat(),
                cx + r * 2.05f * cos(a).toFloat(), cy + r * 2.05f * sin(a).toFloat(),
                0.8f, 3, w)
        }
    }

    /**
     * Штриховка "от руки" - заливка, которая НЕ идеальна: косые штрихи с
     * дрожью, местами обрывающиеся, не достающие до краёв. Ровная заливка
     * рядом с дрожащим контуром выглядит инородно - как будто фигуру
     * закрасили в фотошопе поверх карандашного рисунка.
     */
    fun hatch(p: Path, x0f: Float, y0f: Float, x1f: Float, y1f: Float,
              step: Float, angDeg: Float, inset: Float, w: Wobble) {
        val x0 = x0f + inset; val y0 = y0f + inset
        val x1 = x1f - inset; val y1 = y1f - inset
        if (x1 <= x0 || y1 <= y0) return
        val t = kotlin.math.tan(Math.toRadians(angDeg.toDouble())).toFloat()
        val slope = (y1 - y0) / (if (kotlin.math.abs(t) < 0.1f) 0.1f else kotlin.math.abs(t))
        var px = x0 - slope
        var k = 0
        val dir = if (angDeg < 0) -1f else 1f
        while (px < x1 + slope) {
            // Каждый пятый штрих пропускаем: рука не кладёт линии подряд ровно.
            if (k % 5 != 4) {
                var started = false
                for (sIdx in 0..8) {
                    val u = sIdx / 8f
                    val x = px + dir * u * slope
                    val y = y0 + u * (y1 - y0)
                    if (x in x0..x1) {
                        val jx = x + w.j(1f); val jy = y + w.j(1f)
                        if (!started) { p.moveTo(jx, jy); started = true } else p.lineTo(jx, jy)
                    }
                }
            }
            px += step
            k++
        }
    }

    /**
     * Документ-паспорт: фото (лицо НАРОЧНО неразборчивое - поверх идёт
     * штриховка), строки данных, штрих-код. Луч сканера рисуется отдельно,
     * потому что он движется, а сам документ - нет.
     */
    fun passport(doc: Path, photo: Path, bars: Path, cx: Float, cy: Float,
                 ww: Float, hh: Float, w: Wobble) {
        val x = cx - ww / 2f; val y = cy - hh / 2f
        roundRect(doc, x, y, ww, hh, 5f, 1.2f, w)

        // фото слева
        val px = x + ww * 0.04f; val py = y + hh * 0.06f
        val pw = ww * 0.26f; val phh = hh * 0.52f
        roundRect(photo, px, py, pw, phh, 3f, 1.0f, w)
        val fcx = px + pw / 2f; val hy = py + phh * 0.36f
        // голова
        for (k in 0..16) {
            val a2 = Math.toRadians(360.0 * k / 16)
            val xx = fcx + pw * 0.20f * cos(a2).toFloat() + w.j(0.6f)
            val yy = hy + pw * 0.20f * sin(a2).toFloat() + w.j(0.6f)
            if (k == 0) photo.moveTo(xx, yy) else photo.lineTo(xx, yy)
        }
        // плечи
        arc(photo, fcx, py + phh * 0.90f, pw * 0.36f, 180f, 360f, 0.8f, w)
        // штриховка ПОВЕРХ лица: снимок не должен читаться
        for (k in 0 until 6) {
            val yy = py + 2f + k * (phh - 4f) / 6f
            line(photo, px + 2f, yy, px + pw - 2f, yy, 0.8f, 4, w)
        }

        // строки данных справа
        for (k in 0 until 4) {
            val yy = y + hh * 0.10f + k * (hh * 0.13f)
            val len = if (k % 2 == 0) ww * 0.42f else ww * 0.32f
            line(doc, x + ww * 0.34f, yy, x + ww * 0.34f + len, yy, 0.8f, 5, w)
        }

        // штрих-код: ширины прутьев неровные, как настоящий
        val bx = x + ww * 0.04f
        val by = y + hh * 0.70f
        val bw = ww * 0.92f
        val bh = hh * 0.20f
        var cur = bx
        var k = 0
        while (cur < bx + bw) {
            val wd = 1f + (k * 7 % 3)
            bars.addRect(cur, by, cur + wd, by + bh, Path.Direction.CW)
            cur += wd + 2f + (k * 5 % 3)
            k++
        }
    }

    /** Уголки сканера - рамка прицела вокруг документа. */
    fun scanCorners(p: Path, cx: Float, cy: Float, ww: Float, hh: Float, len: Float) {
        val x0 = cx - ww / 2f - 3f
        val x1 = cx + ww / 2f + 3f
        val y0 = cy - hh / 2f - 3f
        val y1 = cy + hh / 2f + 3f
        corner(p, x0, y0, 1f, 1f, len)
        corner(p, x1, y0, -1f, 1f, len)
        corner(p, x0, y1, 1f, -1f, len)
        corner(p, x1, y1, -1f, -1f, len)
    }

    private fun corner(p: Path, x: Float, y: Float, dx: Float, dy: Float, len: Float) {
        p.moveTo(x, y + dy * len)
        p.lineTo(x, y)
        p.lineTo(x + dx * len, y)
    }

    /** Шестерня: зубчатый контур + втулка. Угол задаётся снаружи -> крутится. */
    fun gear(p: Path, cx: Float, cy: Float, r: Float, teeth: Int, rotDeg: Float, w: Wobble) {
        val n = teeth * 4
        for (k in 0..n) {
            val a = Math.toRadians((rotDeg + 360.0 * k / n))
            val rr = if (k % 4 < 2) r else r * 0.78f
            val x = cx + rr * cos(a).toFloat() + w.j(0.8f)
            val y = cy + rr * sin(a).toFloat() + w.j(0.8f)
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        for (k in 0..12) {
            val a = Math.toRadians(360.0 * k / 12)
            val x = cx + r * 0.30f * cos(a).toFloat()
            val y = cy + r * 0.30f * sin(a).toFloat()
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
    }

    /** Песочные часы: рама (контур) + песок (заливка), fill 1..0 - пересыпается. */
    fun hourglass(frame: Path, sand: Path, cx: Float, cy: Float, ww: Float, hh: Float,
                  fill: Float, w: Wobble) {
        val top = cy - hh / 2f; val bot = cy + hh / 2f
        line(frame, cx - ww / 2, top, cx + ww / 2, top, 1.0f, 6, w)
        line(frame, cx - ww / 2, bot, cx + ww / 2, bot, 1.0f, 6, w)
        line(frame, cx - ww / 2, top, cx + ww / 2, bot, 1.2f, 8, w)
        line(frame, cx + ww / 2, top, cx - ww / 2, bot, 1.2f, 8, w)
        val half = hh / 2f
        val fh = half * fill
        if (fh > 2f) {
            val tw = (ww / 2f) * (fh / half)
            sand.moveTo(cx - tw, top + (half - fh))
            sand.lineTo(cx + tw, top + (half - fh))
            sand.lineTo(cx, cy); sand.close()
        }
        val bh = half * (1f - fill)
        if (bh > 2f) {
            val bw = (ww / 2f) * (bh / half)
            sand.moveTo(cx - bw, bot); sand.lineTo(cx + bw, bot)
            sand.lineTo(cx, bot - bh); sand.close()
        }
        line(frame, cx, cy + 2f, cx, cy + half * 0.6f, 0.6f, 3, w)  // струйка
    }

    /** Дорожный указатель. */
    fun signpost(p: Path, x: Float, baseY: Float, h: Float, w: Wobble) {
        line(p, x, baseY, x, baseY - h, 1.0f, 6, w)
        for ((dx, yy) in listOf(30f to 0.75f, -28f to 0.55f)) {
            val y = baseY - h * yy
            val tip = if (dx > 0) dx + 6f else dx - 6f
            poly(p, floatArrayOf(x, y - 7f, x + dx, y - 7f, x + tip, y, x + dx, y + 7f, x, y + 7f),
                0.8f, 4, w, true)
        }
    }

    /** Извилистая река. */
    fun river(p: Path, x0: Float, y0: Float, x1: Float, y1: Float, w: Wobble) {
        val n = 22
        for (k in 0..n) {
            val t = k.toFloat() / n
            val x = x0 + (x1 - x0) * t + w.j(1f)
            val y = y0 + (y1 - y0) * t + 18f * sin(t * Math.PI * 2.4).toFloat() + w.j(1f)
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
    }

    /** Тетрадь со страницами и пружиной - для Истории. */
    fun notebook(p: Path, cx: Float, cy: Float, ww: Float, hh: Float, w: Wobble) {
        for (off in intArrayOf(6, 0)) {
            roundRect(p, cx - ww / 2 + off, cy - hh / 2 - off, ww, hh, 4f, 1.0f, w)
        }
        for (i in 0 until 3) {
            val y = cy - hh / 4f + i * (hh / 5f)
            line(p, cx - ww / 2 + 8f, y, cx + ww / 2 - 10f, y, 0.8f, 5, w)
        }
        for (i in 0 until 4) {
            val y = cy - hh / 2f + 6f + i * (hh / 5f)
            arc(p, cx - ww / 2f, y, 5f, 90f, 270f, 0.5f, w)
        }
    }

    /** Костёр: поленья + языки пламени. */
    fun fire(logs: Path, flame: Path, cx: Float, baseY: Float, s: Float, w: Wobble) {
        line(logs, cx - s, baseY, cx + s, baseY - s * 0.35f, 1.0f, 5, w)
        line(logs, cx - s, baseY - s * 0.35f, cx + s, baseY, 1.0f, 5, w)
        var first = true
        for (k in 0..12) {
            val a = Math.toRadians((-90 + 180 * (k / 12f)).toDouble())
            val rr = s * (if (k % 2 == 0) 1.5f else 1.0f)
            val x = cx + rr * 0.55f * cos(a).toFloat() + w.j(1f)
            val y = baseY - s * 0.5f - rr * 0.9f * abs(sin(a).toFloat()) + w.j(1f)
            if (first) { flame.moveTo(x, y); first = false } else flame.lineTo(x, y)
        }
    }
}

/**
 * Рамка карточки "от руки". Вешается фоном на любую View вместо
 * shape-drawable: заливка + дрожащий контур нужного цвета.
 *
 * Сид привязан к переданному значению, поэтому одна и та же карточка
 * всегда выглядит одинаково, а РАЗНЫЕ карточки кривятся по-разному -
 * как несколько раз нарисованный от руки прямоугольник.
 */
class DoodleBorderDrawable(
    private val strokeColor: Int,
    private val fillColor: Int,
    private val seed: Long,
    density: Float,
) : Drawable() {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = strokeColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    // UI-2: резной бевел. Светлый кант ловит свет сверху-слева, тёмная
    // грань уводит вглубь снизу-справа - плита читается как вырезанная.
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = lighten(strokeColor, 0.55f)
        alpha = 150
    }
    private val shPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        alpha = 170
    }
    private val d = density
    /** Готовые варианты контура - по одному на кадр "кипения". */
    private val frames = Array(BoilClock.FRAMES) { Path() }
    private var builtFor = Rect()
    private val onTick: () -> Unit = { invalidateSelf() }
    private var subscribed = false

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds == builtFor || bounds.isEmpty) return
        builtFor = Rect(bounds)
        val inset = 2f * d
        // Каждый кадр - СВОЙ сид: контур тот же, дрожь другая. Вся
        // математика делается здесь, один раз, а не на каждом кадре.
        for (i in frames.indices) {
            val w = Wobble(seed * 31L + i)
            frames[i].reset()
            Doodle.roundRect(frames[i], inset, inset,
                bounds.width() - 2 * inset, bounds.height() - 2 * inset,
                14f * d, 1.5f * d, w)
        }
    }

    /**
     * View сам вызывает setVisible при уходе/появлении окна - это и есть
     * честный жизненный цикл для Drawable. Подписка только на видимое:
     * невидимая карточка не заставляет таймер крутиться.
     */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible && !subscribed) {
            BoilClock.register(onTick); subscribed = true
        } else if (!visible && subscribed) {
            BoilClock.unregister(onTick); subscribed = false
        }
        return changed
    }

    override fun draw(canvas: Canvas) {
        val p = frames[BoilClock.frame]
        val off = 1.4f * d
        // тёмная грань снизу-справа (глубина), затем светлый кант сверху-слева
        canvas.save(); canvas.translate(off, off); canvas.drawPath(p, shPaint); canvas.restore()
        canvas.save(); canvas.translate(-off, -off); canvas.drawPath(p, hiPaint); canvas.restore()
        if (fillColor != Color.TRANSPARENT) canvas.drawPath(p, fillPaint)
        Doodle.ink(canvas, p, strokePaint, 0.8f * d)
    }

    /** Осветление цвета к белому на долю t - для светлого канта резьбы. */
    private fun lighten(c: Int, t: Float): Int {
        val r = (Color.red(c) + (255 - Color.red(c)) * t).toInt()
        val g = (Color.green(c) + (255 - Color.green(c)) * t).toInt()
        val b = (Color.blue(c) + (255 - Color.blue(c)) * t).toInt()
        return Color.argb(255, r, g, b)
    }

    override fun setAlpha(alpha: Int) { strokePaint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {
        strokePaint.colorFilter = cf
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Общий помощник: одна строка на карточку в любом экране. */
object DoodleUi {
    /**
     * Мягкая пульсация: элемент дышит прозрачностью, чтобы его нельзя было
     * пропустить взглядом. Подписка живёт ровно столько, сколько View
     * привязана к окну - отписка в onViewDetached, поэтому утечки нет и
     * механизм не крутится ради мёртвой View.
     */
    fun pulse(v: View) {
        val tick: () -> Unit = {
            val k = 0.5f + 0.5f * kotlin.math.sin((BoilClock.phase * 2.2f).toDouble()).toFloat()
            v.alpha = 0.70f + 0.30f * k
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = BoilClock.register(tick)
            override fun onViewDetachedFromWindow(view: View) {
                BoilClock.unregister(tick)
                view.alpha = 1f
            }
        })
        if (v.isAttachedToWindow) BoilClock.register(tick)
    }

    /**
     * КНОПКА-ФИШКА: значок + короткое слово в дрожащей рамке.
     *
     * Зачем. Кнопка во весь экран сообщает ровно одно: «здесь что-то можно
     * нажать». Какое именно — приходится читать. На ходу человек не читает,
     * он узнаёт. Поэтому у фишки три носителя смысла сразу:
     *   значок — ЧТО она делает,
     *   цвет   — ЧТО она значит (зелёное — действие, красное — необратимое,
     *            бирюзовое — данные, фиолетовое — вынести наружу),
     *   размер — насколько её стоит бояться.
     *
     * Кнопка остаётся обычной Button: клики, состояния, доступность — всё
     * системное. Меняется только одежда.
     */
    fun chip(
        btn: android.widget.Button,
        icon: Int,
        strokeRes: Int,
        fillRes: Int,
        textRes: Int,
        seed: Long,
    ) {
        val c = btn.context
        val d = c.resources.displayMetrics.density
        frame(btn, strokeRes, fillRes, seed)
        btn.setCompoundDrawablesWithIntrinsicBounds(
            DoodleIconDrawable(icon, ContextCompat.getColor(c, textRes), d, 19f),
            null, null, null)
        btn.compoundDrawablePadding = (6 * d).toInt()
        btn.setTextColor(ContextCompat.getColor(c, textRes))
        btn.setAllCaps(false)
        btn.textSize = 14f
        // Системные минимумы кнопки (48dp) растягивали фишку в полосу.
        btn.minWidth = 0
        btn.minimumWidth = 0
        btn.minHeight = 0
        btn.minimumHeight = 0
        btn.setPadding((12 * d).toInt(), (9 * d).toInt(), (12 * d).toInt(), (9 * d).toInt())
    }

    fun frame(v: View, strokeRes: Int, fillRes: Int, seed: Long) {
        val c = v.context
        // Старую рамку ОБЯЗАТЕЛЬНО гасим перед заменой. Рамка подписана на
        // общий механизм и отписывается в setVisible(false); если просто
        // затереть фон, подписка осталась бы висеть. Экран Экспедиции
        // перекрашивает кнопки на КАЖДЫЙ клик - подписчики копились бы
        // бесконечно, и механизм дёргал бы мёртвые рамки.
        (v.background as? DoodleBorderDrawable)?.setVisible(false, false)
        v.background = DoodleBorderDrawable(
            ContextCompat.getColor(c, strokeRes),
            ContextCompat.getColor(c, fillRes),
            seed,
            c.resources.displayMetrics.density,
        )
    }
}

/**
 * Дудл-сцена: декоративный слой (пейзаж/ночь/день/лагерь). Чисто
 * визуальный, не перехватывает касания - кладётся ПОД контент.
 */
class DoodleSceneView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        // У КАЖДОГО экрана своя сцена и свой оттенок - вкладку видно,
        // не читая заголовок.
        const val HEADER = 0       // Главный: горный кряж, луна (фиолет+бирюза)
        const val NIGHT = 1        // Карточка ВЧЕРА: луна, звёзды (фиолет)
        const val DAY = 2          // Карточка СЕГОДНЯ: солнце, ёлки (бирюза)
        const val EXPEDITION = 3   // Экспедиция: лагерь, живой костёр (янтарь)
        const val PROFILE = 4      // Профиль: документ под сканером (фиолет+красный луч)
        const val STATS = 5        // Статистика: пики и река (синий)
        const val TIMELINE = 6     // Timeline: солнце, указатель, облака (янтарь)
        const val CALIBRATION = 7  // Калибровка: шестерни, песочные часы (фиолет)
        const val HISTORY = 8      // История: тетради, страницы (серый)

        private const val DECOR_ALPHA = 130
    }

    private var scene = HEADER
    private val d = resources.displayMetrics.density
    private val firLit = 0xFF2FA88A.toInt()
    private val firShadow = 0xFF1A6350.toInt()
    private val firTrunk = 0xFF3A2E22.toInt()
    private val firFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val firOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * resources.displayMetrics.density; color = 0xFF0D1F18.toInt()
    }
    private val mtFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val mtEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * resources.displayMetrics.density; alpha = 110
    }
    private val mtOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * resources.displayMetrics.density; alpha = 175
    }
    private val skyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val skyOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * resources.displayMetrics.density; alpha = 170
    }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.4f * resources.displayMetrics.density
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

    private val violet = ContextCompat.getColor(context, R.color.accent_violet)
    private val violetBr = ContextCompat.getColor(context, R.color.accent_violet_bright)
    private val teal = ContextCompat.getColor(context, R.color.accent_teal)
    private val tealBr = ContextCompat.getColor(context, R.color.accent_teal_bright)
    private val amber = ContextCompat.getColor(context, R.color.accent_amber)
    private val amberBr = ContextCompat.getColor(context, R.color.accent_amber_bright)
    private val blue = ContextCompat.getColor(context, R.color.accent_blue)
    private val blueBr = ContextCompat.getColor(context, R.color.accent_blue_bright)
    private val gray = ContextCompat.getColor(context, R.color.axis_dim)
    private val red = ContextCompat.getColor(context, R.color.accent_red)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val ember = 0xFF966E46.toInt()

    /**
     * Декор ПОЛУПРОЗРАЧЕН намеренно: он фон, а не контент, и обязан уступать
     * тексту. Первая версия рисовала в полную силу - цифры стало не прочесть.
     */
    private fun stroke(c: Int, wpx: Float, a: Int = DECOR_ALPHA) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = c; alpha = a
            strokeWidth = wpx * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }

    /** Один ярус пушистой ёлки: бахрома веток снизу (кончики вниз). */
    private fun fluffyTier(x: Float, apexY: Float, botY: Float, tw: Float, w: Wobble): Path {
        val p = Path()
        p.moveTo(x, apexY)
        p.lineTo(x - tw, botY)
        val n = 5
        for (s in 1..n) {
            val xm = (x - tw) + (2f * tw) * ((s - 0.5f) / n)
            val xr = (x - tw) + (2f * tw) * (s.toFloat() / n)
            p.lineTo(xm + w.j(1.2f), botY + tw * 0.20f)
            p.lineTo(xr, botY - tw * 0.03f)
        }
        p.lineTo(x, apexY)
        p.close()
        return p
    }

    /**
     * Пушистая затенённая ёлка: ствол + 4 яруса с бахромой веток, цел-шейдинг
     * (свет слева / тень справа), снег на верхних лапах с мерцающим бликом.
     * Декор полупрозрачен (уступает тексту), но объёмнее прежнего контура.
     */
    private fun firRich(c: Canvas, x: Float, baseY: Float, h: Float, w: Wobble) {
        val a = 175
        val trunkW = h * 0.07f
        firFill.color = firTrunk; firFill.alpha = a
        c.drawRect(x - trunkW / 2f, baseY - h * 0.12f, x + trunkW / 2f, baseY, firFill)
        for (i in 3 downTo 0) {
            val apexY = baseY - h * (0.24f + 0.185f * i)
            val botY = apexY + h * 0.26f
            val tw = h * 0.46f * (1f - 0.17f * i)
            val path = fluffyTier(x, apexY, botY, tw, w)
            firFill.color = firLit; firFill.alpha = a
            c.drawPath(path, firFill)
            c.save()
            c.clipRect(x, apexY - h, x + tw + 4f * d, botY + 6f * d)
            firFill.color = firShadow; firFill.alpha = (a * 0.75f).toInt()
            c.drawPath(path, firFill)
            c.restore()
            Doodle.ink(c, path, firOutline, 0.6f * d)
            if (i >= 2) {
                val ph = BoilClock.phase * 1.6f + x * 0.03f + i
                val gl = 0.6f + 0.4f * kotlin.math.sin(ph.toDouble()).toFloat()
                firFill.color = 0xFFFFFFFF.toInt(); firFill.alpha = (140f * gl).toInt().coerceIn(0, 255)
                c.drawCircle(x - tw * 0.30f, apexY + h * 0.05f, h * 0.035f, firFill)
            }
        }
        firFill.alpha = 255
    }

    private fun lightenC(col: Int, t: Float): Int {
        val r = (Color.red(col) + (255 - Color.red(col)) * t).toInt()
        val g = (Color.green(col) + (255 - Color.green(col)) * t).toInt()
        val b = (Color.blue(col) + (255 - Color.blue(col)) * t).toInt()
        return Color.argb(255, r, g, b)
    }

    private fun darkenC(col: Int, t: Float): Int {
        return Color.argb(255, (Color.red(col) * (1 - t)).toInt(),
            (Color.green(col) * (1 - t)).toInt(), (Color.blue(col) * (1 - t)).toInt())
    }

    /**
     * Объёмный горный хребет: из оттенка сцены выводятся свет/тень (цел-
     * шейдинг), снежные шапки мерцают бегущей по вершинам волной блика,
     * рёбра от вершин + жирный контур. Декор полупрозрачен.
     */
    private fun mountainsRich(c: Canvas, x0: Float, baseY: Float, ww: Float, h: Float,
                              w: Wobble, tint: Int) {
        val lit = lightenC(tint, 0.12f)
        val shadow = darkenC(tint, 0.45f)
        val px = floatArrayOf(x0 + ww * 0.22f, x0 + ww * 0.52f, x0 + ww * 0.80f)
        val py = floatArrayOf(baseY - h * 0.72f, baseY - h, baseY - h * 0.60f)
        val sil = Path()
        sil.moveTo(x0, baseY)
        sil.lineTo(px[0], py[0]); sil.lineTo(px[0] + ww * 0.10f, baseY - h * 0.24f)
        sil.lineTo(px[1], py[1]); sil.lineTo(px[1] + ww * 0.10f, baseY - h * 0.22f)
        sil.lineTo(px[2], py[2]); sil.lineTo(x0 + ww, baseY)
        sil.close()

        c.save(); c.clipPath(sil)
        mtFill.color = lit; mtFill.alpha = 165; c.drawPath(sil, mtFill)
        mtFill.color = shadow; mtFill.alpha = 120
        c.drawRect(x0 + ww * 0.55f, baseY - h * 1.15f, x0 + ww + 6f, baseY + 6f, mtFill)
        c.restore()

        mtEdge.color = shadow
        for (i in 0 until 3) {
            val e = Path(); e.moveTo(px[i], py[i]); e.lineTo(px[i], baseY); c.drawPath(e, mtEdge)
        }

        mtOutline.color = darkenC(tint, 0.55f)
        Doodle.ink(c, sil, mtOutline, 0.7f * d)

        for (i in 0 until 3) {
            val cap = Path()
            cap.moveTo(px[i], py[i])
            cap.lineTo(px[i] - h * 0.09f, py[i] + h * 0.13f)
            cap.lineTo(px[i] + h * 0.02f, py[i] + h * 0.09f)
            cap.lineTo(px[i] + h * 0.09f, py[i] + h * 0.13f)
            cap.close()
            val t = BoilClock.phase * 0.7f - i * 0.8f
            val gl = 0.55f + 0.45f * kotlin.math.sin(t.toDouble()).toFloat()
            mtFill.color = 0xFFFFFFFF.toInt()
            mtFill.alpha = (150f + 90f * gl).toInt().coerceIn(0, 255)
            c.drawPath(cap, mtFill)
        }
        mtFill.alpha = 255
    }

    private fun moonRich(c: Canvas, cx: Float, cy: Float, r: Float, w: Wobble, tint: Int) {
        val lit = lightenC(tint, 0.55f)
        val dark = darkenC(tint, 0.35f)
        val gl = 0.6f + 0.4f * kotlin.math.sin((BoilClock.phase * 0.8f).toDouble()).toFloat()
        skyFill.color = tint
        skyFill.alpha = (26f * gl).toInt().coerceIn(0, 255); c.drawCircle(cx, cy, r * 1.9f, skyFill)
        skyFill.alpha = (44f * gl).toInt().coerceIn(0, 255); c.drawCircle(cx, cy, r * 1.35f, skyFill)
        val cres = Path()
        cres.moveTo(cx, cy - r)
        cres.quadTo(cx - r * 1.3f, cy, cx, cy + r)
        cres.quadTo(cx + r * 0.4f, cy, cx, cy - r)
        cres.close()
        skyFill.color = lit; skyFill.alpha = 235; c.drawPath(cres, skyFill)
        skyFill.color = darkenC(lit, 0.14f); skyFill.alpha = 150
        c.drawCircle(cx - r * 0.5f, cy - r * 0.15f, r * 0.15f, skyFill)
        c.drawCircle(cx - r * 0.32f, cy + r * 0.32f, r * 0.10f, skyFill)
        skyOutline.color = dark; Doodle.ink(c, cres, skyOutline, 0.6f * d)
        skyFill.alpha = 255
    }

    private fun sunRich(c: Canvas, cx: Float, cy: Float, r: Float, w: Wobble, tint: Int) {
        val lit = lightenC(tint, 0.30f)
        val core = lightenC(tint, 0.65f)
        val dark = darkenC(tint, 0.30f)
        val rot = BoilClock.phase * 0.18f
        rayPaint.color = lit; rayPaint.alpha = 150
        for (k in 0 until 10) {
            val a = rot + k * (Math.PI.toFloat() * 2f / 10f)
            val len = 1.7f + 0.25f * kotlin.math.sin((BoilClock.phase * 1.4f + k).toDouble()).toFloat()
            c.drawLine(
                cx + r * 1.25f * kotlin.math.cos(a.toDouble()).toFloat(),
                cy + r * 1.25f * kotlin.math.sin(a.toDouble()).toFloat(),
                cx + r * len * kotlin.math.cos(a.toDouble()).toFloat(),
                cy + r * len * kotlin.math.sin(a.toDouble()).toFloat(), rayPaint)
        }
        skyFill.color = lit; skyFill.alpha = 225; c.drawCircle(cx, cy, r, skyFill)
        skyFill.color = core; skyFill.alpha = 200; c.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.5f, skyFill)
        val disc = Path(); disc.addCircle(cx, cy, r, Path.Direction.CW)
        skyOutline.color = dark; Doodle.ink(c, disc, skyOutline, 0.6f * d)
        skyFill.alpha = 255
    }

    private fun cloudRich(c: Canvas, cx: Float, cy: Float, s: Float, w: Wobble, tint: Int) {
        val lit = lightenC(tint, 0.30f)
        val dark = darkenC(tint, 0.28f)
        val puff = Path()
        puff.moveTo(cx - s * 1.4f, cy)
        puff.quadTo(cx - s * 1.4f, cy - s * 0.7f, cx - s * 0.7f, cy - s * 0.75f)
        puff.quadTo(cx - s * 0.5f, cy - s * 1.15f, cx, cy - s * 1.0f)
        puff.quadTo(cx + s * 0.5f, cy - s * 1.2f, cx + s * 0.8f, cy - s * 0.72f)
        puff.quadTo(cx + s * 1.4f, cy - s * 0.7f, cx + s * 1.4f, cy)
        puff.close()
        c.save(); c.clipPath(puff)
        skyFill.color = lit; skyFill.alpha = 150; c.drawPath(puff, skyFill)
        skyFill.color = dark; skyFill.alpha = 85
        c.drawRect(cx - s * 1.6f, cy - s * 0.32f, cx + s * 1.6f, cy + s * 0.2f, skyFill)
        c.restore()
        skyOutline.color = dark; Doodle.ink(c, puff, skyOutline, 0.6f * d)
        skyFill.alpha = 255
    }
    private fun fill(c: Int, a: Int = DECOR_ALPHA) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = c; alpha = a
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; alpha = DECOR_ALPHA
    }

    fun setScene(s: Int) { scene = s; invalidate() }

    /** Мерцание: 0..1, у каждой звезды свой сдвиг фазы -> мигают вразнобой. */
    private fun twinkle(off: Float): Float {
        val ph = BoilClock.phase * 1.7f + off
        return 0.45f + 0.55f * (0.5f + 0.5f * sin(ph.toDouble()).toFloat())
    }

    /** Петля 0..1 по времени: облако проходит экран и заходит снова. */
    private fun loop(periodSec: Float, off: Float): Float {
        val t = (BoilClock.phase / periodSec + off)
        return t - kotlin.math.floor(t)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Сид от сцены И кадра кипения: контур "дышит", но в пределах кадра
        // стабилен - иначе рябило бы в глазах.
        val r = Wobble(9001L + scene * 733L + BoilClock.frame * 97L)
        when (scene) {
            HEADER -> drawHeader(canvas, w, h, r)
            NIGHT -> drawNight(canvas, w, h, r)
            DAY -> drawDay(canvas, w, h, r)
            EXPEDITION -> drawCamp(canvas, w, h, r, amber, amberBr)
            PROFILE -> drawPassport(canvas, w, h, r)
            STATS -> drawStats(canvas, w, h, r)
            TIMELINE -> drawTimeline(canvas, w, h, r)
            CALIBRATION -> drawCalibration(canvas, w, h, r)
            HISTORY -> drawHistory(canvas, w, h, r)
        }
    }

    /** Облака, плывущие по петле: ушло за правый край - вошло слева. */
    private fun drifting(c: Canvas, w: Float, h: Float, r: Wobble, color: Int,
                         specs: List<Triple<Float, Float, Float>>) {
        for ((yFrac, sizeFrac, period) in specs) {
            val margin = w * 0.25f
            val x = -margin + (w + 2 * margin) * loop(period, yFrac)
            cloudRich(c, x, h * yFrac, h * sizeFrac, r, color)
        }
    }

    private fun stars(c: Canvas, color: Int, pts: List<Triple<Float, Float, Float>>,
                      w: Float, h: Float, r: Wobble) {
        for ((i, p) in pts.withIndex()) {
            val (xf, yf, rf) = p
            val k = twinkle(i * 1.9f)
            val path = Path()
            Doodle.star(path, w * xf, h * yf, h * rf * k, r)
            Doodle.ink(c, path, stroke(color, 2f, (DECOR_ALPHA * k).toInt()), 0.8f * d)
        }
    }

    private fun drawHeader(c: Canvas, w: Float, h: Float, r: Wobble) {
        val base = h * 0.95f
        mountainsRich(c, w * 0.30f, base, w * 0.40f, h * 0.62f, r, violet)
        firRich(c, w * 0.06f, base, h * 0.52f, r)
        firRich(c, w * 0.14f, base, h * 0.40f, r)
        firRich(c, w * 0.72f, base, h * 0.45f, r)
        firRich(c, w * 0.78f, base, h * 0.34f, r)
        drifting(c, w, h, r, violet, listOf(
            Triple(0.22f, 0.13f, 26f), Triple(0.14f, 0.09f, 34f)))
        moonRich(c, w * 0.92f, h * 0.26f, h * 0.16f, r, amberBr)
        stars(c, blueBr, listOf(Triple(0.44f, 0.18f, 0.08f), Triple(0.86f, 0.70f, 0.06f)), w, h, r)
    }

    private fun drawNight(c: Canvas, w: Float, h: Float, r: Wobble) {
        moonRich(c, w * 0.80f, h * 0.16f, h * 0.11f, r, violetBr)
        stars(c, blueBr, listOf(Triple(0.68f, 0.09f, 0.045f), Triple(0.92f, 0.36f, 0.04f)), w, h, r)
        dotPaint.color = violetBr
        c.drawCircle(w * 0.70f, h * 0.30f, 1.8f * d, dotPaint)
    }

    /** Солнце СИЯЕТ: лучи мерно удлиняются и укорачиваются. */
    private fun drawDay(c: Canvas, w: Float, h: Float, r: Wobble) {
        val k = 0.9f + 0.18f * sin((BoilClock.phase * 1.3f).toDouble()).toFloat()
        sunRich(c, w * 0.82f, h * 0.15f, h * 0.06f * k, r, amber)
        firRich(c, w * 0.93f, h * 0.98f, h * 0.20f, r)
        dotPaint.color = amberBr
        c.drawCircle(w * 0.62f, h * 0.10f, 1.8f * d, dotPaint)
    }

    /** Лагерь: живой костёр (пламя дышит), палатка, лес, горы. */
    private fun drawCamp(c: Canvas, w: Float, h: Float, r: Wobble, tint: Int, tintBr: Int) {
        val base = h * 0.90f
        val tn = Path()
        Doodle.tent(tn, w * 0.62f, base, w * 0.13f, h * 0.42f, r)
        Doodle.ink(c, tn, stroke(violetBr, 2f), 0.8f * d)
        // Пламя живёт: высота гуляет двумя несинхронными волнами, поэтому
        // не выглядит механическим маятником.
        val ph = BoilClock.phase
        val flick = 1f + 0.30f * sin((ph * 5.1f).toDouble()).toFloat() +
                    0.16f * sin((ph * 8.7f + 1.3f).toDouble()).toFloat()
        val logs = Path(); val flame = Path()
        Doodle.fire(logs, flame, w * 0.72f, base, h * 0.09f * flick, r)
        Doodle.ink(c, logs, stroke(ember, 2f), 0.8f * d)
        Doodle.ink(c, flame, stroke(amber, 2f, (DECOR_ALPHA * (0.75f + 0.25f * flick)).toInt()), 0.8f * d)
        // искры над костром
        for (i in 0 until 3) {
            val t = loop(2.2f, i * 0.33f)
            val sx = w * 0.72f + (i - 1) * 5f * d
            val sy = base - h * 0.14f - t * h * 0.30f
            dotPaint.color = amberBr
            dotPaint.alpha = (DECOR_ALPHA * (1f - t)).toInt()
            c.drawCircle(sx, sy, 1.6f * d, dotPaint)
        }
        dotPaint.alpha = DECOR_ALPHA
        firRich(c, w * 0.55f, base, h * 0.30f, r)
        firRich(c, w * 0.97f, base, h * 0.28f, r)
        mountainsRich(c, w * 0.78f, base, w * 0.18f, h * 0.36f, r, tint)
        val trail = Path()
        Doodle.line(trail, w * 0.66f, base + 3f * d, w * 0.74f, base - 2f * d, 1.2f, 6, r)
        Doodle.line(trail, w * 0.74f, base - 2f * d, w * 0.80f, base + 2f * d, 1.2f, 6, r)
        Doodle.ink(c, trail, stroke(tint, 1f), 0.8f * d)
        stars(c, tintBr, listOf(Triple(0.88f, 0.16f, 0.10f)), w, h, r)
    }

    /**
     * Профиль = ДОКУМЕНТ ПОД СКАНЕРОМ. Экран, где хранятся личные данные,
     * логично показать как паспорт: фото (нарочно неразборчивое - поверх
     * идёт штриховка), строки данных, штрих-код. Красный луч идёт сверху
     * вниз по петле и подсвечивает то, что пересекает.
     */
    private fun drawPassport(c: Canvas, w: Float, h: Float, r: Wobble) {
        val cx = w * 0.72f
        val cy = h * 0.50f
        val dw = w * 0.44f
        val dh = h * 0.76f

        val doc = Path(); val photo = Path(); val bars = Path()
        Doodle.passport(doc, photo, bars, cx, cy, dw, dh, r)
        Doodle.ink(c, doc, stroke(violet, 2f), 0.8f * d)
        Doodle.ink(c, photo, stroke(violetBr, 1.4f), 0.8f * d)
        c.drawPath(bars, fill(violetBr, 150))

        val corners = Path()
        Doodle.scanCorners(corners, cx, cy, dw, dh, 10f * d)
        Doodle.ink(c, corners, stroke(red, 2f, 190), 0.8f * d)

        // Луч сканера: идёт сверху вниз за 3 с, следом гаснущий шлейф.
        val t = loop(3f, 0f)
        val ly = cy - dh / 2f + dh * t
        val x0 = cx - dw / 2f - 2f * d
        val x1 = cx + dw / 2f + 2f * d
        for (g in 3 downTo 1) {
            beamPaint.color = red
            beamPaint.alpha = 40 / g
            beamPaint.strokeWidth = 1.5f * d
            c.drawLine(x0, ly - g * 2.5f * d, x1, ly - g * 2.5f * d, beamPaint)
        }
        beamPaint.color = red
        beamPaint.alpha = 230
        beamPaint.strokeWidth = 2f * d
        c.drawLine(x0, ly, x1, ly, beamPaint)

        stars(c, blueBr, listOf(Triple(0.14f, 0.24f, 0.13f), Triple(0.30f, 0.72f, 0.09f)), w, h, r)
        dotPaint.color = amberBr
        c.drawCircle(w * 0.08f, h * 0.60f, 2f * d, dotPaint)
    }

    /** Статистика: острые пики и река - синий оттенок. */
    private fun drawStats(c: Canvas, w: Float, h: Float, r: Wobble) {
        val base = h * 0.92f
        mountainsRich(c, w * 0.46f, base, w * 0.46f, h * 0.72f, r, blue)
        val rv = Path()
        Doodle.river(rv, w * 0.05f, h * 0.72f, w * 0.60f, base, r)
        Doodle.ink(c, rv, stroke(blueBr, 2f), 0.8f * d)
        firRich(c, w * 0.14f, base, h * 0.36f, r)
        firRich(c, w * 0.90f, base, h * 0.42f, r)
        drifting(c, w, h, r, violet, listOf(Triple(0.16f, 0.10f, 30f)))
        stars(c, blueBr, listOf(Triple(0.32f, 0.20f, 0.09f), Triple(0.70f, 0.12f, 0.06f)), w, h, r)
    }

    /** Timeline: солнце сияет, облака плывут, указатель на распутье. */
    private fun drawTimeline(c: Canvas, w: Float, h: Float, r: Wobble) {
        val base = h * 0.92f
        val k = 0.9f + 0.2f * sin((BoilClock.phase * 1.1f).toDouble()).toFloat()
        sunRich(c, w * 0.60f, h * 0.26f, h * 0.13f * k, r, amber)
        drifting(c, w, h, r, violet, listOf(
            Triple(0.20f, 0.12f, 22f), Triple(0.34f, 0.09f, 31f)))
        val sp = Path()
        Doodle.signpost(sp, w * 0.86f, base, h * 0.50f, r)
        Doodle.ink(c, sp, stroke(amberBr, 2f), 0.8f * d)
        firRich(c, w * 0.10f, base, h * 0.48f, r)
        firRich(c, w * 0.20f, base, h * 0.36f, r)
        firRich(c, w * 0.72f, base, h * 0.40f, r)
        stars(c, amberBr, listOf(Triple(0.44f, 0.14f, 0.07f)), w, h, r)
    }

    /** Калибровка: точность - это механизм. Шестерни КРУТЯТСЯ, песок сыплется. */
    private fun drawCalibration(c: Canvas, w: Float, h: Float, r: Wobble) {
        val ph = BoilClock.phase
        val g1 = Path()
        Doodle.gear(g1, w * 0.16f, h * 0.46f, h * 0.30f, 8, ph * 22f, r)
        Doodle.ink(c, g1, stroke(violet, 2f), 0.8f * d)
        // Вторая шестерня крутится В ОБРАТНУЮ сторону и быстрее - так и
        // работает зубчатая передача: меньше колесо - выше обороты.
        val g2 = Path()
        Doodle.gear(g2, w * 0.34f, h * 0.72f, h * 0.19f, 6, -ph * 33f, r)
        Doodle.ink(c, g2, stroke(violetBr, 2f), 0.8f * d)
        // Песок пересыпается за 8 с и переворачивается заново.
        val sandFill = 1f - loop(8f, 0f)
        val frame = Path(); val sand = Path()
        Doodle.hourglass(frame, sand, w * 0.62f, h * 0.50f, h * 0.34f, h * 0.62f, sandFill, r)
        c.drawPath(sand, fill(amber, 90))
        Doodle.ink(c, frame, stroke(amberBr, 2f), 0.8f * d)
        stars(c, blueBr, listOf(Triple(0.86f, 0.24f, 0.11f), Triple(0.94f, 0.66f, 0.07f)), w, h, r)
        dotPaint.color = violetBr
        c.drawCircle(w * 0.48f, h * 0.20f, 1.8f * d, dotPaint)
    }

    /** История: стопка тетрадей - нейтральный серый, экран архивный. */
    private fun drawHistory(c: Canvas, w: Float, h: Float, r: Wobble) {
        val nb = Path()
        Doodle.notebook(nb, w * 0.18f, h * 0.52f, w * 0.16f, h * 0.60f, r)
        Doodle.notebook(nb, w * 0.40f, h * 0.56f, w * 0.14f, h * 0.50f, r)
        Doodle.ink(c, nb, stroke(gray, 2f), 0.8f * d)
        stars(c, gray, listOf(Triple(0.70f, 0.30f, 0.10f), Triple(0.88f, 0.62f, 0.07f)), w, h, r)
        drifting(c, w, h, r, gray, listOf(Triple(0.20f, 0.10f, 38f)))
    }
}
