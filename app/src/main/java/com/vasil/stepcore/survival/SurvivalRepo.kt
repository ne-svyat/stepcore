package com.vasil.stepcore.survival

import android.content.Context
import androidx.room.withTransaction
import com.vasil.stepcore.AppDb
import com.vasil.stepcore.StepService
import com.vasil.stepcore.survival.engine.Corpus
import com.vasil.stepcore.survival.engine.ExpeditionSummary
import com.vasil.stepcore.survival.engine.SplitMix64
import com.vasil.stepcore.survival.engine.StepLedger
import com.vasil.stepcore.survival.engine.SurvivalEngine
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
    )

    suspend fun active(): Expedition? = db.dao().active()
    suspend fun byId(id: Long): Expedition? = db.dao().byId(id)
    suspend fun archive(): List<Expedition> = db.dao().archive()
    suspend fun events(id: Long): List<ExpeditionEvent> = db.dao().eventsOf(id)

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

        val res = StepLedger.advance(
            StepLedger.Sync(e.syncDate, e.syncDaySteps, e.stepRemainder),
            today, todaySteps(today),
            { d -> totals[d] ?: 0 }, between, e.stepsPerTick,
        )

        // --- тики: не дальше плана; лишние шаги при финише сгорают ---
        val mint = minOf(res.newTicks, e.plannedDays - e.ticksDone).coerceAtLeast(0)
        val newTicksDone = e.ticksDone + mint
        val planReached = newTicksDone >= e.plannedDays
        val finishing = planReached || voluntary

        val engine = SurvivalEngine(e.seed, e.startSeason, e.startOffset)
        val now = System.currentTimeMillis()
        val fresh = ArrayList<ExpeditionEvent>()
        val summary = engine.run(e.ticksDone, newTicksDone) { ev ->
            fresh.add(ExpeditionEvent(
                expeditionId = e.id, tick = ev.tick, realTimeMs = now,
                category = ev.category,
                text = corpus.render(ev.key, ev.roll, ev.params),
            ))
        }

        if (finishing) {
            val endKey = if (planReached) "end.success" else "end.voluntary"
            val roll = SplitMix64.forTick(e.seed, newTicksDone + 1).nextLong()
            fresh.add(ExpeditionEvent(
                expeditionId = e.id, tick = newTicksDone, realTimeMs = now,
                category = "milestone",
                text = corpus.render(endKey, roll, mapOf(
                    "days" to newTicksDone.toString(),
                    "daysW" to SurvivalEngine.daysWord(newTicksDone),
                )),
            ))
            if (newTicksDone > 0) {
                fresh.add(ExpeditionEvent(
                    expeditionId = e.id, tick = newTicksDone, realTimeMs = now,
                    category = "system",
                    text = finalText(summary),
                ))
            }
        }

        val updated = e.copy(
            ticksDone = newTicksDone,
            syncDate = res.sync.date,
            syncDaySteps = res.sync.daySteps,
            stepRemainder = if (finishing) 0 else res.sync.remainder,
            status = when {
                planReached -> "done_success"
                voluntary -> "done_voluntary"
                else -> "active"
            },
            finishedMs = if (finishing) now else 0L,
        )

        // Одна транзакция: журнал и паспорт меняются атомарно. Обрыв корутины
        // до/во время транзакции безопасен: откат целиком, повторный догон
        // детерминированно построит те же события.
        db.withTransaction {
            if (fresh.isNotEmpty()) db.dao().insertEvents(fresh)
            db.dao().updateExpedition(updated)
        }

        return SyncOutcome(e.id, mint, res.consumedSteps, finishing)
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
        return sb.toString()
    }

    /** Сегодняшние шаги тем же путём, что главный экран: prefs ядра, read-only. */
    fun todaySteps(today: String): Int {
        val prefs = context.getSharedPreferences(StepService.PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(StepService.KEY_DAY, "") == today)
            prefs.getInt(StepService.KEY_STEPS, 0) else 0
    }
}
