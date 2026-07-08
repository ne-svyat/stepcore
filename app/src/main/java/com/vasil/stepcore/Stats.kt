package com.vasil.stepcore

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

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

    // ===== V11.1: сегментированный расчёт по часам =====
    // Проблема: kcalActive/distanceKm выше берут ТЕКУЩИЙ профиль и применяют
    // его ко ВСЕЙ сумме шагов за день. Снял груз вечером - утренние шаги
    // пересчитались задним числом. Решение: считать день по часам, каждый час
    // с профилем, который действовал ИМЕННО В ТОТ час (ProfileHistory).

    /** Конец часа из HourRecord.dateHour ("2026-07-06 14") -> epoch ms. */
    private fun hourEndMs(dateHour: String): Long {
        val start = LocalDate.parse(dateHour.substring(0, 10))
            .atTime(dateHour.substring(11).toInt(), 0)
        return start.plusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** ACTIVE ккал + дистанция (м) ОДНОГО часа, профиль - того часа. */
    suspend fun energyForHour(c: Context, hour: HourRecord): Pair<Int, Float> {
        if (hour.walkSteps <= 0 && hour.runSteps <= 0) return 0 to 0f
        val p = ProfileHistory.at(c, hourEndMs(hour.dateHour))
        val mass = p.weightKg + p.loadKg
        if (mass <= 0f) return 0 to 0f
        val cadence = StrideModel.walkCadenceHzOf(p.walkMinIntervalMs, p.walkMaxIntervalMs)
        val walkStride =
            StrideModel.walkStrideMOf(cadence, p.strideManual, p.strideA, p.strideB, p.heightCm)
        val runStride = StrideModel.runStrideMOf(p.heightCm)
        val walkIntervalS = if (cadence > 0f) 1f / cadence else 0f
        val wk = EnergyModel.walkKcal(hour.walkSteps, mass, walkStride, walkIntervalS)
        val rk = EnergyModel.runKcal(hour.runSteps * runStride / 1000f, mass)
        val distM = hour.walkSteps * walkStride + hour.runSteps * runStride
        return (wk + rk).toInt() to distM
    }

    /** Сумма по уже загруженным часам. */
    suspend fun segmentedActiveAndDistance(c: Context, hours: List<HourRecord>): Pair<Int, Float> {
        var active = 0; var dist = 0f
        for (h in hours) {
            val (a, d) = energyForHour(c, h)
            active += a; dist += d
        }
        return active to dist
    }

    /** То же, но сама грузит часы дня из БД. */
    suspend fun segmentedActiveAndDistance(c: Context, date: String): Pair<Int, Float> =
        segmentedActiveAndDistance(c, AppDb.get(c).dao().hoursOfDay(date))
}
