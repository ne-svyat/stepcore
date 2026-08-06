package com.vasil.stepcore.vault

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Хранилище заметок Vault. Отдельная база vault.db, отдельная от stepcore.db.
 *
 * ПОЧЕМУ НЕТ КОЛОНКИ "ЧЕЙ ЭТО ТАЙНИК"
 * -----------------------------------
 * Все тайники пишут в одни и те же таблицы. Принадлежность строки
 * определяется тем, расшифровалась она твоим ключом или нет. Колонка
 * vaultId выдала бы, сколько заметок в каждом тайнике, вообще их не
 * открывая — а это ровно то, что мы обещали не выдавать.
 *
 * ПОЧЕМУ ТЕКСТ ЛЕЖИТ ПОСТРАНИЧНО
 * ------------------------------
 * Тысяча страниц по десять тысяч символов — десять мегабайт на одну заметку.
 * Держать это в памяти нельзя. Страница — отдельная строка, читается ровно
 * та, что открыта. Заметка знает только свой заголовок и число страниц.
 *
 * ЧТО ЛЕЖИТ ОТКРЫТО
 * -----------------
 * Идентификаторы, номера страниц, времена изменения и число страниц. Это
 * цена за возможность листать, не расшифровывая всё подряд. Содержимое и
 * заголовки — только шифротекст.
 */
@Entity(tableName = "v_notes")
data class VNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdMs: Long,
    val updatedMs: Long,
    val pageCount: Int,
    val title: ByteArray,      // шифротекст
)

@Entity(tableName = "v_pages", primaryKeys = ["noteId", "idx"])
data class VPage(
    val noteId: Long,
    val idx: Int,              // 0-based номер страницы
    val updatedMs: Long,
    val body: ByteArray,       // шифротекст
)

@Dao
interface VaultDao {

    @Query("SELECT * FROM v_notes ORDER BY updatedMs DESC")
    suspend fun allNotes(): List<VNote>

    @Insert
    suspend fun insertNote(n: VNote): Long

    @Query("UPDATE v_notes SET title = :title, updatedMs = :ms WHERE id = :id")
    suspend fun renameNote(id: Long, title: ByteArray, ms: Long)

    @Query("UPDATE v_notes SET pageCount = :count, updatedMs = :ms WHERE id = :id")
    suspend fun setPageCount(id: Long, count: Int, ms: Long)

    @Query("SELECT * FROM v_notes WHERE id = :id")
    suspend fun note(id: Long): VNote?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPage(p: VPage)

    @Query("SELECT * FROM v_pages WHERE noteId = :noteId AND idx = :idx")
    suspend fun page(noteId: Long, idx: Int): VPage?
}

@Database(entities = [VNote::class, VPage::class], version = 1, exportSchema = false)
abstract class VaultDb : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
        @Volatile private var instance: VaultDb? = null
        fun get(context: Context): VaultDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, VaultDb::class.java, "vault.db"
                ).build().also { instance = it }
            }
    }
}

/**
 * Слой шифрования между экраном и базой. Наружу отдаёт обычный текст,
 * внутрь кладёт только шифротекст.
 *
 * Ключ передаётся в конструктор и не хранится в поле дольше жизни экрана:
 * репозиторий создаётся при входе и умирает вместе с сессией.
 */
class VaultRepo(context: Context, private val dataKey: ByteArray) {

    private val dao = VaultDb.get(context).dao()

    /** Заметка в разобранном виде. Текст страниц сюда не попадает. */
    class NoteHead(val id: Long, val title: String, val pageCount: Int, val updatedMs: Long)

    /**
     * Список заметок ЭТОГО тайника.
     *
     * Строки чужих тайников не расшифровываются и просто не попадают в
     * список. Это не фильтр по признаку, а невозможность прочитать.
     */
    suspend fun notes(): List<NoteHead> =
        dao.allNotes().mapNotNull { n ->
            val t = VaultCrypto.decrypt(dataKey, n.title)?.toString(Charsets.UTF_8)
            if (t == null) null else NoteHead(n.id, t, n.pageCount, n.updatedMs)
        }

    suspend fun createNote(title: String): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertNote(VNote(
            createdMs = now, updatedMs = now, pageCount = 1,
            title = VaultCrypto.encrypt(dataKey, title.toByteArray())
        ))
        dao.putPage(VPage(id, 0, now, VaultCrypto.encrypt(dataKey, ByteArray(0))))
        return id
    }

    suspend fun rename(id: Long, title: String) {
        dao.renameNote(id, VaultCrypto.encrypt(dataKey, title.toByteArray()),
            System.currentTimeMillis())
    }

    suspend fun pageCount(id: Long): Int = dao.note(id)?.pageCount ?: 0

    /**
     * Текст страницы.
     *
     * @return null если страницы нет ИЛИ она принадлежит другому тайнику.
     *   Различать эти случаи мы не можем и не хотим.
     */
    suspend fun readPage(noteId: Long, idx: Int): String? {
        val p = dao.page(noteId, idx) ?: return null
        return VaultCrypto.decrypt(dataKey, p.body)?.toString(Charsets.UTF_8)
    }

    suspend fun writePage(noteId: Long, idx: Int, text: String) {
        require(text.length <= MAX_PAGE_CHARS) { "страница длиннее предела" }
        val now = System.currentTimeMillis()
        dao.putPage(VPage(noteId, idx, now, VaultCrypto.encrypt(dataKey, text.toByteArray())))
        val n = dao.note(noteId) ?: return
        val count = maxOf(n.pageCount, idx + 1)
        dao.setPageCount(noteId, count, now)
    }

    /** @return номер новой страницы, либо -1 если упёрлись в предел. */
    suspend fun addPage(noteId: Long): Int {
        val n = dao.note(noteId) ?: return -1
        if (n.pageCount >= MAX_PAGES) return -1
        val idx = n.pageCount
        writePage(noteId, idx, "")
        return idx
    }

    companion object {
        /** Предел на страницу. Дальше — следующая страница, а не отказ. */
        const val MAX_PAGE_CHARS = 10_000
        /** Предел страниц в одной заметке. */
        const val MAX_PAGES = 1000
    }
}
