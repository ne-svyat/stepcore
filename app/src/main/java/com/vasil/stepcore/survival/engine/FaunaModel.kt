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

    /** Через сколько дней зверь, стоящий рядом, снова становится новостью. */
    private const val NEAR_PAUSE = 6

    /** Насколько ближе надо подойти, чтобы это стоило строки в журнале. */
    private const val NEAR_STEP_KM = 0.35

    /** Шанс за сутки бросить начатое: передумал, отвлёкся, нашёл падаль. */
    private const val GIVE_UP_P = 0.07

    /** Сколько дней зверь не возвращается после визита в лагерь. */
    private const val VISIT_COOLDOWN = 3

    /** Ближе этого зверь без запаха лагеря не подходит: у него свои дела.
     *  Порог выбран ЗА радиусом чтения следов (2,2 км) — иначе тропа зверя
     *  пересекала мою каждый день, и след из события превращался в фон. */
    private const val SHY_KM = 2.6

    // --- v3: зверь ИДЁТ, а не прыгает ---

    /** Дальше этого за сутки к лагерю не подойти. Даже волк. */
    private const val MAX_APPROACH_KM = 1.5

    /** Мягкая стена: зверь не любит лагерь, но не заперт за ней. */
    private fun shyKmV3(kind: String): Double = when (kind) {
        WOLVERINE -> 2.0
        MOOSE -> 1.2
        else -> 2.6
    }

    /** Ближе этого без запаха лагеря не подходит никто и никогда. */
    private fun floorV3(kind: String): Double = when (kind) {
        WOLVERINE -> 0.6
        MOOSE -> 0.8
        else -> 0.5
    }

    const val WOLF = "wolf"
    const val BEAR = "bear"
    const val WOLVERINE = "wolverine"
    const val LYNX = "lynx"
    const val MOOSE = "moose"

    /** Чутьё. Рысь охотится глазами и ушами — запах лагеря ей почти не говорит. */
    fun nose(kind: String): Double = when (kind) {
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

    // --- v5 «Свой участок»: у зверя есть СВОЯ земля, а не круг вокруг лагеря ---
    //
    // ЧТО МЕНЯЕТСЯ. До сих пор «участок» был потолком на расстояние ОТ ЛАГЕРЯ:
    // каждый зверь мотался в кольце вокруг палатки, и центр этого кольца всегда
    // совпадал с человеком. Это неявно ставило человека в середину мира. Теперь
    // мир абсолютный: лагерь — начало координат (0,0), а у каждого зверя свой
    // центр участка где-то на карте и свой радиус. Полярный вид (румб + дальность)
    // ВЫВОДИТСЯ из абсолютной позиции — чутьё, радар и охота его и читают, ничего
    // не заметив. Отсюда три следствия, которых раньше не было:
    //   · плато смещения — из физики: зверь возвращается к своему центру, а не
    //     диффундирует в бесконечность;
    //   · разные экспедиции: лагерь может лечь в сердце участка или на отшибе —
    //     это свойство сида, а не сценария;
    //   · абсолютные координаты — без них лагерь нельзя будет сдвинуть (v124).
    //
    // ОТДЕЛЬНЫЙ ПОТОК. Расстановка участков живёт в своей струе случайности
    // (соль на seed). Погодный и основной фаунный потоки не сдвигаются ни на
    // бросок — миры экспедиций v2..v4 остаются побайтово теми же.

    /** Соль расстановки участков: своя струя, чтобы не двигать старые миры. */
    const val TERRITORY_SALT = 0x7E44_0A19_5C3D_88F1L

    /** Радиус СВОЕГО участка зверя (дом), км. Не путать со старым homeRange —
     *  тот был потолком на дальность от лагеря и остаётся жить для v<5. */
    private fun homeRangeV5(kind: String): Double = when (kind) {
        WOLVERINE -> 6.0   // ходит невероятно много
        WOLF -> 4.0        // стая патрулирует широкий, но свой участок
        BEAR -> 4.0
        LYNX -> 3.0
        MOOSE -> 2.0       // топчется на своей мари — потому и «резидент»
        else -> 3.0
    }

    /** Насколько далеко от лагеря МОЖЕТ лечь центр участка: [min .. min+span]. */
    private fun centerMinKm(kind: String): Double = when (kind) {
        MOOSE -> 0.5; LYNX -> 1.5; WOLF -> 2.5; WOLVERINE -> 2.0; BEAR -> 4.0; else -> 2.0
    }
    private fun centerSpanKm(kind: String): Double = when (kind) {
        MOOSE -> 3.0; LYNX -> 4.5; WOLF -> 5.0; WOLVERINE -> 6.5; BEAR -> 9.0; else -> 4.0
    }

    /** Румб от лагеря по абсолютной точке. 0 С(+y) · 2 В(+x) · 4 Ю · 6 З. */
    private fun bearingSector(x: Double, y: Double): Int {
        val ang = Math.atan2(x, y)                 // 0 на север(+y), +pi/2 на восток(+x)
        var s = Math.round(ang / (Math.PI / 4.0)).toInt() % 8
        if (s < 0) s += 8
        return s
    }

    /** Записать полярный вид от лагеря — то, что читают чутьё, радар и охота. */
    private fun syncPolar(a: Agent) {
        a.distKm = maxOf(0.15, Math.hypot(a.x, a.y))
        a.sector = bearingSector(a.x, a.y)
    }

    /** Визит окончен — зверь ушёл в сердце своего участка. */
    private fun sendHomeV5(a: Agent, rng: SplitMix64) {
        a.approaching = false
        val ang = rng.nextDouble() * 2.0 * Math.PI
        val r = rng.nextDouble() * a.terrR * 0.35
        a.x = a.homeX + r * Math.sin(ang)
        a.y = a.homeY + r * Math.cos(ang)
        syncPolar(a)
    }

    /** Стая обошла лагерь и отступила по своей стороне на пару километров. */
    private fun backOffV5(a: Agent) {
        val d = Math.hypot(a.x, a.y)
        val target = maxOf(2.5, d)
        val k = if (d > 1e-9) target / d else 0.0
        a.x *= k; a.y *= k
        syncPolar(a)
    }

    /**
     * Один день движения в АБСОЛЮТНЫХ координатах (только v5+).
     *
     * Два режима, и решение между ними уже принято выше по коду (флаг
     * approaching, та же логика тяги/срыва, что и в v3): либо зверь взял
     * запах дыма и ИДЁТ к лагерю (к началу координат) шагом, либо он дома —
     * и тогда бродит по своему участку с возвратом к центру (процесс
     * Орнштейна-Уленбека). Возврат к центру и даёт плато смещения: зверь
     * никуда не уходит навсегда, через неделю он всё ещё «где-то тут».
     */
    private fun stepMoveV5(a: Agent, sp: Double, seed: Long, tick: Int) {
        // Своя струя на КАЖДОГО зверя (terrSalt уникален) — два лося не ходят
        // след в след, и новый вид, добавленный позже, не сдвинет этих.
        val w = SplitMix64.forTick(seed xor SALT xor a.terrSalt, tick)
        if (a.approaching) {
            // К лагерю шагом. Ограничения те же, что в полярном v3: не больше
            // MAX_APPROACH_KM, не больше восьмушки суточного хода, не больше
            // трети текущего расстояния — зверь заходит трое-четверо суток.
            val d0 = Math.hypot(a.x, a.y)
            val step = minOf(MAX_APPROACH_KM, sp * 0.12, d0 * 0.35 + 0.08)
            val nd = maxOf(0.15, d0 - step)
            val k = if (d0 > 1e-9) nd / d0 else 0.0
            a.x *= k; a.y *= k
            if (nd <= 0.25) { a.approaching = false; a.visitCooldown = VISIT_COOLDOWN }
        } else {
            // Дома: mean-reverting блуждание вокруг центра участка.
            val theta = 0.16                  // сила возврата к центру
            val sigma = a.terrR * 0.20        // суточный шум, доля радиуса
            a.x += theta * (a.homeX - a.x) + sigma * w.nextGaussian()
            a.y += theta * (a.homeY - a.y) + sigma * w.nextGaussian()
            // Мягкая стена на радиусе: за чужую землю зверь почти не заходит,
            // но и не липнет к стене — выброс отражается внутрь на три четверти.
            val dx = a.x - a.homeX; val dy = a.y - a.homeY
            val rr = Math.hypot(dx, dy)
            if (rr > a.terrR && rr > 1e-9) {
                val k = (a.terrR + (rr - a.terrR) * 0.25) / rr
                a.x = a.homeX + dx * k; a.y = a.homeY + dy * k
            }
        }
        syncPolar(a)
    }

    data class Agent(
        val kind: String,
        var distKm: Double,
        var sector: Int,
        var hunger: Double,      // 0 сыт .. 1 голоден
        var alive: Boolean = true,
        var packSize: Int = 1,   // только для волков
        var asleep: Boolean = false, // медведь в берлоге
        // v3: о чём уже рассказали. «Рядом» — это НОВОСТЬ (зверь подошёл
        // ближе, чем в прошлый раз), а не состояние («всё ещё в километре»).
        var reportedKm: Double = 99.0,
        var reportedTick: Int = -99,
        // v3: зверь, взявший след дыма, ИДЁТ, а не решает заново каждое утро.
        var approaching: Boolean = false,
        var visitCooldown: Int = 0,
        var smelledDays: Int = 0,    // сколько дней подряд чует лагерь
        var quietDays: Int = 0,      // сколько дней стая молчит (пауза между воями)
        // v5. Абсолютные координаты (км). Лагерь — начало (0,0). Пока version<5
        // эти поля мертвы: полярная модель их не читает и не пишет.
        var x: Double = 0.0,
        var y: Double = 0.0,
        var homeX: Double = 0.0,     // центр СВОЕГО участка
        var homeY: Double = 0.0,
        var terrR: Double = 0.0,     // радиус участка
        var terrSalt: Long = 0L,     // своя струя блуждания на этого зверя
    )

    /**
     * Что произошло сегодня — одно, самое значимое событие.
     *
     * sector добавлен в v112: событие всегда ЗНАЛО, где стоит зверь, просто
     * не рассказывало. Журналу румб не нужен («волки выли за рекой» звучит
     * лучше, чем «волки выли, азимут 45»), а радару — нужен.
     */
    data class FaunaEvent(
        val key: String,
        val kind: String,
        val distKm: Double,
        val packSize: Int,
        val sector: Int,
    )

    /** Итог дня фауны: событие + кто оставил след + давят ли волки на мелочь. */
    data class DayResult(
        val event: FaunaEvent?,
        val trackKind: String?,   // след агента (важнее фонового)
        val trackDistKm: Double,
        val trackSector: Int,     // откуда пришёл след: румб от лагеря
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

        // v5. Расстановка участков. Отдельная струя (соль на seed) — основной
        // фаунный поток выше не сдвинут ни на бросок, миры v2..v4 те же. Поля
        // пишутся ВСЕГДА, но читает их только движение v5; для v<5 они инертны.
        val terr = SplitMix64.forTick(seed xor SALT xor TERRITORY_SALT, 0)
        for (a in out) {
            a.terrSalt = terr.nextLong()
            val bearing = terr.nextDouble() * 2.0 * Math.PI
            val cdist = centerMinKm(a.kind) + terr.nextDouble() * centerSpanKm(a.kind)
            a.homeX = cdist * Math.sin(bearing)
            a.homeY = cdist * Math.cos(bearing)
            a.terrR = homeRangeV5(a.kind)
            // Стартовая точка — внутри участка, ближе к центру.
            val a0 = terr.nextDouble() * 2.0 * Math.PI
            val r0 = terr.nextDouble() * a.terrR * 0.5
            a.x = a.homeX + r0 * Math.sin(a0)
            a.y = a.homeY + r0 * Math.cos(a0)
        }
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
        version: Int,
        lightX10: Int = 120,
        moon: Int = 0,
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
            // Ушёл за горизонт — его возвращение снова будет новостью.
            if (version >= 3 && a.distKm > 3.0) a.reportedKm = 99.0

            // Голод. Зимой еда даётся тяжелее — голод копится быстрее.
            val hungerRate = if (st.snowCm > 20) 0.055 else 0.035
            a.hunger = minOf(1.0, a.hunger + hungerRate)

            val smells = ScentModel.smells(a.distKm, a.sector, st, windDir, nose(a.kind))
            a.smelledDays = if (smells) a.smelledDays + 1 else 0

            // Тяга к лагерю: голод, помноженный на наглость. Лось не идёт
            // на запах человека никогда — у него нет причин, и он это знает.
            val pull = if (a.kind == MOOSE) 0.0 else a.hunger * boldness(a.kind)
            val sp = speed(a.kind)

            // v3. РЕШЕНИЕ ИДТИ — УСТОЙЧИВОЕ.
            //
            // Первая версия шага переигрывала выбор каждое утро: чтобы дойти
            // за четверо суток, зверю надо было выиграть четыре броска подряд.
            // Он не выигрывал: встречи с медведем упали с 4086 до 66 на
            // 200 000 дней. Мир стал не осторожным, а пустым.
            //
            // Голодный зверь, взявший запах дыма, не бросает его через сутки.
            // Он идёт, пока запах есть и пока не передумал. Ветер, сменивший
            // сторону, — законный повод потерять след и уйти.
            if (version >= 3) {
                if (a.visitCooldown > 0) {
                    a.visitCooldown--
                    a.approaching = false
                } else {
                    if (!a.approaching && smells && rng.nextDouble() < pull) a.approaching = true
                    if (a.approaching && !smells) a.approaching = false
                    if (a.approaching && rng.nextDouble() < GIVE_UP_P) a.approaching = false
                }
            }

            if (version >= 5) {
                // v5. Движение в абсолютных координатах вокруг своего участка.
                // Решение идти/стоять уже принято выше (флаг approaching той же
                // логикой, что и в v3). Полярный вид проецируется внутри.
                stepMoveV5(a, sp, seed, tick)
            } else if (version >= 3 && a.approaching) {
                // v3. Идёт на запах ШАГОМ, а не прыжком.
                //
                // В v2 шаг равнялся скорости зверя: медведь покрывал 3-6 км
                // за сутки и падал из-за горизонта прямо к палатке. Замер на
                // 240 000 днях: 97% всех наблюдений медведя приходились ровно
                // на 150 м — промежуточных расстояний не существовало в мире.
                // Тревоги не было, потому что не было приближения.
                //
                // Теперь суточное сближение ограничено втройне: не больше
                // MAX_APPROACH_KM, не больше восьмушки суточного хода и не
                // больше трети текущего расстояния. Зверь заходит на лагерь
                // трое-четверо суток — и все эти сутки его видно на радаре.
                val step = minOf(MAX_APPROACH_KM, sp * 0.12, a.distKm * 0.35 + 0.08)
                var nd = a.distKm - step
                if (nd < 0.25) {
                    nd = 0.15                       // дошёл: он у палатки
                    a.approaching = false
                    a.visitCooldown = VISIT_COOLDOWN // визит состоялся, теперь уйдёт
                }
                a.distKm = maxOf(0.15, nd)
                if (rng.nextDouble() < 0.35) a.sector = (a.sector + (if (rng.nextDouble() < 0.5) 1 else 7)) % 8
            } else if (version < 3 && smells && rng.nextDouble() < pull) {
                // v2 (заморожена): прыжок длиной в суточный ход.
                //
                // ГАРД version < 3 ОБЯЗАТЕЛЕН. Без него эта ветка работала
                // как «второй шанс» для v3: если бросок на шаг не выпал,
                // зверь проваливался сюда и прыгал по-старому. Телепорт
                // возвращался через чёрный ход (замер: 574 прыжка).
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
                if (version >= 3) {
                    // v3. Бродя по участку, зверь тоже не сваливается на
                    // лагерь как снег на голову. Вдали он волен мотать по
                    // десять километров — это его дело. Но чем он ближе, тем
                    // короче его суточный подход: рядом с человеком зверь
                    // осторожничает, а не летит. Иначе телепорт возвращался
                    // с другой стороны — не через запах, а через блуждание
                    // (замер: 391 прыжок больше 1,5 км за сутки).
                    val maxIn = minOf(3.0, a.distKm * 0.5 + 0.3)
                    var din = drift
                    if (din < -maxIn) din = -maxIn
                    nd = a.distKm + din
                    // Стена стала МЯГКОЙ. В v2 зверь, не чующий лагеря,
                    // отбрасывался за 2,6 км жёстко — и потому лось, который
                    // на дым не идёт НИКОГДА, физически не мог оказаться
                    // ближе. «Лось рядом» был мёртвым кодом: 0 срабатываний
                    // на 240 000 дней. Теперь зверь может пройти в километре
                    // просто по своим делам — редко, но может.
                    val shy = shyKmV3(a.kind)
                    if (nd < shy) nd += (shy - nd) * 0.6
                    a.distKm = nd.coerceIn(floorV3(a.kind), homeRange(a.kind))
                } else {
                    // Не чуя лагеря, зверь не подходит вплотную просто так: у него
                    // свои дела. Исключение — росомаха, ей чужие дела интересны.
                    val floor = if (a.kind == WOLVERINE) WOLVERINE_SHY_KM else SHY_KM
                    if (nd < floor) nd = floor + rng.nextDouble() * 0.6
                    a.distKm = nd.coerceIn(0.4, homeRange(a.kind))
                }
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

        val event = pickEvent(agents, kill, wolvesNear, st, rng, tick, version,
            windDir, lightX10, moon)

        // ПОСЛЕДСТВИЕ ВИЗИТА. Этого не хватало, и без него зверь, дошедший до
        // лагеря, там и оставался: росомаха жила у палатки треть экспедиции.
        // В жизни визит всегда чем-то кончается — и зверь уходит.
        if (event != null) {
            val who = agents.firstOrNull { it.kind == event.kind && it.alive }
            if (version >= 5) {
                // v5. После визита зверь уходит В СВОЙ участок, а не на случайную
                // дальность от лагеря: теперь ему есть куда возвращаться.
                when (event.key) {
                    "fauna.wolverine.camp" -> if (who != null) { who.hunger = 0.0; sendHomeV5(who, rng) }
                    "fauna.bear.encounter" -> if (who != null) sendHomeV5(who, rng)
                    "fauna.wolf.circle"    -> if (who != null) backOffV5(who)
                    else -> {}
                }
            } else when (event.key) {
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
            trackSector = tracker?.sector ?: 0,
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
        tick: Int,
        version: Int,
        windDir: Int,
        lightX10: Int,
        moon: Int,
    ): FaunaEvent? {
        // Граница знания этого дня. Считается один раз: она общая для всех
        // зверей — это не их свойство, а свойство человека и погоды.
        val sight = SenseModel.sightKm(st.cloud, st.precip, st.fog, st.wind, lightX10, moon)
        val hearBase = SenseModel.hearKm(st.wind, st.precip, st.tempC, st.fog)
        fun a(kind: String) = agents.firstOrNull { it.kind == kind && it.alive && !it.asleep }

        /**
         * Стоит ли рассказывать про зверя, который просто РЯДОМ.
         *
         * v2 выпускала строку каждый день, пока зверь был в полосе. Пока
         * зверь телепортировался, это было незаметно. Как только он пошёл
         * шагом, росомаха начала жить в журнале: 12% всех дней. Событие,
         * которое случается всегда, событием быть перестаёт.
         *
         * Новость — это ПРИБЛИЖЕНИЕ: подошёл ощутимо ближе, чем в прошлый
         * раз. Либо прошло достаточно дней, чтобы напомнить о себе.
         */
        fun news(x: Agent): Boolean {
            if (version < 3) return true
            return x.distKm <= x.reportedKm - NEAR_STEP_KM ||
                tick - x.reportedTick >= NEAR_PAUSE
        }

        /**
         * Заметил ли человек зверя, который прошёл в километре.
         *
         * Чаще всего — НЕТ. Тайга густая, зверь тихий, человек занят дровами.
         * Вероятность падает с расстоянием: у самой палатки не заметить
         * нельзя, в полутора километрах — почти наверняка не заметишь.
         *
         * Без этого росомаха, пойдя шагом вместо прыжка, поселилась в
         * журнале: 10% всех дней были про неё. Дело было не в расстоянии —
         * дело в том, что мир докладывал о ней то, чего человек видеть не мог.
         */
        fun notices(x: Agent): Boolean {
            if (version < 3) return true
            var p: Double
            if (version >= 4) {
                // v4. Заметность держится не на расстоянии, а на ВИДИМОСТИ.
                //
                // В v3 вероятность зависела только от того, как далеко зверь.
                // Но полтора километра ясным днём и полтора километра в метель
                // — это не одно и то же расстояние, это два разных мира.
                // Теперь в пургу медведь может пройти в трёхстах шагах, и в
                // журнале не будет ни строчки. Человек узнает об этом потом,
                // по следу, — и это честно.
                p = when {
                    x.distKm <= sight -> 0.90
                    x.distKm <= sight * 1.8 -> 0.35      // угадал движение, не разглядел
                    x.distKm <= sight * 3.0 -> 0.06      // повезло: просвет, силуэт
                    else -> 0.0                          // за пределом — никак
                }
                // Потолок жёсткий. Иначе в пургу с видимостью в сто шагов
                // зверя всё равно «замечали» за километр — с шансом в шесть
                // процентов, но замечали (инвариант поймал 376 таких случаев).
                // Шести процентов невозможного достаточно, чтобы экран врал.
            } else {
                p = (1.0 - 0.55 * x.distKm).coerceIn(0.15, 0.95)
            }
            if (x.kind == MOOSE) p = minOf(0.95, p * 1.4)   // лось велик и шумен
            if (x.kind == LYNX) p *= 0.8                    // рысь — призрак, но не миф
            return rng.nextDouble() < p
        }

        fun told(x: Agent): FaunaEvent? {
            x.reportedKm = x.distKm
            x.reportedTick = tick
            return null
        }

        // Встреча глаза в глаза — редчайшее событие, и такой она должна
        // остаться. Медведь подходит вплотную, только если по-настоящему
        // голоден; сытый обойдёт стороной, и это будет «медведь рядом».
        val bear = a(BEAR)
        if (bear != null && bear.distKm < 0.35 && bear.hunger > 0.82) {
            told(bear)
            return FaunaEvent("fauna.bear.encounter", BEAR, bear.distKm, 1, bear.sector)
        }
        val wolverine = a(WOLVERINE)
        if (wolverine != null && wolverine.distKm < 0.35) {
            told(wolverine)
            return FaunaEvent("fauna.wolverine.camp", WOLVERINE, wolverine.distKm, 1, wolverine.sector)
        }
        val wolf = a(WOLF)
        if (wolf != null && wolf.distKm < 0.9) {
            told(wolf)
            return FaunaEvent("fauna.wolf.circle", WOLF, wolf.distKm, wolf.packSize, wolf.sector)
        }
        if (kill != null && wolf != null && wolf.distKm < 3.5) {
            return FaunaEvent("fauna.wolf.kill", WOLF, wolf.distKm, wolf.packSize, wolf.sector)
        }
        if (bear != null && bear.distKm < 1.6 && news(bear) && notices(bear)) {
            told(bear)
            return FaunaEvent("fauna.bear.near", BEAR, bear.distKm, 1, bear.sector)
        }
        if (wolverine != null && wolverine.distKm < 1.2 && news(wolverine) && notices(wolverine)) {
            told(wolverine)
            return FaunaEvent("fauna.wolverine.near", WOLVERINE, wolverine.distKm, 1, wolverine.sector)
        }
        // Вой слышно далеко, но только в тихую погоду: в бурю не слышно
        // собственных мыслей, не то что волков за пять километров.
        //
        // Пауза обязательна. Когда стая стала патрулировать ближе, вой пошёл
        // через день и перестал быть событием — а событие, которое случается
        // всегда, событием быть перестаёт.
        // v4. Вой слышно ровно настолько, насколько сегодня слышно вообще —
        // и лучше с наветренной стороны. Старое правило «ветер не сильнее
        // единицы, ближе 4,5 км» было грубой заготовкой этой же мысли.
        val howlHeard = if (wolf == null) false else if (version >= 4) {
            wolvesNear <= SenseModel.hearKmIn(hearBase, wolf.sector, windDir, st.wind)
        } else {
            wolvesNear < 4.5 && st.wind <= 1
        }
        if (wolf != null && howlHeard &&
            wolf.quietDays >= HOWL_PAUSE && rng.nextDouble() < 0.28) {
            wolf.quietDays = 0
            return FaunaEvent("fauna.wolf.howl", WOLF, wolvesNear, wolf.packSize, wolf.sector)
        }
        val lynx = a(LYNX)
        if (lynx != null && lynx.distKm < 1.0 && news(lynx) && notices(lynx)) {
            told(lynx)
            return FaunaEvent("fauna.lynx.near", LYNX, lynx.distKm, 1, lynx.sector)
        }
        val moose = a(MOOSE)
        if (moose != null && moose.distKm < 1.0 && news(moose) && notices(moose)) {
            told(moose)
            return FaunaEvent("fauna.moose.near", MOOSE, moose.distKm, 1, moose.sector)
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
