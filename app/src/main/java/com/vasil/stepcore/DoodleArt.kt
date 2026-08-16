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
    /**
     * Снимок подписчиков для обхода.
     *
     * Обход шёл по `ArrayList(listeners)` - копия всего списка двадцать
     * раз в секунду, то есть ровно тот мусор, который мы вычищали из
     * отрисовки, только в самом сердце механизма. Копия была нужна не
     * зря: подписчик вправе отписаться прямо в своём обработчике, а
     * правка набора во время обхода роняет итератор.
     *
     * Снимок решает обе задачи: он пересобирается ТОЛЬКО когда набор
     * изменился, а обход всегда идёт по массиву, который никто не трогает.
     */
    private var snapshot: Array<() -> Unit> = emptyArray()
    private var snapshotDirty = true
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
            if (snapshotDirty) {
                snapshot = listeners.toTypedArray()
                snapshotDirty = false
            }
            val snap = snapshot
            for (i in snap.indices) snap[i]()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun restartIfNeeded() {
        handler.removeCallbacks(tick)
        if (running) handler.postDelayed(tick, TICK_MS)
    }

    fun register(l: () -> Unit) {
        if (listeners.add(l)) snapshotDirty = true
        restartIfNeeded()
    }

    fun unregister(l: () -> Unit) {
        if (listeners.remove(l)) snapshotDirty = true
        restartIfNeeded()
    }

    /** Сколько подписчиков сейчас живо. Нужно проверке на утечку. */
    fun listenerCount(): Int = listeners.size

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

    /**
     * Вытесанная плита: у каждого угла свой радиус и свой тип обработки
     * (0 - скруглённый, 1 - срез фаской, 2 - зарубка). Порядок углов:
     * левый-верх, правый-верх, правый-низ, левый-низ. Даёт плитам разную
     * геометрию силуэта при общем стиле линии.
     */
    fun carved(p: Path, x: Float, y: Float, ww: Float, hh: Float,
               rs: FloatArray, st: IntArray, jit: Float, w: Wobble) {
        val r0 = rs[0]; val r1 = rs[1]; val r2 = rs[2]; val r3 = rs[3]
        line(p, x + r0, y, x + ww - r1, y, jit, 16, w)
        line(p, x + ww, y + r1, x + ww, y + hh - r2, jit, 16, w)
        line(p, x + ww - r2, y + hh, x + r3, y + hh, jit, 16, w)
        line(p, x, y + hh - r3, x, y + r0, jit, 16, w)
        corner(p, st[0], x, y + r0, x + r0, y, x, y, r0, 180f, 270f, jit, w)
        corner(p, st[1], x + ww - r1, y, x + ww, y + r1, x + ww, y, r1, 270f, 360f, jit, w)
        corner(p, st[2], x + ww, y + hh - r2, x + ww - r2, y + hh, x + ww, y + hh, r2, 0f, 90f, jit, w)
        corner(p, st[3], x + r3, y + hh, x, y + hh - r3, x, y + hh, r3, 90f, 180f, jit, w)
    }

    private fun corner(p: Path, style: Int, sx: Float, sy: Float, ex: Float, ey: Float,
                       cx: Float, cy: Float, r: Float, a0: Float, a1: Float,
                       jit: Float, w: Wobble) {
        when (style) {
            1 -> line(p, sx, sy, ex, ey, jit, 6, w)
            2 -> {
                val m1x = sx + (cx - sx) * 0.55f; val m1y = sy + (cy - sy) * 0.55f
                val m2x = ex + (cx - ex) * 0.55f; val m2y = ey + (cy - ey) * 0.55f
                line(p, sx, sy, m1x, m1y, jit, 4, w)
                line(p, m1x, m1y, m2x, m2y, jit, 4, w)
                line(p, m2x, m2y, ex, ey, jit, 4, w)
            }
            else -> {
                val ax = if (a0 == 180f || a0 == 90f) cx + r else cx - r
                val ay = if (a0 == 180f || a0 == 270f) cy + r else cy - r
                arc(p, ax, ay, r, a0, a1, jit, w)
            }
        }
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
    private val material: Int = MAT_ROCK,
    private val riftMode: Int = RIFT_DEFAULT,
) : Drawable() {

    companion object {
        // Режим трещины. По умолчанию - прежний случайный узор снизу вверх.
        // Смысловые режимы держатся у правого края (текст по центру не
        // задевается) и показывают направление.
        const val RIFT_DEFAULT = 0
        const val RIFT_UP = 1
        const val RIFT_FLAT = 2
        const val RIFT_DOWN = 3
        const val RIFT_NONE = 4
        const val MAT_ROCK = 0       // тяжёлый двойной кант, глухой камень
        const val MAT_LIGHTNING = 1  // свечение + яркое ядро, пульс
        const val MAT_ROPE = 2       // две пряди + насечки витков
        const val MAT_FIRE = 3       // тёплое дрожащее свечение
        const val MAT_ICE = 4        // резкий кант + холодный отблеск
        const val MAT_MECH = 5       // сегментированный контур (пунктир)
        /** Каждый какой такт обновляется мелкая плита. */
        private const val SLOW_EVERY = 4
        /**
         * Сколько ждать без отрисовки, прежде чем считать плиту ушедшей.
         * 1200 мс с запасом больше самого редкого случая - мелкой плиты,
         * которую рисуют раз в четыре такта (200 мс).
         */
        private const val DEAD_MS = 1200L
        /**
         * Зажигание плиты, мс. Было 420 при режущем клипе; со завесой
         * движение читается быстрее, а ждать нечего - содержимое видно
         * сразу, поэтому короче и честнее.
         */
        private const val REVEAL_MS = 300f
        /** Период пробега огонька по канту, с. */
        private const val SPARK_PERIOD = 6.5f
        /** Какую долю периода огонёк бежит (остальное - покой). */
        private const val SPARK_RUN = 0.34f
    }

    private val texFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val texLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    // Замкнутое тело плиты: контур рисуется кусками (стороны/углы отдельно)
    // и как область обрезки почти ничего не оставляет. Тело нужно, чтобы
    // заливка и фактура ложились на всю площадь.
    private val bodyPath = Path()
    private var pw = 0f
    private var phh = 0f
    private val matPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val ropeDash = android.graphics.DashPathEffect(
        floatArrayOf(2.5f * density, 5f * density), 0f)
    private val mechDash = android.graphics.DashPathEffect(
        floatArrayOf(9f * density, 4.5f * density), 0f)

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
    // ГЛУХОЕ ОСНОВАНИЕ. Заливка плиты идёт градиентом, а поверх неё живут
    // полупрозрачные слои (фактура, пыль, свет). Пока под ними ничего нет,
    // сквозь плиту просвечивает то, что за окном, и текст читается плохо -
    // особенно у окна-объяснения, под которым лежит пёстрый главный экран.
    // Основание рисуется первым и всегда непрозрачно.
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(255,
            (android.graphics.Color.red(fillColor) * 0.55f).toInt(),
            (android.graphics.Color.green(fillColor) * 0.55f).toInt(),
            (android.graphics.Color.blue(fillColor) * 0.55f).toInt())
    }
    // UI-2: резной бевел. Светлый кант ловит свет сверху-слева, тёмная
    // грань уводит вглубь снизу-справа - плита читается как вырезанная.
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = lighten(strokeColor, 0.55f)
        alpha = 175
    }
    private val shPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        alpha = 195
    }
    private val d = density
    private val rivFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF6B7488.toInt() }
    private val rivRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF07090D.toInt() }
    private val rivHi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFAEB8C8.toInt(); alpha = 200 }
    private val rivets = FloatArray(8)
    private var bigEnough = false
    // Геометрия силуэта: свои радиусы и типы углов у каждой плиты (от сида).
    private val cornerR = FloatArray(4)
    private val cornerStyle = IntArray(4)
    // Пыль: у каждой плиты свой узор (сид), стабильный между кадрами.
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /**
     * Пылинок на плите. Было десять: на карточке в половину экрана
     * разница между шестью и десятью не читается, а стоит она четырёх
     * лишних окружностей в кадре на КАЖДОЙ плите.
     */
    private val DUST_N = 6
    private val dustX = FloatArray(DUST_N)
    private val dustPhase = FloatArray(DUST_N)
    private val dustSpeed = FloatArray(DUST_N)
    private val dustSize = FloatArray(DUST_N)
    private var dustH = 0f
    // Разлом: трещина в камне, светится цветом смысла этой плиты.
    private val riftPath = Path()
    private var riftSkip = false
    private val riftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    // Лиана: вьётся по краю плиты, приглушённая зелень (не спорит со смыслом).
    private val vinePath = Path()
    private val leafPath = Path()
    private val vinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = 0xFF2F8A6A.toInt(); alpha = 165
    }
    private val leafFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF3FAE86.toInt(); alpha = 150
    }
    private val leafEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF14503B.toInt(); alpha = 165
    }
    /** Готовые варианты контура - по одному на кадр "кипения". */
    private val frames = Array(BoilClock.FRAMES) { Path() }
    private var builtFor = Rect()
    /**
     * ПОДПИСКА ЖИВЁТ РОВНО СТОЛЬКО, СКОЛЬКО НАС РИСУЮТ.
     *
     * С v398 плита подписывается при отрисовке - это починило мёртвый
     * холодный старт, но открыло дыру с другой стороны: отписка осталась
     * только в setVisible(false). Экран, который просто уничтожили,
     * setVisible не получает. Значит подписка оставалась в общем наборе
     * навсегда, а вместе с ней жила цепочка «плита -> вьюха -> экран»:
     * каждый заход в Историю, Профиль или Статистику добавлял в память
     * ещё один мёртвый экран и ещё десяток холостых вызовов в такте.
     * За долгую сессию это и деньги за батарею, и растущая память.
     *
     * Чинить симметрично (отписка в onDetachedFromWindow) нельзя: у
     * Drawable нет такого события. Поэтому подписка САМОПРОВЕРЯЕМАЯ: если
     * нас не рисовали дольше DEAD_MS, значит нас больше нет на экране -
     * подписка снимается сама. Вернут на экран - draw подпишет заново,
     * этот путь уже работает с v398.
     */
    // ЛЯМБДА НЕ МОЖЕТ ССЫЛАТЬСЯ НА САМУ СЕБЯ В СВОЁМ ОБЪЯВЛЕНИИ.
    // Тело `onTick` снимало подписку через `BoilClock.unregister(onTick)`,
    // то есть читало переменную, которая в этот момент ещё не
    // инициализирована. Kotlin отказывается компилировать: «Variable
    // 'onTick' must be initialized». Лечится выносом тела в ФУНКЦИЮ:
    // её тело выполняется позже объявления поля, и ссылка на onTick там
    // законна. Лямбда остаётся тонкой оболочкой.
    private val onTick: () -> Unit = { tickOnce() }

    private fun tickOnce() {
        if (android.os.SystemClock.uptimeMillis() - lastDrawMs > DEAD_MS) {
            BoilClock.unregister(onTick); subscribed = false
            return
        }
        if (needsFullRate()) {
            invalidateSelf()
        } else {
            tickSkip = (tickSkip + 1) % SLOW_EVERY
            if (tickSkip == 0) invalidateSelf()
        }
    }
    /** Когда нас рисовали в последний раз. Ноль - ещё ни разу. */
    private var lastDrawMs = 0L

    /** Идёт ли на плите событие, требующее полной частоты кадров. */
    private fun needsFullRate(): Boolean {
        if (bigEnough) return true
        if (bornAt == 0L) return true
        if (android.os.SystemClock.uptimeMillis() - bornAt < REVEAL_MS + igniteDelay) return true
        if (pathLen <= 0f) return false
        var t = BoilClock.phase / SPARK_PERIOD + sparkOffset
        t -= Math.floor(t.toDouble()).toFloat()
        return t <= SPARK_RUN
    }
    private var subscribed = false

    // ---------- Оживление рамки ----------
    // Две вещи, которых плите не хватало, чтобы перестать быть наклейкой:
    //
    // 1) ПОЯВЛЕНИЕ. Плита не возникает целиком - она НАЛИВАЕТСЯ снизу
    //    вверх, и по кромке заливки идёт светящийся фронт. Тот же приём,
    //    что у горы прогресса, но здесь он длится 420 мс и заканчивается:
    //    постоянная заливка мешала бы читать.
    // 2) ЖИЗНЬ. По канту раз в несколько секунд пробегает огонёк -
    //    короткий отрезок контура, взятый через PathMeasure. Он идёт по
    //    настоящей кромке, со всеми её кривыми, а не по прямоугольнику.
    //
    // Фаза огонька смещена сидом плиты: карточки на одном экране не
    // вспыхивают строем, а перекликаются.
    private val pathMeasure = android.graphics.PathMeasure()
    private val sparkPath = Path()

    // ---------- Кадр без мусора ----------
    // Отрисовка фактуры выделяла в КАЖДОМ кадре: FloatArray(3) на точку
    // периметра, Path на огонёк, Path на каждую жилу молнии, массив
    // массивов на углы инея. При двадцати кадрах в секунду и полутора
    // десятках плит на экране это сотни объектов в секунду - сборщик
    // мусора просыпается и даёт ровно те подёргивания, которые читаются
    // как фриз. Отрисовка обязана быть без единой аллокации: все буферы
    // живут в полях и переиспользуются.
    private val texPt = FloatArray(3)
    private val texPath = Path()
    private val texCorner = FloatArray(2)

    // ---------- Экономия кадров ----------
    // Не каждой плите нужно 20 кадров в секунду. Мелкая фишка живёт
    // только фактурой: её движение на площади в палец не читается вовсе,
    // а перерисовка стоит столько же, сколько у крупной карточки, потому
    // что тянет за собой перерисовку всей вьюхи. Мелкие обновляются
    // каждый четвёртый такт (5 к/с), крупные - каждый такт.
    // Наливание и пробег огонька идут на полной частоте всегда: это
    // короткие события, и рывок в них виден сразу.
    private var tickSkip = 0
    private var pathLen = 0f
    private var bornAt = 0L
    /** Задержка зажигания этой плиты, мс: разнобой вместо общего щелчка. */
    private val igniteDelay = (((seed * 40503L) ushr 27) % 140L)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** Тон завесы: тень собственного тона плиты, а не чёрная клякса. */
    private val veilColor = android.graphics.Color.argb(255,
        (android.graphics.Color.red(fillColor) * 0.25f).toInt(),
        (android.graphics.Color.green(fillColor) * 0.25f).toInt(),
        (android.graphics.Color.blue(fillColor) * 0.25f).toInt())
    private val sparkOffset = ((seed * 2654435761L) ushr 33).toFloat() % 1f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds == builtFor || bounds.isEmpty) return
        builtFor = Rect(bounds)
        val inset = 2f * d
        // Каждый кадр - СВОЙ сид: контур тот же, дрожь другая. Вся
        // математика делается здесь, один раз, а не на каждом кадре.
        val gw = Wobble(seed * 911L + 5L)
        for (k in 0 until 4) {
            cornerR[k] = (9f + 9f * ((gw.j(1f) + 1f) * 0.5f)) * d
            val u = (gw.j(1f) + 1f) * 0.5f
            cornerStyle[k] = if (u < 0.45f) 0 else if (u < 0.78f) 1 else 2
        }
        for (i in frames.indices) {
            val w = Wobble(seed * 31L + i)
            frames[i].reset()
            Doodle.carved(frames[i], inset, inset,
                bounds.width() - 2 * inset, bounds.height() - 2 * inset,
                cornerR, cornerStyle, 1.5f * d, w)
        }

        // Объём плиты: диагональная растушёвка СВОЕГО тона (свет сверху-слева).
        if (fillColor != Color.TRANSPARENT) {
            fillPaint.shader = android.graphics.LinearGradient(
                0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(),
                intArrayOf(lighten(fillColor, 0.18f), fillColor, darken(fillColor, 0.30f)),
                floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        // Гвозди по углам - только на крупных плитах (чипы остаются чистыми).
        bigEnough = bounds.width() > 130 * d && bounds.height() > 46 * d
        val ri = 12f * d
        rivets[0] = inset + ri; rivets[1] = inset + ri
        rivets[2] = bounds.width() - inset - ri; rivets[3] = inset + ri
        rivets[4] = inset + ri; rivets[5] = bounds.height() - inset - ri
        rivets[6] = bounds.width() - inset - ri; rivets[7] = bounds.height() - inset - ri

        pathMeasure.setPath(frames[0], false)
        pathLen = pathMeasure.length
        bornAt = 0L

        pw = bounds.width().toFloat(); phh = bounds.height().toFloat()
        bodyPath.reset()
        bodyPath.addRoundRect(
            android.graphics.RectF(inset, inset, pw - inset, phh - inset),
            floatArrayOf(cornerR[0], cornerR[0], cornerR[1], cornerR[1],
                cornerR[2], cornerR[2], cornerR[3], cornerR[3]),
            Path.Direction.CW)
        dustH = bounds.height().toFloat()
        val dw = Wobble(seed * 77L + 13L)
        for (i in 0 until DUST_N) {
            dustX[i] = inset + (bounds.width() - 2 * inset) * (0.06f + 0.88f * ((dw.j(1f) + 1f) * 0.5f))
            dustPhase[i] = ((dw.j(1f) + 1f) * 0.5f)
            dustSpeed[i] = 0.020f + 0.030f * ((dw.j(1f) + 1f) * 0.5f)
            dustSize[i] = (1.3f + 1.2f * ((dw.j(1f) + 1f) * 0.5f)) * d
        }

        // Трещина: от нижнего края вверх ломаной, узор свой у каждой плиты.
        val rw = Wobble(seed * 131L + 7L)
        val hgt = bounds.height().toFloat(); val wid = bounds.width().toFloat()
        if (riftMode != RIFT_DEFAULT) {
            // Смысловая трещина: у правого края, короткая, читается как знак.
            riftSkip = riftMode == RIFT_NONE
            val ex = wid * 0.90f
            val ln = Math.min(hgt * 0.55f, 46f * d)
            val my = hgt * 0.5f
            riftPath.reset()
            when (riftMode) {
                RIFT_UP -> {
                    riftPath.moveTo(ex - ln * 0.45f, my + ln * 0.42f)
                    riftPath.lineTo(ex - ln * 0.08f, my + ln * 0.04f)
                    riftPath.lineTo(ex + ln * 0.22f, my - ln * 0.32f)
                }
                RIFT_DOWN -> {
                    riftPath.moveTo(ex - ln * 0.45f, my - ln * 0.42f)
                    riftPath.lineTo(ex - ln * 0.08f, my - ln * 0.04f)
                    riftPath.lineTo(ex + ln * 0.22f, my + ln * 0.32f)
                }
                else -> {
                    riftPath.moveTo(ex - ln * 0.45f, my - ln * 0.05f)
                    riftPath.lineTo(ex - ln * 0.05f, my + ln * 0.05f)
                    riftPath.lineTo(ex + ln * 0.30f, my - ln * 0.02f)
                }
            }
        } else {
        val startX = wid * (0.55f + 0.30f * ((rw.j(1f) + 1f) * 0.5f))
        // На высоких панелях (списки, Timeline) трещина растягивалась на
        // полэкрана - там её не рисуем; иначе ограничиваем длину.
        riftSkip = hgt > 230f * d
        val riftLen = Math.min(hgt * 0.62f, 74f * d)
        riftPath.reset()
        riftPath.moveTo(startX, hgt - inset)
        var rx = startX; var ry = hgt - inset
        val steps = 4
        for (k in 1..steps) {
            val ty = (hgt - inset) - riftLen * (k.toFloat() / steps)
            val tx = rx + wid * 0.05f * rw.j(1.6f)
            riftPath.lineTo(tx, ty)
            rx = tx; ry = ty
        }
        }

        // Лиана по вертикальному краю: сторона и изгибы - от сида плиты.
        val vw = Wobble(seed * 197L + 23L)
        val leftSide = vw.j(1f) < 0f
        val edgeX = if (leftSide) inset + 5f * d else wid - inset - 5f * d
        val dirIn = if (leftSide) 1f else -1f
        vinePath.reset(); leafPath.reset()
        vinePath.moveTo(edgeX, inset + 2f * d)
        val segs = 3
        var vy = inset + 2f * d
        val segH = (hgt - 2 * inset - 4f * d) / segs
        for (k in 1..segs) {
            val ny = vy + segH
            val bend = dirIn * (5f + 4f * ((vw.j(1f) + 1f) * 0.5f)) * d * (if (k % 2 == 0) -1f else 1f)
            vinePath.quadTo(edgeX + bend, vy + segH * 0.5f, edgeX, ny)
            // Лист у изгиба.
            val lx = edgeX + bend * 1.15f
            val ly = vy + segH * 0.5f
            val ls = 6.5f * d
            leafPath.moveTo(lx, ly)
            leafPath.quadTo(lx + dirIn * ls * 1.5f, ly - ls * 0.95f, lx + dirIn * ls * 2.1f, ly - ls * 0.15f)
            leafPath.quadTo(lx + dirIn * ls * 1.2f, ly + ls * 0.55f, lx, ly)
            leafPath.close()
            vy = ny
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
            // Плита показалась заново - наливается заново. Иначе окно,
            // открытое второй раз, просто появлялось бы.
            bornAt = 0L
            BoilClock.register(onTick); subscribed = true
        } else if (!visible && subscribed) {
            BoilClock.unregister(onTick); subscribed = false
        }
        return changed
    }

    override fun draw(canvas: Canvas) {
        // ПОДПИСКА В ВОРОНКЕ, А НЕ В СОБЫТИИ.
        //
        // Подписка стояла только в setVisible. Но Drawable создаётся уже
        // видимым (isVisible = true по умолчанию), и при первом показе
        // экрана система НЕ зовёт setVisible - звать нечего, состояние не
        // менялось. Поэтому на холодном старте плиты не подписывались
        // вовсе и стояли мёртвыми; стоило уйти на другую вкладку и
        // вернуться - видимость окна дёргалась false->true, setVisible
        // наконец приходил, и всё оживало. Ровно этот симптом и был.
        //
        // Отрисовка - воронка, через которую проходит ЛЮБАЯ живая плита:
        // если нас рисуют, мы обязаны быть подписаны. Отписка остаётся в
        // setVisible(false), так что фон по-прежнему не тикает.
        lastDrawMs = android.os.SystemClock.uptimeMillis()
        if (!subscribed && isVisible) {
            BoilClock.register(onTick); subscribed = true
        }
        // Контур СТАТИЧЕН: смена вариантов кадра давала рывки, которые на
        // фоне плавных сцен читались как фриз. Живут только свет и частицы.
        val p = frames[0]

        // Наливание снизу вверх. Замедление к концу (easeOut): резкая
        // остановка читалась бы как обрыв кадра.
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (bornAt == 0L) bornAt = nowMs
        val rk = ((nowMs - bornAt - igniteDelay).toFloat() / REVEAL_MS).coerceIn(0f, 1f)
        val grow = 1f - (1f - rk) * (1f - rk)
        val filling = rk < 1f
        val off = 1.7f * d
        // тёмная грань снизу-справа (глубина), затем светлый кант сверху-слева
        canvas.save(); canvas.translate(off, off); canvas.drawPath(p, shPaint); canvas.restore()
        canvas.save(); canvas.translate(-off, -off); canvas.drawPath(p, hiPaint); canvas.restore()
        if (fillColor != Color.TRANSPARENT) {
            canvas.drawPath(bodyPath, basePaint)
            canvas.drawPath(bodyPath, fillPaint)
        }
        if (bigEnough) {
            canvas.save(); canvas.clipPath(bodyPath)
            drawTexture(canvas)
            canvas.restore()
        }
        drawContour(canvas, p)
        // Кованые гвозди по углам (только крупные плиты).
        if (bigEnough) {
            val rr = 3.6f * d
            for (k in 0 until 4) {
                val cx = rivets[k * 2]; val cy = rivets[k * 2 + 1]
                canvas.drawCircle(cx, cy, rr, rivRing)
                canvas.drawCircle(cx, cy, rr * 0.82f, rivFill)
                canvas.drawCircle(cx - rr * 0.3f, cy - rr * 0.3f, rr * 0.3f, rivHi)
            }
            // Лиана: лёгкое покачивание на ветру (общий такт).
            val vp = BoilClock.phase
            val swayV = 1.6f * d * kotlin.math.sin((vp * 0.55f).toDouble()).toFloat()
            canvas.save(); canvas.translate(swayV, 0f)
            vinePaint.strokeWidth = 2.6f * d
            canvas.drawPath(vinePath, vinePaint)
            canvas.drawPath(leafPath, leafFill)
            leafEdge.strokeWidth = 1.1f * d
            canvas.drawPath(leafPath, leafEdge)
            canvas.restore()

            // Разлом: слои свечения + ядро; свет дышит, а оттенок циклично
            // переливается (тон плиты <-> его высветленный вариант).
            val rp = BoilClock.phase
            val breath = 0.6f + 0.4f * kotlin.math.sin((rp * 0.9f).toDouble()).toFloat()
            val hue = 0.5f + 0.5f * kotlin.math.sin((rp * 0.35f).toDouble()).toFloat()
            val riftTone = lighten(strokeColor, 0.10f + 0.35f * hue)
            if (!riftSkip) {
            riftPaint.color = riftTone
            riftPaint.strokeWidth = 5.5f * d; riftPaint.alpha = (70f * breath).toInt().coerceIn(0, 255)
            canvas.drawPath(riftPath, riftPaint)
            riftPaint.strokeWidth = 2.8f * d; riftPaint.alpha = (130f * breath).toInt().coerceIn(0, 255)
            canvas.drawPath(riftPath, riftPaint)
            riftPaint.color = lighten(riftTone, 0.60f)
            riftPaint.strokeWidth = 1.2f * d; riftPaint.alpha = (170f + 60f * breath).toInt().coerceIn(0, 255)
            canvas.drawPath(riftPath, riftPaint)
            }

            // Пыль: медленно всплывает и по кругу возвращается вниз.
            val ph = BoilClock.phase
            for (i in 0 until DUST_N) {
                var t = dustPhase[i] + ph * dustSpeed[i]
                t -= Math.floor(t.toDouble()).toFloat()
                val y = dustH * (1f - t)
                val fade = if (t < 0.15f) t / 0.15f else if (t > 0.8f) (1f - t) / 0.2f else 1f
                val tw = 0.55f + 0.45f * kotlin.math.sin((ph * 1.7f + i * 2.3f).toDouble()).toFloat()
                dustPaint.color = lighten(strokeColor, 0.65f)
                dustPaint.alpha = (215f * fade * tw * 0.7f).toInt().coerceIn(0, 255)
                val sway = 2.5f * d * kotlin.math.sin((ph * 0.9f + i).toDouble()).toFloat()
                canvas.drawCircle(dustX[i] + sway, y, dustSize[i], dustPaint)
            }
        }

        if (filling) {
            // Завеса: та часть, куда свет ещё не дошёл, притенена, но
            // ЧИТАЕМА. Тень уходит вместе с фронтом и целиком исчезает.
            veilPaint.color = veilColor
            veilPaint.alpha = (150f * (1f - rk)).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.clipPath(bodyPath)
            canvas.drawRect(0f, 0f, pw, phh * (1f - grow), veilPaint)
            canvas.restore()
            // Светящийся фронт заливки: широкое гало и тонкое ядро.
            val fy = phh * (1f - grow)
            val fade = if (rk > 0.85f) (1f - rk) / 0.15f else 1f
            riftPaint.color = lighten(strokeColor, 0.30f)
            riftPaint.strokeWidth = 7f * d
            riftPaint.alpha = (90f * fade).toInt().coerceIn(0, 255)
            canvas.drawLine(3f * d, fy, pw - 3f * d, fy, riftPaint)
            riftPaint.color = lighten(strokeColor, 0.85f)
            riftPaint.strokeWidth = 1.8f * d
            riftPaint.alpha = (225f * fade).toInt().coerceIn(0, 255)
            canvas.drawLine(3f * d, fy, pw - 3f * d, fy, riftPaint)
            // Искры срываются с фронта - заливка не безжизненная полоса.
            dustPaint.color = lighten(strokeColor, 0.70f)
            for (i in 0 until 5) {
                val sx = pw * (0.12f + 0.19f * i)
                val lift = (12f + 9f * i % 7) * d * grow
                dustPaint.alpha = (200f * fade * (1f - grow * 0.5f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(sx, fy - lift, (1.1f + 0.5f * (i % 3)) * d, dustPaint)
            }
        }

        drawSpark(canvas)
    }

    /**
     * Огонёк по канту: короткий отрезок настоящего контура, взятый через
     * PathMeasure. Бежит не всё время - треть цикла, остальное кант живёт
     * своим материалом. Постоянный бег превратил бы карточку в вывеску.
     */
    private fun drawSpark(canvas: Canvas) {
        if (pathLen <= 0f) return
        var t = (BoilClock.phase / SPARK_PERIOD + sparkOffset)
        t -= Math.floor(t.toDouble()).toFloat()
        if (t > SPARK_RUN) return
        val k = t / SPARK_RUN
        val head = k * pathLen
        val tail = 0.16f * pathLen
        // Голова ярче хвоста: у бегущего света есть направление.
        val fade = kotlin.math.sin((k * Math.PI).toFloat())
        sparkPath.reset()
        pathMeasure.getSegment(Math.max(0f, head - tail), head, sparkPath, true)
        matPaint.pathEffect = null
        matPaint.color = lighten(strokeColor, 0.35f)
        matPaint.strokeWidth = 7f * d
        matPaint.alpha = (85f * fade).toInt().coerceIn(0, 255)
        canvas.drawPath(sparkPath, matPaint)
        matPaint.color = lighten(strokeColor, 0.90f)
        matPaint.strokeWidth = 1.9f * d
        matPaint.alpha = (235f * fade).toInt().coerceIn(0, 255)
        canvas.drawPath(sparkPath, matPaint)
    }

    /**
     * Точка на кромке плиты по доле обхода t (0..1) - акценты материала
     * садятся на край, а не на середину, чтобы не спорить с текстом.
     * Возвращает x, y и угол касательной в градусах.
     */
    private fun perim(t: Float, out: FloatArray) {
        val ins = 6f * d
        val ww = pw - 2 * ins; val hh = phh - 2 * ins
        val per = 2 * (ww + hh)
        var l = ((t - Math.floor(t.toDouble()).toFloat()) * per)
        if (l < ww) { out[0] = ins + l; out[1] = ins; out[2] = 0f; return }
        l -= ww
        if (l < hh) { out[0] = ins + ww; out[1] = ins + l; out[2] = 90f; return }
        l -= hh
        if (l < ww) { out[0] = ins + ww - l; out[1] = ins + hh; out[2] = 180f; return }
        l -= ww
        out[0] = ins; out[1] = ins + hh - l; out[2] = 270f
    }

    /** Фактура материала по площади плиты: лаконично, у кромок и в углах. */
    private fun drawTexture(canvas: Canvas) {
        val ph = BoilClock.phase
        val pt = texPt
        when (material) {
            MAT_ROPE -> {
                // Узлы с перевязкой: сидят на кромке и слегка «дышат».
                val ts = floatArrayOf(0.18f, 0.47f, 0.79f)
                for ((i, tv) in ts.withIndex()) {
                    perim(tv, pt)
                    val br = 1f + 0.06f * kotlin.math.sin((ph * 1.6f + i).toDouble()).toFloat()
                    canvas.save()
                    canvas.translate(pt[0], pt[1]); canvas.rotate(pt[2])
                    texFill.color = darken(strokeColor, 0.30f); texFill.alpha = 235
                    canvas.drawOval(-7f * d * br, -4.8f * d * br, 7f * d * br, 4.8f * d * br, texFill)
                    texLine.color = lighten(strokeColor, 0.35f); texLine.alpha = 220
                    texLine.strokeWidth = 1.8f * d
                    canvas.drawLine(-3f * d, -4.8f * d * br, -3f * d, 4.8f * d * br, texLine)
                    canvas.drawLine(2.2f * d, -4.8f * d * br, 2.2f * d, 4.8f * d * br, texLine)
                    canvas.restore()
                }
            }
            MAT_FIRE -> {
                // Огонёк у нижней кромки и поднимающийся дымок.
                val fx = pw * 0.13f; val fy = phh - 8f * d
                val fl = 0.6f + 0.4f * kotlin.math.sin((ph * 6.5f).toDouble()).toFloat()
                val flame = texPath
                flame.reset()
                flame.moveTo(fx - 4.6f * d, fy)
                flame.quadTo(fx - 5.2f * d, fy - 7f * d * fl, fx, fy - 14f * d * fl)
                flame.quadTo(fx + 5.2f * d, fy - 7f * d * fl, fx + 4.6f * d, fy)
                flame.close()
                texFill.color = strokeColor; texFill.alpha = 235; canvas.drawPath(flame, texFill)
                texFill.color = lighten(strokeColor, 0.55f); texFill.alpha = 210
                canvas.drawCircle(fx, fy - 3.4f * d, 2.6f * d * fl, texFill)
                for (k in 0 until 4) {
                    var g = (ph * 0.42f + k * 0.25f) % 1f
                    if (g < 0f) g += 1f
                    val sy = fy - 10f * d - g * (phh * 0.42f)
                    val sx = fx + 6f * d * kotlin.math.sin((g * 3.4f + k).toDouble()).toFloat()
                    texFill.color = lighten(strokeColor, 0.20f)
                    texFill.alpha = (110f * (1f - g)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(sx, sy, (1.4f + 2.2f * g) * d, texFill)
                }
            }
            MAT_ICE -> {
                // Иней: кристаллы нарастают из углов, тихо мерцают.
                for (i in 0 until 2) {
                    val cc = texCorner
                    if (i == 0) { cc[0] = 10f * d; cc[1] = 10f * d }
                    else { cc[0] = pw - 10f * d; cc[1] = phh - 10f * d }
                    val tw = 0.55f + 0.45f * kotlin.math.sin((ph * 0.9f + i * 2.0f).toDouble()).toFloat()
                    texLine.color = lighten(strokeColor, 0.55f)
                    texLine.alpha = (130f + 105f * tw).toInt().coerceIn(0, 255)
                    texLine.strokeWidth = 1.7f * d
                    for (k in 0 until 6) {
                        val a2 = Math.toRadians((k * 60f + i * 20f).toDouble())
                        val ex = cc[0] + 11f * d * kotlin.math.cos(a2).toFloat()
                        val ey = cc[1] + 11f * d * kotlin.math.sin(a2).toFloat()
                        canvas.drawLine(cc[0], cc[1], ex, ey, texLine)
                        canvas.drawLine(ex, ey, ex - 2.4f * d * kotlin.math.cos(a2 + 0.7).toFloat(),
                            ey - 2.4f * d * kotlin.math.sin(a2 + 0.7).toFloat(), texLine)
                    }
                }
            }
            MAT_LIGHTNING -> {
                // Жилы: тонкие разряды идут из угла внутрь, пульсируют.
                val fl = 0.5f + 0.5f * kotlin.math.sin((ph * 4.2f).toDouble()).toFloat()
                texLine.color = lighten(strokeColor, 0.45f)
                texLine.alpha = (110f + 120f * fl).toInt().coerceIn(0, 255)
                texLine.strokeWidth = 1.9f * d
                for (i in 0 until 2) {
                    val vp = texPath
                    vp.reset()
                    val sx = if (i == 0) 9f * d else pw - 9f * d
                    val dir = if (i == 0) 1f else -1f
                    vp.moveTo(sx, 8f * d)
                    vp.lineTo(sx + dir * 9f * d, 15f * d)
                    vp.lineTo(sx + dir * 4f * d, 21f * d)
                    vp.lineTo(sx + dir * 13f * d, 29f * d)
                    canvas.drawPath(vp, texLine)
                }
            }
            MAT_MECH -> {
                // Болты по кромке и маленькая шестерня в углу.
                val ts = floatArrayOf(0.10f, 0.40f, 0.60f, 0.90f)
                for (tv in ts) {
                    perim(tv, pt)
                    texFill.color = darken(strokeColor, 0.35f); texFill.alpha = 230
                    canvas.drawCircle(pt[0], pt[1], 3.6f * d, texFill)
                    texLine.color = lighten(strokeColor, 0.30f); texLine.alpha = 230
                    texLine.strokeWidth = 1.5f * d
                    canvas.drawLine(pt[0] - 2.4f * d, pt[1], pt[0] + 2.4f * d, pt[1], texLine)
                }
                val gx = pw - 16f * d; val gy = phh - 15f * d; val gr = 9.5f * d
                texLine.color = lighten(strokeColor, 0.30f); texLine.alpha = 210
                texLine.strokeWidth = 2f * d
                canvas.drawCircle(gx, gy, gr * 0.55f, texLine)
                for (k in 0 until 8) {
                    val a2 = Math.toRadians((k * 45f + ph * 26f).toDouble())
                    canvas.drawLine(gx + gr * 0.62f * kotlin.math.cos(a2).toFloat(),
                        gy + gr * 0.62f * kotlin.math.sin(a2).toFloat(),
                        gx + gr * kotlin.math.cos(a2).toFloat(),
                        gy + gr * kotlin.math.sin(a2).toFloat(), texLine)
                }
            }
            else -> {
                // Камень: редкие выбоины и волосяные трещины у нижней кромки.
                val sw = Wobble(seed * 313L + 11L)
                texFill.color = lighten(strokeColor, 0.10f); texFill.alpha = 120
                for (k in 0 until 5) {
                    val x = pw * (0.10f + 0.80f * ((sw.j(1f) + 1f) * 0.5f))
                    val y = phh * (0.18f + 0.70f * ((sw.j(1f) + 1f) * 0.5f))
                    canvas.drawCircle(x, y, (0.9f + 1.1f * ((sw.j(1f) + 1f) * 0.5f)) * d, texFill)
                }
                texLine.color = lighten(strokeColor, 0.10f); texLine.alpha = 120
                texLine.strokeWidth = 1f * d
                for (k in 0 until 2) {
                    val x = pw * (0.20f + 0.55f * k) + sw.j(6f) * d
                    val y = phh - 8f * d
                    canvas.drawLine(x, y, x + 7f * d, y - 5f * d, texLine)
                    canvas.drawLine(x + 7f * d, y - 5f * d, x + 11f * d, y - 3f * d, texLine)
                }
            }
        }
        texFill.alpha = 255
    }

    /** Контур в материале вкладки: камень, молния, канат, огонь, лёд, механика. */
    private fun drawContour(canvas: Canvas, p: Path) {
        val ph = BoilClock.phase
        when (material) {
            MAT_LIGHTNING -> {
                val fl = 0.6f + 0.4f * kotlin.math.sin((ph * 5.5f).toDouble()).toFloat()
                matPaint.pathEffect = null
                matPaint.color = strokeColor
                matPaint.strokeWidth = 5.5f * d; matPaint.alpha = (70f * fl).toInt().coerceIn(0, 255)
                canvas.drawPath(p, matPaint)
                matPaint.strokeWidth = 2.6f * d; matPaint.alpha = 205
                canvas.drawPath(p, matPaint)
                matPaint.color = lighten(strokeColor, 0.75f)
                matPaint.strokeWidth = 1.1f * d
                matPaint.alpha = (170f + 70f * fl).toInt().coerceIn(0, 255)
                canvas.drawPath(p, matPaint)
            }
            MAT_ROPE -> {
                matPaint.pathEffect = null
                matPaint.color = darken(strokeColor, 0.40f)
                matPaint.strokeWidth = 3.6f * d; matPaint.alpha = 235
                canvas.drawPath(p, matPaint)
                matPaint.color = strokeColor; matPaint.strokeWidth = 1.5f * d; matPaint.alpha = 230
                canvas.save(); canvas.translate(0.9f * d, -0.9f * d); canvas.drawPath(p, matPaint); canvas.restore()
                canvas.save(); canvas.translate(-0.9f * d, 0.9f * d); canvas.drawPath(p, matPaint); canvas.restore()
                matPaint.pathEffect = ropeDash
                matPaint.color = lighten(strokeColor, 0.40f)
                matPaint.strokeWidth = 3.4f * d; matPaint.alpha = 110
                canvas.drawPath(p, matPaint)
                matPaint.pathEffect = null
            }
            MAT_FIRE -> {
                val fl = 0.55f + 0.45f * kotlin.math.sin((ph * 3.3f).toDouble()).toFloat()
                val fl2 = 0.5f + 0.5f * kotlin.math.sin((ph * 7.1f + 1.3f).toDouble()).toFloat()
                matPaint.pathEffect = null
                matPaint.color = darken(strokeColor, 0.25f)
                matPaint.strokeWidth = 6.5f * d; matPaint.alpha = (55f * fl).toInt().coerceIn(0, 255)
                canvas.drawPath(p, matPaint)
                matPaint.color = strokeColor
                matPaint.strokeWidth = 3f * d; matPaint.alpha = (150f + 60f * fl2).toInt().coerceIn(0, 255)
                canvas.drawPath(p, matPaint)
                matPaint.color = lighten(strokeColor, 0.55f)
                matPaint.strokeWidth = 1.2f * d; matPaint.alpha = (140f + 90f * fl2).toInt().coerceIn(0, 255)
                canvas.drawPath(p, matPaint)
            }
            MAT_ICE -> {
                matPaint.pathEffect = null
                matPaint.color = darken(strokeColor, 0.45f)
                matPaint.strokeWidth = 4.2f * d; matPaint.alpha = 220
                canvas.drawPath(p, matPaint)
                matPaint.color = lighten(strokeColor, 0.30f)
                matPaint.strokeWidth = 1.8f * d; matPaint.alpha = 240
                canvas.drawPath(p, matPaint)
                // Холодный отблеск скользит по канту.
                val sh = 0.5f + 0.5f * kotlin.math.sin((ph * 1.1f).toDouble()).toFloat()
                matPaint.color = 0xFFFFFFFF.toInt()
                matPaint.strokeWidth = 1f * d; matPaint.alpha = (60f + 90f * sh).toInt().coerceIn(0, 255)
                canvas.save(); canvas.translate(-1f * d, -1f * d); canvas.drawPath(p, matPaint); canvas.restore()
            }
            MAT_MECH -> {
                matPaint.pathEffect = null
                matPaint.color = darken(strokeColor, 0.50f)
                matPaint.strokeWidth = 4f * d; matPaint.alpha = 210
                canvas.drawPath(p, matPaint)
                matPaint.pathEffect = mechDash
                matPaint.color = lighten(strokeColor, 0.25f)
                matPaint.strokeWidth = 2.2f * d; matPaint.alpha = 235
                canvas.drawPath(p, matPaint)
                matPaint.pathEffect = null
            }
            else -> {
                matPaint.pathEffect = null
                matPaint.color = darken(strokeColor, 0.55f)
                matPaint.strokeWidth = 4.4f * d; matPaint.alpha = 205
                canvas.drawPath(p, matPaint)
                Doodle.ink(canvas, p, strokePaint, 0.8f * d)
            }
        }
    }

    /** Осветление цвета к белому на долю t - для светлого канта резьбы. */
    private fun lighten(c: Int, t: Float): Int {
        val r = (Color.red(c) + (255 - Color.red(c)) * t).toInt()
        val g = (Color.green(c) + (255 - Color.green(c)) * t).toInt()
        val b = (Color.blue(c) + (255 - Color.blue(c)) * t).toInt()
        return Color.argb(255, r, g, b)
    }

    /** Затемнение цвета на долю t - для тёмного края объёма/тона камня. */
    private fun darken(c: Int, t: Float): Int {
        return Color.argb(255, (Color.red(c) * (1 - t)).toInt(),
            (Color.green(c) * (1 - t)).toInt(), (Color.blue(c) * (1 - t)).toInt())
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
    /**
     * Повторный вызов на той же View больше НЕ добавляет вторую подписку.
     *
     * Экраны пересобираются (removeAllViews и заново), и на переиспользуемой
     * View pulse мог быть вызван дважды. Каждый вызов вешал свой слушатель
     * и свою подписку: элемент начинал дышать вдвое сильнее (две лямбды
     * пишут alpha по очереди), а такт получал лишних подписчиков. Метка на
     * View - самый дешёвый способ помнить, что мы здесь уже были.
     */
    fun pulse(v: View) {
        if (v.getTag(R.id.tag_pulse_bound) == true) return
        v.setTag(R.id.tag_pulse_bound, true)
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

    fun frame(v: View, strokeRes: Int, fillRes: Int, seed: Long,
              material: Int = DoodleBorderDrawable.MAT_ROCK) {
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
            material,
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
        /** Точек в кривой тропы следов. Больше - плавнее, дороже кэш. */
        private const val TRAIL_PTS = 40
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
    private val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
    private val green = ContextCompat.getColor(context, R.color.accent_green)
    private val savePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val ember = 0xFF966E46.toInt()

    /**
     * Декор ПОЛУПРОЗРАЧЕН намеренно: он фон, а не контент, и обязан уступать
     * тексту. Первая версия рисовала в полную силу - цифры стало не прочесть.
     */
    // Кисти и пути сцены переиспользуются. Ни один вызов не удерживает
    // возвращённую кисть дольше одной операции рисования - проверено по
    // всем местам вызова, присваиваний вида `val p = stroke(...)` нет.
    private val scStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val scFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scPathA = Path()
    private val scPathB = Path()
    private val scPathC = Path()
    /**
     * Пул путей для сцен. Кино на экранах Статистики, Timeline и Истории
     * создавало десятки Path в КАЖДОМ кадре - там объектов в кадре было
     * больше, чем на главном экране. Внутри одной сцены номера пула не
     * повторяются там, где два пути живут одновременно; между сценами
     * пересечений нет - в кадре рисуется ровно одна сцена.
     */
    private val scPool = Array(8) { Path() }
    private fun sp(i: Int): Path {
        val p = scPool[i]
        p.reset()
        return p
    }
    // Постоянные наборы, которые пересоздавались в каждом кадре.
    private val birdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /**
     * ИМЯ ПОЛЯ - ЭТО ТОЖЕ ИНТЕРФЕЙС.
     *
     * Кисть луча сканера была названа beamPaint - имя, уже занятое в этом
     * же классе кистью лучей другого назначения. Kotlin увидел два поля с
     * одним именем и отказался разбирать КАЖДОЕ обращение к нему.
     * Названо по слою, которому принадлежит: путаницы больше не будет.
     */
    private val scanBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sparkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val smokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val meteorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    /** Звёздная пыль: x, y, размер, сдвиг фазы. Постоянна - не в кадре. */
    private val STAR_DUST = floatArrayOf(
        0.08f, 0.10f, 0.0055f, 0.0f,
        0.17f, 0.26f, 0.0040f, 1.7f,
        0.29f, 0.07f, 0.0060f, 3.1f,
        0.38f, 0.21f, 0.0035f, 0.9f,
        0.52f, 0.11f, 0.0050f, 2.4f,
        0.61f, 0.29f, 0.0038f, 4.2f,
        0.70f, 0.08f, 0.0058f, 1.2f,
        0.79f, 0.23f, 0.0042f, 5.0f,
        0.90f, 0.13f, 0.0050f, 2.9f,
        0.96f, 0.33f, 0.0036f, 0.4f)
    /** Лучистые звёзды: x, y, размер, сдвиг фазы. */
    private val STAR_BIG = floatArrayOf(
        0.44f, 0.18f, 0.0080f, 0.0f,
        0.86f, 0.70f, 0.0060f, 2.2f,
        0.23f, 0.42f, 0.0055f, 4.1f)
    private val mtPX = FloatArray(3)
    private val mtPY = FloatArray(3)
    private val stackHeights = intArrayOf(2, 1, 3, 1)
    private val surfTops = FloatArray(5)
    private val surfCxs = FloatArray(5)

    private fun stroke(c: Int, wpx: Float, a: Int = DECOR_ALPHA): Paint {
        scStroke.color = c
        scStroke.alpha = a
        scStroke.strokeWidth = wpx * d
        return scStroke
    }

    /** Один ярус пушистой ёлки: бахрома веток снизу (кончики вниз). */
    private fun fluffyTier(x: Float, apexY: Float, botY: Float, tw: Float, w: Wobble): Path {
        val p = scPathA
        p.reset()
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

        // КАЧАНИЕ. Ёлка гнётся от общего ветра, но со своим запаздыванием:
        // порыв доходит до дальних деревьев позже, и лес перестаёт быть
        // строем одинаковых фигур. Наклон вокруг ОСНОВАНИЯ - ствол стоит
        // на месте, как и положено. Мелкие деревья гнутся сильнее.
        val lag = x * 0.010f
        val gust = 0.62f * sin((BoilClock.phase * 0.23f - lag).toDouble()).toFloat() +
                   0.38f * sin((BoilClock.phase * 0.071f + 1.3f - lag).toDouble()).toFloat()
        c.save()
        c.rotate(gust * 1.6f * (1.15f - h * 0.0009f), x, baseY)
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

        // СБРОС СНЕГА. Раз в несколько секунд с лапы срывается ком и
        // падает, разбиваясь пылью у земли. Окно задано временем и
        // сдвигом дерева: два соседних никогда не сыплют разом, и ни одно
        // не сыплет в каждом кадре по случайности.
        val dropT = BoilClock.phase / 6.5f + x * 0.017f
        val dropF = dropT - kotlin.math.floor(dropT)
        if (dropF < 0.34f) {
            val g = dropF / 0.34f
            val sy = baseY - h * 0.74f
            val fall = h * 0.70f * g * g
            firFill.color = 0xFFFFFFFF.toInt()
            firFill.alpha = (225f * (1f - g * 0.55f)).toInt().coerceIn(0, 255)
            c.drawCircle(x - h * 0.16f + gust * h * 0.05f * g, sy + fall,
                h * 0.030f * (1f - 0.35f * g), firFill)
            if (g > 0.82f) {
                // Пыль удара: три крупинки в стороны.
                val sk = (g - 0.82f) / 0.18f
                firFill.alpha = (200f * (1f - sk)).toInt().coerceIn(0, 255)
                for (k in 0 until 3) {
                    c.drawCircle(x - h * 0.16f + (k - 1) * h * 0.07f * sk,
                        baseY - h * 0.02f - h * 0.05f * sk * (1f - sk) * 4f,
                        h * 0.014f * (1f - sk), firFill)
                }
            }
        }
        firFill.alpha = 255
        c.restore()
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
        val px = mtPX
        px[0] = x0 + ww * 0.22f; px[1] = x0 + ww * 0.52f; px[2] = x0 + ww * 0.80f
        val py = mtPY
        py[0] = baseY - h * 0.72f; py[1] = baseY - h; py[2] = baseY - h * 0.60f
        val sil = scPathB
        sil.reset()
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
            val e = scPathC
            e.reset(); e.moveTo(px[i], py[i]); e.lineTo(px[i], baseY); c.drawPath(e, mtEdge)
        }

        mtOutline.color = darkenC(tint, 0.55f)
        Doodle.ink(c, sil, mtOutline, 0.7f * d)

        for (i in 0 until 3) {
            val cap = scPathA
            cap.reset()
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
        // Два ореола дышат РАЗНЫМИ периодами: совпадающие пульсации
        // читаются как мигание лампы, разные - как живой свет.
        val gl = 0.6f + 0.4f * kotlin.math.sin((BoilClock.phase * 0.8f).toDouble()).toFloat()
        val gl2 = 0.6f + 0.4f * kotlin.math.sin((BoilClock.phase * 0.31f + 2.1f).toDouble()).toFloat()
        skyFill.color = tint
        skyFill.alpha = (26f * gl2).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r * (1.9f + 0.15f * gl2), skyFill)
        skyFill.alpha = (44f * gl).toInt().coerceIn(0, 255); c.drawCircle(cx, cy, r * 1.35f, skyFill)
        // Полный диск: узкий серп читался как «огрызок». Объём даёт
        // затенённый край, узнаваемость - кратеры.
        val disc = scPathA
        disc.reset(); disc.addCircle(cx, cy, r, Path.Direction.CW)
        skyFill.color = lit; skyFill.alpha = 240; c.drawPath(disc, skyFill)
        c.save(); c.clipPath(disc)
        skyFill.color = darkenC(lit, 0.22f); skyFill.alpha = 130
        c.drawCircle(cx + r * 0.55f, cy + r * 0.25f, r * 0.95f, skyFill)
        skyFill.color = darkenC(lit, 0.30f); skyFill.alpha = 165
        c.drawCircle(cx - r * 0.34f, cy - r * 0.28f, r * 0.22f, skyFill)
        c.drawCircle(cx + r * 0.10f, cy + r * 0.34f, r * 0.16f, skyFill)
        c.drawCircle(cx - r * 0.06f, cy - r * 0.52f, r * 0.11f, skyFill)
        c.drawCircle(cx - r * 0.52f, cy + r * 0.30f, r * 0.09f, skyFill)
        // ФАЗА ЛУНЫ - НАСТОЯЩАЯ. Тень наводится вторым кругом со
        // смещением: чем ближе к новолунию, тем сильнее он съедает диск.
        // Полнолуние - тень уходит целиком, и это видно раз в месяц.
        val term = moonTerminator()
        if (Math.abs(term) > 0.06f) {
            skyFill.color = darkenC(lit, 0.62f)
            skyFill.alpha = 215
            c.drawCircle(cx + r * 1.35f * term, cy, r * 1.02f, skyFill)
        }
        c.restore()
        skyOutline.color = dark; Doodle.ink(c, disc, skyOutline, 0.6f * d)
        skyFill.alpha = 255
    }

    private fun sunRich(c: Canvas, cx: Float, cy: Float, r: Float, w: Wobble, tint: Int) {
        val lit = lightenC(tint, 0.30f)
        val core = lightenC(tint, 0.65f)
        val dark = darkenC(tint, 0.30f)
        // Корона дышит своим, медленным периодом - под лучами есть
        // объём, а не пустой фон.
        val cor = 0.5f + 0.5f * kotlin.math.sin((BoilClock.phase * 0.27f).toDouble()).toFloat()
        skyFill.color = core
        skyFill.alpha = (30f + 26f * cor).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r * (1.55f + 0.22f * cor), skyFill)
        skyFill.alpha = (20f + 18f * cor).toInt().coerceIn(0, 255)
        c.drawCircle(cx, cy, r * (2.15f + 0.30f * cor), skyFill)
        skyFill.alpha = 255

        // Вращение вдвое медленнее прежнего: быстрый оборот читался как
        // вертушка. Лучи чередуются длинный/короткий и живут врозь.
        val rot = BoilClock.phase * 0.09f
        rayPaint.color = lit; rayPaint.alpha = 150
        for (k in 0 until 10) {
            val a = rot + k * (Math.PI.toFloat() * 2f / 10f)
            val base = if (k % 2 == 0) 1.85f else 1.45f
            val len = base + 0.28f *
                kotlin.math.sin((BoilClock.phase * (1.1f + 0.17f * k) + k).toDouble()).toFloat()
            c.drawLine(
                cx + r * 1.25f * kotlin.math.cos(a.toDouble()).toFloat(),
                cy + r * 1.25f * kotlin.math.sin(a.toDouble()).toFloat(),
                cx + r * len * kotlin.math.cos(a.toDouble()).toFloat(),
                cy + r * len * kotlin.math.sin(a.toDouble()).toFloat(), rayPaint)
        }
        skyFill.color = lit; skyFill.alpha = 225; c.drawCircle(cx, cy, r, skyFill)
        skyFill.color = core; skyFill.alpha = 200; c.drawCircle(cx - r * 0.25f, cy - r * 0.25f, r * 0.5f, skyFill)
        val disc = scPathA
        disc.reset(); disc.addCircle(cx, cy, r, Path.Direction.CW)
        skyOutline.color = dark; Doodle.ink(c, disc, skyOutline, 0.6f * d)
        skyFill.alpha = 255
    }

    private fun cloudRich(c: Canvas, cx: Float, cy: Float, s: Float, w: Wobble, tint: Int) {
        val lit = lightenC(tint, 0.30f)
        val dark = darkenC(tint, 0.28f)
        val puff = scPathA
        puff.reset()
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

    /**
     * Следы к горе: путь = земля слева -> подножие -> склон к вершине.
     * Голова цикла бежит по следам, за ней затухающий хвост; в конце
     * периода всё гаснет и путь повторяется. Свечение - янтарное.
     */
    // Путь следов кэшируется по размеру вьюхи: в кадре только отрисовка.
    private val trailPX = FloatArray(TRAIL_PTS)
    private val trailPY = FloatArray(TRAIL_PTS)
    private val trailLen = FloatArray(TRAIL_PTS)
    private var trailKey = 0
    private val footPath = Path()

    /**
     * Тропа следов от ёлок к вершине.
     *
     * Что было не так. След рисовался одним овалом, все следы были
     * одинакового размера, шли по двум прямым отрезкам и стояли через
     * равные промежутки. Получалась пунктирная линия, а не чей-то путь.
     *
     * Что сделано:
     *  - ФОРМА. След - подошва: широкий передок, узкий свод, круглая
     *    пятка, плюс отпечаток каблука отдельным пятном. Левый и правый
     *    зеркальны и развёрнуты носком наружу, как ставит ногу человек.
     *  - ПЕРСПЕКТИВА. Вверх по склону след мельчает до 45% и шаг
     *    укорачивается: подъём короче шагом, и он дальше от смотрящего.
     *  - ГЛУБИНА. Под каждым следом лежит тёмная вмятина со смещением -
     *    отпечаток продавлен, а не наклеен.
     *  - ЖИВОСТЬ. Свежий след впечатывается: первые доли секунды он чуть
     *    крупнее и ярче, потом садится. Старые гаснут по порядку.
     *  - ПУТЬ. Кривая, а не два отрезка: земля у ёлок, затем подъём по
     *    левому ребру горы к самой вершине.
     */
    private fun footprints(c: Canvas, w: Float, h: Float) {
        val base = h * 0.95f
        val key = (w.toInt() shl 16) xor h.toInt()
        if (key != trailKey) {
            // Квадратичная кривая: старт у ёлок, изгиб у подножия,
            // конец - вершина среднего пика (mountainsRich: x0+0.52*ww).
            val ax = w * 0.02f; val ay = base
            val bx = w * 0.36f; val by = base + h * 0.02f
            val ex = w * 0.505f; val ey = base - h * 0.60f
            for (k in 0 until TRAIL_PTS) {
                val t = k.toFloat() / (TRAIL_PTS - 1)
                val u = 1f - t
                trailPX[k] = u * u * ax + 2f * u * t * bx + t * t * ex
                trailPY[k] = u * u * ay + 2f * u * t * by + t * t * ey
                trailLen[k] = if (k == 0) 0f else trailLen[k - 1] +
                    Math.hypot((trailPX[k] - trailPX[k - 1]).toDouble(),
                        (trailPY[k] - trailPY[k - 1]).toDouble()).toFloat()
            }
            trailKey = key
        }
        val total = trailLen[TRAIL_PTS - 1]
        if (total <= 0f) return

        val n = 20
        val period = 8.5f
        val head = ((BoilClock.phase / period) % 1f) * (n + 6)

        for (i in 0 until n) {
            val rel = head - i
            if (rel < 0f) continue
            var a = 1f - rel / 7f
            if (a <= 0f) continue

            // Шаг укорачивается к вершине: доля пути растёт быстрее номера.
            val t = Math.pow(i.toDouble() / (n - 1), 1.28).toFloat()
            val far = t                                   // 0 близко, 1 далеко
            val sAt = t * total
            var k = 1
            while (k < TRAIL_PTS - 1 && trailLen[k] < sAt) k++
            val seg = (trailLen[k] - trailLen[k - 1]).coerceAtLeast(0.001f)
            val f = ((sAt - trailLen[k - 1]) / seg).coerceIn(0f, 1f)
            val px = trailPX[k - 1] + (trailPX[k] - trailPX[k - 1]) * f
            val py = trailPY[k - 1] + (trailPY[k] - trailPY[k - 1]) * f
            val dx = trailPX[k] - trailPX[k - 1]
            val dy = trailPY[k] - trailPY[k - 1]
            val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(0.001f)
            val ux = dx / len; val uy = dy / len

            // Левая и правая нога по разные стороны осевой линии.
            val side = if (i % 2 == 0) 1f else -1f
            val off = w * 0.011f * (1f - 0.5f * far)
            val fx = px + (-uy) * side * off
            val fy = py + ux * side * off

            // Носок развёрнут наружу - так ставит ногу человек.
            val ang = Math.toDegrees(Math.atan2(uy.toDouble(), ux.toDouble())).toFloat() +
                side * 7f

            // Впечатывание свежего следа.
            val press = if (rel < 0.8f) 1f + 0.22f * (1f - rel / 0.8f) else 1f
            if (rel < 0.8f) a = (a + 0.25f * (1f - rel / 0.8f)).coerceAtMost(1f)
            val sz = w * 0.019f * (1f - 0.55f * far) * press

            c.save()
            c.translate(fx, fy)
            c.rotate(ang)
            // Вмятина под следом.
            footPaint.color = 0xFF1A1206.toInt()
            footPaint.alpha = (150f * a).toInt().coerceIn(0, 255)
            solePath(sz * 1.12f, sz * 0.60f, 0.6f * d, 0.6f * d)
            c.drawPath(footPath, footPaint)
            // Сам отпечаток.
            footPaint.color = 0xFFFFD98A.toInt()
            footPaint.alpha = (215f * a).toInt().coerceIn(0, 255)
            solePath(sz, sz * 0.52f, 0f, 0f)
            c.drawPath(footPath, footPaint)
            // Каблук отдельным пятном: подошва не сплошная.
            footPaint.alpha = (150f * a).toInt().coerceIn(0, 255)
            c.drawOval(-sz * 0.98f, -sz * 0.34f, -sz * 0.42f, sz * 0.34f, footPaint)
            c.restore()
        }
        footPaint.alpha = 255
    }

    /**
     * Подошва в местных координатах (носок вправо): широкий передок,
     * узкий свод, круглая пятка. Строится в поле footPath - без
     * аллокаций в кадре.
     */
    private fun solePath(l: Float, wd: Float, ox: Float, oy: Float) {
        footPath.reset()
        footPath.moveTo(ox + l * 0.98f, oy)
        footPath.quadTo(ox + l * 0.92f, oy - wd, ox + l * 0.30f, oy - wd * 0.92f)
        footPath.quadTo(ox + l * 0.02f, oy - wd * 0.42f, ox - l * 0.46f, oy - wd * 0.72f)
        footPath.quadTo(ox - l * 1.05f, oy - wd * 0.55f, ox - l * 1.02f, oy)
        footPath.quadTo(ox - l * 1.05f, oy + wd * 0.55f, ox - l * 0.46f, oy + wd * 0.72f)
        footPath.quadTo(ox + l * 0.02f, oy + wd * 0.42f, ox + l * 0.30f, oy + wd * 0.92f)
        footPath.quadTo(ox + l * 0.92f, oy + wd, ox + l * 0.98f, oy)
        footPath.close()
    }

    private fun fill(c: Int, a: Int = DECOR_ALPHA): Paint {
        scFill.color = c
        scFill.alpha = a
        return scFill
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

    /**
     * ВЕТЕР - ОДИН НА ВСЮ СЦЕНУ.
     *
     * Прежде каждый элемент двигался сам по себе, и сцена читалась как
     * набор независимых заводных игрушек. Ветер связывает их: он гонит
     * облака, качает ёлки и стряхивает с них снег. Порыв собран из двух
     * несоразмерных волн, поэтому не повторяется на слух глаза: период
     * заметного повтора - минуты, а не секунды.
     *
     * Возвращает -1..1: знак - направление, модуль - сила.
     */
    private fun wind(): Float {
        val p = BoilClock.phase
        return 0.62f * sin((p * 0.23f).toDouble()).toFloat() +
               0.38f * sin((p * 0.071f + 1.3f).toDouble()).toFloat()
    }

    /**
     * Освещённая доля луны, -1..1: знак - с какой стороны тень.
     *
     * Луна в приложении показывает ТУ ЖЕ фазу, что и настоящая за окном.
     * Это ничего не стоит и делает картинку правдой, а не украшением:
     * человек, поднявший голову, увидит то же самое. Считается по
     * синодическому месяцу от известного новолуния; точность - день,
     * большего для картинки не нужно.
     *
     * Пересчёт раз в час, а не в кадре: LocalDate в кадре - лишний мусор.
     */
    private var moonKeyH = -1L
    private var moonTermK = 0f
    private fun moonTerminator(): Float {
        val h = System.currentTimeMillis() / 3_600_000L
        if (h != moonKeyH) {
            moonKeyH = h
            // Новолуние 2000-01-06 18:14 UTC = 947182440 с. Месяц 29.53 сут.
            val days = (System.currentTimeMillis() / 1000.0 - 947182440.0) / 86400.0
            var age = (days % 29.530588) / 29.530588
            if (age < 0) age += 1.0
            // 0 - новолуние, 0.5 - полнолуние. Терминатор идёт от края к
            // краю и обратно, знак меняется в полнолуние.
            moonTermK = (Math.cos(age * 2.0 * Math.PI)).toFloat()
        }
        return moonTermK
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
        // Облака несёт ТОТ ЖЕ ветер, что качает ёлки: при порыве они
        // заметно ускоряются. Дрейф остаётся равномерным в среднем -
        // облако не должно останавливаться и пятиться.
        val gust = wind()
        for ((yFrac, sizeFrac, period) in specs) {
            val margin = w * 0.25f
            val x = -margin + (w + 2 * margin) * loop(period, yFrac) +
                gust * w * 0.035f
            // Высота слегка гуляет: облако не жёсткая наклейка.
            val bob = h * 0.012f * sin((BoilClock.phase * 0.4f + yFrac * 9f).toDouble()).toFloat()
            cloudRich(c, x, h * yFrac + bob, h * sizeFrac * (1f + 0.05f * gust), r, color)
        }
    }

    private fun stars(c: Canvas, color: Int, pts: List<Triple<Float, Float, Float>>,
                      w: Float, h: Float, r: Wobble, scale: Float = 1f) {
        for ((i, p) in pts.withIndex()) {
            val (xf, yf, rf) = p
            val k = twinkle(i * 1.9f)
            val path = scPathA
            path.reset()
            Doodle.star(path, w * xf, h * yf, h * rf * k, r)
            Doodle.ink(c, path, stroke(color, 2f, (DECOR_ALPHA * k * scale).toInt()), 0.8f * d)
        }
    }

    /**
     * НЕБО ИЗ ТРЁХ ПОРОД ЗВЁЗД.
     *
     * Две одинаковые звезды, мерцающие в такт, читались как две лампочки.
     * Теперь на небе три разных вида, и у каждой звезды свой период:
     *  - ПЫЛЬ: мелкие точки, живут только яркостью; их много, они держат
     *    глубину неба и почти ничего не стоят;
     *  - ЛУЧИСТЫЕ: крупные четырёхлучевые, дышат размером;
     *  - ПАДАЮЩАЯ: раз в семнадцать секунд одна прочерчивает небо. Она
     *    не случайна в кадре - её окно задано временем, поэтому полёт
     *    ровный и не дёргается при просадке частоты.
     *
     * Данные звёзд лежат в постоянном массиве: ни списков, ни Triple в
     * кадре. Формат: x, y, размер, сдвиг фазы.
     */
    private fun starfield(c: Canvas, w: Float, h: Float, r: Wobble, scale: Float) {
        if (scale <= 0f) return
        // Пыль.
        var i = 0
        while (i < STAR_DUST.size) {
            val x = w * STAR_DUST[i]
            val y = h * STAR_DUST[i + 1]
            val rad = h * STAR_DUST[i + 2]
            val ph = BoilClock.phase * (0.9f + 0.31f * (i % 7)) + STAR_DUST[i + 3]
            val k = 0.30f + 0.70f * (0.5f + 0.5f * sin(ph.toDouble()).toFloat())
            skyFill.color = if (i % 12 == 0) amberBr else blueBr
            skyFill.alpha = (190f * k * scale).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, rad * (0.7f + 0.5f * k), skyFill)
            i += 4
        }
        skyFill.alpha = 255
        // Лучистые.
        i = 0
        while (i < STAR_BIG.size) {
            val ph = BoilClock.phase * (1.2f + 0.4f * i) + STAR_BIG[i + 3]
            val k = 0.45f + 0.55f * (0.5f + 0.5f * sin(ph.toDouble()).toFloat())
            val path = scPathA
            path.reset()
            Doodle.star(path, w * STAR_BIG[i], h * STAR_BIG[i + 1], h * STAR_BIG[i + 2] * k, r)
            Doodle.ink(c, path, stroke(blueBr, 2f, (DECOR_ALPHA * k * scale).toInt()), 0.8f * d)
            i += 4
        }
        // Падающая.
        val periodS = 17f
        val t = BoilClock.phase / periodS
        val slot = kotlin.math.floor(t).toInt()
        val f = t - slot
        if (f < 0.085f) {
            val g = f / 0.085f
            val hs = ((slot * 2654435761L) ushr 20)
            val x0 = w * (0.12f + 0.62f * ((hs % 100L) / 100f))
            val y0 = h * (0.05f + 0.22f * (((hs shr 7) % 100L) / 100f))
            val len = w * 0.30f
            val hx = x0 + len * g
            val hy = y0 + len * 0.42f * g
            val fade = sin((g * Math.PI).toFloat())
            meteorPaint.color = 0xFFFFFFFF.toInt()
            meteorPaint.strokeWidth = 1.8f * d
            meteorPaint.alpha = (235f * fade * scale).toInt().coerceIn(0, 255)
            c.drawLine(hx - len * 0.26f, hy - len * 0.11f, hx, hy, meteorPaint)
            meteorPaint.color = blueBr
            meteorPaint.strokeWidth = 4.5f * d
            meteorPaint.alpha = (90f * fade * scale).toInt().coerceIn(0, 255)
            c.drawLine(hx - len * 0.34f, hy - len * 0.14f, hx, hy, meteorPaint)
        }
    }

    private enum class DayPhase { NIGHT, DAWN, DAY, DUSK }

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var skyShader: android.graphics.LinearGradient? = null
    private var skyKey = ""

    /** Фаза дня по ЛОКАЛЬНОМУ времени телефона (учитывает часовой пояс). */
    private fun currentPhase(): DayPhase = when (java.time.LocalTime.now().hour) {
        in 5..7 -> DayPhase.DAWN
        in 8..16 -> DayPhase.DAY
        in 17..21 -> DayPhase.DUSK
        else -> DayPhase.NIGHT
    }

    /** Небо-градиент фазы; низ тает в фон экрана (#0A0A0A) - плиты читаемы. */
    private fun drawSky(c: Canvas, w: Float, h: Float, phase: DayPhase) {
        val key = "$phase-${w.toInt()}-${h.toInt()}"
        if (key != skyKey || skyShader == null) {
            val cols: IntArray; val pos: FloatArray
            when (phase) {
                DayPhase.NIGHT -> {
                    cols = intArrayOf(0xFF161A40.toInt(), 0xFF0A0A0A.toInt()); pos = floatArrayOf(0f, 1f)
                }
                DayPhase.DAWN -> {
                    cols = intArrayOf(0xFF2E2748.toInt(), 0xFFA05A3E.toInt(), 0xFF0A0A0A.toInt())
                    pos = floatArrayOf(0f, 0.62f, 1f)
                }
                DayPhase.DAY -> {
                    cols = intArrayOf(0xFF3A6FC0.toInt(), 0xFF12203A.toInt(), 0xFF0A0A0A.toInt())
                    pos = floatArrayOf(0f, 0.70f, 1f)
                }
                DayPhase.DUSK -> {
                    cols = intArrayOf(0xFF2A2048.toInt(), 0xFFC05A34.toInt(), 0xFF0A0A0A.toInt())
                    pos = floatArrayOf(0f, 0.58f, 1f)
                }
            }
            skyShader = android.graphics.LinearGradient(0f, 0f, 0f, h, cols, pos,
                android.graphics.Shader.TileMode.CLAMP)
            skyKey = key
        }
        skyPaint.shader = skyShader
        c.drawRect(0f, 0f, w, h, skyPaint)
    }

    private fun drawHeader(c: Canvas, w: Float, h: Float, r: Wobble) {
        val base = h * 0.95f
        val phase = currentPhase()
        drawSky(c, w, h, phase)

        val mtTint = when (phase) {
            DayPhase.DAWN -> amber
            DayPhase.DAY -> blue
            DayPhase.DUSK -> red
            DayPhase.NIGHT -> violet
        }
        mountainsRich(c, w * 0.30f, base, w * 0.40f, h * 0.62f, r, mtTint)
        firRich(c, w * 0.06f, base, h * 0.52f, r)
        firRich(c, w * 0.14f, base, h * 0.40f, r)
        firRich(c, w * 0.72f, base, h * 0.45f, r)
        firRich(c, w * 0.78f, base, h * 0.34f, r)

        val cloudTint = when (phase) {
            DayPhase.DAY -> blue
            DayPhase.NIGHT -> violet
            else -> amber
        }
        drifting(c, w, h, r, cloudTint, listOf(
            Triple(0.22f, 0.13f, 26f), Triple(0.14f, 0.09f, 34f)))

        // Небесное тело: солнце встаёт/высоко/садится, ночью луна.
        val sunK = 0.9f + 0.18f * sin((BoilClock.phase * 1.3f).toDouble()).toFloat()
        when (phase) {
            DayPhase.DAWN -> sunRich(c, w * 0.20f, h * 0.34f, h * 0.09f * sunK, r, amber)
            DayPhase.DAY -> sunRich(c, w * 0.50f, h * 0.16f, h * 0.11f * sunK, r, amber)
            DayPhase.DUSK -> sunRich(c, w * 0.84f, h * 0.34f, h * 0.09f * sunK, r, red)
            DayPhase.NIGHT -> moonRich(c, w * 0.92f, h * 0.26f, h * 0.16f, r, amberBr)
        }

        // Звёзды: ночью ярко, в сумерки/рассвет слабо, днём нет.
        val starA = when (phase) {
            DayPhase.NIGHT -> 1f
            DayPhase.DUSK -> 0.45f
            DayPhase.DAWN -> 0.45f
            DayPhase.DAY -> 0f
        }
        if (starA > 0f) starfield(c, w, h, r, starA)

        footprints(c, w, h)
    }

    private fun drawNight(c: Canvas, w: Float, h: Float, r: Wobble) {
        moonRich(c, w * 0.80f, h * 0.16f, h * 0.11f, r, violetBr)
        starfield(c, w, h, r, 1f)
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

    /**
     * АТМОСФЕРА ВКЛАДОК: РАЗНАЯ ПО ПРИРОДЕ, А НЕ ПО НАСТРОЙКАМ.
     *
     * Соблазн был очевидный: сделать один красивый слой (скажем, парящие
     * частицы) и включить его всюду с разным цветом. Это дало бы шесть
     * одинаковых экранов - и хуже: перестало бы что-либо значить, потому
     * что одинаковое движение не отличает Историю от Калибровки.
     *
     * Поэтому у каждой вкладки приём СВОЕЙ природы, и он вытекает из
     * смысла экрана:
     *   Аналитика   - стая птиц клином: взгляд сверху, издалека, на общее;
     *   Timeline    - тени облаков бегут по земле: время как движение дня;
     *   Профиль     - объёмный луч сканера с пылью: тебя разглядывают;
     *   Калибровка  - искры зацепления: механизм работает и стачивается;
     *   История     - улетающие страницы: прошлое листается и уходит;
     *   Экспедиция  - дым по ветру и светлячки: стоянка живёт ночью.
     *
     * Все слои: без аллокаций в кадре, время - из общего такта, никакой
     * случайности внутри кадра (иначе дрожь вместо движения).
     */

    /** Аналитика: стая клином проходит над хребтом раз в цикл. */
    private fun birdFlock(c: Canvas, w: Float, h: Float) {
        val t = loop(21f, 0f)
        // Стая видна не весь цикл: пришла, прошла, небо снова пустое.
        if (t > 0.62f) return
        val g = t / 0.62f
        val fx = -w * 0.15f + w * 1.30f * g
        val fy = h * (0.30f - 0.10f * sin((g * Math.PI).toFloat()))
        val fade = sin((g * Math.PI).toFloat()).coerceIn(0f, 1f)
        var i = 0
        while (i < 7) {
            // Клин: чётные - левое крыло строя, нечётные - правое.
            val rank = (i + 1) / 2
            val side = if (i % 2 == 0) -1f else 1f
            val bx = fx - rank * w * 0.045f
            val by = fy + side * rank * h * 0.035f
            // Взмах: у каждой птицы свой сдвиг, стая не машет строем.
            val flap = sin((BoilClock.phase * 6.5f + i * 0.9f).toDouble()).toFloat()
            val sp2 = h * 0.030f
            val lift = sp2 * 0.55f * flap
            birdPaint.color = blueBr
            birdPaint.strokeWidth = 1.6f * d
            birdPaint.alpha = (170f * fade).toInt().coerceIn(0, 255)
            c.drawLine(bx - sp2, by - lift, bx, by + sp2 * 0.18f, birdPaint)
            c.drawLine(bx, by + sp2 * 0.18f, bx + sp2, by - lift, birdPaint)
            i++
        }
    }

    /** Timeline: тени облаков ползут по земле - день идёт. */
    private fun cloudShadows(c: Canvas, w: Float, h: Float, base: Float) {
        var i = 0
        while (i < 3) {
            val t = loop(22f + 9f * i, 0.17f * i)
            val cx = -w * 0.3f + w * 1.6f * t
            // Тень длиннее облака и мягче: свет идёт под углом.
            val sw = w * (0.26f + 0.07f * i)
            val sy = base - h * (0.02f + 0.03f * i)
            shadowPaint.color = 0xFF000000.toInt()
            shadowPaint.alpha = (34 - i * 7).coerceIn(0, 255)
            c.drawOval(cx - sw, sy - h * 0.045f, cx + sw, sy + h * 0.045f, shadowPaint)
            i++
        }
    }

    /** Профиль: луч сканера объёмен - в нём видна пыль. */
    private fun scanBeam(c: Canvas, w: Float, h: Float, cx: Float, dw: Float) {
        val t = loop(3.4f, 0f)
        // Проход туда-обратно: сканер не телепортируется в начало.
        val y = h * (0.10f + 0.80f * (if (t < 0.5f) t * 2f else 2f - t * 2f))
        val x0 = cx - dw * 0.62f
        val x1 = cx + dw * 0.62f
        // Конус света: три полосы разной ширины и прозрачности.
        var k = 0
        while (k < 3) {
            scanBeamPaint.color = red
            scanBeamPaint.alpha = (46 - k * 13).coerceIn(0, 255)
            val hh = h * (0.010f + 0.020f * k)
            c.drawRect(x0, y - hh, x1, y + hh, scanBeamPaint)
            k++
        }
        // Ядро луча ярче тела: в классе есть red, отдельного яркого
        // красного нет, поэтому осветляем имеющийся - на один
        // цвет в палитре меньше поводов ошибиться.
        scanBeamPaint.color = lightenC(red, 0.45f)
        scanBeamPaint.alpha = 210
        c.drawRect(x0, y - 0.9f * d, x1, y + 0.9f * d, scanBeamPaint)
        // Пыль в луче: всплывает и гаснет, поэтому свет читается объёмным.
        var i = 0
        while (i < 9) {
            val ph = BoilClock.phase * 0.8f + i * 0.7f
            val dx = x0 + (x1 - x0) * (((i * 37) % 100) / 100f)
            val dy = y - h * 0.03f * (0.5f + 0.5f * sin(ph.toDouble()).toFloat())
            scanBeamPaint.alpha = (120f * (0.4f + 0.6f *
                sin((ph * 1.7f).toDouble()).toFloat())).toInt().coerceIn(0, 255)
            c.drawCircle(dx, dy, 1.1f * d, scanBeamPaint)
            i++
        }
    }

    /** Калибровка: искры в точке зацепления шестерён. */
    private fun gearSparks(c: Canvas, cx: Float, cy: Float, rad: Float) {
        val t = loop(2.6f, 0f)
        // Искрит не постоянно: зуб входит в зуб - вспышка, дальше тишина.
        if (t > 0.22f) return
        val g = t / 0.22f
        val fade = 1f - g
        var i = 0
        while (i < 6) {
            val a = Math.toRadians((-40.0 + i * 23.0))
            val len = rad * (0.25f + 0.55f * g) * (0.6f + 0.4f * ((i % 3) / 2f))
            val sx = cx + (Math.cos(a) * rad * 0.15f).toFloat()
            val sy = cy + (Math.sin(a) * rad * 0.15f).toFloat()
            // Искра летит по дуге и падает: у неё есть вес.
            val ex = sx + (Math.cos(a) * len).toFloat()
            val ey = sy + (Math.sin(a) * len).toFloat() + rad * 0.35f * g * g
            sparkLine.color = if (i % 2 == 0) amberBr else 0xFFFFFFFF.toInt()
            sparkLine.strokeWidth = (1.7f - 0.7f * g) * d
            sparkLine.alpha = (230f * fade).toInt().coerceIn(0, 255)
            c.drawLine(sx, sy, ex, ey, sparkLine)
            i++
        }
    }

    /** История: страницы отрываются и уносятся вверх, кружась. */
    private fun flyingPages(c: Canvas, w: Float, h: Float) {
        var i = 0
        while (i < 4) {
            val t = loop(7.5f, i * 0.27f)
            val x = w * (0.14f + 0.16f * i) + w * 0.55f * t
            // Подъём с замедлением: страницу подхватывает и отпускает.
            val y = h * 0.72f - h * 0.80f * (1f - (1f - t) * (1f - t))
            val fade = (sin((t * Math.PI).toFloat())).coerceIn(0f, 1f)
            val sz = h * 0.075f
            c.save()
            c.translate(x, y)
            // Кувырок вокруг своей оси: лист то плашмя, то ребром.
            c.rotate(t * 420f + i * 40f)
            val flat = kotlin.math.abs(sin((BoilClock.phase * 2.2f + i).toDouble()).toFloat())
            c.scale(0.25f + 0.75f * flat, 1f)
            pagePaint.color = 0xFFE8E4DA.toInt()
            pagePaint.alpha = (215f * fade).toInt().coerceIn(0, 255)
            c.drawRect(-sz * 0.7f, -sz, sz * 0.7f, sz, pagePaint)
            pagePaint.color = gray
            pagePaint.alpha = (170f * fade).toInt().coerceIn(0, 255)
            var k = 0
            while (k < 3) {
                val ly = -sz * 0.5f + sz * 0.45f * k
                c.drawRect(-sz * 0.45f, ly, sz * 0.45f, ly + 1.2f * d, pagePaint)
                k++
            }
            c.restore()
            i++
        }
    }

    /** Экспедиция: дым по ветру и светлячки у палатки. */
    private fun campAir(c: Canvas, w: Float, h: Float, fireX: Float, fireY: Float) {
        val gust = wind()
        // Дым: клубы поднимаются, растут и уводятся ветром тем сильнее,
        // чем выше поднялись - внизу воздух спокойнее.
        var i = 0
        while (i < 6) {
            var g = (BoilClock.phase * 0.22f + i * 0.1667f) % 1f
            if (g < 0f) g += 1f
            val y = fireY - h * 0.62f * g
            val x = fireX + gust * w * 0.16f * g * g +
                w * 0.02f * sin((BoilClock.phase * 0.9f + i).toDouble()).toFloat()
            smokePaint.color = 0xFF9AA0AA.toInt()
            smokePaint.alpha = (70f * (1f - g) * (1f - g)).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, h * (0.03f + 0.11f * g), smokePaint)
            i++
        }
        // Светлячки: висят, дышат светом и медленно смещаются. Их мало -
        // редкая точка в темноте заметнее россыпи.
        i = 0
        while (i < 4) {
            val ph = BoilClock.phase * (0.5f + 0.13f * i) + i * 1.9f
            val fx = w * (0.16f + 0.20f * i) +
                w * 0.05f * sin(ph.toDouble()).toFloat()
            val fy = h * (0.52f + 0.10f * ((i * 3) % 4) / 3f) +
                h * 0.04f * sin((ph * 0.7f + 1.4f).toDouble()).toFloat()
            val gl = (0.5f + 0.5f * sin((ph * 2.3f).toDouble()).toFloat())
            val bright = gl * gl * gl      // вспышка короче темноты
            smokePaint.color = amberBr
            smokePaint.alpha = (60f * bright).toInt().coerceIn(0, 255)
            c.drawCircle(fx, fy, h * 0.030f, smokePaint)
            smokePaint.alpha = (235f * bright).toInt().coerceIn(0, 255)
            c.drawCircle(fx, fy, 1.5f * d, smokePaint)
            i++
        }
    }

    private fun drawCamp(c: Canvas, w: Float, h: Float, r: Wobble, tint: Int, tintBr: Int) {
        val base = h * 0.90f
        val tn = sp(0)
        Doodle.tent(tn, w * 0.62f, base, w * 0.13f, h * 0.42f, r)
        Doodle.ink(c, tn, stroke(violetBr, 2f), 0.8f * d)
        // Пламя живёт: высота гуляет двумя несинхронными волнами, поэтому
        // не выглядит механическим маятником.
        val ph = BoilClock.phase
        val flick = 1f + 0.30f * sin((ph * 5.1f).toDouble()).toFloat() +
                    0.16f * sin((ph * 8.7f + 1.3f).toDouble()).toFloat()
        val logs = sp(1); val flame = sp(2)
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
        val trail = sp(3)
        Doodle.line(trail, w * 0.66f, base + 3f * d, w * 0.74f, base - 2f * d, 1.2f, 6, r)
        Doodle.line(trail, w * 0.74f, base - 2f * d, w * 0.80f, base + 2f * d, 1.2f, 6, r)
        Doodle.ink(c, trail, stroke(tint, 1f), 0.8f * d)
        stars(c, tintBr, listOf(Triple(0.88f, 0.16f, 0.10f)), w, h, r)

        // Воздух стоянки: дым от костра уводит общим ветром, в темноте
        // висят светлячки. Огонь уже нарисован - воздух ложится ПОВЕРХ.
        campAir(c, w, h, w * 0.30f, base - h * 0.10f)
    }

    /**
     * Профиль = ДОКУМЕНТ ПОД СКАНЕРОМ. Экран, где хранятся личные данные,
     * логично показать как паспорт: фото (нарочно неразборчивое - поверх
     * идёт штриховка), строки данных, штрих-код. Красный луч идёт сверху
     * вниз по петле и подсвечивает то, что пересекает.
     */
    /** Силуэт в фотографии: 3 разных лица (форма головы и плеч). */
    /**
     * @param slot номер пути в пуле. Слот передаётся ЯВНО, а не выводится
     * из idx: при переборе личности текущее и предыдущее лицо живут в
     * кадре одновременно, и вывод из idx однажды дал бы им один путь -
     * второй вызов затёр бы первый. Такая ошибка проявилась бы ровно на
     * одном из трёх лиц и только на доле секунды глитча.
     */
    private fun facePath(px: Float, py: Float, pw: Float, phh: Float, idx: Int,
                         slot: Int): Path {
        val p = sp(slot)
        val fcx = px + pw / 2f
        val hy = py + phh * 0.36f
        val hr = pw * (0.20f + 0.03f * idx)
        when (idx) {
            0 -> p.addCircle(fcx, hy, hr, Path.Direction.CW)
            1 -> {
                p.addOval(fcx - hr * 0.85f, hy - hr * 1.15f,
                    fcx + hr * 0.85f, hy + hr * 1.05f, Path.Direction.CW)
                p.addRect(fcx - hr * 0.92f, hy - hr * 1.30f,
                    fcx + hr * 0.92f, hy - hr * 0.55f, Path.Direction.CW)
            }
            else -> {
                p.moveTo(fcx - hr, hy - hr * 0.60f)
                p.lineTo(fcx, hy - hr * 1.15f)
                p.lineTo(fcx + hr, hy - hr * 0.60f)
                p.lineTo(fcx + hr * 0.75f, hy + hr * 0.95f)
                p.lineTo(fcx - hr * 0.75f, hy + hr * 0.95f)
                p.close()
            }
        }
        p.addOval(fcx - pw * 0.40f, py + phh * 0.66f,
            fcx + pw * 0.40f, py + phh * 1.30f, Path.Direction.CW)
        return p
    }

    private fun drawPassport(c: Canvas, w: Float, h: Float, r: Wobble) {
        val cx = w * 0.72f
        val cy = h * 0.50f
        val dw = w * 0.44f
        val dh = h * 0.76f

        scanBeam(c, w, h, cx, dw)
        val doc = sp(0); val photo = sp(1); val bars = sp(2)
        Doodle.passport(doc, photo, bars, cx, cy, dw, dh, r)
        Doodle.ink(c, doc, stroke(violet, 2f), 0.8f * d)
        Doodle.ink(c, photo, stroke(violetBr, 1.4f), 0.8f * d)
        c.drawPath(bars, fill(violetBr, 150))

        // Личность в базе «перебирается»: силуэт меняется с коротким глитчем.
        run {
            val px = cx - dw / 2f + dw * 0.04f
            val py = cy - dh / 2f + dh * 0.06f
            val pw = dw * 0.26f
            val phh = dh * 0.52f
            val tt = loop(6f, 0f) * 3f
            val idx = tt.toInt().coerceIn(0, 2)
            val frac = tt - idx
            val g = if (frac < 0.14f) 1f - frac / 0.14f else 0f
            val sil = facePath(px, py, pw, phh, idx, 4)
            c.save()
            c.clipRect(px, py, px + pw, py + phh)
            if (g > 0f) {
                val prev = facePath(px, py, pw, phh, (idx + 2) % 3, 5)
                c.drawPath(prev, fill(red, (95f * g).toInt().coerceIn(0, 255)))
            }
            val slices = 5
            for (k in 0 until slices) {
                val y0 = py + phh * k / slices
                val y1 = py + phh * (k + 1) / slices
                val off = g * 4.5f * d *
                    sin((BoilClock.phase * 9f + k * 2.1f).toDouble()).toFloat()
                c.save()
                c.clipRect(px, y0, px + pw, y1)
                c.translate(off, 0f)
                c.drawPath(sil, fill(violetBr, 180))
                c.restore()
            }
            c.restore()
        }

        val corners = sp(3)
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
    /** Кубик-день: передняя грань, светлый верх, тёмный бок - объём. */
    private fun cubeIso(c: Canvas, cx: Float, byY: Float, sz: Float, tint: Int) {
        val hf = sz / 2f; val dp2 = sz * 0.30f
        val front = sp(6)
        front.addRect(cx - hf, byY - sz, cx + hf, byY, Path.Direction.CW)
        skyFill.color = tint; skyFill.alpha = 225; c.drawPath(front, skyFill)
        val top = sp(7)
        top.moveTo(cx - hf, byY - sz); top.lineTo(cx - hf + dp2, byY - sz - dp2)
        top.lineTo(cx + hf + dp2, byY - sz - dp2); top.lineTo(cx + hf, byY - sz); top.close()
        skyFill.color = lightenC(tint, 0.35f); skyFill.alpha = 235; c.drawPath(top, skyFill)
        val side = scPathC
        side.reset()
        side.moveTo(cx + hf, byY - sz); side.lineTo(cx + hf + dp2, byY - sz - dp2)
        side.lineTo(cx + hf + dp2, byY - dp2); side.lineTo(cx + hf, byY); side.close()
        skyFill.color = darkenC(tint, 0.40f); skyFill.alpha = 235; c.drawPath(side, skyFill)
        skyOutline.color = darkenC(tint, 0.65f)
        c.drawPath(front, skyOutline); c.drawPath(top, skyOutline); c.drawPath(side, skyOutline)
        skyFill.alpha = 255
    }

    private fun drawStats(c: Canvas, w: Float, h: Float, r: Wobble) {
        val base = h * 0.92f
        mountainsRich(c, w * 0.46f, base, w * 0.46f, h * 0.72f, r, blue)
        val rv = sp(0)
        Doodle.river(rv, w * 0.05f, h * 0.72f, w * 0.60f, base, r)
        Doodle.ink(c, rv, stroke(blueBr, 2f), 0.8f * d)
        firRich(c, w * 0.14f, base, h * 0.36f, r)
        firRich(c, w * 0.90f, base, h * 0.42f, r)
        drifting(c, w, h, r, violet, listOf(Triple(0.16f, 0.10f, 30f)))
        // Взгляд сверху и издалека - как и сама Аналитика.
        birdFlock(c, w, h)


        // Кубики-дни: рекорд и провал. У каждого кубика своя роль и свой
        // цвет - янтарь и жёлтый в стопке (обычные дни), ЗЕЛЁНЫЙ в руках
        // (лучший день), КРАСНЫЙ по ветру (худший). Красный сбивает только
        // что поставленный рекорд, и оба кубика достаются носильщику.
        run {
            val ph3 = BoilClock.phase
            val sz = h * 0.15f
            val stacks = stackHeights
            val x0 = w * 0.10f; val step = w * 0.115f
            val dropX = x0 + 3 * step
            val t = loop(9.5f, 0f)
            val stackTopY = base - stacks[3] * sz
            val carryY = base - sz * 0.85f

            // Ветер поднимается перед прилётом красного и стихает после.
            val wind = when {
                t < 0.46f -> 0f
                t < 0.58f -> (t - 0.46f) / 0.12f
                t < 0.88f -> 1f
                t < 0.97f -> 1f - (t - 0.88f) / 0.09f
                else -> 0f
            }

            // Обычные дни: янтарь и жёлтый, чередуются - стопки различимы.
            for (i in stacks.indices) {
                val cx = x0 + i * step
                for (k in 0 until stacks[i]) {
                    cubeIso(c, cx, base - k * sz, sz, if ((i + k) % 2 == 0) amber else amberBr)
                }
            }

            // Потоки ветра, сор и клубы пыли.
            if (wind > 0.02f) {
                for (k in 0 until 9) {
                    var g = (ph3 * 1.35f + k * 0.1111f) % 1f
                    if (g < 0f) g += 1f
                    val wy = h * (0.14f + 0.72f * ((k * 37) % 9) / 8f)
                    val wx = w * 1.05f - w * 1.25f * g
                    val len = w * (0.06f + 0.09f * ((k * 53) % 5) / 4f) * wind
                    val a2 = (150f * wind * (1f - kotlin.math.abs(0.5f - g) * 1.4f)).toInt().coerceIn(0, 255)
                    c.drawLine(wx, wy, wx + len, wy - 2f * d, stroke(blueBr, 1.6f, a2))
                }
                // Клубы пыли у земли: катятся по ветру и разрастаются.
                for (k in 0 until 4) {
                    var g = (ph3 * 0.55f + k * 0.25f) % 1f
                    if (g < 0f) g += 1f
                    val px2 = w * 1.10f - w * 1.30f * g
                    val py2 = base - h * 0.02f - h * 0.05f * g
                    val rr2 = h * (0.05f + 0.16f * g)
                    val a3 = (95f * wind * (1f - g)).toInt().coerceIn(0, 255)
                    c.drawCircle(px2, py2, rr2, fill(gray, a3))
                    c.drawCircle(px2 + rr2 * 0.55f, py2 - rr2 * 0.35f, rr2 * 0.66f, fill(gray, (a3 * 0.75f).toInt()))
                    c.drawCircle(px2 - rr2 * 0.60f, py2 - rr2 * 0.20f, rr2 * 0.55f, fill(gray, (a3 * 0.6f).toInt()))
                }
            }

            // ---- Носильщик ----
            var fx: Float; var fy = base; var rot = 0f; var pose = 0
            var visible = true; var carry = true
            when {
                t < 0.34f -> { fx = w * 0.95f - (w * 0.95f - dropX) * (t / 0.34f) }
                t < 0.42f -> { fx = dropX; pose = 1 }                 // ставит рекорд
                t < 0.70f -> {                                        // уходит влево
                    val fr = (t - 0.42f) / 0.28f
                    fx = dropX - (dropX - w * 0.32f) * fr; carry = false; pose = 2
                }
                t < 0.78f -> {                                        // первый удар
                    val fr = (t - 0.70f) / 0.08f
                    fx = w * 0.32f - w * 0.05f * fr
                    fy = base - h * 0.08f * kotlin.math.sin((Math.PI * fr).toDouble()).toFloat()
                    rot = -60f * fr; carry = false; pose = 3
                }
                t < 0.94f -> {                                        // второй, сильнее
                    val fr = (t - 0.78f) / 0.16f
                    fx = w * 0.27f - w * 0.42f * fr
                    fy = base - h * 0.34f * kotlin.math.sin((Math.PI * fr).toDouble()).toFloat()
                    rot = -60f - 460f * fr; carry = false; pose = 3
                    visible = fx > -w * 0.12f
                }
                else -> { fx = -w * 0.2f; visible = false; carry = false }
            }

            // ---- Зелёный кубик: рекорд ----
            if (t < 0.34f) {
                val bob = 1.2f * d * sin((ph3 * 6f).toDouble()).toFloat()
                cubeIso(c, fx - sz * 0.45f, carryY + bob, sz, green)
            } else if (t < 0.42f) {
                val fr = (t - 0.34f) / 0.08f
                val fromX = dropX - sz * 0.45f
                cubeIso(c, fromX + (dropX - fromX) * fr,
                    carryY + (stackTopY - carryY) * fr, sz, green)
            } else if (t < 0.66f) {
                cubeIso(c, dropX, stackTopY, sz, green)               // стоит на стопке
            } else if (t < 0.78f) {                                   // сбит, летит в затылок
                val fr = (t - 0.66f) / 0.12f
                val gx = dropX - (dropX - w * 0.30f) * (fr * fr)
                val gy = stackTopY - h * 0.14f * kotlin.math.sin((Math.PI * fr).toDouble()).toFloat()
                c.save(); c.translate(gx, gy); c.rotate(340f * fr)
                cubeIso(c, 0f, sz * 0.5f, sz, green); c.restore()
            } else if (t < 0.94f) {                                   // улетает вместе с ним
                val fr = (t - 0.78f) / 0.16f
                c.save()
                c.translate(w * 0.30f - w * 0.40f * fr,
                    stackTopY - h * 0.10f - h * 0.16f * kotlin.math.sin((Math.PI * fr * 0.8f).toDouble()).toFloat())
                c.rotate(340f + 300f * fr)
                cubeIso(c, 0f, sz * 0.5f, sz, green); c.restore()
            }

            // ---- Красный кубик: провал, прилетает по ветру ----
            if (t >= 0.54f && t < 0.94f) {
                val rx: Float; val ry: Float; val rr3: Float
                if (t < 0.66f) {                                      // подлетает справа
                    val fr = (t - 0.54f) / 0.12f
                    rx = w * 1.15f - (w * 1.15f - (dropX + sz * 0.9f)) * fr
                    ry = stackTopY - h * 0.05f * kotlin.math.sin((Math.PI * fr).toDouble()).toFloat()
                    rr3 = 420f * fr
                } else if (t < 0.82f) {                               // догоняет носильщика
                    val fr = (t - 0.66f) / 0.16f
                    rx = dropX + sz * 0.9f - (dropX + sz * 0.9f - w * 0.26f) * (fr * fr)
                    ry = stackTopY - h * 0.10f * kotlin.math.sin((Math.PI * fr).toDouble()).toFloat()
                    rr3 = 420f + 380f * fr
                } else {                                              // добивает и уносит
                    val fr = (t - 0.82f) / 0.12f
                    rx = w * 0.26f - w * 0.40f * fr
                    ry = stackTopY - h * 0.06f - h * 0.20f * kotlin.math.sin((Math.PI * fr * 0.9f).toDouble()).toFloat()
                    rr3 = 800f + 420f * fr
                }
                c.save(); c.translate(rx, ry); c.rotate(rr3)
                cubeIso(c, 0f, sz * 0.5f, sz, red); c.restore()
            }

            // ---- Вспышки ударов ----
            if (t in 0.64f..0.70f) {                                  // красный по зелёному
                val fr = (t - 0.64f) / 0.06f
                c.drawCircle(dropX, stackTopY - sz * 0.5f, (6f + 26f * fr) * d,
                    fill(amberBr, (200f * (1f - fr)).toInt().coerceIn(0, 255)))
            }
            if (t in 0.76f..0.82f) {                                  // зелёный по затылку
                val fr = (t - 0.76f) / 0.06f
                c.drawCircle(w * 0.30f, base - sz * 1.7f, (5f + 22f * fr) * d,
                    fill(green, (190f * (1f - fr)).toInt().coerceIn(0, 255)))
            }

            if (visible) {
                c.save(); c.translate(fx, fy); if (rot != 0f) c.rotate(rot)
                val stepPh = sin((ph3 * (if (pose == 2) 10f else 7f)).toDouble()).toFloat()
                val fig = sp(1)
                when (pose) {
                    1 -> {
                        fig.moveTo(0f, -sz * 1.30f); fig.lineTo(0f, -sz * 0.60f)
                        fig.moveTo(0f, -sz * 0.60f); fig.lineTo(-3.4f * d, 0f)
                        fig.moveTo(0f, -sz * 0.60f); fig.lineTo(3.4f * d, 0f)
                        fig.moveTo(0f, -sz * 1.15f); fig.lineTo(5f * d, -sz * 1.55f)
                    }
                    3 -> {
                        fig.moveTo(0f, -sz * 1.00f); fig.lineTo(0f, -sz * 0.40f)
                        fig.moveTo(0f, -sz * 0.40f); fig.lineTo(-4.4f * d, sz * 0.20f)
                        fig.moveTo(0f, -sz * 0.40f); fig.lineTo(3.6f * d, sz * 0.34f)
                        fig.moveTo(0f, -sz * 0.90f); fig.lineTo(-5f * d, -sz * 0.50f)
                        fig.moveTo(0f, -sz * 0.90f); fig.lineTo(4.6f * d, -sz * 1.20f)
                    }
                    else -> {
                        fig.moveTo(0f, -sz * 1.55f); fig.lineTo(0f, -sz * 0.72f)
                        fig.moveTo(0f, -sz * 0.72f); fig.lineTo(-2.8f * d - 2f * d * stepPh, 0f)
                        fig.moveTo(0f, -sz * 0.72f); fig.lineTo(2.8f * d + 2f * d * stepPh, 0f)
                        if (carry) {
                            fig.moveTo(0f, -sz * 1.30f); fig.lineTo(-sz * 0.50f, -sz * 1.05f)
                            fig.moveTo(0f, -sz * 1.15f); fig.lineTo(-sz * 0.52f, -sz * 0.55f)
                        } else { fig.moveTo(0f, -sz * 1.35f); fig.lineTo(-4.4f * d, -sz * 1.05f) }
                    }
                }
                val headY = if (pose == 3) -sz * 1.25f else if (pose == 1) -sz * 1.55f else -sz * 1.80f
                c.drawCircle(0f, headY, 2.8f * d, fill(amberBr, 240))
                Doodle.ink(c, fig, stroke(amberBr, 2f, 240), 0.6f * d)
                c.restore()
            }
        }

        stars(c, blueBr, listOf(Triple(0.32f, 0.20f, 0.09f), Triple(0.70f, 0.12f, 0.06f)), w, h, r)
    }

    /** Timeline: солнце сияет, облака плывут, указатель на распутье. */
    private fun drawTimeline(c: Canvas, w: Float, h: Float, r: Wobble) {
        val base = h * 0.92f
        val k = 0.9f + 0.2f * sin((BoilClock.phase * 1.1f).toDouble()).toFloat()
        sunRich(c, w * 0.60f, h * 0.26f, h * 0.13f * k, r, amber)
        drifting(c, w, h, r, violet, listOf(
            Triple(0.20f, 0.12f, 22f), Triple(0.34f, 0.09f, 31f)))
        // Тени бегут по земле раньше всего остального: они лежат ПОД
        // деревьями и указателем, иначе фокус ломается.
        cloudShadows(c, w, h, base)
        val post = sp(0)
        Doodle.signpost(post, w * 0.86f, base, h * 0.50f, r)
        Doodle.ink(c, post, stroke(amberBr, 2f), 0.8f * d)
        firRich(c, w * 0.10f, base, h * 0.48f, r)
        firRich(c, w * 0.20f, base, h * 0.36f, r)

        // Сёрф по волнам: разбег -> подъём прыжками по водяным колоннам с
        // доской и брызгами -> срыв и падение с ускорением -> подхват
        // огненным столбом (краснеет ровно в момент касания) -> доску
        // уносит, его выбрасывает вверх. Цикл 7.5 с.
        run {
            val ph2 = BoilClock.phase
            val n = 5
            val bx0 = w * 0.05f; val bw = w * 0.055f; val gap = w * 0.017f
            val tops = surfTops; val cxs = surfCxs
            for (i in 0 until n) {
                val bh = h * (0.13f + 0.095f * i)
                val x0 = bx0 + i * (bw + gap)
                tops[i] = base - bh; cxs[i] = x0 + bw / 2f
                c.drawRect(x0, tops[i], x0 + bw, base, fill(blue, 105))
                c.save()
                c.clipRect(x0, tops[i] - 3f * d, x0 + bw, base)
                val wave = sp(1)
                val amp = 1.8f * d
                wave.moveTo(x0, tops[i] + amp)
                var xx = x0
                while (xx <= x0 + bw) {
                    val k2 = (xx - x0) / bw
                    wave.lineTo(xx, tops[i] + amp * sin((ph2 * 2.6f + k2 * 6.2f + i).toDouble()).toFloat())
                    xx += 2f * d
                }
                wave.lineTo(x0 + bw, tops[i] + 6f * d); wave.lineTo(x0, tops[i] + 6f * d)
                wave.close()
                c.drawPath(wave, fill(blueBr, 155))
                for (k in 0 until 3) {
                    var g = (ph2 * 0.38f + k * 0.33f + i * 0.17f) % 1f
                    if (g < 0f) g += 1f
                    val by2 = base - (base - tops[i]) * g
                    val bxp = x0 + bw * (0.28f + 0.44f * ((k + i) % 3) / 2f)
                    c.drawCircle(bxp, by2, (0.9f + 0.8f * (1f - g)) * d,
                        fill(blueBr, (150f * (1f - g)).toInt().coerceIn(0, 255)))
                }
                c.restore()
                val bp = sp(2)
                Doodle.roundRect(bp, x0, tops[i], bw, bh, 2f * d, 1f * d, r)
                Doodle.ink(c, bp, stroke(blueBr, 1.6f, 175), 0.7f * d)
            }

            val t = loop(6.5f, 0f)
            val fireX = bx0 + n * (bw + gap) + bw / 2f
            // Подхват происходит НА ВЕРШИНЕ столба, а не у земли - иначе
            // герой выглядел проваливающимся внутрь огня.
            val fireFullH = h * 0.26f
            val contactY = base - fireFullH
            val boardTone = green

            // Огненный столб успевает вырасти ровно к падению.
            val grow = when {
                t < 0.50f -> 0f
                t < 0.64f -> (t - 0.50f) / 0.14f
                t < 0.90f -> 1f
                t < 0.98f -> 1f - (t - 0.90f) / 0.08f
                else -> 0f
            }
            if (grow > 0.02f) {
                val fh = fireFullH * grow
                val fx0 = fireX - bw / 2f
                c.drawRect(fx0, base - fh, fx0 + bw, base, fill(red, 125))
                val fl = 0.7f + 0.3f * sin((ph2 * 8f).toDouble()).toFloat()
                val tongue = sp(3)
                tongue.moveTo(fx0, base - fh)
                tongue.quadTo(fx0 + bw * 0.25f, base - fh - 10f * d * fl,
                    fx0 + bw * 0.5f, base - fh - 3f * d)
                tongue.quadTo(fx0 + bw * 0.75f, base - fh - 13f * d * fl,
                    fx0 + bw, base - fh)
                tongue.close()
                c.drawPath(tongue, fill(amberBr, 215))
                val fp = sp(4)
                Doodle.roundRect(fp, fx0, base - fh, bw, fh, 2f * d, 1f * d, r)
                Doodle.ink(c, fp, stroke(red, 1.8f, 200), 0.7f * d)
                for (k in 0 until 5) {
                    var g = (ph2 * 1.1f + k * 0.2f) % 1f
                    if (g < 0f) g += 1f
                    c.drawCircle(fireX + 6f * d * sin((g * 6f + k).toDouble()).toFloat(),
                        base - fh - 5f * d - g * h * 0.34f, 1.5f * d,
                        fill(amberBr, (215f * (1f - g) * grow).toInt().coerceIn(0, 255)))
                }
            }

            // Траектория героя по фазам.
            var fx: Float; var fy: Float
            var onBoard = false; var lean = 0f; var visible = true
            var splash = 0f
            if (t < 0.12f) {                       // Приземление на волну:
                // падает из-за верхнего края и встаёт на первый гребень.
                val fr = t / 0.12f
                fx = cxs[0] - w * 0.05f * (1f - fr)
                fy = (tops[0] - h * 1.5f) + (h * 1.5f) * (fr * fr)
                onBoard = fr > 0.55f
                lean = 10f * (1f - fr)
                if (fr > 0.86f) splash = (fr - 0.86f) / 0.14f
            } else if (t < 0.55f) {                // сёрф вверх по волнам
                val u = (t - 0.12f) / 0.43f * (n - 1)
                val i0 = u.toInt().coerceIn(0, n - 2)
                val fr = u - i0
                fx = cxs[i0] + (cxs[i0 + 1] - cxs[i0]) * fr
                val arc = 13f * d * sin((Math.PI * fr).toDouble()).toFloat()
                fy = tops[i0] + (tops[i0 + 1] - tops[i0]) * fr - arc
                onBoard = true
                lean = -16f * sin((Math.PI * fr).toDouble()).toFloat()
                if (fr < 0.16f) splash = 1f - fr / 0.16f
            } else if (t < 0.66f) {                // срыв и падение с ускорением
                val fr = (t - 0.55f) / 0.11f
                fx = cxs[n - 1] + (fireX - cxs[n - 1]) * fr
                fy = tops[n - 1] + (contactY - tops[n - 1]) * (fr * fr)
                onBoard = true
                lean = 22f * fr
            } else {                               // мгновенный выброс огнём
                val fr = (t - 0.66f) / 0.28f
                fx = fireX + w * 0.04f * fr
                // Импульс максимален СРАЗУ и затем гасится - подброс, а не
                // плавный подъём (раньше старт был вялым).
                val up = 1f - (1f - fr) * (1f - fr)
                fy = contactY - (h * 2.3f) * up
                lean = -34f * fr
                visible = fy > -h * 0.7f
            }

            // Доска: под ногами, а после подхвата улетает вправо с вращением.
            if (onBoard || t >= 0.66f) {
                c.save()
                if (t < 0.66f) {
                    c.translate(fx, fy + 1.5f * d); c.rotate(lean * 0.5f)
                    c.drawRoundRect(-9f * d, -1.8f * d, 9f * d, 1.8f * d,
                        1.8f * d, 1.8f * d, fill(boardTone, 245))
                    c.drawLine(-9f * d, 0f, 9f * d, 0f, stroke(lightenC(green, 0.40f), 1.4f, 220))
                } else {
                    val fr = ((t - 0.66f) / 0.34f).coerceAtMost(1f)
                    val bxx = fireX + w * 0.55f * fr
                    val byy = contactY - h * 0.55f * fr + h * 0.9f * fr * fr
                    c.translate(bxx, byy); c.rotate(760f * fr)
                    val al = (255f * (1f - fr)).toInt().coerceIn(0, 255)
                    c.drawRoundRect(-9f * d, -1.8f * d, 9f * d, 1.8f * d,
                        1.8f * d, 1.8f * d, fill(boardTone, al))
                }
                c.restore()
            }

            // Брызги в момент касания гребня.
            if (splash > 0f) {
                for (k in 0 until 5) {
                    val a2 = Math.PI * (0.15 + 0.7 * k / 4.0)
                    val rr2 = (5f + 9f * (1f - splash)) * d
                    c.drawCircle(fx - rr2 * kotlin.math.cos(a2).toFloat(),
                        fy + 2f * d - rr2 * kotlin.math.sin(a2).toFloat() * 0.8f,
                        1.5f * d, fill(blueBr, (200f * splash).toInt().coerceIn(0, 255)))
                }
            }

            if (visible) {
                // Краснеет РОВНО с касанием огня, не раньше.
                val tint = if (t >= 0.66f) red else amberBr
                c.save()
                c.translate(fx, fy); c.rotate(lean * 0.35f)
                val stride = 3f * d * sin((ph2 * 9f).toDouble()).toFloat()
                val fig = sp(5)
                fig.moveTo(0f, -5.5f * d); fig.lineTo(0f, -1.5f * d)
                if (t >= 0.66f) {                  // выброс: ноги поджаты, руки вверх
                    fig.moveTo(0f, -1.5f * d); fig.lineTo(-3.4f * d, 1.4f * d)
                    fig.moveTo(0f, -1.5f * d); fig.lineTo(3.4f * d, 1.4f * d)
                    fig.moveTo(0f, -4.6f * d); fig.lineTo(-4.2f * d, -8.6f * d)
                    fig.moveTo(0f, -4.6f * d); fig.lineTo(4.2f * d, -8.6f * d)
                } else if (onBoard) {              // стойка сёрфера
                    fig.moveTo(0f, -1.5f * d); fig.lineTo(-4.2f * d, 1.2f * d)
                    fig.moveTo(0f, -1.5f * d); fig.lineTo(3.6f * d, 1.2f * d)
                    fig.moveTo(0f, -4.4f * d); fig.lineTo(-5.4f * d, -6.6f * d)
                    fig.moveTo(0f, -4.4f * d); fig.lineTo(5.4f * d, -5.4f * d)
                } else {                           // бег
                    fig.moveTo(0f, -1.5f * d); fig.lineTo(-2.6f * d - stride, 0f)
                    fig.moveTo(0f, -1.5f * d); fig.lineTo(2.6f * d + stride, 0f)
                    fig.moveTo(0f, -4.4f * d); fig.lineTo(4.4f * d, -6.2f * d)
                }
                c.drawCircle(0f, -8f * d, 2.6f * d, fill(tint, 245))
                Doodle.ink(c, fig, stroke(tint, 2f, 245), 0.6f * d)
                c.restore()
            }
        }

        firRich(c, w * 0.72f, base, h * 0.40f, r)
        stars(c, amberBr, listOf(Triple(0.44f, 0.14f, 0.07f)), w, h, r)
    }

    /** Калибровка: точность - это механизм. Шестерни КРУТЯТСЯ, песок сыплется. */
    /** Литая шестерня: тело с заливкой, ступица, спицы, слабое свечение. */
    private fun gearRich(c: Canvas, cx: Float, cy: Float, rad: Float, teeth: Int,
                         rotDeg: Float, w: Wobble, tint: Int) {
        val body = sp(0)
        Doodle.gear(body, cx, cy, rad, teeth, rotDeg, w)
        skyFill.color = darkenC(tint, 0.55f); skyFill.alpha = 210
        c.drawPath(body, skyFill)
        skyFill.color = tint; skyFill.alpha = 40
        c.drawCircle(cx, cy, rad * 1.15f, skyFill)
        skyOutline.color = lightenC(tint, 0.20f)
        Doodle.ink(c, body, skyOutline, 0.6f * d)
        // Ступица и спицы: колесо читается как деталь, а не как звёздочка.
        skyFill.color = darkenC(tint, 0.25f); skyFill.alpha = 235
        c.drawCircle(cx, cy, rad * 0.30f, skyFill)
        skyFill.color = lightenC(tint, 0.35f); skyFill.alpha = 220
        c.drawCircle(cx, cy, rad * 0.12f, skyFill)
        rayPaint.color = lightenC(tint, 0.15f); rayPaint.alpha = 190
        rayPaint.strokeWidth = 1.8f * d
        for (k in 0 until 4) {
            val a2 = Math.toRadians((rotDeg + k * 90f).toDouble())
            c.drawLine(cx + rad * 0.26f * kotlin.math.cos(a2).toFloat(),
                cy + rad * 0.26f * kotlin.math.sin(a2).toFloat(),
                cx + rad * 0.74f * kotlin.math.cos(a2).toFloat(),
                cy + rad * 0.74f * kotlin.math.sin(a2).toFloat(), rayPaint)
        }
        skyFill.alpha = 255
    }

    /**
     * Компас: стрелка качается и успокаивается на севере (затухающие
     * колебания), по ободу румбы, поверх - блик стекла.
     */
    private fun compassRich(c: Canvas, cx: Float, cy: Float, rad: Float, tint: Int) {
        val ph = BoilClock.phase
        skyFill.color = tint; skyFill.alpha = 34
        c.drawCircle(cx, cy, rad * 1.5f, skyFill)
        skyFill.color = darkenC(tint, 0.60f); skyFill.alpha = 225
        c.drawCircle(cx, cy, rad, skyFill)
        val ring = sp(1); ring.addCircle(cx, cy, rad, Path.Direction.CW)
        skyOutline.color = lightenC(tint, 0.30f); Doodle.ink(c, ring, skyOutline, 0.6f * d)
        // Румбы.
        rayPaint.color = lightenC(tint, 0.20f); rayPaint.alpha = 185
        rayPaint.strokeWidth = 1.6f * d
        for (k in 0 until 8) {
            val a2 = Math.toRadians((k * 45f).toDouble())
            val inR = if (k % 2 == 0) rad * 0.72f else rad * 0.84f
            c.drawLine(cx + inR * kotlin.math.cos(a2).toFloat(),
                cy + inR * kotlin.math.sin(a2).toFloat(),
                cx + rad * 0.94f * kotlin.math.cos(a2).toFloat(),
                cy + rad * 0.94f * kotlin.math.sin(a2).toFloat(), rayPaint)
        }
        // Стрелка: цикл 9 с - рыскает, затем затухает к северу.
        val t = loop(9f, 0f)
        val decay = kotlin.math.exp((-3.0 * t)).toFloat()
        val ang = -90f + 52f * decay * kotlin.math.sin((t * 26f).toDouble()).toFloat()
        val ar = Math.toRadians(ang.toDouble())
        val bx = cx + rad * 0.70f * kotlin.math.cos(ar).toFloat()
        val by = cy + rad * 0.70f * kotlin.math.sin(ar).toFloat()
        val sx = cx - rad * 0.62f * kotlin.math.cos(ar).toFloat()
        val sy = cy - rad * 0.62f * kotlin.math.sin(ar).toFloat()
        val px = -kotlin.math.sin(ar).toFloat() * rad * 0.16f
        val py = kotlin.math.cos(ar).toFloat() * rad * 0.16f
        val nd = sp(2)
        nd.moveTo(bx, by); nd.lineTo(cx + px, cy + py); nd.lineTo(cx - px, cy - py); nd.close()
        skyFill.color = 0xFFE23636.toInt(); skyFill.alpha = 240; c.drawPath(nd, skyFill)
        val sd = sp(3)
        sd.moveTo(sx, sy); sd.lineTo(cx + px, cy + py); sd.lineTo(cx - px, cy - py); sd.close()
        skyFill.color = 0xFFDCE4F2.toInt(); skyFill.alpha = 225; c.drawPath(sd, skyFill)
        skyFill.color = lightenC(tint, 0.55f); skyFill.alpha = 240
        c.drawCircle(cx, cy, rad * 0.10f, skyFill)
        // Блик стекла.
        val gl = 0.5f + 0.5f * kotlin.math.sin((ph * 1.2f).toDouble()).toFloat()
        rayPaint.color = 0xFFFFFFFF.toInt(); rayPaint.alpha = (45f + 70f * gl).toInt().coerceIn(0, 255)
        rayPaint.strokeWidth = 2.2f * d
        c.drawLine(cx - rad * 0.55f, cy - rad * 0.52f, cx - rad * 0.05f, cy - rad * 0.80f, rayPaint)
        skyFill.alpha = 255
    }

    /**
     * Песочные часы: конус песка сверху, растущая горка снизу и отдельные
     * падающие песчинки (линия-«струйка» читалась как палка). Когда песок
     * пересыпался - часы плавно переворачиваются, и цикл идёт заново.
     */
    private fun hourglassRich(c: Canvas, cx: Float, cy: Float, ww: Float, hh: Float, tint: Int) {
        val period = 10f
        val t = loop(period, 0f) * period
        val pour = 8.5f
        val fr = if (t > pour) (t - pour) / (period - pour) else 0f
        val flip = fr * fr * (3f - 2f * fr)   // плавный старт и остановка
        val fillTop = if (t <= pour) 1f - t / pour else 0f
        val botFrac = 1f - fillTop

        c.save()
        c.rotate(180f * flip, cx, cy)

        val top = cy - hh / 2f; val bot = cy + hh / 2f
        val hw = ww / 2f; val neck = ww * 0.07f; val half = hh / 2f

        val glass = sp(4)
        glass.moveTo(cx - hw, top); glass.lineTo(cx + hw, top)
        glass.lineTo(cx + neck, cy); glass.lineTo(cx + hw, bot)
        glass.lineTo(cx - hw, bot); glass.lineTo(cx - neck, cy)
        glass.close()
        skyFill.color = lightenC(tint, 0.60f); skyFill.alpha = 26
        c.drawPath(glass, skyFill)

        c.save(); c.clipPath(glass)
        if (fillTop > 0.02f) {
            val fh = half * fillTop
            val yTop = cy - fh
            val wTop = neck + (hw - neck) * fillTop
            val sand = sp(5)
            sand.moveTo(cx - wTop, yTop); sand.lineTo(cx + wTop, yTop)
            sand.lineTo(cx + neck, cy); sand.lineTo(cx - neck, cy); sand.close()
            skyFill.color = tint; skyFill.alpha = 215; c.drawPath(sand, skyFill)
            skyFill.color = lightenC(tint, 0.45f); skyFill.alpha = 120
            c.drawRect(cx - wTop, yTop, cx + wTop, yTop + 1.6f * d, skyFill)
        }
        val moundH = half * 0.80f * botFrac
        if (botFrac > 0.02f) {
            val mw = hw * (0.45f + 0.55f * botFrac)
            val mp = sp(6)
            mp.moveTo(cx - mw, bot); mp.lineTo(cx + mw, bot)
            mp.lineTo(cx + mw * 0.34f, bot - moundH)
            mp.lineTo(cx, bot - moundH * 1.18f)
            mp.lineTo(cx - mw * 0.34f, bot - moundH)
            mp.close()
            skyFill.color = tint; skyFill.alpha = 225; c.drawPath(mp, skyFill)
        }
        // Песчинки: падают от горла до вершины горки, вразнобой.
        if (fillTop > 0.02f && flip <= 0f) {
            val ph = BoilClock.phase
            val landY = bot - moundH * 1.18f
            for (k in 0 until 9) {
                var g = (ph * 1.7f + k * 0.1111f) % 1f
                if (g < 0f) g += 1f
                val gy = cy + (landY - cy) * g
                val gx = cx + 1.3f * d * kotlin.math.sin((ph * 8f + k * 1.7f).toDouble()).toFloat()
                val a2 = if (g > 0.88f) (1f - g) / 0.12f else 1f
                skyFill.color = lightenC(tint, 0.35f)
                skyFill.alpha = (230f * a2).toInt().coerceIn(0, 255)
                c.drawCircle(gx, gy, (0.9f + 0.5f * ((k % 3) / 2f)) * d, skyFill)
            }
        }
        c.restore()

        skyOutline.color = lightenC(tint, 0.35f)
        Doodle.ink(c, glass, skyOutline, 0.6f * d)
        // Блик стекла и деревянные оправы.
        rayPaint.color = 0xFFFFFFFF.toInt(); rayPaint.alpha = 95; rayPaint.strokeWidth = 1.8f * d
        c.drawLine(cx - hw * 0.62f, top + hh * 0.10f, cx - neck * 1.6f, cy - hh * 0.06f, rayPaint)
        skyFill.color = darkenC(tint, 0.55f); skyFill.alpha = 245
        c.drawRect(cx - hw * 1.10f, top - 3f * d, cx + hw * 1.10f, top + 1.5f * d, skyFill)
        c.drawRect(cx - hw * 1.10f, bot - 1.5f * d, cx + hw * 1.10f, bot + 3f * d, skyFill)
        skyFill.alpha = 255
        c.restore()
    }

    private fun drawCalibration(c: Canvas, w: Float, h: Float, r: Wobble) {
        val ph = BoilClock.phase
        gearRich(c, w * 0.16f, h * 0.46f, h * 0.30f, 8, ph * 22f, r, violet)
        // Вторая шестерня крутится В ОБРАТНУЮ сторону и быстрее - так и
        // работает зубчатая передача: меньше колесо - выше обороты.
        gearRich(c, w * 0.34f, h * 0.72f, h * 0.19f, 6, -ph * 33f, r, violetBr)
        // Искры бьют В ТОЧКЕ ЗАЦЕПЛЕНИЯ - между двумя колёсами, а не
        // где придётся: иначе это фейерверк, а не работающий механизм.
        gearSparks(c, w * 0.26f, h * 0.60f, h * 0.13f)
        // Песок пересыпается, затем часы переворачиваются (см. hourglassRich).
        hourglassRich(c, w * 0.62f, h * 0.50f, h * 0.34f, h * 0.62f, amber)
        // Компас: прибор поверки направления рядом с механизмом.
        compassRich(c, w * 0.87f, h * 0.46f, h * 0.30f, blueBr)
        stars(c, blueBr, listOf(Triple(0.72f, 0.86f, 0.06f)), w, h, r)
        dotPaint.color = violetBr
        c.drawCircle(w * 0.48f, h * 0.20f, 1.8f * d, dotPaint)
    }

    /** История: стопка тетрадей - нейтральный серый, экран архивный. */
    /**
     * Кино: побег с архивом. Цикл 10 с и семь тактов: вспышка-старт ->
     * бег со свитком под взрывами -> сальто в слоу-мо (камера наезжает,
     * мир обесцвечивается) -> приземление возвращает цвет -> печать в пол
     * и ледяная дорожка -> быстрое скольжение -> прыжок в портал ->
     * вспышка с глюком -> табличка SAVE -> снова вспышка и по кругу.
     *
     * Тряски нет намеренно: дрожащий кадр читался как дефект. Вместо неё
     * лёгкий наклон перспективы - тревога есть, читаемость цела.
     */
    private fun scrollAct(c: Canvas, w: Float, h: Float, r: Wobble) {
        val t = loop(10f, 0f)
        val ph = BoilClock.phase
        val ground = h * 0.90f
        val portalX = w * 0.84f

        // Слоу-мо: на сальто время идёт медленнее, камера ближе, мир серый.
        val slow = when {
            t < 0.20f -> 0f
            t < 0.24f -> (t - 0.20f) / 0.04f
            t < 0.38f -> 1f
            t < 0.43f -> 1f - (t - 0.38f) / 0.05f
            else -> 0f
        }

        c.save()
        // Мягкий сдвиг перспективы вместо тряски.
        val tilt = if (t < 0.78f) 0.010f * sin((ph * 1.7f).toDouble()).toFloat() else 0f
        c.skew(tilt, 0f)
        c.translate(-w * tilt * 0.5f, 0f)

        // ---- Позиция и поза героя ----
        var hx: Float; var hy = ground; var rot = 0f; var pose = 0
        var visible = true
        when {
            t < 0.06f -> { hx = -w * 0.10f; visible = false }         // вспышка-старт
            t < 0.20f -> { hx = -w * 0.10f + (w * 0.34f) * ((t - 0.06f) / 0.14f) }
            t < 0.40f -> {                                            // сальто в слоу-мо
                val fr = (t - 0.20f) / 0.20f
                hx = w * 0.24f + w * 0.16f * fr
                hy = ground - 36f * d * sin((Math.PI * fr).toDouble()).toFloat()
                rot = -360f * fr; pose = 1
            }
            t < 0.48f -> { hx = w * 0.40f + w * 0.08f * ((t - 0.40f) / 0.08f); pose = 2 }
            t < 0.74f -> {                                            // скольжение, быстро
                val fr = (t - 0.48f) / 0.26f
                hx = w * 0.48f + (portalX - w * 0.06f - w * 0.48f) * (fr * (2f - fr))
                pose = 3
            }
            t < 0.80f -> {
                val fr = (t - 0.74f) / 0.06f
                hx = portalX - w * 0.06f + w * 0.06f * fr
                hy = ground - 34f * d * sin((Math.PI * fr).toDouble()).toFloat()
                rot = -80f * fr; pose = 4
            }
            else -> { hx = portalX; visible = false }
        }

        // Камера наезжает на слоу-мо.
        if (slow > 0f) {
            val z = 1f + 0.28f * slow
            c.scale(z, z, hx, hy - h * 0.10f)
        }

        // ---- Взрывы: ядро, языки пламени, дым, обломки ----
        val blasts = floatArrayOf(0.07f, 0.26f, 0.50f)
        for ((bi, b0) in blasts.withIndex()) {
            val age = (t - b0) / 0.22f
            if (age < 0f || age > 1f) continue
            val bx = w * (0.02f + 0.24f * bi)
            val by = ground - h * 0.10f
            val rad = h * (0.10f + 0.62f * age)
            savePaint.style = Paint.Style.STROKE
            savePaint.strokeWidth = (4f - 3f * age) * d
            savePaint.color = amberBr
            savePaint.alpha = (185f * (1f - age)).toInt().coerceIn(0, 255)
            c.drawCircle(bx, by, rad, savePaint)
            savePaint.style = Paint.Style.FILL
            // языки пламени пожирают пространство вокруг ядра
            for (k in 0 until 9) {
                val a2 = Math.toRadians((k * 40f + age * 60f).toDouble())
                val fl = rad * (0.52f + 0.30f * kotlin.math.sin((ph * 9f + k).toDouble()).toFloat())
                val tongue = sp(0)
                val tx = bx + fl * kotlin.math.cos(a2).toFloat()
                val ty = by + fl * kotlin.math.sin(a2).toFloat()
                tongue.moveTo(bx, by)
                tongue.quadTo(bx + fl * 0.6f * kotlin.math.cos(a2 - 0.4).toFloat(),
                    by + fl * 0.6f * kotlin.math.sin(a2 - 0.4).toFloat(), tx, ty)
                tongue.quadTo(bx + fl * 0.6f * kotlin.math.cos(a2 + 0.4).toFloat(),
                    by + fl * 0.6f * kotlin.math.sin(a2 + 0.4).toFloat(), bx, by)
                savePaint.color = if (k % 2 == 0) red else amberBr
                savePaint.alpha = (150f * (1f - age)).toInt().coerceIn(0, 255)
                c.drawPath(tongue, savePaint)
            }
            savePaint.color = amberBr; savePaint.alpha = (235f * (1f - age * 1.25f)).toInt().coerceIn(0, 255)
            c.drawCircle(bx, by, rad * 0.28f, savePaint)
            // дым поднимается следом
            for (k in 0 until 4) {
                val g = (age + k * 0.18f) % 1f
                savePaint.color = gray; savePaint.alpha = (110f * (1f - g) * (1f - age)).toInt().coerceIn(0, 255)
                c.drawCircle(bx + (k - 1.5f) * rad * 0.28f, by - rad * (0.5f + g),
                    rad * (0.18f + 0.22f * g), savePaint)
            }
            // обломки по параболе
            for (k in 0 until 8) {
                val a2 = Math.PI * (0.10 + 0.80 * k / 7.0)
                val sp = (0.6f + 0.4f * ((k * 37) % 10) / 10f) * h * 1.2f
                val dx = kotlin.math.cos(a2).toFloat() * sp * age
                val dy = -kotlin.math.sin(a2).toFloat() * sp * age + h * 1.6f * age * age
                savePaint.color = gray; savePaint.alpha = (220f * (1f - age)).toInt().coerceIn(0, 255)
                c.drawCircle(bx + dx, by + dy, (1.2f + 1.5f * (1f - age)) * d, savePaint)
            }
        }

        // ---- Лёд: слоистая плита, трещины, грани, кристаллы ----
        if (t >= 0.44f) {
            val sp = ((t - 0.44f) / 0.08f).coerceAtMost(1f)
            val x0 = w * 0.44f
            val x1 = x0 + (portalX - x0) * sp
            savePaint.style = Paint.Style.FILL
            savePaint.color = blue; savePaint.alpha = 120
            c.drawRect(x0, ground - 5f * d, x1, ground + 3f * d, savePaint)
            savePaint.color = blueBr; savePaint.alpha = 150
            c.drawRect(x0, ground - 5f * d, x1, ground - 2.4f * d, savePaint)
            savePaint.color = 0xFFFFFFFF.toInt(); savePaint.alpha = 175
            c.drawRect(x0, ground - 5f * d, x1, ground - 3.8f * d, savePaint)
            savePaint.style = Paint.Style.STROKE; savePaint.strokeWidth = 1.2f * d
            savePaint.color = 0xFFFFFFFF.toInt(); savePaint.alpha = 120
            var ix = x0
            var seg = 0
            while (ix < x1) {
                // грани и трещины внутри плиты
                c.drawLine(ix, ground - 5f * d, ix + 5f * d, ground + 3f * d, savePaint)
                if (seg % 2 == 0) c.drawLine(ix + 2f * d, ground - 1f * d, ix + 9f * d, ground - 2.5f * d, savePaint)
                // кристаллы инея по кромке
                savePaint.color = blueBr; savePaint.alpha = 190
                val hgt = (4f + 5f * (seg % 3)) * d
                c.drawLine(ix, ground - 5f * d, ix + 2.5f * d, ground - 5f * d - hgt, savePaint)
                c.drawLine(ix + 2.5f * d, ground - 5f * d - hgt,
                    ix + 0.6f * d, ground - 5f * d - hgt * 0.6f, savePaint)
                savePaint.color = 0xFFFFFFFF.toInt(); savePaint.alpha = 120
                ix += 12f * d; seg++
            }
            savePaint.style = Paint.Style.FILL
            // искры на льду
            for (k in 0 until 4) {
                var g = (ph * 0.8f + k * 0.25f) % 1f
                if (g < 0f) g += 1f
                savePaint.color = 0xFFFFFFFF.toInt()
                savePaint.alpha = (180f * (1f - kotlin.math.abs(0.5f - g) * 2f)).toInt().coerceIn(0, 255)
                c.drawCircle(x0 + (x1 - x0) * g, ground - 4.4f * d, 1.5f * d, savePaint)
            }
        }
        if (t >= 0.42f && t < 0.50f) {                       // печать бьёт в пол
            val fr = (t - 0.42f) / 0.08f
            savePaint.style = Paint.Style.FILL
            savePaint.color = blueBr; savePaint.alpha = (210f * (1f - fr)).toInt().coerceIn(0, 255)
            c.drawCircle(w * 0.44f, ground - 2f * d, (6f + 26f * fr) * d, savePaint)
        }

        // ---- Портал ----
        if (t >= 0.52f) {
            val op = ((t - 0.52f) / 0.10f).coerceAtMost(1f)
            val cl = if (t > 0.80f) (1f - (t - 0.80f) / 0.08f).coerceAtLeast(0f) else 1f
            val k2 = op * cl
            if (k2 > 0.01f) {
                val py = ground - h * 0.30f
                val rw = w * 0.055f * k2; val rh = h * 0.34f * k2
                savePaint.style = Paint.Style.FILL
                savePaint.color = violet; savePaint.alpha = (80f * k2).toInt().coerceIn(0, 255)
                c.drawOval(portalX - rw * 1.8f, py - rh * 1.25f, portalX + rw * 1.8f, py + rh * 1.25f, savePaint)
                savePaint.style = Paint.Style.STROKE
                for (i2 in 0 until 3) {
                    val kk = 1f - i2 * 0.22f
                    savePaint.strokeWidth = (2.8f - i2 * 0.5f) * d
                    savePaint.color = if (i2 == 0) 0xFFFFFFFF.toInt() else violetBr
                    savePaint.alpha = (210f * k2).toInt().coerceIn(0, 255)
                    c.save(); c.rotate(ph * (16f + i2 * 10f), portalX, py)
                    c.drawOval(portalX - rw * kk, py - rh * kk, portalX + rw * kk, py + rh * kk, savePaint)
                    c.restore()
                }
                savePaint.style = Paint.Style.FILL
                for (i2 in 0 until 6) {
                    var g = (ph * 0.75f + i2 * 0.17f) % 1f
                    if (g < 0f) g += 1f
                    val rr = (1f - g) * w * 0.11f
                    val a3 = Math.toRadians((i2 * 60f + ph * 44f).toDouble())
                    savePaint.color = violetBr; savePaint.alpha = (210f * g * k2).toInt().coerceIn(0, 255)
                    c.drawCircle(portalX + rr * kotlin.math.cos(a3).toFloat(),
                        py + rr * 0.7f * kotlin.math.sin(a3).toFloat(), 1.7f * d, savePaint)
                }
            }
        }

        // ---- Мир обесцвечивается на слоу-мо ----
        if (slow > 0.01f) {
            savePaint.style = Paint.Style.FILL
            savePaint.color = 0xFF20232A.toInt()
            savePaint.alpha = (150f * slow).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, w, h, savePaint)
        }

        // ---- Герой со свитком (в цвете даже на чёрно-белом кадре) ----
        if (visible) {
            c.save(); c.translate(hx, hy); if (rot != 0f) c.rotate(rot)
            val stride = 3.4f * d * sin((ph * 13f).toDouble()).toFloat()
            val fig = sp(1)
            when (pose) {
                1 -> {
                    fig.moveTo(0f, -6f * d); fig.lineTo(0f, -2f * d)
                    fig.moveTo(0f, -2f * d); fig.lineTo(-4.4f * d, 1.2f * d)
                    fig.moveTo(0f, -2f * d); fig.lineTo(3.2f * d, 2.6f * d)
                    fig.moveTo(0f, -5f * d); fig.lineTo(-5f * d, -3f * d)
                }
                2 -> {
                    fig.moveTo(0f, -6.4f * d); fig.lineTo(0f, -2f * d)
                    fig.moveTo(0f, -2f * d); fig.lineTo(-4.6f * d, 0f)
                    fig.moveTo(0f, -2f * d); fig.lineTo(3.4f * d, 0f)
                    fig.moveTo(0f, -5.4f * d); fig.lineTo(-6.6f * d, 0.8f * d)
                }
                3 -> {
                    fig.moveTo(0f, -4.8f * d); fig.lineTo(1.4f * d, -1.6f * d)
                    fig.moveTo(1.4f * d, -1.6f * d); fig.lineTo(-4.8f * d, 0f)
                    fig.moveTo(1.4f * d, -1.6f * d); fig.lineTo(4.6f * d, -0.4f * d)
                    fig.moveTo(0f, -4.2f * d); fig.lineTo(-5.6f * d, -5.6f * d)
                }
                4 -> {
                    fig.moveTo(0f, -6f * d); fig.lineTo(0f, -2f * d)
                    fig.moveTo(0f, -2f * d); fig.lineTo(-4.8f * d, 1.4f * d)
                    fig.moveTo(0f, -2f * d); fig.lineTo(4.2f * d, -1.6f * d)
                    fig.moveTo(0f, -5f * d); fig.lineTo(5.4f * d, -8f * d)
                }
                else -> {
                    fig.moveTo(0f, -6f * d); fig.lineTo(0f, -2f * d)
                    fig.moveTo(0f, -2f * d); fig.lineTo(-3f * d - stride, 0f)
                    fig.moveTo(0f, -2f * d); fig.lineTo(3f * d + stride, 0f)
                    fig.moveTo(0f, -5.2f * d); fig.lineTo(4.6f * d, -7f * d)
                }
            }
            savePaint.style = Paint.Style.FILL
            savePaint.color = amberBr; savePaint.alpha = 245
            c.drawCircle(0f, -8.6f * d, 2.9f * d, savePaint)
            Doodle.ink(c, fig, stroke(amberBr, 2.1f, 245), 0.6f * d)

            c.save()
            c.translate(-1.5f * d, -4.2f * d)
            c.rotate(if (pose == 3) -18f else -26f)
            savePaint.color = 0xFFE8DCC0.toInt(); savePaint.alpha = 250
            c.drawRoundRect(-13f * d, -3.6f * d, 13f * d, 3.6f * d, 3.6f * d, 3.6f * d, savePaint)
            savePaint.color = 0xFF7A5A32.toInt()
            c.drawRoundRect(-15f * d, -4.6f * d, -10.5f * d, 4.6f * d, 2f * d, 2f * d, savePaint)
            c.drawRoundRect(10.5f * d, -4.6f * d, 15f * d, 4.6f * d, 2f * d, 2f * d, savePaint)
            savePaint.color = 0xFF8A7B58.toInt(); savePaint.alpha = 200
            c.drawRect(-8f * d, -1.2f * d, 7f * d, -0.4f * d, savePaint)
            c.drawRect(-8f * d, 0.8f * d, 4f * d, 1.6f * d, savePaint)
            savePaint.color = red; savePaint.alpha = 220
            c.drawCircle(0f, 0f, 2.2f * d, savePaint)
            c.restore()

            // Скольжение: иней из-под ног и скоростные линии.
            if (pose == 3) {
                for (k in 0 until 6) {
                    var g = (ph * 2.1f + k * 0.17f) % 1f
                    if (g < 0f) g += 1f
                    savePaint.color = blueBr
                    savePaint.alpha = (210f * (1f - g)).toInt().coerceIn(0, 255)
                    c.drawCircle(-6f * d - g * 26f * d, -1f * d - g * 8f * d, (1.8f - g) * d, savePaint)
                }
                savePaint.style = Paint.Style.STROKE; savePaint.strokeWidth = 1.4f * d
                savePaint.color = 0xFFFFFFFF.toInt(); savePaint.alpha = 90
                for (k in 0 until 3) {
                    val yy = -3f * d - k * 3.2f * d
                    c.drawLine(-10f * d - k * 4f * d, yy, -24f * d - k * 6f * d, yy, savePaint)
                }
                savePaint.style = Paint.Style.FILL
            }
            c.restore()
        }

        // ---- Вспышка входа + глюк-полосы ----
        if (t in 0.78f..0.88f) {
            val fl = 1f - (t - 0.78f) / 0.10f
            savePaint.style = Paint.Style.FILL
            savePaint.color = 0xFFFFFFFF.toInt(); savePaint.alpha = (215f * fl).toInt().coerceIn(0, 255)
            c.drawCircle(portalX, ground - h * 0.30f, h * 0.50f * (1f - fl * 0.45f), savePaint)
            for (k in 0 until 5) {
                val yy = h * (0.12f + 0.16f * k)
                val off = (12f * d) * fl * sin((ph * 20f + k * 2.1f).toDouble()).toFloat()
                savePaint.color = if (k % 2 == 0) violetBr else blueBr
                savePaint.alpha = (150f * fl).toInt().coerceIn(0, 255)
                c.drawRect(off, yy, w + off, yy + 3.5f * d, savePaint)
            }
        }

        // ---- Табличка SAVE ----
        if (t >= 0.86f) {
            val fr = (t - 0.86f) / 0.14f
            val pop = if (fr < 0.20f) fr / 0.20f else 1f
            val fade = if (fr > 0.78f) (1f - (fr - 0.78f) / 0.22f) else 1f
            val a4 = (255f * fade).toInt().coerceIn(0, 255)
            val cxp = w * 0.50f; val cyp = h * 0.42f
            val pwd = w * 0.19f * (0.75f + 0.25f * pop)
            val phd = h * 0.25f * (0.75f + 0.25f * pop)
            savePaint.style = Paint.Style.FILL
            savePaint.color = green; savePaint.alpha = (45f * fade).toInt().coerceIn(0, 255)
            c.drawRoundRect(cxp - pwd * 1.2f, cyp - phd * 1.3f, cxp + pwd * 1.2f, cyp + phd * 1.3f,
                6f * d, 6f * d, savePaint)
            savePaint.color = 0xFF0B2416.toInt(); savePaint.alpha = a4
            c.drawRoundRect(cxp - pwd, cyp - phd, cxp + pwd, cyp + phd, 5f * d, 5f * d, savePaint)
            savePaint.style = Paint.Style.STROKE; savePaint.strokeWidth = 2f * d
            savePaint.color = green; savePaint.alpha = a4
            c.drawRoundRect(cxp - pwd, cyp - phd, cxp + pwd, cyp + phd, 5f * d, 5f * d, savePaint)
            // Галочка: компактная и жирная, с круглыми концами.
            savePaint.strokeWidth = 4.2f * d
            savePaint.strokeCap = Paint.Cap.ROUND
            savePaint.strokeJoin = Paint.Join.ROUND
            val gx = cxp - pwd * 0.52f; val gy = cyp
            val gs = phd * 0.42f
            val chk = sp(2)
            chk.moveTo(gx - gs * 0.75f, gy)
            chk.lineTo(gx - gs * 0.15f, gy + gs * 0.62f)
            chk.lineTo(gx + gs * 0.85f, gy - gs * 0.70f)
            c.drawPath(chk, savePaint)
            savePaint.style = Paint.Style.FILL
            savePaint.textSize = phd * 1.0f
            savePaint.color = green; savePaint.alpha = a4
            c.drawText("SAVE", cxp + pwd * 0.34f, cyp + phd * 0.36f, savePaint)
        }

        // ---- Стартовая вспышка: из неё номер начинается заново ----
        if (t < 0.06f) {
            val fl = 1f - t / 0.06f
            savePaint.style = Paint.Style.FILL
            savePaint.color = 0xFFFFFFFF.toInt(); savePaint.alpha = (200f * fl).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, w, h, savePaint)
        }
        savePaint.alpha = 255
        c.restore()
    }

    private fun drawHistory(c: Canvas, w: Float, h: Float, r: Wobble) {
        val nb = sp(3)
        Doodle.notebook(nb, w * 0.09f, h * 0.54f, w * 0.10f, h * 0.44f, r)
        Doodle.ink(c, nb, stroke(gray, 2f), 0.8f * d)
        scrollAct(c, w, h, r)
        flyingPages(c, w, h)
        stars(c, gray, listOf(Triple(0.88f, 0.22f, 0.08f)), w, h, r)
        drifting(c, w, h, r, gray, listOf(Triple(0.20f, 0.10f, 38f)))
    }
}
