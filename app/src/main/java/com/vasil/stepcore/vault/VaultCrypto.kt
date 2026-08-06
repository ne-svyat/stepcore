package com.vasil.stepcore.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптографическое ядро Vault. Чистый Kotlin/JVM: ни одного Android-импорта,
 * поэтому проверяется через kotlinc ДО сборки приложения.
 *
 * ЗАМЫСЕЛ
 * -------
 * Пароль нигде не хранится — ни в открытом виде, ни хэшем для сравнения.
 * Из секрета выводится ключ, им расшифровывается ключ данных. Неверный
 * секрет = расшифровка не удалась. Сравнивать не с чем.
 *
 * ДВА КЛЮЧА К ОДНОМУ ЗАМКУ
 * ------------------------
 * dataKey генерируется случайно один раз и хранится ДВАЖДЫ завёрнутым:
 * паролем и фразой восстановления. Забыл пароль -> вошёл фразой -> задал
 * новый пароль. dataKey при этом не меняется, заметки остаются целы.
 * Поэтому оба враппера существуют с первого релиза: дописать второй позже
 * означало бы перешифровать весь накопленный архив.
 *
 * ПОЧЕМУ SCRYPT, А НЕ PBKDF2
 * --------------------------
 * PBKDF2 требует только процессорного времени, поэтому перебирается на
 * видеокартах тысячами потоков дёшево. Scrypt требует ПАМЯТИ на каждую
 * попытку (при N=2^15, r=8 это 32 МБ), и ферма GPU теряет смысл.
 *
 * ПОЧЕМУ НЕ ARGON2
 * ----------------
 * Argon2 сильнее, но в JDK его нет: нужна нативная библиотека в APK.
 * Приложение строит доверие на проверяемости, чужой бинарник этому
 * противоречит. Scrypt имеет тестовые векторы RFC 7914 — нашу реализацию
 * можно ДОКАЗАТЬ, а не принять на веру. Компромисс осознанный.
 *
 * ПОЧЕМУ НЕ ANDROID KEYSTORE
 * --------------------------
 * Keystore привязывает ключ к железу. Тогда зашифрованный экспорт нельзя
 * было бы открыть на новом телефоне после потери старого — обещание
 * "экспорт полезен с паролем" перестало бы выполняться. Отклонено.
 */
object VaultCrypto {

    // --- параметры scrypt ---------------------------------------------------
    // r и p фиксированы по RFC 7914 (рекомендация автора алгоритма).
    // N подбирается измерением на конкретном устройстве, см. calibrateN().
    const val R = 8
    const val P = 1
    const val KEY_LEN = 32          // AES-256
    const val SALT_LEN = 16
    const val NONCE_LEN = 12        // GCM: 96 бит — размер, для которого GCM спроектирован
    const val TAG_BITS = 128

    /** Нижняя граница N. 2^14 * 128 * 8 = 16 МБ: слабее не опускаемся никогда. */
    const val N_MIN = 1 shl 14
    /** Верхняя граница N. 2^17 = 128 МБ — больше телефон не переживёт. */
    const val N_MAX = 1 shl 17

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** Новый ключ данных. Генерируется один раз за всю жизнь хранилища. */
    fun newDataKey(): ByteArray = randomBytes(KEY_LEN)

    // --- обёртка ключа данных ----------------------------------------------

    /**
     * Ключ данных, завёрнутый одним секретом. Всё, кроме самого секрета,
     * хранится открыто — это нормально: соль и параметры не тайна.
     */
    data class Wrap(
        val n: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val blob: ByteArray,     // зашифрованный dataKey + тег GCM
    ) {
        /** salt(16) | nonce(12) | n(4, BE) | blob */
        fun toBytes(): ByteArray {
            val out = ByteArray(SALT_LEN + NONCE_LEN + 4 + blob.size)
            salt.copyInto(out, 0)
            nonce.copyInto(out, SALT_LEN)
            var o = SALT_LEN + NONCE_LEN
            out[o++] = (n ushr 24).toByte()
            out[o++] = (n ushr 16).toByte()
            out[o++] = (n ushr 8).toByte()
            out[o++] = n.toByte()
            blob.copyInto(out, o)
            return out
        }

        override fun equals(other: Any?): Boolean =
            other is Wrap && n == other.n && salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) && blob.contentEquals(other.blob)

        override fun hashCode(): Int = n * 31 + blob.contentHashCode()

        companion object {
            fun fromBytes(b: ByteArray): Wrap? {
                val head = SALT_LEN + NONCE_LEN + 4
                if (b.size <= head) return null
                val n = ((b[head - 4].toInt() and 0xFF) shl 24) or
                        ((b[head - 3].toInt() and 0xFF) shl 16) or
                        ((b[head - 2].toInt() and 0xFF) shl 8) or
                        (b[head - 1].toInt() and 0xFF)
                if (n < N_MIN || n > N_MAX || Integer.bitCount(n) != 1) return null
                return Wrap(
                    n = n,
                    salt = b.copyOfRange(0, SALT_LEN),
                    nonce = b.copyOfRange(SALT_LEN, SALT_LEN + NONCE_LEN),
                    blob = b.copyOfRange(head, b.size)
                )
            }
        }
    }

    /**
     * Завернуть ключ данных секретом.
     * @param secret пароль или фраза восстановления. Форма значения не имеет:
     *   слова, цифры, предложение — путь один и тот же.
     */
    fun wrap(dataKey: ByteArray, secret: CharArray, n: Int): Wrap {
        require(dataKey.size == KEY_LEN) { "dataKey must be $KEY_LEN bytes" }
        val salt = randomBytes(SALT_LEN)
        val nonce = randomBytes(NONCE_LEN)
        val kek = Scrypt.derive(secret, salt, n, R, P, KEY_LEN)
        try {
            val blob = gcm(Cipher.ENCRYPT_MODE, kek, nonce).doFinal(dataKey)
            return Wrap(n, salt, nonce, blob)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Развернуть ключ данных.
     * @return ключ данных, либо null если секрет неверен ИЛИ данные испорчены.
     *   Различать эти два случая мы не можем и не хотим: GCM даёт один ответ.
     */
    fun unwrap(w: Wrap, secret: CharArray): ByteArray? {
        val kek = Scrypt.derive(secret, w.salt, w.n, R, P, KEY_LEN)
        return try {
            gcm(Cipher.DECRYPT_MODE, kek, w.nonce).doFinal(w.blob)
        } catch (e: Exception) {
            null
        } finally {
            kek.fill(0)
        }
    }

    // --- шифрование самих заметок ------------------------------------------
    // dataKey уже случаен и полноценен, растягивать его не нужно: только AES.

    /** nonce(12) | шифротекст+тег. Новый nonce на КАЖДУЮ запись. */
    fun encrypt(dataKey: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE_LEN)
        val ct = gcm(Cipher.ENCRYPT_MODE, dataKey, nonce).doFinal(plain)
        val out = ByteArray(NONCE_LEN + ct.size)
        nonce.copyInto(out, 0)
        ct.copyInto(out, NONCE_LEN)
        return out
    }

    /** @return открытый текст, либо null при неверном ключе или порче байтов. */
    fun decrypt(dataKey: ByteArray, blob: ByteArray): ByteArray? {
        if (blob.size <= NONCE_LEN) return null
        return try {
            gcm(Cipher.DECRYPT_MODE, dataKey, blob.copyOfRange(0, NONCE_LEN))
                .doFinal(blob, NONCE_LEN, blob.size - NONCE_LEN)
        } catch (e: Exception) {
            null
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }

    // --- калибровка ---------------------------------------------------------

    /**
     * Подобрать N измерением, а не константой.
     *
     * Захардкоженное число через пять лет будет смешным, а на слабом телефоне
     * сегодня — неподъёмным. Меряем реальную скорость устройства и берём
     * наибольшее N, укладывающееся в бюджет ожидания.
     *
     * Бюджет задаётся длительностью анимации входа: пользователь смотрит на
     * открывающуюся дверь, а не на пустой экран. Ожидание, закрытое картинкой,
     * не воспринимается как ожидание.
     *
     * @param budgetMs сколько миллисекунд разрешено тратить на один вход.
     * @param clock источник времени (подменяется в тестах).
     */
    fun calibrateN(budgetMs: Long, clock: () -> Long = { System.currentTimeMillis() }): Int {
        val probeSalt = ByteArray(SALT_LEN)
        val probe = "calibration".toCharArray()
        var n = N_MIN
        var best = N_MIN
        while (n <= N_MAX) {
            val t0 = clock()
            Scrypt.derive(probe, probeSalt, n, R, P, KEY_LEN).fill(0)
            val dt = clock() - t0
            if (dt > budgetMs) break
            best = n
            // Следующая ступень стоит примерно вдвое дороже: если уже не влезаем
            // с запасом, дальше можно не мерить.
            if (dt * 2 > budgetMs) break
            n = n shl 1
        }
        return best
    }

    // --- требования к секрету ----------------------------------------------

    /** Минимум по NIST 800-63B: длина, и только длина. */
    const val MIN_SECRET_LEN = 8

    /**
     * Проверка секрета.
     *
     * Правил "обязательная заглавная и цифра" нет сознательно: они заставляют
     *человека придумывать Password1! вместо длинной осмысленной фразы и делают
     * пароли ХУЖЕ. Пробел — полноценный символ, края не обрезаются: пробел
     * в начале это часть секрета пользователя, а не мусор.
     *
     * @return null если всё в порядке, иначе текст претензии.
     */
    fun checkSecret(secret: CharArray): String? = when {
        secret.isEmpty() -> "Пусто"
        secret.size < MIN_SECRET_LEN -> "Нужно хотя бы $MIN_SECRET_LEN символов"
        secret.all { it == ' ' } -> "Одни пробелы — это не секрет"
        else -> null
    }
}

/**
 * Scrypt по RFC 7914. Реализация проверяется официальными тестовыми
 * векторами — см. VaultCryptoTest. Ни одной зависимости: PBKDF2-HMAC-SHA256
 * берётся из JDK, остальное — арифметика.
 *
 * Память: N * 128 * r байт. При N=2^15, r=8 это 32 МБ на попытку — ровно та
 * стоимость, которая делает перебор невыгодным.
 */
object Scrypt {

    fun derive(secret: CharArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        val pw = utf8(secret)
        try {
            return derive(pw, salt, n, r, p, dkLen)
        } finally {
            pw.fill(0)
        }
    }

    fun derive(pw: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && Integer.bitCount(n) == 1) { "N must be a power of 2 > 1" }
        require(r > 0 && p > 0) { "r,p must be positive" }

        val blockLen = 128 * r
        val b = pbkdf2(pw, salt, 1, blockLen * p)
        val v = ByteArray(blockLen * n)
        val xy = ByteArray(blockLen * 2)
        for (i in 0 until p) roMix(b, i * blockLen, r, n, v, xy)
        val out = pbkdf2(pw, b, 1, dkLen)
        b.fill(0); v.fill(0); xy.fill(0)
        return out
    }

    /** Явная кодировка секрета. Никаких догадок платформы. */
    private fun utf8(c: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(c))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
    }

    /**
     * PBKDF2-HMAC-SHA256, собственная сборка поверх javax.crypto.Mac.
     *
     * Почему не SecretKeyFactory("PBKDF2WithHmacSHA256"): он принимает пароль
     * как CharArray и САМ решает, в какие байты его превратить. На разных
     * реализациях (JDK против Conscrypt на Android) это решение исторически
     * различалось, а ещё он отказывается работать с пустым паролем. Мы явно
     * кодируем секрет в UTF-8 и полностью контролируем результат — иначе
     * тестовые векторы, прошедшие в песочнице, ничего не доказывали бы
     * про телефон.
     */
    private fun pbkdf2(pw: ByteArray, salt: ByteArray, iter: Int, len: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pw, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(len)
        var done = 0
        var block = 1
        while (done < len) {
            mac.update(salt)
            mac.update(byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(),
                (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iter) {
                u = mac.doFinal(u)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val take = minOf(hLen, len - done)
            t.copyInto(out, done, 0, take)
            done += take
            block++
        }
        return out
    }

    /** ROMix: заполняем V последовательно, потом N раз прыгаем по нему псевдослучайно. */
    private fun roMix(b: ByteArray, off: Int, r: Int, n: Int, v: ByteArray, xy: ByteArray) {
        val blockLen = 128 * r
        b.copyInto(xy, 0, off, off + blockLen)

        for (i in 0 until n) {
            xy.copyInto(v, i * blockLen, 0, blockLen)
            blockMix(xy, r)
        }
        for (i in 0 until n) {
            val j = integerify(xy, r) and (n - 1)
            val base = j * blockLen
            for (k in 0 until blockLen) xy[k] = (xy[k].toInt() xor v[base + k].toInt()).toByte()
            blockMix(xy, r)
        }
        xy.copyInto(b, off, 0, blockLen)
    }

    /** Последнее 64-байтовое подблок-слово как целое (little-endian, младшие 32 бита). */
    private fun integerify(xy: ByteArray, r: Int): Int {
        val o = (2 * r - 1) * 64
        return (xy[o].toInt() and 0xFF) or
                ((xy[o + 1].toInt() and 0xFF) shl 8) or
                ((xy[o + 2].toInt() and 0xFF) shl 16) or
                ((xy[o + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * BlockMix: X = B[2r-1]; на каждом шаге X = Salsa20/8(X xor B[i]).
     * Результат перетасован: сначала чётные, потом нечётные.
     */
    private fun blockMix(xy: ByteArray, r: Int) {
        val y = ByteArray(128 * r)
        val x = ByteArray(64)
        xy.copyInto(x, 0, (2 * r - 1) * 64, 2 * r * 64)
        for (i in 0 until 2 * r) {
            for (k in 0 until 64) x[k] = (x[k].toInt() xor xy[i * 64 + k].toInt()).toByte()
            salsa20_8(x)
            val dst = if (i % 2 == 0) (i / 2) * 64 else (r + i / 2) * 64
            x.copyInto(y, dst, 0, 64)
        }
        y.copyInto(xy, 0, 0, 128 * r)
    }

    /** Salsa20/8 core: 8 раундов над 16 словами little-endian. */
    private fun salsa20_8(block: ByteArray) {
        val x = IntArray(16)
        for (i in 0 until 16) {
            x[i] = (block[i * 4].toInt() and 0xFF) or
                    ((block[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((block[i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((block[i * 4 + 3].toInt() and 0xFF) shl 24)
        }
        val w = x.copyOf()
        var i = 0
        while (i < 8) {
            w[4] = w[4] xor rot(w[0] + w[12], 7);   w[8] = w[8] xor rot(w[4] + w[0], 9)
            w[12] = w[12] xor rot(w[8] + w[4], 13); w[0] = w[0] xor rot(w[12] + w[8], 18)
            w[9] = w[9] xor rot(w[5] + w[1], 7);    w[13] = w[13] xor rot(w[9] + w[5], 9)
            w[1] = w[1] xor rot(w[13] + w[9], 13);  w[5] = w[5] xor rot(w[1] + w[13], 18)
            w[14] = w[14] xor rot(w[10] + w[6], 7); w[2] = w[2] xor rot(w[14] + w[10], 9)
            w[6] = w[6] xor rot(w[2] + w[14], 13);  w[10] = w[10] xor rot(w[6] + w[2], 18)
            w[3] = w[3] xor rot(w[15] + w[11], 7);  w[7] = w[7] xor rot(w[3] + w[15], 9)
            w[11] = w[11] xor rot(w[7] + w[3], 13); w[15] = w[15] xor rot(w[11] + w[7], 18)

            w[1] = w[1] xor rot(w[0] + w[3], 7);    w[2] = w[2] xor rot(w[1] + w[0], 9)
            w[3] = w[3] xor rot(w[2] + w[1], 13);   w[0] = w[0] xor rot(w[3] + w[2], 18)
            w[6] = w[6] xor rot(w[5] + w[4], 7);    w[7] = w[7] xor rot(w[6] + w[5], 9)
            w[4] = w[4] xor rot(w[7] + w[6], 13);   w[5] = w[5] xor rot(w[4] + w[7], 18)
            w[11] = w[11] xor rot(w[10] + w[9], 7); w[8] = w[8] xor rot(w[11] + w[10], 9)
            w[9] = w[9] xor rot(w[8] + w[11], 13);  w[10] = w[10] xor rot(w[9] + w[8], 18)
            w[12] = w[12] xor rot(w[15] + w[14], 7);w[13] = w[13] xor rot(w[12] + w[15], 9)
            w[14] = w[14] xor rot(w[13] + w[12], 13);w[15] = w[15] xor rot(w[14] + w[13], 18)
            i += 2
        }
        for (k in 0 until 16) {
            val v = w[k] + x[k]
            block[k * 4] = v.toByte()
            block[k * 4 + 1] = (v ushr 8).toByte()
            block[k * 4 + 2] = (v ushr 16).toByte()
            block[k * 4 + 3] = (v ushr 24).toByte()
        }
    }

    private fun rot(a: Int, b: Int): Int = (a shl b) or (a ushr (32 - b))
}
