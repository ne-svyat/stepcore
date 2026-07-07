package com.vasil.stepcore

import android.content.Context

/**
 * Адаптер профиля к моделям: длина шага - из StrideModel (единый
 * источник, V9.3), энергия - из EnergyModel. Одна ответственность:
 * связать prefs с чистыми модулями.
 */
object Stats {
    private fun prefs(c: Context) = c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    fun distanceKm(c: Context, walkSteps: Int, runSteps: Int): Float {
        val walkM = walkSteps * StrideModel.walkStrideAvgM(c)
        val runM = runSteps * StrideModel.runStrideM(c)
        return (walkM + runM) / 1000f
    }

    /** Калории НЕТТО (сверх покоя). */
    fun kcalNet(c: Context, walkSteps: Int, runSteps: Int): Int {
        val p = prefs(c)
        val w = p.getFloat("p_weight", 0f)
        if (w <= 0f) return 0
        val mass = w + p.getFloat("p_load", 0f)
        val stride = StrideModel.walkStrideAvgM(c)
        val intervalS = 1f / StrideModel.avgWalkCadenceHz(c)
        val walk = EnergyModel.walkKcal(walkSteps, mass, stride, intervalS)
        val run = EnergyModel.runKcal(runSteps * StrideModel.runStrideM(c) / 1000f, mass)
        return (walk + run).toInt()
    }

    /** Калории БРУТТО = нетто + базовый обмен за время ходьбы/бега. */
    fun kcalGross(c: Context, walkSteps: Int, runSteps: Int): Int {
        val p = prefs(c)
        val w = p.getFloat("p_weight", 0f)
        if (w <= 0f) return 0
        val mass = w + p.getFloat("p_load", 0f)
        val activeSec = activeSeconds(c, walkSteps, runSteps)
        val rest = EnergyModel.restKcal(mass, activeSec)
        return kcalNet(c, walkSteps, runSteps) + rest.toInt()
    }

    /** Обратная совместимость: старые вызовы kcal -> нетто. */
    fun kcal(c: Context, walkSteps: Int, runSteps: Int): Int = kcalNet(c, walkSteps, runSteps)

    /** Время активности, сек: шаги * интервал по режимам. */
    fun activeSeconds(c: Context, walkSteps: Int, runSteps: Int): Long {
        val walkIv = 1f / StrideModel.avgWalkCadenceHz(c)
        val runIv = 0.34f // средний беговой интервал (из логов 340мс)
        return (walkSteps * walkIv + runSteps * runIv).toLong()
    }
}
