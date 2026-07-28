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

    /** Личные пороги уклона. Берутся из калибровки уклона (три отрезка
     *  на одном склоне): там условия совпадают, поэтому границы честные.
     *  Если якорей нет - строим от медианы самой прогулки, и об этом
     *  надо сказать человеку прямо. */
    data class Anchors(
        val up: Float, val flat: Float, val down: Float, val measured: Boolean
    ) {
        /** Границы между классами - середины между якорями. */
        val upFlat: Float get() = (up + flat) / 2f
        val flatDown: Float get() = (flat + down) / 2f
    }

    /** Якоря от медианы прогулки, когда измеренных нет. Опирается на
     *  измеренный зазор корпуса: в гору 6.44, ровно 6.93, с горы 7.79,
     *  то есть примерно медиана -0.5 / медиана / медиана +0.85. */
    fun fallbackAnchors(amps: List<Float>): Anchors {
        val v = amps.sorted()
        val med = if (v.isEmpty()) 0f else v[v.size / 2]
        return Anchors(med - 0.5f, med, med + 0.85f, false)
    }

    /** Класс одного шага по его амплитуде. */
    fun classify(amp: Float, a: Anchors): String = when {
        amp <= a.upFlat -> "UP"
        amp >= a.flatDown -> "DOWN"
        else -> "FLAT"
    }

    /** До трёх осмысленных точек разлома, лучшая первой. Человек должен
     *  выбирать из вариантов, а не принимать единственное предложение. */
    fun candidates(amps: List<Float>, limit: Int = 3): List<Split> {
        val n = amps.size
        if (n < MIN_SIDE * 2) return emptyList()
        val loK = maxOf(MIN_SIDE, EDGE_SKIP + 1)
        val hiK = minOf(n - MIN_SIDE, n - EDGE_SKIP - 1)
        if (loK > hiK) return emptyList()
        val spread = (amps.max() - amps.min()).coerceAtLeast(0.1f)
        val found = ArrayList<Pair<Float, Split>>()
        for (k in loK..hiK) {
            var ls = 0f; for (i in 0 until k) ls += amps[i]
            var rs = 0f; for (i in k until n) rs += amps[i]
            val lm = ls / k
            val rm = rs / (n - k)
            val gap = kotlin.math.abs(lm - rm)
            if (gap < MIN_ABS_GAP) continue
            if (gap / spread < SIGNIF) continue
            val jump = kotlin.math.abs(amps[k] - amps[k - 1])
            if (jump < gap * JUMP_RATIO) continue
            val balance = 1f - kotlin.math.abs(k - n / 2f) / (n / 2f)
            found.add((gap * (0.5f + 0.5f * balance)) to Split(k, gap, lm, rm))
        }
        // Близкие точки - это одна и та же граница; оставляем лучшую.
        val sorted = found.sortedByDescending { it.first }
        val out = ArrayList<Split>()
        for ((_, sp) in sorted) {
            if (out.any { kotlin.math.abs(it.index - sp.index) < MIN_SIDE }) continue
            out.add(sp)
            if (out.size >= limit) break
        }
        return out
    }

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
