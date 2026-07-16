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
    val nth: Int = 0,   // какое по счёту срабатывание этого ключа за экспедицию
    val phase: Int = 1, // 0 утро - 1 день - 2 вечер - 3 ночь
)

/**
 * НАБЛЮДЕНИЕ — то, что человек в лагере МОГ узнать о звере.
 *
 * Это не позиция зверя. Это отчёт очевидца, у которого есть глаза, уши и
 * снег под ногами. Три источника — три разные честности:
 *   SEEN  — видел сам: румб точен, расстояние почти точно;
 *   TRACK — прочитал след утром: румб точен, расстояние — догадка;
 *   HEARD — услышал вой или возню на добыче: и румб, и дальность на слух.
 *
 * НЕ ХРАНИТСЯ. Как и весь мир — пересчитывается из seed. Поэтому радар
 * работает и у экспедиций, прожитых до этого обновления, и у архивных.
 *
 * Наблюдение рождается ровно там, где рождается СТРОКА ЖУРНАЛА, и ни на
 * шаг раньше: радар не имеет права знать то, о чём журнал промолчал.
 */
data class Obs(
    val tick: Int,
    val phase: Int,
    val kind: String,     // wolf / bear / wolverine / lynx / moose
    val sector: Int,      // румб от лагеря: 0 С .. 7 СЗ
    val distKm: Double,   // расстояние в МОМЕНТ наблюдения
    val source: Int,      // SEEN / TRACK / HEARD
    val packSize: Int,
    /**
     * Насколько человек мог ошибиться со стороной. 0 — уверен, 2 — «эхо
     * множит, направление не угадать». Считается по погоде того дня, а не
     * броском: в туман и пургу слух врёт всегда одинаково.
     */
    val bearingErr: Int = 0,
) {
    companion object {
        const val SEEN = 0
        const val TRACK = 1
        const val HEARD = 2
    }
}

/**
 * Состояние одного дня мира для шапки карточки журнала.
 *
 * НЕ хранится в базе: мир детерминирован, поэтому день дешевле пересчитать
 * из seed, чем сериализовать. Отсюда важное следствие — карточки появляются
 * и у экспедиций, прожитых ДО этого обновления, включая архивные.
 */
data class DaySnap(
    val tick: Int,
    val tempC: Int,
    val cloud: Int,
    val wind: Int,
    val precip: Int,
    val fog: Boolean,
    val snowCm: Int,
    val riverIce: Int,
    val windDir: Int, // румб, откуда дует: 0 С .. 7 СЗ
    // v118: показания неба. Нужны, чтобы посчитать границу знания дня,
    // не пересобирая весь мир: снимок дня несёт всё, от чего она зависит.
    val light: Int = 120,
    val moon: Int = 0,
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
    val firstSnowDay: Int,  // -1, если снега не было или он был с самого старта
    val snowCoverDays: Int, // дней, когда снег ЛЕЖАЛ (не путать со снежными днями)
    val maxSnowCm: Int,     // наибольшая глубина покрова
    // v7. Мир остановился и ждёт решения игрока: встреча дня haltedDay не
    // разрешена. -1 — остановки нет. Прогресс дальше (haltedDay-1) не минтится.
    val haltedDay: Int = -1,
    val haltedKind: String = "",
    val haltedText: String = "",
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
    private val version: Int = ENGINE_VERSION, // правила мира из паспорта экспедиции
) {

    /** День года для тика (тик 1 = первый полный день экспедиции). */
    fun yearDay(tick: Int): Int =
        (startSeason * WeatherModel.SEASON_DAYS + startOffset + tick - 1) % WeatherModel.YEAR_DAYS

    fun seasonOf(tick: Int): Int = WeatherModel.seasonOf(yearDay(tick))

    /**
     * Прожить мир до фазы toInclusive; события эмитить только для фаз строго
     * после fromExclusive (более ранние уже лежат в журнале).
     *
     * ЕДИНИЦА ПРОГРЕССА ТЕПЕРЬ — ФАЗА, А НЕ ДЕНЬ. День мира состоит из
     * четырёх фаз (утро, день, вечер, ночь), и каждая стоит четверть дневной
     * нормы шагов. Поэтому запись падает в журнал ПО ХОДУ дня, а не задним
     * числом в его конце: прошёл четверть нормы — прочитал утренний след;
     * прошёл всю — услышал ночной вой.
     *
     * Глобальный номер фазы: (день - 1) * 4 + фаза + 1. День 0 (прибытие)
     * имеет номер 0 и выдаётся при первой же синхронизации.
     *
     * Сводка считается только по ПОЛНОСТЬЮ прожитым дням: половина дня —
     * это ещё не день, и в статистику она попасть не может.
     */
    fun run(
        fromExclusive: Int,
        toInclusive: Int,
        obsSink: (Obs) -> Unit = {},
        // v6. Курс дня: 0..7 — заданный игроком (из сохранённой тропы), -1 —
        // решает автопилот. plannedHeading отдаёт заданный курс уже прожитых
        // дней (архив стабилен), headingSink принимает фактически пройденный
        // курс КАЖДОГО дня — чтобы автопилотные дни тоже легли в тропу и мир
        // не переигрывался, если логика автопилота однажды поумнеет.
        plannedHeading: (Int) -> Int = { -1 },
        headingSink: (Int, Int) -> Unit = { _, _ -> },
        // v7. Выбор игрока во встрече дня t: 0..N-1, -1 — решения ещё нет.
        // Нет решения на НОВОМ дне — мир останавливается и ждёт (halt).
        choiceProvider: (Int) -> Int = { -1 },
        sink: (WorldEvent) -> Unit,
    ): ExpeditionSummary {
        var state = WeatherModel.initial(
            version, SplitMix64.forTick(seed, 0), yearDay(0), startSeason
        )
        var rain = 0; var snow = 0; var storm = 0; var fogs = 0
        var minT = Int.MAX_VALUE; var maxT = Int.MIN_VALUE
        var firstSnow = -1
        var coverDays = 0; var maxSnow = 0
        // Счётчик срабатываний ключей ведётся по ВСЕМ тикам, а не только по
        // выдаваемым: иначе догон кусками сдвигал бы выбор вариантов.
        val seen = HashMap<String, Int>()

        // Ветер и звери живут в СВОИХ потоках случайности (соль на seed).
        // Погодный поток от этого не сдвигается ни на один бросок — миры
        // старых экспедиций остаются теми же, какими были.
        var windDir = WindField.initial(seed)
        val fauna = if (version >= 2) FaunaModel.spawn(seed, startSeason) else emptyList()

        // v7. Живая тайга: места дичи и находки стоят в мире; контекст помнит,
        // что найдено за прогон. Гарантия: крупный зверь есть в достижимости.
        val pockets = if (version >= 7) WildModel.pockets(seed) else emptyList()
        val finds = if (version >= 7) WildModel.finds(seed) else emptyList()
        val wctx = WildModel.Ctx()
        if (version >= 7) FaunaModel.ensureNear(fauna, seed)
        var halted = -1; var haltKind = ""; var haltText = ""

        // v6. Позиция игрока (км, абсолютные). Лагерь стоит там, где игрок.
        // Старт — начало координат: в первый день ты там, где высадился.
        var playerX = 0.0; var playerY = 0.0
        // То, что игрок УЖЕ знает о зверях — ровно радарные наблюдения, не
        // истинные позиции. Автопилот смотрит только сюда: он слеп там, где
        // слеп ты, и не имеет права рулить по тому, чего человек не видел.
        val lastObs = HashMap<String, Obs>()
        val obsSink2: (Obs) -> Unit = { o -> lastObs[o.kind] = o; obsSink(o) }

        // Мир считается до последнего ЗАТРОНУТОГО дня, даже если он прожит
        // наполовину: утренние события этого дня уже случились.
        val lastDay = (toInclusive + PHASES - 1) / PHASES
        val fullDays = toInclusive / PHASES

        for (t in 1..lastDay) {
            val rng = SplitMix64.forTick(seed, t)
            val prev = state
            state = WeatherModel.step(version, prev, yearDay(t), rng)

            if (t <= fullDays) {
                if (state.precip == 1 || state.precip == 2) rain++
                if (state.precip >= 3) snow++
                if (state.wind == 3 || (state.precip == 4 && state.wind >= 2)) storm++
                if (state.fog) fogs++
                if (state.tempC < minT) minT = state.tempC
                if (state.tempC > maxT) maxT = state.tempC
                if (firstSnow < 0 && !prev.snowSeen && state.snowSeen) firstSnow = t
                if (state.snowCm > 0) coverDays++
                if (state.snowCm > maxSnow) maxSnow = state.snowCm
            }

            windDir = WindField.step(seed, t, windDir, state.front)

            // v7. ВСТРЕЧА решается ДО перехода: вы сошлись на утренней тропе.
            // Нет решения на новом дне — мир останавливается и ждёт игрока.
            var lostDay = false
            if (version >= 7) {
                val enc = WildModel.encounterAt(seed, t, fauna, wctx, playerX, playerY)
                if (enc != null) {
                    val ch = choiceProvider(t)
                    if (ch < 0 && (t - 1) * PHASES >= fromExclusive) {
                        halted = t; haltKind = enc.kind; haltText = enc.meetTxt
                        break
                    }
                    val out = WildModel.resolve(seed, t, enc.kind,
                        ch.coerceIn(0, enc.options.size - 1))
                    lostDay = out.lostDay
                    if (out.woundDays > 0) { wctx.woundDays = out.woundDays; wctx.woundKind = enc.kind }
                    if (out.scared) WildModel.scare(fauna, enc.kind, playerX, playerY)
                    val g = globalPhase(t, MORNING)
                    if (g > fromExclusive && g <= toInclusive) {
                        sink(WorldEvent(t, "animal", "raw", 0,
                            params = mapOf("txt" to enc.meetTxt), phase = MORNING))
                        sink(WorldEvent(t, "animal", "raw", 0,
                            params = mapOf("txt" to out.txt), phase = MORNING))
                    }
                    // Зверь был вплотную — это знание, и оно на радаре.
                    val (ed, es) = polarOf(fauna, enc.kind, playerX, playerY)
                    obsSink2(Obs(t, MORNING, enc.kind, es, ed, Obs.SEEN,
                        fauna.firstOrNull { it.kind == enc.kind }?.packSize ?: 1))
                }
            }

            // v6/v7. Дневной переход. Рана режет ход, потерянный день — стоянка.
            if (version >= 6) {
                val planned = plannedHeading(t)
                val heading = if (planned in 0..7) planned
                    else autopilotHeading(seed, t, lastObs, playerX, playerY)
                val pace = when {
                    version < 7 -> TRAVEL_KM_PER_DAY
                    lostDay -> 0.0
                    wctx.woundDays > 0 -> TRAVEL_KM_PER_DAY * 0.4
                    else -> TRAVEL_KM_PER_DAY
                }
                playerX += pace * Math.sin(heading * Math.PI / 4.0)
                playerY += pace * Math.cos(heading * Math.PI / 4.0)
                headingSink(t, heading)
            }

            // v7. Рана живёт днями: ноет в журнале, заживает — отпускает.
            if (version >= 7 && wctx.woundDays > 0) {
                wctx.woundDays--
                val g = globalPhase(t, EVENING)
                if (g > fromExclusive && g <= toInclusive) {
                    val wr = SplitMix64.forTick(seed xor WildModel.ENC_SALT, t * 7 + 3).nextLong()
                    val txt = if (wctx.woundDays == 0) WildModel.woundHealedLine(wr)
                        else if (t % 2 == 0) WildModel.woundLine(wr, wctx.woundKind) else null
                    if (txt != null) sink(WorldEvent(t, "wound", "raw", 0,
                        params = mapOf("txt" to txt), phase = EVENING))
                }
            }

            val fr = if (fauna.isEmpty()) null
                else FaunaModel.step(fauna, state, windDir, seasonOf(t), seed, t, version,
                    lightX10(yearDay(t)), moonPhase(yearDay(t), seed), playerX, playerY)

            emit(t, prev, state, rng, windDir, fr, seen, fromExclusive, toInclusive,
                sink, obsSink2)

            // v7. Дичь и находки вокруг НОВОЙ стоянки: радар полнится каждый
            // день, журнал — когда есть что сказать.
            if (version >= 7) {
                for (n in WildModel.dayNotes(seed, t, seasonOf(t), pockets, finds,
                        wctx, playerX, playerY)) {
                    if (n.obs != null && globalPhase(t, n.phase) <= toInclusive)
                        obsSink2(n.obs)
                    val g = globalPhase(t, n.phase)
                    if (n.journalTxt != null && g > fromExclusive && g <= toInclusive)
                        sink(WorldEvent(t, n.category, "raw", 0,
                            params = mapOf("txt" to n.journalTxt), phase = n.phase))
                }
            }
        }

        val d0 = maxOf(fullDays, 0)
        val d = if (halted > 0) minOf(d0, halted - 1) else d0
        return ExpeditionSummary(
            days = d, rainDays = rain, snowDays = snow, stormDays = storm,
            fogMornings = fogs,
            minTemp = if (d == 0) 0 else minT,
            maxTemp = if (d == 0) 0 else maxT,
            firstSnowDay = firstSnow,
            snowCoverDays = coverDays,
            maxSnowCm = maxSnow,
            haltedDay = halted,
            haltedKind = haltKind,
            haltedText = haltText,
        )
    }

    /** Полярные координаты ближайшего зверя вида от игрока (для метки встречи). */
    private fun polarOf(
        fauna: List<FaunaModel.Agent>, kind: String, px: Double, py: Double,
    ): Pair<Double, Int> {
        val a = fauna.filter { it.kind == kind && it.alive }
            .minByOrNull { Math.hypot(it.x - px, it.y - py) }
            ?: return Pair(0.5, 0)
        val dx = a.x - px; val dy = a.y - py
        var deg = Math.toDegrees(Math.atan2(dx, dy))
        if (deg < 0) deg += 360.0
        return Pair(Math.hypot(dx, dy), Math.round(deg / 45.0).toInt() % 8)
    }

    /**
     * КУРС АВТОПИЛОТА, когда игрок не выбрал сам.
     *
     * Железное правило: автопилот видит РОВНО то, что человек на радаре —
     * последние наблюдения (`lastObs`), а не истинные позиции зверей. Он
     * слеп там, где слеп ты. Никакого подглядывания в мир.
     *
     * Логика простая и читаемая: опасность близко — прочь; свежий след или
     * добыча поодаль — посмотреть; иначе — разведка по плавной дуге, чтобы
     * не уходить из обжитого края и день за днём менять соседей.
     */
    private fun autopilotHeading(
        seed: Long, day: Int, lastObs: Map<String, Obs>, px: Double, py: Double,
    ): Int {
        val rng = SplitMix64.forTick(seed xor AUTOPILOT_SALT, day)
        // 1. Опасность рядом и свежая — уходим строго в противоположную сторону.
        val danger = lastObs.values.firstOrNull {
            (day - it.tick) <= 3 && it.distKm < 2.5 &&
                (it.kind == FaunaModel.WOLF || it.kind == FaunaModel.BEAR)
        }
        if (danger != null) return (danger.sector + 4) % 8
        // 2. Свежая добыча или чей-то след поодаль — идём смотреть.
        val interest = lastObs.values
            .filter {
                (day - it.tick) <= 4 && it.distKm >= 1.0 && it.distKm <= 6.0 &&
                    (it.kind == FaunaModel.MOOSE || it.source == Obs.TRACK)
            }
            .minByOrNull { it.distKm }
        if (interest != null) {
            val j = if (rng.nextDouble() < 0.3) (if (rng.nextDouble() < 0.5) 1 else 7) else 0
            return (interest.sector + j) % 8
        }
        // 3. Разведка: старт-направление стабильно на экспедицию, курс плавно
        //    поворачивает (~румб за 5 дней) — игрок обходит обжитой край по
        //    розетке, а не улетает в пустую тайгу по прямой.
        val start = SplitMix64.forTick(seed xor AUTOPILOT_SALT, 0).nextInt(8)
        val wobble = if (rng.nextDouble() < 0.15) (if (rng.nextDouble() < 0.5) 1 else 7) else 0
        return (start + day / 5 + wobble + 8) % 8
    }

    /**
     * Всё, что человек узнал о зверях к моменту toPhase.
     *
     * Мир проживается заново (это копейки арифметики), но НИ ОДНО событие
     * наружу не выдаётся: интересны только наблюдения. Гейт по фазе тот же,
     * что у журнала, — радар не забегает вперёд журнала даже на четверть дня.
     */
    fun observations(
        toPhase: Int,
        plannedHeading: (Int) -> Int = { -1 },
        choiceProvider: (Int) -> Int = { -1 },
    ): List<Obs> {
        val out = ArrayList<Obs>()
        run(toPhase, toPhase, { out.add(it) }, plannedHeading, { _, _ -> }, choiceProvider) { }
        return out
    }

    /**
     * Пересчёт состояний дней без эмиссии событий — для шапок карточек.
     * Дешёво: тик это несколько десятков арифметических операций.
     */
    fun daySnapshots(toInclusive: Int): List<DaySnap> {
        var state = WeatherModel.initial(
            version, SplitMix64.forTick(seed, 0), yearDay(0), startSeason
        )
        val out = ArrayList<DaySnap>(maxOf(toInclusive, 0))
        var dir = WindField.initial(seed)
        for (t in 1..toInclusive) {
            state = WeatherModel.step(version, state, yearDay(t), SplitMix64.forTick(seed, t))
            dir = WindField.step(seed, t, dir, state.front)
            out.add(DaySnap(
                tick = t, tempC = state.tempC, cloud = state.cloud, wind = state.wind,
                precip = state.precip, fog = state.fog,
                snowCm = state.snowCm, riverIce = state.riverIce, windDir = dir,
                light = lightX10(yearDay(t)), moon = moonPhase(yearDay(t), seed),
            ))
        }
        return out
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
        rng: SplitMix64, windDir: Int, fr: FaunaModel.DayResult?,
        seen: HashMap<String, Int>, fromPhase: Int, toPhase: Int,
        sink: (WorldEvent) -> Unit,
        obsSink: (Obs) -> Unit,
    ) {
        // Наблюдение уходит наружу по тому же правилу, что и строка журнала:
        // фаза должна быть ПРОЖИТА. Нижней границы нет — радар показывает
        // всё, что человек знает, а не только новости последнего догона.
        fun observe(ph: Int, kind: String, sector: Int, distKm: Double,
                    source: Int, pack: Int) {
            if (globalPhase(t, ph) <= toPhase) {
                // Глазами и по следу сторона известна точно. На слух — как
                // повезёт с погодой: в туман и в гул ветра направление плывёт.
                val err = if (source == Obs.HEARD)
                    SenseModel.bearingErrHeardCalm(st.wind, st.precip, st.fog) else 0
                obsSink(Obs(t, ph, kind, sector, distKm, source, pack, err))
            }
        }

        // Выдача события: срабатывание ключа считается ВСЕГДА (иначе догон
        // кусками сдвигал бы выбор вариантов), а наружу событие уходит,
        // только если его фаза уже прожита и ещё не записана.
        fun fire(category: String, key: String, roll: Long,
                 params: Map<String, String>, ctx: Map<String, Int>) {
            val nth = seen[key] ?: 0
            seen[key] = nth + 1
            val ph = phaseOf(category, key)
            val g = globalPhase(t, ph)
            if (g > fromPhase && g <= toPhase) {
                sink(WorldEvent(t, category, key, roll, params, ctx, nth, ph))
            }
        }

        val season = seasonOf(t)
        val dt = st.tempC - prev.tempC

        // След бросается ПЕРВЫМ: остальные строки дня должны знать, был ли он.
        // Иначе зарисовка «никто чужой по моему следу не прошёл» могла бы
        // выйти в один день с волчьей цепочкой в двух шагах от лагеря.
        //
        // След АГЕНТА важнее фонового: если у лагеря ходила стая, писать про
        // заячьи петли — значит скрыть главное. Фон бросается только тогда,
        // когда крупного зверя рядом не было.
        val wolvesKm = fr?.wolvesNearKm ?: 99.0
        val agentTrack = fr?.trackKind
        val bg = if (agentTrack == null) TrackModel.roll(rng, st, season, wolvesKm) else null
        val trackKind = agentTrack ?: bg?.species
        val trackVisible = trackKind != null &&
            st.snowCm >= TrackModel.MIN_SNOW_CM && st.precip < 3 && st.wind < 3 && st.snowAgeDays >= 1
        val track = if (trackVisible) trackKind else null

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
            "snowcm" to st.snowCm,
            "snowage" to st.snowAgeDays,
            "ice" to st.riverIce,
            "winter" to (if (st.winterSet) 1 else 0),
            "track" to (if (track != null) 1 else 0),
            "windir" to windDir,
            "scent" to (ScentModel.campScentKm(st) * 10).toInt(),
            "wolfkm" to minOf(99, wolvesKm.toInt()),
            "dist" to ((fr?.event?.distKm ?: 99.0) * 10).toInt(),
            // Читаемые с неба величины. Ни одного нового броска кубика: и
            // свет, и луна — чистые функции от дня года. Мир от них не
            // зависит; от них зависит то, что человек видит и пишет.
            "light" to lightX10(yearDay(t)),
            "moon" to moonPhase(yearDay(t), seed),
            // Граница знания: докуда сегодня видно и слышно, в сотнях метров.
            // Корпус может на это опереться — и человек в журнале скажет то,
            // что и должен: «в такую метель я не увижу зверя, даже если он
            // сядет рядом».
            "sight" to (SenseModel.sightKm(
                st.cloud, st.precip, st.fog, st.wind,
                lightX10(yearDay(t)), moonPhase(yearDay(t), seed)) * 10).toInt(),
            "hear" to (SenseModel.hearKm(st.wind, st.precip, st.tempC, st.fog) * 10).toInt(),
        )

        if (t > 1 && seasonOf(t) != seasonOf(t - 1)) {
            fire("milestone", "season.to_" + SEASON_EN[season], rng.nextLong(), emptyMap(), ctx)
        }

        // Вехи ЗЕМЛИ. Это события мира, а не календаря: зима наступает,
        // когда снег лежит и мороз держится, а не когда так решил номер дня.
        val phase: String? = when {
            !prev.winterSet && st.winterSet -> "phase.winter_set"
            // Снег сходит ПЛАВНО: 4 см, 1 см, ноль. Порог «вчера было ≥5»
            // такое таяние просто перепрыгивало, и веха не выпадала никогда.
            // Правильный признак — исчезновение зимы как состояния земли.
            prev.winterSet && !st.winterSet -> "phase.snow_gone"
            prev.riverIce < 2 && st.riverIce == 2 -> "phase.river_freeze"
            prev.riverIce > 0 && st.riverIce == 0 -> "phase.river_open"
            else -> null
        }
        if (phase != null) {
            fire("milestone", phase, rng.nextLong(),
                mapOf("temp" to fmtTemp(st.tempC), "snow" to st.snowCm.toString()), ctx)
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

            // --- земля ---
            st.snowCm >= 40 && prev.snowCm < 40 -> "wx.snow_deep"

            // --- небо ---
            // Туман важнее облачности: шапка дня в тумане пишет «туманно»,
            // и строка «Наконец ясно» рядом с ней была прямой ложью.
            st.cloud == 0 && !st.fog && prev.overcastStreak >= 4 -> "wx.clear_streak"
            st.fog && !prev.fog -> "wx.fog"
            st.cloud == 2 && st.precip == 0 && st.overcastStreak >= 6 &&
                st.overcastStreak % 3 == 0 -> "wx.gloom_hold"
            st.wind == 2 && prev.wind <= 1 && rng.nextDouble() < 0.4 -> "wx.wind_strong"

            else -> null
        }

        if (key != null) {
            // Сторона света теперь ПАРАМЕТР, а не буква в строке корпуса.
            // Дождь приходит с того ветра, который сегодня в мире, и уходит
            // туда, куда этот ветер его несёт.
            fire("weather", key, rng.nextLong(), mapOf(
                "temp" to fmtTemp(st.tempC),
                "snow" to st.snowCm.toString(),
                "windfrom" to windFrom(windDir),
                "windto" to windTo(windDir),
            ), ctx)
        } else {
            // Тихий день. Цифры дня (небо, ветер, температура, снег) больше
            // НЕ печатаются строкой: их несёт шапка карточки. Строка остаётся
            // только там, где ей есть что добавить сверх цифр, — и то не всегда.
            // Совсем тихий день имеет право быть совсем тихим.
            if (rng.nextDouble() < DAY_NOTE_P) {
                fire("ambient", "day_note", rng.nextLong(), mapOf(
                    "sky" to skyWord(st.cloud, st.fog),
                    "wind" to windWord(st.wind),
                    "temp" to fmtTemp(st.tempC),
                    "snow" to st.snowCm.toString(),
                    "windfrom" to windFrom(windDir),
                    "windto" to windTo(windDir),
                ), ctx)
            }
            if (rng.nextDouble() < AMBIENT_P) {
                fire("ambient", "ambient", rng.nextLong(), mapOf(
                    "windfrom" to windFrom(windDir),
                    "windto" to windTo(windDir),
                ), ctx)
            }
        }

        // След — не погода и не быт: это первый признак того, что в тайге
        // есть кто-то кроме тебя. Поэтому он идёт отдельной строкой и своей
        // категорией, а не соревнуется с погодой за место в дне.
        if (track != null) {
            fire("track", "track." + track, rng.nextLong(), mapOf(
                "temp" to fmtTemp(st.tempC),
                "snow" to st.snowCm.toString(),
                "age" to st.snowAgeDays.toString(),
                "km" to distWord(fr?.trackDistKm ?: 99.0),
            ), ctx)
            // След АГЕНТА — наблюдение с румбом: цепочка уходит в конкретную
            // сторону. Фоновый след (заяц, соболь) румба не несёт: он и в
            // жизни ничего не говорит о том, где зверь сейчас.
            if (agentTrack != null && fr != null) {
                observe(MORNING, agentTrack, fr.trackSector, fr.trackDistKm, Obs.TRACK, 1)
            }
        }

        // Зверь. Отдельная категория и отдельный цвет: это не погода и не быт,
        // это единственное в мире, у чего есть собственная воля.
        val ev = fr?.event
        if (ev != null) {
            fire("animal", ev.key, rng.nextLong(), mapOf(
                "km" to distWord(ev.distKm),
                "pack" to ev.packSize.toString(),
                "packw" to packWord(ev.packSize),
                "temp" to fmtTemp(st.tempC),
            ), ctx)
            // Откуда человек это узнал — решает не тип события, а ПОГОДА.
            //
            // Вой и возня на добыче — всегда слух. Остальное: если зверь был
            // в пределах видимости — видел своими глазами. Если нет (пурга,
            // туман, чёрная ночь) — не видел никого: нашёл разорённый лабаз,
            // прочёл кольцо следов вокруг палатки. Это тот же факт, но
            // добытый иначе — и радар обязан это различать.
            val sightNow = SenseModel.sightKm(
                st.cloud, st.precip, st.fog, st.wind,
                lightX10(yearDay(t)), moonPhase(yearDay(t), seed))
            val src = when {
                ev.key == "fauna.wolf.howl" || ev.key == "fauna.wolf.kill" -> Obs.HEARD
                ev.distKm <= sightNow * 3.0 -> Obs.SEEN
                else -> Obs.TRACK
            }
            observe(phaseOf("animal", ev.key), ev.kind, ev.sector, ev.distKm,
                src, ev.packSize)
        } else if (track == null && wolvesKm < 2.5 &&
            st.snowCm >= TrackModel.MIN_SNOW_CM && st.precip < 3 &&
            (version < 3 || rng.nextDouble() < QUIET_P)) {
            // Пустой лес под стаей — тоже сообщение. Отсутствие следов
            // информативно ровно настолько же, насколько их наличие.
            //
            // v3: со стаей, которая теперь ходит рядом, а не за горизонтом,
            // эта строка стала самой частой в журнале — 7% всех дней.
            // Тишина, звучащая каждый день, перестаёт быть тишиной.
            fire("animal", "fauna.quiet", rng.nextLong(), emptyMap(), ctx)
        }
    }

    companion object {
        /**
         * Версия правил МИРА. v103 вводит фронты и землю (снежный покров,
         * лёд, фенологическую зиму) — это другой мир, поэтому версия 2.
         *
         * Экспедиции, начатые на версии 1, доигрываются по правилам версии 1:
         * номер версии лежит в их паспорте, WeatherModel хранит обе ветки.
         * Архив остаётся честным — он прожит тем миром, в котором начинался.
         */
        /** Как часто пустой лес под стаей становится строкой. Только v3. */
    private const val QUIET_P = 0.40

    const val ENGINE_VERSION = 7

        /** Дневной переход игрока по выбранному курсу, км. Подобран так, чтобы
         *  за экспедицию исходить обжитой край, а не улететь в пустоту. */
        const val TRAVEL_KM_PER_DAY = 1.5

        /** Своя струя случайности для автопилота — не двигает мир. */
        const val AUTOPILOT_SALT = 0x40B7_1C3E_9A55_2D08L

        /**
         * Вероятность бытовой зарисовки в тихий день. 0.18 подобрано
         * прогоном: вместе с погодными событиями даёт ~0.4-0.5 записи
         * на день мира — журнал живой, но тишина остаётся тишиной.
         */
        const val AMBIENT_P = 0.18

        /**
         * Вероятность «заметки дня» в тихий день. Заметка выпадает только
         * если корпус найдёт вариант, которому есть что сказать сверх шапки
         * (ветер не стихает, река молчит подо льдом, мороз лютый). Гейт 0.5
         * не даёт этим наблюдениям превратиться в ежедневную мантру.
         */
        const val DAY_NOTE_P = 0.5

        /** Фаз в дне мира. Четверть дневной нормы шагов на каждую. */
        const val PHASES = 4
        const val MORNING = 0
        const val DAY = 1
        const val EVENING = 2
        const val NIGHT = 3

        val PHASE_RU = arrayOf("утро", "день", "вечер", "ночь")

        /** Глобальный номер фазы: сквозная шкала прогресса экспедиции. */
        fun globalPhase(tick: Int, phase: Int): Int =
            if (tick <= 0) 0 else (tick - 1) * PHASES + phase + 1

        /**
         * Когда в сутках случается событие.
         *
         * Это не украшение. След читают УТРОМ — по свежему снегу, до того как
         * его затопчешь. Волки воют НОЧЬЮ. Туман стоит до полудня и потому
         * утренний. Записи в дневник делают ВЕЧЕРОМ у костра. Погода — это
         * характеристика дня целиком.
         */
        fun phaseOf(category: String, key: String): Int = when {
            category == "track" -> MORNING
            key == "wx.fog" || key == "wx.rain_stop" ||
                key == "wx.snow_stop" || key == "wx.clear_streak" ||
                // «Утром всё белое» — первый снег замечают, выйдя из палатки,
                // а не в середине дня.
                key == "wx.snow_first" -> MORNING
            key.startsWith("wx.blizzard") -> NIGHT
            key == "fauna.wolf.howl" || key == "fauna.wolf.circle" ||
                key.startsWith("fauna.wolverine") -> NIGHT
            category == "ambient" -> EVENING
            else -> DAY
        }

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

        /**
         * Расстояние словами. Человек в тайге не меряет километрами —
         * он меряет тем, что слышно и что видно.
         */
        /**
         * Расстояние словами. Пороги подтянуты в v118: радар показывал
         * 0,4 км, а текст в ту же ночь говорил «в двух сотнях шагов» —
         * это полтораста метров. Экран и журнал обязаны говорить об одном
         * и том же одинаково.
         */
        fun distWord(km: Double): String = when {
            km < 0.2 -> "у самого лагеря"
            km < 0.35 -> "в двух сотнях шагов"
            km < 0.7 -> "метрах в пятистах"
            km < 1.3 -> "в километре"
            km < 2.2 -> "километрах в двух"
            km < 4.0 -> "километрах в трёх"
            km < 7.0 -> "далеко, километрах в пяти"
            else -> "где-то далеко"
        }

        /** Словесные осадки для шапки дня. */
        fun precipWord(precip: Int): String = when (precip) {
            1 -> "дождь"
            2 -> "ливень"
            3 -> "мокрый снег"
            4 -> "снегопад"
            else -> ""
        }

        /**
         * Шапка дня: одна строка фактов. Ветер называется только когда он
         * есть, снег — только когда он лежит. Шапка не имеет права молчать
         * о том, что видно, и не имеет права говорить о том, чего нет.
         */
        fun headerOf(d: DaySnap): String {
            val sb = StringBuilder()
            sb.append(fmtTemp(d.tempC)).append("°")
            sb.append(" · ").append(skyWord(d.cloud, d.fog))
            if (d.precip > 0) sb.append(" · ").append(precipWord(d.precip))
            if (d.wind > 0) {
                sb.append(" · ").append(windWord(d.wind))
                sb.append(" (").append(Compass.RU[d.windDir]).append(")")
            }
            if (d.snowCm > 0) sb.append(" · снег ").append(d.snowCm).append(" см")
            if (d.riverIce == 2) sb.append(" · река подо льдом")
            else if (d.riverIce == 1) sb.append(" · шуга")
            return sb.toString()
        }

        /** Словесный ветер для сводки дня. */
        fun windWord(wind: Int): String = when (wind) {
            0 -> "тихо"
            1 -> "ветрено"
            2 -> "сильный ветер"
            else -> "буря"
        }

        /** Русское склонение: 1 день - 2 дня - 5 дней - 11 дней - 21 день. */
        /**
         * Стая словами: 1 зверь, 2 зверя, 5 зверей.
         *
         * Раньше в корпусе стояло «{pack} зверя», и стая из пяти волков
         * выходила «5 зверя». Число приходит из мира — значит, и падеж
         * обязан приходить оттуда же, а не быть вписанным в строку.
         */
        fun packWord(n: Int): String {
            val m10 = n % 10
            val m100 = n % 100
            return when {
                m100 in 11..14 -> "зверей"
                m10 == 1 -> "зверь"
                m10 in 2..4 -> "зверя"
                else -> "зверей"
            }
        }

        /** Откуда: «с севера», «с юго-запада». Родительный падеж. */
        val WIND_FROM = arrayOf(
            "севера", "северо-востока", "востока", "юго-востока",
            "юга", "юго-запада", "запада", "северо-запада",
        )

        /** Куда: «на восток», «на юго-запад». Винительный падеж. */
        val WIND_TO = arrayOf(
            "север", "северо-восток", "восток", "юго-восток",
            "юг", "юго-запад", "запад", "северо-запад",
        )

        /** Откуда дует. */
        fun windFrom(windDir: Int): String = WIND_FROM[((windDir % 8) + 8) % 8]

        /** Куда уносит — то есть подветренная сторона. */
        fun windTo(windDir: Int): String = WIND_TO[Compass.downwind(windDir)]

        /**
         * Длина светового дня, в десятых долях часа. Северная тайга, ~62°N.
         *
         * Середина зимы — около пяти часов света, середина лета — почти
         * девятнадцать. Ничего не бросается: это косинус от дня года.
         * Зачем: зимний журнал должен знать, что человек живёт в темноте.
         */
        fun lightX10(yearDay: Int): Int {
            val yd = ((yearDay % WeatherModel.YEAR_DAYS) + WeatherModel.YEAR_DAYS) %
                WeatherModel.YEAR_DAYS
            // максимум в середине лета (день 225), минимум в середине зимы (45)
            val a = 2.0 * Math.PI * (yd - 225.0) / WeatherModel.YEAR_DAYS
            val hours = 12.0 + 6.8 * Math.cos(a)
            return Math.round(hours * 10.0).toInt()
        }

        /**
         * Фаза луны: 0 — новолуние, 4 — полнолуние. Цикл 30 дней.
         *
         * Тоже без кубика: сдвиг цикла берётся из сида, дальше — календарь.
         * Мир луне не подчиняется (зверь не воет на полную луну — это миф),
         * но человек её видит, и в ясную ночь при полной луне по снегу можно
         * идти без огня. Это уже повод для строки.
         */
        fun moonPhase(yearDay: Int, seed: Long): Int {
            val off = ((seed % 30L) + 30L) % 30L
            val d = ((yearDay + off) % 30L).toInt()
            return (d * 8 / 30) % 8
        }

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
