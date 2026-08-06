package com.vasil.stepcore.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Картинки заметок: зашифрованные файлы рядом с ключами.
 *
 * ПОЧЕМУ НЕ В БАЗЕ
 * ----------------
 * Картинка в 300 КБ внутри строки таблицы заставляет SQLite таскать её при
 * любом чтении строки. Файл читается ровно тогда, когда его показывают.
 *
 * ПОЧЕМУ НЕ ВНУТРИ ТЕКСТА СТРАНИЦЫ
 * --------------------------------
 * Предел в десять тысяч символов задуман для ТЕКСТА. Три фотографии в
 * base64 съели бы его целиком, и человек упёрся бы в предел, написав
 * полстраницы. В тексте стоит только метка [img:id].
 *
 * ПОЧЕМУ КАРТИНКА УМЕНЬШАЕТСЯ
 * ---------------------------
 * Снимок с камеры этого телефона — 4000 пикселей и 5 МБ. На экране шириной
 * 1220 пикселей разница не видна, а расшифровывать и держать в памяти
 * приходится всё. Уменьшение до 1600 по длинной стороне — предел, за
 * которым качество ещё не теряется, а вес падает в разы.
 */
class VaultImages(private val context: Context, private val dataKey: ByteArray) {

    private val dir = File(context.filesDir, "vault/img")

    /**
     * Взять картинку у системы, уменьшить, зашифровать, положить.
     * @return идентификатор для метки в тексте, либо null при неудаче.
     */
    fun store(uri: Uri): String? {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            val jpeg = shrink(raw) ?: return null
            if (jpeg.size > MAX_BYTES) return null
            dir.mkdirs()
            val id = newId()
            val tmp = File(dir, "$id.tmp")
            tmp.outputStream().use { out ->
                out.write(VaultCrypto.encrypt(dataKey, jpeg))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(File(dir, "$id.bin"))) return null
            id
        } catch (e: Exception) {
            null
        }
    }

    /** @return картинка, либо null если её нет или она из другого тайника. */
    fun load(id: String): Bitmap? {
        if (!safeId(id)) return null
        val f = File(dir, "$id.bin")
        if (!f.isFile) return null
        return try {
            val plain = VaultCrypto.decrypt(dataKey, f.readBytes()) ?: return null
            BitmapFactory.decodeByteArray(plain, 0, plain.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Удалить картинку насовсем.
     *
     * Правило "не удалять - помечать" здесь не действует: Vault - то самое
     * место, где человек имеет право стереть своё по-настоящему, и обещание
     * должно выполняться буквально.
     */
    fun delete(id: String) {
        if (!safeId(id)) return
        File(dir, "$id.bin").delete()
    }

    private fun newId(): String {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Только буквы и цифры: идентификатор попадает в имя файла. */
    private fun safeId(id: String) =
        id.isNotEmpty() && id.length <= 32 && id.all { it.isLetterOrDigit() }

    private fun shrink(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > MAX_SIDE * 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale >= 1f) bmp else Bitmap.createScaledBitmap(
            bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos)
        return bos.toByteArray()
    }

    companion object {
        const val MAX_SIDE = 2000
        const val QUALITY = 85
        /** Потолок на одну картинку после сжатия. */
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
