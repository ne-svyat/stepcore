package com.vasil.stepcore.survival.harness

import com.vasil.stepcore.survival.engine.BackupCodec
import com.vasil.stepcore.survival.engine.Corpus
import com.vasil.stepcore.survival.engine.Compass
import com.vasil.stepcore.survival.engine.DaySnap
import com.vasil.stepcore.survival.engine.FaunaModel
import com.vasil.stepcore.survival.engine.Obs
import com.vasil.stepcore.survival.engine.RadarModel
import com.vasil.stepcore.survival.engine.ScentModel
import com.vasil.stepcore.survival.engine.SenseModel
import com.vasil.stepcore.survival.engine.SplitMix64
import com.vasil.stepcore.survival.engine.WeatherModel
import com.vasil.stepcore.survival.engine.WindField
import com.vasil.stepcore.survival.engine.StepLedger
import com.vasil.stepcore.survival.engine.SurvivalEngine
import com.vasil.stepcore.survival.engine.WorldEvent
import java.io.File

/**
 * Песочный прогон движка Survival Mode. НЕ входит в APK — лежит в tools/.
 * Запуск: kotlinc (все .kt движка) SimHarness.kt -include-runtime -d h.jar
 *         java -jar h.jar app/src/main/assets/survival/weather_ru.txt
 * Ненулевой код выхода = нарушен инвариант.
 */

private var failures = 0

/**
 * ЗОНД МИРА. Посуточно печатает то, что журнал никогда не покажет:
 * где стоит каждый зверь, насколько он голоден, идёт ли он на лагерь,
 * докуда сегодня видно и слышно, куда несёт запах — и какие строки в
 * итоге легли в журнал.
 *
 * Инструмент разбора, а не игры: он не участвует в инвариантах и не влияет
 * на мир. Но именно из-за его отсутствия каждый разбор реального журнала
 * превращался в гадание.
 */
private fun probe(seed: Long, season: Int, days: Int, version: Int, corpus: Corpus) {
    val off = SurvivalEngine.startOffsetFrom(seed)
    val eng = SurvivalEngine(seed, season, off, version)

    val lines = HashMap<Int, MutableList<String>>()
    eng.run(0, days * SurvivalEngine.PHASES) { e ->
        val txt = corpus.renderOrNull(e.key, e.roll, e.params, e.ctx, e.nth, seed)
        if (txt != null) {
            lines.getOrPut(e.tick) { ArrayList() }
                .add("[" + e.category + "/" + e.key + "] " + txt)
        }
    }

    val agents = FaunaModel.spawn(seed, season)
    var state = WeatherModel.initial(version, SplitMix64.forTick(seed, 0), eng.yearDay(0), season)
    var dir = 0

    println("ЗОНД · seed=" + seed + " сезон=" + season + " версия мира=" + version +
        " · " + days + " дней")
    println()
    for (t in 1..days) {
        val rng = SplitMix64.forTick(seed, t)
        state = WeatherModel.step(version, state, eng.yearDay(t), rng)
        dir = WindField.step(seed, t, dir, state.front)
        val light = SurvivalEngine.lightX10(eng.yearDay(t))
        val moon = SurvivalEngine.moonPhase(eng.yearDay(t), seed)
        val fr = FaunaModel.step(agents, state, dir, eng.seasonOf(t), seed, t, version, light, moon)

        val sight = SenseModel.sightKm(state.cloud, state.precip, state.fog, state.wind, light, moon)
        val hear = SenseModel.hearKm(state.wind, state.precip, state.tempC, state.fog)
        val scent = ScentModel.reachKm(state.wind, state.tempC, state.precip, state.fog)

        println("Д" + t + "  " + SurvivalEngine.headerOf(eng.daySnapshots(t).last()))
        println("     небо: свет " + (light / 10.0) + " ч · луна " + moon +
            " | чувства: видно " + String.format("%.2f", sight) +
            " км · слышно " + String.format("%.2f", hear) + " км" +
            " | запах: " + String.format("%.2f", scent) + " км на " +
            Compass.RU[Compass.downwind(dir)])
        for (a in agents) {
            if (!a.alive) { println("     " + a.kind.padEnd(10) + " МЁРТВ"); continue }
            if (a.asleep) { println("     " + a.kind.padEnd(10) + " спит"); continue }
            val smells = ScentModel.smells(a.distKm, a.sector, state, dir, FaunaModel.nose(a.kind))
            println("     " + a.kind.padEnd(10) +
                String.format("%5.2f км", a.distKm) + " " + Compass.RU[a.sector].padEnd(3) +
                " голод " + String.format("%.2f", a.hunger) +
                (if (smells) " ЧУЕТ" else "     ") +
                (if (a.approaching) " ИДЁТ" else "     ") +
                (if (a.visitCooldown > 0) " отходит(" + a.visitCooldown + ")" else "") +
                (if (a.distKm <= sight) " <- ВИДЕН" else "") +
                (if (a.kind == FaunaModel.WOLF) " стая=" + a.packSize else ""))
        }
        if (fr?.event != null) {
            println("     СОБЫТИЕ: " + fr.event!!.key + " на " +
                String.format("%.2f", fr.event!!.distKm) + " км, " + Compass.RU[fr.event!!.sector])
        }
        if (fr?.trackKind != null) {
            println("     СЛЕД: " + fr.trackKind + " на " +
                String.format("%.2f", fr.trackDistKm) + " км, " + Compass.RU[fr.trackSector])
        }
        for (l in lines[t] ?: emptyList<String>()) println("     ЖУРНАЛ " + l)
        println()
    }
}

/**
 * Отпечаток мира версии 2. Считается по событиям 50 сидов x 100 дней.
 * Пока ENGINE_VERSION не меняется, эта константа неприкосновенна: любой
 * новый слой обязан жить в своём потоке случайности и не двигать погоду,
 * ветер и зверя уже начатых экспедиций.
 *
 * v115: из отпечатка убраны params — готовые СЛОВА события. Слова обязаны
 * улучшаться (падежи, стороны света), мир — нет. Теперь отпечаток берётся
 * по ctx: это сырое состояние мира на день, из которого слова и лепятся.
 * Проверено: до и после v115 значение одинаково — мир не шелохнулся.
 */
/**
 * Поля, которые ЕСТЬ мир. Отпечаток берётся только по ним.
 *
 * v117 добавил в ctx показания неба (длина дня, фаза луны). Это не мир —
 * это то, что человек с неба считывает; ни одного броска кубика за ними
 * нет. Отпечаток обязан ловить сдвиг ПОГОДЫ, ВЕТРА и ЗВЕРЯ, а не факт
 * появления новой строчки в справке. Поэтому список явный и закрытый.
 */
private val WORLD_CTX = listOf(
    "day", "t", "dt", "season", "cloud", "wind", "precip", "fog", "snowseen",
    "snowcm", "snowage", "ice", "winter", "track", "windir", "scent", "wolfkm", "dist",
)

private const val WORLD_FINGERPRINT = "ab8e1d4f84aa0f82"

/**
 * Отпечаток мира версии 3 (v116: зверь идёт, а не прыгает).
 * Заморожен так же, как v2. Правило прежнее: новый слой не двигает уже
 * начатые экспедиции — он получает свою версию.
 */
private const val WORLD_FINGERPRINT_V3 = "f6cc590484b7d5cb"

/**
 * Отпечаток мира версии 4 (v118: граница знания — видимость и слышимость).
 * Заморожен так же, как v2 и v3.
 */
private const val WORLD_FINGERPRINT_V4 = "65bdb3c215e0ccc3"

/**
 * Отпечаток мира версии 5 (v120: свой участок — абсолютные координаты,
 * центр и радиус на зверя). Заморожен так же, как v2..v4. Погода и ветер
 * не тронуты — отличие от v4 идёт только по фауне (dist, wolfkm, track).
 */
private const val WORLD_FINGERPRINT_V5 = "d627ec198ce3487e"

/**
 * Отпечаток мира версии 6 (v121: ты идёшь — лагерь едет с игроком, мир
 * считается вокруг него; курс по умолчанию ведёт честный автопилот).
 */
private const val WORLD_FINGERPRINT_V6 = "e1b40f2e308f8172"

/** v7. Пустая строка = ещё не заморожен; после первого прогона вписывается. */
private const val WORLD_FINGERPRINT_V7 = "e630dea3e919f47f"

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (!ok) {
        failures++
        println("FAIL  " + name + (if (detail.isEmpty()) "" else "  [" + detail + "]"))
    }
}

private fun sig(e: WorldEvent) = "" + e.tick + "|" + e.category + "|" + e.key + "|" + e.roll

private fun z(ctx: Map<String, Int>, k: String): Int = ctx[k] ?: 0

/**
 * Детектор физической лжи. Слово из левой колонки не имеет права попасть
 * в журнал, если условие справа не выполнено. Именно этот класс багов
 * дал в реальном журнале строку «Мороз окреп: +18°».
 *
 * Границы слов обязательны: подстрочный поиск ловил «НАСТоящий шторм»
 * как наст. Ложное срабатывание детектора хуже отсутствия детектора —
 * оно приучает игнорировать его вывод.
 */
private val RE_NAST = Regex("(?<![А-Яа-яЁё])наст[аеоуы]?(?![А-Яа-яЁё])")
private val RE_ROSA = Regex("(?<![А-Яа-яЁё])рос[аеу](?![А-Яа-яЁё])")

private fun physicalLie(txt: String, ctx: Map<String, Int>): String? {
    val low = txt.lowercase()
    val t = z(ctx, "t")
    val snow = z(ctx, "snowseen")
    val snowWord = low.contains("снег") || low.contains("снеж")
    if (low.contains("мороз") && t > 0) return "мороз при " + t
    if (low.contains("иней") && t > 0) return "иней при " + t
    if (RE_ROSA.containsMatchIn(low) && t < 0) return "роса при " + t
    val cm = z(ctx, "snowcm")
    if (snowWord && snow == 0) return "снег без снега"
    if (RE_NAST.containsMatchIn(low) && cm == 0) return "наст без покрова"
    if (low.contains("снегоступ") && cm == 0) return "снегоступы без покрова"
    if (low.contains("покров") && cm == 0) return "покров без покрова"
    if ((low.contains("ледостав") || low.contains("подо льдом") || low.contains("по льду")) &&
        z(ctx, "ice") < 2) return "лёд без ледостава"
    if (low.contains("шуга") && z(ctx, "ice") != 1) return "шуга без шуги"
    if ((low.contains("метел") || low.contains("метёт")) && snow == 0) return "метель без снега"
    if ((low.contains("жара") || low.contains("жарк")) && t < 25) return "жара при " + t
    if (low.contains("комар") && t < 8) return "комары при " + t
    return null
}

/** Сводка не имеет права называть день спокойным, если он не спокоен. */
private fun claimsCalm(txt: String): Boolean {
    val low = txt.lowercase()
    return low.contains("спокойн") || low.contains("без происшествий") || low.contains("тихо и ясно")
}

fun main(args: Array<String>) {
    val corpus = Corpus(File(args[0]).readText())

    // ЗОНД МИРА: посуточный дамп внутренностей одной экспедиции.
    //   kotlin SimHarness.jar corpus.txt --probe <seed> <season 0-3> <дней> [версия]
    // Нужен затем, что журнал показывает мир через щёлочку: видно строку, но
    // не видно, где на самом деле стоял зверь, насколько он был голоден и
    // докуда в тот день было видно. Разбор реальных журналов раз за разом
    // упирался именно в это.
    if (args.size >= 2 && args[1] == "--probe") {
        probe(
            seed = args.getOrElse(2) { "42" }.toLong(),
            season = args.getOrElse(3) { "0" }.toInt(),
            days = args.getOrElse(4) { "30" }.toInt(),
            version = args.getOrElse(5) { "4" }.toInt(),
            corpus = corpus,
        )
        return
    }

    // --- 1. Детерминизм: два прогона одного seed идентичны ---
    run {
        val a = ArrayList<String>(); val b = ArrayList<String>()
        SurvivalEngine(42L, 3, 30, 2).run(0, 100 * SurvivalEngine.PHASES) { a.add(sig(it)) }
        SurvivalEngine(42L, 3, 30, 2).run(0, 100 * SurvivalEngine.PHASES) { b.add(sig(it)) }
        check("determinism.same_seed", a == b)
        val c = ArrayList<String>()
        SurvivalEngine(43L, 3, 30, 2).run(0, 100 * SurvivalEngine.PHASES) { c.add(sig(it)) }
        check("determinism.diff_seed_diff_world", a != c)
    }

    // --- 2. Инвариантность партий: 1x100 == куски произвольной нарезки ---
    run {
        val whole = ArrayList<String>()
        SurvivalEngine(777L, 2, 25, 2).run(0, 100 * SurvivalEngine.PHASES) { whole.add(sig(it)) }
        // Нарезка теперь по ФАЗАМ, в том числе посреди дня: догон, прерванный
        // на утре, обязан дать ровно тот же журнал, что и цельный прогон.
        val cuts = listOf(0, 1, 3, 7, 8, 29, 30, 149, 256, 399, 400)
        val chunked = ArrayList<String>()
        val eng = SurvivalEngine(777L, 2, 25, 2)
        for (i in 1 until cuts.size) {
            eng.run(cuts[i - 1], cuts[i]) { chunked.add(sig(it)) }
        }
        check("determinism.batch_invariance", whole == chunked,
            "whole=" + whole.size + " chunked=" + chunked.size)
    }

    // --- 3. Массовый прогон: 2000 экспедиций x 100 дней, все сезоны ---
    run {
        var events = 0L; var days = 0L
        var weather = 0L; var ambient = 0L; var milestones = 0L
        var digests = 0L
        var tracks = 0L
        var badTrackNoSnow = 0
        var badTrackInSnowfall = 0
        var badBear = 0
        var badSky = 0
        val animalKeys = HashMap<String, Int>()
        var badPhase = 0
        var trackNotMorning = 0
        var howlNotNight = 0
        var stormDaysTotal = 0L
        var holes = 0
        var lies = 0
        var mutedStorms = 0
        val perDayMax = HashMap<Int, Int>()
        var autumnFirstSnow = 0; var autumnRuns = 0
        var winterFirstSnowLeak = 0
        val tempMin = IntArray(4) { Int.MAX_VALUE }
        val tempMax = IntArray(4) { Int.MIN_VALUE }

        for (i in 0 until 2000) {
            val season = i % 4
            val seed = 100000L + i * 31L
            val eng = SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), 2)
            var expFirstSnowEvent = false
            val perDay = HashMap<Int, Int>()
            val wxTicks = HashSet<Int>()
            val stormTicks = HashSet<Int>()
            val summary = eng.run(0, 100 * SurvivalEngine.PHASES) { e ->
                events++
                when {
                    e.category == "weather" -> weather++
                    e.category == "track" -> tracks++
                    e.key == "day_note" -> digests++
                    e.category == "ambient" -> ambient++
                    e.category == "milestone" -> milestones++
                }
                if (e.phase < 0 || e.phase > 3) badPhase++
                if (e.category == "track" && e.phase != SurvivalEngine.MORNING) trackNotMorning++
                if (e.key == "fauna.wolf.howl" && e.phase != SurvivalEngine.NIGHT) howlNotNight++
                val pc = e.ctx["precip"] ?: 0
                if ((pc == 2 || pc == 4) && (e.ctx["cloud"] ?: 0) < 2) badSky++
                if (e.category == "animal") animalKeys[e.key] = (animalKeys[e.key] ?: 0) + 1
                if (e.category == "track") {
                    val cm = e.ctx["snowcm"] ?: 0
                    val pr = e.ctx["precip"] ?: 0
                    val wd = e.ctx["wind"] ?: 0
                    val tt = e.ctx["t"] ?: 0
                    if (cm < 2) badTrackNoSnow++
                    if (pr >= 3 || wd == 3) badTrackInSnowfall++
                    if (e.key == "track.bear" && tt <= -8) badBear++
                }
                if (e.key == "wx.snow_first") expFirstSnowEvent = true
                perDay[e.tick] = (perDay[e.tick] ?: 0) + 1
                val txt = corpus.renderOrNull(e.key, e.roll, e.params, e.ctx, e.nth)
                if (txt == null) {
                    // day_note — необязательная заметка: если сказать нечего
                    // сверх шапки карточки, день молчит. Это не дыра.
                    if (e.key != "day_note") {
                        holes++
                        println("  нет подходящего варианта: " + e.key + " ctx=" + e.ctx)
                    }
                } else {
                    if (txt.startsWith("[")) { holes++; println("  дыра корпуса: " + e.key) }
                    if (txt.contains("{")) { holes++; println("  сирота-плейсхолдер: " + txt) }
                    val lie = physicalLie(txt, e.ctx)
                    if (lie != null) { lies++; println("  ФИЗИЧЕСКАЯ ЛОЖЬ [" + lie + "] " + e.key + " ctx=" + e.ctx + " :: " + txt) }
                    if (e.key == "day_note" && claimsCalm(txt) &&
                        !(z(e.ctx, "wind") <= 1 && z(e.ctx, "precip") == 0 && z(e.ctx, "fog") == 0)) {
                        lies++
                        println("  ЛОЖНОЕ СПОКОЙСТВИЕ ctx=" + e.ctx + " :: " + txt)
                    }
                }
                if (e.category == "weather") wxTicks.add(e.tick)
                if (z(e.ctx, "wind") == 3) stormTicks.add(e.tick)
                if (z(e.ctx, "precip") == 4 && z(e.ctx, "wind") >= 2) stormTicks.add(e.tick)
            }
            days += 100
            // каждый день бури/метели ОБЯЗАН иметь погодное событие:
            // «второй день бури» больше не проваливается в сводку тихого дня
            for (tk in stormTicks) if (!wxTicks.contains(tk)) mutedStorms++
            stormDaysTotal += summary.stormDays
            if (tempMin[season] > summary.minTemp) tempMin[season] = summary.minTemp
            if (tempMax[season] < summary.maxTemp) tempMax[season] = summary.maxTemp
            for ((d, n) in perDay) {
                if ((perDayMax[d] ?: 0) < n) perDayMax[d] = n
            }
            if (season == 3) { autumnRuns++; if (expFirstSnowEvent) autumnFirstSnow++ }
            if (season == 0 && expFirstSnowEvent) winterFirstSnowLeak++
        }

        val perDayRate = events.toDouble() / days
        println("ЗВЕРЬ по ключам: " + animalKeys.entries.sortedByDescending { it.value }.joinToString(" · ") { it.key + "=" + it.value })
        println("события/день = " + String.format("%.3f", perDayRate) +
            "  (погода " + weather + " · сводки " + digests + " · зарисовки " + ambient +
            " · вехи " + milestones + " · следы " + tracks + ")")
        println("бурь: " + String.format("%.1f", stormDaysTotal * 100.0 / days) + " проц. дней")
        println("температуры по сезонам (мин..макс): зима " + tempMin[0] + ".." + tempMax[0] +
            " · весна " + tempMin[1] + ".." + tempMax[1] +
            " · лето " + tempMin[2] + ".." + tempMax[2] +
            " · осень " + tempMin[3] + ".." + tempMax[3])
        println("первый снег: осень " + autumnFirstSnow + "/" + autumnRuns)

        // Цифры дня несёт шапка карточки, поэтому строк стало меньше:
        // журнал жив, но тишина наконец имеет право быть тишиной.
        check("rate.per_day_alive", perDayRate >= 0.35, "" + perDayRate)
        check("rate.per_day_not_spammy", perDayRate <= 1.10, "" + perDayRate)
        // событий-погоды (без сводок) — прежняя редкость, мир не сорит драмой
        val wxRate = weather.toDouble() / days
        check("rate.weather_events_rare", wxRate in 0.10..0.55, "" + wxRate)
        // веха + погода + заметка + зарисовка + след — потолок дня
        check("rate.max_four_per_day", (perDayMax.values.maxOrNull() ?: 0) <= 5)
        check("track.only_on_snow", badTrackNoSnow == 0, "" + badTrackNoSnow)
        check("track.never_in_snowfall", badTrackInSnowfall == 0, "" + badTrackInSnowfall)
        check("track.no_bear_in_hard_frost", badBear == 0, "" + badBear)
        check("track.exists", tracks > 0, "" + tracks)
        check("sky.heavy_precip_needs_overcast", badSky == 0, "" + badSky)
        check("corpus.no_holes", holes == 0)
        check("phase.in_range", badPhase == 0, "" + badPhase)
        check("phase.tracks_in_morning", trackNotMorning == 0, "" + trackNotMorning)
        check("phase.howl_at_night", howlNotNight == 0, "" + howlNotNight)
        check("corpus.no_broken_conditions", corpus.problems().isEmpty(),
            corpus.problems().joinToString("; "))
        check("text.no_physical_lies", lies == 0, "" + lies)
        check("text.storm_never_muted", mutedStorms == 0, "" + mutedStorms)
        // границы учитывают дрейф сезона: старт зимой за 100 дней доходит
        // до середины весны (до ~+20), старт летом — до осенних холодов (~-18)
        check("temp.winter_sane", tempMin[0] >= -45 && tempMax[0] <= 25,
            tempMin[0].toString() + ".." + tempMax[0])
        check("temp.summer_sane", tempMin[2] >= -25 && tempMax[2] <= 40,
            tempMin[2].toString() + ".." + tempMax[2])
        check("snow.autumn_first_snow_common", autumnFirstSnow.toDouble() / autumnRuns > 0.7,
            "" + autumnFirstSnow + "/" + autumnRuns)
        check("snow.no_first_snow_in_winter_start", winterFirstSnowLeak == 0)
        check("storm.rare", stormDaysTotal.toDouble() / days in 0.005..0.10)
    }

    // --- 3b. ЗЕМЛЯ: снег не берётся из воздуха и не лежит в жару ---
    //     Проверяется по СНИМКАМ ДНЕЙ (daySnapshots), а не по событиям:
    //     тихий день теперь не эмитит ни одной строки, и цепочка событий
    //     не описывает мир полностью.
    run {
        var badGrowth = 0; var badHeat = 0; var iceNoCold = 0
        var maxCm = 0; var coverDays = 0; var days = 0
        for (i in 0 until 500) {
            val season = i % 4
            val seed = 900000L + i * 17L
            val eng = SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), 2)
            val snaps = eng.daySnapshots(120)
            var prev: DaySnap? = null
            for (d in snaps) {
                days++
                if (prev != null && d.snowCm > prev.snowCm && d.precip < 3) badGrowth++
                if (d.snowCm > 0 && d.tempC >= 20) badHeat++
                if (d.snowCm > maxCm) maxCm = d.snowCm
                if (d.snowCm > 0) coverDays++
                prev = d
            }
        }
        println("земля: макс. покров " + maxCm + " см · покров " +
            (coverDays * 100 / days) + " проц. дней")
        check("ground.no_snow_from_nothing", badGrowth == 0, "" + badGrowth)
        check("ground.no_snow_in_heat", badHeat == 0, "" + badHeat)
        check("ground.snow_accumulates", maxCm in 20..130, "" + maxCm)
        check("ground.cover_reasonable", coverDays * 100 / days in 15..60,
            "" + (coverDays * 100 / days))
    }

    // --- 3b2. ВЕСНА ОБЯЗАНА ПРИХОДИТЬ ---
    //     Реальный журнал показал: река стояла подо льдом при +4 и дожде,
    //     потому что вскрытие требовало четырёх ТЁПЛЫХ ДНЕЙ ПОДРЯД, а один
    //     нулевой день обнулял счётчик. Теперь лёд считает градусо-дни,
    //     и эти проверки не дадут регрессу вернуться.
    run {
        var absurdIce = 0
        var springsWithSnow = 0
        var springsTotal = 0
        var iceLingering = 0
        for (i in 0 until 300) {
            val seed = 700000L + i * 13L
            val eng = SurvivalEngine(seed, 1, SurvivalEngine.startOffsetFrom(seed), 2) // старт весной
            val snaps = eng.daySnapshots(120)
            springsTotal++
            var warmRun = 0
            var iceAtEnd = 0
            var snowAtEnd = 0
            for (d in snaps) {
                if (d.riverIce > 0 && d.tempC >= 15) absurdIce++
                warmRun = if (d.tempC >= 5) warmRun + 1 else 0
                // две недели устойчивого тепла — лёд обязан уйти
                if (warmRun >= 14 && d.riverIce > 0) iceLingering++
                iceAtEnd = d.riverIce
                snowAtEnd = d.snowCm
            }
            if (snowAtEnd > 0) springsWithSnow++
        }
        println("весна: лёд при +15 — " + absurdIce + " дн. · лёд после 2 недель тепла — " + iceLingering + " дн.")
        check("spring.no_ice_in_warmth", absurdIce == 0, "" + absurdIce)
        check("spring.ice_breaks_up", iceLingering == 0, "" + iceLingering)
        check("spring.snow_melts", springsWithSnow == 0, "" + springsWithSnow + "/" + springsTotal)
    }

    // --- 3d. ЗАПАХ И ЗВЕРЬ ---
    //     Главная правда тайги, которую модель обязана держать:
    //     зверь НЕ ЧУЕТ ПРОТИВ ВЕТРА. Волк в трёхстах метрах с наветренной
    //     стороны не знает о лагере ничего. Если эта проверка когда-нибудь
    //     упадёт — вся механика запаха превратилась в лотерею.
    run {
        var upwindSmell = 0
        var noScentAtAll = 0
        var checked = 0
        for (i in 0 until 400) {
            val seed = 313000L + i * 29L
            val season = i % 4
            val eng = SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), 2)
            val snaps = eng.daySnapshots(60)
            var dir = WindField.initial(seed)
            var st = WeatherModel.initial(2, SplitMix64.forTick(seed, 0), eng.yearDay(0), season)
            for (t in 1..60) {
                st = WeatherModel.step(2, st, eng.yearDay(t), SplitMix64.forTick(seed, t))
                dir = WindField.step(seed, t, dir, st.front)
                if (st.wind == 0) continue // штиль: сектора нет, запах лужей
                val upwind = Compass.downwind(dir + 4) // сектор ПРОТИВ ветра
                checked++
                // зверь с лучшим нюхом в 300 метрах строго против ветра
                if (ScentModel.smells(0.3, upwind, st, dir, 1.6)) upwindSmell++
                // он же ниже по ветру в 300 метрах — обязан чуять почти всегда
                if (!ScentModel.smells(0.3, Compass.downwind(dir), st, dir, 1.6)) noScentAtAll++
            }
        }
        println("запах: против ветра учуяли " + upwindSmell + " раз из " + checked +
            " · по ветру не учуяли " + noScentAtAll)
        check("scent.never_upwind", upwindSmell == 0, "" + upwindSmell)
        check("scent.works_downwind", noScentAtAll * 100 / maxOf(checked, 1) < 10,
            "" + (noScentAtAll * 100 / maxOf(checked, 1)) + "%")
    }

    // --- 3e. ЗВЕРИ: границы участка, спячка, охота ---
    run {
        var outOfRange = 0
        var bearInFrost = 0
        var deadMooseWalking = 0
        var kills = 0
        var wolfNearCamp = 0
        for (i in 0 until 400) {
            val seed = 424000L + i * 31L
            val season = i % 4
            val eng = SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), 2)
            val agents = FaunaModel.spawn(seed, season)
            var st = WeatherModel.initial(2, SplitMix64.forTick(seed, 0), eng.yearDay(0), season)
            var dir = WindField.initial(seed)
            var mooseAlive = agents.count { it.kind == FaunaModel.MOOSE }
            for (t in 1..90) {
                st = WeatherModel.step(2, st, eng.yearDay(t), SplitMix64.forTick(seed, t))
                dir = WindField.step(seed, t, dir, st.front)
                FaunaModel.step(agents, st, dir, eng.seasonOf(t), seed, t, 3)
                for (a in agents) {
                    if (a.distKm < 0.0 || a.distKm > 30.0) outOfRange++
                    if (a.kind == FaunaModel.BEAR && !a.asleep && st.tempC <= -12) bearInFrost++
                }
                val nowAlive = agents.count { it.kind == FaunaModel.MOOSE && it.alive }
                if (nowAlive < mooseAlive) kills++
                mooseAlive = nowAlive
                val w = agents.first { it.kind == FaunaModel.WOLF }
                if (w.distKm < 1.0) wolfNearCamp++
            }
        }
        println("звери: волк у лагеря " + wolfNearCamp + " дн. · охот удачных " + kills)
        check("fauna.in_range", outOfRange == 0, "" + outOfRange)
        check("fauna.bear_sleeps_in_frost", bearInFrost == 0, "" + bearInFrost)
        check("fauna.wolves_hunt", kills > 0, "" + kills)
        check("fauna.wolves_visit_camp", wolfNearCamp > 0, "" + wolfNearCamp)
    }

    // --- 3c. Версия 1 жива: старые экспедиции доигрываются старым миром ---
    run {
        val a = ArrayList<String>(); val b = ArrayList<String>()
        SurvivalEngine(555L, 3, 30, 1).run(0, 80 * SurvivalEngine.PHASES) { a.add(sig(it)) }
        SurvivalEngine(555L, 3, 30, 1).run(0, 80 * SurvivalEngine.PHASES) { b.add(sig(it)) }
        check("v1.determinism", a == b)
        val v2 = ArrayList<String>()
        SurvivalEngine(555L, 3, 30, 2).run(0, 80 * SurvivalEngine.PHASES) { v2.add(sig(it)) }
        check("v1.differs_from_v2", a != v2)
        var holes = 0
        SurvivalEngine(555L, 3, 30, 1).run(0, 80 * SurvivalEngine.PHASES) { e ->
            if (e.key != "day_note" &&
                corpus.renderOrNull(e.key, e.roll, e.params, e.ctx, e.nth) == null) holes++
        }
        check("v1.no_holes_in_new_corpus", holes == 0, "" + holes)
    }

    // --- 4. StepLedger: крайние случаи ---
    run {
        val totals = mapOf("2026-07-08" to 9000, "2026-07-09" to 4000, "2026-07-10" to 12000)
        val get = { d: String -> totals[d] ?: 0 }

        // тот же день, простое приращение
        var r = StepLedger.advance(StepLedger.Sync("2026-07-11", 1000, 200), "2026-07-11",
            4300, get, emptyList(), 1000)
        check("ledger.same_day", r.newTicks == 3 && r.sync.remainder == 500 &&
            r.sync.daySteps == 4300, r.toString())

        // ролловер: хвост дня синхронизации + дни между + сегодня
        r = StepLedger.advance(StepLedger.Sync("2026-07-08", 6500, 0), "2026-07-11",
            700, get, listOf("2026-07-09", "2026-07-10"), 5000)
        // хвост 2500 + 4000 + 12000 + 700 = 19200 -> 3 тика, остаток 4200
        check("ledger.rollover", r.newTicks == 3 && r.sync.remainder == 4200 &&
            r.sync.date == "2026-07-11", r.toString())

        // часы назад: ноль начислений, состояние не тронуто
        r = StepLedger.advance(StepLedger.Sync("2026-07-11", 500, 100), "2026-07-10",
            9999, get, emptyList(), 1000)
        check("ledger.clock_back", r.newTicks == 0 && r.sync.date == "2026-07-11" &&
            r.sync.daySteps == 500 && r.sync.remainder == 100)

        // сброс prefs: сегодня счётчик меньше базы -> принять новую базу
        r = StepLedger.advance(StepLedger.Sync("2026-07-11", 8000, 300), "2026-07-11",
            50, get, emptyList(), 1000)
        check("ledger.prefs_reset", r.newTicks == 0 && r.sync.daySteps == 50 &&
            r.sync.remainder == 300)

        // исчезнувшая запись дня синхронизации не даёт отрицательных шагов
        r = StepLedger.advance(StepLedger.Sync("2026-07-01", 5000, 0), "2026-07-11",
            100, get, emptyList(), 1000)
        check("ledger.missing_day_guard", r.newTicks == 0 && r.sync.remainder == 100)

        // эквивалентность: скормить шаги одним куском == произвольными кусками
        val lump = StepLedger.advance(StepLedger.Sync("2026-07-11", 0, 0), "2026-07-11",
            23456, get, emptyList(), 700)
        var s = StepLedger.Sync("2026-07-11", 0, 0)
        var ticks = 0
        for (v in intArrayOf(100, 5000, 5100, 12222, 23456)) {
            val rr = StepLedger.advance(s, "2026-07-11", v, get, emptyList(), 700)
            ticks += rr.newTicks; s = rr.sync
        }
        check("ledger.chunk_equivalence", ticks == lump.newTicks && s.remainder == lump.sync.remainder,
            "lump=" + lump.newTicks + " chunk=" + ticks)
    }

    // --- 5. Корпус: обязательные ключи присутствуют ---
    run {
        val need = listOf(
            "start.winter", "start.spring", "start.summer", "start.autumn",
            "season.to_winter", "season.to_spring", "season.to_summer", "season.to_autumn",
            "wx.rain_start", "wx.rain_stop", "wx.heavy_rain", "wx.thunder",
            "wx.sleet_start", "wx.snow_first", "wx.snow_start", "wx.snow_stop",
            "wx.blizzard", "wx.blizzard_hold", "wx.storm", "wx.storm_hold",
            "wx.rain_hold", "wx.snow_hold", "wx.gloom_hold",
            "wx.cold_snap", "wx.cool_down", "wx.hard_frost", "wx.thaw", "wx.heat",
            "wx.clear_streak", "wx.fog", "wx.wind_strong", "wx.snow_deep", "ambient", "day_note",
            "phase.winter_set", "phase.snow_gone", "phase.river_freeze", "phase.river_open",
            "fauna.wolf.howl", "fauna.wolf.circle", "fauna.wolf.kill", "fauna.quiet",
            "fauna.wolverine.camp", "fauna.wolverine.near", "fauna.bear.encounter",
            "fauna.bear.near", "fauna.lynx.near", "fauna.moose.near",
            "track.hare", "track.fox", "track.moose", "track.wolf", "track.sable",
            "track.lynx", "track.wolverine", "track.grouse", "track.bear", "track.unknown",
            "final.snow_cover",
            "end.success", "end.voluntary",
            "final.summary", "final.first_snow", "final.storms",
        )
        for (k in need) check("corpus.key." + k, corpus.has(k))
    }

    // --- 7. Кодек бэкапа: экранирование и план слияния ---
    run {
        val ev = BackupCodec.EvBackup(1, 5L, "weather", "Строка с \"кавычкой\", \\ и\nпереносом")
        val exp = BackupCodec.ExpBackup(42L, 1, "taiga", 2, 20, 30, 100,
            "done_success", 1L, 2L, 30, "2026-07-11", 0, 0, listOf(ev))
        val frag = BackupCodec.exportFragment(listOf(exp))
        check("codec.no_raw_newline_in_strings", !frag.contains("и\nпереносом"))
        check("codec.escaped_quote", frag.contains("\\\"кавычкой\\\""))
        check("codec.fragment_shape", frag.startsWith("\"expeditions\":[") && frag.endsWith("]"))

        // план слияния: дубликат seed, конфликт активных, свежая вставка
        val a1 = BackupCodec.ExpBackup(100L, 1, "taiga", 0, 10, 30, 100, "active", 0, 0, 1, "d", 0, 0, emptyList())
        val a2 = BackupCodec.ExpBackup(200L, 1, "taiga", 0, 10, 30, 100, "active", 0, 0, 1, "d", 0, 0, emptyList())
        val d1 = BackupCodec.ExpBackup(300L, 1, "taiga", 0, 10, 30, 100, "done_success", 0, 0, 30, "d", 0, 0, emptyList())
        val dup = BackupCodec.ExpBackup(400L, 1, "taiga", 0, 10, 30, 100, "done_success", 0, 0, 30, "d", 0, 0, emptyList())

        // свежее устройство: всё входит, активная одна
        var plan = BackupCodec.mergePlan(emptySet(), false, listOf(a1, d1))
        check("merge.fresh_all_insert", plan.all { it is BackupCodec.Action.Insert })

        // локальная активная есть: активная из файла пропускается, завершённая входит
        plan = BackupCodec.mergePlan(emptySet(), true, listOf(a1, d1))
        check("merge.active_conflict_skip", plan[0] is BackupCodec.Action.Skip &&
            (plan[0] as BackupCodec.Action.Skip).reason == "уже есть активная")
        check("merge.done_passes", plan[1] is BackupCodec.Action.Insert)

        // дубликат seed пропускается
        plan = BackupCodec.mergePlan(setOf(400L), false, listOf(dup))
        check("merge.seed_dup_skip", plan[0] is BackupCodec.Action.Skip)

        // две активные в файле: входит только первая
        plan = BackupCodec.mergePlan(emptySet(), false, listOf(a1, a2))
        check("merge.two_actives_in_file", plan[0] is BackupCodec.Action.Insert &&
            plan[1] is BackupCodec.Action.Skip)
    }

    // --- 6. Склонение дней ---
    run {
        val cases = mapOf(1 to "день", 2 to "дня", 4 to "дня", 5 to "дней",
            11 to "дней", 14 to "дней", 21 to "день", 22 to "дня",
            30 to "дней", 100 to "дней", 104 to "дня", 111 to "дней")
        for ((n, w) in cases) {
            check("plural." + n, SurvivalEngine.daysWord(n) == w,
                SurvivalEngine.daysWord(n))
        }
    }

    // --- 8. ОТПЕЧАТОК МИРА: новые слои не двигают старые миры ---
    //     Хеш всех событий 50 сидов x 100 дней. Любой лишний бросок кубика
    //     в общем потоке — и константа разъедется. Именно так проверяется
    //     обещание «слой добавляется, экспедиции не ломаются».
    //     Меняется ЛЕГАЛЬНО только вместе с ENGINE_VERSION.
    run {
        var h = 1125899906842597L
        for (i in 0 until 50) {
            val season = i % 4
            val seed = 424242L + i * 977L
            SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), 2)
                .run(0, 100 * SurvivalEngine.PHASES) { e ->
                    val world = WORLD_CTX.joinToString(",") { k -> k + "=" + (e.ctx[k] ?: 0) }
                    val sg = "" + e.tick + "|" + e.category + "|" + e.key + "|" + e.roll +
                        "|" + e.phase + "|" + e.nth + "|" + world
                    for (c in sg) h = 31 * h + c.code
                }
        }
        val fp = java.lang.Long.toHexString(h)
        println("отпечаток мира v2: " + fp)
        check("world.fingerprint_frozen", fp == WORLD_FINGERPRINT,
            "было " + WORLD_FINGERPRINT + ", стало " + fp)

        var h3 = 1125899906842597L
        for (i in 0 until 50) {
            val seed = 424242L + i * 977L
            SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 3)
                .run(0, 100 * SurvivalEngine.PHASES) { e ->
                    val world = WORLD_CTX.joinToString(",") { k -> k + "=" + (e.ctx[k] ?: 0) }
                    val sg = "" + e.tick + "|" + e.category + "|" + e.key + "|" + e.roll +
                        "|" + e.phase + "|" + e.nth + "|" + world
                    for (c in sg) h3 = 31 * h3 + c.code
                }
        }
        val fp3 = java.lang.Long.toHexString(h3)
        println("отпечаток мира v3: " + fp3)
        check("world.fingerprint_v3_frozen", fp3 == WORLD_FINGERPRINT_V3,
            "было " + WORLD_FINGERPRINT_V3 + ", стало " + fp3)
        check("world.v2_and_v3_differ", fp != fp3)

        var h4 = 1125899906842597L
        for (i in 0 until 50) {
            val seed = 424242L + i * 977L
            SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 4)
                .run(0, 100 * SurvivalEngine.PHASES) { e ->
                    val world = WORLD_CTX.joinToString(",") { k -> k + "=" + (e.ctx[k] ?: 0) }
                    val sg = "" + e.tick + "|" + e.category + "|" + e.key + "|" + e.roll +
                        "|" + e.phase + "|" + e.nth + "|" + world
                    for (c in sg) h4 = 31 * h4 + c.code
                }
        }
        val fp4 = java.lang.Long.toHexString(h4)
        println("отпечаток мира v4: " + fp4)
        check("world.fingerprint_v4_frozen", fp4 == WORLD_FINGERPRINT_V4,
            "было " + WORLD_FINGERPRINT_V4 + ", стало " + fp4)
        check("world.v3_and_v4_differ", fp3 != fp4)

        var h5 = 1125899906842597L
        for (i in 0 until 50) {
            val seed = 424242L + i * 977L
            SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 5)
                .run(0, 100 * SurvivalEngine.PHASES) { e ->
                    val world = WORLD_CTX.joinToString(",") { k -> k + "=" + (e.ctx[k] ?: 0) }
                    val sg = "" + e.tick + "|" + e.category + "|" + e.key + "|" + e.roll +
                        "|" + e.phase + "|" + e.nth + "|" + world
                    for (c in sg) h5 = 31 * h5 + c.code
                }
        }
        val fp5 = java.lang.Long.toHexString(h5)
        println("отпечаток мира v5: " + fp5)
        check("world.fingerprint_v5_frozen", fp5 == WORLD_FINGERPRINT_V5,
            "было " + WORLD_FINGERPRINT_V5 + ", стало " + fp5)
        check("world.v4_and_v5_differ", fp4 != fp5)

        var h6 = 1125899906842597L
        for (i in 0 until 50) {
            val seed = 424242L + i * 977L
            SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 6)
                .run(0, 100 * SurvivalEngine.PHASES, {}, { -1 }, { _, _ -> }) { e ->
                    val world = WORLD_CTX.joinToString(",") { k -> k + "=" + (e.ctx[k] ?: 0) }
                    val sg = "" + e.tick + "|" + e.category + "|" + e.key + "|" + e.roll +
                        "|" + e.phase + "|" + e.nth + "|" + world
                    for (c in sg) h6 = 31 * h6 + c.code
                }
        }
        val fp6 = java.lang.Long.toHexString(h6)
        println("отпечаток мира v6: " + fp6)
        check("world.fingerprint_v6_frozen", fp6 == WORLD_FINGERPRINT_V6,
            "было " + WORLD_FINGERPRINT_V6 + ", стало " + fp6)
        check("world.v5_and_v6_differ", fp5 != fp6)

        // v7: живая тайга. Выбор во встречах фиксирован (всегда вариант 1) —
        // отпечаток детерминирован. Мир обязан отличаться от v6.
        var h7 = 1125899906842597L
        for (i in 0 until 50) {
            val seed = 424242L + i * 977L
            SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 7)
                .run(0, 100 * SurvivalEngine.PHASES, {}, { -1 }, { _, _ -> }, { 1 }) { e ->
                    val world = WORLD_CTX.joinToString(",") { k -> k + "=" + (e.ctx[k] ?: 0) }
                    val sg = "" + e.tick + "|" + e.category + "|" + e.key + "|" + e.roll +
                        "|" + e.phase + "|" + e.nth + "|" + world +
                        "|" + (e.params["txt"] ?: "")
                    for (c in sg) h7 = 31 * h7 + c.code
                }
        }
        val fp7 = java.lang.Long.toHexString(h7)
        println("отпечаток мира v7: " + fp7)
        check("world.fingerprint_v7_frozen",
            WORLD_FINGERPRINT_V7.isEmpty() || fp7 == WORLD_FINGERPRINT_V7,
            "было " + WORLD_FINGERPRINT_V7 + ", стало " + fp7)
        check("world.v6_and_v7_differ", fp6 != fp7)
    }

    // --- 9б. ЖИВАЯ ТАЙГА v7: остановки, выборы, раны, переигрыш ---
    run {
        var halts = 0; var badHalt = 0; var replayMism = 0
        var encWorlds = 0; var totalObs = 0L; var emptyW = 0
        var firstIn7 = 0
        for (i in 0 until 300) {
            val seed = 880000L + i * 61L
            val season = i % 4
            val phases = 30 * SurvivalEngine.PHASES
            val eng = SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), 7)

            // Прогон «жизнь»: решения принимаются по мере остановок (вариант 1),
            // как их принимал бы игрок. Копим фактические выборы и тропу.
            val choices = HashMap<Int, Int>()
            val heads = IntArray(31) { -1 }
            var from = 0
            var guard = 0
            val liveEv = ArrayList<String>()
            while (from < phases && guard < 40) {
                guard++
                val s = eng.run(from, phases, {}, { d -> if (d in 1..30) heads[d].let { if (it >= 0) it else -1 } else -1 },
                    { d, h -> if (d in 1..30) heads[d] = h },
                    { d -> choices[d] ?: -1 }) { e ->
                    liveEv.add(e.tick.toString() + "|" + e.key + "|" + (e.params["txt"] ?: "") + "|" + e.phase)
                }
                if (s.haltedDay > 0) {
                    halts++
                    if (s.haltedDay < 1 || s.haltedDay > 30) badHalt++
                    if (choices.containsKey(s.haltedDay)) badHalt++ // одна встреча — одна остановка
                    choices[s.haltedDay] = 1
                    // прогресс не может уйти дальше кануна встречи
                    from = (s.haltedDay - 1) * SurvivalEngine.PHASES
                } else {
                    from = phases
                }
            }
            if (guard >= 40) badHalt++
            if (choices.isNotEmpty()) encWorlds++

            // Переигрыш одним куском с готовыми выборами и тропой обязан дать
            // ровно те же события (журнал переигрывается честно).
            val repEv = ArrayList<String>()
            eng.run(0, phases, {}, { d -> if (d in 1..30 && heads[d] >= 0) heads[d] else -1 },
                { _, _ -> }, { d -> choices[d] ?: -1 }) { e ->
                repEv.add(e.tick.toString() + "|" + e.key + "|" + (e.params["txt"] ?: "") + "|" + e.phase)
            }
            if (repEv != liveEv) replayMism++

            // Плотность: радар больше не пустой месяцами.
            val obs = eng.observations(phases, { d -> if (d in 1..30 && heads[d] >= 0) heads[d] else -1 },
                { d -> choices[d] ?: -1 })
            totalObs += obs.size
            if (obs.isEmpty()) emptyW++
            if (obs.any { it.tick <= 7 }) firstIn7++
        }
        println("v7 живая тайга (300 миров × 30 дн): наблюдений/мир=" +
            "%.1f".format(totalObs.toDouble() / 300) +
            " · пустых миров=" + emptyW +
            " · встреч-миров=" + encWorlds + " · остановок=" + halts +
            " · след в первые 7 дн: " + (firstIn7 * 100 / 300) + "%")
        check("wild.halt_sane", badHalt == 0, "badHalt=" + badHalt)
        check("wild.replay_faithful", replayMism == 0, "мисматчей=" + replayMism)
        check("wild.not_empty", emptyW == 0, "пустых=" + emptyW)
        check("wild.first_week", firstIn7 >= 285, "лишь " + firstIn7 + "/300")
    }

    // --- 9. РАДАР: наблюдения и туман войны (200 000 дней мира) ---
    run {
        var obsTotal = 0L
        var days = 0L
        var badSector = 0; var badDist = 0; var badAge = 0; var badMark = 0
        var knownDays = 0L
        var attentionDays = 0L
        val bySource = IntArray(3)
        for (i in 0 until 2000) {
            val season = i % 4
            val seed = 310000L + i * 41L
            val eng = SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), 2)
            val obs = eng.observations(100 * SurvivalEngine.PHASES)
            val snaps = eng.daySnapshots(100)
            for (o in obs) {
                obsTotal++
                if (o.sector < 0 || o.sector > 7) badSector++
                if (o.distKm <= 0.0 || o.distKm > 40.0) badDist++
                if (o.tick < 1 || o.tick > 100) badAge++
                if (o.source in 0..2) bySource[o.source]++ else badAge++
            }
            for (t in 1..100) {
                days++
                val upto = obs.filter { it.tick <= t }
                val rec = RadarModel.build(upto, snaps[t - 1], t, 2)
                if (rec.marks.isNotEmpty()) knownDays++
                if (rec.marks.any { it.attention }) attentionDays++
                for (m in rec.marks) {
                    if (m.ageDays < 0 || m.ageDays > RadarModel.FORGET_DAYS) badMark++
                    if (m.attention && m.ageDays != 0) badMark++
                    if (m.uncertaintyKm >= RadarModel.FORGET_KM) badMark++
                    if (m.freshness <= 0.0 || m.freshness > 1.0) badMark++
                }
                if (rec.marks.size + rec.unknown.size != RadarModel.KINDS.size &&
                    rec.marks.size + rec.unknown.size > RadarModel.KINDS.size) badMark++
            }
        }
        println("радар: наблюдений " + obsTotal + " (видел " + bySource[0] +
            " · след " + bySource[1] + " · слух " + bySource[2] +
            ") · дней со сведениями " + (knownDays * 100 / days) +
            " проц. · дней тревоги " + (attentionDays * 100 / days) + " проц.")
        check("radar.sector_in_range", badSector == 0, "" + badSector)
        check("radar.dist_sane", badDist == 0, "" + badDist)
        check("radar.obs_sane", badAge == 0, "" + badAge)
        check("radar.mark_sane", badMark == 0, "" + badMark)
        check("radar.obs_exist", obsTotal > 0, "" + obsTotal)
        // Радар не должен ни молчать всегда, ни знать всё всегда.
        check("radar.knows_sometimes", knownDays * 100 / days in 5..90,
            "" + (knownDays * 100 / days))
        check("radar.alarm_is_rare", attentionDays * 100 / days <= 30,
            "" + (attentionDays * 100 / days))
    }

    // --- 9b. Радар не знает того, о чём журнал промолчал ---
    run {
        var orphan = 0
        var mid = 0
        for (i in 0 until 300) {
            val seed = 610000L + i * 7L
            val eng = SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 2)
            val ticks = HashSet<Int>()
            val obs = ArrayList<Obs>()
            eng.run(0, 100 * SurvivalEngine.PHASES, { obs.add(it) }) { e ->
                if (e.category == "animal" || e.category == "track") ticks.add(e.tick)
            }
            for (o in obs) if (!ticks.contains(o.tick)) orphan++

            // догон, оборванный на утре: вечерние наблюдения того же дня
            // ещё не случились — радар не имеет права их показывать
            val g = SurvivalEngine.globalPhase(41, SurvivalEngine.MORNING)
            val part = eng.observations(g)
            if (part.any { it.tick == 41 && it.phase > SurvivalEngine.MORNING }) mid++
            if (part.any { it.tick > 41 }) mid++
            // и наблюдения только копятся: префикс полного списка
            val full = eng.observations(100 * SurvivalEngine.PHASES)
            if (full.take(part.size) != part) mid++
        }
        check("radar.no_orphan_observations", orphan == 0, "" + orphan)
        check("radar.obs_phase_gated_and_append_only", mid == 0, "" + mid)
    }

    // --- 9c. Туман войны: знание расползается и в конце концов гаснет ---
    run {
        check("radar.uncertainty_grows",
            RadarModel.uncertaintyKm(FaunaModel.WOLF, Obs.SEEN, 1, 4) >
                RadarModel.uncertaintyKm(FaunaModel.WOLF, Obs.SEEN, 0, 4))
        check("radar.freshness_fades", RadarModel.freshness(3) < RadarModel.freshness(0))
        check("radar.hearing_is_vaguer_than_sight",
            RadarModel.baseErrKm(Obs.HEARD) > RadarModel.baseErrKm(Obs.SEEN))
        // Знание НАСЫЩАЕТСЯ: прирост неопределённости за седьмые сутки
        // должен быть заметно меньше, чем за первые. Зверь живёт в участке,
        // а не уходит по прямой.
        val d1 = RadarModel.uncertaintyKm(FaunaModel.WOLF, Obs.SEEN, 1, 4) -
            RadarModel.uncertaintyKm(FaunaModel.WOLF, Obs.SEEN, 0, 4)
        val d7 = RadarModel.uncertaintyKm(FaunaModel.WOLF, Obs.SEEN, 7, 4) -
            RadarModel.uncertaintyKm(FaunaModel.WOLF, Obs.SEEN, 6, 4)
        check("radar.memory_saturates", d7 < d1 * 0.25, String.format("%.3f/%.3f", d1, d7))
        // Росомаха мотается широко и забывается; лось топчется и помнится.
        check("radar.wolverine_forgotten_first",
            RadarModel.uncertaintyKm(FaunaModel.WOLVERINE, Obs.SEEN, 10, 4) >
                RadarModel.uncertaintyKm(FaunaModel.MOOSE, Obs.SEEN, 10, 4) * 3)
        // И главное: росомаху, разорившую лагерь ТРИ дня назад, помнят.
        check("radar.wolverine_remembered_3_days",
            RadarModel.uncertaintyKm(FaunaModel.WOLVERINE, Obs.SEEN, 3, 4) < RadarModel.FORGET_KM,
            String.format("%.2f", RadarModel.uncertaintyKm(FaunaModel.WOLVERINE, Obs.SEEN, 3, 4)))
    }

    // --- 15. ПАМЯТЬ РАДАРА СВЕРЯЕТСЯ С МИРОМ ---
    //
    // Радар предполагает, как быстро зверь уходит. Мир знает это точно.
    // Здесь они встречаются. Если походка зверя изменится, а таблицу забудут
    // пересчитать — прогон упадёт ЗДЕСЬ, а не в журнале у человека.
    //
    // Это же и главный диагностический стол: таблица печатается каждый раз.
    run {
        for (version in intArrayOf(2, 3, 4, 5, 6)) {
            val kinds = listOf(FaunaModel.WOLF, FaunaModel.BEAR, FaunaModel.WOLVERINE,
                FaunaModel.LYNX, FaunaModel.MOOSE)
            val disp = HashMap<String, Array<MutableList<Double>>>()
            for (k in kinds) disp[k] = Array(22) { mutableListOf<Double>() }

            for (i in 0 until 200) {
                val seed = 313000L + i * 37L
                val season = i % 4
                val eng = SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), version)
                val hist = HashMap<String, DoubleArray>()
                for (k in kinds) hist[k] = DoubleArray(121)
                val agents = FaunaModel.spawn(seed, season)
                var state = WeatherModel.initial(
                    version, SplitMix64.forTick(seed, 0), eng.yearDay(0), season)
                var dir = 0
                // v6: лагерь едет с игроком. Типичный ход — разведка автопилота
                // по плавной дуге (старт-сторона + день/5). Смещение distKm
                // меряем именно на нём — оно и есть «типичное» для памяти радара.
                var px = 0.0; var py = 0.0
                val startDir = SplitMix64.forTick(seed xor SurvivalEngine.AUTOPILOT_SALT, 0).nextInt(8)
                for (t in 1..120) {
                    val rng = SplitMix64.forTick(seed, t)
                    state = WeatherModel.step(version, state, eng.yearDay(t), rng)
                    dir = WindField.step(seed, t, dir, state.front)
                    if (version >= 6) {
                        val h = (startDir + t / 5) % 8
                        px += SurvivalEngine.TRAVEL_KM_PER_DAY * Math.sin(h * Math.PI / 4.0)
                        py += SurvivalEngine.TRAVEL_KM_PER_DAY * Math.cos(h * Math.PI / 4.0)
                    }
                    FaunaModel.step(agents, state, dir, eng.seasonOf(t), seed, t, version,
                        SurvivalEngine.lightX10(eng.yearDay(t)),
                        SurvivalEngine.moonPhase(eng.yearDay(t), seed), px, py)
                    for (a in agents) if (a.alive) hist[a.kind]!![t] = a.distKm
                }
                for (k in kinds) {
                    val h = hist[k]!!
                    for (t in 1..120) for (dt in 1..21) {
                        if (t + dt <= 120 && h[t] > 0.0 && h[t + dt] > 0.0) {
                            disp[k]!![dt].add(Math.abs(h[t + dt] - h[t]))
                        }
                    }
                }
            }

            println("смещение зверя, мир v" + version + " (80-й процентиль -> что помнит радар):")
            var wrong = 0
            for (k in kinds) {
                val line = StringBuilder("  " + k.padEnd(11))
                for (dt in intArrayOf(1, 3, 7, 14)) {
                    val l = disp[k]!![dt].sorted()
                    if (l.isEmpty()) continue
                    val real = l[l.size * 8 / 10]
                    val model = RadarModel.uncertaintyKm(k, Obs.SEEN, dt, version) -
                        RadarModel.baseErrKm(Obs.SEEN)
                    line.append(String.format("%dсут %.2f/%.2f  ", dt, real, model))
                    // Радар вправе ошибаться в пределах трети — но не в разы.
                    if (model < real * 0.65 || model > real * 1.45) wrong++
                }
                println(line)
            }
            check("radar.memory_matches_world_v" + version, wrong == 0, "" + wrong)
        }
    }

    // --- 9d. Запах радара == запах зверя. Одна физика, две ветки кода ---
    run {
        var mismatch = 0
        for (i in 0 until 300) {
            val seed = 990000L + i * 11L
            val eng = SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 2)
            val snaps = eng.daySnapshots(60).associateBy { it.tick }
            eng.run(0, 60 * SurvivalEngine.PHASES) { e ->
                val d = snaps[e.tick]
                if (d != null) {
                    val s10 = (ScentModel.campScentKm(d.wind, d.tempC, d.precip, d.fog) * 10).toInt()
                    if (s10 != (e.ctx["scent"] ?: s10)) mismatch++
                    if (d.windDir != (e.ctx["windir"] ?: d.windDir)) mismatch++
                }
            }
        }
        check("radar.scent_matches_world", mismatch == 0, "" + mismatch)
    }

    // --- 10. ОТЧЁТ РАДАРА: текст не имеет права расходиться с экраном ---
    run {
        var bad = 0
        var machineLines = 0L
        var reports = 0L
        for (i in 0 until 500) {
            val seed = 550000L + i * 23L
            val eng = SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 2)
            val obs = eng.observations(80 * SurvivalEngine.PHASES)
            val snaps = eng.daySnapshots(80)
            for (t in intArrayOf(1, 7, 30, 80)) {
                val rec = RadarModel.build(obs.filter { it.tick <= t }, snaps[t - 1], t, 4)
                val txt = RadarModel.report(1L, SurvivalEngine.headerOf(snaps[t - 1]), rec)
                reports++
                if (!txt.startsWith("РАДАР")) bad++
                if (!txt.contains("# day=" + t)) bad++
                // машинных строк ровно столько, сколько меток на экране
                val lines = txt.lines().filter {
                    it.startsWith("# ") && !it.contains("day=") && !it.contains("FADED")
                }
                if (lines.size != rec.marks.size) bad++
                // Устаревшие сведения тоже обязаны быть в отчёте — строкой.
                val fadedLines = txt.lines().count { it.contains("FADED") }
                if (fadedLines != rec.faded.size) bad++
                machineLines += lines.size
                // каждый зверь назван по-русски ровно один раз
                for (m in rec.marks) {
                    if (!txt.contains(RadarModel.kindRu(m.kind))) bad++
                    if (m.attention && !txt.contains("ЧУЕТ ЛАГЕРЬ")) bad++
                }
                // отчёт не имеет права упоминать зверя, которого нет на радаре
                for (k in rec.unknown) {
                    if (txt.contains("# " + k + " ")) bad++
                }
                if (txt.contains("{") || txt.contains("null")) bad++
            }
        }
        println("отчёты радара: " + reports + " · машинных строк " + machineLines)
        check("report.matches_screen", bad == 0, "" + bad)
    }

    // --- 11. ТЕКСТ НЕ ВРЁТ ---
    //
    // Два правила, обе нарушены в реальном журнале до v115:
    //   а) число и падеж приходят из мира, а не из строки корпуса;
    //   б) сторона света, названная в тексте, обязана совпадать с ветром
    //      этого дня — и не имеет права звучать в штиль.
    run {
        // падежи стаи
        check("text.pack_1", SurvivalEngine.packWord(1) == "зверь")
        check("text.pack_2", SurvivalEngine.packWord(2) == "зверя")
        check("text.pack_5", SurvivalEngine.packWord(5) == "зверей")
        check("text.pack_11", SurvivalEngine.packWord(11) == "зверей")
        check("text.pack_21", SurvivalEngine.packWord(21) == "зверь")
        check("text.pack_22", SurvivalEngine.packWord(22) == "зверя")

        // в самом корпусе не должно остаться ни одной зашитой стороны света
        val roots = listOf("север", "юг", "восток", "запад", "Север", "Юг", "Восток", "Запад")
        var hardcoded = 0
        for (line in File(args[0]).readLines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || !l.contains("|")) continue
            val text = l.substringAfter("|")
            if (roots.any { text.contains(it) }) {
                hardcoded++
                println("  зашитая сторона света: " + l.take(70))
            }
        }
        check("text.no_hardcoded_direction", hardcoded == 0, "" + hardcoded)
    }

    // --- 11b. Сторона света в готовом тексте == ветер этого дня ---
    run {
        val gen = SurvivalEngine.WIND_FROM
        val acc = SurvivalEngine.WIND_TO
        var lies = 0
        var calmLies = 0
        var withDir = 0L
        for (i in 0 until 3000) {
            val seed = 121000L + i * 17L
            SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 2)
                .run(0, 70 * SurvivalEngine.PHASES) { e ->
                    val txt = corpus.renderOrNull(e.key, e.roll, e.params, e.ctx, e.nth) ?: ""
                    val dir = e.ctx["windir"] ?: 0
                    val wind = e.ctx["wind"] ?: 0
                    // «на север» — подстрока «на северо-восток», поэтому
                    // совпадение засчитывается только по границе слова.
                    fun hit(txt: String, needle: String): Boolean {
                        var i = txt.indexOf(needle)
                        while (i >= 0) {
                            val j = i + needle.length
                            val nxt = if (j < txt.length) txt[j] else ' '
                            if (nxt != '-' && !nxt.isLetter()) return true
                            i = txt.indexOf(needle, i + 1)
                        }
                        return false
                    }
                    // «с юго-запада» — только если ветер сегодня оттуда
                    for (k in 0 until 8) {
                        if (hit(txt, "с " + gen[k]) || hit(txt, "стороны " + gen[k])) {
                            withDir++
                            if (k != dir) lies++
                            if (wind == 0) calmLies++
                        }
                        if (hit(txt, "на " + acc[k])) {
                            withDir++
                            if (k != Compass.downwind(dir)) lies++
                            if (wind == 0) calmLies++
                        }
                    }
                    // Падеж обязан отвечать числу: 2 зверя — верно,
                    // 5 зверя — ложь. Проверяем ровно так: слово рядом с
                    // числом должно совпасть с packWord этого числа.
                    for (n in 1..12) {
                        for (wd in listOf("зверь", "зверя", "зверей")) {
                            if (txt.contains("" + n + " " + wd) &&
                                wd != SurvivalEngine.packWord(n)) lies++
                        }
                    }
                }
        }
        println("текст: сторон света названо " + withDir + " раз")
        check("text.direction_matches_world", lies == 0, "" + lies)
        check("text.no_direction_in_calm", calmLies == 0, "" + calmLies)
        check("text.direction_actually_used", withDir > 0, "" + withDir)
    }

    // --- 12. v3: ЗВЕРЬ ИДЁТ, А НЕ ПРЫГАЕТ (200 000 дней) ---
    run {
        var days = 0L
        var animalDays = 0L
        var teleports = 0        // прыжок больше суточного предела
        var closing = 0          // наблюдений «ближе, чем в прошлый раз»
        var mooseSeen = 0
        var mooseTracks = 0
        var wolverine = 0
        var atFloor = 0          // наблюдений ровно на минимуме (признак телепорта)
        var obsTotal = 0
        for (i in 0 until 2000) {
            val seed = 777000L + i * 13L
            val eng = SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 3)
            eng.run(0, 100 * SurvivalEngine.PHASES) { e ->
                if (e.category == "animal" && e.key != "fauna.quiet") animalDays++
                if (e.key == "fauna.moose.near") mooseSeen++
                if (e.key == "track.moose") mooseTracks++
                if (e.key.startsWith("fauna.wolverine")) wolverine++
            }
            val obs = eng.observations(100 * SurvivalEngine.PHASES)
            val seq = HashMap<String, MutableList<Pair<Int, Double>>>()
            for (o in obs) {
                obsTotal++
                if (o.distKm <= 0.16) atFloor++
                seq.getOrPut(o.kind) { ArrayList() }.add(o.tick to o.distKm)
            }
            for ((_, l) in seq) {
                for (j in 1 until l.size) {
                    val dt = l[j].first - l[j - 1].first
                    val drop = l[j - 1].second - l[j].second
                    if (drop > 0) closing++
                    // За сутки зверь не может сократить расстояние больше,
                    // чем ему позволено на этом расстоянии: вдали он волен
                    // мотать по тайге, вблизи — осторожничает.
                    val limit = minOf(3.05, l[j - 1].second * 0.5 + 0.35)
                    if (dt == 1 && drop > limit) teleports++
                }
            }
            days += 100
        }
        val floorPct = atFloor * 100 / maxOf(1, obsTotal)
        println("v3: дней со зверем " + (animalDays * 100 / days) + " проц. · росомаха " +
            (wolverine * 100L / days) + " проц. · шагов навстречу " + closing +
            " · лось: встреч " + mooseSeen + ", следов " + mooseTracks +
            " · наблюдений в упор " + floorPct + " проц.")

        check("v3.no_teleport", teleports == 0, "" + teleports)
        // Главное, ради чего всё затевалось: зверь ВИДНО, как подходит.
        check("v3.approach_is_visible", closing > 8000, "" + closing)
        // Лось перестал быть мёртвым кодом.
        check("v3.moose_is_alive", mooseSeen > 100 && mooseTracks > 100,
            mooseSeen.toString() + "/" + mooseTracks)
        // Росомаха не поселилась в журнале.
        check("v3.wolverine_not_wallpaper", wolverine * 100L / days <= 10,
            "" + (wolverine * 100L / days))
        // Зверь не стоит вечно у палатки и не исчезает из мира.
        check("v3.animal_days_sane", animalDays * 100 / days in 10..35,
            "" + (animalDays * 100 / days))
        // В v2 97% наблюдений медведя были ровно на 150 м. Теперь — полоса.
        check("v3.not_everything_at_the_tent", floorPct <= 35, "" + floorPct)
    }

    // --- 13. ЖУРНАЛ НЕ ПОВТОРЯЕТСЯ ---
    //
    // Реальный зимний журнал за 60 дней: «Ходил за водой к полынье» пять раз,
    // «Росомаха. След косой...» четыре, из них дважды ПОДРЯД. Причина была не
    // в бедности корпуса, а в том, что обещанная ротация не работала: точка
    // отсчёта бралась из свежего броска и затирала счётчик срабатываний.
    run {
        var backToBack = 0      // один и тот же текст два раза подряд у ключа
        var rendered = 0L
        var dupes = 0L          // повтор текста ключа внутри экспедиции
        var tightDays = 0L      // дней, где выбирать было не из чего (пул = 1)
        var poolSum = 0L
        var poolMin = 99
        for (i in 0 until 1500) {
            val seed = 505000L + i * 29L
            val eng = SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 3)
            val prev = HashMap<String, String>()
            val used = HashMap<String, HashSet<String>>()
            eng.run(0, 90 * SurvivalEngine.PHASES) { e ->
                val txt = corpus.renderOrNull(e.key, e.roll, e.params, e.ctx, e.nth, seed)
                if (txt != null) {
                    rendered++
                    val p = corpus.poolSize(e.key, e.ctx)
                    poolSum += p
                    if (p < poolMin) poolMin = p
                    if (p <= 1) tightDays++
                    if (prev[e.key] == txt) backToBack++
                    if (used.getOrPut(e.key) { HashSet() }.add(txt)) Unit else dupes++
                    prev[e.key] = txt
                }
            }
        }
        println("текст: строк " + rendered + " · повторов подряд " + backToBack +
            " · повторов внутри экспедиции " + (dupes * 100 / rendered) + " проц." +
            " · пул: мин " + poolMin + ", средний " + (poolSum / rendered))

        // Главное: один и тот же текст не может выйти два раза подряд.
        check("text.no_back_to_back", backToBack == 0, "" + backToBack)
        // Кубику должно быть где развернуться в любой день мира.
        check("text.pool_never_single", tightDays == 0L, "" + tightDays)
        check("text.pool_is_wide", poolSum / rendered >= 5, "" + (poolSum / rendered))
        // Полностью без повторов за 90 дней не выйдет — но их должно быть мало.
        check("text.repeats_are_rare", dupes * 100 / rendered <= 30,
            "" + (dupes * 100 / rendered))
    }

    // --- 13b. Показания неба: свет и луна — календарь, а не кубик ---
    run {
        // зимой темно, летом светло — и это не мнение
        val winterLight = SurvivalEngine.lightX10(45)
        val summerLight = SurvivalEngine.lightX10(225)
        check("sky.winter_is_dark", winterLight in 45..60, "" + winterLight)
        check("sky.summer_is_bright", summerLight in 180..195, "" + summerLight)
        var bad = 0
        for (yd in 0 until 360) {
            val l = SurvivalEngine.lightX10(yd)
            if (l < 50 || l > 190) bad++
            val m = SurvivalEngine.moonPhase(yd, 12345L)
            if (m < 0 || m > 7) bad++
        }
        check("sky.always_sane", bad == 0, "" + bad)
        // луна проходит полный круг примерно за месяц
        val seen = HashSet<Int>()
        for (yd in 0 until 30) seen.add(SurvivalEngine.moonPhase(yd, 777L))
        check("sky.moon_cycles", seen.size >= 7, "" + seen.size)
    }

    // --- 14. v4: ГРАНИЦА ЗНАНИЯ (200 000 дней) ---
    //
    // Радара в тайге нет: есть человек, который видит, слышит и читает следы.
    // Значит, в непогоду прибор не шумит — прибор ПУСТЕЕТ. Ложных меток мы
    // не ставим: в игре про честность одна выдуманная метка убила бы доверие
    // к экрану навсегда. Вместо этого расширяется ореол — признание в том,
    // что человек не уверен.
    run {
        var days = 0L
        var blind = 0L
        var deaf = 0L
        var badRange = 0
        var upwind = 0
        var downwind = 0
        var badErr = 0
        var fadedShown = 0L
        var radars = 0L
        var seenInBlizzard = 0
        for (i in 0 until 2000) {
            val seed = 777000L + i * 13L
            val eng = SurvivalEngine(seed, i % 4, SurvivalEngine.startOffsetFrom(seed), 4)
            val snaps = eng.daySnapshots(100)
            for (d in snaps) {
                days++
                val sg = SenseModel.sightKm(d.cloud, d.precip, d.fog, d.wind, d.light, d.moon)
                val hr = SenseModel.hearKm(d.wind, d.precip, d.tempC, d.fog)
                if (sg < 0.08 || sg > 2.5) badRange++
                if (hr < 0.3 || hr > 6.5) badRange++
                if (sg < 0.4) blind++
                if (hr < 1.5) deaf++
            }
            val obs = eng.observations(100 * SurvivalEngine.PHASES)
            for (o in obs) {
                if (o.bearingErr < 0 || o.bearingErr > 2) badErr++
                // Глазами и по следу сторона известна точно — иначе это ложь.
                if (o.source != Obs.HEARD && o.bearingErr != 0) badErr++
                val d = snaps[o.tick - 1]
                val sg = SenseModel.sightKm(d.cloud, d.precip, d.fog, d.wind, d.light, d.moon)
                // В пургу зверя НЕ ВИДНО. Далёкое наблюдение глазами в такой
                // день — это ровно тот баг, ради которого всё затевалось.
                // Глазами дальше трёх границ видимости не увидишь НИКАК.
                if (o.source == Obs.SEEN && o.distKm > sg * 3.05) seenInBlizzard++
                if (o.source == Obs.HEARD && d.wind > 0) {
                    val df = Compass.diff(o.sector, d.windDir)
                    if (df <= 1) upwind++ else if (df >= 3) downwind++
                }
            }
            for (t in intArrayOf(20, 50, 80, 100)) {
                radars++
                val rec = RadarModel.build(obs.filter { it.tick <= t }, snaps[t - 1], t, 4)
                if (rec.faded.isNotEmpty()) fadedShown++
                if (rec.sightKm <= 0.0 || rec.hearKm <= 0.0) badRange++
                // Метка и «устаревшее» — непересекающиеся множества.
                for (m in rec.marks) if (rec.faded.any { it.kind == m.kind }) badRange++
            }
        }
        println("чувства: слепых дней " + (blind * 100 / days) + " проц. · глухих " +
            (deaf * 100 / days) + " проц. · вой против ветра " + upwind +
            " против " + downwind + " по ветру · радаров с устаревшими сведениями " +
            (fadedShown * 100 / radars) + " проц.")

        check("sense.ranges_sane", badRange == 0, "" + badRange)
        check("sense.bearing_err_sane", badErr == 0, "" + badErr)
        // Мир не должен ослепнуть насовсем — но и не видеть всё насквозь.
        check("sense.blind_days_exist", blind * 100 / days in 5..35, "" + (blind * 100 / days))
        // Против ветра слышно ощутимо лучше — это зеркало конуса запаха.
        check("sense.upwind_hears_better", upwind > downwind * 2, upwind.toString() + "/" + downwind)
        // В пургу зверя не видно. Совсем.
        check("sense.blizzard_blinds", seenInBlizzard == 0, "" + seenInBlizzard)
        // Забытое показывается строкой, а не исчезает молча.
        check("radar.faded_is_shown", fadedShown * 100 / radars >= 30,
            "" + (fadedShown * 100 / radars))
    }

    if (failures == 0) {
        println("OK: все инварианты выдержаны")
    } else {
        println("ПРОВАЛОВ: " + failures)
        kotlin.system.exitProcess(1)
    }
}
