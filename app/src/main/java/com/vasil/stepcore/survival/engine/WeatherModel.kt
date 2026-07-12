package com.vasil.stepcore.survival.engine

import kotlin.math.roundToInt

/**
 * Состояние мира за один день. Небо + ЗЕМЛЯ.
 *
 * До v103 существовало только небо: осадки сегодня, ветер сегодня. Земля
 * не помнила ничего, поэтому снег мог «идти» и бесследно исчезать, а фраза
 * «на свежем снегу ни одной отметины» могла выйти в сентябре после снега,
 * стаявшего к обеду. Теперь снег ложится, лежит, тает и уплотняется, река
 * замерзает и вскрывается, а зима наступает тогда, когда её видно глазами,
 * а не когда так решил календарь.
 *
 * Марковское свойство сохранено: этих полей достаточно, чтобы посчитать
 * следующий день. Ничего не сериализуется — мир пересчитывается из seed.
 */
data class WeatherState(
    val airMass: Double,       // медленная аномалия температуры, AR(1)
    val tempC: Int,            // итоговая дневная температура
    val cloud: Int,            // 0 ясно - 1 переменная - 2 пасмурно
    val wind: Int,             // 0 тихо - 1 ветрено - 2 сильный - 3 буря
    val precip: Int,           // 0 нет - 1 дождь - 2 ливень/гроза - 3 мокрый снег - 4 снег
    val fog: Boolean,          // утренний туман
    val snowSeen: Boolean,     // экспедиция уже видела снегопад
    val overcastStreak: Int,   // подряд пасмурных дней
    val precipStreak: Int,     // подряд дней с осадками
    val dryStreak: Int,        // подряд сухих дней
    // --- земля (только модель v2; в v1 остаются нулями) ---
    val front: Int = 0,        // 0 антициклон - 1 приближение - 2 прохождение - 3 тыл
    val snowMm: Int = 0,       // покров в миллиметрах — истинный накопитель
    val snowCm: Int = 0,       // он же в сантиметрах: то, что видит человек
    val riverIce: Int = 0,     // 0 открыта - 1 шуга/забереги - 2 ледостав
    val snowAgeDays: Int = 0,  // сколько дней прошло с последнего снегопада
    val coldStreak: Int = 0,   // подряд дней с t <= -5
    val thawStreak: Int = 0,   // подряд дней с t >= +2
    val winterSet: Boolean = false, // зима фактически встала (снег лежит, мороз держится)
)

/**
 * Погодная модель Северной тайги (континентальный климат ~60 с.ш.).
 *
 * Годовая кривая температуры и общая арифметика — общие для всех версий.
 * Правила одного дня существуют в ДВУХ версиях:
 *
 *  v1 — исходная: облачность и ветер как две независимые марковские цепи,
 *       земли нет. Оставлена в коде навсегда: экспедиции, начатые до v103,
 *       обязаны доигрываться по тем правилам, при которых начинались.
 *       Версия записана в паспорте (Expedition.engineVersion).
 *
 *  v2 — фронтальная: у неба появляется скрытая причина — атмосферный фронт.
 *       Из него выводятся И облачность, И ветер, И осадки, И температурный
 *       сдвиг. Поэтому «ясная буря» перестаёт быть возможной: буря приходит
 *       с фронтом, а фронт приносит облака. Плюс земля: снег, лёд, зима.
 *
 * Порядок обращений к rng внутри каждого step ФИКСИРОВАН и является частью
 * контракта детерминизма: менять его можно только с ростом версии.
 */
object WeatherModel {

    const val SEASON_DAYS = 90
    const val YEAR_DAYS = 360
    // сезоны: 0 зима - 1 весна - 2 лето - 3 осень; день 0 года = начало зимы

    // Якоря годовой кривой (день года -> типичная дневная температура).
    private val ANCHOR_DAY = intArrayOf(0, 45, 90, 135, 180, 225, 270, 315, 360)
    private val ANCHOR_TEMP = doubleArrayOf(-12.0, -20.0, -8.0, 4.0, 13.0, 18.0, 9.0, -2.0, -12.0)

    private const val AIR_PHI = 0.85
    private const val AIR_SIGMA = 2.2
    private const val AIR_CLAMP = 10.0
    private const val DAY_NOISE = 1.2

    // --- v1: осадки из облачности ---
    private const val P_PRECIP_OVERCAST = 0.55
    private const val P_PRECIP_PARTLY = 0.15
    private const val P_HEAVY = 0.25

    private const val FOG_BASE = 0.05
    private const val FOG_AFTER_RAIN_BONUS = 0.20
    private const val FOG_AUTUMN_FACTOR = 1.7
    private const val FOG_CAP = 0.5

    // --- v2: снег и лёд ---
    /** Мороз считается «настоящим» с -5: с него начинается счёт морозных дней. */
    private const val FROST_T = -5
    /** Оттепель считается с +2: с неё начинается счёт дней таяния. */
    private const val THAW_T = 2
    /** Зима встала: покров не меньше 10 см и мороз держится 4 дня подряд. */
    private const val WINTER_SNOW_CM = 10
    private const val WINTER_COLD_DAYS = 4
    /** Ледостав: 6 морозных дней подряд. Забереги: 3. Вскрытие: 4 дня оттепели. */
    private const val ICE_FULL_DAYS = 6
    private const val ICE_EDGE_DAYS = 3
    private const val ICE_OPEN_DAYS = 4
    private const val SNOW_MAX_CM = 130
    /** Испарение покрова в мороз: 2 мм в сутки. */
    private const val SUBLIMATION_MM = 2
    /** Оседание под собственным весом: 1/100 глубины в сутки. */
    private const val SETTLE_DIV = 100

    /** Кусочно-линейная годовая кривая температуры. */
    fun baseTemp(yearDay: Int): Double {
        val d = ((yearDay % YEAR_DAYS) + YEAR_DAYS) % YEAR_DAYS
        for (i in 1 until ANCHOR_DAY.size) {
            if (d <= ANCHOR_DAY[i]) {
                val d0 = ANCHOR_DAY[i - 1]; val d1 = ANCHOR_DAY[i]
                val t0 = ANCHOR_TEMP[i - 1]; val t1 = ANCHOR_TEMP[i]
                return t0 + (t1 - t0) * (d - d0).toDouble() / (d1 - d0)
            }
        }
        return ANCHOR_TEMP.last()
    }

    fun seasonOf(yearDay: Int): Int =
        (((yearDay % YEAR_DAYS) + YEAR_DAYS) % YEAR_DAYS) / SEASON_DAYS

    /**
     * Состояние «дня 0» — вечер прибытия в лагерь.
     *
     * snowSeen стартует истинным зимой и весной: там снегопад — норма,
     * и текст «первый снег» был бы фальшью.
     *
     * Стартовый покров (только v2): зимой снег уже лежит, весной — оседает,
     * осенью и летом земля голая. Иначе зимняя экспедиция начиналась бы
     * в тайге без снега, что абсурдно.
     */
    fun initial(version: Int, rng: SplitMix64, yearDay0: Int, startSeason: Int): WeatherState {
        val air = (rng.nextGaussian() * 3.0).coerceIn(-8.0, 8.0)
        val temp = (baseTemp(yearDay0) + air).roundToInt()
        val cloudDraw = rng.nextDouble()
        val gloomy = startSeason == 0 || startSeason == 3
        val cloud = when {
            cloudDraw < (if (gloomy) 0.25 else 0.40) -> 0
            cloudDraw < (if (gloomy) 0.60 else 0.75) -> 1
            else -> 2
        }
        val wind = if (rng.nextDouble() < 0.6) 0 else 1

        var snow = 0
        var ice = 0
        var winter = false
        if (version >= 2) {
            when (startSeason) {
                0 -> { snow = 35 + rng.nextInt(30); ice = 2; winter = true }
                1 -> { snow = 20 + rng.nextInt(20); ice = 2; winter = true }
                else -> { snow = 0; ice = 0; winter = false }
            }
        }
        val snowMm = snow * 10

        return WeatherState(
            airMass = air, tempC = temp, cloud = cloud, wind = wind,
            precip = 0, fog = false,
            snowSeen = startSeason == 0 || startSeason == 1,
            overcastStreak = if (cloud == 2) 1 else 0,
            precipStreak = 0,
            dryStreak = 3,
            front = 0,
            snowMm = snowMm,
            snowCm = snow,
            snowAgeDays = if (snow > 0) 2 else 0,
            riverIce = ice,
            coldStreak = if (temp <= FROST_T) 1 else 0,
            thawStreak = if (temp >= THAW_T) 1 else 0,
            winterSet = winter,
        )
    }

    /** Один день мира. Правила выбираются по версии, записанной в паспорте. */
    fun step(version: Int, prev: WeatherState, yearDay: Int, rng: SplitMix64): WeatherState =
        if (version >= 2) stepV2(prev, yearDay, rng) else stepV1(prev, yearDay, rng)

    // =================================================================
    //  v1 — заморожена. Правки запрещены: по ней доигрывают старые миры.
    // =================================================================
    private fun stepV1(prev: WeatherState, yearDay: Int, rng: SplitMix64): WeatherState {
        val season = seasonOf(yearDay)

        val air = (AIR_PHI * prev.airMass + rng.nextGaussian() * AIR_SIGMA)
            .coerceIn(-AIR_CLAMP, AIR_CLAMP)
        val temp = (baseTemp(yearDay) + air + rng.nextGaussian() * DAY_NOISE).roundToInt()

        val cloud = sampleCloudV1(prev.cloud, season, rng.nextDouble())

        val precip = when (cloud) {
            2 -> if (rng.nextDouble() < P_PRECIP_OVERCAST)
                precipType(temp, heavy = rng.nextDouble() < P_HEAVY) else 0
            1 -> if (rng.nextDouble() < P_PRECIP_PARTLY)
                precipType(temp, heavy = false) else 0
            else -> 0
        }

        val wind = sampleWindV1(prev.wind, rng.nextDouble())

        var fogP = FOG_BASE
        if (prev.precip != 0 && wind <= 1 && cloud <= 1 && temp in -3..14) {
            fogP += FOG_AFTER_RAIN_BONUS
        }
        if (season == 3) fogP *= FOG_AUTUMN_FACTOR
        val fog = rng.nextDouble() < minOf(fogP, FOG_CAP)

        return WeatherState(
            airMass = air, tempC = temp, cloud = cloud, wind = wind,
            precip = precip, fog = fog,
            snowSeen = prev.snowSeen || precip >= 3,
            overcastStreak = if (cloud == 2) prev.overcastStreak + 1 else 0,
            precipStreak = if (precip != 0) prev.precipStreak + 1 else 0,
            dryStreak = if (precip == 0) prev.dryStreak + 1 else 0,
        )
    }

    private fun sampleCloudV1(from: Int, season: Int, draw: Double): Int {
        var pClear: Double; var pPartly: Double
        when (from) {
            0 -> { pClear = 0.55; pPartly = 0.30 }
            1 -> { pClear = 0.25; pPartly = 0.45 }
            else -> { pClear = 0.12; pPartly = 0.30 }
        }
        if (season == 0 || season == 3) pClear -= 0.10
        return if (draw < pClear) 0 else if (draw < pClear + pPartly) 1 else 2
    }

    private fun sampleWindV1(from: Int, draw: Double): Int {
        val row = when (from) {
            0 -> doubleArrayOf(0.62, 0.30, 0.07, 0.01)
            1 -> doubleArrayOf(0.30, 0.48, 0.18, 0.04)
            2 -> doubleArrayOf(0.12, 0.38, 0.40, 0.10)
            else -> doubleArrayOf(0.05, 0.25, 0.45, 0.25)
        }
        var acc = 0.0
        for (i in row.indices) {
            acc += row[i]
            if (draw < acc) return i
        }
        return 3
    }

    // =================================================================
    //  v2 — фронты и земля
    // =================================================================

    /**
     * Порядок черпания случайности (контракт):
     *   1 фронт · 2-3 воздушная масса и суточный шум · 4 облачность ·
     *   5 осадки (наличие) · 6 осадки (сила) · 7 ветер · 8 туман ·
     *   9 прирост снега
     */
    private fun stepV2(prev: WeatherState, yearDay: Int, rng: SplitMix64): WeatherState {
        val season = seasonOf(yearDay)

        // 1. Фронт — скрытая причина всей погоды дня.
        val front = sampleFront(prev.front, rng.nextDouble())

        // 2-3. Температура: климат + медленная волна + сдвиг от фронта.
        //      Тёплый сектор перед фронтом теплее, тыл фронта холоднее —
        //      отсюда естественные, а не выдуманные похолодания.
        val frontShift = when (front) {
            1 -> 1.5
            2 -> 0.5
            3 -> -2.5
            else -> 0.0
        }
        val air = (AIR_PHI * prev.airMass + rng.nextGaussian() * AIR_SIGMA)
            .coerceIn(-AIR_CLAMP, AIR_CLAMP)
        val temp = (baseTemp(yearDay) + air + frontShift + rng.nextGaussian() * DAY_NOISE)
            .roundToInt()

        // 4. Облачность — следствие фронта, а не отдельная жизнь.
        val cloud = sampleCloudV2(front, rng.nextDouble())

        // 5-6. Осадки: вероятность от фронта, множитель от облачности.
        val pBase = when (front) {
            0 -> 0.03
            1 -> 0.22
            2 -> 0.68
            else -> 0.25
        }
        val cloudFactor = when (cloud) {
            0 -> 0.10
            1 -> 0.55
            else -> 1.0
        }
        val precip = if (rng.nextDouble() < pBase * cloudFactor) {
            val heavy = rng.nextDouble() < (if (front == 2) 0.30 else 0.12)
            precipType(temp, heavy)
        } else {
            rng.nextDouble() // холостой бросок: порядок rng не должен зависеть от ветки
            0
        }

        // 7. Ветер — тоже следствие фронта. Буря без фронта невозможна.
        val wind = sampleWindV2(front, rng.nextDouble())

        // 8. Туман: радиационный (после осадков, в тихую погоду) —
        //    или морозная дымка в сильный мороз при ясном небе.
        var fogP = FOG_BASE
        if (prev.precip != 0 && wind <= 1 && cloud <= 1 && temp in -3..14) {
            fogP += FOG_AFTER_RAIN_BONUS
        }
        if (temp <= -20 && wind == 0) fogP += 0.15
        if (season == 3) fogP *= FOG_AUTUMN_FACTOR
        val fog = rng.nextDouble() < minOf(fogP, FOG_CAP)

        // 9. ЗЕМЛЯ: снег ложится, тает, уплотняется.
        // Покров считается в МИЛЛИМЕТРАХ. В сантиметрах оседание застревало:
        // 14 см * 0.965 = 13.5 -> округление возвращало те же 14, и снег
        // «замерзал» на месте навсегда. Хуже того, оседание съедало по 2 см
        // в сутки даже в тридцатиградусный мороз, где таять нечему.
        var mm = prev.snowMm
        mm += when (precip) {
            4 -> 20 + rng.nextInt(41)  // 20..60 мм за сутки снегопада
            3 -> 8                     // мокрый снег ложится еле-еле
            else -> { rng.nextDouble(); 0 }
        }
        if (temp > 0) {
            // Таяние: солнце по градусам плюс дождь, который съедает снег втрое быстрее.
            var melt = temp * 15
            if (precip == 1 || precip == 2) melt += 40
            mm -= melt
        } else {
            // Мороз: покров не тает, но оседает под своим весом (1% в сутки)
            // и понемногу испаряется. Приход снегопадов и этот сток дают
            // равновесие на 60-90 см — столько и лежит в тайге к марту.
            mm -= SUBLIMATION_MM + mm / SETTLE_DIV
        }
        mm = mm.coerceIn(0, SNOW_MAX_CM * 10)
        val snow = mm / 10

        // Возраст снега: сколько дней он лежит нетронутым. Это носитель
        // информации — чем свежее покров, тем чётче на нём читается след.
        val snowAge = when {
            snow == 0 -> 0
            precip >= 3 -> 0            // снегопад переписал страницу набело
            else -> minOf(prev.snowAgeDays + 1, 99)
        }

        val coldStreak = if (temp <= FROST_T) prev.coldStreak + 1 else 0
        val thawStreak = if (temp >= THAW_T) prev.thawStreak + 1 else 0

        // Лёд на реке. Вскрытие важнее замерзания: весна сильнее зимы.
        val ice = when {
            prev.riverIce > 0 && thawStreak >= ICE_OPEN_DAYS -> 0
            coldStreak >= ICE_FULL_DAYS -> 2
            coldStreak >= ICE_EDGE_DAYS -> maxOf(prev.riverIce, 1)
            else -> prev.riverIce
        }

        // Фенологическая зима: не по календарю, а по факту — покров лежит
        // и мороз держится. Кончается зима, когда снег сошёл полностью.
        // Зима — состояние ЗЕМЛИ, а не календаря и не настроения: голая земля
        // означает, что зимы нет, чем бы ни был занят календарь.
        val winter = when {
            snow == 0 -> false
            snow >= WINTER_SNOW_CM && coldStreak >= WINTER_COLD_DAYS -> true
            else -> prev.winterSet
        }

        return WeatherState(
            airMass = air, tempC = temp, cloud = cloud, wind = wind,
            precip = precip, fog = fog,
            snowSeen = prev.snowSeen || precip >= 3,
            overcastStreak = if (cloud == 2) prev.overcastStreak + 1 else 0,
            precipStreak = if (precip != 0) prev.precipStreak + 1 else 0,
            dryStreak = if (precip == 0) prev.dryStreak + 1 else 0,
            front = front,
            snowMm = mm,
            snowCm = snow,
            snowAgeDays = snowAge,
            riverIce = ice,
            coldStreak = coldStreak,
            thawStreak = thawStreak,
            winterSet = winter,
        )
    }

    /**
     * Марковская цепь фронта. Антициклон липкий (0.70) — отсюда длинные
     * ясные полосы. Прохождение фронта короткое и почти всегда сменяется
     * тылом: погода после фронта не «щёлкает» обратно в штиль, а выдувается.
     */
    private fun sampleFront(from: Int, draw: Double): Int {
        val row = when (from) {
            0 -> doubleArrayOf(0.70, 0.24, 0.05, 0.01)
            1 -> doubleArrayOf(0.10, 0.35, 0.50, 0.05)
            2 -> doubleArrayOf(0.04, 0.11, 0.45, 0.40)
            else -> doubleArrayOf(0.42, 0.23, 0.10, 0.25)
        }
        return pick(row, draw)
    }

    private fun sampleCloudV2(front: Int, draw: Double): Int {
        val row = when (front) {
            0 -> doubleArrayOf(0.70, 0.25, 0.05)
            1 -> doubleArrayOf(0.15, 0.55, 0.30)
            2 -> doubleArrayOf(0.02, 0.18, 0.80)
            else -> doubleArrayOf(0.35, 0.45, 0.20)
        }
        return pick(row, draw)
    }

    private fun sampleWindV2(front: Int, draw: Double): Int {
        val row = when (front) {
            0 -> doubleArrayOf(0.68, 0.29, 0.03, 0.00)
            1 -> doubleArrayOf(0.26, 0.52, 0.20, 0.02)
            2 -> doubleArrayOf(0.10, 0.37, 0.46, 0.07)
            else -> doubleArrayOf(0.22, 0.47, 0.29, 0.02)
        }
        return pick(row, draw)
    }

    private fun pick(row: DoubleArray, draw: Double): Int {
        var acc = 0.0
        for (i in row.indices) {
            acc += row[i]
            if (draw < acc) return i
        }
        return row.size - 1
    }

    /** Тип осадков по температуре: снег ниже -2, мокрый снег до +1. */
    private fun precipType(temp: Int, heavy: Boolean): Int = when {
        temp <= -2 -> 4
        temp <= 1 -> 3
        heavy -> 2
        else -> 1
    }
}
