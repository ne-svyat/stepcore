package com.vasil.stepcore.survival

import android.content.Context
import androidx.room.withTransaction
import com.vasil.stepcore.survival.engine.BackupCodec

/**
 * Мост бэкапа между ядром и Survival Mode. Ядро вызывает две функции и
 * ничего не знает об устройстве режима; вся семантика — в чистом
 * BackupCodec, здесь только чтение/запись БД и разбор JSON.
 *
 * Атомарность: survival.db — отдельный файл, у него СВОЯ транзакция.
 * Если ядро импортировалось, а секция экспедиций упала — отчёт скажет
 * об этом честно, база ядра при этом уже корректна (домены независимы).
 */
object SurvivalBackup {

    /** Фрагмент "expeditions":[...] для вклейки в JSON бэкапа ядра. */
    suspend fun exportFragment(context: Context): String {
        val dao = SurvivalDb.get(context).dao()
        val all = ArrayList<BackupCodec.ExpBackup>()
        val exps = dao.archive() + listOfNotNull(dao.active())
        for (e in exps.sortedBy { it.id }) {
            val evs = dao.eventsOf(e.id).sortedBy { it.id } // eventsOf DESC — в бэкап хронологически
            all.add(BackupCodec.ExpBackup(
                seed = e.seed, engineVersion = e.engineVersion, region = e.region,
                startSeason = e.startSeason, startOffset = e.startOffset,
                plannedDays = e.plannedDays, stepsPerTick = e.stepsPerTick,
                status = e.status, createdMs = e.createdMs, finishedMs = e.finishedMs,
                ticksDone = e.ticksDone, syncDate = e.syncDate,
                syncDaySteps = e.syncDaySteps, stepRemainder = e.stepRemainder,
                events = evs.map { BackupCodec.EvBackup(it.tick, it.realTimeMs, it.category, it.text) },
            ))
        }
        return BackupCodec.exportFragment(all)
    }

    /**
     * Импорт секции экспедиций из корня бэкапа. Возвращает строку отчёта
     * или "" — если секции нет (бэкап schema < 3): отсутствие данных
     * не является командой на удаление, локальные экспедиции не трогаются.
     */
    suspend fun importFromBackup(context: Context, root: org.json.JSONObject): String {
        val arr = root.optJSONArray("expeditions") ?: return ""
        val imported = ArrayList<BackupCodec.ExpBackup>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val evArr = o.optJSONArray("events") ?: org.json.JSONArray()
            val evs = ArrayList<BackupCodec.EvBackup>(evArr.length())
            for (j in 0 until evArr.length()) {
                val ev = evArr.getJSONObject(j)
                evs.add(BackupCodec.EvBackup(
                    tick = ev.getInt("tick"),
                    realTimeMs = ev.optLong("realTimeMs", 0L),
                    category = ev.optString("category", "weather"),
                    text = ev.getString("text"),
                ))
            }
            imported.add(BackupCodec.ExpBackup(
                seed = o.getLong("seed"),
                engineVersion = o.optInt("engineVersion", 1),
                region = o.optString("region", "taiga"),
                startSeason = o.getInt("startSeason"),
                startOffset = o.getInt("startOffset"),
                plannedDays = o.getInt("plannedDays"),
                stepsPerTick = o.getInt("stepsPerTick"),
                status = o.optString("status", "done_voluntary"),
                createdMs = o.optLong("createdMs", 0L),
                finishedMs = o.optLong("finishedMs", 0L),
                ticksDone = o.optInt("ticksDone", 0),
                syncDate = o.optString("syncDate", "1970-01-01"),
                syncDaySteps = o.optInt("syncDaySteps", 0),
                stepRemainder = o.optInt("stepRemainder", 0),
                events = evs,
            ))
        }

        val db = SurvivalDb.get(context)
        val dao = db.dao()
        val localSeeds = (dao.archive() + listOfNotNull(dao.active()))
            .map { it.seed }.toHashSet()
        val plan = BackupCodec.mergePlan(localSeeds, dao.active() != null, imported)

        var added = 0; var dup = 0; var activeSkipped = false
        db.withTransaction {
            for (a in plan) when (a) {
                is BackupCodec.Action.Insert -> {
                    val e = a.exp
                    val newId = dao.insertExpedition(Expedition(
                        id = 0, // нумерация — свойство устройства: id выдаёт база
                        seed = e.seed, engineVersion = e.engineVersion, region = e.region,
                        startSeason = e.startSeason, startOffset = e.startOffset,
                        plannedDays = e.plannedDays, stepsPerTick = e.stepsPerTick,
                        status = e.status, createdMs = e.createdMs, finishedMs = e.finishedMs,
                        ticksDone = e.ticksDone, syncDate = e.syncDate,
                        syncDaySteps = e.syncDaySteps, stepRemainder = e.stepRemainder,
                    ))
                    dao.insertEvents(e.events.map { ev ->
                        ExpeditionEvent(
                            expeditionId = newId, tick = ev.tick,
                            realTimeMs = ev.realTimeMs,
                            category = ev.category, text = ev.text,
                        )
                    })
                    added++
                }
                is BackupCodec.Action.Skip -> {
                    dup++
                    if (a.reason == "уже есть активная") activeSkipped = true
                }
            }
        }

        return buildString {
            append("Экспедиции: +").append(added).append(", пропущено ").append(dup)
            if (activeSkipped) {
                append(" (активная из файла пропущена: уже есть активная)")
            }
        }
    }
}
