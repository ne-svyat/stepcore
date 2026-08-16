package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.sin

/**
 * РЕШЁТКА ЗА СУНДУКОМ.
 *
 * Замысел. Экран входа - самое пустое место приложения: заголовок, поле,
 * кнопка. Пустоту можно занять узором, но неподвижный узор через день
 * становится обоями. Здесь узор ОТВЕЧАЕТ НА НАБОР: каждые несколько
 * знаков он пересобирается заново - будто замок перекладывает свои
 * внутренности, пока ты подбираешь к нему ключ.
 *
 * Что рисуется. Сетка ячеек, в каждой одна из фигур: дуга угла, прямая,
 * перекрёсток, тройник, узел, пусто. Фигуры стыкуются краями, поэтому
 * из них складывается связный лабиринт - то ли схема, то ли корни, то ли
 * чертёж механизма. Набор фигур и оттенок берутся из ЗЕРНА, а зерно - из
 * длины набранного. Один и тот же ввод даёт один и тот же узор: это не
 * случайность в кадре, а функция от состояния.
 *
 * Как меняется. Не подменой: новый узор приходит ВОЛНОЙ от центра к
 * краям, старый в это время гаснет. Ячейка ближе к середине переключается
 * раньше дальней - получается расходящийся круг, а не мигание всей сетки.
 *
 * Стоимость. Ни одной аллокации в кадре, все пути в полях. В покое
 * перерисовка редкая: узор дышит медленно.
 */
class VaultLatticeView(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cell = Path()
    private val oval = RectF()

    private var seed = 1L
    private var prevSeed = 1L
    private var morphAt = 0L

    /**
     * Новое состояние ввода. Зерно берётся от длины: узор меняется каждые
     * два-три знака, а не на каждый - иначе рябит и мешает набирать.
     */
    fun setTyped(len: Int) {
        val s = (len / 2).toLong() + 1L
        if (s == seed) return
        prevSeed = seed
        seed = s
        morphAt = System.currentTimeMillis()
        invalidate()
    }

    /**
     * Перемешивание зерна и номера ячейки.
     *
     * Множители записаны отрицательными шестнадцатеричными литералами, а
     * не беззнаковыми: Long в Kotlin знаковый, и запись вида 0x9E37...uL
     * потребовала бы беззнакового типа там, где он не нужен ни для чего.
     */
    private fun hash(s: Long, i: Int): Long {
        var z = (s * -0x61c8864680b583ebL) + i * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()

        val cols = 9
        val rows = 5
        val cw = w / cols
        val chh = h / rows
        val cxm = (cols - 1) / 2f
        val cym = (rows - 1) / 2f
        val maxDist = kotlin.math.sqrt((cxm * cxm + cym * cym).toDouble()).toFloat()
        val morph = if (morphAt == 0L) 1f
        else ((now - morphAt).toFloat() / MORPH_MS).coerceIn(0f, 1f)

        // Дыхание: узор чуть светлеет и гаснет сам по себе.
        val breath = 0.5f + 0.5f * sin(now * 0.0007).toFloat()

        var ry = 0
        while (ry < rows) {
            var rx = 0
            while (rx < cols) {
                val idx = ry * cols + rx
                val dx = rx - cxm
                val dy = ry - cym
                val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / maxDist
                // Волна переключения: центр раньше, край позже.
                val local = ((morph - dist * 0.55f) / 0.45f).coerceIn(0f, 1f)
                val x0 = rx * cw
                val y0 = ry * chh

                if (local < 1f) drawCell(canvas, prevSeed, idx, x0, y0, cw, chh,
                    (1f - local) * breath)
                if (local > 0f) drawCell(canvas, seed, idx, x0, y0, cw, chh,
                    local * breath)
                rx++
            }
            ry++
        }

        if (morph < 1f) postInvalidateOnAnimation() else postInvalidateDelayed(180)
    }

    /**
     * Одна ячейка. Фигура и оттенок - от зерна и номера ячейки, поэтому
     * узор воспроизводим: тот же ввод даст ту же картину.
     */
    private fun drawCell(c: Canvas, s: Long, idx: Int, x0: Float, y0: Float,
                         cw: Float, ch: Float, k: Float) {
        if (k <= 0.01f) return
        val hv = hash(s, idx)
        val kind = (hv and 7L).toInt()
        if (kind == 6 || kind == 7) return          // пустые ячейки: узор дышит
        val turn = ((hv ushr 3) and 3L).toInt()
        val hue = ((hv ushr 5) and 3L).toInt()
        val col = when (hue) {
            0 -> 0xFF7B5BC4.toInt()
            1 -> 0xFF4E7FB8.toInt()
            2 -> 0xFF8A6636.toInt()
            else -> 0xFF5C4E86.toInt()
        }
        val a = (110f * k).toInt().coerceIn(0, 255)
        line.color = col
        line.alpha = a
        line.strokeWidth = (1.4f + 0.9f * k) * d

        val cx = x0 + cw / 2f
        val cy = y0 + ch / 2f
        val r = minOf(cw, ch) / 2f

        c.save()
        c.rotate(turn * 90f, cx, cy)
        when (kind) {
            0 -> {
                // Дуга угла: соединяет левый край с верхним.
                oval.set(cx - r, cy - r, cx + r, cy + r)
                cell.reset()
                cell.addArc(oval, 90f, 90f)
                c.drawPath(cell, line)
            }
            1 -> c.drawLine(x0, cy, x0 + cw, cy, line)
            2 -> {
                c.drawLine(x0, cy, x0 + cw, cy, line)
                c.drawLine(cx, y0, cx, y0 + ch, line)
            }
            3 -> {
                c.drawLine(x0, cy, x0 + cw, cy, line)
                c.drawLine(cx, cy, cx, y0 + ch, line)
            }
            4 -> {
                // Узел: точка со скобами - место, где линия ветвится.
                c.drawLine(x0, cy, cx - r * 0.35f, cy, line)
                c.drawLine(cx + r * 0.35f, cy, x0 + cw, cy, line)
                dot.color = col
                dot.alpha = (150f * k).toInt().coerceIn(0, 255)
                c.drawCircle(cx, cy, r * 0.22f * k, dot)
            }
            else -> {
                // Двойная дуга: рисунок корня, уходящего вбок.
                oval.set(cx - r, cy - r, cx + r, cy + r)
                cell.reset()
                cell.addArc(oval, 90f, 90f)
                c.drawPath(cell, line)
                oval.set(cx - r * 0.45f, cy - r * 0.45f, cx + r * 0.45f, cy + r * 0.45f)
                cell.reset()
                cell.addArc(oval, 90f, 90f)
                line.alpha = (a * 0.6f).toInt()
                c.drawPath(cell, line)
            }
        }
        c.restore()
    }

    private companion object {
        /** Пересборка узора, мс: волна успевает дойти до края. */
        const val MORPH_MS = 620f
    }
}
