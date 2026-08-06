package com.vasil.stepcore.vault

import kotlin.math.abs

/**
 * Оттенки классов. Чистый Kotlin, проверяется тестами.
 *
 * ЗАМЫСЕЛ
 * -------
 * Класс задаёт человек словом, тон выдаёт система. Никаких меню "выберите
 * тип" и никакой палитры: выбор цвета руками через двадцать классов
 * превращается в мучение, а через сто — в кашу, потому что человек не
 * держит в голове, какой оттенок уже занят.
 *
 * ГЛАВНОЕ: ЦВЕТ — ЭТО ИНФОРМАЦИЯ, А НЕ РАСКРАСКА
 * ----------------------------------------------
 * Классы, часто встречающиеся на одних заметках, СБЛИЖАЮТСЯ по тону.
 * Тогда похожий оттенок означает "эти темы рядом", и глаз читает
 * структуру раньше, чем человек прочтёт подписи. Случайные цвета такого
 * не дают вообще.
 *
 * НО НЕ СЛИВАЮТСЯ
 * ---------------
 * Держится минимальный зазор. Два класса, всегда идущие вместе, — всё
 * равно два класса, и спутать их нельзя.
 *
 * ДЕТЕРМИНИЗМ
 * -----------
 * Одни и те же классы и связи дают один и тот же результат всегда. Цвет,
 * прыгающий между запусками, хуже отсутствия цвета: человек привыкает к
 * "зелёное — это работа", и обманывать эту привычку нельзя.
 */
object VaultHues {

    /** Минимальный зазор между тонами, градусов. */
    const val MIN_GAP = 14f

    /** Насколько сильно связанные классы притягиваются за один проход. */
    private const val PULL = 0.22f
    private const val PUSH = 0.55f
    private const val PASSES = 240
    /**
     * Последние проходы — только разведение.
     *
     * Сначала притяжение и отталкивание боролись одновременно, и на сильных
     * связях притяжение побеждало: классы сходились до 8° при обещанных 14.
     * Обещание "не сливаются" важнее силы сближения, поэтому финал за
     * разведением: сближение уже сделало своё дело, и отменить его
     * несколько проходов не могут.
     */
    private const val SEPARATE_TAIL = 90

    /**
     * Стартовый тон имени: устойчивый разброс без всякой истории.
     *
     * Своя хэш-функция, а не String.hashCode: тот не обещает
     * постоянства между версиями платформы, а тон обязан быть вечным.
     */
    fun baseHue(name: String): Float {
        var h = 2166136261u
        for (c in name.trim().lowercase()) {
            h = h xor c.code.toUInt()
            h *= 16777619u
        }
        return ((h % 3600u).toFloat()) / 10f
    }

    /** Разница тонов по кругу: 350 и 10 отличаются на 20, а не на 340. */
    fun distance(a: Float, b: Float): Float {
        val d = abs(norm(a) - norm(b))
        return if (d > 180f) 360f - d else d
    }

    private fun norm(h: Float): Float {
        var x = h % 360f
        if (x < 0f) x += 360f
        return x
    }

    /**
     * Разложить классы по кругу тонов.
     *
     * @param names классы. Порядок значения не имеет: он сортируется
     *   внутри, иначе результат зависел бы от порядка выборки из базы.
     * @param together сколько раз пара классов встретилась на одной
     *   заметке. Ключ — пара имён в любом порядке.
     */
    fun layout(names: List<String>, together: Map<Pair<String, String>, Int>): Map<String, Float> {
        val keys = names.map { it.trim().lowercase() }.distinct().sorted()
        if (keys.isEmpty()) return emptyMap()
        if (keys.size == 1) return mapOf(keys[0] to baseHue(keys[0]))

        val hue = HashMap<String, Float>()
        for (k in keys) hue[k] = baseHue(k)

        // Пары приводим к одному виду, чтобы (а,б) и (б,а) были одним.
        val bond = HashMap<Pair<String, String>, Int>()
        for ((p, w) in together) {
            val a = p.first.trim().lowercase()
            val b = p.second.trim().lowercase()
            if (a == b || a !in hue || b !in hue) continue
            val key = if (a < b) a to b else b to a
            bond[key] = (bond[key] ?: 0) + w
        }
        val maxW = (bond.values.maxOrNull() ?: 1).toFloat()

        repeat(PASSES) { pass ->
            val shift = HashMap<String, Float>()
            for (k in keys) shift[k] = 0f
            val attracting = pass < PASSES - SEPARATE_TAIL

            // Притяжение связанных: чем чаще вместе, тем ближе тон.
            if (attracting) for ((p, w) in bond) {
                val (a, b) = p
                val ha = hue[a]!!
                val hb = hue[b]!!
                val strength = PULL * (w.toFloat() / maxW)
                val dir = signedDelta(ha, hb)
                shift[a] = shift[a]!! + dir * strength
                shift[b] = shift[b]!! - dir * strength
            }

            // Отталкивание слишком близких: классы обязаны различаться.
            for (i in keys.indices) {
                for (j in i + 1 until keys.size) {
                    val a = keys[i]
                    val b = keys[j]
                    val d = distance(hue[a]!!, hue[b]!!)
                    if (d >= MIN_GAP) continue
                    val need = (MIN_GAP - d) / 2f + 0.01f
                    val dir = signedDelta(hue[a]!!, hue[b]!!)
                    val away = if (dir >= 0f) -1f else 1f
                    shift[a] = shift[a]!! + away * need * PUSH
                    shift[b] = shift[b]!! - away * need * PUSH
                }
            }

            for (k in keys) hue[k] = norm(hue[k]!! + shift[k]!!)
        }
        return hue
    }

    /** Куда двигаться от a к b по короткой дуге: со знаком. */
    private fun signedDelta(a: Float, b: Float): Float {
        var d = norm(b) - norm(a)
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * Цвет класса.
     *
     * Насыщенность растёт с числом заметок: у класса из трёх записей тон
     * бледный, у класса из двухсот — плотный. Плотность цвета честно
     * показывает вес темы, а не выдумывает его.
     */
    fun color(hue: Float, count: Int): Int {
        val sat = (0.34f + 0.030f * count).coerceAtMost(0.72f)
        val value = 0.92f
        return hsv(norm(hue), sat, value)
    }

    /** HSV в ARGB. Своя реализация: android.graphics недоступен в тестах. */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255f).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255f).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
