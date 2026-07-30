package com.vasil.stepcore

import android.content.Context

/**
 * Реестр калибровок (V10). Единый источник правды: какие калибровки
 * существуют, что каждая даёт, когда пройдена, насколько свежа.
 *
 * СВЕЖЕСТЬ. Калибровка не портится мгновенно, но дрейфует: обувь, вес,
 * усталость, техника ходьбы. Модель: 100% в день калибровки, линейный
 * спад до FLOOR за DECAY_DAYS. Ниже FLOOR не падает - устаревшая
 * калибровка всё равно лучше табличной оценки по росту.
 * Не пройдена -> UNCALIBRATED (табличные значения, ~±15%).
 *
 * ОБЩАЯ ТОЧНОСТЬ = сумма (вес * свежесть). Веса отражают реальное
 * влияние на цифры:
 *  - длина шага 50%: прямо задаёт км, и через скорость - калории;
 *  - темп ходьбы 35%: задаёт скорость (LCDA сильно зависит от неё);
 *  - темп бега 15%: метка WALK/RUN и время бега.
 */
object CalibrationRegistry {

    enum class Kind(val title: String, val weight: Float, val affects: String) {
        STRIDE("Длина шага", 0.50f, "километры и калории"),
        WALK_TEMPO("Темп ходьбы", 0.35f, "скорость, калории, метку «ходьба»"),
        RUN_TEMPO("Темп бега", 0.15f, "метку «бег» и время бега"),
    }

    private const val DECAY_DAYS = 30f
    private const val FLOOR = 0.70f
    private const val UNCALIBRATED = 0.50f
    private const val DAY_MS = 86_400_000L
    // v280. Вес калибровки должен отражать её вклад в ЦИФРЫ ЭТОГО человека.
    // Прежде некалиброванный темп бега вечно держал общую точность на
    // потолке 92.5% даже у того, кто не бегает вовсе: экран показывал
    // недостачу там, где её нет. Теперь вес бега умножается на его
    // значимость, а остальные веса перенормируются.
    // Значимость падает не сразу: месяц без бега - это перерыв, а не
    // отказ от бега; четыре месяца - уже отказ.
    private const val RUN_FULL_DAYS = 30f
    private const val RUN_ZERO_DAYS = 120f
    const val KEY_RUN_SEEN = "run_last_seen_ms"

    private fun p(c: Context) =
        c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    private fun dateKey(k: Kind) = when (k) {
        Kind.STRIDE -> "cal_date_stride"
        Kind.WALK_TEMPO -> "cal_date_walk"
        Kind.RUN_TEMPO -> "cal_date_run"
    }

    fun markDone(c: Context, k: Kind) {
        p(c).edit().putLong(dateKey(k), System.currentTimeMillis()).apply()
    }

    fun doneAtMs(c: Context, k: Kind): Long = p(c).getLong(dateKey(k), 0L)

    fun isDone(c: Context, k: Kind): Boolean = when (k) {
        Kind.STRIDE -> StrideModel.source(c) != StrideModel.Source.ESTIMATE
        Kind.WALK_TEMPO -> p(c).contains("walk_min_interval")
        Kind.RUN_TEMPO -> p(c).contains("run_min_interval")
    }

    fun ageDays(c: Context, k: Kind): Int {
        val t = doneAtMs(c, k)
        if (t <= 0L || !isDone(c, k)) return -1
        return ((System.currentTimeMillis() - t) / DAY_MS).toInt()
    }

    fun freshness(c: Context, k: Kind): Float {
        if (!isDone(c, k)) return UNCALIBRATED
        val age = ageDays(c, k)
        if (age < 0) return UNCALIBRATED
        val decayed = 1f - (age / DECAY_DAYS) * (1f - FLOOR)
        return decayed.coerceIn(FLOOR, 1f)
    }

    /**
     * Значимость темпа бега для этого человека: 1 - бегал в последний
     * месяц, 0 - не видели бега больше RUN_ZERO_DAYS, между ними спад.
     * Метка ставится службой, когда за день набирается заметный бег,
     * поэтому это наблюдение, а не настройка.
     */
    fun runRelevance(c: Context): Float {
        // Откалиброванный бег значим всегда: человек его измерил осознанно.
        if (isDone(c, Kind.RUN_TEMPO)) return 1f
        val last = p(c).getLong(KEY_RUN_SEEN, 0L)
        if (last <= 0L) return 0f
        val days = (System.currentTimeMillis() - last).toFloat() / DAY_MS
        return when {
            days <= RUN_FULL_DAYS -> 1f
            days >= RUN_ZERO_DAYS -> 0f
            else -> 1f - (days - RUN_FULL_DAYS) / (RUN_ZERO_DAYS - RUN_FULL_DAYS)
        }
    }

    /** Эффективный вес калибровки с учётом значимости. */
    fun effectiveWeight(c: Context, k: Kind): Float =
        if (k == Kind.RUN_TEMPO) k.weight * runRelevance(c) else k.weight

    fun overallAccuracy(c: Context): Float {
        var sum = 0f
        var total = 0f
        for (k in Kind.values()) {
            val w = effectiveWeight(c, k)
            sum += w * freshness(c, k)
            total += w
        }
        // Перенормировка: если бег не в счёт, оставшиеся веса делят его долю
        // между собой, а не оставляют дыру в процентах.
        return if (total <= 0f) UNCALIBRATED else sum / total
    }

    fun overallPercent(c: Context): Int = (overallAccuracy(c) * 100).toInt()

    fun valueText(c: Context, k: Kind): String {
        val pr = p(c)
        return when (k) {
            Kind.STRIDE -> {
                val cm = StrideModel.measuredStrideCm(c)
                when {
                    cm == null -> "не измерена (оценка по росту)"
                    StrideModel.source(c) == StrideModel.Source.GPS -> "$cm см · по GPS"
                    else -> "$cm см · по метражу"
                }
            }
            Kind.WALK_TEMPO -> {
                if (!isDone(c, k)) "не измерен (стандартный диапазон)"
                else {
                    val lo = pr.getLong("walk_min_interval", 0L)
                    val hi = pr.getLong("walk_max_interval", 0L)
                    "${(lo + hi) / 2} мс на шаг"
                }
            }
            Kind.RUN_TEMPO -> {
                if (!isDone(c, k) && runRelevance(c) <= 0f)
                    "не измерен — и не нужен: бега не видно"
                else if (!isDone(c, k)) "не измерен (стандартный диапазон)"
                else {
                    val lo = pr.getLong("run_min_interval", 0L)
                    val hi = pr.getLong("run_max_interval", 0L)
                    "${(lo + hi) / 2} мс на шаг"
                }
            }
        }
    }

    fun ageText(c: Context, k: Kind): String {
        val age = ageDays(c, k)
        return when {
            age < 0 -> ""
            age == 0 -> "сегодня"
            age == 1 -> "вчера"
            age < 5 -> "$age дня назад"
            else -> "$age дней назад"
        }
    }

    /** Средний интервал бега, сек - для расчёта времени бега (V10). */
    fun runIntervalSec(c: Context): Float {
        val pr = p(c)
        val lo = pr.getLong("run_min_interval", 250L)
        val hi = pr.getLong("run_max_interval", 420L)
        return ((lo + hi) / 2f) / 1000f
    }
}
