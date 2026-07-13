package com.vasil.stepcore.survival.engine

import kotlin.math.max
import kotlin.math.pow

/**
 * РАДАР ОКРЕСТНОСТЕЙ — экран знания, а не экран мира.
 *
 * Здесь нет ни одной новой случайности и ни одного нового события. Радар
 * ничего не выдумывает: он берёт наблюдения, которые УЖЕ попали в журнал,
 * и переводит их на язык геометрии. Если журнал молчал — радар пуст.
 *
 * ГЛАВНАЯ ИДЕЯ: метка — это не зверь. Это ПАМЯТЬ о звере. У памяти есть
 * возраст, и с возрастом она расползается.
 *
 *   неопределённость = ошибка источника + снос зверя со временем
 *
 * Волк за сутки смещается на километры — значит, вчерашняя волчья метка
 * это уже не точка, а размытая дуга. Лось топчется на месте — про него
 * можно помнить неделю. Никакой отдельной «механики тумана войны» не
 * заводится: туман ВЫВЕДЕН из скорости зверя, и потому не врёт.
 *
 * Когда неопределённость перерастает FORGET_KM, метка исчезает совсем.
 * Размытое враньё — всё ещё враньё; лучше честное «неизвестно».
 */
object RadarModel {

    /** Дальше этого радар не смотрит: там уже не окрестности, а тайга. */
    const val MAX_KM = 12.0

    /** Кольца дальности. Подписываются на экране. */
    val RINGS = doubleArrayOf(1.0, 3.0, 6.0, 12.0)

    /** Неопределённость больше этой — сведения бесполезны, метка гаснет. */
    const val FORGET_KM = 9.0

    /** И жёсткий предел по времени: месяц назад — это уже не сведения. */
    const val FORGET_DAYS = 21

    /** Красным горит только то, что близко ИЛИ чует лагерь. */
    const val NEAR_KM = 2.0

    val KINDS = listOf(
        FaunaModel.WOLF, FaunaModel.BEAR, FaunaModel.WOLVERINE,
        FaunaModel.LYNX, FaunaModel.MOOSE,
    )

    /**
     * Насколько расползается знание о звере за сутки, км.
     *
     * Это НЕ скорость зверя (волк за день намотает двадцать километров) —
     * это его типичное СМЕЩЕНИЕ от вчерашней точки. Взято из шага модели:
     * дрейф зверя за день = случайная доля от speed(kind) * 0.35.
     */
    fun driftPerDay(kind: String): Double = when (kind) {
        FaunaModel.WOLVERINE -> 3.0
        FaunaModel.WOLF -> 2.4
        FaunaModel.BEAR -> 1.5
        FaunaModel.LYNX -> 1.0
        FaunaModel.MOOSE -> 0.7
        else -> 1.5
    }

    /** Насколько врёт сам источник, км. */
    fun baseErrKm(source: Int): Double = when (source) {
        Obs.SEEN -> 0.25   // своими глазами
        Obs.TRACK -> 0.9   // след: сторона верна, дальность — догадка
        else -> 1.8        // на слух
    }

    fun uncertaintyKm(kind: String, source: Int, ageDays: Int): Double =
        baseErrKm(source) + driftPerDay(kind) * max(0, ageDays)

    /** Яркость метки: свежее — ярче. Пол нужен, чтобы старьё не пропадало
     *  раньше, чем его признают устаревшим по неопределённости. */
    fun freshness(ageDays: Int): Double =
        if (ageDays <= 0) 1.0 else max(0.12, 0.82.pow(ageDays))

    /**
     * Что известно про одного зверя.
     *
     * distKm/sector — координаты НАБЛЮДЕНИЯ, не зверя. attention — зверь
     * прямо сейчас в конусе запаха или у самого лагеря: единственное на
     * экране, что требует решения сегодня.
     */
    data class Mark(
        val kind: String,
        val sector: Int,
        val distKm: Double,
        val ageDays: Int,
        val source: Int,
        val packSize: Int,
        val uncertaintyKm: Double,
        val freshness: Double,
        val attention: Boolean,
    ) {
        /** Сведения ещё показываем, но полагаться на них уже нельзя. */
        val stale: Boolean get() = uncertaintyKm > 3.0
    }

    /**
     * Снимок знания на конец дня `day`.
     *
     * scentKm — докуда СЕГОДНЯ достаёт запах лагеря для зверя с обычным
     * чутьём. downwind — куда его несёт. Это не украшение экрана: ровно по
     * этому правилу зверь и решает, знает он о тебе или нет.
     */
    data class Recon(
        val day: Int,
        val hasWorld: Boolean,  // мир прожил хотя бы день
        val windDir: Int,       // откуда дует
        val calm: Boolean,      // штиль: запах стоит кругом
        val scentKm: Double,
        val marks: List<Mark>,
        val unknown: List<String>,
    ) {
        val downwind: Int get() = Compass.downwind(windDir)
    }

    fun build(obs: List<Obs>, day: DaySnap?, ticksDone: Int): Recon {
        // Наблюдения приходят по времени — последнее по каждому зверю побеждает.
        val last = HashMap<String, Obs>()
        for (o in obs) last[o.kind] = o

        val marks = ArrayList<Mark>()
        for (k in KINDS) {
            val o = last[k] ?: continue
            val age = ticksDone - o.tick
            if (age < 0 || age > FORGET_DAYS) continue
            val u = uncertaintyKm(k, o.source, age)
            if (u >= FORGET_KM) continue

            // Внимание — только про СЕГОДНЯ. Вчерашняя близость не значит
            // ничего: зверь давно ушёл. И лось не повод для тревоги.
            val hot = day != null && age == 0 && k != FaunaModel.MOOSE && (
                o.distKm <= NEAR_KM ||
                ScentModel.smells(
                    o.distKm, o.sector,
                    day.wind, day.tempC, day.precip, day.fog,
                    day.windDir, FaunaModel.nose(k),
                )
            )
            marks.add(Mark(
                kind = k, sector = o.sector, distKm = o.distKm, ageDays = age,
                source = o.source, packSize = o.packSize,
                uncertaintyKm = u, freshness = freshness(age), attention = hot,
            ))
        }
        // Сверху — то, что горит; дальше — самое свежее.
        marks.sortWith(compareByDescending<Mark> { it.attention }
            .thenBy { it.ageDays }.thenBy { it.distKm })

        val unknown = KINDS.filter { !last.containsKey(it) }

        return Recon(
            day = ticksDone,
            hasWorld = day != null,
            windDir = day?.windDir ?: 0,
            calm = day == null || day.wind == 0,
            scentKm = if (day == null) 0.0
                else ScentModel.reachKm(day.wind, day.tempC, day.precip, day.fog),
            marks = marks,
            unknown = unknown,
        )
    }

    // ---------- слова ----------

    fun kindRu(kind: String): String = when (kind) {
        FaunaModel.WOLF -> "Волчья стая"
        FaunaModel.BEAR -> "Медведь"
        FaunaModel.WOLVERINE -> "Росомаха"
        FaunaModel.LYNX -> "Рысь"
        FaunaModel.MOOSE -> "Лось"
        else -> kind
    }

    /** Короткая подпись у метки на экране: места мало. */
    fun kindShort(kind: String): String = when (kind) {
        FaunaModel.WOLF -> "волки"
        FaunaModel.BEAR -> "медведь"
        FaunaModel.WOLVERINE -> "росомаха"
        FaunaModel.LYNX -> "рысь"
        FaunaModel.MOOSE -> "лось"
        else -> kind
    }

    fun sourceRu(source: Int): String = when (source) {
        Obs.SEEN -> "видел сам"
        Obs.TRACK -> "по следу"
        else -> "на слух"
    }

    fun ageRu(days: Int): String = when (days) {
        0 -> "сегодня"
        1 -> "вчера"
        else -> days.toString() + " " + SurvivalEngine.daysWord(days) + " назад"
    }

    /** Километры по-человечески: 1,2 км. Точка в дробях выглядит чужой. */
    fun kmRu(km: Double): String {
        val v = Math.round(km * 10.0) / 10.0
        return v.toString().replace('.', ',') + " км"
    }
}
