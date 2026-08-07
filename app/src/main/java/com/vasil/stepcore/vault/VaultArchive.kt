package com.vasil.stepcore.vault

/**
 * Архив заметок Vault. Чистый Kotlin, проверяется тестами до сборки.
 *
 * ГЛАВНОЕ ОБЕЩАНИЕ
 * ----------------
 * Файл выгружается ТОЛЬКО зашифрованным. Без ключа он бесполезен - это
 * проверяемое свойство, а не заверение: в файле нет ни одного открытого
 * байта текста, и тесты это проверяют прямым поиском.
 *
 * АРХИВ VAULT И БЭКАП STEPCORE - РАЗНЫЕ ВЕЩИ
 * ------------------------------------------
 * Общий бэкап приложения заметок НЕ содержит и содержать не должен: иначе
 * пароль тайника обходился бы через экспорт шагомера. Значит переносить
 * их надо по отдельности, и об этом человеку говорится прямым текстом на
 * экране, а не мелким шрифтом.
 *
 * ДВА СПОСОБА ЗАКРЫТЬ АРХИВ
 * -------------------------
 * BY_VAULT - ключом самого тайника. Ничего вводить не надо, но открыть
 *   такой файл можно только в ТОМ ЖЕ тайнике.
 * BY_PASSWORD - отдельным паролем, заданным при выгрузке. Файл можно
 *   открыть в любом тайнике и на другом устройстве.
 *
 * Защита от ошибки здесь не галочка "я понял" - её прокликивают не читая.
 * Способы РАЗЛИЧАЮТСЯ поведением: второй требует ввести пароль дважды и
 * отличный от пароля тайника. Разное поведение запоминается, ритуал - нет.
 */
object VaultArchive {

    private const val M0 = 'S'.code.toByte()
    private const val M1 = 'C'.code.toByte()
    private const val M2 = 'V'.code.toByte()
    private const val M3 = 'A'.code.toByte()

    /** Формат заголовка. Меняется только вместе с версией. */
    const val VERSION = 1

    enum class Lock { BY_VAULT, BY_PASSWORD }

    /**
     * Потолок страниц при чтении архива. Продублирован здесь намеренно:
     * движок архива обязан оставаться чистым Kotlin и проверяться без
     * Android, а VaultRepo тянет базу. Значение сверяется валидатором.
     */
    const val MAX_PAGES = 1000

    /** Одна заметка целиком: заголовок, классы, все страницы. */
    class Note(val title: String, val tags: List<String>, val pages: List<String>)

    class Archive(val madeAtMs: Long, val notes: List<Note>)

    // ------------------------------------------------------- сериализация

    private fun putInt(out: ArrayList<Byte>, v: Int) {
        out.add((v ushr 24).toByte()); out.add((v ushr 16).toByte())
        out.add((v ushr 8).toByte()); out.add(v.toByte())
    }

    private fun putLong(out: ArrayList<Byte>, v: Long) {
        for (i in 7 downTo 0) out.add((v ushr (i * 8)).toByte())
    }

    private fun putStr(out: ArrayList<Byte>, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        putInt(out, b.size)
        for (x in b) out.add(x)
    }

    /** Разобранный вид архива в байты. Без шифрования: см. seal. */
    fun encode(a: Archive): ByteArray {
        val out = ArrayList<Byte>()
        putInt(out, VERSION)
        putLong(out, a.madeAtMs)
        putInt(out, a.notes.size)
        for (n in a.notes) {
            putStr(out, n.title)
            putInt(out, n.tags.size)
            for (t in n.tags) putStr(out, t)
            putInt(out, n.pages.size)
            for (p in n.pages) putStr(out, p)
        }
        return out.toByteArray()
    }

    private class Reader(val b: ByteArray) {
        var i = 0
        fun int(): Int {
            need(4)
            val v = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
                    ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            i += 4
            return v
        }
        fun long(): Long {
            need(8)
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
            i += 8
            return v
        }
        fun str(): String {
            val n = int()
            // Длина из файла НЕ доверенная: испорченный архив мог бы
            // попросить гигабайт или отрицательное число.
            require(n >= 0 && n <= b.size - i) { "bad length" }
            val s = String(b, i, n, Charsets.UTF_8)
            i += n
            return s
        }
        fun need(n: Int) = require(i + n <= b.size) { "truncated" }
    }

    /** @return null при любой порче: обрезке, вранье в длинах, чужой версии. */
    fun decode(raw: ByteArray): Archive? = try {
        val r = Reader(raw)
        val ver = r.int()
        if (ver != VERSION) null else {
            val made = r.long()
            val count = r.int()
            require(count >= 0 && count <= 100_000) { "bad count" }
            val notes = ArrayList<Note>(minOf(count, 1024))
            repeat(count) {
                val title = r.str()
                val tagsN = r.int()
                require(tagsN >= 0 && tagsN <= 1000) { "bad tags" }
                val tags = ArrayList<String>(minOf(tagsN, 64))
                repeat(tagsN) { tags.add(r.str()) }
                val pagesN = r.int()
                require(pagesN >= 0 && pagesN <= MAX_PAGES) { "bad pages" }
                val pages = ArrayList<String>(minOf(pagesN, 64))
                repeat(pagesN) { pages.add(r.str()) }
                notes.add(Note(title, tags, pages))
            }
            Archive(made, notes)
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------- шифрование

    /**
     * Раскладка файла:
     * "SCVA" | замок (1) | соль (16) | N (4) | шифротекст
     *
     * Соль и N нужны только для BY_PASSWORD, но пишутся всегда: файл
     * одинакового вида не сообщает посторонним, каким способом закрыт.
     */
    private const val HEAD = 4 + 1 + VaultCrypto.SALT_LEN + 4

    fun seal(a: Archive, lock: Lock, key: ByteArray, n: Int, salt: ByteArray): ByteArray {
        require(salt.size == VaultCrypto.SALT_LEN) { "bad salt" }
        val body = VaultCrypto.encrypt(key, encode(a))
        val out = ByteArray(HEAD + body.size)
        out[0] = M0; out[1] = M1; out[2] = M2; out[3] = M3
        out[4] = (if (lock == Lock.BY_VAULT) 0 else 1).toByte()
        salt.copyInto(out, 5)
        var o = 5 + VaultCrypto.SALT_LEN
        out[o++] = (n ushr 24).toByte(); out[o++] = (n ushr 16).toByte()
        out[o++] = (n ushr 8).toByte(); out[o] = n.toByte()
        body.copyInto(out, HEAD)
        return out
    }

    /** Заголовок файла: чем закрыт и с какими параметрами. */
    class Head(val lock: Lock, val salt: ByteArray, val n: Int, val body: ByteArray)

    fun head(raw: ByteArray): Head? {
        if (raw.size <= HEAD) return null
        if (raw[0] != M0 || raw[1] != M1 || raw[2] != M2 || raw[3] != M3) return null
        val lock = when (raw[4].toInt()) {
            0 -> Lock.BY_VAULT
            1 -> Lock.BY_PASSWORD
            else -> return null
        }
        val salt = raw.copyOfRange(5, 5 + VaultCrypto.SALT_LEN)
        val o = 5 + VaultCrypto.SALT_LEN
        val n = ((raw[o].toInt() and 0xFF) shl 24) or ((raw[o + 1].toInt() and 0xFF) shl 16) or
                ((raw[o + 2].toInt() and 0xFF) shl 8) or (raw[o + 3].toInt() and 0xFF)
        if (n < VaultCrypto.N_MIN || n > VaultCrypto.N_MAX || Integer.bitCount(n) != 1) return null
        return Head(lock, salt, n, raw.copyOfRange(HEAD, raw.size))
    }

    /** @return архив, либо null если ключ не тот или файл испорчен. */
    fun open(h: Head, key: ByteArray): Archive? {
        val plain = VaultCrypto.decrypt(key, h.body) ?: return null
        return decode(plain)
    }

    /** Ключ файла, закрытого своим паролем. */
    fun keyFromPassword(secret: CharArray, salt: ByteArray, n: Int): ByteArray =
        Scrypt.derive(secret, salt, n, VaultCrypto.R, VaultCrypto.P, VaultCrypto.KEY_LEN)
}
