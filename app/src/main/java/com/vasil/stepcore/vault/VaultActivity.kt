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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

        if (VaultSession.isOpen) {
            repo = VaultSession.key()?.let { VaultRepo(this, it) }
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
            VaultSession.lock()
            finish()
        }
    }

    // ------------------------------------------------------------------- вход

    private fun showEntrance() {
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

        back.setOnClickListener { if (!busy) showEntrance() }
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
                repo = VaultSession.key()?.let { VaultRepo(this@VaultActivity, it) }
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
        editor = null
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
        val text = editor?.text?.toString()
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
        VaultSession.lock()
        finish()
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
