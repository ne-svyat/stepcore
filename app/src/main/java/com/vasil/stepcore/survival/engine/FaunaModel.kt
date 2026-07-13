package com.vasil.stepcore.survival.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Звери-агенты.
 *
 * ЧТО ЗДЕСЬ ПРИНЦИПИАЛЬНО НОВОГО. До сих пор след был лотереей: бросок
 * кубика решал, кто «прошёл». Теперь след — ОТЧЁТ. Зверь существует до
 * того, как о нём напишут: у него есть место, голод, чутьё и причина идти
 * туда, куда он идёт. Если волчьи следы попались дважды за неделю — это
 * значит, что стая действительно ходит рядом, а не что кубик лёг дважды.
 *
 * КТО АГЕНТ, А КТО ФОН. Агентами сделаны только те, у кого есть своя
 * история: волчья стая, медведь, росомаха, рысь и пара лосей. Заяц, лиса,
 * соболь и глухарь остались статистикой (TrackModel) — заводить сорок
 * зайцев ради строки «заячий след» было бы честно и бессмысленно.
 * Но фон теперь зависит от агентов: пришли волки — заяц ушёл.
 *
 * ГЕОМЕТРИЯ. Мир — не карта, а полярные координаты от лагеря: расстояние
 * в километрах и румб. Этого ровно достаточно, чтобы ветер, запах и след
 * стали одной механикой, и ровно недостаточно, чтобы утонуть в карте,
 * которую всё равно негде показать.
 *
 * СЛУЧАЙНОСТЬ. Свой поток (соль на seed). Погодный поток не сдвигается.
 */
object FaunaModel {

    const val SALT = 0x1A0F_BEA5_7C0D_3E11L

    /** Дальше этого — зверь «далеко» и может потянуться к знакомому месту. */
    private const val FAR_KM = 3.0
    /** Росомахе лагерь интересен, но и она не ночует под палаткой. */
    private const val WOLVERINE_SHY_KM = 1.1

    /** Минимум дней молчания между воями стаи. */
    private const val HOWL_PAUSE = 9

    /** Ближе этого зверь без запаха лагеря не подходит: у него свои дела.
     *  Порог выбран ЗА радиусом чтения следов (2,2 км) — иначе тропа зверя
     *  пересекала мою каждый день, и след из события превращался в фон. */
    private const val SHY_KM = 2.6

    const val WOLF = "wolf"
    const val BEAR = "bear"
    const val WOLVERINE = "wolverine"
    const val LYNX = "lynx"
    const val MOOSE = "moose"

    /** Чутьё. Рысь охотится глазами и ушами — запах лагеря ей почти не говорит. */
    private fun nose(kind: String): Double = when (kind) {
        BEAR -> 1.6
        WOLF -> 1.5
        WOLVERINE -> 1.4
        MOOSE -> 0.7
        LYNX -> 0.5
        else -> 1.0
    }

    /** Сколько километров зверь способен пройти за сутки. */
    private fun speed(kind: String): Double = when (kind) {
        WOLVERINE -> 25.0   // росомаха ходит невероятно много
        WOLF -> 20.0
        BEAR -> 12.0
        LYNX -> 8.0
        MOOSE -> 6.0
        else -> 5.0
    }

    /** Радиус участка: дальше зверь не уходит, там чужая земля. */
    private fun homeRange(kind: String): Double = when (kind) {
        WOLVERINE -> 25.0
        WOLF -> 12.0
        BEAR -> 15.0
        LYNX -> 8.0
        MOOSE -> 6.0
        else -> 8.0
    }

    /**
     * Насколько зверь готов подойти к человеку.
     * Росомаха наглая: лабаз для неё — это склад, а не опасность.
     * Рысь человека почти игнорирует — но и не интересуется им.
     */
    private fun boldness(kind: String): Double = when (kind) {
        WOLVERINE -> 0.85
        BEAR -> 0.32
        WOLF -> 0.30
        LYNX -> 0.15
        MOOSE -> 0.05
        else -> 0.2
    }

    data class Agent(
        val kind: String,
        var distKm: Double,
        var sector: Int,
        var hunger: Double,      // 0 сыт .. 1 голоден
        var alive: Boolean = true,
        var packSize: Int = 1,   // только для волков
        var asleep: Boolean = false, // медведь в берлоге
        var smelledDays: Int = 0,    // сколько дней подряд чует лагерь
        var quietDays: Int = 0,      // сколько дней стая молчит (пауза между воями)
    )

    /** Что произошло сегодня — одно, самое значимое событие. */
    data class FaunaEvent(
        val key: String,
        val kind: String,
        val distKm: Double,
        val packSize: Int,
    )

    /** Итог дня фауны: событие + кто оставил след + давят ли волки на мелочь. */
    data class DayResult(
        val event: FaunaEvent?,
        val trackKind: String?,   // след агента (важнее фонового)
        val trackDistKm: Double,
        val wolvesNearKm: Double, // 99 если далеко
    )

    fun spawn(seed: Long, season: Int): List<Agent> {
        val rng = SplitMix64.forTick(seed xor SALT, 0)
        val out = ArrayList<Agent>()
        out.add(Agent(
            kind = WOLF,
            distKm = 6.0 + rng.nextDouble() * 5.0,
            sector = rng.nextInt(8),
            hunger = 0.4 + rng.nextDouble() * 0.3,
            packSize = 3 + rng.nextInt(4),
        ))
        out.add(Agent(BEAR, 5.0 + rng.nextDouble() * 8.0, rng.nextInt(8), 0.5 + rng.nextDouble() * 0.4))
        out.add(Agent(WOLVERINE, 8.0 + rng.nextDouble() * 10.0, rng.nextInt(8), 0.5 + rng.nextDouble() * 0.4))
        out.add(Agent(LYNX, 4.0 + rng.nextDouble() * 4.0, rng.nextInt(8), 0.4 + rng.nextDouble() * 0.3))
        out.add(Agent(MOOSE, 3.0 + rng.nextDouble() * 4.0, rng.nextInt(8), 0.2))
        out.add(Agent(MOOSE, 4.0 + rng.nextDouble() * 4.0, rng.nextInt(8), 0.2))
        return out
    }

    /**
     * Один день жизни зверей.
     *
     * Порядок важен: сначала каждый решает, чует ли он лагерь, потом
     * двигается, и только потом волки охотятся — иначе стая успевала бы
     * задрать лося и в тот же день прийти на запах за десять километров.
     */
    fun step(
        agents: List<Agent>,
        st: WeatherState,
        windDir: Int,
        season: Int,
        seed: Long,
        tick: Int,
    ): DayResult {
        val rng = SplitMix64.forTick(seed xor SALT, tick)
        val scent = ScentModel.campScentKm(st)

        for (a in agents) {
            if (!a.alive) continue

            // Медведь: спит по факту зимы, а не по календарю. Ложится, когда
            // лёг снег и держит мороз; встаёт по теплу. Спящий медведь
            // не ходит, не чует и не оставляет следов — его просто нет.
            if (a.kind == BEAR) {
                // Ложится по факту: лёг снег и держится холод — или просто
                // ударил мороз. Флага «зима встала» тут мало: в ноябре может
                // стоять -15 без устойчивого покрова, а медведь уже спит.
                val shouldSleep = (st.snowCm >= 8 && st.tempC <= -3) || st.tempC <= -7
                val shouldWake = st.snowCm < 15 && st.tempC >= 2
                if (!a.asleep && shouldSleep) a.asleep = true
                if (a.asleep && shouldWake) {
                    a.asleep = false
                    a.hunger = 1.0            // весенний медведь голоден до злости
                    a.distKm = 3.0 + rng.nextDouble() * 6.0
                }
                if (a.asleep) { a.smelledDays = 0; continue }
            }

            if (a.kind == WOLF) a.quietDays++

            // Голод. Зимой еда даётся тяжелее — голод копится быстрее.
            val hungerRate = if (st.snowCm > 20) 0.055 else 0.035
            a.hunger = minOf(1.0, a.hunger + hungerRate)

            val smells = ScentModel.smells(a.distKm, a.sector, st, windDir, nose(a.kind))
            a.smelledDays = if (smells) a.smelledDays + 1 else 0

            // Тяга к лагерю: голод, помноженный на наглость. Лось не идёт
            // на запах человека никогда — у него нет причин, и он это знает.
            val pull = if (a.kind == MOOSE) 0.0 else a.hunger * boldness(a.kind)
            val sp = speed(a.kind)

            if (smells && rng.nextDouble() < pull) {
                // Идёт на запах. Не по прямой — по ветру, забирая в сторону.
                a.distKm = maxOf(0.15, a.distKm - sp * (0.25 + 0.25 * a.hunger))
                if (rng.nextDouble() < 0.35) a.sector = (a.sector + (if (rng.nextDouble() < 0.5) 1 else 7)) % 8
            } else if (smells && a.kind != MOOSE && a.distKm < 1.5) {
                // Чует, но сыт или осторожен: обходит стороной, не приближаясь.
                a.distKm += sp * 0.15
                a.sector = (a.sector + (if (rng.nextDouble() < 0.5) 1 else 7)) % 8
            } else {
                // Не чует: бродит по участку. Направление меняется лениво.
                //
                // Голодный зверь бродит НЕ равномерно: он обходит участок,
                // и лагерь на этом участке — самая заметная аномалия. Волк
                // помнит, где пахло дымом, даже когда ветер сменился.
                // Без этого стая, разминувшись с подветренным сектором,
                // уходила на десять километров и не возвращалась неделями.
                // Любопытство работает только ИЗДАЛЕКА: голодный зверь
                // возвращается в тот угол участка, где однажды пахло дымом.
                // Вблизи оно выключается — иначе волк превращался в собаку
                // и сутками сидел у палатки (проверено: 43% дней у лагеря).
                val curiosity =
                    if (a.kind == MOOSE || a.distKm < FAR_KM) 0.0 else a.hunger * 0.25
                val drift = (rng.nextDouble() - 0.45 - curiosity) * sp * 0.35
                var nd = a.distKm + drift
                // Не чуя лагеря, зверь не подходит вплотную просто так: у него
                // свои дела. Исключение — росомаха, ей чужие дела интересны.
                val floor = if (a.kind == WOLVERINE) WOLVERINE_SHY_KM else SHY_KM
                if (nd < floor) nd = floor + rng.nextDouble() * 0.6
                a.distKm = nd.coerceIn(0.4, homeRange(a.kind))
                if (rng.nextDouble() < 0.3) {
                    a.sector = (a.sector + (if (rng.nextDouble() < 0.5) 1 else 7) + 8) % 8
                }
            }
        }

        // Охота волков на лося. Глубокий снег — приговор: лось проваливается,
        // волки идут поверх наста. Без снега лось уходит почти всегда.
        val wolves = agents.firstOrNull { it.kind == WOLF && it.alive }
        var kill: Agent? = null
        if (wolves != null && wolves.hunger > 0.45) {
            val prey = agents.filter { it.kind == MOOSE && it.alive }
                .minByOrNull { between(wolves, it) }
            if (prey != null && between(wolves, prey) < 2.0) {
                val chance = when {
                    st.snowCm >= 45 -> 0.55
                    st.snowCm >= 25 -> 0.30
                    st.snowCm >= 10 -> 0.12
                    else -> 0.04
                }
                if (rng.nextDouble() < chance) {
                    prey.alive = false
                    wolves.hunger = 0.0
                    kill = prey
                }
            }
        }

        val wolvesNear = if (wolves != null && wolves.alive) wolves.distKm else 99.0

        // Кто оставил след у лагеря. Приоритет — тому, кто ближе.
        // Зверь рядом — ещё не значит, что его тропа пересеклась с моей:
        // тайга большая, а я хожу по одной тропе. Поэтому след — событие
        // вероятное, а не обязательное.
        val near = agents.filter { it.alive && !it.asleep && it.distKm <= 2.2 }
            .minByOrNull { it.distKm }
        val trackChance = if ((near?.distKm ?: 9.0) < 1.0) 0.5 else 0.3
        val tracker = if (near != null && rng.nextDouble() < trackChance) near else null

        val event = pickEvent(agents, kill, wolvesNear, st, rng)

        // ПОСЛЕДСТВИЕ ВИЗИТА. Этого не хватало, и без него зверь, дошедший до
        // лагеря, там и оставался: росомаха жила у палатки треть экспедиции.
        // В жизни визит всегда чем-то кончается — и зверь уходит.
        if (event != null) {
            val who = agents.firstOrNull { it.kind == event.kind && it.alive }
            when (event.key) {
                // Наелась — и ушла отсыпаться. Голод сброшен, участок сменён.
                "fauna.wolverine.camp" -> if (who != null) {
                    who.hunger = 0.0
                    who.distKm = 6.0 + rng.nextDouble() * 8.0
                    who.sector = rng.nextInt(8)
                }
                // Разошлись. Медведь не охотится на человека — он его избегает,
                // просто делает это неторопливо и с достоинством.
                "fauna.bear.encounter" -> if (who != null) {
                    who.distKm = 4.0 + rng.nextDouble() * 6.0
                    who.sector = rng.nextInt(8)
                }
                // Обошли, изучили, ушли. Стая не штурмует лагерь — она его читает.
                "fauna.wolf.circle" -> if (who != null) {
                    who.distKm = 2.5 + rng.nextDouble() * 3.0
                }
                else -> {}
            }
        }

        return DayResult(
            event = event,
            trackKind = tracker?.kind,
            trackDistKm = tracker?.distKm ?: 99.0,
            wolvesNearKm = wolvesNear,
        )
    }

    /**
     * Одно событие в день — самое значимое. Порядок отражает то, как это
     * переживалось бы в лагере: встреча важнее следа, след важнее воя,
     * вой важнее тишины.
     */
    private fun pickEvent(
        agents: List<Agent>,
        kill: Agent?,
        wolvesNear: Double,
        st: WeatherState,
        rng: SplitMix64,
    ): FaunaEvent? {
        fun a(kind: String) = agents.firstOrNull { it.kind == kind && it.alive && !it.asleep }

        // Встреча глаза в глаза — редчайшее событие, и такой она должна
        // остаться. Медведь подходит вплотную, только если по-настоящему
        // голоден; сытый обойдёт стороной, и это будет «медведь рядом».
        val bear = a(BEAR)
        if (bear != null && bear.distKm < 0.35 && bear.hunger > 0.82) {
            return FaunaEvent("fauna.bear.encounter", BEAR, bear.distKm, 1)
        }
        val wolverine = a(WOLVERINE)
        if (wolverine != null && wolverine.distKm < 0.35) {
            return FaunaEvent("fauna.wolverine.camp", WOLVERINE, wolverine.distKm, 1)
        }
        val wolf = a(WOLF)
        if (wolf != null && wolf.distKm < 0.9) {
            return FaunaEvent("fauna.wolf.circle", WOLF, wolf.distKm, wolf.packSize)
        }
        if (kill != null && wolf != null && wolf.distKm < 3.5) {
            return FaunaEvent("fauna.wolf.kill", WOLF, wolf.distKm, wolf.packSize)
        }
        if (bear != null && bear.distKm < 1.6) {
            return FaunaEvent("fauna.bear.near", BEAR, bear.distKm, 1)
        }
        if (wolverine != null && wolverine.distKm < 1.2) {
            return FaunaEvent("fauna.wolverine.near", WOLVERINE, wolverine.distKm, 1)
        }
        // Вой слышно далеко, но только в тихую погоду: в бурю не слышно
        // собственных мыслей, не то что волков за пять километров.
        //
        // Пауза обязательна. Когда стая стала патрулировать ближе, вой пошёл
        // через день и перестал быть событием — а событие, которое случается
        // всегда, событием быть перестаёт.
        if (wolf != null && wolvesNear < 4.5 && st.wind <= 1 &&
            wolf.quietDays >= HOWL_PAUSE && rng.nextDouble() < 0.28) {
            wolf.quietDays = 0
            return FaunaEvent("fauna.wolf.howl", WOLF, wolvesNear, wolf.packSize)
        }
        val lynx = a(LYNX)
        if (lynx != null && lynx.distKm < 1.0) {
            return FaunaEvent("fauna.lynx.near", LYNX, lynx.distKm, 1)
        }
        val moose = a(MOOSE)
        if (moose != null && moose.distKm < 1.0) {
            return FaunaEvent("fauna.moose.near", MOOSE, moose.distKm, 1)
        }
        return null
    }

    /** Расстояние между двумя зверями в полярных координатах от лагеря. */
    private fun between(a: Agent, b: Agent): Double {
        val ang = Compass.diff(a.sector, b.sector) * Math.PI / 4.0
        val d2 = a.distKm * a.distKm + b.distKm * b.distKm -
            2.0 * a.distKm * b.distKm * cos(ang)
        return sqrt(abs(d2))
    }
}
