package com.vasil.stepcore

import android.content.Context

/**
 * История профиля (V11). Единственная ответственность: превратить текущий
 * профиль в точку во времени и найти, какая точка действовала в момент atMs.
 *
 * Зачем: StrideModel/Stats читают SharedPreferences напрямую - это ЖИВОЕ
 * значение, годное только для "сейчас". Расчёт ПРОШЕДШИХ часов дня обязан
 * знать, что было в профиле ТОГДА. Иначе смена груза в обед задним числом
 * переписывает утро.
 *
 * V11.0 только НАКАПЛИВАЕТ историю. Потребители - в V11.1/V11.2.
 */
object ProfileHistory {
    private fun prefs(c: Context) = c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    fun currentSnapshot(c: Context, timestampMs: Long = System.currentTimeMillis()): ProfileSnapshotRecord {
        val pr = prefs(c)
        return ProfileSnapshotRecord(
            timestampMs = timestampMs,
            weightKg = pr.getFloat("p_weight", 0f),
            loadKg = pr.getFloat("p_load", 0f),
            heightCm = pr.getInt("p_height", 0),
            age = pr.getInt("p_age", 0),
            male = pr.getString("p_sex", "m") != "f",
            walkMinIntervalMs = pr.getLong("walk_min_interval", 400L),
            walkMaxIntervalMs = pr.getLong("walk_max_interval", 1200L),
            runMinIntervalMs = pr.getLong("run_min_interval", 250L),
            runMaxIntervalMs = pr.getLong("run_max_interval", 420L),
            strideA = pr.getFloat("stride_a", StrideModel.A_DEFAULT),
            strideB = pr.getFloat("stride_b", 0f),
            strideManual = pr.getBoolean("stride_manual", false),
            strideByGps = pr.getBoolean("stride_by_gps", false),
        )
    }

    /** Вызывать СРАЗУ после реальной записи в SharedPreferences, не раньше. */
    suspend fun record(c: Context, timestampMs: Long = System.currentTimeMillis()) {
        AppDb.get(c).dao().insertProfileSnapshot(currentSnapshot(c, timestampMs))
    }

    /**
     * Профиль, действовавший в момент atMs.
     *
     * Порядок отката осознанный:
     *   - точка не позже atMs: точный ответ;
     *   - САМАЯ РАННЯЯ точка: для часов, прожитых до первой записи, при
     *     переходе на V11. Замораживает их на первом известном профиле. Без
     *     этого они плыли бы за текущим грузом - тот самый баг, что чиним;
     *   - текущий профиль: только если история пуста совсем.
     */
    suspend fun at(c: Context, atMs: Long): ProfileSnapshotRecord {
        val dao = AppDb.get(c).dao()
        return dao.profileAt(atMs) ?: dao.earliestProfile() ?: currentSnapshot(c, atMs)
    }
}
