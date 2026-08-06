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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    // Теги заметки одной строкой, шифротекстом. Отдельной таблицей их
    // держать незачем: по зашифрованному тегу всё равно нельзя сделать
    // WHERE, а расшифровывать пришлось бы то же самое.
    val tags: ByteArray = ByteArray(0),
)

@Entity(tableName = "v_pages", primaryKeys = ["noteId", "idx"])
data class VPage(
    val noteId: Long,
    val idx: Int,              // 0-based номер страницы
    val updatedMs: Long,
    val body: ByteArray,       // шифротекст
    // Три частых слова страницы, шифротекстом. Считаются при сохранении,
    // чтобы список страниц не расшифровывал весь текст ради подписи.
    val words: ByteArray = ByteArray(0),
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

    @Query("UPDATE v_notes SET tags = :tags, updatedMs = :ms WHERE id = :id")
    suspend fun setTags(id: Long, tags: ByteArray, ms: Long)

    /**
     * Страницы куском. Поиск обязан читать порциями: тысяча страниц по
     * десять тысяч символов разом в память не поместится.
     */
    @Query("SELECT * FROM v_pages WHERE noteId = :noteId AND idx >= :from AND idx < :to ORDER BY idx")
    suspend fun pagesRange(noteId: Long, from: Int, to: Int): List<VPage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPage(p: VPage)

    @Query("SELECT * FROM v_pages WHERE noteId = :noteId AND idx = :idx")
    suspend fun page(noteId: Long, idx: Int): VPage?

    @Query("DELETE FROM v_pages WHERE noteId = :noteId")
    suspend fun dropPages(noteId: Long)

    @Query("DELETE FROM v_notes WHERE id = :noteId")
    suspend fun dropNote(noteId: Long)
}

@Database(entities = [VNote::class, VPage::class], version = 2, exportSchema = false)
abstract class VaultDb : RoomDatabase() {
    abstract fun dao(): VaultDao

    companion object {
        /**
         * Прошлое неизменно: заметки, созданные до появления тегов и
         * частых слов, не пересоздаются. Новые колонки добавляются
         * пустыми, пустой блоб расшифровывается в null и трактуется как
         * "признака нет" — не как "признак пустой".
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE v_notes ADD COLUMN tags BLOB NOT NULL DEFAULT x''")
                db.execSQL("ALTER TABLE v_pages ADD COLUMN words BLOB NOT NULL DEFAULT x''")
            }
        }

        @Volatile private var instance: VaultDb? = null
        fun get(context: Context): VaultDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, VaultDb::class.java, "vault.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
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
    class NoteHead(val id: Long, val title: String, val pageCount: Int,
                   val updatedMs: Long, val tags: List<String>)

    /** Находка поиска: где именно и что видно вокруг. */
    class Hit(val noteId: Long, val noteTitle: String, val page: Int, val snippet: String)

    /**
     * Список заметок ЭТОГО тайника.
     *
     * Строки чужих тайников не расшифровываются и просто не попадают в
     * список. Это не фильтр по признаку, а невозможность прочитать.
     */
    suspend fun notes(): List<NoteHead> =
        dao.allNotes().mapNotNull { n ->
            val t = VaultCrypto.decrypt(dataKey, n.title)?.toString(Charsets.UTF_8)
            if (t == null) null else NoteHead(n.id, t, n.pageCount, n.updatedMs, tagsOf(n))
        }

    private fun tagsOf(n: VNote): List<String> {
        val raw = VaultCrypto.decrypt(dataKey, n.tags)?.toString(Charsets.UTF_8) ?: return emptyList()
        return VaultText.parseTags(raw)
    }

    suspend fun setTags(id: Long, raw: String) {
        val clean = VaultText.formatTags(VaultText.parseTags(raw))
        dao.setTags(id, VaultCrypto.encrypt(dataKey, clean.toByteArray()), System.currentTimeMillis())
    }

    /** Подпись страницы: три частых слова. Пусто, если страница ещё не
     *  пересохранялась после появления этой возможности. */
    suspend fun wordsOf(noteId: Long, idx: Int): List<String> {
        val p = dao.page(noteId, idx) ?: return emptyList()
        val raw = VaultCrypto.decrypt(dataKey, p.words)?.toString(Charsets.UTF_8) ?: return emptyList()
        return raw.split(' ').filter { it.isNotEmpty() }
    }

    /**
     * Поиск по всем заметкам тайника.
     *
     * Индекса на диске нет и не будет: открытый индекс отдал бы содержимое
     * без пароля. Страницы читаются порциями и расшифровываются на лету.
     *
     * @param onProgress вызывается по мере обхода заметок.
     * @param cancelled даёт прервать долгий поиск, не дожидаясь конца.
     */
    suspend fun search(
        query: String,
        limit: Int = 200,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<Hit> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<Hit>()
        val heads = notes()
        for ((i, h) in heads.withIndex()) {
            if (cancelled() || out.size >= limit) break
            onProgress(i, heads.size)
            var from = 0
            while (from < h.pageCount) {
                if (cancelled() || out.size >= limit) break
                val chunk = dao.pagesRange(h.id, from, from + PAGE_CHUNK)
                for (p in chunk) {
                    val text = VaultCrypto.decrypt(dataKey, p.body)?.toString(Charsets.UTF_8)
                        ?: continue
                    val pos = VaultText.find(text, query)
                    if (pos >= 0) out.add(Hit(h.id, h.title, p.idx, VaultText.snippet(text, pos)))
                    if (out.size >= limit) break
                }
                from += PAGE_CHUNK
            }
        }
        return out
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
        val top = VaultText.formatTags(VaultText.topWords(text)).replace(", ", " ")
        dao.putPage(VPage(
            noteId, idx, now,
            VaultCrypto.encrypt(dataKey, text.toByteArray()),
            VaultCrypto.encrypt(dataKey, top.toByteArray())
        ))
        val n = dao.note(noteId) ?: return
        val count = maxOf(n.pageCount, idx + 1)
        dao.setPageCount(noteId, count, now)
    }

    /**
     * Удалить заметку насовсем: страницы, строку заметки и все её картинки.
     *
     * Правило проекта "не удалять - помечать" здесь НЕ действует. Vault -
     * единственное место, где человек имеет право стереть своё
     * по-настоящему, и обещание должно выполняться буквально. Помеченная
     * заметка, которую видно в файле базы, - это невыполненное обещание.
     *
     * Порядок важен: сначала картинки (их адреса лежат в тексте страниц),
     * потом страницы, потом сама заметка. Обратный порядок оставил бы
     * файлы картинок сиротами навсегда.
     */
    suspend fun deleteNote(noteId: Long, images: VaultImages) {
        val n = dao.note(noteId) ?: return
        var from = 0
        while (from < n.pageCount) {
            for (p in dao.pagesRange(noteId, from, from + PAGE_CHUNK)) {
                val text = VaultCrypto.decrypt(dataKey, p.body)?.toString(Charsets.UTF_8)
                    ?: continue
                for (id in VaultText.imageRefs(text)) images.delete(id)
            }
            from += PAGE_CHUNK
        }
        dao.dropPages(noteId)
        dao.dropNote(noteId)
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
        /** Сколько страниц поиск держит в памяти за раз. */
        const val PAGE_CHUNK = 20
    }
}
