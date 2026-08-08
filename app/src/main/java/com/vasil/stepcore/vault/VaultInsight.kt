package com.vasil.stepcore.vault

/**
 * Разбор структуры тайника: связность, перекосы, закономерности.
 * Чистый Kotlin, проверяется тестами до сборки.
 *
 * ЧЕГО ЗДЕСЬ НЕТ И ПОЧЕМУ
 * ----------------------
 * Нет «сходства заметок в процентах». Сравнивать мы умеем только три
 * частых слова на страницу — это грубая прикидка, а не мера. Показать её
 * как «94%» значит выдумать точность, которой нет. Поэтому наружу идут
 * только СЧИТАЕМЫЕ величины: сколько заметок в классе, сколько классов
 * делят пару, сколько связей у класса.
 *
 * ПОЧЕМУ ПАТТЕРНЫ ИМЕННО ТАКИЕ
 * ---------------------------
 * Каждый отвечает на вопрос, который человек и так себе задаёт, и каждый
 * доказуем по данным:
 *  - близнецы: два класса почти всегда вместе — может, это один класс;
 *  - одиночки: класс из одной заметки — либо забыт, либо лишний;
 *  - центр: класс, связанный со всеми — вокруг него всё и крутится;
 *  - цепочка: A-B-C-D без прямой связи A-D — неочевидный путь между темами.
 */
object VaultInsight {

    /** Итог по тайнику одной строкой чисел. */
    class Summary(
        val notes: Int,
        val classes: Int,
        val links: Int,
        val classified: Int,      // заметок хотя бы с одним классом
        val lonely: Int,          // заметок вообще без классов
        val biggestClass: String?,
        val biggestClassCount: Int,
        val avgClassesPerNote: Float,
    )

    /** Найденная закономерность: что и почему. */
    class Pattern(val kind: Kind, val title: String, val detail: String, val weight: Int) {
        enum class Kind { TWINS, ORPHAN, CENTER, CHAIN, UNTAGGED }
    }

    /**
     * @param noteTags классы каждой заметки (уже приведённые к нижнему регистру).
     */
    fun summarize(noteTags: List<List<String>>,
                  counts: Map<String, Int>,
                  together: Map<Pair<String, String>, Int>): Summary {
        val classified = noteTags.count { it.isNotEmpty() }
        val biggest = counts.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
        val totalTags = noteTags.sumOf { it.size }
        return Summary(
            notes = noteTags.size,
            classes = counts.size,
            links = together.size,
            classified = classified,
            lonely = noteTags.size - classified,
            biggestClass = biggest?.key,
            biggestClassCount = biggest?.value ?: 0,
            avgClassesPerNote = if (noteTags.isEmpty()) 0f
                                else totalTags.toFloat() / noteTags.size
        )
    }

    /**
     * Закономерности, отсортированные по весу: сначала то, что заметнее.
     *
     * @param minTwin доля совместных появлений, с которой пара считается
     *   близнецами. 0.8 значит «вместе в четырёх случаях из пяти».
     */
    fun patterns(counts: Map<String, Int>,
                 together: Map<Pair<String, String>, Int>,
                 untagged: Int,
                 minTwin: Float = 0.8f): List<Pattern> {
        val out = ArrayList<Pattern>()

        // Близнецы: пара, которая почти не встречается порознь.
        for ((pair, w) in together) {
            val a = counts[pair.first] ?: continue
            val b = counts[pair.second] ?: continue
            val smaller = minOf(a, b)
            if (smaller < 2) continue
            val share = w.toFloat() / smaller
            if (share >= minTwin) {
                out.add(Pattern(
                    Pattern.Kind.TWINS,
                    pair.first + " ↔ " + pair.second,
                    "вместе в " + w + " заметках из " + smaller +
                        " — возможно, это один класс",
                    (share * 100).toInt() + w
                ))
            }
        }

        // Центр: класс с наибольшим числом РАЗНЫХ соседей.
        val degree = HashMap<String, Int>()
        for ((pair, _) in together) {
            degree[pair.first] = (degree[pair.first] ?: 0) + 1
            degree[pair.second] = (degree[pair.second] ?: 0) + 1
        }
        val center = degree.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
        if (center != null && center.value >= 2) {
            out.add(Pattern(
                Pattern.Kind.CENTER,
                center.key,
                "связан с " + center.value + " классами — вокруг него держится структура",
                60 + center.value
            ))
        }

        // Одиночки: класс из одной заметки.
        val orphans = counts.filter { it.value == 1 }.keys.sorted()
        if (orphans.isNotEmpty()) {
            out.add(Pattern(
                Pattern.Kind.ORPHAN,
                "Классы из одной заметки: " + orphans.size,
                orphans.take(6).joinToString(", ") +
                    (if (orphans.size > 6) " и ещё " + (orphans.size - 6) else ""),
                30 + orphans.size
            ))
        }

        // Цепочка: путь A-B-C-D, где концы напрямую не связаны.
        val chain = longestChain(together.keys)
        if (chain.size >= 3) {
            out.add(Pattern(
                Pattern.Kind.CHAIN,
                chain.joinToString(" → "),
                "неочевидный путь между темами: концы напрямую не связаны",
                40 + chain.size
            ))
        }

        if (untagged > 0) {
            out.add(Pattern(
                Pattern.Kind.UNTAGGED,
                "Без классов: " + untagged,
                "эти заметки не видны в корнях и не попадут в отбор",
                20 + untagged
            ))
        }

        return out.sortedWith(compareByDescending<Pattern> { it.weight }.thenBy { it.title })
    }

    /**
     * Самая длинная простая цепочка классов.
     *
     * Полный перебор с отсечением: классов у человека десятки, не тысячи,
     * и обход дешевле любой эвристики. Ограничение на длину защищает от
     * вырожденных случаев вроде полного графа.
     */
    fun longestChain(links: Set<Pair<String, String>>, maxLen: Int = 6): List<String> {
        if (links.isEmpty()) return emptyList()
        val near = HashMap<String, MutableSet<String>>()
        for ((a, b) in links) {
            near.getOrPut(a) { sortedSetOf() }.add(b)
            near.getOrPut(b) { sortedSetOf() }.add(a)
        }
        var best = emptyList<String>()

        fun walk(path: MutableList<String>) {
            if (path.size > best.size) {
                val ends = path.first() to path.last()
                // Концы НЕ должны быть связаны напрямую: иначе это не
                // цепочка, а просто кусок плотного клубка.
                if (path.size < 3 || near[ends.first]?.contains(ends.second) != true) {
                    best = ArrayList(path)
                }
            }
            if (path.size >= maxLen) return
            for (n in near[path.last()].orEmpty()) {
                if (n in path) continue
                path.add(n)
                walk(path)
                path.removeAt(path.size - 1)
            }
        }

        for (start in near.keys.sorted()) walk(mutableListOf(start))
        return best
    }
}
