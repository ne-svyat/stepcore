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

    /** Число с одним знаком после точки — для машинных строк. */
    private fun f1(v: Double): String = (Math.round(v * 10.0) / 10.0).toString()

    private fun f2(v: Double): String = (Math.round(v * 100.0) / 100.0).toString()

    private fun srcTag(source: Int): String = when (source) {
        Obs.SEEN -> "seen"
        Obs.TRACK -> "track"
        else -> "heard"
    }

    /**
     * ОТЧЁТ РАДАРА — то же знание, но текстом.
     *
     * Скриншот не годится: по картинке не видно ни точных километров, ни
     * возраста сведений, ни источника метки. Поэтому отчёт двухслойный.
     *
     * Верх — для человека: ровно те же слова, что на экране.
     * Низ (строки с решёткой) — для разбора: сырые числа, из которых экран
     * и нарисован. Если радар однажды соврёт, ложь будет видна именно здесь,
     * а не в пересказе. Так же устроен экспорт журнала — и именно он ловил
     * баги, которых не видели инварианты.
     */
    fun report(expId: Long, header: String, r: Recon): String {
        val sb = StringBuilder()
        sb.append("РАДАР · экспедиция №").append(expId)
            .append(" · день ").append(r.day).append('\n')
        if (header.isNotEmpty()) sb.append(header).append('\n')
        if (r.hasWorld) {
            if (r.calm) {
                sb.append("штиль: запах стоит вокруг лагеря, ")
                    .append(kmRu(r.scentKm)).append('\n')
            } else {
                sb.append("ветер ").append(Compass.RU[r.windDir])
                    .append(" -> запах несёт на ").append(Compass.RU[r.downwind])
                    .append(", ").append(kmRu(r.scentKm)).append('\n')
            }
        }
        sb.append('\n')

        if (r.marks.isEmpty()) {
            sb.append("Пока ничего. Следов нет, голосов не слышно.\n")
        }
        for (m in r.marks) {
            sb.append(kindRu(m.kind))
            if (m.kind == FaunaModel.WOLF && m.packSize > 1) {
                sb.append(", ").append(m.packSize)
            }
            sb.append(" — ").append(Compass.RU[m.sector])
                .append(", ").append(kmRu(m.distKm))
            sb.append(" · ").append(ageRu(m.ageDays))
                .append(", ").append(sourceRu(m.source))
            if (m.attention) sb.append(" · ЧУЕТ ЛАГЕРЬ")
            else if (m.stale) sb.append(" · сведения устарели")
            sb.append('\n')
        }
        if (r.unknown.isNotEmpty()) {
            sb.append("Ни разу не встречены: ")
                .append(r.unknown.joinToString(" · ") { kindRu(it) }).append('\n')
        }

        sb.append("\n#== данные для разбора\n")
        sb.append("# day=").append(r.day)
            .append(" windir=").append(r.windDir)
            .append(" down=").append(r.downwind)
            .append(" calm=").append(if (r.calm) 1 else 0)
            .append(" scent=").append(f1(r.scentKm))
            .append('\n')
        for (m in r.marks) {
            sb.append("# ").append(m.kind)
                .append(" sec=").append(m.sector)
                .append(" d=").append(f2(m.distKm))
                .append(" age=").append(m.ageDays)
                .append(" src=").append(srcTag(m.source))
                .append(" u=").append(f2(m.uncertaintyKm))
                .append(" fresh=").append(f2(m.freshness))
                .append(" hot=").append(if (m.attention) 1 else 0)
                .append(" pack=").append(m.packSize)
                .append('\n')
        }
        return sb.toString().trimEnd()
    }
}
