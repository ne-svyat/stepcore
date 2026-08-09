package com.vasil.stepcore.vault

/**
 * Разделение секрета по Шамиру над GF(256). Чистый Kotlin, ноль
 * зависимостей, проверяется тестами до сборки.
 *
 * ЗАМЫСЕЛ
 * -------
 * Секрет превращается в N частей так, что любые K из них восстанавливают
 * его точно, а любые K-1 не дают о нём НИЧЕГО. Это не «часть пароля»:
 * K-1 частей математически неотличимы от случайного шума.
 *
 * ПОЧЕМУ GF(256), А НЕ БОЛЬШИЕ ЧИСЛА
 * ----------------------------------
 * Байт секрета - точка в поле из 256 элементов, и часть весит ровно
 * столько же, сколько секрет. Схемы на больших простых раздувают части
 * втрое и требуют длинной арифметики.
 *
 * ЧЕГО СХЕМА НЕ ДЕЛАЕТ
 * --------------------
 * Не проверяет честность держателей: подменённая часть даст неверный
 * секрет, и узнать, кто её испортил, нельзя. Против опечаток стоит
 * контрольная сумма в каждой части, против злого умысла - ничего.
 */
object VaultShamir {

    private const val VERSION = 1

    /** Умножение в поле AES: тот же неприводимый многочлен 0x11B. */
    private fun mul(a: Int, b: Int): Int {
        var x = a; var y = b; var r = 0
        while (y != 0) {
            if (y and 1 != 0) r = r xor x
            val hi = x and 0x80
            x = (x shl 1) and 0xFF
            if (hi != 0) x = x xor 0x1B
            y = y shr 1
        }
        return r
    }

    private fun pow(a: Int, n: Int): Int {
        var r = 1; var base = a; var e = n
        while (e > 0) {
            if (e and 1 != 0) r = mul(r, base)
            base = mul(base, base)
            e = e shr 1
        }
        return r
    }

    /** Обратный элемент: a^254 = a^-1 в GF(256). */
    private fun inv(a: Int): Int {
        require(a != 0) { "нет обратного к нулю" }
        return pow(a, 254)
    }

    /**
     * Разделить.
     *
     * @param secret что делим
     * @param n сколько частей выдать
     * @param k сколько частей нужно для сборки
     * @param rnd источник случайности для коэффициентов многочлена
     */
    fun split(secret: ByteArray, n: Int, k: Int, rnd: (Int) -> ByteArray): List<ByteArray> {
        require(k in 2..n) { "нужно 2 <= k <= n" }
        require(n in 2..16) { "частей от 2 до 16" }
        require(secret.isNotEmpty()) { "пустой секрет" }

        val out = List(n) { ByteArray(secret.size) }
        // Коэффициенты берём разом: один вызов источника вместо тысячи.
        val coef = rnd(secret.size * (k - 1))
        for (b in secret.indices) {
            for (i in 0 until n) {
                val x = i + 1               // x=0 отдал бы сам секрет
                var acc = secret[b].toInt() and 0xFF
                var xp = 1
                for (d in 1 until k) {
                    xp = mul(xp, x)
                    val c = coef[b * (k - 1) + (d - 1)].toInt() and 0xFF
                    acc = acc xor mul(c, xp)
                }
                out[i][b] = acc.toByte()
            }
        }
        return out
    }

    /**
     * Собрать. Интерполяция Лагранжа в точке x=0.
     *
     * @param parts пары «номер части (1..n) - её байты»
     */
    fun combine(parts: List<Pair<Int, ByteArray>>): ByteArray? {
        if (parts.size < 2) return null
        val len = parts[0].second.size
        if (parts.any { it.second.size != len }) return null
        if (parts.map { it.first }.toSet().size != parts.size) return null
        if (parts.any { it.first < 1 || it.first > 255 }) return null

        val out = ByteArray(len)
        for (b in 0 until len) {
            var acc = 0
            for ((i, pi) in parts.withIndex()) {
                var num = 1
                var den = 1
                for ((j, pj) in parts.withIndex()) {
                    if (i == j) continue
                    num = mul(num, pj.first)
                    den = mul(den, pi.first xor pj.first)
                }
                acc = acc xor mul(pi.second[b].toInt() and 0xFF, mul(num, inv(den)))
            }
            out[b] = acc.toByte()
        }
        return out
    }

    // ------------------------------------------------------------ запись

    /**
     * Алфавит без похожих знаков: нет I, L, O, U и нет цифр 0 и 1.
     * Часть переписывают с бумаги от руки, и «ноль или О» - самая частая
     * ошибка, которую нельзя допускать в принципе.
     */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BASE = 30

    /** Заголовок: версия, k, номер части, метка набора, длина. */
    private const val HEAD = 1 + 1 + 1 + 2 + 1

    fun encodeShare(idx: Int, k: Int, setId: Int, data: ByteArray): String {
        require(idx in 1..16)
        val body = ByteArray(HEAD + data.size)
        body[0] = VERSION.toByte()
        body[1] = k.toByte()
        body[2] = idx.toByte()
        body[3] = (setId ushr 8).toByte()
        body[4] = setId.toByte()
        body[5] = data.size.toByte()
        data.copyInto(body, HEAD)
        val crc = crc16(body)
        return group(toBase(body + byteArrayOf((crc ushr 8).toByte(), crc.toByte())))
    }

    class Share(val idx: Int, val k: Int, val setId: Int, val data: ByteArray)

    /** @return null при опечатке, обрезке или чужом формате. */
    fun decodeShare(text: String): Share? {
        val full = fromBase(text.filter { it != '-' && it != ' ' }.uppercase()) ?: return null
        if (full.size < HEAD + 2 + 1) return null
        val body = full.copyOfRange(0, full.size - 2)
        val want = ((full[full.size - 2].toInt() and 0xFF) shl 8) or
                (full[full.size - 1].toInt() and 0xFF)
        if (crc16(body) != want) return null
        if ((body[0].toInt() and 0xFF) != VERSION) return null
        val k = body[1].toInt() and 0xFF
        val idx = body[2].toInt() and 0xFF
        val setId = ((body[3].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
        val len = body[5].toInt() and 0xFF
        if (k < 2 || idx < 1 || len == 0) return null
        if (body.size != HEAD + len) return null
        return Share(idx, k, setId, body.copyOfRange(HEAD, HEAD + len))
    }

    /** Секрет как строка того же алфавита: её можно записать и целиком. */
    fun secretToText(secret: ByteArray): String = group(toBase(secret))

    fun textToSecret(text: String): ByteArray? =
        fromBase(text.filter { it != '-' && it != ' ' }.uppercase())

    // ------------------------------------------------------- внутреннее

    private fun group(s: String): String = s.chunked(4).joinToString("-")

    /** Перевод в систему по основанию 30 длинным делением. */
    private fun toBase(bytes: ByteArray): String {
        val digits = ArrayList<Int>()
        val work = bytes.map { it.toInt() and 0xFF }.toIntArray()
        var start = 0
        while (start < work.size) {
            var rem = 0
            var allZero = true
            for (i in start until work.size) {
                val cur = rem * 256 + work[i]
                work[i] = cur / BASE
                rem = cur % BASE
                if (work[i] != 0) allZero = false
            }
            digits.add(rem)
            if (allZero) start = work.size
        }
        // Ведущий нулевой байт не теряется: каждый даёт свой знак.
        var lead = 0
        while (lead < bytes.size && bytes[lead].toInt() == 0) lead++
        val sb = StringBuilder()
        repeat(lead) { sb.append(ALPHABET[0]) }
        for (i in digits.indices.reversed()) sb.append(ALPHABET[digits[i]])
        return sb.toString()
    }

    private fun fromBase(text: String): ByteArray? {
        if (text.isEmpty()) return null
        var lead = 0
        while (lead < text.length && text[lead] == ALPHABET[0]) lead++
        val digits = ArrayList<Int>()
        for (c in text) {
            val d = ALPHABET.indexOf(c)
            if (d < 0) return null
            digits.add(d)
        }
        val bytes = ArrayList<Int>()
        val work = digits.toIntArray()
        var start = 0
        while (start < work.size) {
            var rem = 0
            var allZero = true
            for (i in start until work.size) {
                val cur = rem * BASE + work[i]
                work[i] = cur / 256
                rem = cur % 256
                if (work[i] != 0) allZero = false
            }
            bytes.add(rem)
            if (allZero) start = work.size
        }
        val out = ByteArray(lead + bytes.size)
        for (i in bytes.indices) out[lead + i] = bytes[bytes.size - 1 - i].toByte()
        return out
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                      else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }
}
