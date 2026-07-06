package com.vasil.stepcore

import android.content.Context

/**
 * Дистанция и калории из шагов и профиля. ГРУБЫЕ ОЦЕНКИ до ручной
 * калибровки дистанции (V9): длина шага от роста (ходьба ~0.414 роста,
 * бег ~0.65 роста), ккал по стандартным приближениям на км
 * (ходьба ~0.53*вес, бег ~1.03*вес).
 */
object Stats {
    private fun prefs(c: Context) = c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    fun distanceKm(c: Context, walkSteps: Int, runSteps: Int): Float {
        val h = prefs(c).getInt("p_height", 0)
        if (h <= 0) return 0f
        val strideWalk = h * 0.414f / 100f
        val strideRun = h * 0.65f / 100f
        return (walkSteps * strideWalk + runSteps * strideRun) / 1000f
    }

    fun kcal(c: Context, walkSteps: Int, runSteps: Int): Int {
        val p = prefs(c)
        val w = p.getFloat("p_weight", 0f)
        val h = p.getInt("p_height", 0)
        if (w <= 0f || h <= 0) return 0
        val kmWalk = walkSteps * (h * 0.414f / 100f) / 1000f
        val kmRun = runSteps * (h * 0.65f / 100f) / 1000f
        return (0.53f * w * kmWalk + 1.03f * w * kmRun).toInt()
    }
}
