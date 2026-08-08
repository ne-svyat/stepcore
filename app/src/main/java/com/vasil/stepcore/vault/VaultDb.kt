package com.vasil.stepcore.vault

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
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
    /**
     * Тепло и когда его считали. Числа, не шифротекст: по ним видно, что
     * какая-то заметка активна, но не какая и не о чём. Та же цена, что у
     * updatedMs, - за возможность сортировать, не расшифровывая тайник.
     */
    val heat: Float = 0f,
    val heatAtMs: Long = 0L,
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

/**
 * Снимок страницы. Пятьдесят на страницу плюс три развилки.
 *
 * ЗАЧЕМ РАЗВИЛКИ
 * --------------
 * Обычная отмена — стек: откатился, начал править, старое будущее исчезло
 * навсегда. Это и есть та потеря, из-за которой люди боятся откатывать.
 * Здесь брошенное будущее не умирает: при возврате старой версии текущая
 * уезжает в развилку и остаётся доступной.
 *
 * kind: 0 — снимок в ленте, 1 — развилка.
 */
@Entity(
    tableName = "v_history",
    // Индекс объявлен ЗДЕСЬ, а не только в миграции. Room сверяет схему
    // буквально: индекс, созданный миграцией, но не описанный в сущности,
    // роняет приложение при открытии базы. Имя задано явно и совпадает с
    // именем в миграции — иначе Room сгенерирует своё и снова не сойдётся.
    indices = [Index(name = "idx_hist", value = ["noteId", "idx", "kind", "ms"])]
)
data class VHist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val idx: Int,
    val ms: Long,
    val kind: Int,
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

    @Query("UPDATE v_notes SET heat = :heat, heatAtMs = :ms WHERE id = :id")
    suspend fun setHeat(id: Long, heat: Float, ms: Long)

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

    @Insert
    suspend fun addHist(h: VHist)

    @Query("SELECT * FROM v_history WHERE noteId = :noteId AND idx = :idx AND kind = :kind ORDER BY ms DESC")
    suspend fun hist(noteId: Long, idx: Int, kind: Int): List<VHist>

    /** Обрезка по кругу: старейшее сверх предела уходит. */
    @Query("""DELETE FROM v_history WHERE noteId = :noteId AND idx = :idx AND kind = :kind
              AND id NOT IN (SELECT id FROM v_history WHERE noteId = :noteId AND idx = :idx
                             AND kind = :kind ORDER BY ms DESC LIMIT :keep)""")
    suspend fun trimHist(noteId: Long, idx: Int, kind: Int, keep: Int)

    @Query("DELETE FROM v_history WHERE noteId = :noteId")
    suspend fun dropHist(noteId: Long)

    @Query("DELETE FROM v_history WHERE id = :id")
    suspend fun dropHistOne(id: Long)

    @Query("DELETE FROM v_pages WHERE noteId = :noteId")
    suspend fun dropPages(noteId: Long)

    @Query("DELETE FROM v_notes WHERE id = :noteId")
    suspend fun dropNote(noteId: Long)
}

@Database(entities = [VNote::class, VPage::class, VHist::class], version = 4, exportSchema = false)
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

        /** История правок. Прошлое неизменно: старые заметки не трогаем. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS v_history (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "noteId INTEGER NOT NULL, idx INTEGER NOT NULL, " +
                    "ms INTEGER NOT NULL, kind INTEGER NOT NULL, body BLOB NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_hist ON v_history (noteId, idx, kind, ms)"
                )
            }
        }

        /** Тепло. Старые заметки начинают с нуля и разогреваются
         *  первым же касанием: прошлое неизменно, выдумывать историю
         *  задним числом мы не станем. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE v_notes ADD COLUMN heat REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE v_notes ADD COLUMN heatAtMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: VaultDb? = null
        fun get(context: Context): VaultDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, VaultDb::class.java, "vault.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
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
                   val updatedMs: Long, val tags: List<String>, val heat: Float = 0f)

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
            if (t == null) null else NoteHead(
                n.id, t, n.pageCount, n.updatedMs, tagsOf(n),
                // Остывание считается при ЧТЕНИИ: хранить приходится два
                // числа вместо журнала касаний.
                VaultHeat.decay(n.heat, n.heatAtMs, System.currentTimeMillis())
            )
        }

    /** Отметить касание заметки: открыли, дописали или завели. */
    suspend fun touch(noteId: Long, weight: Float) {
        val n = dao.note(noteId) ?: return
        val now = System.currentTimeMillis()
        dao.setHeat(noteId, VaultHeat.bump(n.heat, n.heatAtMs, now, weight), now)
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

    /**
     * Тропа — связь между страницами. Три уровня близости, и они читаются
     * глазом мгновенно, потому что рисуются по-разному.
     *
     * DIRECT — ты сам поставил [[ссылку]].
     * BACK   — на тебя сослались откуда-то ещё.
     * KIN    — просто похожие темы, по частым словам.
     *
     * Графа не будет. Граф красив на скриншоте и бесполезен на телефоне
     * после сотни узлов: он показывает, что связи ЕСТЬ, но не отвечает на
     * вопрос "куда мне сейчас". Список, отсортированный по силе, отвечает.
     */
    enum class TrailKind { DIRECT, BACK, KIN }

    class Trail(
        val noteId: Long,
        val title: String,
        val page: Int,
        val kind: TrailKind,
        val strength: Int,
    )

    /**
     * Тропы от конкретной страницы.
     *
     * Родство считается по УЖЕ сохранённым подписям страниц: текст чужих
     * страниц ради этого не расшифровывается. Прямые и обратные ссылки
     * требуют чтения текста, поэтому идут порциями, как поиск.
     */
    suspend fun trails(noteId: Long, idx: Int, limit: Int = 60): List<Trail> {
        val heads = notes()
        val me = heads.firstOrNull { it.id == noteId } ?: return emptyList()
        val myText = readPage(noteId, idx) ?: ""
        val myWords = wordsOf(noteId, idx)
        val myLinks = VaultText.linkRefs(myText)

        val out = ArrayList<Trail>()

        // 1. Прямые: названия, на которые ссылается эта страница.
        for (name in myLinks) {
            val target = heads.firstOrNull { VaultText.sameTitle(it.title, name) } ?: continue
            if (target.id == noteId) continue
            out.add(Trail(target.id, target.title, 0, TrailKind.DIRECT, 1000))
        }

        // 2. Обратные и родство — одним обходом, чтобы не читать всё дважды.
        for (h in heads) {
            if (h.id == noteId) continue
            var from = 0
            while (from < h.pageCount) {
                for (p in dao.pagesRange(h.id, from, from + PAGE_CHUNK)) {
                    val words = VaultCrypto.decrypt(dataKey, p.words)
                        ?.toString(Charsets.UTF_8)?.split(' ')?.filter { it.isNotEmpty() }
                        ?: emptyList()
                    val kin = VaultText.kinship(myWords, words)

                    // Текст читаем только если ищем обратную ссылку.
                    val text = VaultCrypto.decrypt(dataKey, p.body)?.toString(Charsets.UTF_8)
                    val backs = if (text == null) false
                        else VaultText.linkRefs(text).any { VaultText.sameTitle(it, me.title) }

                    if (backs) out.add(Trail(h.id, h.title, p.idx, TrailKind.BACK, 500))
                    else if (kin > 0) out.add(Trail(h.id, h.title, p.idx, TrailKind.KIN, kin))
                }
                from += PAGE_CHUNK
                if (out.size > limit * 4) break
            }
        }

        // Сортировка ровно та, в какой человек их читает: сначала что я
        // связал сам, потом кто пришёл ко мне, потом просто похожее.
        return out.sortedWith(
            compareBy<Trail> { it.kind.ordinal }.thenByDescending { it.strength }
        ).take(limit)
    }

    /** Класс со своим весом и тоном. */
    class ClassInfo(val name: String, val count: Int, val hue: Float,
                    val heat: Float = 0f)

    /**
     * Классы тайника и их сплетения.
     *
     * Классы - это теги. Отдельного поля "тип записи" нет сознательно:
     * человек уже вводит эти слова, и заводить второе поле того же смысла
     * значит заставлять его помнить, где что.
     *
     * Считается по заголовкам заметок, текст страниц не читается вовсе.
     */
    /** Заметки каждого класса, САМЫЕ ЖИВЫЕ первыми. */
    suspend fun classMembers(): Map<String, List<Pair<Long, String>>> {
        val out = HashMap<String, ArrayList<Pair<Long, String>>>()
        for (h in notes().sortedByDescending { it.heat }) {
            for (t in h.tags.map { it.trim().lowercase() }.distinct()) {
                out.getOrPut(t) { ArrayList() }.add(h.id to h.title)
            }
        }
        return out
    }

    suspend fun classes(): Pair<List<ClassInfo>, Map<Pair<String, String>, Int>> {
        val heads = notes()
        val count = HashMap<String, Int>()
        val together = HashMap<Pair<String, String>, Int>()
        val warmth = HashMap<String, Float>()
        for (h in heads) {
            val tags = h.tags.map { it.trim().lowercase() }.distinct().sorted()
            for (t in tags) {
                count[t] = (count[t] ?: 0) + 1
                // Тепло класса - сумма тепла его заметок. Класс из трёх
                // живых заметок должен обгонять класс из тридцати мёртвых.
                warmth[t] = (warmth[t] ?: 0f) + h.heat
            }
            for (i in tags.indices) for (j in i + 1 until tags.size) {
                val k = tags[i] to tags[j]
                together[k] = (together[k] ?: 0) + 1
            }
        }
        val hues = VaultHues.layout(count.keys.toList(), together)
        val list = count.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key })
            .map { ClassInfo(it.key, it.value, hues[it.key] ?: 0f, warmth[it.key] ?: 0f) }
        return list to together
    }

    /** Собрать заметки в архив. Пустой список означает «все». */
    suspend fun collect(ids: List<Long>): VaultArchive.Archive {
        val heads = notes().filter { ids.isEmpty() || it.id in ids }
        val out = ArrayList<VaultArchive.Note>(heads.size)
        for (h in heads) {
            val pages = ArrayList<String>(h.pageCount)
            for (i in 0 until h.pageCount) pages.add(readPage(h.id, i) ?: "")
            out.add(VaultArchive.Note(h.title, h.tags, pages))
        }
        return VaultArchive.Archive(System.currentTimeMillis(), out)
    }

    /**
     * Влить архив в этот тайник.
     *
     * Заметки ДОБАВЛЯЮТСЯ, а не заменяют существующие: импорт не должен
     * стирать то, что уже есть. Совпадение названий - не повод считать
     * заметки одной и той же.
     *
     * @return сколько заметок добавлено.
     */
    suspend fun absorb(a: VaultArchive.Archive): Int {
        var added = 0
        for (n in a.notes) {
            val id = createNote(if (n.title.isBlank()) "Без названия" else n.title)
            if (n.tags.isNotEmpty()) setTags(id, VaultText.formatTags(n.tags))
            for ((i, p) in n.pages.withIndex()) {
                if (i >= VaultRepo.MAX_PAGES) break
                writePage(id, i, if (p.length > MAX_PAGE_CHARS) p.take(MAX_PAGE_CHARS) else p)
            }
            added++
        }
        return added
    }

    /** Заголовок из текста: где он и какого уровня. */
    class Section(val page: Int, val level: Int, val text: String)

    /**
     * Оглавление ВСЕЙ заметки, а не одной страницы.
     *
     * В заметке на сотни страниц оглавление отдельной страницы почти
     * бесполезно: искать надо по всему тексту. Страницы читаются
     * порциями, как в поиске - разом их держать в памяти нельзя.
     */
    suspend fun sections(noteId: Long, limit: Int = 500): List<Section> {
        val n = dao.note(noteId) ?: return emptyList()
        val out = ArrayList<Section>()
        var from = 0
        while (from < n.pageCount && out.size < limit) {
            for (p in dao.pagesRange(noteId, from, from + PAGE_CHUNK)) {
                val text = VaultCrypto.decrypt(dataKey, p.body)?.toString(Charsets.UTF_8)
                    ?: continue
                for (h in VaultText.outline(text)) {
                    out.add(Section(p.idx, h.level, h.text))
                    if (out.size >= limit) break
                }
            }
            from += PAGE_CHUNK
        }
        return out
    }

    /** Один снимок страницы: что было и когда. */
    class Snap(val id: Long, val ms: Long, val text: String, val fork: Boolean)

    suspend fun history(noteId: Long, idx: Int): List<Snap> = load(noteId, idx, KIND_SNAP)
    suspend fun forks(noteId: Long, idx: Int): List<Snap> = load(noteId, idx, KIND_FORK)

    private suspend fun load(noteId: Long, idx: Int, kind: Int): List<Snap> =
        dao.hist(noteId, idx, kind).mapNotNull { h ->
            val t = VaultCrypto.decrypt(dataKey, h.body)?.toString(Charsets.UTF_8)
            if (t == null) null else Snap(h.id, h.ms, t, kind == KIND_FORK)
        }

    private suspend fun keep(noteId: Long, idx: Int, kind: Int, text: String, limit: Int) {
        dao.addHist(VHist(
            noteId = noteId, idx = idx, ms = System.currentTimeMillis(), kind = kind,
            body = VaultCrypto.encrypt(dataKey, text.toByteArray())
        ))
        dao.trimHist(noteId, idx, kind, limit)
    }

    /**
     * Отложить текущую версию в развилку.
     *
     * Вызывается перед возвратом старой версии. Именно это делает потерю
     * текста правкой невозможной, а не просто маловероятной.
     */
    suspend fun fork(noteId: Long, idx: Int, currentText: String) {
        if (currentText.isEmpty()) return
        keep(noteId, idx, KIND_FORK, currentText, MAX_FORKS)
    }

    suspend fun dropSnap(id: Long) = dao.dropHistOne(id)

    suspend fun writePage(noteId: Long, idx: Int, text: String) {
        require(text.length <= MAX_PAGE_CHARS) { "страница длиннее предела" }
        val now = System.currentTimeMillis()

        // Прежняя версия уходит в ленту ДО перезаписи. Снимок делается
        // только при реальном отличии: сохранение без правок не должно
        // вытеснять полезные снимки из полусотни.
        val prev = dao.page(noteId, idx)?.let {
            VaultCrypto.decrypt(dataKey, it.body)?.toString(Charsets.UTF_8)
        }
        if (prev != null && prev != text) keep(noteId, idx, KIND_SNAP, prev, MAX_HISTORY)
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
        dao.dropHist(noteId)
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
        /**
         * Предел на страницу. Дальше - следующая страница, а не отказ.
         *
         * Поднят с 10 000 до 20 000 по просьбе: одна мысль часто не
         * помещалась. Цена честная - страница целиком держится в памяти
         * при открытии, но двадцать тысяч символов это сорок килобайт,
         * а не мегабайты.
         */
        const val MAX_PAGE_CHARS = 20_000
        /** Предел страниц в одной заметке. */
        const val MAX_PAGES = 1000
        /** Сколько страниц поиск держит в памяти за раз. */
        const val PAGE_CHUNK = 20
        /** Глубина ленты правок на страницу. */
        const val MAX_HISTORY = 50
        /** Брошенных будущих на страницу. */
        const val MAX_FORKS = 3
        const val KIND_SNAP = 0
        const val KIND_FORK = 1
    }
}
