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
    private const val M3 = '2'.code.toByte()

    /** Восемь тайников по два ключа. */
    const val MAX_VAULTS = 8
    const val MAX_SLOTS = MAX_VAULTS * 2

    const val SLOT_LEN = VaultCrypto.NONCE_LEN + VaultCrypto.KEY_LEN + 16
    private const val HEAD = 4 + 4 + VaultCrypto.SALT_LEN + 1

    /** Содержимое файла в разобранном виде. */
    class Box(val n: Int, val salt: ByteArray, val slots: List<ByteArray>) {
        val vaultCount: Int get() = slots.size / 2
        val isFull: Boolean get() = slots.size >= MAX_SLOTS
    }

    // ------------------------------------------------------------ разбор/сборка

    fun encode(b: Box): ByteArray {
        require(b.salt.size == VaultCrypto.SALT_LEN) { "bad salt" }
        require(b.slots.size <= MAX_SLOTS) { "too many slots" }
        require(b.slots.size % 2 == 0) { "slots must come in pairs" }
        require(b.slots.all { it.size == SLOT_LEN }) { "bad slot size" }

        val out = ByteArray(HEAD + b.slots.size * SLOT_LEN)
        out[0] = M0; out[1] = M1; out[2] = M2; out[3] = M3
        out[4] = (b.n ushr 24).toByte()
        out[5] = (b.n ushr 16).toByte()
        out[6] = (b.n ushr 8).toByte()
        out[7] = b.n.toByte()
        b.salt.copyInto(out, 8)
        out[8 + VaultCrypto.SALT_LEN] = b.slots.size.toByte()
        var o = HEAD
        for (s in b.slots) { s.copyInto(out, o); o += SLOT_LEN }
        return out
    }

    /** @return null при любой порче: обрезке, чужом файле, враньё в длине. */
    fun decode(raw: ByteArray): Box? {
        if (raw.size < HEAD) return null
        if (raw[0] != M0 || raw[1] != M1 || raw[2] != M2 || raw[3] != M3) return null
        val n = ((raw[4].toInt() and 0xFF) shl 24) or ((raw[5].toInt() and 0xFF) shl 16) or
                ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if (n < VaultCrypto.N_MIN || n > VaultCrypto.N_MAX || Integer.bitCount(n) != 1) return null
        val salt = raw.copyOfRange(8, 8 + VaultCrypto.SALT_LEN)
        val count = raw[8 + VaultCrypto.SALT_LEN].toInt() and 0xFF
        if (count == 0 || count > MAX_SLOTS || count % 2 != 0) return null
        if (raw.size != HEAD + count * SLOT_LEN) return null
        val slots = ArrayList<ByteArray>(count)
        var o = HEAD
        repeat(count) { slots.add(raw.copyOfRange(o, o + SLOT_LEN)); o += SLOT_LEN }
        return Box(n, salt, slots)
    }

    // -------------------------------------------------------------------- вход

    /**
     * Попытка входа. Один прогон scrypt, дальше примерка ко всем слотам.
     *
     * @return ключ данных подошедшего тайника, либо null.
     */
    fun open(box: Box, secret: CharArray): ByteArray? {
        val kek = Scrypt.derive(secret, box.salt, box.n, VaultCrypto.R, VaultCrypto.P,
            VaultCrypto.KEY_LEN)
        try {
            for (s in box.slots) {
                val k = VaultCrypto.decrypt(kek, s)
                if (k != null) return k
            }
            return null
        } finally {
            kek.fill(0)
        }
    }

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
            return Added(AddResult.OK, Box(box.n, box.salt, slots))
        } finally {
            dataKey.fill(0)
        }
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
