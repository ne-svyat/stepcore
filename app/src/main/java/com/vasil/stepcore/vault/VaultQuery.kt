package com.vasil.stepcore.vault

/**
 * Разбор поискового запроса и проверка попадания. Чистый Kotlin,
 * проверяется тестами до сборки.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ РАЗБОР
 * ---------------------
 * Поиск подстрокой не находил «машина была красная» по запросу «красная
 * машина»: слова обязаны были стоять подряд и в том же порядке. Здесь
 * запрос - это НАБОР слов, и порядок не важен.
 *
 * ОДНО ПРАВИЛО НА ОБА ЯЗЫКА
 * -------------------------
 * Язык не определяется вовсе. И у русского, и у английского изменяемая
 * часть слова стоит в конце: «машина/машины/машиной», «run/runs/running».
 * Совпадение по НАЧАЛУ слова покрывает оба, не притворяясь языковой
 * моделью. Это не морфология, и называть это морфологией нельзя.
 */
object VaultQuery {

    /** Как сопоставлять слово запроса со словом текста. */
    const val WORD_PREFIX = 0    // по началу слова: машин -> машина, машины
    const val WORD_EXACT = 1     // слово целиком
    const val WORD_INSIDE = 2    // где угодно внутри слова: асть -> часть

    /** Где искать. */
    const val IN_ALL = 0
    const val IN_TITLE = 1
    const val IN_TAGS = 2

    class Options(
        val word: Int = WORD_PREFIX,
        /** true - достаточно одного слова из запроса, а не всех. */
        val anyWord: Boolean = false,
        val where: Int = IN_ALL,
    )

    /**
     * @param words слова запроса, приведённые к общему виду
     * @param phrases то, что стояло в кавычках: ищется как есть, подряд
     */
    class Parsed(val words: List<String>, val phrases: List<String>) {
        val isEmpty: Boolean get() = words.isEmpty() && phrases.isEmpty()
    }

    /**
     * Похожие на вид буквы латиницы и кириллицы.
     *
     * В смешанном тексте «Cалат» через латинскую C не находился никак, и
     * человек видел пустой список, будучи уверенным, что заметка есть.
     * Свести к одному виду дешевле, чем объяснять.
     */
    private const val LOOKALIKE_LAT = "acepxyokbhmt"
    private const val LOOKALIKE_CYR = "асерхуоквнмт"

    /** Единый вид: нижний регистр, ё=е, похожие латинские буквы к русским. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            val i = LOOKALIKE_LAT.indexOf(c)
            sb.append(
                when {
                    c == 'ё' -> 'е'
                    i >= 0 -> LOOKALIKE_CYR[i]
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Разбить строку на слова по любым не-буквенным знакам. */
    fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in normalize(s)) {
            if (isWordChar(c)) sb.append(c)
            else if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * Разобрать запрос: кавычки выделяют точную фразу, остальное - слова.
     *
     * Незакрытая кавычка не считается ошибкой: человек ещё печатает, и
     * ронять поиск на середине ввода нельзя. Хвост после неё читается как
     * фраза до конца строки.
     */
    fun parse(query: String): Parsed {
        val phrases = ArrayList<String>()
        val rest = StringBuilder()
        var i = 0
        while (i < query.length) {
            val c = query[i]
            if (c == '"' || c == '\u00AB' || c == '\u00BB') {
                val close = query.indexOfFirst(i + 1) { it == '"' || it == '\u00BB' }
                val end = if (close < 0) query.length else close
                val ph = normalize(query.substring(i + 1, end)).trim()
                if (ph.isNotEmpty()) phrases.add(ph)
                i = if (close < 0) query.length else close + 1
            } else {
                rest.append(c)
                i++
            }
        }
        return Parsed(words(rest.toString()).distinct(), phrases)
    }

    private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
        for (j in from until length) if (pred(this[j])) return j
        return -1
    }

    private fun wordHits(target: String, needle: String, mode: Int): Boolean = when (mode) {
        WORD_EXACT -> target == needle
        WORD_INSIDE -> target.contains(needle)
        else -> target.startsWith(needle)
    }

    /**
     * Подходит ли текст под запрос.
     *
     * Фразы обязательны ВСЕГДА, даже в режиме «любое из слов»: кавычки -
     * это прямое указание, и ослаблять его нельзя.
     */
    fun matches(text: String, q: Parsed, o: Options): Boolean {
        if (q.isEmpty) return false
        val norm = normalize(text)
        for (ph in q.phrases) if (!norm.contains(ph)) return false
        if (q.words.isEmpty()) return true
        val ws = words(text)
        var hit = 0
        for (need in q.words) {
            if (ws.any { wordHits(it, need, o.word) }) hit++
            else if (!o.anyWord) return false
        }
        return if (o.anyWord) hit > 0 else hit == q.words.size
    }

    /** Сколько слов запроса нашлось: по этому числу список и упорядочен. */
    fun score(text: String, q: Parsed, o: Options): Int {
        val ws = words(text)
        var hit = 0
        for (need in q.words) if (ws.any { wordHits(it, need, o.word) }) hit++
        val norm = normalize(text)
        for (ph in q.phrases) if (norm.contains(ph)) hit++
        return hit
    }

    /**
     * Куда встать подсветкой: позиция первого совпадения.
     * @return -1, если в этом тексте не нашлось
     */
    fun firstHit(text: String, q: Parsed, o: Options): Int {
        val norm = normalize(text)
        var best = -1
        for (ph in q.phrases) {
            val at = norm.indexOf(ph)
            if (at >= 0 && (best < 0 || at < best)) best = at
        }
        for (need in q.words) {
            var i = 0
            while (i < norm.length) {
                if (!isWordChar(norm[i])) { i++; continue }
                var j = i
                while (j < norm.length && isWordChar(norm[j])) j++
                val w = norm.substring(i, j)
                if (wordHits(w, need, o.word)) {
                    val at = if (o.word == WORD_INSIDE) i + w.indexOf(need) else i
                    if (best < 0 || at < best) best = at
                    break
                }
                i = j
            }
        }
        return best
    }

    /** Чем именно совпало: от точного к самому широкому. */
    const val MARK_PHRASE = 0
    const val MARK_EXACT = 1
    const val MARK_PREFIX = 2
    const val MARK_INSIDE = 3

    /**
     * Отрезки для подсветки: начало, конец и ЧЕМ совпало.
     *
     * Вид совпадения возвращается вместе с отрезком, потому что на экране
     * они красятся по-разному: человек должен видеть, точное это попадание
     * или притянутое расширением поиска. Без этого самоподбор выглядит
     * так, будто нашлось ровно то, что просили.
     *
     * Отрезки идут по порядку и без наложений: наложенные красились бы
     * дважды, и тон получался бы разный на глаз.
     */
    fun spans(text: String, q: Parsed, o: Options): List<IntArray> {
        val norm = normalize(text)
        val raw = ArrayList<IntArray>()
        for (ph in q.phrases) {
            var at = norm.indexOf(ph)
            while (at >= 0) {
                raw.add(intArrayOf(at, at + ph.length, MARK_PHRASE))
                at = norm.indexOf(ph, at + 1)
            }
        }
        var i = 0
        while (i < norm.length) {
            if (!isWordChar(norm[i])) { i++; continue }
            var j = i
            while (j < norm.length && isWordChar(norm[j])) j++
            val w = norm.substring(i, j)
            for (need in q.words) {
                if (!wordHits(w, need, o.word)) continue
                val start = if (o.word == WORD_INSIDE) i + w.indexOf(need) else i
                val end = if (o.word == WORD_EXACT) j else minOf(j, start + need.length)
                val kind = when {
                    w == need -> MARK_EXACT
                    w.startsWith(need) -> MARK_PREFIX
                    else -> MARK_INSIDE
                }
                raw.add(intArrayOf(start, maxOf(end, start + 1), kind))
            }
            i = j
        }
        if (raw.isEmpty()) return raw
        raw.sortBy { it[0] }
        val out = ArrayList<IntArray>()
        for (r in raw) {
            val last = out.lastOrNull()
            if (last != null && r[0] <= last[1]) {
                last[1] = maxOf(last[1], r[1])
                // При слиянии остаётся САМОЕ точное: иначе наложенные
                // отрезки красились бы по последнему, а не по лучшему.
                last[2] = minOf(last[2], r[2])
            } else out.add(intArrayOf(r[0], r[1], r[2]))
        }
        return out
    }

    /**
     * Объяснение запроса словами человека.
     *
     * Главное в шторке: человек видит, КАК его поняли, и сразу замечает,
     * если понято не так. Подписи к переключателям этого не дают - они
     * говорят, что кнопка делает, а не что происходит прямо сейчас.
     */
    fun explain(q: Parsed, o: Options): String {
        if (q.isEmpty) return "Впиши, что искать"
        val parts = ArrayList<String>()
        if (q.phrases.isNotEmpty()) {
            parts.add("точное сочетание " + q.phrases.joinToString(" и ") { "«" + it + "»" })
        }
        if (q.words.isNotEmpty()) {
            val how = when (o.word) {
                WORD_EXACT -> "слова целиком"
                WORD_INSIDE -> "слова, где внутри есть"
                else -> "слова, начинающиеся на"
            }
            parts.add(how + " " + q.words.joinToString(" и ") { "«" + it + "»" })
        }
        val join = if (o.anyWord && q.words.size > 1) "Ищу, где есть ХОТЯ БЫ ОДНО: "
                   else "Ищу, где есть "
        val where = when (o.where) {
            IN_TITLE -> " Только в названиях."
            IN_TAGS -> " Только в тегах."
            else -> ""
        }
        return join + parts.joinToString("; ") + "." + where
    }
}
