package com.vasil.stepcore

/**
 * v312. Выбор реплики без повторов.
 *
 * Правило, заданное человеком: реплики идут по кругу, каждая вычёркивается
 * из списка; когда список опустел - заполняется заново, НО первой в новом
 * круге не может стать та, что прозвучала последней. Иначе на стыке кругов
 * одна и та же фраза сыграет дважды подряд - самый заметный вид повтора.
 *
 * Мешок хранится как перемешанный список индексов. Состояние переживает
 * перезапуск: порядок и позиция пишутся строкой в настройки, поэтому после
 * перезагрузки телефона круг продолжается, а не начинается заново.
 *
 * Класс без Android - его поведение проверено тестами до сборки.
 */
class VoiceBag(private val rnd: (Int) -> Int) {

    /**
     * Следующий индекс для набора из [count] вариантов.
     *
     * [state] - строка вида "3,1,4,0,2|2": порядок круга и сколько уже
     * израсходовано. Возвращает выбранный индекс и НОВОЕ состояние -
     * класс ничего не хранит сам, чтобы не было скрытой памяти.
     */
    fun next(count: Int, state: String): Pair<Int, String> {
        if (count <= 0) return -1 to ""
        if (count == 1) return 0 to "0|1"

        var order = parseOrder(state, count)
        var used = parseUsed(state)

        if (order.isEmpty() || used >= order.size) {
            val last = if (order.isEmpty()) -1 else order.last()
            order = shuffled(count, forbidFirst = last)
            used = 0
        }
        val pick = order[used]
        used++
        return pick to (order.joinToString(",") + "|" + used)
    }

    private fun parseOrder(state: String, count: Int): List<Int> {
        val head = state.substringBefore('|', "")
        if (head.isEmpty()) return emptyList()
        val list = head.split(',').mapNotNull { it.trim().toIntOrNull() }
        // Набор мог измениться (добавили файлы) - тогда круг начинаем заново,
        // иначе новые реплики не прозвучат никогда.
        if (list.size != count || list.toSortedSet().size != count) return emptyList()
        if (list.any { it < 0 || it >= count }) return emptyList()
        return list
    }

    private fun parseUsed(state: String): Int =
        state.substringAfter('|', "").trim().toIntOrNull() ?: 0

    /** Перемешивание с запретом на конкретный первый элемент. */
    private fun shuffled(count: Int, forbidFirst: Int): List<Int> {
        val pool = (0 until count).toMutableList()
        val out = ArrayList<Int>(count)
        while (pool.isNotEmpty()) {
            var i = rnd(pool.size)
            // Первый элемент круга не должен совпасть с последним прошлого.
            if (out.isEmpty() && count > 1 && pool[i] == forbidFirst) {
                i = (i + 1) % pool.size
                // При count == 1 запрет невыполним, но туда мы не попадаем.
            }
            out.add(pool.removeAt(i))
        }
        return out
    }
}
