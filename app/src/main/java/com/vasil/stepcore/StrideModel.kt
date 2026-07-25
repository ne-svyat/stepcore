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
    // v229. Пороги двухточечной калибровки (Шаг Б).
    private const val MIN_CADENCE_GAP = 0.15f   // ближе - наклон не определить
    private const val SANE_A_MIN = 0.15f        // разумный наклон человека
    private const val SANE_A_MAX = 0.65f
    private const val HEIGHT_FACTOR_WALK = 0.414f
    private const val HEIGHT_FACTOR_RUN = 0.65f

    private fun p(c: Context) =
        c.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)

    // ===== ЧИСТЫЕ ФОРМУЛЫ (V11.1) =====
    // Без Context: считают по ПЕРЕДАННЫМ параметрам, а не по живым prefs.
    // Нужны Stats.energyForHour, который берёт профиль ИЗ ИСТОРИИ. Обёртки
    // ниже делегируют сюда: одна формула, два способа достать параметры
    // (ARCHITECTURE_RULES - источник истины один).

    fun walkCadenceHzOf(minIntervalMs: Long, maxIntervalMs: Long): Float {
        val medMs = (minIntervalMs + maxIntervalMs) / 2f
        return if (medMs > 0) 1000f / medMs else 1.8f
    }

    /** v220. Каденс из ЖИВОГО среднего интервала часа (мс -> Гц).
     *  Диапазон зажат: вне 1.3..2.4 Гц линейная модель длины шага не
     *  валидна (PDR-литература), а мусорный интервал не должен раздуть
     *  дистанцию. count == 0 -> нет измерений -> вернём 0, вызывающий
     *  откатится на константу профиля. */
    fun cadenceHzFromHour(intervalSum: Long, stepCount: Int): Float {
        if (stepCount <= 0 || intervalSum <= 0L) return 0f
        val medMs = intervalSum.toFloat() / stepCount
        if (medMs <= 0f) return 0f
        return (1000f / medMs).coerceIn(1.3f, 2.4f)
    }

    fun walkStrideMOf(
        cadenceHz: Float, strideManual: Boolean,
        strideA: Float, strideB: Float, heightCm: Int
    ): Float = if (strideManual) (strideA * cadenceHz + strideB).coerceIn(0.3f, 1.2f)
        else if (heightCm > 0) heightCm * HEIGHT_FACTOR_WALK / 100f else 0.7f

    fun runStrideMOf(heightCm: Int): Float =
        if (heightCm > 0) heightCm * HEIGHT_FACTOR_RUN / 100f else 1.0f

    fun source(c: Context): Source = when {
        !p(c).getBoolean("stride_manual", false) -> Source.ESTIMATE
        p(c).getBoolean("stride_by_gps", false) -> Source.GPS
        else -> Source.MANUAL
    }

    /** Длина шага ходьбы для заданного каденса (Гц), по ТЕКУЩЕМУ профилю. */
    fun walkStrideM(c: Context, cadenceHz: Float): Float {
        val pr = p(c)
        return walkStrideMOf(
            cadenceHz,
            pr.getBoolean("stride_manual", false),
            pr.getFloat("stride_a", A_DEFAULT),
            pr.getFloat("stride_b", 0f),
            pr.getInt("p_height", 0)
        )
    }

    /** Средняя длина шага ходьбы по калиброванному каденсу (для сумм за день). */
    fun walkStrideAvgM(c: Context): Float = walkStrideM(c, avgWalkCadenceHz(c))

    fun runStrideM(c: Context): Float = runStrideMOf(p(c).getInt("p_height", 0))

    /** Каденс ходьбы из калибровки интервалов: med = (lo+hi)/2 -> Гц. */
    fun avgWalkCadenceHz(c: Context): Float {
        val pr = p(c)
        return walkCadenceHzOf(
            pr.getLong("walk_min_interval", 400L),
            pr.getLong("walk_max_interval", 1200L)
        )
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
        val pr = p(c)

        // Есть ли уже сохранённая ПЕРВАЯ точка на заметно другом каденсе?
        val hadPoint = pr.getBoolean("stride_manual", false)
        val c1 = pr.getFloat("stride_cal_cadence", 0f)
        val sl1 = pr.getFloat("stride_measured_sl", 0f)

        val solved = if (hadPoint && c1 > 0f && sl1 > 0f)
            solveTwoPoint(c1, sl1, cadence, measuredSL) else null

        val a: Float
        val b: Float
        if (solved != null) {
            a = solved.first; b = solved.second
        } else {
            // Первая точка или вторая слишком близко/кривая: наклон табличный,
            // сдвиг из текущей точки. Динамика уже работает (v220).
            a = A_DEFAULT
            b = measuredSL - A_DEFAULT * cadence
        }

        pr.edit()
            .putFloat("stride_a", a)
            .putFloat("stride_b", b)
            .putBoolean("stride_manual", true)
            .putBoolean("stride_by_gps", byGps)
            .putFloat("stride_measured_sl", measuredSL)
            .putFloat("stride_cal_cadence", cadence)
            .putBoolean("stride_personal_slope", solved != null)
            .apply()
    }

    /** Решает прямую SL = a*cadence + b по двум точкам. Возвращает (a,b) или
     *  null, если точки не годятся: близкие каденсы или неправдоподобный
     *  наклон (GPS-дрейф, разный маршрут). null -> откат на табличный наклон. */
    fun solveTwoPoint(c1: Float, sl1: Float, c2: Float, sl2: Float): Pair<Float, Float>? {
        if (kotlin.math.abs(c2 - c1) < MIN_CADENCE_GAP) return null
        val a = (sl2 - sl1) / (c2 - c1)
        if (a < SANE_A_MIN || a > SANE_A_MAX) return null
        val b = sl1 - a * c1
        return a to b
    }

    /** Персональный ли наклон (обе калибровки сошлись) или ещё табличный. */
    fun hasPersonalSlope(c: Context): Boolean =
        p(c).getBoolean("stride_personal_slope", false)

    fun reset(c: Context) {
        p(c).edit()
            .remove("stride_a").remove("stride_b")
            .remove("stride_manual").remove("stride_by_gps").remove("stride_measured_sl")
            .remove("stride_cal_cadence").remove("stride_personal_slope")
            .apply()
    }

    /** Для UI: измеренная длина шага в см или null, если не калибровано. */
    fun measuredStrideCm(c: Context): Int? {
        val pr = p(c)
        if (!pr.getBoolean("stride_manual", false)) return null
        return (pr.getFloat("stride_measured_sl", 0f) * 100).toInt()
    }
}
