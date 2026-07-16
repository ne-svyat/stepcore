package com.vasil.stepcore.survival.engine

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ЖИВАЯ ТАЙГА (движок v7). Три слоя поверх крупного зверя:
 *
 * 1. МЕЛКАЯ ДИЧЬ. Заяц, лиса, белка, глухарь, рябчик. Это не агенты, а
 *    МЕСТА: заячьи покопы у ивняка, ток на болоте, беличий бор. Мест много,
 *    и они стоят в мире (из seed). Прошёл рядом — узнал. Тайга перестаёт
 *    быть пустой не потому, что зверя стало больше на радаре, а потому, что
 *    он там ЖИВЁТ.
 *
 * 2. НАХОДКИ. Зимовьё, ягодник, сброшенные рога, родник. Стоят в мире,
 *    находятся, когда проходишь рядом. Найденное не забывается: постоянная
 *    метка на радаре. Тайга накапливает ТВОЮ историю.
 *
 * 3. ВСТРЕЧИ. Сошёлся с крупным зверем вплотную — мир останавливается и
 *    ждёт РЕШЕНИЯ. У решения есть цена: день пути, рана, спугнутый зверь.
 *    Исход детерминирован от seed и выбора: переигрыш с тем же выбором даёт
 *    тот же мир. Раны заживают днями и замедляют ход.
 *
 * Вся случайность — в своих потоках (соль на seed): погода, ветер, крупный
 * зверь не сдвинуты ни на бросок. Миры v2..v6 байт-в-байт прежние.
 */
object WildModel {

    const val SALT = 0x5EED_0F0A_11CE_77A3L
    const val ENC_SALT = 0x0DDB_A11C_0FFE_E123L
    const val FIND_SALT = 0x0BADF00D_5EEDL

    // --- мелкая дичь: виды-места ---
    const val HARE = "hare"
    const val FOX = "fox"
    const val SQUIRREL = "squirrel"
    const val CAPER = "caper"   // глухарь
    const val HAZEL = "hazel"   // рябчик
    val SMALL_KINDS = listOf(HARE, FOX, SQUIRREL, CAPER, HAZEL)

    // --- находки ---
    const val F_CABIN = "f_cabin"
    const val F_BERRY = "f_berry"
    const val F_ANTLER = "f_antler"
    const val F_SPRING = "f_spring"
    val FIND_KINDS = listOf(F_CABIN, F_BERRY, F_ANTLER, F_SPRING)

    /** Наблюдение «нашёл сам»: метка не стареет и не расплывается. */
    const val SRC_LANDMARK = 3

    // ---------- места мелкой дичи ----------

    class Pocket(val kind: String, val x: Double, val y: Double, val salt: Long)

    /**
     * Сетка 3×3 км на квадрате ±30 км; клетка занята с шансом ~0.4.
     * Итого ~160 мест: в дневном переходе почти всегда кто-то живёт.
     */
    fun pockets(seed: Long): List<Pocket> {
        val out = ArrayList<Pocket>()
        val rng = SplitMix64.forTick(seed xor SALT, 0)
        var gx = -30.0
        while (gx < 30.0) {
            var gy = -30.0
            while (gy < 30.0) {
                val roll = rng.nextDouble()
                val kx = gx + rng.nextDouble() * 1.5
                val ky = gy + rng.nextDouble() * 1.5
                val ksalt = rng.nextLong()
                if (roll < 0.50) {
                    val kind = when {
                        roll < 0.16 -> HARE
                        roll < 0.26 -> SQUIRREL
                        roll < 0.36 -> FOX
                        roll < 0.44 -> HAZEL
                        else -> CAPER
                    }
                    out.add(Pocket(kind, kx, ky, ksalt))
                }
                gy += 1.5
            }
            gx += 1.5
        }
        return out
    }

    /** С какого расстояния место себя выдаёт (км): след/голос/взгляд. */
    private fun detectKm(kind: String): Double = when (kind) {
        HARE -> 1.2        // покопы, тропы, помёт — читаются вдоль перехода
        FOX -> 1.3
        SQUIRREL -> 0.9
        CAPER -> 2.0       // ток слышно издалека
        HAZEL -> 1.2
        else -> 1.0
    }

    /** Активность места в сезон: зимой ток молчит, летом заяц малозаметен. */
    private fun activity(kind: String, season: Int): Double = when (kind) {
        HARE -> if (season == 0) 0.85 else 0.55
        FOX -> 0.6
        SQUIRREL -> if (season == 0 || season == 3) 0.7 else 0.45
        CAPER -> if (season == 1) 0.9 else 0.35
        HAZEL -> 0.55
        else -> 0.5
    }

    // ---------- находки ----------

    class Find(val id: Int, val kind: String, val x: Double, val y: Double)

    /** Ровно 30 находок на мир, кольцом 2..24 км: редкое — реже. */
    fun finds(seed: Long): List<Find> {
        val out = ArrayList<Find>()
        val rng = SplitMix64.forTick(seed xor FIND_SALT, 0)
        var id = 0
        fun place(kind: String, n: Int, minKm: Double, spanKm: Double) {
            repeat(n) {
                val a = rng.nextDouble() * 2.0 * Math.PI
                val d = minKm + rng.nextDouble() * spanKm
                out.add(Find(id++, kind, d * Math.sin(a), d * Math.cos(a)))
            }
        }
        place(F_CABIN, 2, 5.0, 17.0)
        place(F_SPRING, 6, 2.0, 18.0)
        place(F_BERRY, 13, 2.0, 20.0)
        place(F_ANTLER, 9, 2.0, 22.0)
        return out
    }

    private const val DISCOVER_KM = 0.8

    // ---------- контекст одного прогона ----------

    /**
     * Память прогона: что уже найдено и когда какое место отчитывалось.
     * Живёт один run(): мир никогда не сериализуется, память — тоже.
     */
    class Ctx {
        val found = HashSet<Int>()
        val pocketSeen = HashMap<Int, Int>() // индекс места -> день последнего отчёта
        var lastEncDay = -99
        var woundDays = 0
        var woundKind = ""
    }

    // ---------- дневной отчёт: дичь и находки вокруг новой стоянки ----------

    class DayNote(
        val obs: Obs?,               // на радар (может не быть)
        val journalTxt: String?,     // в журнал (может не быть)
        val category: String,        // track / animal / camp
        val phase: Int,
    )

    fun dayNotes(
        seed: Long, day: Int, season: Int,
        pockets: List<Pocket>, finds: List<Find>, ctx: Ctx,
        px: Double, py: Double,
    ): List<DayNote> {
        val out = ArrayList<DayNote>()
        val rng = SplitMix64.forTick(seed xor SALT, day.toLong().toInt())

        // Находки: наткнулся — узнал навсегда. Не больше одной за день:
        // день в тайге короткий, две находки съедают друг друга.
        for (f in finds) {
            if (f.id in ctx.found) continue
            if (hypot(f.x - px, f.y - py) > DISCOVER_KM) continue
            ctx.found.add(f.id)
            val (dist, sec) = polar(f.x - px, f.y - py)
            out.add(DayNote(
                Obs(tick = day, phase = SurvivalEngine.DAY, kind = f.kind + "#" + f.id,
                    sector = sec, distKm = dist, source = SRC_LANDMARK, packSize = 1),
                pick(findTxt(f.kind), rng.nextLong()),
                "camp", SurvivalEngine.DAY,
            ))
            break
        }

        // Мелкая дичь: не больше двух отчётов за день, ближние вперёд.
        var told = 0
        val near = pockets.withIndex()
            .map { (i, p) -> Triple(i, p, hypot(p.x - px, p.y - py)) }
            .filter { it.third <= detectKm(it.second.kind) }
            .sortedBy { it.third }
        for ((i, p, d) in near) {
            if (told >= 2) break
            val lastTold = ctx.pocketSeen[i] ?: -99
            if (day - lastTold < 3) continue // то же место — не новость три дня
            val roll = SplitMix64.forTick(p.salt, day).nextDouble()
            if (roll > activity(p.kind, season)) continue
            ctx.pocketSeen[i] = day
            val (dist, sec) = polar(p.x - px, p.y - py)
            val heard = p.kind == CAPER || p.kind == HAZEL
            val src = if (heard) Obs.HEARD else Obs.TRACK
            // Журнальная строка — не на каждый отчёт: радар полнее журнала.
            val txt = if (rng.nextDouble() < 0.6) pick(smallTxt(p.kind), rng.nextLong()) else null
            val ph = if (heard) SurvivalEngine.MORNING else SurvivalEngine.DAY
            out.add(DayNote(
                Obs(tick = day, phase = ph, kind = p.kind, sector = sec, distKm = dist,
                    source = src, packSize = 1,
                    bearingErr = if (heard && rng.nextDouble() < 0.3) 1 else 0),
                txt, "track", ph,
            ))
            told++
        }
        return out
    }

    // ---------- встречи ----------

    class Encounter(val kind: String, val meetTxt: String, val options: List<String>)

    class Outcome(
        val txt: String,
        val lostDay: Boolean,
        val woundDays: Int,
        val scared: Boolean,
    )

    /** Ближе этого — уже не наблюдение, а ВСТРЕЧА. */
    private const val ENC_KM = 1.2
    private const val ENC_COOLDOWN = 5

    fun optionsFor(kind: String): List<String> = when (kind) {
        FaunaModel.WOLF -> listOf("Обойти стороной (день)", "Идти прямо", "Отпугнуть огнём")
        FaunaModel.BEAR -> listOf("Тихо отойти (день)", "Показать себя, шуметь", "Пройти краем")
        FaunaModel.MOOSE -> listOf("Переждать (день)", "Обойти по лесу", "Идти мимо")
        FaunaModel.WOLVERINE -> listOf("Отогнать от лабаза", "Не связываться")
        else -> listOf("Отойти", "Идти дальше")
    }

    /**
     * Есть ли встреча этим утром. Смотрит ИСТИННЫЕ позиции — это мир решает,
     * что вы сошлись, а не радар. Рысь не считается: она человека игнорирует.
     */
    fun encounterAt(
        seed: Long, day: Int, fauna: List<FaunaModel.Agent>, ctx: Ctx,
        px: Double, py: Double,
    ): Encounter? {
        if (day - ctx.lastEncDay < ENC_COOLDOWN) return null
        val a = fauna.filter {
            it.alive && !it.asleep && it.kind != FaunaModel.LYNX &&
                hypot(it.x - px, it.y - py) <= ENC_KM
        }.minByOrNull { hypot(it.x - px, it.y - py) } ?: return null
        val rng = SplitMix64.forTick(seed xor ENC_SALT, day)
        if (rng.nextDouble() > 0.75) return null
        ctx.lastEncDay = day
        return Encounter(a.kind, pick(meetTxt(a.kind), rng.nextLong()), optionsFor(a.kind))
    }

    /** Исход выбора. Детерминирован от seed+день+выбор: переигрыш честен. */
    fun resolve(seed: Long, day: Int, kind: String, choice: Int): Outcome {
        val rng = SplitMix64.forTick(seed xor ENC_SALT, day * 31 + choice + 1)
        val r = rng.nextDouble()
        val roll = rng.nextLong()
        fun t(k: String) = pick(outTxt(kind, choice, k), roll)
        return when (kind) {
            FaunaModel.WOLF -> when (choice) {
                0 -> Outcome(t("safe"), lostDay = true, woundDays = 0, scared = false)
                1 -> when {
                    r < 0.62 -> Outcome(t("good"), false, 0, true)
                    r < 0.88 -> Outcome(t("tense"), false, 0, false)
                    else -> Outcome(t("bad"), false, 3 + rng.nextInt(3), true)
                }
                else -> when {
                    r < 0.78 -> Outcome(t("good"), false, 0, true)
                    else -> Outcome(t("bad"), false, 2 + rng.nextInt(3), true)
                }
            }
            FaunaModel.BEAR -> when (choice) {
                0 -> Outcome(t("safe"), true, 0, false)
                1 -> when {
                    r < 0.70 -> Outcome(t("good"), false, 0, true)
                    r < 0.92 -> Outcome(t("tense"), true, 0, false)
                    else -> Outcome(t("bad"), false, 4 + rng.nextInt(3), true)
                }
                else -> when {
                    r < 0.55 -> Outcome(t("good"), false, 0, false)
                    r < 0.90 -> Outcome(t("tense"), false, 0, false)
                    else -> Outcome(t("bad"), false, 3 + rng.nextInt(4), true)
                }
            }
            FaunaModel.MOOSE -> when (choice) {
                0 -> Outcome(t("safe"), true, 0, false)
                1 -> Outcome(t("good"), false, 0, false)
                else -> when {
                    r < 0.80 -> Outcome(t("good"), false, 0, true)
                    else -> Outcome(t("bad"), false, 2 + rng.nextInt(3), true)
                }
            }
            else -> when (choice) { // росомаха
                0 -> when {
                    r < 0.85 -> Outcome(t("good"), false, 0, true)
                    else -> Outcome(t("bad"), false, 2, true)
                }
                else -> Outcome(t("safe"), false, 0, false)
            }
        }
    }

    /** Спугнутый зверь уходит на дальний край СВОЕГО участка. */
    fun scare(fauna: List<FaunaModel.Agent>, kind: String, px: Double, py: Double) {
        val a = fauna.filter { it.kind == kind && it.alive }
            .minByOrNull { hypot(it.x - px, it.y - py) } ?: return
        val dx = a.homeX - px; val dy = a.homeY - py
        val d = hypot(dx, dy).coerceAtLeast(0.001)
        a.x = a.homeX + dx / d * a.terrR * 0.8
        a.y = a.homeY + dy / d * a.terrR * 0.8
        a.approaching = false
        a.visitCooldown = 4
    }

    // ---------- раны ----------

    fun woundLine(roll: Long, kind: String): String =
        pick(listOf(
            "Рана ноет на каждом шагу. Иду вполовину обычного.",
            "Перевязал заново. Идти можно, но недалеко.",
            "Нога слушается плохо. День вышел короткий.",
        ), roll)

    fun woundHealedLine(roll: Long): String =
        pick(listOf(
            "Рана затянулась. Шаг снова мой.",
            "Сегодня впервые не вспоминал о ране. Значит, зажила.",
        ), roll)

    // ---------- тексты ----------

    private fun pick(v: List<String>, roll: Long): String =
        v[((roll % v.size + v.size) % v.size).toInt()]

    private fun polar(dx: Double, dy: Double): Pair<Double, Int> {
        val dist = hypot(dx, dy)
        var deg = Math.toDegrees(atan2(dx, dy))
        if (deg < 0) deg += 360.0
        val sec = Math.round(deg / 45.0).toInt() % 8
        return Pair(dist, sec)
    }

    private fun smallTxt(kind: String): List<String> = when (kind) {
        HARE -> listOf(
            "Заячьи тропы натоптаны, покопы свежие. Тут их много.",
            "Заяц живёт рядом: погрызы на иве, помёт, лёжка под выворотнем.",
        )
        FOX -> listOf(
            "Лисий след ровной строчкой. Мышкует по краю поляны.",
            "Лиса держит этот край: следы каждый день, помётные точки на буграх.",
        )
        SQUIRREL -> listOf(
            "Бор беличий: шелуха шишек горками, цокот сверху.",
            "Белка гоняет по кронам. Под кедром — расклёванные шишки.",
        )
        CAPER -> listOf(
            "Слышал глухаря. Тяжёлый взлёт, потом тишина.",
            "Глухариное место: наброды, ямки-порхалища, перо на мху.",
        )
        HAZEL -> listOf(
            "Рябчик свистит в ельнике. Близко, но не показывается.",
            "Пересвист рябчиков по распадку. Выводок держится тут.",
        )
        else -> listOf("Мелкая жизнь рядом: следы, шорохи.", "Кто-то мелкий живёт по соседству.")
    }

    private fun findTxt(kind: String): List<String> = when (kind) {
        F_CABIN -> listOf(
            "ЗИМОВЬЁ. Старое, но крыша живая и печка целая. Такое место запоминают навсегда.",
            "Вышел на зимовьё. Дверь на палке, внутри нары и ржавая печь. Отметил на память.",
        )
        F_BERRY -> listOf(
            "Ягодник. Целый склон — есть за чем возвращаться.",
            "Набрёл на ягодное место. Птица его тоже знает: наклёвано.",
        )
        F_ANTLER -> listOf(
            "Сброшенные рога в мху. Тяжёлые. Зверь ходит где-то рядом.",
            "Нашёл рога-сброс. Погрызены мышами, но хороши.",
        )
        F_SPRING -> listOf(
            "Родник. Вода ледяная и чистая — не чета ручьевой.",
            "Ключ бьёт из-под камня. Отметил: чистая вода дорого стоит.",
        )
        else -> listOf("Приметное место. Отметил.", "Находка. Такое запоминаешь.")
    }

    private fun meetTxt(kind: String): List<String> = when (kind) {
        FaunaModel.WOLF -> listOf(
            "ВСТРЕЧА. Волки на тропе. Стоят и смотрят — не таясь, как хозяева.",
            "ВСТРЕЧА. Стая вышла на меня по краю распадка. Между нами сотня шагов.",
        )
        FaunaModel.BEAR -> listOf(
            "ВСТРЕЧА. Медведь на моём пути. Возится у выворотня, меня ещё не видит.",
            "ВСТРЕЧА. Медведь. Крупный. Поднял голову и слушает в мою сторону.",
        )
        FaunaModel.MOOSE -> listOf(
            "ВСТРЕЧА. Лось стоит на тропе. Не уходит. Уши прижаты — это плохой знак.",
            "ВСТРЕЧА. Бык вышел из ельника прямо на меня. Земля летит из-под копыта.",
        )
        FaunaModel.WOLVERINE -> listOf(
            "ВСТРЕЧА. Росомаха у лабаза. Наглая, уходить не собирается.",
            "ВСТРЕЧА. Росомаха потрошит мой лабаз средь бела дня.",
        )
        else -> listOf("ВСТРЕЧА. Зверь на тропе.", "ВСТРЕЧА. Мы увидели друг друга одновременно.")
    }

    private fun outTxt(kind: String, choice: Int, res: String): List<String> {
        val key = kind + "." + choice + "." + res
        return when (key) {
            "wolf.0.safe" -> listOf(
                "Дал кругом. Потерял день, зато свой. Волки — не те соседи, с кем спорят.",
                "Обошёл болотом. День пропал, спина цела.")
            "wolf.1.good" -> listOf(
                "Шёл ровно, не глядя в глаза. Расступились и растворились в ельнике.",
                "Не сбавил шага. Вожак повёл стаю прочь — сегодня им не до меня.")
            "wolf.1.tense" -> listOf(
                "Разминулись. Шли параллельно до самого вечера — я их не видел, но знал.",
                "Пропустили. Но до темноты за спиной пару раз хрустело.")
            "wolf.1.bad" -> listOf(
                "Молодой бросился. Отбился, но клык распорол ногу. Перевязал — идти тяжко.",
                "Сшиблись. Стаю отогнал головнёй, нога разодрана. Худой размен.")
            "wolf.2.good" -> listOf(
                "Огонь и железо об железо. Ушли — злые, но ушли.",
                "Головня и крик. Стая снялась. Долго выли на распадке — пусть.")
            "wolf.2.bad" -> listOf(
                "Огонь их смутил, но крайний успел цапнуть. Рана рваная, зато волков нет.",
                "Отпугнул. В свалке ожёг руку о собственную головню и порвал ладонь о сук.")
            "bear.0.safe" -> listOf(
                "Отошёл тихо, не поворачиваясь спиной. День потерян, голова цела.",
                "Пятился полверсты, потом кругом. Медведь так и не понял, что я был.")
            "bear.1.good" -> listOf(
                "Встал в рост, заговорил громко. Мотнул башкой и ушёл — не его день.",
                "Шумел, стучал по стволу. Ушёл тяжёлым махом. Уважили друг друга.")
            "bear.1.tense" -> listOf(
                "Сделал ложный бросок — земля дрогнула. Замер. Ушёл сам, но день кончился тут.",
                "Рявкнул так, что сердце встало. Разошлись, но дальше я сегодня не ходок.")
            "bear.1.bad" -> listOf(
                "Бросок был не ложный. Ушёл от него по буревалу, распоров бедро о сук.",
                "Пошёл на меня всерьёз. Спасся в буреломе. Нога вспорота, но живой.")
            "bear.2.good" -> listOf(
                "Прошёл краем, по ветру. Он так и возился у своего выворотня.",
                "Обогнул под ветер. Медведь меня не учуял — или сделал вид.")
            "bear.2.tense" -> listOf(
                "Прошёл. Он поднялся и долго смотрел вслед. Спина это запомнила.",
                "Краем прошёл, но он заметил. Не пошёл следом — и на том спасибо.")
            "bear.2.bad" -> listOf(
                "Не хватило края. Кинулся. Ушёл через завал, оставив на суках клок мяса.",
                "Учуял и пошёл на меня. Оторвался по камням, голень разбита.")
            "moose.0.safe" -> listOf(
                "Переждал за ветром. К вечеру ушёл сам. День — быку.",
                "Сидел тихо полдня. Лось выстоял своё и убрёл. Дешёвая цена.")
            "moose.1.good" -> listOf(
                "Обошёл по лесу. Крюк съел полдня, но с быком в гон не спорят.",
                "Дал круг ельником. Слышал, как он ломал ветки там, где я мог идти.")
            "moose.2.good" -> listOf(
                "Прошёл мимо, не глядя. Фыркнул и отступил в ольховник.",
                "Разминулись на полсотне шагов. Он проводил меня рогами, как антенной.")
            "moose.2.bad" -> listOf(
                "Кинулся. Успел за ствол — копыто снесло кору у плеча. Рука висит.",
                "Пошёл на меня. Уходил через валежник, подвернул и разбил колено.")
            "wolverine.0.good" -> listOf(
                "Отогнал камнями и криком. Утащила кусок, но лабаз отстоял.",
                "Пошёл на неё — отскочила и ушла. Наглости много, войны не захотела.")
            "wolverine.0.bad" -> listOf(
                "Отогнал, но успела полоснуть по руке. Мелкий зверь — злые когти.",
                "Кинулась в упор, зацепила запястье. Лабаз мой, кровь тоже моя.")
            "wolverine.1.safe" -> listOf(
                "Не стал связываться. Взяла своё и ушла. Лабаз теперь вешать выше.",
                "Отдал ей этот раунд. Росомаха дерётся до конца — оно того не стоит.")
            else -> listOf("Обошлось.", "Разошлись.")
        }
    }
}
