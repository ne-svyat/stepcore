package com.vasil.stepcore

import android.content.Context

/**
 * StrideModel (V9.3) - единственная ответственность: длина шага.
 * Чистый Kotlin поверх SharedPreferences, без Android-логики сверх чтения.
 *
 * Три уровня доверия к длине шага (source), каждый честно помечен в UI:
 *   ESTIMATE - от роста (SL = 0.414 * рост), ±15%, без действий;
 *   MANUAL   - измерена калибровкой на известной дистанции, ±5%.
 * (AUTO зарезервирован под V10: подстройка формы кривой из чистых сессий
 *  с привязкой к MANUAL как ground truth. Без метрики доверия не включаем -
 *  тапы/шум отравили бы модель, см. лог 07.07.)
 *
 * Модель длины: SL(cadence) = a * cadenceHz + b [метры].
 * Физиология: ускорение идёт в основном в удлинение шага, в диапазоне
 * 1.4-2.2 Гц зависимость линейна (PDR-литература). Два прохода на разных
 * темпах -> точное решение (a, b); один проход -> сдвиг b при табличном
 * a = 0.37 (наклон популяционный, абсолют персональный).
 */
object StrideModel {

    enum class Source { ESTIMATE, MANUAL, GPS }

    const val A_DEFAULT = 0.37f          // популяционный наклон, м/Гц
    private const val HEIGHT_FACTOR_WALK = 0.414f
    private const val HEIGHT_FACTOR_RUN = 0.65f

    private fun p(c: Context) =
        c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    fun source(c: Context): Source = when {
        !p(c).getBoolean("stride_manual", false) -> Source.ESTIMATE
        p(c).getBoolean("stride_by_gps", false) -> Source.GPS
        else -> Source.MANUAL
    }

    /** Длина шага ходьбы для заданного каденса (Гц). */
    fun walkStrideM(c: Context, cadenceHz: Float): Float {
        val pr = p(c)
        if (pr.getBoolean("stride_manual", false)) {
            val a = pr.getFloat("stride_a", A_DEFAULT)
            val b = pr.getFloat("stride_b", 0f)
            return (a * cadenceHz + b).coerceIn(0.3f, 1.2f)
        }
        val h = pr.getInt("p_height", 0)
        return if (h > 0) h * HEIGHT_FACTOR_WALK / 100f else 0.7f
    }

    /** Средняя длина шага ходьбы по калиброванному каденсу (для сумм за день). */
    fun walkStrideAvgM(c: Context): Float = walkStrideM(c, avgWalkCadenceHz(c))

    fun runStrideM(c: Context): Float {
        val h = p(c).getInt("p_height", 0)
        return if (h > 0) h * HEIGHT_FACTOR_RUN / 100f else 1.0f
    }

    /** Каденс ходьбы из калибровки интервалов: med = (lo+hi)/2 -> Гц. */
    fun avgWalkCadenceHz(c: Context): Float {
        val pr = p(c)
        val lo = pr.getLong("walk_min_interval", 400L)
        val hi = pr.getLong("walk_max_interval", 1200L)
        val medMs = (lo + hi) / 2f
        return if (medMs > 0) 1000f / medMs else 1.8f
    }

    /**
     * Результат калибровки дистанции: metres пройдено за steps шагов.
     * Один вызов -> сдвиг b (наклон табличный). Модель помечается MANUAL.
     * Второй вызов с ДРУГИМ каденсом мог бы решить (a,b) точно - задел,
     * пока сохраняем последнюю точку и сдвиг.
     */
    fun applyCalibration(c: Context, metres: Float, steps: Int, byGps: Boolean = false) {
        if (steps <= 0 || metres <= 0f) return
        val measuredSL = metres / steps
        val cadence = avgWalkCadenceHz(c)
        val b = measuredSL - A_DEFAULT * cadence
        p(c).edit()
            .putFloat("stride_a", A_DEFAULT)
            .putFloat("stride_b", b)
            .putBoolean("stride_manual", true)
            .putBoolean("stride_by_gps", byGps)
            .putFloat("stride_measured_sl", measuredSL)
            .apply()
    }

    fun reset(c: Context) {
        p(c).edit()
            .remove("stride_a").remove("stride_b")
            .remove("stride_manual").remove("stride_by_gps").remove("stride_measured_sl")
            .apply()
    }

    /** Для UI: измеренная длина шага в см или null, если не калибровано. */
    fun measuredStrideCm(c: Context): Int? {
        val pr = p(c)
        if (!pr.getBoolean("stride_manual", false)) return null
        return (pr.getFloat("stride_measured_sl", 0f) * 100).toInt()
    }
}
