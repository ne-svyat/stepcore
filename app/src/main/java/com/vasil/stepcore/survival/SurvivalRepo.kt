package com.vasil.stepcore.survival

import android.content.Context
import androidx.room.withTransaction
import com.vasil.stepcore.AppDb
import com.vasil.stepcore.StepService
import com.vasil.stepcore.survival.engine.Corpus
import com.vasil.stepcore.survival.engine.ExpeditionSummary
import com.vasil.stepcore.survival.engine.SplitMix64
import com.vasil.stepcore.survival.engine.StepLedger
import com.vasil.stepcore.survival.engine.DaySnap
import com.vasil.stepcore.survival.engine.RadarModel
import com.vasil.stepcore.survival.engine.SurvivalEngine
import com.vasil.stepcore.survival.engine.WildModel
import com.vasil.stepcore.survival.engine.WorldEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Один мьютекс на процесс: репозиторий создаётся на каждый экран,
 * а догон мира не должен идти в две руки (двойной тап по «Обновить»,
 * onResume поверх кнопки). Транзакция ниже атомарна и без мьютекса,
 * но мьютекс избавляет от двойной работы и гонок чтения-перед-записью.
 */
private val syncMutex = Mutex()

/**
 * Оркестратор Survival Mode. Ядро шагомера о нём не знает.
 *
 * Чтение шагов — строго read-only и только существующими путями ядра:
 * замороженные дни из таблицы days + сегодняшний счётчик из prefs.
 * Ноль записей в данные шагомера, ноль хуков в StepService.
 */
class SurvivalRepo(private val context: Context) {

    private val db = SurvivalDb.get(context)

    private val corpus: Corpus by lazy {
        Corpus(context.assets.open("survival/weather_ru.txt")
            .bufferedReader().use { it.readText() })
    }

    data class SyncOutcome(
        val expeditionId: Long,
        val newDays: Int,        // сколько дней мира созрело за этот догон
        val consumedSteps: Long, // сколько реальных шагов пришло с прошлого раза
        val completed: Boolean,  // экспедиция завершилась этим догоном
        val signal: Signal = Signal.NONE, // что озвучить: новое знание с этого догона
        val pending: PendingEnc? = null,  // мир стоит и ждёт решения игрока
    )

    /** Встреча, которая остановила мир: день, кто, текст и варианты решения. */
    data class PendingEnc(
        val day: Int,
        val kind: String,
        val kindRu: String,
        val text: String,
        val options: List<String>,
    )

    /**
     * Классификатор голоса тайги. Смотрит радар ДО и ПОСЛЕ догона и решает,
     * что нового узнал человек. Видит ровно то же, что игрок на радаре, — ни
     * байтом больше. Приоритет: опасность важнее выбора и события.
     */
    private fun classifySignal(
        before: RadarModel.Recon, after: RadarModel.Recon, courseHeading: Int,
    ): Signal {
        // Опасность: зверь стал чуять лагерь там, где раньше не чуял.
        val beforeAttn = before.marks.filter { it.attention }.map { it.kind }.toHashSet()
        val newAttn = after.marks.any { it.attention && it.kind !in beforeAttn }
        if (newAttn) return Signal.DANGER
        // Новое знание: метка (зверь+источник), которой раньше не было.
        val beforeKeys = before.marks.map { it.kind + "/" + it.source }.toHashSet()
        val gainedNew = after.marks.any { (it.kind + "/" + it.source) !in beforeKeys }
        if (gainedNew) return if (courseHeading == -1) Signal.CHOICE else Signal.EVENT
        return Signal.NONE
    }

    suspend fun active(): Expedition? = db.dao().active()
    suspend fun byId(id: Long): Expedition? = db.dao().byId(id)
    suspend fun archive(): List<Expedition> = db.dao().archive()
    suspend fun events(id: Long): List<ExpeditionEvent> = db.dao().eventsOf(id)

    /**
     * Курс дня для переигрыша мира. Прожитый день читается из тропы (фиксирован
     * навсегда), день за её концом — из приказа игрока courseHeading. -1 значит
     * «решает честный автопилот». Пустая тропа + приказ -1 = поведение v121
     * (чистый автопилот), поэтому экспедиции, начатые до v122, переигрываются
     * ровно так, как были прожиты.
     */
    private fun courseOf(e: Expedition, day: Int): Int =
        if (day >= 1 && day - 1 < e.path.length) Character.getNumericValue(e.path[day - 1])
        else e.courseHeading

    /**
     * Игрок задаёт курс на БУДУЩИЕ дни. Прожитые дни в тропе не трогаются —
     * прошлое неизменно. heading: 0..7 (румб) либо -1 (вернуть автопилот).
     * Текущий начатый день уже записан в тропу, поэтому новый курс вступает в
     * силу со следующего свежего дня — ты уже вышел, поворачиваешь назавтра.
     */
    private suspend fun choiceMap(id: Long): Map<Int, Int> =
        db.dao().choicesOf(id).associate { it.day to it.choice }

    /**
     * Решение игрока во встрече дня day. Записывается навсегда: прожитое
     * неизменно, переигрыш мира пойдёт по этому же решению. После записи
     * зови sync() — мир двинется дальше с этого места.
     */
    suspend fun choose(id: Long, day: Int, choice: Int) {
        val e = db.dao().byId(id) ?: return
        if (e.status != "active") return
        if (choiceMap(id).containsKey(day)) return // решение не переиграть
        db.dao().insertChoice(ExpeditionChoice(
            expeditionId = id, day = day, choice = choice.coerceAtLeast(0)))
    }

    suspend fun setCourse(id: Long, heading: Int) {
        val e = db.dao().byId(id) ?: return
        if (e.status != "active") return
        db.dao().updateExpedition(e.copy(courseHeading = heading.coerceIn(-1, 7)))
    }

    /**
     * Состояния дней экспедиции. Не читаются из базы — пересчитываются
     * из seed по правилам ТОЙ версии движка, на которой экспедиция начата.
     * Поэтому шапки карточек честны и для старых, уже прожитых миров.
     */
    fun days(e: Expedition): List<DaySnap> =
        SurvivalEngine(e.seed, e.startSeason, e.startOffset, e.engineVersion)
            .daySnapshots(e.ticksDone)

    /**
     * Что человек ЗНАЕТ об окрестностях — на последний прожитый день.
     *
     * Как и шапки дней, не хранится в базе: наблюдения пересчитываются из
     * seed теми же правилами, по которым мир и прожит. Отсюда следствие:
     * радар есть и у экспедиций, начатых до этого обновления, и у архивных
     * — там он показывает знание на день, когда всё закончилось.
     */
    suspend fun recon(e: Expedition): RadarModel.Recon {
        val eng = SurvivalEngine(e.seed, e.startSeason, e.startOffset, e.engineVersion)
        val ch = choiceMap(e.id)
        val obs = eng.observations(e.phasesDone, { day -> courseOf(e, day) },
            { day -> ch[day] ?: -1 })
        val day = eng.daySnapshots(e.ticksDone).lastOrNull()
        // Радар читает мир по правилам ТОЙ версии, в которой этот мир живёт.
        return RadarModel.build(obs, day, e.ticksDone, e.engineVersion)
    }

    /**
     * Полный текст экспедиции для копирования/шаринга: шапка, журнал в
     * ХРОНОЛОГИЧЕСКОМ порядке (в отличие от экрана, где новое сверху —
     * для чтения «истории» естественнее от старта к финалу) и, если
     * экспедиция завершена, итоговая строка уже лежит в журнале.
     *
     * Спека: завершённая экспедиция — это «полный отчёт», который человек
     * изучает и хранит. Этот текст и есть такой отчёт в переносимом виде.
     */
    suspend fun exportText(id: Long): String {
        val e = db.dao().byId(id) ?: return ""
        val evs = db.dao().eventsOf(id).sortedBy { it.id } // eventsOf идёт DESC — разворачиваем
        val sb = StringBuilder()
        sb.append("Экспедиция №").append(e.id).append('\n')
        sb.append("Северная тайга · старт: ")
            .append(SurvivalEngine.SEASON_RU[e.startSeason])
            .append(" · план ").append(e.plannedDays).append(' ')
            .append(SurvivalEngine.daysWord(e.plannedDays))
            .append(" · темп ").append(e.stepsPerTick).append(" шаг/день").append('\n')
        val statusLine = when (e.status) {
            "done_success" -> "Завершена по плану на " + e.ticksDone + " " +
                SurvivalEngine.daysWord(e.ticksDone) + " мира"
            "done_voluntary" -> "Прервана на " + e.ticksDone + " " +
                SurvivalEngine.daysWord(e.ticksDone) + " мира"
            else -> "В процессе · день " + e.ticksDone + " из " + e.plannedDays
        }
        sb.append(statusLine).append("\n\n")
        sb.append("— ЖУРНАЛ —\n")

        // Экспорт повторяет экран: день = шапка фактов + строки событий.
        // Сводки тихого дня, записанные до появления карточки, пропускаются:
        // они дублировали бы шапку. В базе они остаются — прошлое не трогаем.
        val byTick = HashMap<Int, MutableList<ExpeditionEvent>>()
        for (ev in evs) byTick.getOrPut(ev.tick) { mutableListOf() }.add(ev)
        val heads = days(e).associateBy { it.tick }

        for (t in 0..e.ticksDone) {
            val evsOfDay = byTick[t]?.filter { it.category != "digest" } ?: emptyList()
            val head = heads[t]
            if (evsOfDay.isEmpty() && head == null) continue
            if (t == 0) {
                for (ev in evsOfDay) sb.append("Старт · ").append(ev.text).append('\n')
                continue
            }
            sb.append("Д").append(t)
            if (head != null) sb.append(" · ").append(SurvivalEngine.headerOf(head))
            sb.append('\n')
            var lastPhase = -1
            for (ev in evsOfDay.sortedWith(compareBy<ExpeditionEvent>({ it.phase }, { it.id }))) {
                if (ev.phase != lastPhase) {
                    sb.append("    [").append(SurvivalEngine.PHASE_RU[ev.phase]).append("]\n")
                    lastPhase = ev.phase
                }
                sb.append("      ").append(ev.text).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    /** Средний шаг за последние 7 закрытых дней — подсказка выбора темпа. */
    suspend fun avgDailySteps(): Int {
        val today = LocalDate.now().toString()
        val days = AppDb.get(context).dao().recentDays(8).filter { it.date != today }
        if (days.isEmpty()) return 0
        var sum = 0L
        for (d in days) sum += d.walkSteps + d.runSteps
        return (sum / days.size).toInt()
    }

    /**
     * Старт экспедиции. Базовая линия шагов = текущее значение дня:
     * шаги, сделанные ДО старта, в мир не идут.
     */
    suspend fun start(season: Int, plannedDays: Int, stepsPerTick: Int): Expedition {
        val seed = java.security.SecureRandom().nextLong()
        val today = LocalDate.now().toString()
        val e = Expedition(
            seed = seed,
            engineVersion = SurvivalEngine.ENGINE_VERSION,
            region = "taiga",
            startSeason = season,
            startOffset = SurvivalEngine.startOffsetFrom(seed),
            plannedDays = plannedDays,
            stepsPerTick = stepsPerTick,
            status = "active",
            createdMs = System.currentTimeMillis(),
            syncDate = today,
            syncDaySteps = todaySteps(today),
        )
        val id = db.dao().insertExpedition(e)
        val roll = SplitMix64.forTick(seed, 0).nextLong()
        db.dao().insertEvents(listOf(ExpeditionEvent(
            expeditionId = id, tick = 0, realTimeMs = e.createdMs,
            category = "milestone",
            text = corpus.render("start." + SurvivalEngine.SEASON_EN[season], roll,
                mapOf("plan" to plannedDays.toString())),
        )))
        return e.copy(id = id)
    }

    /** Догнать мир по накопленным шагам. null — активной экспедиции нет. */
    suspend fun sync(): SyncOutcome? = syncMutex.withLock { syncLocked(voluntary = false) }

    /** Досрочное завершение: сначала честный догон, потом финал. */
    suspend fun finishVoluntary(): SyncOutcome? = syncMutex.withLock { syncLocked(voluntary = true) }

    private suspend fun syncLocked(voluntary: Boolean): SyncOutcome? {
        val e = db.dao().active() ?: return null
        val today = LocalDate.now().toString()

        // --- шаги: замороженные дни + живой счётчик сегодня (read-only) ---
        val coreDao = AppDb.get(context).dao()
        val allDays = if (today > e.syncDate) coreDao.allDays() else emptyList()
        val totals = HashMap<String, Int>()
        for (d in allDays) totals[d.date] = d.walkSteps + d.runSteps
        val between = ArrayList<String>()
        for (d in allDays) if (d.date > e.syncDate && d.date < today) between.add(d.date)
        between.sort()

        // ФАЗА — новая единица прогресса. Лестница шагов считает те же
        // ступени, только вчетверо мельче: четверть дневной нормы = одна фаза.
        // Поэтому запись падает в журнал по ходу дня, а не в его конце.
        val phaseSteps = maxOf(1, e.stepsPerTick / SurvivalEngine.PHASES)
        val res = StepLedger.advance(
            StepLedger.Sync(e.syncDate, e.syncDaySteps, e.stepRemainder),
            today, todaySteps(today),
            { d -> totals[d] ?: 0 }, between, phaseSteps,
        )

        // --- фазы: не дальше плана; лишние шаги при финише сгорают ---
        val maxPhases = e.plannedDays * SurvivalEngine.PHASES
        val gained = minOf(res.newTicks, maxPhases - e.phasesDone).coerceAtLeast(0)
        val newPhasesDone = e.phasesDone + gained
        val newTicksDone = newPhasesDone / SurvivalEngine.PHASES
        val mint = newTicksDone - e.ticksDone
        val planReached = newPhasesDone >= maxPhases
        val finishing = planReached || voluntary

        val engine = SurvivalEngine(e.seed, e.startSeason, e.startOffset, e.engineVersion)
        val now = System.currentTimeMillis()
        val fresh = ArrayList<ExpeditionEvent>()
        // v122. Курс дня: прожитый день фиксирован тропой (архив неизменен),
        // будущий — приказ игрока courseHeading; -1 отдаёт день автопилоту.
        // headingSink пишет ФАКТИЧЕСКИЙ курс каждого дня (в т.ч. автопилотный)
        // в новую тропу — переигрыш радара/журнала потом даст ровно этот путь.
        val lastDay = (newPhasesDone + SurvivalEngine.PHASES - 1) / SurvivalEngine.PHASES
        val heads = IntArray(lastDay + 1) { -1 }
        val choices = choiceMap(e.id)
        val summary = engine.run(e.phasesDone, newPhasesDone, {},
            { day -> courseOf(e, day) },
            { day, h -> if (day in 1..lastDay) heads[day] = h },
            { day -> choices[day] ?: -1 }) { ev ->
            val text = renderEvent(ev, e.seed)
            if (text != null) {
                fresh.add(ExpeditionEvent(
                    expeditionId = e.id, tick = ev.tick, realTimeMs = now,
                    category = ev.category,
                    text = text,
                    phase = ev.phase,
                ))
            }
        }
        // v124. ВСТРЕЧА ОСТАНОВИЛА МИР. Прогресс не уходит дальше кануна дня
        // встречи; шаги за неминченные фазы не сгорают, а возвращаются в
        // остаток — решишь, и они двинут мир дальше. Тропа — по прожитое.
        val halted = summary.haltedDay > 0 && !voluntary
        val haltPhases = if (halted)
            ((summary.haltedDay - 1) * SurvivalEngine.PHASES).coerceAtLeast(e.phasesDone)
        else newPhasesDone
        val phasesDone2 = if (halted) minOf(newPhasesDone, haltPhases) else newPhasesDone
        val refundSteps = if (halted) (newPhasesDone - phasesDone2) * phaseSteps else 0
        val ticksDone2 = phasesDone2 / SurvivalEngine.PHASES
        val mint2 = ticksDone2 - e.ticksDone
        val planReached2 = phasesDone2 >= maxPhases
        val finishing2 = planReached2 || voluntary
        val pathDays = if (halted) minOf(lastDay, summary.haltedDay - 1) else lastDay
        val newPath = if (e.engineVersion >= 6)
            buildString { for (d in 1..pathDays) if (heads[d] >= 0) append(heads[d].coerceIn(0, 7)) }
        else e.path

        if (finishing2) {
            val endKey = if (planReached2) "end.success" else "end.voluntary"
            val roll = SplitMix64.forTick(e.seed, ticksDone2 + 1).nextLong()
            fresh.add(ExpeditionEvent(
                expeditionId = e.id, tick = ticksDone2, realTimeMs = now,
                category = "milestone",
                phase = SurvivalEngine.NIGHT,
                text = corpus.render(endKey, roll, mapOf(
                    "days" to ticksDone2.toString(),
                    "daysW" to SurvivalEngine.daysWord(ticksDone2),
                )),
            ))
            if (ticksDone2 > 0) {
                fresh.add(ExpeditionEvent(
                    expeditionId = e.id, tick = ticksDone2, realTimeMs = now,
                    category = "system",
                    phase = SurvivalEngine.NIGHT,
                    text = finalText(summary),
                ))
            }
        }

        val updated = e.copy(
            ticksDone = ticksDone2,
            phasesDone = phasesDone2,
            syncDate = res.sync.date,
            syncDaySteps = res.sync.daySteps,
            stepRemainder = if (finishing2) 0 else res.sync.remainder + refundSteps,
            status = when {
                planReached2 -> "done_success"
                voluntary -> "done_voluntary"
                else -> "active"
            },
            finishedMs = if (finishing2) now else 0L,
            path = newPath,
        )

        // v124. Мир стоит и ждёт решения: наружу уходит встреча с вариантами.
        val pending = if (halted && !finishing2)
            PendingEnc(summary.haltedDay, summary.haltedKind,
                RadarModel.kindRu(summary.haltedKind), summary.haltedText,
                WildModel.optionsFor(summary.haltedKind))
        else null

        // Голос тайги: что нового узнал человек за этот догон. Считаем радар
        // до и после — озвучиваем только НОВОЕ, поэтому сигнал не повторяется.
        val signal = when {
            pending != null -> Signal.CHOICE
            phasesDone2 > e.phasesDone && e.engineVersion >= 2 ->
                classifySignal(recon(e), recon(updated), e.courseHeading)
            else -> Signal.NONE
        }

        // Одна транзакция: журнал и паспорт меняются атомарно. Обрыв корутины
        // до/во время транзакции безопасен: откат целиком, повторный догон
        // детерминированно построит те же события.
        db.withTransaction {
            if (fresh.isNotEmpty()) db.dao().insertEvents(fresh)
            db.dao().updateExpedition(updated)
        }

        return SyncOutcome(e.id, mint2, res.consumedSteps, finishing2, signal, pending)
    }

    /**
     * Рендер события с контекстом дня.
     * - ключа нет в корпусе -> громкая дыра "[ключ]" в журнале;
     * - ключ есть, но ни один вариант не допустим в этот день -> строка
     *   не печатается вовсе. Молчание честнее физической лжи.
     */
    private fun renderEvent(ev: WorldEvent, salt: Long): String? {
        // v124. Живая тайга пишет готовым текстом: встречи, раны, находки.
        if (ev.key == "raw") return ev.params["txt"]
        if (!corpus.has(ev.key)) return "[" + ev.key + "]"
        return corpus.renderOrNull(ev.key, ev.roll, ev.params, ev.ctx, ev.nth, salt)
    }

    private fun finalText(s: ExpeditionSummary): String {
        val sb = StringBuilder()
        sb.append(corpus.render("final.summary", 0L, mapOf(
            "days" to s.days.toString(),
            "daysW" to SurvivalEngine.daysWord(s.days),
            "rain" to s.rainDays.toString(),
            "snow" to s.snowDays.toString(),
            "tmin" to SurvivalEngine.fmtTemp(s.minTemp),
            "tmax" to SurvivalEngine.fmtTemp(s.maxTemp),
        )))
        if (s.firstSnowDay > 0) {
            sb.append(' ')
            sb.append(corpus.render("final.first_snow", 0L,
                mapOf("day" to s.firstSnowDay.toString())))
        }
        if (s.stormDays > 0) {
            sb.append(' ')
            sb.append(corpus.render("final.storms", 0L,
                mapOf("storms" to s.stormDays.toString())))
        }
        if (s.snowCoverDays > 0) {
            sb.append(' ')
            sb.append(corpus.render("final.snow_cover", 0L, mapOf(
                "cover" to s.snowCoverDays.toString(),
                "maxsnow" to s.maxSnowCm.toString(),
            )))
        }
        return sb.toString()
    }

    /** Сегодняшние шаги тем же путём, что главный экран: prefs ядра, read-only. */
    fun todaySteps(today: String): Int {
        val prefs = context.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(StepService.KEY_DAY, "") == today)
            prefs.getInt(StepService.KEY_STEPS, 0) else 0
    }
}
