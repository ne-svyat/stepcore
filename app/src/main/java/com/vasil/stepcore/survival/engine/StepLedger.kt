package com.vasil.stepcore.survival.engine

/**
 * Чистая арифметика «реальные шаги -> тики мира».
 *
 * Не знает ни про Room, ни про prefs, ни про Android: принимает уже
 * прочитанные числа. Благодаря этому вся логика догона (включая крайние
 * случаи) проверяется юнит-прогоном в песочнице до установки на устройство.
 *
 * Модель: экспедиция «съедает» шаги монотонно. Точка синхронизации —
 * (дата, съедено шагов этой даты, несожжённый остаток). При догоне:
 * хвост дня последней синхронизации добирается из ЗАМОРОЖЕННОГО итога дня
 * (DayRecord пишется при ролловере), полные дни между — целиком, сегодня —
 * по живому счётчику prefs.
 */
object StepLedger {

    data class Sync(val date: String, val daySteps: Int, val remainder: Int)

    data class Result(
        val newTicks: Int,      // сколько дней мира созрело
        val sync: Sync,         // новая точка синхронизации
        val consumedSteps: Long // сколько реальных шагов пришло с прошлого раза
    )

    /**
     * @param sync           точка прошлой синхронизации
     * @param today          сегодняшняя дата ISO ("2026-07-11")
     * @param todaySteps     шаги сегодня (0, если prefs ещё про другой день)
     * @param closedDayTotal итог закрытого дня по дате (0, если записи нет —
     *                       телефон лежал выключенным, день пуст)
     * @param daysBetween    даты строго между sync.date и today, по которым
     *                       в БД есть записи
     * @param stepsPerTick   темп экспедиции: шагов на день мира
     */
    fun advance(
        sync: Sync,
        today: String,
        todaySteps: Int,
        closedDayTotal: (String) -> Int,
        daysBetween: List<String>,
        stepsPerTick: Int,
    ): Result {
        var s = sync
        var available = 0L

        if (today > s.date) {
            // хвост дня последней синхронизации — из замороженного итога;
            // coerceAtLeast(0) защищает от исчезнувшей записи дня
            available += (closedDayTotal(s.date) - s.daySteps).coerceAtLeast(0).toLong()
            for (d in daysBetween) available += closedDayTotal(d).coerceAtLeast(0).toLong()
            s = Sync(today, 0, s.remainder)
        } else if (today < s.date) {
            // часы ушли назад: не начисляем и не ломаемся, ждём возврата дат
            return Result(0, s, 0)
        }

        // сегодня: если счётчик оказался МЕНЬШЕ базовой линии (сброс prefs,
        // переустановка) — ничего не начисляем и принимаем новую, меньшую
        // базу, иначе шаги после сброса не считались бы никогда
        available += (todaySteps - s.daySteps).coerceAtLeast(0).toLong()

        val pool = s.remainder + available
        val ticks = (pool / stepsPerTick).toInt()
        val rem = (pool % stepsPerTick).toInt()
        return Result(ticks, Sync(today, todaySteps, rem), available)
    }
}
