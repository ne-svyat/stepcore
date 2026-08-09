package com.vasil.stepcore.vault

/**
 * Файл тайников. Чистый Kotlin, проверяется тестами до сборки.
 *
 * ЗАЧЕМ НЕСКОЛЬКО ТАЙНИКОВ
 * -----------------------
 * Один файл держит до MAX_VAULTS независимых хранилищ. У каждого свой ключ
 * данных, свой пароль и свой секрет восстановления. Пароль одного тайника
 * не открывает другой и не сообщает о его существовании.
 *
 * ПОЧЕМУ СОЛЬ ОДНА НА ФАЙЛ, А НЕ НА ОБЁРТКУ
 * -----------------------------------------
 * Соль защищает от заранее посчитанных таблиц. Ей НЕ требуется различаться
 * внутри одного файла: разные секреты и так дают разные ключи. Зато общая
 * соль позволяет посчитать scrypt ОДИН раз на попытку входа и потом дёшево
 * примерить результат ко всем слотам.
 *
 * С отдельными солями шестнадцать обёрток стоили бы шестнадцать прогонов
 * scrypt — полминуты на каждый неверный пароль. Тогда лимит в восемь
 * тайников был бы неиспользуем на практике.
 *
 * ЧЕГО ЭТОТ ФОРМАТ НЕ ОБЕЩАЕТ
 * ---------------------------
 * Правдоподобного отрицания. Число слотов лежит в файле открыто: тот, кто
 * вскрыл папку приложения, увидит, сколько тайников создано (но не что
 * внутри). Прятать это "шумом в пустых слотах" бессмысленно — приложение
 * само перестало бы отличать пустой слот от занятого и затирало бы чужие
 * данные при создании нового тайника. Скрытность держится на интерфейсе,
 * который одинаков в обоих случаях, и об этом честно сказано вслух.
 *
 * РАСКЛАДКА
 * ---------
 * "SCV2" | N (4, BE) | соль (16) | число слотов (1) | слоты по 60 байт
 * Слот = nonce (12) + зашифрованный ключ данных (32) + тег GCM (16).
 * Слоты идут парами: чётный — пароль, нечётный — секрет восстановления.
 */
object VaultFile {

    private const val M0 = 'S'.code.toByte()
    private const val M1 = 'C'.code.toByte()
    private const val M2 = 'V'.code.toByte()

    /** Старый формат: без байта льготы в хвосте. Читается по-прежнему. */
    private const val M3_V2 = '2'.code.toByte()

    /** Формат с одним байтом льготы в конце. Читается по-прежнему. */
    private const val M3_V3 = '3'.code.toByte()

    /**
     * Текущий формат: в конце длина блока настроек и сам блок.
     *
     * ПОЧЕМУ БЛОК, А НЕ ОЧЕРЕДНОЙ БАЙТ
     * --------------------------------
     * Льгота уже потребовала поднять версию формата, клавиатура потребовала
     * бы второй раз, запрет скриншотов - третий. Каждая такая правка
     * трогает разбор ключей, то есть самое опасное место модуля.
     *
     * Блок переменной длины закрывает вопрос навсегда: новая настройка -
     * это новый байт внутри блока, а не новая версия файла. Неизвестные
     * байты в конце блока пропускаются, короткий блок читается со
     * значениями по умолчанию.
     */
    private const val M3_V4 = '4'.code.toByte()

    /** Место каждой настройки в блоке. Порядок менять нельзя. */
    private const val OPT_GRACE = 0
    private const val OPT_KB_LAYOUT = 1
    private const val OPT_KB_SCOPE = 2
    private const val OPTS_LEN = 3

    // ---------------------------------------------------------- клавиатура
    //
    // Где действует своя клавиатура.

    /** Везде системная. Так было до появления этой настройки. */
    const val KB_OFF = 0

    /** Только ввод пароля тайника. */
    const val KB_PASSWORD = 1

    // ------------------------------------------------------------- льгота
    //
    // Сколько живёт расшифрованный ключ после ухода с экрана.
    //
    // Настройка лежит В ФАЙЛЕ КЛЮЧЕЙ, а не в общих настройках приложения:
    // строка "льгота тайника" в общем бэкапе выдала бы, что тайник вообще
    // существует. Здесь она соседствует с числом слотов - то есть на том
    // же уровне открытости, о котором сказано выше честно.
    //
    // Значение общее на файл, а не на тайник: разная льгота у соседних
    // тайников выдала бы, что их несколько.

    const val GRACE_90S = 0
    const val GRACE_15M = 1
    const val GRACE_1H = 2

    /** Ключ живёт, пока горит экран. Гаснет экран - запирается сразу. */
    const val GRACE_SCREEN = 3

    /** Сколько миллисекунд ждать. Для GRACE_SCREEN время не при чём. */
    fun graceMs(mode: Int): Long = when (mode) {
        GRACE_15M -> 15L * 60_000L
        GRACE_1H -> 60L * 60_000L
        else -> 90_000L
    }

    /** Восемь тайников по два ключа. */
    const val MAX_VAULTS = 8
    const val MAX_SLOTS = MAX_VAULTS * 2

    const val SLOT_LEN = VaultCrypto.NONCE_LEN + VaultCrypto.KEY_LEN + 16
    private const val HEAD = 4 + 4 + VaultCrypto.SALT_LEN + 1

    /** Содержимое файла в разобранном виде. */
    class Box(
        val n: Int,
        val salt: ByteArray,
        val slots: List<ByteArray>,
        /** Файлы старого формата читаются как полторы минуты - так было. */
        val grace: Int = GRACE_90S,
        /** Раскладка своей клавиатуры. */
        val kbLayout: Int = VaultKeys.LAYOUT_NORMAL,
        /** Где своя клавиатура применяется. По умолчанию нигде. */
        val kbScope: Int = KB_OFF,
    ) {
        val vaultCount: Int get() = slots.size / 2
        val isFull: Boolean get() = slots.size >= MAX_SLOTS
    }

    // ------------------------------------------------------------ разбор/сборка

    fun encode(b: Box): ByteArray {
        require(b.salt.size == VaultCrypto.SALT_LEN) { "bad salt" }
        require(b.slots.size <= MAX_SLOTS) { "too many slots" }
        require(b.slots.size % 2 == 0) { "slots must come in pairs" }
        require(b.slots.all { it.size == SLOT_LEN }) { "bad slot size" }

        require(b.grace in GRACE_90S..GRACE_SCREEN) { "bad grace" }
        require(b.kbLayout in VaultKeys.LAYOUT_NORMAL..VaultKeys.LAYOUT_CHAOS) { "bad layout" }
        require(b.kbScope in KB_OFF..KB_PASSWORD) { "bad scope" }

        // Байт льготы дописывается В КОНЕЦ, а не в заголовок: тогда
        // раскладка слотов не сдвигается, и разбор старого файла отличается
        // от нового ровно одним хвостовым байтом.
        val out = ByteArray(HEAD + b.slots.size * SLOT_LEN + 1 + OPTS_LEN)
        out[0] = M0; out[1] = M1; out[2] = M2; out[3] = M3_V4
        out[4] = (b.n ushr 24).toByte()
        out[5] = (b.n ushr 16).toByte()
        out[6] = (b.n ushr 8).toByte()
        out[7] = b.n.toByte()
        b.salt.copyInto(out, 8)
        out[8 + VaultCrypto.SALT_LEN] = b.slots.size.toByte()
        var o = HEAD
        for (s in b.slots) { s.copyInto(out, o); o += SLOT_LEN }
        out[o] = OPTS_LEN.toByte()
        out[o + 1 + OPT_GRACE] = b.grace.toByte()
        out[o + 1 + OPT_KB_LAYOUT] = b.kbLayout.toByte()
        out[o + 1 + OPT_KB_SCOPE] = b.kbScope.toByte()
        return out
    }

    /** @return null при любой порче: обрезке, чужом файле, враньё в длине. */
    fun decode(raw: ByteArray): Box? {
        if (raw.size < HEAD) return null
        if (raw[0] != M0 || raw[1] != M1 || raw[2] != M2) return null
        val v4 = raw[3] == M3_V4
        val v3 = raw[3] == M3_V3
        if (!v4 && !v3 && raw[3] != M3_V2) return null
        val n = ((raw[4].toInt() and 0xFF) shl 24) or ((raw[5].toInt() and 0xFF) shl 16) or
                ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if (n < VaultCrypto.N_MIN || n > VaultCrypto.N_MAX || Integer.bitCount(n) != 1) return null
        val salt = raw.copyOfRange(8, 8 + VaultCrypto.SALT_LEN)
        val count = raw[8 + VaultCrypto.SALT_LEN].toInt() and 0xFF
        if (count == 0 || count > MAX_SLOTS || count % 2 != 0) return null
        val body = HEAD + count * SLOT_LEN

        // Настройки: у старых файлов их нет или есть только льгота.
        var grace = GRACE_90S
        var kbLayout = VaultKeys.LAYOUT_NORMAL
        var kbScope = KB_OFF
        when {
            v4 -> {
                if (raw.size < body + 1) return null
                val optsLen = raw[body].toInt() and 0xFF
                if (raw.size != body + 1 + optsLen) return null
                // Читаем только то, что знаем. Лишние байты в конце - это
                // настройки будущих версий, и они нам не мешают.
                if (optsLen > OPT_GRACE) grace = raw[body + 1 + OPT_GRACE].toInt() and 0xFF
                if (optsLen > OPT_KB_LAYOUT) kbLayout = raw[body + 1 + OPT_KB_LAYOUT].toInt() and 0xFF
                if (optsLen > OPT_KB_SCOPE) kbScope = raw[body + 1 + OPT_KB_SCOPE].toInt() and 0xFF
            }
            v3 -> {
                if (raw.size != body + 1) return null
                grace = raw[body].toInt() and 0xFF
            }
            else -> if (raw.size != body) return null
        }
        if (grace < GRACE_90S || grace > GRACE_SCREEN) return null
        if (kbLayout < VaultKeys.LAYOUT_NORMAL || kbLayout > VaultKeys.LAYOUT_CHAOS) return null
        if (kbScope < KB_OFF || kbScope > KB_PASSWORD) return null
        val slots = ArrayList<ByteArray>(count)
        var o = HEAD
        repeat(count) { slots.add(raw.copyOfRange(o, o + SLOT_LEN)); o += SLOT_LEN }
        return Box(n, salt, slots, grace, kbLayout, kbScope)
    }

    // -------------------------------------------------------------------- вход

    /**
     * Попытка входа. Один прогон scrypt, дальше примерка ко всем слотам.
     *
     * @return ключ данных подошедшего тайника, либо null.
     */
    fun open(box: Box, secret: CharArray): ByteArray? = openAt(box, secret)?.key

    // ---------------------------------------------------------------- создание

    /** Первый тайник: новая соль, новое N, два слота. */
    fun createFirst(n: Int, password: CharArray, phrase: CharArray): Box {
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_LEN)
        val dataKey = VaultCrypto.newDataKey()
        try {
            return Box(n, salt, listOf(
                seal(salt, n, password, dataKey),
                seal(salt, n, phrase, dataKey)
            ))
        } finally {
            dataKey.fill(0)
        }
    }

    /** Причина отказа при добавлении тайника. Разделены, чтобы экран мог
     *  объяснить человеку, что не так, не выдавая ничего постороннему:
     *  добавление доступно только изнутри уже открытого тайника. */
    enum class AddResult { OK, FULL, SECRET_ALREADY_USED }

    class Added(val result: AddResult, val box: Box?)

    /**
     * Добавить тайник в существующий файл.
     *
     * Секрет, уже открывающий какой-то слот, отвергается: иначе один пароль
     * вёл бы в два тайника и человек терял бы доступ ко второму навсегда,
     * даже не поняв этого.
     */
    fun addVault(box: Box, password: CharArray, phrase: CharArray): Added {
        if (box.isFull) return Added(AddResult.FULL, null)
        if (open(box, password) != null || open(box, phrase) != null) {
            return Added(AddResult.SECRET_ALREADY_USED, null)
        }
        val dataKey = VaultCrypto.newDataKey()
        try {
            val slots = ArrayList(box.slots)
            slots.add(seal(box.salt, box.n, password, dataKey))
            slots.add(seal(box.salt, box.n, phrase, dataKey))
            return Added(AddResult.OK,
                Box(box.n, box.salt, slots, box.grace, box.kbLayout, box.kbScope))
        } finally {
            dataKey.fill(0)
        }
    }

    // ---------------------------------------------------------------- удаление

    /**
     * Что именно открылось. Для удаления одного ключа мало: стирать надо
     * ПАРУ слотов одного тайника, а значит нужен индекс.
     */
    class Opened(val slot: Int, val key: ByteArray)

    /**
     * Вход с указанием слота. Один прогон scrypt, дальше примерка.
     *
     * Вызывающий обязан затереть key нулями после использования.
     */
    fun openAt(box: Box, secret: CharArray): Opened? {
        val kek = Scrypt.derive(secret, box.salt, box.n, VaultCrypto.R, VaultCrypto.P,
            VaultCrypto.KEY_LEN)
        try {
            for (i in box.slots.indices) {
                val k = VaultCrypto.decrypt(kek, box.slots[i])
                if (k != null) return Opened(i, k)
            }
            return null
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Убрать тайник из файла: обе его обёртки разом.
     *
     * Слоты идут парами, и снятие одной обёртки оставило бы тайник
     * открываемым вторым секретом — то есть удаление не удаляло бы.
     * Поэтому индекс приводится к началу пары.
     *
     * @return новый Box, либо null если пара была последней. Null означает
     *         «файла ключей быть не должно»: Box с нулём слотов невалиден
     *         по формату, decode такой файл отверг бы как порченый.
     */
    fun removeVault(box: Box, slot: Int): Box? {
        require(slot in box.slots.indices) { "bad slot" }
        val first = slot and 1.inv()
        val rest = ArrayList<ByteArray>(box.slots.size - 2)
        for (i in box.slots.indices) {
            if (i != first && i != first + 1) rest.add(box.slots[i])
        }
        if (rest.isEmpty()) return null
        return Box(box.n, box.salt, rest, box.grace, box.kbLayout, box.kbScope)
    }

    /**
     * Сменить льготу. Слоты и соль не трогаются: пароли остаются теми же,
     * scrypt не пересчитывается, менять настройку можно хоть каждый день.
     */
    fun withGrace(box: Box, mode: Int): Box {
        require(mode in GRACE_90S..GRACE_SCREEN) { "bad grace" }
        return Box(box.n, box.salt, box.slots, mode, box.kbLayout, box.kbScope)
    }

    /** Сменить раскладку клавиатуры. Слоты и соль не трогаются. */
    fun withKeyboard(box: Box, layout: Int, scope: Int): Box {
        require(layout in VaultKeys.LAYOUT_NORMAL..VaultKeys.LAYOUT_CHAOS) { "bad layout" }
        require(scope in KB_OFF..KB_PASSWORD) { "bad scope" }
        return Box(box.n, box.salt, box.slots, box.grace, layout, scope)
    }

    private fun seal(salt: ByteArray, n: Int, secret: CharArray, dataKey: ByteArray): ByteArray {
        val kek = Scrypt.derive(secret, salt, n, VaultCrypto.R, VaultCrypto.P, VaultCrypto.KEY_LEN)
        try {
            return VaultCrypto.encrypt(kek, dataKey)
        } finally {
            kek.fill(0)
        }
    }
}
