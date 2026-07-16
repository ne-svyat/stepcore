package com.vasil.stepcore.survival

import com.vasil.stepcore.R

/**
 * Единая таблица «категория события -> как оно выглядит в журнале».
 *
 * Смысл существования отдельного файла: журнал будет наполняться слоями
 * (радио, следы, звери, ресурсы), и каждый новый слой должен добавлять
 * СТРОКУ в эту таблицу, а не править разметку журнала. Категории, которых
 * ещё нет в движке, уже заведены — чтобы при их появлении не пришлось
 * трогать UI.
 *
 * Цвет — единственный носитель смысла: по нему видно, что перед тобой,
 * не читая текста. Поэтому цвета не должны множиться бесконтрольно.
 */
object JournalStyle {

    /** Веха мира: смена сезона, зима встала, ледостав. Янтарь — «важное». */
    const val MILESTONE = "milestone"
    /** Погода. Синий — небо. */
    const val WEATHER = "weather"
    /** Сводка тихого дня (историческая; новые дни пишут шапку карточки). */
    const val DIGEST = "digest"
    /** Бытовая зарисовка. Приглушённый — фон жизни. */
    const val AMBIENT = "ambient"
    /** Системная строка: итоги, служебное. */
    const val SYSTEM = "system"

    // --- заведено заранее, движок начнёт это эмитить в следующих релизах ---
    /** Радио и наблюдательная сеть. Бирюзовый — «голос извне». */
    const val RADIO = "radio"
    /** Следы на снегу и на земле. */
    const val TRACK = "track"
    /** Животные: наблюдение, встреча. Фиолетовый — «живое и чужое». */
    const val ANIMAL = "animal"
    /** Лагерь и снаряжение. */
    const val CAMP = "camp"
    /** Рана и её заживание. Красный — тело. */
    const val WOUND = "wound"

    fun colorRes(category: String): Int = when (category) {
        MILESTONE -> R.color.accent_amber_bright
        WEATHER -> R.color.accent_blue_bright
        AMBIENT -> R.color.text_dim
        DIGEST -> R.color.text_dim
        SYSTEM -> R.color.text_dim
        RADIO -> R.color.accent_teal_bright
        TRACK -> R.color.accent_teal
        ANIMAL -> R.color.accent_violet_bright
        CAMP -> R.color.accent_amber
        WOUND -> R.color.accent_red_bright
        else -> R.color.text_main
    }

    /**
     * Метка-префикс строки. Точка/значок вместо слова: журнал остаётся
     * дневником, а не приборной панелью, но глаз всё равно цепляется.
     */
    fun markOf(category: String): String = when (category) {
        MILESTONE -> "»"
        RADIO -> "((•))"
        TRACK -> "~"
        ANIMAL -> "•"
        WOUND -> "+"
        else -> "·"
    }

    /**
     * Показывать ли строку в новом журнале. Сводки тихого дня, записанные
     * до появления карточки дня, теперь дублируют её шапку — прошлое не
     * переписываем (строки остаются в базе и в экспорте), но на экране
     * не показываем дважды одно и то же.
     */
    fun visibleInCard(category: String): Boolean = category != DIGEST
}
