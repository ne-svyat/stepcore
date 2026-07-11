package com.vasil.stepcore.survival.engine

/**
 * Корпус текстов мира. Формат файла:
 *
 *   # комментарий
 *   ключ | текст с {плейсхолдерами}
 *
 * Несколько строк с одним ключом — варианты; выбор варианта делается по
 * roll события (детерминирован тиком). Журнал хранит уже отрендеренный
 * текст, поэтому правки корпуса меняют только будущее — прошлое
 * неизменно, как и везде в StepCore.
 *
 * Чистый Kotlin: Android-слой лишь читает файл из assets и передаёт сюда.
 */
class Corpus(raw: String) {

    private val map = LinkedHashMap<String, MutableList<String>>()

    init {
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) return@forEach
            val i = l.indexOf('|')
            if (i <= 0) return@forEach
            val key = l.substring(0, i).trim()
            val text = l.substring(i + 1).trim()
            if (key.isNotEmpty() && text.isNotEmpty()) {
                map.getOrPut(key) { mutableListOf() }.add(text)
            }
        }
    }

    /**
     * Рендер: выбор варианта по roll + подстановка параметров.
     * Отсутствующий ключ возвращает "[ключ]" — дыра в корпусе видна
     * в журнале сразу, а не молчит (принцип «не показывать метрику,
     * которая врёт» распространён и на тексты).
     */
    fun render(key: String, roll: Long, params: Map<String, String> = emptyMap()): String {
        val variants = map[key] ?: return "[" + key + "]"
        val idx = (((roll % variants.size) + variants.size) % variants.size).toInt()
        var t = variants[idx]
        for ((k, v) in params) t = t.replace("{" + k + "}", v)
        return t
    }

    fun has(key: String): Boolean = map.containsKey(key)

    fun keys(): Set<String> = map.keys
}
