package com.vasil.stepcore.vault

import android.content.Context
import androidx.room.withTransaction
import java.io.File

/**
 * Вычистка всего, что открывается одним ключом данных.
 *
 * ПОЧЕМУ ПРОБНОЕ РАСШИФРОВАНИЕ, А НЕ КОЛОНКА
 * ------------------------------------------
 * Колонка «чей это тайник» решила бы задачу одним DELETE, но выдала бы
 * число заметок в каждом тайнике, вообще его не открывая. Это ровно то,
 * чего модуль обещал не выдавать. Поэтому принадлежность определяется
 * так же, как везде: строка наша, если расшифровалась нашим ключом.
 *
 * Тег GCM работает опознавательным знаком. Совпадение подделать нельзя,
 * ложных срабатываний не бывает: чужая строка не расшифруется никогда.
 *
 * ПОЧЕМУ ЧИСТКА КОНСЕРВАТИВНА
 * ---------------------------
 * Испорченная строка не расшифруется и останется лежать. Это осознанный
 * выбор: лучше оставить сироту, чем удалить чужое. Порча — редкий случай,
 * а удаление чужих заметок необратимо.
 *
 * ПОЧЕМУ КАРТИНКИ ЧИТАЮТСЯ ЦЕЛИКОМ
 * --------------------------------
 * Опознать файл по началу нельзя: тег GCM лежит в конце, и проверка
 * подлинности требует всего шифротекста. Файлы читаются по одному и
 * сразу отпускаются — предел размера картинки уже задан в VaultImages.
 *
 * ПОЧЕМУ МЕТКИ [img:id] В ТЕКСТЕ НЕ РАЗБИРАЮТСЯ
 * ---------------------------------------------
 * Разбор потребовал бы расшифровать весь корпус ради списка меток, и
 * любая метка, потерянная при правке текста, оставила бы файл навсегда.
 * Владение файлом определяется тем же ключом, что и владение строкой.
 */
class VaultPurge(private val context: Context, private val dataKey: ByteArray) {

    /** Что было стёрто. Показывается человеку: удаление вслепую пугает. */
    class Report(val notes: Int, val pages: Int, val images: Int)

    /**
     * Строки — одной транзакцией, файлы — после неё.
     *
     * Транзакция на всю чистку, а не на заметку: половина удалённого
     * тайника — состояние, которого не должно существовать. Обрыв
     * откатывает базу целиком, а ключ к этому моменту ещё жив, и
     * удаление можно просто повторить.
     */
    suspend fun purge(): Report {
        val db = VaultDb.get(context)
        val dao = db.dao()
        var notes = 0
        var pages = 0
        db.withTransaction {
            for (n in dao.allNotes()) {
                val title = VaultCrypto.decrypt(dataKey, n.title) ?: continue
                title.fill(0)
                dao.dropHist(n.id)
                dao.dropPages(n.id)
                dao.dropNote(n.id)
                notes++
                pages += n.pageCount
            }
        }
        return Report(notes, pages, purgeImages())
    }

    private fun purgeImages(): Int {
        val dir = File(context.filesDir, "vault/img")
        val files = dir.listFiles() ?: return 0
        var gone = 0
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(".bin")) continue
            val plain = try {
                VaultCrypto.decrypt(dataKey, f.readBytes())
            } catch (e: Exception) {
                null
            } ?: continue
            plain.fill(0)
            if (f.delete()) gone++
        }
        return gone
    }
}
