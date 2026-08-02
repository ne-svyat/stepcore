package com.vasil.stepcore

/**
 * v300. Почасовой учёт, вынесенный из StepService.
 *
 * ЗАЧЕМ ОТДЕЛЬНО
 * --------------
 * Раньше час копился в шести полях службы (pendKey, pendW, pendR, pendUp,
 * pendDown, pendCadSum, pendCadN) и уходил в базу только при смене часа
 * или при `persistDb`. Любой путь, где эти поля обнулялись или служба
 * перезапускалась, терял час целиком - молча, без единой записи в журнал.
 * Из-за этого Timeline и дистанция оказывались пустыми при живом счёте
 * шагов: они читают таблицу часов, а она не наполнялась.
 *
 * Здесь другой принцип: **запись сразу**. Дельта чипа приходит пачками,
 * несколько раз в минуту - две операции Room на дельту это ничто, а
 * терять больше нечего. В памяти не остаётся состояния, которое можно
 * потерять.
 *
 * Класс не знает ни про Android, ни про службу: он получает готовую
 * дельту и функцию записи. Поэтому его поведение проверяется тестами.
 */
class HourAccumulator(
    private val nowKey: () -> String,
    private val write: suspend (key: String, walk: Int, run: Int,
                                up: Int, down: Int,
                                cadSum: Long, cadN: Int) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    /** Последний записанный ключ - только для отчёта, не для накопления. */
    var lastKey: String = ""
        private set
    var writes: Int = 0
        private set
    var failures: Int = 0
        private set

    /**
     * Одна дельта шагов. Возвращает ключ часа, в который она записана.
     *
     * Ничего не копит: если запись упадёт, потеряется одна дельта, а не
     * весь час, и об этом будет сказано вслух через onError.
     */
    suspend fun add(
        walk: Int, run: Int, up: Int, down: Int, cadSum: Long, cadN: Int
    ): String {
        if (walk == 0 && run == 0 && up == 0 && down == 0 && cadN == 0) return lastKey
        val key = nowKey()
        try {
            write(key, walk, run, up, down, cadSum, cadN)
            writes++
            lastKey = key
        } catch (e: Exception) {
            failures++
            onError("час " + key + ": " + (e.message ?: e.javaClass.simpleName))
        }
        return key
    }

    /** Короткая сводка для диагностики. */
    fun report(): String =
        "часовых записей " + writes + ", сбоев " + failures +
            (if (lastKey.isNotEmpty()) ", последний час " + lastKey else "")
}
