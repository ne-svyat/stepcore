package com.vasil.stepcore

/**
 * Разбор записанного курса. Чистая логика без Android: её можно прогнать
 * тестами до установки.
 *
 * Зачем: косвенные признаки "вернулся тем же путём" (похожие объёмы, порядок
 * меток) оказались слабыми на корпусе. Прямая улика - разворот направления.
 * Прежде чем строить на нём контекст, надо убедиться, что курс вообще
 * читается: точность компаса в кармане неизвестна.
 */
object HeadingDiag {

    /** Курс отрезка: медиана азимута и разброс. */
    data class Seg(val label: String, val startMs: Long, val n: Int,
                   val headMed: Float, val spread: Float, val accMed: Int)

    /** Разница курсов с учётом кольца 0..360. Всегда 0..180. */
    fun angleDiff(a: Float, b: Float): Float {
        var d = kotlin.math.abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    /** Медиана углов через кольцо: обычная медиана врёт около нуля
     *  (359 и 1 - соседи, а среднее даст 180). */
    fun circularMedian(vals: List<Float>): Float {
        if (vals.isEmpty()) return 0f
        var best = vals[0]; var bestCost = Float.MAX_VALUE
        for (c in vals) {
            var cost = 0f
            for (v in vals) cost += angleDiff(c, v)
            if (cost < bestCost) { bestCost = cost; best = c }
        }
        return best
    }

    /** Разброс курса внутри отрезка: медиана отклонений от медианы.
     *  Большой разброс - человек петлял или компас врёт. */
    fun circularSpread(vals: List<Float>): Float {
        if (vals.isEmpty()) return 0f
        val m = circularMedian(vals)
        val d = vals.map { angleDiff(it, m) }.sorted()
        return d[d.size / 2]
    }

    /** Похоже ли на разворот: курс сменился примерно на 180 градусов. */
    fun isReversal(a: Float, b: Float, tol: Float = 45f): Boolean =
        angleDiff(a, b) >= 180f - tol
}
