package com.vasil.stepcore

import android.content.Context

/**
 * Адаптер профиля к моделям (V9.9): длина шага - StrideModel, энергия -
 * EnergyModel. Разделяет ACTIVE (расход на движение) и BASAL (BMR).
 *
 * Снапшот: для ЗАКРЫТЫХ дней калории/дистанция читаются из DayRecord
 * (заморожены с параметрами того дня), не пересчитываются при смене веса.
 * Для сегодняшнего (открытого) дня и старых дней без снапшота - на лету.
 */
object Stats {
    private fun prefs(c: Context) = c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    private fun weight(c: Context) = prefs(c).getFloat("p_weight", 0f)
    private fun height(c: Context) = prefs(c).getInt("p_height", 0)
    private fun age(c: Context) = prefs(c).getInt("p_age", 0)
    private fun male(c: Context) = prefs(c).getString("p_sex", "m") != "f"
    private fun load(c: Context) = prefs(c).getFloat("p_load", 0f)

    fun distanceKm(c: Context, walkSteps: Int, runSteps: Int): Float {
        val walkM = walkSteps * StrideModel.walkStrideAvgM(c)
        val runM = runSteps * StrideModel.runStrideM(c)
        return (walkM + runM) / 1000f
    }

    /** ACTIVE калории: расход именно на движение (нетто, сверх покоя). */
    fun kcalActive(c: Context, walkSteps: Int, runSteps: Int): Int {
        val w = weight(c)
        if (w <= 0f) return 0
        val mass = w + load(c)
        val stride = StrideModel.walkStrideAvgM(c)
        val intervalS = 1f / StrideModel.avgWalkCadenceHz(c)
        val walk = EnergyModel.walkKcal(walkSteps, mass, stride, intervalS)
        val run = EnergyModel.runKcal(runSteps * StrideModel.runStrideM(c) / 1000f, mass)
        return (walk + run).toInt()
    }

    /** BASAL калории за прошедшую долю суток (BMR по Mifflin-St Jeor). */
    fun kcalBasalToday(c: Context): Int {
        val bmr = EnergyModel.bmrPerDay(weight(c), height(c), age(c), male(c))
        val secOfDay = java.time.LocalTime.now().toSecondOfDay().toLong()
        return EnergyModel.basalKcalForElapsed(bmr, secOfDay).toInt()
    }

    /** BASAL за полные сутки (для закрытого дня). */
    fun kcalBasalFullDay(c: Context): Int =
        EnergyModel.bmrPerDay(weight(c), height(c), age(c), male(c)).toInt()

    /** БРУТТО (Total) = Active + Basal. Для сегодня Basal за долю суток. */
    fun kcalGrossToday(c: Context, walkSteps: Int, runSteps: Int): Int =
        kcalActive(c, walkSteps, runSteps) + kcalBasalToday(c)

    /** Обратная совместимость: старый kcal -> ACTIVE. */
    fun kcal(c: Context, walkSteps: Int, runSteps: Int): Int = kcalActive(c, walkSteps, runSteps)

    /** Калории закрытого дня из снапшота, иначе на лету. (active, basal). */
    fun kcalOfDay(c: Context, rec: DayRecord): Pair<Int, Int> =
        if (rec.kcalActive >= 0) rec.kcalActive to rec.kcalBasal.coerceAtLeast(0)
        else kcalActive(c, rec.walkSteps, rec.runSteps) to kcalBasalFullDay(c)

    fun distanceOfDayKm(c: Context, rec: DayRecord): Float =
        if (rec.distanceM >= 0) rec.distanceM / 1000f
        else distanceKm(c, rec.walkSteps, rec.runSteps)

    /**
     * Время активности, сек. Интервал бега берётся из КАЛИБРОВКИ, а не
     * из хардкода 0.34 c (до V10 калибровка бега на время не влияла).
     */
    fun activeSeconds(c: Context, walkSteps: Int, runSteps: Int): Long {
        val walkIv = 1f / StrideModel.avgWalkCadenceHz(c)
        val runIv = CalibrationRegistry.runIntervalSec(c)
        return (walkSteps * walkIv + runSteps * runIv).toLong()
    }

    /** Снапшот дня при закрытии (V9.9): (active, basal, distM). */
    fun snapshotForDay(c: Context, walkSteps: Int, runSteps: Int): Triple<Int, Int, Int> {
        val active = kcalActive(c, walkSteps, runSteps)
        val basal = kcalBasalFullDay(c)
        val distM = (distanceKm(c, walkSteps, runSteps) * 1000).toInt()
        return Triple(active, basal, distM)
    }
}
