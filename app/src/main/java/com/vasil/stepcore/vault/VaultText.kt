package com.vasil.stepcore.vault

/**
 * Работа с текстом заметок. Чистый Kotlin: ни Android, ни базы, ни сети.
 * Проверяется тестами до сборки.
 *
 * ПОИСК — ПОДСТРОКА БЕЗ УЧЁТА РЕГИСТРА
 * ------------------------------------
 * Поиск по словам был бы точнее, но "моторный" по запросу "мотор" не нашёлся
 * бы без морфологии, а морфология офлайн — отдельная библиотека и словарь.
 * В личных заметках дешевле показать лишнее, чем спрятать нужное: человек
 * глазами отбросит мусор за секунду, а ненайденную запись он не отбросит
 * никогда, потому что не узнает о ней.
 *
 * Ё И Е СЧИТАЮТСЯ ОДНОЙ БУКВОЙ
 * ----------------------------
 * Половина людей пишет "ещё", половина "еще", один и тот же человек — по
 * настроению. Поиск, различающий их, регулярно не находит собственные
 * записи автора.
 *
 * ИНДЕКСА НА ДИСКЕ НЕТ И НЕ БУДЕТ
 * -------------------------------
 * Открытый поисковый индекс свёл бы шифрование к нулю: по нему читается
 * содержимое без всякого пароля. Поиск расшифровывает страницы на лету.
 */
object VaultText {

    /**
     * Стоп-слова. Без них тремя частыми словами любой страницы будут
     * "и", "в", "не" — то есть подпись вкладки не будет значить ничего.
     *
     * Список ручной и офлайн. Он неполон и таким останется: цель не
     * лингвистическая точность, а чтобы в подписи попадали существительные,
     * а не связки.
     */
    val STOP: Set<String> = setOf(
        "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а",
        "то", "все", "она", "так", "его", "но", "да", "ты", "к", "у", "же",
        "вы", "за", "бы", "по", "только", "ее", "мне", "было", "вот", "от",
        "меня", "еще", "нет", "о", "из", "ему", "теперь", "когда", "даже",
        "ну", "вдруг", "ли", "если", "уже", "или", "ни", "быть", "был",
        "него", "до", "вас", "нибудь", "опять", "уж", "вам", "ведь", "там",
        "потом", "себя", "ничего", "ей", "может", "они", "тут", "где",
        "есть", "надо", "ней", "для", "мы", "тебя", "их", "чем", "была",
        "сам", "чтоб", "без", "будто", "чего", "раз", "тоже", "себе", "под",
        "будет", "ж", "тогда", "кто", "этот", "того", "потому", "этого",
        "какой", "совсем", "ним", "здесь", "этом", "один", "почти", "мой",
        "тем", "чтобы", "нее", "сейчас", "были", "куда", "зачем", "всех",
        "никогда", "можно", "при", "наконец", "два", "об", "другой", "хоть",
        "после", "над", "больше", "тот", "через", "эти", "нас", "про",
        "всего", "них", "какая", "много", "разве", "тр", "эту", "моя",
        "впрочем", "хорошо", "свою", "этой", "перед", "иногда", "лучше",
        "чуть", "том", "нельзя", "такой", "им", "более", "всегда", "конечно",
        "всю", "между", "это", "весь", "нибудь", "который", "которые",
        "the", "and", "for", "you", "not", "but", "with", "this", "that",
        "have", "has", "was", "were", "are", "from", "они", "оно"
    )

    /** Разбор на слова: всё, что не буква и не цифра, — разделитель. */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in normalize(text)) {
            if (c.isLetterOrDigit()) sb.append(c)
            else if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * Самые частые значимые слова страницы — подпись вкладки.
     *
     * Слова короче трёх букв отбрасываются вместе со стоп-словами: "он",
     * "ок", "да" ничего не говорят о содержании. При равной частоте
     * побеждает более длинное слово — оно, как правило, конкретнее.
     */
    fun topWords(text: String, n: Int = 3): List<String> {
        val count = HashMap<String, Int>()
        for (w in words(text)) {
            if (w.length < 3 || w in STOP) continue
            count[w] = (count[w] ?: 0) + 1
        }
        return count.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key.length }
                .thenBy { it.key })
            .take(n)
            .map { it.key }
    }

    /** @return позиция первого совпадения в НОРМАЛИЗОВАННОМ тексте, или -1. */
    fun find(text: String, query: String): Int {
        val q = normalize(query)
        if (q.isEmpty()) return -1
        return normalize(text).indexOf(q)
    }

    fun contains(text: String, query: String): Boolean = find(text, query) >= 0

    /**
     * Кусок текста вокруг совпадения — то, что видно в списке находок.
     *
     * Границы двигаются к ближайшему пробелу, чтобы строка не начиналась с
     * обрубка слова. Переводы строк схлопываются: в одну строку списка
     * многострочный кусок всё равно не поместится.
     */
    fun snippet(text: String, pos: Int, radius: Int = 40): String {
        if (pos < 0 || pos >= text.length) return ""
        var from = maxOf(0, pos - radius)
        var to = minOf(text.length, pos + radius)
        while (from > 0 && !text[from].isWhitespace()) from--
        while (to < text.length && !text[to].isWhitespace()) to++
        val core = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
        return (if (from > 0) "…" else "") + core + (if (to < text.length) "…" else "")
    }

    /** Нижний регистр плюс ё -> е. Одна точка правды для поиска и слов. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) sb.append(if (c == 'ё') 'е' else c)
        return sb.toString()
    }

    // ------------------------------------------------------------------ теги

    /**
     * Разбор строки тегов, введённой человеком.
     *
     * Теги ручные и НЕ смешиваются с частыми словами: частые слова говорят,
     * о чём страница, теги — как ты сам её разложил. Смешать их значит
     * лишить фильтр смысла.
     */
    fun parseTags(raw: String): List<String> =
        raw.split(',', ';', '\n')
            .map { it.trim().trimStart('#') }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
            .distinct()
            .take(MAX_TAGS)

    fun formatTags(tags: List<String>): String = tags.joinToString(", ")

    const val MAX_TAGS = 20

    // -------------------------------------------------- разметка страницы

    /**
     * Метка картинки в тексте. Картинка не лежит внутри строки: в тексте
     * стоит только ссылка, сам файл зашифрован отдельно. Иначе страница
     * с тремя фотографиями весила бы мегабайты и упиралась в предел
     * символов, который задуман для ТЕКСТА.
     */
    const val IMG_OPEN = "[img:"
    private const val IMG_CLOSE = "]"

    fun imageMark(id: String): String = IMG_OPEN + id + IMG_CLOSE

    /** Все картинки, на которые ссылается текст, в порядке появления. */
    fun imageRefs(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val a = text.indexOf(IMG_OPEN, i)
            if (a < 0) break
            val b = text.indexOf(IMG_CLOSE, a + IMG_OPEN.length)
            if (b < 0) break
            val id = text.substring(a + IMG_OPEN.length, b)
            if (id.isNotEmpty() && id.all { it.isLetterOrDigit() }) out.add(id)
            i = b + 1
        }
        return out
    }

    /**
     * Кусок страницы для режима просмотра.
     *
     * Правка идёт по обычному тексту — это сознательно: редактор, который
     * рисует картинки прямо в поле ввода, ломает выделение, копирование и
     * позицию курсора. Правишь текст, смотришь оформленное.
     */
    sealed class Block {
        /** Заголовок. level 1..3, по числу решёток в начале строки. */
        class Head(val level: Int, val text: String) : Block()
        class Para(val text: String) : Block()
        class Img(val id: String) : Block()
    }

    /** Разбор страницы на куски для просмотра. */
    fun blocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()

        fun flush() {
            val t = para.toString().trim()
            if (t.isNotEmpty()) out.add(Block.Para(t))
            para.setLength(0)
        }

        for (line in text.split("\n")) {
            val trimmed = line.trimStart()
            // Картинка на отдельной строке становится отдельным куском.
            // Метка посреди абзаца остаётся текстом: разрывать предложение
            // картинкой человек не просил.
            if (trimmed.startsWith(IMG_OPEN) && trimmed.endsWith(IMG_CLOSE) &&
                imageRefs(trimmed).size == 1) {
                flush()
                out.add(Block.Img(imageRefs(trimmed)[0]))
                continue
            }
            val hashes = trimmed.takeWhile { it == '#' }.length
            if (hashes in 1..3 && trimmed.length > hashes && trimmed[hashes] == ' ') {
                flush()
                out.add(Block.Head(hashes, trimmed.substring(hashes + 1).trim()))
                continue
            }
            if (para.isNotEmpty()) para.append('\n')
            para.append(line)
        }
        flush()
        return out
    }


    // ------------------------------------------------------------- связи

    /**
     * Ссылка на другую заметку: [[Название]].
     *
     * Форма взята у Obsidian сознательно — это единственная запись связи,
     * которую человек набирает не задумываясь, прямо в потоке письма.
     * Меню "добавить связь" в тот же поток не помещается: пока выбираешь
     * из списка, мысль уходит.
     */
    const val LINK_OPEN = "[["
    private const val LINK_CLOSE = "]]"

    fun linkMark(title: String): String = LINK_OPEN + title + LINK_CLOSE

    /** Названия, на которые ссылается текст, в порядке появления. */
    fun linkRefs(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val a = text.indexOf(LINK_OPEN, i)
            if (a < 0) break
            val b = text.indexOf(LINK_CLOSE, a + 2)
            if (b < 0) break
            val name = text.substring(a + 2, b).trim()
            // Перевод строки внутри скобок означает, что закрывающие
            // скобки от другой ссылки: незакрытая не должна проглатывать
            // половину страницы.
            if (name.isNotEmpty() && name.length <= 120 && !name.contains('\n')) {
                out.add(name)
            }
            i = b + 2
        }
        return out.distinct()
    }

    /** Границы ссылок для подсветки: пары (начало, конец) вместе со скобками. */
    fun linkSpans(text: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (true) {
            val a = text.indexOf(LINK_OPEN, i)
            if (a < 0) break
            val b = text.indexOf(LINK_CLOSE, a + 2)
            if (b < 0) break
            val name = text.substring(a + 2, b)
            if (name.isNotEmpty() && !name.contains('\n')) out.add(intArrayOf(a, b + 2))
            i = b + 2
        }
        return out
    }

    /** Совпадает ли название без учёта регистра, ё/е и краевых пробелов. */
    fun sameTitle(a: String, b: String): Boolean =
        normalizeTitle(a) == normalizeTitle(b)

    private fun normalizeTitle(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.trim().lowercase()) sb.append(if (c == 'ё') 'е' else c)
        return sb.toString()
    }

    /**
     * Родство двух страниц по их частым словам.
     *
     * Считается по УЖЕ сохранённым подписям страниц, а не по тексту:
     * значит связи ничего не стоят — ни нового хранилища, ни лишней
     * расшифровки текста. Это и есть та ценность графа, ради которой
     * граф обычно и рисуют, только без графа.
     *
     * @return число общих слов.
     */
    fun kinship(aWords: List<String>, bWords: List<String>): Int {
        if (aWords.isEmpty() || bWords.isEmpty()) return 0
        val set = aWords.map { it.lowercase() }.toHashSet()
        return bWords.count { it.lowercase() in set }
    }

    /** Оглавление страницы — заголовки по порядку. */
    fun outline(text: String): List<Block.Head> =
        blocks(text).filterIsInstance<Block.Head>()
}
