package com.vasil.stepcore.survival.engine

/**
 * Семантическое событие мира: ключ шаблона + параметры подстановки + контекст.
 *
 * roll — бросок для выбора варианта текста: детерминирован тиком,
 * поэтому рендер не зависит от общего потока случайности.
 *
 * ctx — числовое состояние дня (температура, ветер, осадки, сезон...).
 * Корпус фильтрует по нему варианты: строка про мороз не может выпасть
 * в тёплый день. Ключ отвечает за то, ЧТО произошло; ctx — за то,
 * КАКИМ языком об этом можно говорить.
 */
data class WorldEvent(
    val tick: Int,
    val category: String, // milestone / weather / digest / ambient
    val key: String,
    val roll: Long,
    val params: Map<String, String> = emptyMap(),
    val ctx: Map<String, Int> = emptyMap(),
    val nth: Int = 0, // какое по счёту срабатывание этого ключа за экспедицию
)

/** Сводка по прожитым дням — для финальной истории и архива. */
data class ExpeditionSummary(
    val days: Int,
    val rainDays: Int,
    val snowDays: Int,
    val stormDays: Int,
    val fogMornings: Int,
    val minTemp: Int,
    val maxTemp: Int,
    val firstSnowDay: Int, // -1, если снега не было или он был с самого старта
)

/**
 * Движок экспедиции: превращает номера тиков в события мира.
 *
 * Принцип хранения состояния: состояние НЕ сериализуется. Мир каждый раз
 * пересчитывается из seed с первого тика — это дешевле и надёжнее любой
 * сериализации (тик — микросекунды арифметики), а детерминизм становится
 * структурным свойством, а не дисциплиной. Журнал при этом append-only:
 * события уже прожитых тиков никогда не перегенерируются.
 */
class SurvivalEngine(
    private val seed: Long,
    private val startSeason: Int, // 0 зима - 1 весна - 2 лето - 3 осень
    private val startOffset: Int, // день внутри стартового сезона
) {

    /** День года для тика (тик 1 = первый полный день экспедиции). */
    fun yearDay(tick: Int): Int =
        (startSeason * WeatherModel.SEASON_DAYS + startOffset + tick - 1) % WeatherModel.YEAR_DAYS

    fun seasonOf(tick: Int): Int = WeatherModel.seasonOf(yearDay(tick))

    /**
     * Прожить мир до тика toInclusive; события эмитить только для тиков
     * строго после fromExclusive (более ранние уже лежат в журнале).
     * Возвращает сводку по всему диапазону 1..toInclusive.
     *
     * Пропуск эмиссии для старых тиков безопасен: у каждого тика свой
     * изолированный rng, черпание случайности внутри тика не влияет
     * на соседние.
     */
    fun run(fromExclusive: Int, toInclusive: Int, sink: (WorldEvent) -> Unit): ExpeditionSummary {
        var state = WeatherModel.initial(
            SplitMix64.forTick(seed, 0), yearDay(0), startSeason
        )
        var rain = 0; var snow = 0; var storm = 0; var fogs = 0
        var minT = Int.MAX_VALUE; var maxT = Int.MIN_VALUE
        var firstSnow = -1
        // Счётчик срабатываний ключей ведётся по ВСЕМ тикам, а не только по
        // выдаваемым: иначе догон кусками сдвигал бы выбор вариантов.
        val seen = HashMap<String, Int>()

        for (t in 1..toInclusive) {
            val rng = SplitMix64.forTick(seed, t)
            val prev = state
            state = WeatherModel.step(prev, yearDay(t), rng)

            if (state.precip == 1 || state.precip == 2) rain++
            if (state.precip >= 3) snow++
            if (state.wind == 3 || (state.precip == 4 && state.wind >= 2)) storm++
            if (state.fog) fogs++
            if (state.tempC < minT) minT = state.tempC
            if (state.tempC > maxT) maxT = state.tempC
            if (firstSnow < 0 && !prev.snowSeen && state.snowSeen) firstSnow = t

            emit(t, prev, state, rng, seen, t > fromExclusive, sink)
        }

        val d = maxOf(toInclusive, 0)
        return ExpeditionSummary(
            days = d, rainDays = rain, snowDays = snow, stormDays = storm,
            fogMornings = fogs,
            minTemp = if (d == 0) 0 else minT,
            maxTemp = if (d == 0) 0 else maxT,
            firstSnowDay = firstSnow,
        )
    }

    /**
     * Эмиссия событий дня. Правила:
     * - смена сезона — вне конкуренции, отдельной строкой;
     * - погодное событие — максимум одно, по убыванию значимости;
     * - у длящихся явлений есть СВОИ ключи (_hold): раньше второй день
     *   бури проваливался в «сводку тихого дня» и журнал врал
     *   («День спокойный: ясно, +16°, буря»);
     * - температурные события разделены по АБСОЛЮТНОЙ температуре, а не
     *   только по величине падения: летний спад на 7° — это не мороз;
     * - если событий нет — сводка дня из живых данных, изредка зарисовка.
     */
    private fun emit(
        t: Int, prev: WeatherState, st: WeatherState,
        rng: SplitMix64, seen: HashMap<String, Int>, deliver: Boolean,
        sink: (WorldEvent) -> Unit,
    ) {
        // Выдача события: сначала считаем срабатывание ключа (всегда),
        // потом отдаём наружу (только для новых тиков).
        fun fire(category: String, key: String, roll: Long,
                 params: Map<String, String>, ctx: Map<String, Int>) {
            val nth = seen[key] ?: 0
            seen[key] = nth + 1
            if (deliver) sink(WorldEvent(t, category, key, roll, params, ctx, nth))
        }

        val season = seasonOf(t)
        val dt = st.tempC - prev.tempC
        val ctx = mapOf(
            "day" to t,
            "t" to st.tempC,
            "dt" to dt,
            "season" to season,
            "cloud" to st.cloud,
            "wind" to st.wind,
            "precip" to st.precip,
            "fog" to (if (st.fog) 1 else 0),
            "snowseen" to (if (st.snowSeen) 1 else 0),
        )

        if (t > 1 && seasonOf(t) != seasonOf(t - 1)) {
            fire("milestone", "season.to_" + SEASON_EN[season], rng.nextLong(), emptyMap(), ctx)
        }

        val blizzardNow = st.precip == 4 && st.wind >= 2
        val blizzardPrev = prev.precip == 4 && prev.wind >= 2
        val wetNow = st.precip == 1 || st.precip == 2
        val wetPrev = prev.precip == 1 || prev.precip == 2

        val key: String? = when {
            // --- фронты и длящиеся состояния сильных явлений ---
            blizzardNow && !blizzardPrev -> "wx.blizzard"
            blizzardNow && blizzardPrev -> "wx.blizzard_hold"
            st.wind == 3 && prev.wind < 3 -> "wx.storm"
            st.wind == 3 && prev.wind == 3 -> "wx.storm_hold"

            // --- осадки: начало ---
            !prev.snowSeen && st.snowSeen -> "wx.snow_first"
            st.precip == 2 && prev.precip != 2 ->
                if (season == 2 && st.tempC >= 16) "wx.thunder" else "wx.heavy_rain"
            st.precip == 1 && prev.precip == 0 && prev.dryStreak >= 2 -> "wx.rain_start"
            st.precip == 3 && prev.precip == 0 -> "wx.sleet_start"
            st.precip == 4 && prev.precip == 0 -> "wx.snow_start"

            // --- осадки: затяжные (каждый третий день ненастья) ---
            wetNow && st.precipStreak >= 3 && st.precipStreak % 3 == 0 -> "wx.rain_hold"
            st.precip >= 3 && st.precipStreak >= 3 && st.precipStreak % 3 == 0 -> "wx.snow_hold"

            // --- осадки: конец ---
            st.precip == 0 && wetPrev && prev.precipStreak >= 2 -> "wx.rain_stop"
            st.precip == 0 && prev.precip >= 3 && prev.precipStreak >= 2 -> "wx.snow_stop"

            // --- температура: по абсолютному значению, не только по дельте ---
            st.tempC <= -25 && prev.tempC > -25 -> "wx.hard_frost"
            dt <= -7 && st.tempC <= 5 -> "wx.cold_snap"
            dt <= -7 -> "wx.cool_down"
            st.snowSeen && st.tempC >= 1 && prev.tempC < 0 -> "wx.thaw"
            st.tempC >= 27 && prev.tempC < 27 -> "wx.heat"

            // --- небо ---
            st.cloud == 0 && prev.overcastStreak >= 4 -> "wx.clear_streak"
            st.fog && !prev.fog -> "wx.fog"
            st.cloud == 2 && st.precip == 0 && st.overcastStreak >= 6 &&
                st.overcastStreak % 3 == 0 -> "wx.gloom_hold"
            st.wind == 2 && prev.wind <= 1 && rng.nextDouble() < 0.4 -> "wx.wind_strong"

            else -> null
        }

        if (key != null) {
            fire("weather", key, rng.nextLong(), mapOf("temp" to fmtTemp(st.tempC)), ctx)
        } else {
            // Тихий день: сводка наблюдательной сети из ЖИВЫХ данных.
            // Формулировки «день спокойный» отфильтрует корпус: они помечены
            // условиями (тихо, без осадков, без тумана).
            fire("digest", "digest", rng.nextLong(), mapOf(
                "sky" to skyWord(st.cloud, st.fog),
                "wind" to windWord(st.wind),
                "temp" to fmtTemp(st.tempC),
            ), ctx)
            // Редкая бытовая зарисовка ПОВЕРХ сводки — приправа, не замена.
            if (rng.nextDouble() < AMBIENT_P) {
                fire("ambient", "ambient", rng.nextLong(), emptyMap(), ctx)
            }
        }
    }

    companion object {
        /**
         * Версия правил МИРА (генерация погоды). v102 меняет только выбор
         * и формулировку событий, сам мир не тронут — версия остаётся 1,
         * активные экспедиции продолжаются без разрыва.
         */
        const val ENGINE_VERSION = 1

        /**
         * Вероятность бытовой зарисовки в тихий день. 0.18 подобрано
         * прогоном: вместе с погодными событиями даёт ~0.4-0.5 записи
         * на день мира — журнал живой, но тишина остаётся тишиной.
         */
        const val AMBIENT_P = 0.18

        val SEASON_EN = arrayOf("winter", "spring", "summer", "autumn")
        val SEASON_RU = arrayOf("зима", "весна", "лето", "осень")

        /**
         * Смещение старта внутри сезона: день 5..30, из seed.
         *
         * Было 10..50: осенняя экспедиция начиналась на 40-й день осени,
         * то есть фактически в предзимье — первый снег на первой неделе,
         * минус к четвёртому дню. Человек выбирал осень и не видел осени.
         * 5..30 оставляет стартовому сезону не меньше двух месяцев
         * характера и по-прежнему позволяет длинной экспедиции застать
         * смену сезона.
         */
        fun startOffsetFrom(seed: Long): Int = 5 + SplitMix64.forTick(seed, 0).nextInt(26)

        fun fmtTemp(t: Int): String = if (t > 0) "+" + t else t.toString()

        /** Словесное небо для сводки дня. Туман приоритетнее облачности. */
        fun skyWord(cloud: Int, fog: Boolean): String = when {
            fog -> "туманно"
            cloud == 0 -> "ясно"
            cloud == 1 -> "переменная облачность"
            else -> "пасмурно"
        }

        /** Словесный ветер для сводки дня. */
        fun windWord(wind: Int): String = when (wind) {
            0 -> "тихо"
            1 -> "ветрено"
            2 -> "сильный ветер"
            else -> "буря"
        }

        /** Русское склонение: 1 день - 2 дня - 5 дней - 11 дней - 21 день. */
        fun daysWord(n: Int): String {
            val m10 = n % 10
            val m100 = n % 100
            return when {
                m100 in 11..14 -> "дней"
                m10 == 1 -> "день"
                m10 in 2..4 -> "дня"
                else -> "дней"
            }
        }
    }
}
