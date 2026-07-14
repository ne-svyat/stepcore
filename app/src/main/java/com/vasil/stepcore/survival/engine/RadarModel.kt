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
        /** Ошибка стороны в румбах на момент наблюдения (слух в туман). */
        val bearingErr: Int = 0,
    ) {
        /** Сведения ещё показываем, но полагаться на них уже нельзя. */
        val stale: Boolean get() = uncertaintyKm > 3.0
    }

    /**
     * Зверь, о котором ЗНАЛИ, но сведения протухли.
     *
     * Реальный журнал: волки выли, обходили лагерь, оставили след на 52-й
     * день — а на радаре 60-го дня их не было НИГДЕ. Ни метки, ни строки.
     * Экран молчал так, будто волков не существует. Забыть — это не то же
     * самое, что не знать, и человек имеет право видеть разницу.
     */
    data class Faded(
        val kind: String,
        val ageDays: Int,
        val sector: Int,
        val source: Int,
    )

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
        /** Знали, но сведения устарели: показываем строкой, без метки. */
        val faded: List<Faded> = emptyList(),
        /** Докуда сегодня видно. */
        val sightKm: Double = 0.0,
        /** Докуда сегодня слышно (без учёта стороны). */
        val hearKm: Double = 0.0,
    ) {
        val downwind: Int get() = Compass.downwind(windDir)

        /** Слышимость в конкретную сторону: против ветра дальше. */
        fun hearIn(sector: Int, wind: Int): Double =
            SenseModel.hearKmIn(hearKm, sector, windDir, wind)
    }

    fun build(obs: List<Obs>, day: DaySnap?, ticksDone: Int): Recon {
        // Наблюдения приходят по времени — последнее по каждому зверю побеждает.
        val last = HashMap<String, Obs>()
        for (o in obs) last[o.kind] = o

        val marks = ArrayList<Mark>()
        val faded = ArrayList<Faded>()
        for (k in KINDS) {
            val o = last[k] ?: continue
            val age = ticksDone - o.tick
            if (age < 0) continue
            val u = uncertaintyKm(k, o.source, age)
            if (age > FORGET_DAYS || u >= FORGET_KM) {
                // Метку рисовать нельзя — она превратилась бы в ложь. Но и
                // молчать нельзя: человек помнит, что зверь тут был.
                faded.add(Faded(k, age, o.sector, o.source))
                continue
            }

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
                bearingErr = o.bearingErr,
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
            faded = faded,
            sightKm = if (day == null) 0.0 else SenseModel.sightKm(
                day.cloud, day.precip, day.fog, day.wind, day.light, day.moon),
            hearKm = if (day == null) 0.0
                else SenseModel.hearKm(day.wind, day.precip, day.tempC, day.fog),
        )
    }

    /** Слышимость в сторону (дробный румб — для гладкого лепестка на экране). */
    fun hearAt(r: Recon, sector: Double, wind: Int): Double {
        if (r.calm) return r.hearKm
        var d = Math.abs(sector - r.windDir)
        if (d > 4.0) d = 8.0 - d
        val k = when {
            d <= 1.0 -> 1.45
            d <= 2.0 -> 1.45 - 0.45 * (d - 1.0)
            else -> 1.0 - 0.45 * (d - 2.0) / 2.0
        }
        return r.hearKm * k
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
            sb.append("видно ").append(kmRu(r.sightKm))
                .append(" · слышно ").append(kmRu(r.hearKm))
            if (!r.calm) sb.append(" (против ветра дальше)")
            sb.append('\n')
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
        for (f in r.faded) {
            sb.append(kindRu(f.kind)).append(" — сведения устарели: ")
                .append(ageRu(f.ageDays)).append(", ").append(Compass.RU[f.sector])
                .append(", ").append(sourceRu(f.source)).append('\n')
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
            .append(" sight=").append(f2(r.sightKm))
            .append(" hear=").append(f2(r.hearKm))
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
                .append(" berr=").append(m.bearingErr)
                .append('\n')
        }
        for (f in r.faded) {
            sb.append("# ").append(f.kind)
                .append(" FADED age=").append(f.ageDays)
                .append(" sec=").append(f.sector)
                .append(" src=").append(srcTag(f.source))
                .append('\n')
        }
        return sb.toString().trimEnd()
    }
}
