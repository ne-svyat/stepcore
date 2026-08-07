package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * Корни: срез почвы, а не дерево.
 *
 * ПОЧЕМУ НЕ ГРАФ И НЕ ДЕРЕВО
 * --------------------------
 * Дерево требует одного родителя у каждого узла, а заметка спокойно
 * принадлежит трём классам сразу. Граф после сотни узлов превращается в
 * клубок, по которому нельзя ткнуть пальцем.
 *
 * Здесь класс — вертикальная жила своего оттенка, заметка — узелок на
 * ней, а места, где классы встречаются на одной заметке, — сплетения:
 * дуги между жилами. Всё видно на одном экране без панорамы и зума.
 *
 * ЧТО ЧИТАЕТСЯ С ОДНОГО ВЗГЛЯДА
 * -----------------------------
 * Толщина жилы — сколько заметок в классе. Плотность цвета — то же самое,
 * продублировано намеренно: цвет замечаешь боковым зрением, толщину —
 * прямым. Близость оттенков означает, что темы часто идут вместе, это
 * уже посчитано снаружи.
 */
class VaultRootsView(context: Context) : View(context) {

    /** Узелок на жиле — конкретная заметка. */
    class Node(val id: Long, val title: String)

    /** Одна жила. */
    class Strand(val name: String, val count: Int, val color: Int,
                 val nodes: List<Node> = emptyList())

    /** Сплетение: два класса встретились на одной заметке. */
    class Weave(val a: String, val b: String, val weight: Int)

    private var strands: List<Strand> = emptyList()
    private var weaves: List<Weave> = emptyList()
    private var onPick: ((String) -> Unit)? = null
    private var onNote: ((Long) -> Unit)? = null

    /** Где нарисован каждый узелок: нужно для попадания пальцем. */
    private val hits = ArrayList<Triple<Float, Float, Node>>()

    private val veinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val weavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val d = resources.displayMetrics.density

    companion object {
        /** Ширина на одну жилу. Уже - подпись класса не читается. */
        const val LANE_DP = 56f
    }

    fun setData(s: List<Strand>, w: List<Weave>,
                pick: (String) -> Unit, note: (Long) -> Unit = {}) {
        strands = s
        weaves = w
        onPick = pick
        onNote = note
        // requestLayout обязателен: от числа жил зависит НУЖНАЯ ШИРИНА,
        // а её сообщает onMeasure. Без пересчёта разметки полотно
        // останется прежнего размера.
        requestLayout()
        invalidate()
    }

    /**
     * Ширина считается здесь, а не снаружи.
     *
     * Внутри HorizontalScrollView ребёнок измеряется без ограничения по
     * ширине, и View по умолчанию возвращает НОЛЬ. Вьюха с нулевой
     * шириной выходит из отрисовки первой же строкой - экран выглядит
     * пустым, хотя данные на месте.
     *
     * Раньше ширину задавали снаружи прямым присваиванием layoutParams -
     * и это роняло приложение, потому что тип параметров не совпадал с
     * контейнером. Правильное место для такого расчёта одно: onMeasure.
     * Тогда снаружи вообще не надо знать, сколько места нужно.
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        // Жила уже 56dp читается плохо: подпись класса не помещается.
        val need = (LANE_DP * d * strands.size + 24f * d).toInt()
        val avail = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(maxOf(need, avail), measuredHeight)
    }

    /**
     * Где стоит жила класса. Нужно, чтобы прокрутка могла подвести карту
     * к нужному месту, а не оставлять человека искать её глазами.
     * @return координата по горизонтали, либо -1 если такого класса нет.
     */
    fun laneX(name: String): Float {
        val i = strands.indexOfFirst { it.name.equals(name, true) }
        return if (i < 0) -1f else xOf(i)
    }

    private fun xOf(i: Int): Float {
        if (strands.isEmpty()) return 0f
        val step = width.toFloat() / (strands.size + 1)
        return step * (i + 1)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || strands.isEmpty()) return true

        // Сначала узелки: попал в заметку - открываем её. Узелок меньше
        // жилы, поэтому проверяется первым, иначе до него не дотянуться.
        var bestNode: Node? = null
        var bestDist = Float.MAX_VALUE
        for ((nx, ny, node) in hits) {
            val dx = event.x - nx
            val dy = event.y - ny
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist < bestDist) { bestDist = dist; bestNode = node }
        }
        if (bestNode != null && bestDist < 22f * d) {
            onNote?.invoke(bestNode.id)
            return true
        }

        // Ближайшая жила, но не дальше половины шага: промах не должен
        // молча открывать соседний класс.
        var best = -1
        var bestDx = Float.MAX_VALUE
        for (i in strands.indices) {
            val dx = kotlin.math.abs(event.x - xOf(i))
            if (dx < bestDx) { bestDx = dx; best = i }
        }
        val step = width.toFloat() / (strands.size + 1)
        if (best >= 0 && bestDx < step / 2f) onPick?.invoke(strands[best].name)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear()
        if (width == 0) return
        if (strands.isEmpty()) {
            // Молчаливая пустота неотличима от поломки. Лучше сказать
            // прямо, что смотреть не на что и почему.
            textPaint.color = 0xFF7A7A88.toInt()
            textPaint.textSize = 12f * d
            canvas.drawText("Классов пока нет", width / 2f, height / 2f, textPaint)
            return
        }

        val top = 14f * d
        val bottom = height - 26f * d
        val maxCount = (strands.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

        // Сплетения рисуются первыми: они фон, жилы поверх.
        for (w in weaves) {
            val ia = strands.indexOfFirst { it.name == w.a }
            val ib = strands.indexOfFirst { it.name == w.b }
            if (ia < 0 || ib < 0) continue
            val xa = xOf(ia)
            val xb = xOf(ib)
            // Глубина дуги зависит от силы связи: частые пары сходятся выше
            // и заметнее, редкие едва намечены у самого низа.
            val k = (w.weight.toFloat() / maxCount).coerceIn(0.15f, 1f)
            val y = bottom - (bottom - top) * 0.18f * k
            val path = Path()
            path.moveTo(xa, y)
            path.quadTo((xa + xb) / 2f, y + 46f * d * k, xb, y)
            weavePaint.strokeWidth = (0.9f + 2.2f * k) * d
            weavePaint.color = blend(
                strands[ia].color, strands[ib].color,
                (70 + 90 * k).toInt().coerceIn(40, 190)
            )
            canvas.drawPath(path, weavePaint)
        }

        for ((i, s) in strands.withIndex()) {
            val x = xOf(i)
            val thick = (2.2f + 7f * (s.count.toFloat() / maxCount)) * d
            veinPaint.color = s.color
            veinPaint.strokeWidth = thick
            veinPaint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x, top, x, bottom, veinPaint)

            // Узелки: до двенадцати, дальше глаз всё равно не считает.
            val shown = s.nodes.take(12)
            val nodes = if (shown.isEmpty()) s.count.coerceAtMost(12) else shown.size
            nodePaint.color = s.color
            for (n in 0 until nodes) {
                val t = if (nodes == 1) 0.5f else n.toFloat() / (nodes - 1)
                val y = top + (bottom - top) * (0.06f + 0.88f * t)
                canvas.drawCircle(x, y, thick * 0.62f, nodePaint)
                if (n < shown.size) hits.add(Triple(x, y, shown[n]))
            }

            // Названия заметок подписываются только там, где их мало.
            // На жиле из двадцати узелков подписи налезут друг на друга и
            // превратят карту в кашу - лучше честно ничего не писать.
            if (shown.size in 1..4) {
                textPaint.textSize = 9f * d
                textPaint.color = (0x99 shl 24) or (s.color and 0xFFFFFF)
                textPaint.textAlign = Paint.Align.LEFT
                for (n in shown.indices) {
                    val t = if (shown.size == 1) 0.5f else n.toFloat() / (shown.size - 1)
                    val y = top + (bottom - top) * (0.06f + 0.88f * t)
                    val label = if (shown[n].title.length > 11)
                        shown[n].title.take(10) + "…" else shown[n].title
                    canvas.drawText(label, x + thick, y + 3f * d, textPaint)
                }
                textPaint.textAlign = Paint.Align.CENTER
            }

            textPaint.color = s.color
            textPaint.textSize = 11f * d
            val label = if (s.name.length > 9) s.name.take(8) + "…" else s.name
            canvas.drawText(label, x, height - 9f * d, textPaint)
        }
    }

    /** Смешать два цвета с заданной непрозрачностью результата. */
    private fun blend(c1: Int, c2: Int, alpha: Int): Int {
        val r = (((c1 shr 16) and 0xFF) + ((c2 shr 16) and 0xFF)) / 2
        val g = (((c1 shr 8) and 0xFF) + ((c2 shr 8) and 0xFF)) / 2
        val b = ((c1 and 0xFF) + (c2 and 0xFF)) / 2
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }
}
