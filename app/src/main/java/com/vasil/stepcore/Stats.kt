package com.vasil.stepcore

import android.content.Context

/**
 * Адаптер профиля к EnergyModel: читает prefs, отдаёт дистанцию и ккал.
 * Длина шага от роста - ГРУБО до V9-калибровки дистанции
 * (ходьба ~0.414 роста, бег ~0.65 роста). Энергия - см. EnergyModel.
 */
object Stats {
    private fun prefs(c: Context) = c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    fun distanceKm(c: Context, walkSteps: Int, runSteps: Int): Float {
        val h = prefs(c).getInt("p_height", 0)
        if (h <= 0) return 0f
        return (walkSteps * strideWalkM(h) + runSteps * strideRunM(h)) / 1000f
    }

    fun kcal(c: Context, walkSteps: Int, runSteps: Int): Int {
        val p = prefs(c)
        val w = p.getFloat("p_weight", 0f)
        val h = p.getInt("p_height", 0)
        if (w <= 0f || h <= 0) return 0
        val mass = w + p.getFloat("p_load", 0f)
        // Медиана калиброванного интервала восстанавливается точно:
        // калибровка хранит lo = 0.65*med и hi = 1.35*med -> med = (lo+hi)/2.
        val lo = p.getLong("walk_min_interval", 400L)
        val hi = p.getLong("walk_max_interval", 1200L)
        val intervalS = (lo + hi) / 2f / 1000f
        val walk = EnergyModel.walkKcal(walkSteps, mass, strideWalkM(h), intervalS)
        val run = EnergyModel.runKcal(runSteps * strideRunM(h) / 1000f, mass)
        return (walk + run).toInt()
    }

    private fun strideWalkM(heightCm: Int) = heightCm * 0.414f / 100f
    private fun strideRunM(heightCm: Int) = heightCm * 0.65f / 100f
}
