package com.vasil.stepcore.survival.engine

/**
 * Кодек бэкапа Survival Mode. ЧИСТЫЙ Kotlin: сериализация — сборкой строки
 * (как в ядре), решения слияния — функцией без побочных эффектов. Благодаря
 * этому и формат, и семантика слияния проверяются в песочнице до устройства.
 *
 * Правила слияния — те же, что у ядра (V11.16): импорт только добавляет,
 * локальные данные всегда главнее файла.
 * - Отпечаток мира — seed (64-битный случайный: совпадение двух разных
 *   миров исключено практически). Экспедиция с уже известным seed
 *   пропускается целиком вместе с событиями.
 * - Активная экспедиция из файла при живой локальной активной пропускается
 *   с честным отчётом: инвариант «одна активная» дороже полноты импорта,
 *   а выдумывать файлу статус завершения нельзя.
 * - id при вставке переназначаются базой (нумерация — свойство устройства,
 *   не мира); события следуют за новым id.
 */
object BackupCodec {

    /** Паспорт экспедиции в бэкапе — зеркало entity без привязки к Room. */
    data class ExpBackup(
        val seed: Long, val engineVersion: Int, val region: String,
        val startSeason: Int, val startOffset: Int, val plannedDays: Int,
        val stepsPerTick: Int, val status: String, val createdMs: Long,
        val finishedMs: Long, val ticksDone: Int, val syncDate: String,
        val syncDaySteps: Int, val stepRemainder: Int,
        val events: List<EvBackup>,
        val path: String = "", val courseHeading: Int = -1,
        val choices: List<Pair<Int, Int>> = emptyList(), // день -> выбор
    )

    data class EvBackup(
        val tick: Int, val realTimeMs: Long,
        val category: String, val text: String,
    )

    /**
     * Экранирование СТРОЖЕ ядра: помимо кавычек/бэкслэша/переноса, любой
     * управляющий символ (< 0x20, включая таб) уходит в \\uXXXX. Строгие
     * парсеры (не только снисходительный org.json) обязаны принимать бэкап.
     */
    fun esc(t: String): String {
        val sb = StringBuilder(t.length + 8)
        for (c in t) when {
            c == '\\' -> sb.append("\\\\")
            c == '"' -> sb.append("\\\"")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> { /* как в ядре: выбрасываем */ }
            c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Фрагмент JSON вида: "expeditions":[ {...}, ... ]
     * Без внешних скобок объекта — встраивается в бэкап ядра.
     */
    fun exportFragment(exps: List<ExpBackup>): String = buildString {
        append("\"expeditions\":[\n")
        append(exps.joinToString(",\n") { e ->
            buildString {
                append("{\"seed\":").append(e.seed)
                append(",\"engineVersion\":").append(e.engineVersion)
                append(",\"region\":\"").append(esc(e.region)).append('"')
                append(",\"startSeason\":").append(e.startSeason)
                append(",\"startOffset\":").append(e.startOffset)
                append(",\"plannedDays\":").append(e.plannedDays)
                append(",\"stepsPerTick\":").append(e.stepsPerTick)
                append(",\"status\":\"").append(esc(e.status)).append('"')
                append(",\"createdMs\":").append(e.createdMs)
                append(",\"finishedMs\":").append(e.finishedMs)
                append(",\"ticksDone\":").append(e.ticksDone)
                append(",\"syncDate\":\"").append(esc(e.syncDate)).append('"')
                append(",\"syncDaySteps\":").append(e.syncDaySteps)
                append(",\"stepRemainder\":").append(e.stepRemainder)
                append(",\"path\":\"").append(esc(e.path)).append('"')
                append(",\"courseHeading\":").append(e.courseHeading)
                append(",\"choices\":[")
                for ((ci, c) in e.choices.withIndex()) {
                    if (ci > 0) append(',')
                    append("{\"day\":").append(c.first)
                        .append(",\"choice\":").append(c.second).append('}')
                }
                append(']')
                append(",\n\"events\":[")
                append(e.events.joinToString(",\n") { ev ->
                    "{\"tick\":" + ev.tick +
                        ",\"realTimeMs\":" + ev.realTimeMs +
                        ",\"category\":\"" + esc(ev.category) + "\"" +
                        ",\"text\":\"" + esc(ev.text) + "\"}"
                })
                append("]}")
            }
        })
        append("\n]")
    }

    /** Решение по одной экспедиции из файла. */
    sealed class Action {
        data class Insert(val exp: ExpBackup) : Action()
        data class Skip(val exp: ExpBackup, val reason: String) : Action()
    }

    /**
     * План слияния. Обрабатывает файл последовательно, отслеживая
     * появление активной по ходу (файл с двумя активными — повреждён,
     * вторая тоже будет пропущена, инвариант держится).
     */
    fun mergePlan(
        localSeeds: Set<Long>,
        localHasActive: Boolean,
        imported: List<ExpBackup>,
    ): List<Action> {
        var hasActive = localHasActive
        val out = ArrayList<Action>(imported.size)
        for (e in imported) {
            when {
                e.seed in localSeeds ->
                    out.add(Action.Skip(e, "уже есть"))
                e.status == "active" && hasActive ->
                    out.add(Action.Skip(e, "уже есть активная"))
                else -> {
                    if (e.status == "active") hasActive = true
                    out.add(Action.Insert(e))
                }
            }
        }
        return out
    }
}
