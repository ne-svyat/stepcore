package com.vasil.stepcore

import kotlin.math.pow

/**
 * Энергетическая модель V8.13. Чистый Kotlin, без Android-зависимостей:
 * тестируется отдельно, Stats.kt - лишь адаптер к профилю.
 *
 * ХОДЬБА - уравнение LCDA (Load Carriage Decision Aid, Looney et al.,
 * USARIEM 2019): полная метаболическая мощность на кг перемещаемой массы
 *   e(S) = 1.44 + 1.94*S^0.43 + 0.24*S^4  [Вт/кг], S - скорость, м/с.
 * Показываем НЕТТО (без члена покоя 1.44) - "сожжено сверх покоя".
 * Преемственно старой оценке 0.53 ккал/кг/км: при 1.3 м/с модели
 * совпадают с точностью 1%, но теперь есть зависимость от скорости
 * (быстрая ходьба дороже за км - член S^4) и от груза.
 *
 * ГРУЗ - как часть перемещаемой массы (W+L): по данным USARIEM удельная
 * стоимость кг груза в рюкзаке ~= кг собственной массы до ~30% массы
 * тела. Альтернатива Pandolf (1977) с членом 2.0*(W+L)*(L/W)^2
 * отклонена: выигрыш заметен лишь при грузах >30% массы и на уклоне,
 * а уклон без барометра неизмерим (G=0 для этого устройства).
 *
 * БЕГ - стоимость дистанции ~константа 1.03 ккал/(кг*км) (Margaria),
 * от темпа почти не зависит; прежний коэффициент, теперь на (W+L).
 */
object EnergyModel {

    /** Границы валидности LCDA по скорости, м/с. */
    private const val S_MIN = 0.4f
    private const val S_MAX = 2.5f
    private const val J_PER_KCAL = 4184f
    private const val RUN_KCAL_PER_KG_KM = 1.03f

    /** Нетто-мощность ходьбы, Вт/кг перемещаемой массы (LCDA без покоя). */
    fun walkNetWattsPerKg(speedMs: Float): Float {
        val s = speedMs.coerceIn(S_MIN, S_MAX)
        return 1.94f * s.pow(0.43f) + 0.24f * s.pow(4f)
    }

    /**
     * Ккал за ходьбу: мощность * масса * время.
     * @param movedMassKg    вес тела + груз, кг
     * @param strideM        длина шага, м
     * @param stepIntervalS  средний интервал шага, с (из калибровки)
     */
    fun walkKcal(walkSteps: Int, movedMassKg: Float, strideM: Float, stepIntervalS: Float): Float {
        if (walkSteps <= 0 || movedMassKg <= 0f || strideM <= 0f || stepIntervalS <= 0f) return 0f
        val speed = strideM / stepIntervalS
        val seconds = walkSteps * stepIntervalS
        return walkNetWattsPerKg(speed) * movedMassKg * seconds / J_PER_KCAL
    }

    /**
     * Базовый обмен (покой) за время активности - разница брутто и нетто.
     * Член покоя LCDA = 1.44 Вт/кг; брутто = нетто + это.
     */
    fun restKcal(movedMassKg: Float, seconds: Long): Float =
        if (movedMassKg <= 0f || seconds <= 0) 0f
        else 1.44f * movedMassKg * seconds / J_PER_KCAL

    /** Ккал за бег: стоимость дистанции, почти не зависит от темпа. */
    fun runKcal(runKm: Float, movedMassKg: Float): Float =
        if (runKm <= 0f || movedMassKg <= 0f) 0f
        else RUN_KCAL_PER_KG_KM * movedMassKg * runKm

    // ==================== BASAL (V9.9) ====================
    /**
     * Базовый обмен (BMR) по Mifflin-St Jeor (1990) - клинический стандарт,
     * точнее члена покоя LCDA (учитывает возраст и пол, не только массу):
     *   муж:  10*вес + 6.25*рост - 5*возраст + 5    [ккал/сутки]
     *   жен:  10*вес + 6.25*рост - 5*возраст - 161
     * BMR тела считается по СОБСТВЕННОЙ массе (без груза): базовый обмен
     * не зависит от рюкзака, груз влияет только на Active.
     */
    fun bmrPerDay(weightKg: Float, heightCm: Int, age: Int, male: Boolean): Float {
        if (weightKg <= 0f || heightCm <= 0) return 0f
        val base = 10f * weightKg + 6.25f * heightCm - 5f * age
        return (base + if (male) 5f else -161f).coerceAtLeast(0f)
    }

    /**
     * Basal-калории за прошедшую долю суток. BMR идёт непрерывно 24/7,
     * поэтому считается от КАЛЕНДАРНОГО времени (доля суток), а не от
     * аптайма сервиса - иначе цифра зависела бы от момента включения.
     */
    fun basalKcalForElapsed(bmrPerDay: Float, secondsOfDay: Long): Float {
        if (bmrPerDay <= 0f || secondsOfDay <= 0) return 0f
        return bmrPerDay * secondsOfDay.coerceAtMost(86_400L) / 86_400f
    }
}
