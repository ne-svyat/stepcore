package com.vasil.stepcore.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vasil.stepcore.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран Vault.
 *
 * ГЛАВНОЕ ТРЕБОВАНИЕ К ВХОДУ
 * --------------------------
 * Вид экрана входа ОДИНАКОВ независимо от того, создан хоть один тайник или
 * ни одного. Ни текстом, ни расположением, ни поведением кнопок нельзя
 * узнать, есть ли тут что-нибудь. Поэтому файл не читается до нажатия
 * "Открыть", а сообщение об ошибке одно на все случаи.
 *
 * ПОЧЕМУ СОЗДАНИЕ — ОТДЕЛЬНЫЙ ЭКРАН
 * ---------------------------------
 * Форма создания требует четырёх полей и галки. Если держать её на одном
 * экране с входом, вход уезжает под скролл — а он нужен каждый день, тогда
 * как создание случается несколько раз в жизни. Частое действие наверху и
 * без прокрутки, редкое — за одно нажатие.
 *
 * FLAG_SECURE обязателен: без него система кладёт превью экрана в список
 * задач, и заметки утекают мимо всей криптографии.
 */
class VaultActivity : AppCompatActivity() {

    private val store by lazy { VaultStore(this) }
    private lateinit var root: LinearLayout
    private var busy = false
    private var repo: VaultRepo? = null

    // Открытая страница. Держим ровно одну: тысяча страниц по десять тысяч
    // символов в памяти не поместится и не должна.
    private var openNoteId = 0L
    private var openIdx = 0
    private var openPages = 1
    private var editor: EditText? = null
    private var images: VaultImages? = null
    private var preview = false
    private var lastBackMs = 0L

    /**
     * Где мы сейчас. Без этого "Назад" не знает, куда возвращаться, и
     * закрывает тайник с любого экрана — именно так и вышло на истории
     * правок: там нет редактора, и обработчик считал, что мы на входе.
     *
     * Свой стек экранов, а не системный, потому что весь Vault живёт в
     * одной Activity: класть каждый экран в отдельную Activity значило бы
     * плодить записи в списке задач, которых у скрытого модуля быть не
     * должно.
     */
    private enum class Screen { ENTRANCE, CREATE, NOTES, PAGE, PREVIEW, HISTORY }
    private var screen = Screen.ENTRANCE
    private var histNoteId = 0L
    private var histIdx = 0

    /**
     * Выбор картинки у системы. Регистрируется один раз при создании
     * экрана: регистрировать по нажатию Android не позволяет.
     */
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) insertImage(uri)
        }

    /**
     * Бюджет ожидания при входе. Подобран под будущую анимацию двери:
     * человек смотрит на открывающийся механизм, а не на пустой экран.
     * Чем дороже вход, тем дороже перебор — здесь красота и стойкость
     * совпадают.
     */
    private val unlockBudgetMs = 1500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0A0A0A.toInt())
            isFillViewport = true
            addView(root, LinearLayout.LayoutParams(-1, -2))
        })

        // Случайный выход из заметки - самая обидная потеря, и она
        // случается постоянно. Но спрашивать ВСЕГДА значит раздражать:
        // вопрос задаётся только когда есть что терять.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        if (VaultSession.isOpen) {
            VaultSession.key()?.let { repo = VaultRepo(this, it); images = VaultImages(this, it) }
            showNotes()
        } else showEntrance()
    }

    /** Уход с экрана запирает тайник. Ключ не переживает сворачивание. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            // Сохранить до запирания: несохранённый текст важнее скорости.
            val text = editor?.text?.toString()
            val id = openNoteId
            val idx = openIdx
            val r = repo
            if (text != null && id != 0L && r != null) {
                kotlinx.coroutines.runBlocking {
                    try { r.writePage(id, idx, text) } catch (e: Exception) { }
                }
            }
            editor = null
            repo = null
            images = null
            VaultSession.lock()
            finish()
        }
    }

    // ------------------------------------------------------------------- вход

    private fun showEntrance() {
        screen = Screen.ENTRANCE
        root.removeAllViews()
        title("Тайник")

        val field = secretField("Пароль или секрет восстановления")
        val warn = warnLabel()
        val go = button("Открыть")

        // Создание — вторым и тише: вход нужен каждый день, создание редко.
        val make = flatButton("Создать новый тайник")

        go.setOnClickListener {
            if (busy) return@setOnClickListener
            val s = field.chars()
            if (s.isEmpty()) return@setOnClickListener
            warn.visibility = View.GONE
            unlock(s, field, go, warn)
        }
        make.setOnClickListener { if (!busy) showCreate() }
    }

    private fun unlock(secret: CharArray, field: EditText, go: Button, warn: TextView) {
        busy = true
        go.isEnabled = false
        go.text = "Открываю…"
        lifecycleScope.launch {
            val key = withContext(Dispatchers.Default) {
                try {
                    // Один прогон scrypt на попытку независимо от числа
                    // тайников: соль общая на файл.
                    store.read()?.let { VaultFile.open(it, secret) }
                } catch (e: Exception) {
                    null
                } finally {
                    secret.fill('\u0000')
                }
            }
            busy = false
            if (key != null) {
                VaultSession.open(key)
                repo = VaultRepo(this@VaultActivity, key)
                images = VaultImages(this@VaultActivity, key)
                showNotes()
            } else {
                go.isEnabled = true
                go.text = "Открыть"
                field.setText("")
                // Одно сообщение на все случаи: пароль не тот, файла нет,
                // файл испорчен. Разные ответы выдали бы состояние тайника.
                warn.text = "Не подходит"
                warn.visibility = View.VISIBLE
            }
        }
    }

    // --------------------------------------------------------------- создание

    private fun showCreate() {
        screen = Screen.CREATE
        root.removeAllViews()
        title("Новый тайник")
        dim("Заметки шифруются на этом устройстве и никуда не отправляются.")

        val pass = secretField("Пароль")
        val pass2 = secretField("Пароль ещё раз")

        gap()
        dim("Секрет восстановления — второй способ войти, если пароль забыт. " +
            "Слова, цифры, предложение — что угодно, лишь бы ты это помнил или " +
            "где-то записал. Это НЕ подсказка к паролю: он открывает тайник " +
            "точно так же, поэтому хранить его надо так же бережно.")

        val rec = secretField("Секрет восстановления")
        val rec2 = secretField("Секрет восстановления ещё раз")

        val ack = CheckBox(this).apply {
            text = "Я записал секрет восстановления или точно его помню"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
        }
        root.addView(ack)

        val warn = warnLabel()
        val go = button("Создать")
        val back = flatButton("Назад")

        gap()
        dim("Восстановить пароль невозможно — это не недоработка. Если бы " +
            "приложение умело его вернуть, вернуть его смог бы и посторонний. " +
            "Забыл оба секрета — заметки потеряны навсегда.\n\n" +
            "Тайников может быть несколько, у каждого свои секреты и свои " +
            "заметки. Пароль одного не открывает другой.")

        back.setOnClickListener { if (!busy) goBack() }
        go.setOnClickListener {
            if (busy) return@setOnClickListener
            val p = pass.chars(); val p2 = pass2.chars()
            val r = rec.chars(); val r2 = rec2.chars()

            val problem: String? = when {
                VaultCrypto.checkSecret(p) != null -> "Пароль: " + VaultCrypto.checkSecret(p)
                !p.contentEquals(p2) -> "Пароли не совпадают"
                VaultCrypto.checkSecret(r) != null ->
                    "Секрет восстановления: " + VaultCrypto.checkSecret(r)
                !r.contentEquals(r2) -> "Секреты восстановления не совпадают"
                p.contentEquals(r) ->
                    "Пароль и секрет восстановления совпадают — тогда второго ключа нет"
                !ack.isChecked -> "Подтверди, что секрет восстановления не потеряется"
                else -> null
            }
            if (problem != null) {
                warn.text = problem
                warn.visibility = View.VISIBLE
                return@setOnClickListener
            }
            warn.visibility = View.GONE
            create(p, r, go, warn)
        }
    }

    private fun create(pass: CharArray, phrase: CharArray, go: Button, warn: TextView) {
        busy = true
        go.isEnabled = false
        go.text = "Кую замок…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.Default) {
                try {
                    val existing = store.read()
                    if (existing == null) {
                        // N меряется здесь: замок настраивается по этому
                        // телефону, а не по константе из прошлого десятилетия.
                        val n = VaultCrypto.calibrateN(unlockBudgetMs)
                        val box = VaultFile.createFirst(n, pass, phrase)
                        store.write(box)
                        VaultSession.open(VaultFile.open(box, pass)!!)
                        VaultFile.AddResult.OK
                    } else {
                        val added = VaultFile.addVault(existing, pass, phrase)
                        if (added.result == VaultFile.AddResult.OK) {
                            store.write(added.box!!)
                            VaultSession.open(VaultFile.open(added.box, pass)!!)
                        }
                        added.result
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    pass.fill('\u0000'); phrase.fill('\u0000')
                }
            }
            busy = false
            if (res == VaultFile.AddResult.OK) {
                VaultSession.key()?.let {
                    repo = VaultRepo(this@VaultActivity, it)
                    images = VaultImages(this@VaultActivity, it)
                }
                showNotes()
                return@launch
            }
            go.isEnabled = true
            go.text = "Создать"
            warn.text = when (res) {
                // Честное объяснение: человек всё равно узнает, перебрав
                // пароли, а молчание заставило бы его думать, что сломано.
                VaultFile.AddResult.SECRET_ALREADY_USED ->
                    "Этот секрет уже используется. Возьми другой."
                else -> "Не удалось создать"
            }
            warn.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------- заметки

    private fun showNotes(query: String = "") {
        screen = Screen.NOTES
        editor = null
        preview = false
        openNoteId = 0L
        root.removeAllViews()
        title("Заметки")
        val r = repo ?: return

        val q = EditText(this).apply {
            hint = "Поиск по тексту, или #тег"
            setText(query)
            textSize = 15f
            isSingleLine = true
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF6A6A75.toInt())
            setBackgroundColor(0xFF1F1F26.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(q, LinearLayout.LayoutParams(-1, -2))

        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(status)
        root.addView(holder)

        button("Найти").setOnClickListener {
            if (!busy) runSearch(q.text.toString(), holder, status)
        }
        button("Новая заметка").setOnClickListener {
            if (!busy) askText("Название заметки", "") { name ->
                lifecycleScope.launch {
                    val id = r.createNote(if (name.isBlank()) "Без названия" else name)
                    openNote(id, 0)
                }
            }
        }
        flatButton("Закрыть").setOnClickListener { closeVault() }

        lifecycleScope.launch { fillNotes(holder, status, r.notes()) }
    }

    private fun fillNotes(holder: LinearLayout, status: TextView,
                          list: List<VaultRepo.NoteHead>) {
        holder.removeAllViews()
        status.text = if (list.isEmpty()) "Пусто. Заметки этого тайника видны только с его паролем."
                      else "Заметок: " + list.size
        for (n in list) {
            val tags = if (n.tags.isEmpty()) "" else "\n#" + n.tags.joinToString(" #")
            holder.addView(TextView(this).apply {
                text = n.title + "\n" + n.pageCount + " стр." + tags
                textSize = 17f
                setTextColor(0xFFEEEEEE.toInt())
                setBackgroundColor(0xFF17171C.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener { openNote(n.id, 0) }
            }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
        }
    }

    /**
     * Поиск. Пустой запрос возвращает список заметок целиком, запрос с
     * решётки фильтрует по тегу (это дёшево — теги уже расшифрованы),
     * остальное идёт в полнотекстовый обход с расшифровкой на лету.
     */
    private fun runSearch(query: String, holder: LinearLayout, status: TextView) {
        val r = repo ?: return
        val text = query.trim()
        busy = true
        status.text = "Ищу…"
        holder.removeAllViews()
        lifecycleScope.launch {
            if (text.isEmpty()) {
                fillNotes(holder, status, r.notes())
                busy = false
                return@launch
            }
            if (text.startsWith("#")) {
                val tag = text.removePrefix("#").trim().lowercase()
                fillNotes(holder, status, r.notes().filter { h -> h.tags.any { it.contains(tag) } })
                busy = false
                return@launch
            }
            val hits = withContext(Dispatchers.Default) { r.search(text) }
            busy = false
            holder.removeAllViews()
            status.text = if (hits.isEmpty()) "Ничего не нашлось" else "Найдено: " + hits.size
            for (h in hits) {
                holder.addView(TextView(this@VaultActivity).apply {
                    this.text = h.noteTitle + "  ·  стр. " + (h.page + 1) + "\n" + h.snippet
                    textSize = 15f
                    setTextColor(0xFFDDDDE5.toInt())
                    setBackgroundColor(0xFF17171C.toInt())
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    setOnClickListener { openNote(h.noteId, h.page) }
                }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
            }
        }
    }

    /** Сохранить открытую страницу и уйти. Порядок важен: сначала запись. */
    private fun leavePage(then: () -> Unit) {
        val r = repo
        val text = editor?.text?.toString()   // в просмотре null — сохранять нечего
        val id = openNoteId
        val idx = openIdx
        if (r == null || text == null || id == 0L) { then(); return }
        lifecycleScope.launch {
            try { r.writePage(id, idx, text) } catch (e: Exception) { }
            then()
        }
    }

    private fun openNote(noteId: Long, idx: Int) {
        val r = repo ?: return
        lifecycleScope.launch {
            openPages = maxOf(1, r.pageCount(noteId))
            val safeIdx = idx.coerceIn(0, openPages - 1)
            val text = r.readPage(noteId, safeIdx) ?: ""
            val top = r.wordsOf(noteId, safeIdx)
            openNoteId = noteId
            openIdx = safeIdx
            drawPage(text, top)
        }
    }

    private fun drawPage(text: String, top: List<String> = emptyList()) {
        if (preview) { drawPreview(text, top); return }
        screen = Screen.PAGE
        root.removeAllViews()
        val r = repo ?: return
        val noteId = openNoteId

        val head = TextView(this).apply {
            // Подпись страницы: три частых слова. Пусто у страниц, не
            // пересохранявшихся после появления подписи, — это честнее,
            // чем выдумывать её задним числом.
            val sign = if (top.isEmpty()) "" else "   " + top.joinToString(" · ")
            this.text = "Страница " + (openIdx + 1) + " из " + openPages + sign
            textSize = 15f
            setTextColor(getColor(R.color.accent_violet_bright))
            setPadding(0, 0, 0, dp(10))
            isClickable = true
            setOnClickListener { askJump() }
        }
        root.addView(head)

        val e = EditText(this).apply {
            setText(text)
            textSize = 16f
            gravity = Gravity.TOP
            setTextColor(0xFFEEEEEE.toInt())
            setBackgroundColor(0xFF15151A.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines = 12
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // Предел не отказ, а граница страницы: дальше следующая.
            filters = arrayOf(android.text.InputFilter.LengthFilter(VaultRepo.MAX_PAGE_CHARS))
        }
        root.addView(e, LinearLayout.LayoutParams(-1, -2))
        editor = e

        val counter = TextView(this).apply {
            this.text = e.text.length.toString() + " / " + VaultRepo.MAX_PAGE_CHARS
            textSize = 12f
            setTextColor(0xFF7A7A88.toInt())
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(counter)
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                counter.text = (s?.length ?: 0).toString() + " / " + VaultRepo.MAX_PAGE_CHARS
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        nav.addView(navButton("◀") {
            if (openIdx > 0) leavePage { openNote(noteId, openIdx - 1) }
        })
        nav.addView(navButton("▶") {
            if (openIdx + 1 < openPages) leavePage { openNote(noteId, openIdx + 1) }
        })
        nav.addView(navButton("+ стр.") {
            leavePage {
                lifecycleScope.launch {
                    val idx = r.addPage(noteId)
                    if (idx < 0) toast("Предел " + VaultRepo.MAX_PAGES + " страниц")
                    else openNote(noteId, idx)
                }
            }
        })

        button("Копировать страницу").setOnClickListener {
            val sel = e.selectionEnd - e.selectionStart
            val whole = e.text.toString()
            val part = if (sel > 0) whole.substring(e.selectionStart, e.selectionEnd) else whole
            copy(part)
            toast(if (sel > 0) "Скопирован выделенный кусок" else "Скопирована страница")
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tools, LinearLayout.LayoutParams(-1, -2))
        tools.addView(navButton("Картинка") {
            // Метка вставляется отдельной строкой: в просмотре картинка
            // станет отдельным блоком, а не разорвёт предложение.
            pickImage.launch("image/*")
        })
        tools.addView(navButton(if (preview) "Правка" else "Просмотр") {
            preview = !preview
            leavePage { openNote(noteId, openIdx) }
        })

        flatButton("Теги заметки").setOnClickListener {
            if (busy) return@setOnClickListener
            val r2 = repo ?: return@setOnClickListener
            lifecycleScope.launch {
                val cur = r2.notes().firstOrNull { it.id == noteId }?.tags ?: emptyList()
                askText("Теги через запятую", cur.joinToString(", ")) { v ->
                    lifecycleScope.launch { r2.setTags(noteId, v); toast("Теги сохранены") }
                }
            }
        }
        flatButton("К списку заметок").setOnClickListener { leavePage { showNotes() } }
        flatButton("История правок").setOnClickListener {
            leavePage { showHistory(noteId, openIdx) }
        }
        flatButton("Удалить заметку").setOnClickListener { askDelete(noteId) }
    }

    private fun askJump() {
        askText("На какую страницу? 1.." + openPages, (openIdx + 1).toString()) { v ->
            val n = v.trim().toIntOrNull()
            if (n == null || n < 1 || n > openPages) toast("Такой страницы нет")
            else leavePage { openNote(openNoteId, n - 1) }
        }
    }

    private fun askText(label: String, preset: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(preset)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(input)
            .setPositiveButton("Ок") { _, _ -> done(input.text.toString()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("", text))
    }

    private fun closeVault() {
        editor = null
        repo = null
        images = null
        VaultSession.lock()
        finish()
    }

    /**
     * Удаление с подтверждением. В окне показывается НАЗВАНИЕ заметки:
     * "уверен?" без имени человек подтверждает не глядя, а увидев чужое
     * название - останавливается.
     */
    private fun askDelete(noteId: Long) {
        val r = repo ?: return
        val im = images ?: return
        lifecycleScope.launch {
            val head = r.notes().firstOrNull { it.id == noteId } ?: return@launch
            AlertDialog.Builder(this@VaultActivity)
                .setTitle("Удалить заметку?")
                .setMessage("«" + head.title + "», страниц: " + head.pageCount +
                    "\n\nЭто навсегда. Восстановить будет нечем: копий нет, " +
                    "корзины нет, картинки заметки тоже стираются.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ ->
                    lifecycleScope.launch {
                        editor = null
                        openNoteId = 0L
                        r.deleteNote(noteId, im)
                        toast("Удалено")
                        showNotes()
                    }
                }
                .show()
        }
    }

    /** Картинка уходит в зашифрованный файл, в текст встаёт только метка. */
    private fun insertImage(uri: android.net.Uri) {
        val im = images ?: return
        val e = editor ?: return
        val noteId = openNoteId
        if (noteId == 0L) return
        busy = true
        toast("Готовлю картинку…")
        lifecycleScope.launch {
            val id = withContext(Dispatchers.Default) { im.store(uri) }
            busy = false
            if (id == null) { toast("Не удалось добавить картинку"); return@launch }
            val mark = "\n" + VaultText.imageMark(id) + "\n"
            val pos = e.selectionEnd.coerceIn(0, e.text.length)
            if (e.text.length + mark.length > VaultRepo.MAX_PAGE_CHARS) {
                im.delete(id)
                toast("На странице не осталось места — начни новую")
                return@launch
            }
            e.text.insert(pos, mark)
            toast("Картинка вставлена")
        }
    }

    /**
     * Режим просмотра: заголовки крупнее, картинки нарисованы.
     *
     * Правка остаётся по обычному тексту сознательно. Редактор, рисующий
     * картинки прямо в поле ввода, ломает выделение, копирование и позицию
     * курсора — за красоту платит тем, ради чего блокнот и нужен.
     */
    private fun drawPreview(text: String, top: List<String>) {
        screen = Screen.PREVIEW
        root.removeAllViews()
        val noteId = openNoteId
        val im = images

        root.addView(TextView(this).apply {
            val sign = if (top.isEmpty()) "" else "   " + top.joinToString(" · ")
            this.text = "Страница " + (openIdx + 1) + " из " + openPages + sign
            textSize = 15f
            setTextColor(getColor(R.color.accent_violet_bright))
            setPadding(0, 0, 0, dp(12))
            isClickable = true
            setOnClickListener { askJump() }
        })

        for (b in VaultText.blocks(text)) {
            when (b) {
                is VaultText.Block.Head -> root.addView(TextView(this).apply {
                    this.text = b.text
                    textSize = when (b.level) { 1 -> 24f; 2 -> 20f; else -> 17f }
                    setTextColor(0xFFF2F2F7.toInt())
                    setPadding(0, dp(14), 0, dp(6))
                })
                is VaultText.Block.Para -> root.addView(TextView(this).apply {
                    this.text = b.text
                    textSize = 16f
                    setTextColor(0xFFDDDDE5.toInt())
                    setTextIsSelectable(true)
                    setPadding(0, dp(4), 0, dp(8))
                })
                is VaultText.Block.Img -> {
                    val bmp = im?.load(b.id)
                    if (bmp == null) {
                        root.addView(TextView(this).apply {
                            this.text = "[картинка недоступна]"
                            textSize = 14f
                            setTextColor(0xFF7A7A88.toInt())
                            setPadding(0, dp(6), 0, dp(6))
                        })
                    } else {
                        root.addView(ImageView(this).apply {
                            setImageBitmap(bmp)
                            adjustViewBounds = true
                        }, LinearLayout.LayoutParams(-1, -2).also {
                            it.topMargin = dp(8); it.bottomMargin = dp(8)
                        })
                    }
                }
            }
        }

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tools, LinearLayout.LayoutParams(-1, -2))
        tools.addView(navButton("◀") {
            if (openIdx > 0) openNote(noteId, openIdx - 1)
        })
        tools.addView(navButton("▶") {
            if (openIdx + 1 < openPages) openNote(noteId, openIdx + 1)
        })
        tools.addView(navButton("Правка") {
            preview = false
            openNote(noteId, openIdx)
        })
        flatButton("К списку заметок").setOnClickListener { showNotes() }
    }

    /**
     * История правок страницы.
     *
     * Сверху лента версий, снизу текст выбранной с подсветкой отличий от
     * нынешней. Отсюда можно скопировать кусок, вставить кусок в страницу
     * или вернуть версию целиком.
     *
     * Смысл именно в куске: чаще нужен один зря переписанный абзац, а не
     * вся старая страница. Все известные блокноты дают только "откатить
     * всё" — и поэтому их откатом не пользуются.
     */
    private fun showHistory(noteId: Long, idx: Int) {
        val r = repo ?: return
        screen = Screen.HISTORY
        histNoteId = noteId
        histIdx = idx
        root.removeAllViews()
        editor = null
        title("История правок")

        val lane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFDDDDE5.toInt())
            setBackgroundColor(0xFF15151A.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        val note = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, dp(10), 0, dp(6))
        }

        root.addView(note)
        root.addView(lane)

        lifecycleScope.launch {
            val current = r.readPage(noteId, idx) ?: ""
            val snaps = r.history(noteId, idx)
            val forks = r.forks(noteId, idx)
            val now = System.currentTimeMillis()

            if (snaps.isEmpty() && forks.isEmpty()) {
                note.text = "Правок пока нет. Снимок делается при каждом сохранении с изменениями."
            } else {
                note.text = "Версий: " + snaps.size + " · развилок: " + forks.size +
                    "\nВыбери версию — внизу появится её текст."
            }

            var chosen: VaultRepo.Snap? = null

            fun paint(s: VaultRepo.Snap) {
                chosen = s
                // Подсветка области, которой версии различаются: глаз
                // ловит цветное пятно раньше, чем читает буквы.
                val reg = VaultDiff.region(s.text, current)
                val sp = android.text.SpannableString(s.text)
                val to = minOf(s.text.length, maxOf(reg.oldEnd, reg.start))
                if (reg.start < to) {
                    sp.setSpan(android.text.style.BackgroundColorSpan(0xFF4A2B57.toInt()),
                        reg.start, to, 0)
                }
                body.text = sp
            }

            for (s in snaps + forks) {
                val label = (if (s.fork) "⑂ развилка · " else "") +
                    VaultDiff.ago(s.ms, now) + " · " + VaultDiff.plural(s.text.length) +
                    "\n" + VaultDiff.summary(s.text, current)
                lane.addView(TextView(this@VaultActivity).apply {
                    text = label
                    textSize = 14f
                    setTextColor(if (s.fork) 0xFFE0B96A.toInt() else 0xFFCFCFDA.toInt())
                    setBackgroundColor(0xFF17171C.toInt())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    setOnClickListener { paint(s); toast("Версия показана ниже") }
                }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })
            }

            root.addView(body, LinearLayout.LayoutParams(-1, -2))

            button("Копировать выделенное").setOnClickListener {
                val t = selectionOf(body)
                if (t.isEmpty()) toast("Выдели кусок в тексте версии")
                else { copy(t); toast("Скопировано") }
            }
            button("Вставить выделенное в страницу").setOnClickListener {
                val t = selectionOf(body)
                if (t.isEmpty()) { toast("Выдели кусок в тексте версии"); return@setOnClickListener }
                lifecycleScope.launch {
                    val merged = current + (if (current.endsWith("\n")) "" else "\n") + t
                    if (merged.length > VaultRepo.MAX_PAGE_CHARS) {
                        toast("Не помещается — начни новую страницу")
                        return@launch
                    }
                    // Нынешняя версия тоже уходит в ленту: вставка это
                    // такая же правка, и её тоже должно быть чем отменить.
                    r.writePage(noteId, idx, merged)
                    toast("Вставлено в конец страницы")
                    openNote(noteId, idx)
                }
            }
            button("Вернуть версию целиком").setOnClickListener {
                val s = chosen
                if (s == null) { toast("Сначала выбери версию"); return@setOnClickListener }
                AlertDialog.Builder(this@VaultActivity)
                    .setTitle("Вернуть эту версию?")
                    .setMessage("Нынешний текст не пропадёт — он уедет в развилку " +
                        "и останется здесь же, в истории.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Вернуть") { _, _ ->
                        lifecycleScope.launch {
                            r.fork(noteId, idx, current)
                            r.writePage(noteId, idx, s.text)
                            toast("Версия возвращена, прежняя в развилке")
                            openNote(noteId, idx)
                        }
                    }
                    .show()
            }
            flatButton("Назад к странице").setOnClickListener { goBack() }
        }
    }

    /**
     * Один шаг назад по экранам Vault.
     *
     * Наружу, к шагомеру, тайник закрывается ТОЛЬКО со списка заметок и
     * только по второму нажатию. Раньше выход случался с любого экрана,
     * где не было редактора, — и с истории правок выбрасывало прямо на
     * главный экран приложения.
     */
    private fun goBack() {
        when (screen) {
            Screen.HISTORY -> openNote(histNoteId, histIdx)

            Screen.PREVIEW -> {
                preview = false
                openNote(openNoteId, openIdx)
            }

            // Со страницы уходим только по второму нажатию: текст под
            // рукой, и случайный свайп не должен его прятать.
            Screen.PAGE -> if (confirmedBack("Ещё раз — к списку заметок. Текст сохранится.")) {
                leavePage { showNotes() }
            }

            Screen.CREATE -> showEntrance()

            // Со списка выходим к шагомеру — тоже со второго раза.
            Screen.NOTES -> if (confirmedBack("Ещё раз — закрыть тайник.")) closeVault()

            Screen.ENTRANCE -> closeVault()
        }
    }

    /** @return true, если это второе нажатие подряд в пределах окна. */
    private fun confirmedBack(hint: String): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackMs < 2500L) {
            lastBackMs = 0L
            return true
        }
        lastBackMs = now
        toast(hint)
        return false
    }

    /** Выделенный кусок текста версии, либо пусто. */
    private fun selectionOf(v: TextView): String {
        val a = v.selectionStart
        val b = v.selectionEnd
        if (a < 0 || b < 0 || a == b) return ""
        val from = minOf(a, b)
        val to = maxOf(a, b)
        return v.text.toString().substring(from, to)
    }

    private fun toast(t: String) {
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun navButton(label: String, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            textSize = 15f
            setOnClickListener { if (!busy) action() }
        }
        b.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        return b
    }

    // ------------------------------------------------------------------ мелочи

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun EditText.chars(): CharArray {
        val e = text
        val out = CharArray(e.length)
        e.getChars(0, e.length, out, 0)
        return out
    }

    private fun title(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 26f
            setTextColor(getColor(R.color.accent_violet_bright))
            setPadding(0, 0, 0, dp(20))
        })
    }

    private fun dim(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun gap() {
        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(12)))
    }

    private fun secretField(hint: String): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF6A6A75.toInt())
            setBackgroundColor(0xFF1F1F26.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(10)
        root.addView(e, lp)
        return e
    }

    private fun warnLabel(): TextView {
        val t = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.accent_red_bright))
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(t)
        return t
    }

    private fun button(label: String): Button {
        val b = Button(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(8)
        root.addView(b, lp)
        return b
    }

    /** Второстепенное действие: без заливки, тише основной кнопки. */
    private fun flatButton(label: String): TextView {
        val t = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF8F8FA0.toInt())
            setPadding(dp(8), dp(16), dp(8), dp(8))
            isClickable = true
        }
        root.addView(t, LinearLayout.LayoutParams(-1, -2))
        return t
    }
}
