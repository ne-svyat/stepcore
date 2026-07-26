package com.vasil.stepcore

/**
 * Поиск точки разлома в ряду амплитуд сессии. Подъём мягче (амплитуда ниже),
 * спуск жёстче (выше). Если внутри сессии есть и то, и другое - амплитуда
 * скачком меняет уровень. Ищем точку, где две половины максимально разошлись.
 *
 * Порог значимости отсекает однородные сессии: если разрыв мал относительно
 * общего разброса, резать не надо. Проверено на kotlinc: смесь режется в
 * середине, чистый подъём/спуск не трогается.
 */
object SplitFinder {
    data class Split(
        val index: Int,        // разрез ПЕРЕД этим образцом
        val gap: Float,        // разрыв средних
        val leftMean: Float,
        val rightMean: Float
    )

    private const val MIN_SIDE = 3       // минимум образцов в каждой части
    private const val SIGNIF = 0.4f      // разрыв/разброс, ниже которого не режем

    fun find(amps: List<Float>): Split? {
        val n = amps.size
        if (n < MIN_SIDE * 2) return null
        var best: Split? = null
        var bestScore = 0f
        for (k in MIN_SIDE..(n - MIN_SIDE)) {
            var ls = 0f; for (i in 0 until k) ls += amps[i]
            var rs = 0f; for (i in k until n) rs += amps[i]
            val lm = ls / k
            val rm = rs / (n - k)
            val gap = kotlin.math.abs(lm - rm)
            val balance = 1f - kotlin.math.abs(k - n / 2f) / (n / 2f)
            val score = gap * (0.5f + 0.5f * balance)
            if (score > bestScore) { bestScore = score; best = Split(k, gap, lm, rm) }
        }
        val spread = (amps.max() - amps.min()).coerceAtLeast(0.1f)
        val b = best ?: return null
        return if (b.gap / spread >= SIGNIF) b else null
    }

    /** Человеческое объяснение части по её средней амплитуде относительно
     *  другой части. Возвращает (метка, фраза). */
    fun explain(ampMean: Float, relToOther: Float): Pair<String, String> {
        val label = when {
            relToOther > 0.5f -> "DOWN"
            relToOther < -0.5f -> "UP"
            else -> "FLAT"
        }
        val phrase = when (label) {
            "DOWN" -> "похоже на спуск: приземление жёстче, амплитуда выше (" +
                String.format(java.util.Locale.US, "%.1f", ampMean) + ")"
            "UP" -> "похоже на подъём: шаг мягче и короче, амплитуда ниже (" +
                String.format(java.util.Locale.US, "%.1f", ampMean) + ")"
            else -> "похоже на ровно: амплитуда ровная (" +
                String.format(java.util.Locale.US, "%.1f", ampMean) + ")"
        }
        return label to phrase
    }
}
