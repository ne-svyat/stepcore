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

    fun overallAccuracy(c: Context): Float =
        Kind.values().sumOf { (it.weight * freshness(c, it)).toDouble() }.toFloat()

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
                if (!isDone(c, k)) "не измерен (стандартный диапазон)"
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
