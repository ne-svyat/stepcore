package com.vasil.stepcore.survival.harness

import com.vasil.stepcore.survival.engine.BackupCodec
import com.vasil.stepcore.survival.engine.Corpus
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

    // --- 1. Детерминизм: два прогона одного seed идентичны ---
    run {
        val a = ArrayList<String>(); val b = ArrayList<String>()
        SurvivalEngine(42L, 3, 30, 2).run(0, 100) { a.add(sig(it)) }
        SurvivalEngine(42L, 3, 30, 2).run(0, 100) { b.add(sig(it)) }
        check("determinism.same_seed", a == b)
        val c = ArrayList<String>()
        SurvivalEngine(43L, 3, 30, 2).run(0, 100) { c.add(sig(it)) }
        check("determinism.diff_seed_diff_world", a != c)
    }

    // --- 2. Инвариантность партий: 1x100 == куски произвольной нарезки ---
    run {
        val whole = ArrayList<String>()
        SurvivalEngine(777L, 2, 25, 2).run(0, 100) { whole.add(sig(it)) }
        val cuts = listOf(0, 1, 7, 8, 37, 64, 99, 100)
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
            val summary = eng.run(0, 100) { e ->
                events++
                when (e.category) {
                    "weather" -> weather++
                    "ambient" -> ambient++
                    "milestone" -> milestones++
                    "digest" -> digests++
                }
                if (e.key == "wx.snow_first") expFirstSnowEvent = true
                perDay[e.tick] = (perDay[e.tick] ?: 0) + 1
                val txt = corpus.renderOrNull(e.key, e.roll, e.params, e.ctx)
                if (txt == null) {
                    holes++
                    println("  нет подходящего варианта: " + e.key + " ctx=" + e.ctx)
                } else {
                    if (txt.startsWith("[")) { holes++; println("  дыра корпуса: " + e.key) }
                    if (txt.contains("{")) { holes++; println("  сирота-плейсхолдер: " + txt) }
                    val lie = physicalLie(txt, e.ctx)
                    if (lie != null) { lies++; println("  ФИЗИЧЕСКАЯ ЛОЖЬ [" + lie + "] " + e.key + " ctx=" + e.ctx + " :: " + txt) }
                    if (e.category == "digest" && claimsCalm(txt) &&
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
        println("события/день = " + String.format("%.3f", perDayRate) +
            "  (погода " + weather + " · сводки " + digests + " · зарисовки " + ambient + " · вехи " + milestones + ")")
        println("бурь: " + String.format("%.1f", stormDaysTotal * 100.0 / days) + " проц. дней")
        println("температуры по сезонам (мин..макс): зима " + tempMin[0] + ".." + tempMax[0] +
            " · весна " + tempMin[1] + ".." + tempMax[1] +
            " · лето " + tempMin[2] + ".." + tempMax[2] +
            " · осень " + tempMin[3] + ".." + tempMax[3])
        println("первый снег: осень " + autumnFirstSnow + "/" + autumnRuns)

        // каждый тик = событие или сводка, плюс редкий ambient сверху
        check("rate.per_day_min_one", perDayRate >= 1.0, "" + perDayRate)
        check("rate.per_day_not_spammy", perDayRate <= 1.30, "" + perDayRate)
        // событий-погоды (без сводок) — прежняя редкость, мир не сорит драмой
        val wxRate = weather.toDouble() / days
        check("rate.weather_events_rare", wxRate in 0.10..0.55, "" + wxRate)
        check("rate.max_three_per_day", (perDayMax.values.maxOrNull() ?: 0) <= 3)
        check("corpus.no_holes", holes == 0)
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
    run {
        var badGrowth = 0; var badSummerSnow = 0; var winterNoSnow = 0
        var maxCm = 0; var coverSeen = 0
        for (i in 0 until 500) {
            val season = i % 4
            val seed = 900000L + i * 17L
            val eng = SurvivalEngine(seed, season, SurvivalEngine.startOffsetFrom(seed), 2)
            var prevCm = -1
            var prevCtx: Map<String, Int> = emptyMap()
            eng.run(0, 120) { e ->
                val cm = e.ctx["snowcm"] ?: 0
                val pr = e.ctx["precip"] ?: 0
                val t = e.ctx["t"] ?: 0
                if (prevCm >= 0 && cm > prevCm && pr < 3) badGrowth++
                if (cm > maxCm) maxCm = cm
                if (cm > 0) coverSeen++
                // зима "встала" — значит покров есть
                if ((e.ctx["winter"] ?: 0) == 1 && cm == 0 && (prevCtx["winter"] ?: 0) == 1) winterNoSnow++
                // снег не может лежать при устойчивой жаре
                if (cm > 0 && t >= 20) badSummerSnow++
                prevCm = cm
                prevCtx = e.ctx
            }
        }
        println("земля: макс. покров " + maxCm + " см · дней с покровом " + coverSeen)
        check("ground.no_snow_from_nothing", badGrowth == 0, "" + badGrowth)
        check("ground.no_snow_in_heat", badSummerSnow == 0, "" + badSummerSnow)
        check("ground.winter_implies_snow", winterNoSnow == 0, "" + winterNoSnow)
        check("ground.snow_accumulates", maxCm in 20..180, "" + maxCm)
    }

    // --- 3c. Версия 1 жива: старые экспедиции доигрываются старым миром ---
    run {
        val a = ArrayList<String>(); val b = ArrayList<String>()
        SurvivalEngine(555L, 3, 30, 1).run(0, 80) { a.add(sig(it)) }
        SurvivalEngine(555L, 3, 30, 1).run(0, 80) { b.add(sig(it)) }
        check("v1.determinism", a == b)
        val v2 = ArrayList<String>()
        SurvivalEngine(555L, 3, 30, 2).run(0, 80) { v2.add(sig(it)) }
        check("v1.differs_from_v2", a != v2)
        var holes = 0
        SurvivalEngine(555L, 3, 30, 1).run(0, 80) { e ->
            if (corpus.renderOrNull(e.key, e.roll, e.params, e.ctx, e.nth) == null) holes++
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
            "wx.clear_streak", "wx.fog", "wx.wind_strong", "wx.snow_deep", "ambient",
            "phase.winter_set", "phase.snow_gone", "phase.river_freeze", "phase.river_open",
            "final.snow_cover",
            "digest",
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

    if (failures == 0) {
        println("OK: все инварианты выдержаны")
    } else {
        println("ПРОВАЛОВ: " + failures)
        kotlin.system.exitProcess(1)
    }
}
