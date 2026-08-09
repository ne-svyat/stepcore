package com.vasil.stepcore.vault

import kotlin.random.Random

/**
 * Раскладки клавиатуры тайника. Чистый Kotlin: ни View, ни Context.
 * Проверяется тестами до сборки, потому что цена ошибки здесь предельная.
 *
 * ГЛАВНОЕ ТРЕБОВАНИЕ
 * ------------------
 * Набор символов обязан покрывать ВСЁ, что человек мог набрать системной
 * клавиатурой. Пропущенный символ означает, что чей-то пароль перестал
 * набираться, а ключа нет нигде: тайник потерян навсегда.
 *
 * Отсюда правило перемешивания: оно только МЕНЯЕТ ПОРЯДОК. Ни один режим
 * не имеет права добавить или потерять клавишу. Это проверяется тестом на
 * каждой странице и в каждом режиме.
 *
 * ПОЧЕМУ ПРОБЕЛ ОТДЕЛЬНОЙ КЛАВИШЕЙ
 * --------------------------------
 * Пробел - полноценный символ секрета, и края не обрезаются: пробел в
 * начале пароля это часть пароля. В перемешивании он не участвует -
 * клавиша без надписи, найденная на ощупь, ничего не выдаёт.
 *
 * ЧЕГО КЛАВИАТУРА НЕ ОБЕЩАЕТ
 * --------------------------
 * Защиты от программного перехвата. Она защищает от взгляда через плечо
 * и от следов пальца на стекле - движение перестаёт быть подписью пароля.
 * Так и сказано на экране: точности, которой нет, мы не показываем.
 */
object VaultKeys {

    const val LAYOUT_NORMAL = 0
    const val LAYOUT_SHUFFLED = 1
    const val LAYOUT_GROUPED = 2
    const val LAYOUT_CHAOS = 3

    const val PAGE_LAT = 0
    const val PAGE_CYR = 1
    const val PAGE_NUM = 2
    const val PAGE_SYM = 3

    const val PAGE_COUNT = 4

    private val LAT = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val CYR = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё")
    private val NUM = listOf("1234567890", "-/:;()\$&@\"", ".,?!'")
    private val SYM = listOf("[]{}#%^*+=", "_\\|~<>№€₽", "`")

    private fun base(page: Int): List<String> = when (page) {
        PAGE_CYR -> CYR
        PAGE_NUM -> NUM
        PAGE_SYM -> SYM
        else -> LAT
    }

    // ------------------------------------------------------- классы клавиш
    //
    // ЗАЧЕМ ЦВЕТ
    // ----------
    // В перемешанной раскладке глаз ищет одну букву среди тридцати трёх
    // одинаковых. Цвет сокращает поиск втрое: сначала находится группа,
    // потом буква внутри группы. По алфавиту искать и так легко, но
    // деление работает и там - хуже не делает.
    //
    // ЧЕГО ЦВЕТ НЕ ВЫДАЁТ
    // -------------------
    // Ничего. Буквы и так написаны на клавишах: тот, кто видит экран,
    // видит их прямо. Клавиатура защищает от чтения ДВИЖЕНИЯ пальца, а не
    // от чтения экрана, и цвет этого не меняет.

    const val CLASS_VOWEL = 0
    const val CLASS_CONSONANT = 1
    const val CLASS_SIGN = 2
    const val CLASS_DIGIT = 3
    const val CLASS_SYMBOL = 4

    private const val VOWELS = "aeiouyаеёиоуыэюя"

    /** Ъ и Ь - не гласные и не согласные, и на вид это честнее. */
    private const val SIGNS = "ъь"

    /** Класс не зависит от регистра: заглавная А та же гласная. */
    fun classOf(c: Char): Int {
        val l = c.lowercaseChar()
        return when {
            l in '0'..'9' -> CLASS_DIGIT
            SIGNS.indexOf(l) >= 0 -> CLASS_SIGN
            VOWELS.indexOf(l) >= 0 -> CLASS_VOWEL
            l.isLetter() -> CLASS_CONSONANT
            else -> CLASS_SYMBOL
        }
    }

    /** Меняется ли порядок при пересборке. */
    fun isShuffling(layout: Int): Boolean =
        layout == LAYOUT_SHUFFLED || layout == LAYOUT_CHAOS

    /** Подпись на клавише смены страницы. */
    fun pageLabel(page: Int): String = when (page) {
        PAGE_CYR -> "АБВ"
        PAGE_NUM -> "123"
        PAGE_SYM -> "#+="
        else -> "ABC"
    }

    /** Есть ли у страницы регистр. У цифр и знаков его нет. */
    fun hasCase(page: Int): Boolean = page == PAGE_LAT || page == PAGE_CYR

    /** Название режима для экрана настроек. */
    fun layoutName(layout: Int): String = when (layout) {
        LAYOUT_SHUFFLED -> "Перемешанная"
        LAYOUT_GROUPED -> "Сгруппированная"
        LAYOUT_CHAOS -> "Полный хаос"
        else -> "Обычная"
    }

    /**
     * Ряды клавиш страницы.
     *
     * Длины рядов сохраняются при любом режиме: иначе клавиатура прыгала
     * бы по высоте при смене страницы, и палец терял бы опору там, где
     * терять её незачем.
     */
    fun rows(page: Int, layout: Int, upper: Boolean, seed: Long): List<String> {
        val src = base(page)
        val sizes = src.map { it.length }
        val all = src.joinToString("")

        val arranged = when (layout) {
            LAYOUT_GROUPED -> all.toCharArray().sortedBy { it.code }.joinToString("")
            LAYOUT_SHUFFLED, LAYOUT_CHAOS -> {
                val c = all.toCharArray().toMutableList()
                val r = Random(seed)
                for (i in c.indices.reversed()) {
                    val j = r.nextInt(i + 1)
                    val t = c[i]; c[i] = c[j]; c[j] = t
                }
                c.joinToString("")
            }
            else -> all
        }

        val out = ArrayList<String>(sizes.size)
        var o = 0
        for (n in sizes) { out.add(arranged.substring(o, o + n)); o += n }
        return if (upper && hasCase(page)) out.map { it.uppercase() } else out
    }

    /**
     * Ширины клавиш ряда. Обычно все равны; в полном хаосе размер тоже
     * пляшет, чтобы палец не мог опереться даже на геометрию.
     *
     * Пределы жёсткие: клавиша уже 0.7 не попадается пальцем, шире 1.4
     * ломает ряд.
     */
    fun widths(row: String, layout: Int, seed: Long): FloatArray {
        val w = FloatArray(row.length) { 1f }
        if (layout != LAYOUT_CHAOS) return w
        val r = Random(seed * 31 + row.length)
        for (i in w.indices) w[i] = 0.75f + r.nextFloat() * 0.6f
        return w
    }
}
