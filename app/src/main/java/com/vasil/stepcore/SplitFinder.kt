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
    // v236. Абсолютный порог: настоящий зазор UP/DOWN по корпусу 1.35
    // (в гору 6.44, с горы 7.79). Берём 1.0 - с запасом, но выше шума.
    // Без него на ровной ходьбе рябь 0.5 выглядела значимой.
    private const val MIN_ABS_GAP = 1.0f
    // v241. Настоящая граница подъём/спуск - СКАЧОК между соседними
    // шагами (последний мягкий, первый уже жёсткий). Плавный разгон в
    // начале записи даёт расхождение средних без скачка: на данных
    // Markus 0.8 при зазоре 2.3 (0.35), а настоящая граница 1.5 при 1.6.
    private const val JUMP_RATIO = 0.5f
    // Края записи - всегда разгон/затухание, там не режем.
    private const val EDGE_SKIP = 2

    fun find(amps: List<Float>): Split? {
        val n = amps.size
        if (n < MIN_SIDE * 2) return null
        var best: Split? = null
        var bestScore = 0f
        val loK = maxOf(MIN_SIDE, EDGE_SKIP + 1)
        val hiK = minOf(n - MIN_SIDE, n - EDGE_SKIP - 1)
        if (loK > hiK) return null
        for (k in loK..hiK) {
            var ls = 0f; for (i in 0 until k) ls += amps[i]
            var rs = 0f; for (i in k until n) rs += amps[i]
            val lm = ls / k
            val rm = rs / (n - k)
            val gap = kotlin.math.abs(lm - rm)
            val balance = 1f - kotlin.math.abs(k - n / 2f) / (n / 2f)
            val score = gap * (0.5f + 0.5f * balance)
            if (score > bestScore) { bestScore = score; best = Split(k, gap, lm, rm) }
        }
        val b = best ?: return null
        // Сначала физика: разрыв должен быть сопоставим с реальным
        // различием подъёма и спуска, иначе это рябь ровной ходьбы.
        if (b.gap < MIN_ABS_GAP) return null
        // Резкость границы: перепад между соседними шагами в точке
        // разреза. Плавный разгон не проходит.
        val jump = kotlin.math.abs(amps[b.index] - amps[b.index - 1])
        if (jump < b.gap * JUMP_RATIO) return null
        val spread = (amps.max() - amps.min()).coerceAtLeast(0.1f)
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
