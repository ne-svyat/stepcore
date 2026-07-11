package com.vasil.stepcore.survival.engine

import kotlin.math.roundToInt

/**
 * Погодное состояние одного дня мира. Полностью описывает то, что нужно
 * знать следующему дню (марковское свойство) и слою событий.
 */
data class WeatherState(
    val airMass: Double,       // медленная аномалия температуры, AR(1), -10..+10
    val tempC: Int,            // итоговая дневная температура
    val cloud: Int,            // 0 ясно - 1 переменная - 2 пасмурно
    val wind: Int,             // 0 тихо - 1 ветрено - 2 сильный - 3 буря
    val precip: Int,           // 0 нет - 1 дождь - 2 ливень/гроза - 3 мокрый снег - 4 снег
    val fog: Boolean,          // утренний туман
    val snowSeen: Boolean,     // экспедиция уже видела снег (для «первого снега»)
    val overcastStreak: Int,   // подряд пасмурных дней
    val precipStreak: Int,     // подряд дней с осадками
    val dryStreak: Int,        // подряд сухих дней
)

/**
 * Погодная модель Северной тайги (континентальный климат ~60 с.ш.).
 *
 * Устройство:
 * - годовая температурная кривая — кусочно-линейная по 8 якорям;
 * - поверх кривой живёт «воздушная масса» — авторегрессия AR(1),
 *   дающая многодневные волны холода и оттепелей вместо белого шума;
 * - облачность и ветер — марковские цепи с инерцией (погода не скачет);
 * - осадки выводятся из облачности, тип осадков — из температуры.
 *
 * Порядок обращений к rng внутри step() ФИКСИРОВАН и является частью
 * контракта детерминизма: менять его можно только с ростом
 * SurvivalEngine.ENGINE_VERSION.
 */
object WeatherModel {

    const val SEASON_DAYS = 90
    const val YEAR_DAYS = 360
    // сезоны: 0 зима - 1 весна - 2 лето - 3 осень; день 0 года = начало зимы

    // Якоря годовой кривой (день года -> типичная дневная температура).
    // Значения — сглаженный климат тайги ~60 с.ш.: минимум в середине зимы
    // около -20, максимум в середине лета около +18.
    private val ANCHOR_DAY = intArrayOf(0, 45, 90, 135, 180, 225, 270, 315, 360)
    private val ANCHOR_TEMP = doubleArrayOf(-12.0, -20.0, -8.0, 4.0, 13.0, 18.0, 9.0, -2.0, -12.0)

    // Воздушная масса: коэффициент инерции 0.85 даёт характерное время
    // волны ~5-6 дней; сигма 2.2 с клампом -10..10 — реалистичный размах
    // аномалий (мороз до -30 зимой случается, но редко).
    private const val AIR_PHI = 0.85
    private const val AIR_SIGMA = 2.2
    private const val AIR_CLAMP = 10.0
    private const val DAY_NOISE = 1.2 // суточный шум поверх волны

    // Осадки: вероятности подобраны так, чтобы пасмурная полоса почти
    // всегда приносила дождь/снег, а переменная облачность — изредка.
    private const val P_PRECIP_OVERCAST = 0.55
    private const val P_PRECIP_PARTLY = 0.15
    private const val P_HEAVY = 0.25 // доля ливней среди осадков при пасмурности

    // Туман: базовая вероятность + условия радиационного тумана
    // (влажно после осадков, тихо, ясно, умеренная температура).
    private const val FOG_BASE = 0.05
    private const val FOG_AFTER_RAIN_BONUS = 0.20
    private const val FOG_AUTUMN_FACTOR = 1.7
    private const val FOG_CAP = 0.5

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
     * Состояние «дня 0» — вечер прибытия в лагерь. Служит только опорой
     * для диффов первого дня.
     *
     * snowSeen стартует истинным зимой и весной: там снегопад — норма,
     * и текст «первый снег» был бы фальшью. Осенью и летом первый снег —
     * настоящее событие.
     */
    fun initial(rng: SplitMix64, yearDay0: Int, startSeason: Int): WeatherState {
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
        return WeatherState(
            airMass = air, tempC = temp, cloud = cloud, wind = wind,
            precip = 0, fog = false,
            snowSeen = startSeason == 0 || startSeason == 1,
            overcastStreak = if (cloud == 2) 1 else 0,
            precipStreak = 0,
            dryStreak = 3, // чтобы осадки в первые же дни дали событие «начался дождь»
        )
    }

    /** Один день мира. Порядок обращений к rng фиксирован — см. контракт выше. */
    fun step(prev: WeatherState, yearDay: Int, rng: SplitMix64): WeatherState {
        val season = seasonOf(yearDay)

        // 1-2. воздушная масса и суточный шум
        val air = (AIR_PHI * prev.airMass + rng.nextGaussian() * AIR_SIGMA)
            .coerceIn(-AIR_CLAMP, AIR_CLAMP)
        val temp = (baseTemp(yearDay) + air + rng.nextGaussian() * DAY_NOISE).roundToInt()

        // 3. облачность
        val cloud = sampleCloud(prev.cloud, season, rng.nextDouble())

        // 4. осадки: тип по температуре — снег ниже -2, мокрый снег до +1
        val precip = when (cloud) {
            2 -> if (rng.nextDouble() < P_PRECIP_OVERCAST)
                precipType(temp, heavy = rng.nextDouble() < P_HEAVY) else 0
            1 -> if (rng.nextDouble() < P_PRECIP_PARTLY)
                precipType(temp, heavy = false) else 0
            else -> 0
        }

        // 5. ветер
        val wind = sampleWind(prev.wind, rng.nextDouble())

        // 6. туман: радиационный — после вчерашних осадков, в тихую
        //    малооблачную погоду при умеренной температуре
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

    private fun precipType(temp: Int, heavy: Boolean): Int = when {
        temp <= -2 -> 4          // снег
        temp <= 1 -> 3           // мокрый снег
        heavy -> 2               // ливень (летом при тепле слой событий назовёт грозой)
        else -> 1                // дождь
    }

    /**
     * Марковская цепь облачности. Диагональ ~0.5-0.6 — полосы погоды
     * длиной 2-4 дня. Осенью и зимой часть массы «ясно» уходит
     * в «пасмурно» — сезонный перекос континентального климата.
     */
    private fun sampleCloud(from: Int, season: Int, draw: Double): Int {
        var pClear: Double; var pPartly: Double
        when (from) {
            0 -> { pClear = 0.55; pPartly = 0.30 }
            1 -> { pClear = 0.25; pPartly = 0.45 }
            else -> { pClear = 0.12; pPartly = 0.30 }
        }
        if (season == 0 || season == 3) pClear -= 0.10 // остаток уходит в пасмурно
        return if (draw < pClear) 0 else if (draw < pClear + pPartly) 1 else 2
    }

    /**
     * Марковская цепь ветра. Буря — редкое (доли процента стационарной
     * вероятности из тихих состояний) и липкое (0.25 остаться) состояние:
     * шторм длится день-два и утихает через «сильный ветер».
     */
    private fun sampleWind(from: Int, draw: Double): Int {
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
}
