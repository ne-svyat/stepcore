package com.vasil.stepcore.survival.engine

/**
 * Корпус текстов мира. Формат строки:
 *
 *   ключ [условия] | текст с {плейсхолдерами}
 *
 * Условия необязательны. Если они есть — вариант допустим только в том дне
 * мира, где ВСЕ условия выполнены. Условие: имя, оператор, число.
 * Операторы: >=  <=  !=  =  >  <
 * Пример:  wx.cold_snap [t<=-5] | Мороз окреп: {temp}°. Снег скрипит.
 *
 * Зачем условия. Раньше вариант выбирался броском кубика среди всех строк
 * ключа, и текст мог физически противоречить дню («Мороз окреп: +18°»).
 * Теперь недопустимость выражена в самом корпусе, а не спрятана в коде:
 * добавить строку, которая выстрелит не в том мире, стало невозможно
 * по конструкции, а не по внимательности.
 *
 * Разбор строки с непонятным условием НЕ делает вариант универсальным —
 * вариант выбрасывается и попадает в problems(): молчание честнее лжи.
 *
 * Несколько строк с одним ключом — варианты; выбор делается по roll события
 * (детерминирован тиком). Журнал хранит уже отрендеренный текст, поэтому
 * правки корпуса меняют только будущее — прошлое неизменно.
 *
 * Чистый Kotlin: Android-слой лишь читает файл из assets и передаёт сюда.
 */
class Corpus(raw: String) {

    private data class Cond(val name: String, val op: String, val value: Int)

    private data class Variant(val text: String, val conds: List<Cond>)

    private val map = LinkedHashMap<String, MutableList<Variant>>()
    private val problems = ArrayList<String>()

    /**
     * Что этот ключ выдал в прошлый раз.
     *
     * Одной ротации мало: пул зависит от дня, и на границе (вчера подходило
     * пять вариантов, сегодня четыре) сдвиг мог привести в ту же строку.
     * Память короткая — ровно на одну строку на ключ, — но она закрывает
     * самое заметное: один и тот же текст два дня подряд.
     *
     * Журнал хранит уже отрендеренный текст, поэтому память не обязана
     * переживать перезапуск: прошлое всё равно неизменно.
     */
    private val last = HashMap<String, String>()

    init {
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) return@forEach
            val bar = l.indexOf('|')
            if (bar <= 0) return@forEach
            var head = l.substring(0, bar).trim()
            val text = l.substring(bar + 1).trim()

            var conds: List<Cond> = emptyList()
            val lb = head.indexOf('[')
            if (lb >= 0) {
                val rb = head.indexOf(']', lb)
                if (rb < lb) { problems.add("незакрытая скобка: " + l); return@forEach }
                val parts = head.substring(lb + 1, rb).split(',')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val parsed = ArrayList<Cond>()
                for (p in parts) {
                    val c = parseCond(p)
                    if (c == null) { problems.add("непонятное условие '" + p + "' в: " + l); return@forEach }
                    parsed.add(c)
                }
                conds = parsed
                head = head.substring(0, lb).trim()
            }

            if (head.isNotEmpty() && text.isNotEmpty()) {
                map.getOrPut(head) { mutableListOf() }.add(Variant(text, conds))
            }
        }
    }

    /**
     * Рендер с учётом контекста дня. Возвращает null, если ключа нет
     * или ни один вариант не подходит к этому дню — вызывающий слой решает,
     * пропустить строку (тихий день) или показать дыру.
     */
    fun renderOrNull(
        key: String,
        roll: Long,
        params: Map<String, String> = emptyMap(),
        ctx: Map<String, Int> = emptyMap(),
        nth: Int = 0,
        salt: Long = 0L,
    ): String? {
        val all = map[key] ?: return null
        val pool = all.filter { v -> v.conds.all { c -> match(c, ctx) } }
        if (pool.isEmpty()) return null

        // АНТИ-ПОВТОР. Раньше здесь стояло:
        //     base = roll % pool.size ;  idx = (base + nth) % pool.size
        // и в комментарии обещалась ротация. Ротации не было: roll — свежий
        // бросок кубика на КАЖДОЕ событие, он затирал сдвиг nth начисто.
        // Вариант выбирался случайно с возвращением — отсюда «Росомаха.
        // След косой...» два дня подряд и «Ходил за водой к полынье» пять
        // раз за экспедицию (реальный журнал, 60 дней).
        //
        // Теперь точка отсчёта СТАБИЛЬНА: она зависит от сида экспедиции и
        // от самого ключа, но не от дня. И потому nth — счётчик срабатываний
        // этого ключа — честно прокручивает пул: второй раз ключ выдаст
        // соседний вариант, третий — следующий. Один и тот же текст подряд
        // невозможен по конструкции, пока в пуле больше одной строки.
        val h = (key.hashCode().toLong() * -0x61c8864680b583ebL) xor salt
        val base = (((h % pool.size) + pool.size) % pool.size).toInt()
        var idx = (base + (nth % pool.size)) % pool.size
        if (pool.size > 1 && pool[idx].text == last[key]) idx = (idx + 1) % pool.size
        var t = pool[idx].text
        last[key] = t
        for ((k, v) in params) t = t.replace("{" + k + "}", v)
        return t
    }

    /**
     * Рендер без контекста (вехи, финал — там условий нет).
     * Отсутствующий ключ возвращает "[ключ]": дыра в корпусе видна сразу,
     * а не молчит.
     */
    fun render(key: String, roll: Long, params: Map<String, String> = emptyMap()): String =
        renderOrNull(key, roll, params, emptyMap()) ?: ("[" + key + "]")

    fun has(key: String): Boolean = map.containsKey(key)

    fun keys(): Set<String> = map.keys

    /** Строки корпуса, отвергнутые при разборе. Пусто — корпус здоров. */
    fun problems(): List<String> = problems

    /** Сколько вариантов у ключа (для проверок покрытия в песочнице). */
    fun variantCount(key: String): Int = map[key]?.size ?: 0

    /**
     * Сколько вариантов ДОПУСТИМО в этот конкретный день.
     *
     * Ключ может иметь тридцать строк, но в глухую зиму со льдом и метелью
     * пройти отбор могут четыре — и кубик будет крутиться по ним всю
     * экспедицию. Именно так «Ходил за водой к полынье» попало в журнал
     * пять раз. Тесноту пула теперь видно цифрой, а не на глаз.
     */
    fun poolSize(key: String, ctx: Map<String, Int>): Int =
        map[key]?.count { v -> v.conds.all { c -> match(c, ctx) } } ?: 0

    private fun match(c: Cond, ctx: Map<String, Int>): Boolean {
        val v = ctx[c.name] ?: return false // переменной нет — вариант не подходит
        return when (c.op) {
            ">=" -> v >= c.value
            "<=" -> v <= c.value
            ">" -> v > c.value
            "<" -> v < c.value
            "=" -> v == c.value
            "!=" -> v != c.value
            else -> false
        }
    }

    companion object {
        private val OPS = arrayOf(">=", "<=", "!=", "=", ">", "<")

        /** Словесные значения сезона — чтобы корпус читался человеком. */
        private val WORDS = mapOf(
            "winter" to 0, "spring" to 1, "summer" to 2, "autumn" to 3,
            "yes" to 1, "no" to 0,
        )

        private fun parseCond(s: String): Cond? {
            for (op in OPS) {
                val i = s.indexOf(op)
                if (i > 0) {
                    val name = s.substring(0, i).trim()
                    val rawV = s.substring(i + op.length).trim()
                    if (name.isEmpty() || rawV.isEmpty()) return null
                    val v = WORDS[rawV] ?: rawV.toIntOrNull() ?: return null
                    return Cond(name, op, v)
                }
            }
            return null
        }
    }
}
