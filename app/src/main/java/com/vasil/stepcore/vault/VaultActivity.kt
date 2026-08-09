package com.vasil.stepcore.vault

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    // Настройки поиска живут до закрытия тайника: человек, включивший
    // «слово целиком», ищет так подряд несколько раз.
    /**
     * Режим подбирается сам, пока человек не выбрал своё.
     *
     * Это ответ на «а система может сама?»: может, и почти всегда лучше
     * человека, потому что знает, НАШЛОСЬ ли что-нибудь. Настройки
     * остаются для тех редких случаев, когда самоподбор промахнулся.
     */
    private var searchAuto = true

    /** Отдельное значение для окна настроек: «пусть решает само». */
    private val AUTO_WORD = 99
    private var searchWord = VaultQuery.WORD_PREFIX
    private var searchAny = false
    private var searchWhere = VaultQuery.IN_ALL

    /** Своя клавиатура текущего экрана, если она развёрнута. */
    private var keyboardView: VaultKeyboard? = null
    private var entranceField: EditText? = null
    private var entranceGo: Button? = null
    private var entranceWarn: TextView? = null
    private var repo: VaultRepo? = null

    // Открытая страница. Держим ровно одну: тысяча страниц по десять тысяч
    // символов в памяти не поместится и не должна.
    private var openNoteId = 0L
    private var openIdx = 0
    private var openPages = 1
    private var openTags: List<String> = emptyList()
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
    private enum class Screen { ENTRANCE, CREATE, NOTES, PAGE, PREVIEW, HISTORY, IMAGE, TRAILS, ROOTS, ARCHIVE, GUARD, SHARES, SHARE_IN, IMPORT }
    private var screen = Screen.ENTRANCE
    private var histNoteId = 0L
    private var histIdx = 0

    /** Что подсветить на странице после перехода из поиска. */
    private var pendingFind: String? = null

    /** Точное место найденного. Искать его заново нельзя: на странице
     *  может быть другое вхождение, и прыжок ушёл бы не туда. */
    private var pendingFindAt: Int = -1

    /** Прокрутка списка результатов: нужна, чтобы вернуть её наверх. */
    private var listPane: ScrollView? = null

    /** Тоны классов. Считаются при открытии списка и живут до ухода. */
    private var classHues: Map<String, Float> = emptyMap()

    /** Класс, выбранный тапом по жиле: список откроется уже отобранным. */
    private var pendingTag: String? = null

    /**
     * Действующий отбор по классу. ОТДЕЛЬНО от строки поиска.
     *
     * Раньше отбор жил прямо в поле поиска текстом "#класс", и снять его
     * можно было только стерев буквы руками. Фильтр и поиск - разные
     * вещи, и в одном поле им тесно.
     */
    private var activeTag: String? = null

    /**
     * Класс, к которому подводится карта корней. При входе пусто - карта
     * стоит на самом горячем. Как только открыта заметка, фокус переходит
     * на её класс: карта следует за работой, а не за вчерашним рейтингом.
     */
    private var focusTag: String? = null

    /**
     * Выбранные заметки. Пустой набор - режим выбора выключен.
     *
     * Один механизм на две задачи: выгрузить выбранное и удалить
     * выбранное. Заводить для них разные способы выделения значило бы
     * заставлять человека помнить два.
     */
    private val chosen = LinkedHashSet<Long>()
    private var selecting = false

    /**
     * Карточки списка по номеру заметки и пересборка нижней панели.
     *
     * Нужны, чтобы отметить заметку БЕЗ перерисовки экрана. Раньше
     * каждое выделение звало showNotes целиком: экран моргал, а список
     * прокручивался обратно наверх - выбрать что-то ниже первого экрана
     * было почти невозможно.
     */
    private val rowViews = HashMap<Long, TextView>()
    private val rowTexts = HashMap<Long, VaultRepo.NoteHead>()
    private var rebuildBottom: (() -> Unit)? = null

    /** Что делаем после того, как система даст файл. */
    private var pendingExport: ByteArray? = null

    private val createFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument(
            "application/octet-stream")) { uri ->
            val data = pendingExport
            pendingExport = null
            if (uri != null && data != null) writeExport(uri, data)
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readImport(uri)
        }

    /** Следующее открытие заметки - чтение, а не продолжение правки. */
    private var openingForRead = false

    /**
     * Пришли ли в чтение из правки.
     *
     * Нужно для возврата: нажал "Чтение", посмотрел, назад - и снова в
     * правке, а не в списке заметок. Иначе просмотр оформления стоит
     * повторного открытия заметки.
     */
    private var readFromEdit = false
    /**
     * Способ показа экрана.
     *
     * Главный экран - ЖЁСТКИЙ КАРКАС без внешней прокрутки: шапка, гибкий
     * список, корни, панель. Прокрутка живёт внутри списка.
     *
     * Так сделано после двух неудачных попыток удержать корни внизу.
     * Внутри ScrollView высота содержимого не ограничена ничем: он меряет
     * ребёнка "сколько угодно", весу списка нечего делить, и список
     * растягивается на все заметки, унося корни за край. Ни match_parent,
     * ни точная высота в post этого не лечат надёжно - лечит только
     * отсутствие прокрутки НАД каркасом.
     *
     * Прочие экраны прокручиваются: там содержимое действительно длиннее
     * окна, и делить нечего.
     */
    private var scrollable = true

    /** Порядок заметок в списке. */
    private enum class SortBy { HOT, NEW, OLD, TITLE }
    private var sortBy = SortBy.HOT

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

    /**
     * Экран погас. Приёмник НЕ запирает тайник сам - он лишь сообщает
     * факт, а решение принимает VaultSession. Иначе появился бы второй
     * путь запирания в обход единой точки.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            VaultSession.onScreenOff()
            if (VaultSession.shouldRelock(System.currentTimeMillis())) {
                // Ключ гасится немедленно, не дожидаясь возвращения:
                // смысл этого режима в том, что заблокированный телефон
                // не хранит расшифрованный ключ ни секунды.
                VaultSession.lock()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Скриншоты РАЗРЕШЕНЫ сознательно.
        //
        // FLAG_SECURE запрещает и снимок экрана, и превью в списке задач.
        // Ценность - во втором: содержимое не должно всплывать миниатюрой,
        // когда листаешь открытые приложения. Запрет скриншотов защищает
        // от того, кто уже держит разблокированный телефон с открытым
        // тайником, то есть от почти невозможного случая, а мешает каждый
        // день. С Android 13 эти две вещи разделяются отдельной ручкой; на
        // более старых остаётся полный FLAG_SECURE - там выбора нет.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(false)
        } else {
            // До 33 превью и снимки не разделяются: остаётся полный запрет.
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE)
        }
        applyShots(store.read()?.shots ?: VaultFile.SHOTS_ALLOW)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        mount(scrollable = true)

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

    /**
     * Шапка страницы: назад, имя заметки, номер страницы.
     *
     * Имя вместо слова "правка" - по нему человек узнаёт, где он. Тап по
     * имени переименовывает: раньше для этого был чип среди классов, где
     * он терялся. Тап по строке страниц - переход на страницу.
     */
    private fun pageHeader(noteId: Long, mode: String) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        // Кнопка назад в шапке: свайп есть не на всех прошивках и не у
        // всех включён, а уходить со страницы надо всегда.
        head.addView(ImageView(this).apply {
            setImageDrawable(VaultIcon(VaultIcon.Kind.PREV,
                VaultIcon.tintFor(VaultIcon.Kind.PREV), dp(22)))
            contentDescription = "Назад"
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, null)
            setPadding(dp(4), dp(4), dp(10), dp(4))
            setOnClickListener { goBack() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        val name = rowTexts[noteId]?.title ?: "Заметка"
        col.addView(TextView(this).apply {
            text = name
            textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFF2F0F7.toInt())
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener { renameNote(noteId) }
        })
        col.addView(TextView(this).apply {
            text = mode + " · стр. " + (openIdx + 1) + " из " + openPages + "  ·  тап — перейти"
            textSize = 12f
            setTextColor(0xFF8A8A98.toInt())
            isClickable = true
            setOnClickListener { askJump() }
        })

        root.addView(View(this).apply { setBackgroundColor(LINE_SOFT) },
            LinearLayout.LayoutParams(-1, dp(1)).also {
                it.topMargin = dp(10); it.bottomMargin = dp(8)
            })
    }

    private fun renameNote(noteId: Long) {
        val r = repo ?: return
        val current = rowTexts[noteId]?.title ?: ""
        askText("Название заметки", current) { v ->
            if (v.isBlank()) { toast("Название не может быть пустым"); return@askText }
            leavePage {
                lifecycleScope.launch {
                    r.rename(noteId, v)
                    rowTexts.clear()
                    for (h in r.notes()) rowTexts[h.id] = h
                    toast("Переименовано")
                    openNote(noteId, openIdx)
                }
            }
        }
    }

    /**
     * Пересобрать способ показа: жёсткий каркас или прокрутка.
     *
     * Каркас нужен главному экрану: только там вес списка обязан
     * ограничиваться высотой окна. Внутри прокрутки такое ограничение
     * недостижимо в принципе.
     */
    /** Прокрутить показанный экран к началу. */
    private fun scrollToTop() {
        val p = root.parent
        if (p is ScrollView) p.post { p.scrollTo(0, 0) }
    }

    /**
     * Клавиатура принадлежит экрану, а не приложению. Экран сменился -
     * клавиатуры больше нет: иначе она осталась бы висеть, привязанная к
     * полю, которого уже не существует.
     */
    private fun dropKeyboard() {
        keyboardView = null
        entranceField = null
        entranceGo = null
        entranceWarn = null
    }

    private fun mount(scrollable: Boolean) {
        if (this.scrollable == scrollable && root.parent != null) return
        this.scrollable = scrollable
        // Без ведущей скобки на новой строке: Kotlin прочитал бы её как
        // вызов scrollable(...) - предыдущая строка кончается значением.
        val host = root.parent as? android.view.ViewGroup
        host?.removeView(root)
        if (scrollable) {
            setContentView(ScrollView(this).apply {
                setBackgroundColor(SURFACE_BASE)
                isFillViewport = true
                addView(root, FrameLayout.LayoutParams(-1, -2))
            })
        } else {
            setContentView(FrameLayout(this).apply {
                setBackgroundColor(SURFACE_BASE)
                addView(root, FrameLayout.LayoutParams(-1, -1))
            })
        }
    }

    /**
     * Уход с экрана: сохранить текст и запустить льготное окно.
     *
     * Экран больше НЕ закрывается: вернувшись в течение полутора минут,
     * человек попадает туда же, где был. Раньше здесь стоял finish(), и
     * любое переключение приложений выбрасывало из тайника.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        val text = editor?.text?.toString()
        val id = openNoteId
        val idx = openIdx
        val r = repo
        if (text != null && id != 0L && r != null) {
            kotlinx.coroutines.runBlocking {
                try { r.writePage(id, idx, text) } catch (e: Exception) { }
            }
        }
        VaultSession.leave(System.currentTimeMillis())
    }

    override fun onDestroy() {
        // Снятие в try: система могла снять приёмник сама при убийстве
        // процесса, и повторное снятие роняет приложение.
        try { unregisterReceiver(screenOffReceiver) } catch (e: Exception) { }
        super.onDestroy()
    }

    /** Возврат: льгота цела - продолжаем, истекла - запираем. */
    override fun onStart() {
        super.onStart()
        if (screen == Screen.ENTRANCE || screen == Screen.CREATE) return
        if (!VaultSession.resume(System.currentTimeMillis())) {
            editor = null
            repo = null
            images = null
            openNoteId = 0L
            showEntrance()
        }
    }

    // ------------------------------------------------------------------- вход

    private fun showEntrance() {
        mount(scrollable = true)
        screen = Screen.ENTRANCE
        dropKeyboard()
        root.removeAllViews()
        title("Тайник")

        val warn: TextView
        val go: Button
        val field = secretFieldWithKeyboard("Пароль или секрет восстановления") {
            tryUnlockFromKeyboard()
        }
        warn = warnLabel()
        go = button("Открыть")
        entranceGo = go
        entranceField = field
        entranceWarn = warn

        // Создание — вторым и тише: вход нужен каждый день, создание редко.
        val byParts = flatButton("Войти по частям секрета")
        byParts.setOnClickListener { if (!busy) showShareEntry() }

        val make = flatButton("Создать новый тайник")

        go.setOnClickListener { tryUnlockFromKeyboard() }
        make.setOnClickListener { if (!busy) showCreate() }
    }

    /**
     * Единственный путь к попытке входа: и кнопка «Открыть», и клавиша
     * «Готово» на своей клавиатуре идут сюда. Два пути к одному действию
     * однажды разъезжаются.
     */
    private fun tryUnlockFromKeyboard() {
        if (busy) return
        val field = entranceField ?: return
        val go = entranceGo ?: return
        val warn = entranceWarn ?: return
        val s = field.chars()
        if (s.isEmpty()) return
        warn.visibility = View.GONE
        go.isEnabled = false
        go.text = "Открываю…"
        unlock(s) {
            go.isEnabled = true
            go.text = "Открыть"
            field.setText("")
            // Раскладка пересобирается ПОСЛЕ каждой неудачи: если за
            // неверной попыткой подсмотрели, повтор по той же раскладке
            // выдал бы пароль целиком.
            keyboardView?.reshuffle()
            // Одно сообщение на все случаи: пароль не тот, файла нет, файл
            // испорчен. Разные ответы выдали бы состояние тайника.
            warn.text = "Не подходит"
            warn.visibility = View.VISIBLE
        }
    }

    /**
     * Единственное место, где вообще пробуется секрет: и пароль с экрана
     * входа, и секрет, собранный из частей, идут сюда. Второй путь к
     * открытию тайника однажды разошёлся бы с первым.
     */
    private fun unlock(secret: CharArray, onFail: () -> Unit) {
        busy = true
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
                openSession(key)
                showNotes()
            } else onFail()
        }
    }

    // --------------------------------------------------------------- создание

    private fun showCreate() {
        mount(scrollable = true)
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
                        openSession(VaultFile.open(box, pass)!!)
                        VaultFile.AddResult.OK
                    } else {
                        val added = VaultFile.addVault(existing, pass, phrase)
                        if (added.result == VaultFile.AddResult.OK) {
                            store.write(added.box!!)
                            openSession(VaultFile.open(added.box, pass)!!)
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

    /**
     * Главный экран тайника: список сверху, корни снизу.
     *
     * Верх - заметки со своей прокруткой, низ - живая карта классов.
     * Половина экрана раньше пустовала, а карта была отдельной кнопкой,
     * куда никто не заходит. Теперь низ управляет верхом: тап по жиле
     * отбирает класс, тап по узелку открывает заметку.
     */
    private fun showNotes(query: String = "") {
        dropKeyboard()
        screen = Screen.NOTES
        editor = null
        preview = false
        openNoteId = 0L
        mount(scrollable = false)
        root.removeAllViews()
        root.setPadding(dp(16), dp(16), dp(16), dp(8))
        val r = repo ?: return
        pendingTag?.let { activeTag = it; pendingTag = null }

        title("Тайник")

        val q = EditText(this).apply {
            hint = "Например: красная машина"
            // Кнопка на самой клавиатуре: палец уже там, и тянуться к
            // лупе - лишнее движение при каждом поиске.
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(query)
            textSize = 15f
            isSingleLine = true
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF6A6A75.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(SURFACE_RAISED)
                setStroke(dp(1), LINE_EDGE)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        // Кнопка поиска РЯДОМ с полем. Раньше она стояла внизу экрана, и
        // рука ехала через весь экран туда и обратно.
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
        searchRow.addView(q, LinearLayout.LayoutParams(0, -2, 1f))

        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8A8A98.toInt())
            setPadding(dp(2), dp(8), 0, dp(6))
        }

        searchRow.addView(iconButton(VaultIcon.Kind.SEARCH, "Найти",
            VaultIcon.tintFor(VaultIcon.Kind.SEARCH)) {
            if (!busy) startSearch(q, holder, status)
        })

        // Тот же путь, что и у лупы: два обработчика поиска однажды
        // разошлись бы, и с клавиатуры искалось бы иначе, чем с кнопки.
        q.setOnEditorActionListener { _, actionId, event ->
            val fromKey = event != null &&
                event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || fromKey) {
                if (!busy) startSearch(q, holder, status)
                true
            } else false
        }

        // Объяснение и шторка. Строка объяснения стоит ВСЕГДА, шторка
        // раскрывается по ней: человек сначала видит, как его поняли, и
        // только потом лезет крутить.
        // ШАПКА. Раньше здесь было три сообщения об одном и том же:
        // подсказка в пустом поле, «Впиши, что искать» и «Настроено».
        // Вместе они складывались в кашу, где непонятно, что от тебя
        // хотят. Осталось одно утверждение: КАК сейчас ищется.
        //
        // Две строки и не больше: блок стоит в жёстком каркасе, и каждая
        // лишняя строка отнимается у списка результатов.
        val explain = TextView(this).apply {
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isClickable = true
        }
        root.addView(explain, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })

        lateinit var repaint: () -> Unit
        repaint = {
            // Ровно одно утверждение: как ищется прямо сейчас. Не «впиши»
            // - поле само об этом просит подсказкой, и повторять незачем.
            val how = if (searchAuto) "Подбирается само" else searchHowShort()
            val head = android.text.SpannableStringBuilder("Как ищу:  ")
            head.append(how)
            head.setSpan(
                android.text.style.ForegroundColorSpan(0xFF7A7488.toInt()),
                0, 9, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            head.setSpan(
                android.text.style.ForegroundColorSpan(0xFFCFCFDA.toInt()),
                10, head.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            head.append("\nНастроить  ▸")
            head.setSpan(
                android.text.style.ForegroundColorSpan(0xFFB9A6E8.toInt()),
                head.length - 12, head.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            explain.text = head
            // Спокойная рамка вместо цветной заливки: этот блок не событие
            // и не предупреждение, он просто сообщает состояние.
            explain.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(SURFACE_RAISED)
                setStroke(dp(1), if (searchAuto) LINE_EDGE else 0x66B9A6E8)
            }
        }

        explain.setOnClickListener {
            if (!busy) {
                hideKeyboard(q)
                showSearchOptions(q.text.toString()) {
                    repaint(); runSearch(q.text.toString(), holder, status)
                }
            }
        }
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = repaint()
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) = Unit
        })

        repaint()

        // Порядок: четыре положения, «Живые» по умолчанию.
        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(sortRow, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        for ((mode, label) in listOf(SortBy.HOT to "Живые", SortBy.NEW to "Новые",
                                     SortBy.OLD to "Старые", SortBy.TITLE to "А-Я")) {
            sortRow.addView(tabButton(label, mode == sortBy) {
                sortBy = mode
                showNotes(q.text.toString())
            })
        }

        // Действующий отбор отдельной строкой: снимается одним нажатием,
        // а не стиранием текста в поиске.
        activeTag?.let { tag ->
            val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(filterRow, LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = dp(8)
            })
            val hue = classHues[tag]
            val c = if (hue == null) 0xFF8A8A98.toInt() else VaultHues.color(hue, 12)
            filterRow.addView(chip("#" + tag + "   ✕", c) {
                activeTag = null
                showNotes(q.text.toString())
            })
            filterRow.addView(chip("Все классы", 0xFF8A8A98.toInt()) {
                activeTag = null
                showNotes(q.text.toString())
            })
        }

        root.addView(status)

        // Список со своей прокруткой: примерно до середины экрана.
        val listPane = ScrollView(this).apply {
            isFillViewport = false
            // Своя прокрутка и никакой внешней: вложенные прокрутки
            // перехватывают палец друг у друга, и список начинает
            // «залипать». Главный экран потому и жёсткий каркас.
            isNestedScrollingEnabled = false
            addView(holder, LinearLayout.LayoutParams(-1, -2))
        }
        this.listPane = listPane
        // Список ГИБКИЙ, а не в жёстких процентах экрана. С фиксированной
        // долей появление строки фильтра выталкивало нижнюю панель за край:
        // сумма высот переставала помещаться, а внешняя прокрутка здесь
        // отключена. Теперь список сам отдаёт ровно столько места,
        // сколько заняла новая строка.
        root.addView(listPane, LinearLayout.LayoutParams(-1, 0, 1f))

        // Черта: две половины не должны сливаться в одно полотно.
        root.addView(View(this).apply {
            setBackgroundColor(LINE_SOFT)
        }, LinearLayout.LayoutParams(-1, dp(1)).also {
            it.topMargin = dp(10); it.bottomMargin = dp(8)
        })

        val rootsHint = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF7A7A88.toInt())
            text = "Корни · жила — отбор класса, узелок — заметка"
            setPadding(dp(2), 0, 0, dp(4))
        }
        root.addView(rootsHint)

        // Горизонтальная прокрутка вместо зума: пятьдесят классов на
        // ширину телефона - это по восемь точек на жилу, каша при любом
        // приближении. Прокрутка - одно движение и ничего не теряется.
        val rootsView = VaultRootsView(this)
        val rootsHeight = (resources.displayMetrics.heightPixels * 0.23f).toInt()
        val rootsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(rootsView, FrameLayout.LayoutParams(-2, rootsHeight))
        }
        root.addView(rootsScroll, LinearLayout.LayoutParams(-1, rootsHeight))

        // Нижняя панель пересобирается ОТДЕЛЬНО от экрана: вход в режим
        // выбора и каждая отметка меняют только её. Раньше это звало
        // showNotes целиком - экран моргал, а список прокручивался
        // обратно наверх, и выбрать что-то ниже первого экрана было
        // почти невозможно.
        val bottomBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bottomBox, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })

        val renderBottom = {
            bottomBox.removeAllViews()
            if (selecting) {
                bottomBox.addView(tabButton("Выгрузить " + chosen.size,
                    VaultIcon.Kind.JUMP, VaultIcon.tintFor(VaultIcon.Kind.JUMP)) {
                    askExport(chosen.toList())
                })
                // Закрепление живёт там же, где выгрузка и удаление:
                // новый жест учить не надо, режим выбора уже знаком.
                bottomBox.addView(tabButton(pinActionLabel(), VaultIcon.Kind.TAG,
                    VaultIcon.tintFor(VaultIcon.Kind.TAG)) {
                    togglePins(chosen.toList())
                })
                bottomBox.addView(tabButton("Удалить " + chosen.size,
                    VaultIcon.Kind.TRASH, VaultIcon.tintFor(VaultIcon.Kind.TRASH)) {
                    askDeleteMany(chosen.toList())
                })
                bottomBox.addView(tabButton("Отмена", VaultIcon.Kind.CLOSE,
                    VaultIcon.tintFor(VaultIcon.Kind.CLOSE)) {
                    selecting = false
                    chosen.clear()
                    for ((id, v) in rowViews) paintRow(v, id)
                    rebuildBottom?.invoke()
                })
            } else {
                bottomBox.addView(tabButton("Новая", VaultIcon.Kind.PLUS,
                    VaultIcon.tintFor(VaultIcon.Kind.PLUS)) {
                    if (!busy) askText("Название заметки", "") { name ->
                        lifecycleScope.launch {
                            val id = r.createNote(if (name.isBlank()) "Без названия" else name)
                            r.touch(id, VaultHeat.W_CREATE)
                            openForRead(id, 0)
                        }
                    }
                })
                bottomBox.addView(tabButton("Корни", VaultIcon.Kind.ROOTS,
                    VaultIcon.tintFor(VaultIcon.Kind.ROOTS)) {
                    if (!busy) showRoots()
                })
                bottomBox.addView(tabButton("Архив", VaultIcon.Kind.JUMP,
                    VaultIcon.tintFor(VaultIcon.Kind.JUMP)) {
                    if (!busy) showArchive()
                })
                bottomBox.addView(tabButton("Защита", VaultIcon.Kind.SHIELD,
                    VaultIcon.tintFor(VaultIcon.Kind.SHIELD)) {
                    if (!busy) showGuard()
                })
                bottomBox.addView(tabButton("Закрыть", VaultIcon.Kind.CLOSE,
                    VaultIcon.tintFor(VaultIcon.Kind.CLOSE)) {
                    closeVault()
                })
            }
        }
        rebuildBottom = renderBottom
        renderBottom()

        q.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        q.setOnEditorActionListener { _, _, _ ->
            if (!busy) runSearch(q.text.toString(), holder, status)
            true
        }

        lifecycleScope.launch {
            val (list, together) = r.classes()
            classHues = list.associate { it.name to it.hue }
            val all = r.notes()
            val shown = activeTag?.let { tag ->
                all.filter { n -> n.tags.any { it.trim().lowercase() == tag } }
            } ?: all
            if (query.isBlank()) fillNotes(holder, status, shown)
            else runSearch(query, holder, status)

            if (list.isNotEmpty()) {
                val members = r.classMembers()
                // Порядок жил ПО ТЕПЛУ: живое слева, под большим пальцем.
                // Раньше сортировалось по тону - красиво, но бесполезно:
                // самое нужное могло оказаться в конце.
                val ordered = list.sortedByDescending { it.heat }
                // Ширину вьюха считает сама в onMeasure. Снаружи её
                // задавать нельзя: прямое присваивание layoutParams роняет
                // приложение, а match_parent внутри прокрутки даёт ноль.
                rootsView.setData(
                    ordered.map { c ->
                        VaultRootsView.Strand(
                            c.name, c.count, VaultHues.color(c.hue, c.count),
                            (members[c.name] ?: emptyList())
                                .map { VaultRootsView.Node(it.first, it.second) }
                        )
                    },
                    together.entries.sortedByDescending { it.value }.take(24)
                        .map { VaultRootsView.Weave(it.key.first, it.key.second, it.value) },
                    { name -> pendingTag = name; focusTag = name; showNotes() },
                    { id -> openForRead(id, 0) }
                )
                // Подвести карту к нужной жиле. Без post ширина полотна
                // ещё не посчитана, и прокручивать некуда.
                focusTag?.let { tag ->
                    rootsScroll.post {
                        val x = rootsView.laneX(tag)
                        if (x >= 0f) {
                            rootsScroll.smoothScrollTo(
                                (x - rootsScroll.width / 2f).toInt().coerceAtLeast(0), 0)
                        }
                    }
                }
            } else {
                rootsHint.text = "Корней нет. Впиши классу имя в заметке — появится жила."
            }
        }
    }

    private fun fillNotes(holder: LinearLayout, status: TextView,
                          raw: List<VaultRepo.NoteHead>) {
        rowViews.clear()
        rowTexts.clear()
        val sorted = when (sortBy) {
            // «Живые» по умолчанию: то, чем занимаешься сейчас, а не то,
            // что случайно завёл последним.
            SortBy.HOT -> raw.sortedWith(
                compareByDescending<VaultRepo.NoteHead> { it.heat }
                    .thenByDescending { it.updatedMs })
            SortBy.NEW -> raw.sortedByDescending { it.id }
            SortBy.OLD -> raw.sortedBy { it.id }
            SortBy.TITLE -> raw.sortedBy { it.title.lowercase() }
        }
        // Закреплённые ВСЕГДА сверху и своим порядком - в любой сортировке.
        // Иначе человек расставил бы их, переключил порядок и решил, что
        // приложение потеряло его расстановку.
        val pinned = sorted.filter { it.pin > 0 }.sortedBy { it.pin }
        val list = pinned + sorted.filter { it.pin == 0 }
        holder.removeAllViews()
        status.text = if (list.isEmpty()) "Пусто. Заметки этого тайника видны только с его паролем."
                      else "Заметок: " + list.size
        var separatorDone = pinned.isEmpty()
        for (n in list) {
            // Черта между закреплёнными и остальными: без неё верхний
            // блок читается как «просто первые заметки».
            if (!separatorDone && n.pin == 0) {
                separatorDone = true
                holder.addView(TextView(this).apply {
                    text = "Остальные"
                    textSize = 12f
                    setTextColor(0xFF7A7488.toInt())
                    setPadding(dp(2), dp(6), 0, dp(6))
                })
            }
            val row = TextView(this).apply {
                textSize = 17f
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                setOnLongClickListener {
                    if (!selecting) {
                        selecting = true
                        chosen.add(n.id)
                        // Перекрасить ВСЕ карточки: режим сменился, но
                        // список остаётся на месте и не прокручивается.
                        for ((id, v) in rowViews) paintRow(v, id)
                        rebuildBottom?.invoke()
                    }
                    true
                }
                setOnClickListener {
                    if (selecting) {
                        if (!chosen.remove(n.id)) chosen.add(n.id)
                        val leaving = chosen.isEmpty()
                        if (leaving) selecting = false
                        if (leaving) for ((id, v) in rowViews) paintRow(v, id)
                        else paintRow(this, n.id)
                        rebuildBottom?.invoke()
                    } else openForRead(n.id, 0)
                }
            }
            rowTexts[n.id] = n
            rowViews[n.id] = row
            paintRow(row, n.id)

            if (n.pin > 0 && !selecting) {
                // Строка и стрелки одной полосой: стрелки НЕ поверх
                // строки, иначе мелкая цель перехватывала бы нажатие у
                // крупной и заметка перестала бы открываться.
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                line.addView(row, LinearLayout.LayoutParams(0, -2, 1f))
                val first = n.id == pinned.first().id
                val last = n.id == pinned.last().id
                line.addView(arrowButton("▲", !first) { movePin(n.id, -1) })
                line.addView(arrowButton("▼", !last) { movePin(n.id, 1) })
                holder.addView(line, LinearLayout.LayoutParams(-1, -2).also {
                    it.bottomMargin = dp(8)
                })
            } else {
                holder.addView(row, LinearLayout.LayoutParams(-1, -2).also {
                    it.bottomMargin = dp(8)
                })
            }
        }
    }

    /** Как сейчас ищется - в несколько слов, для шапки. */
    private fun searchHowShort(): String {
        val a = when (searchWord) {
            VaultQuery.WORD_EXACT -> "слова целиком"
            VaultQuery.WORD_INSIDE -> "внутри слов"
            else -> "по началу слова"
        }
        val b = if (searchAny) ", хотя бы одно" else ""
        val c = when (searchWhere) {
            VaultQuery.IN_TITLE -> ", только названия"
            VaultQuery.IN_TAGS -> ", только теги"
            else -> ""
        }
        return a + b + c
    }

    /**
     * Единственный вход в поиск с экрана: и лупа, и кнопка на клавиатуре.
     * Клавиатура убирается ДО поиска - она закрывает собой ровно ту
     * половину экрана, где появятся результаты.
     */
    private fun startSearch(q: EditText, holder: LinearLayout, status: TextView) {
        hideKeyboard(q)
        runSearch(q.text.toString(), holder, status)
    }

    /** Убрать системную клавиатуру: она закрывает собой результаты. */
    private fun hideKeyboard(v: View) {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    /**
     * Настройки поиска ОТДЕЛЬНЫМ ОКНОМ.
     *
     * ПОЧЕМУ НЕ ШТОРКОЙ НА ЭКРАНЕ
     * ---------------------------
     * Главный экран - жёсткий каркас без внешней прокрутки, и список
     * результатов берёт высоту остатком. Шторка в том же столбце заняла
     * почти всё: списку доставался ноль точек, результаты были и их
     * нельзя было увидеть.
     *
     * Ограничить высоту шторки означало бы подпорку - ту же самую, что
     * когда-то задавала точную высоту корням внутри прокрутки. Окно не
     * делит место со списком вовсе, и вопрос закрыт, а не отложен.
     *
     * @param onChange вызывается ПОСЛЕ закрытия окна: перерисовывать
     *        список на каждое нажатие внутри окна значило бы гонять поиск
     *        по всем страницам три раза подряд.
     */
    private fun showSearchOptions(query: String, onChange: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        // Своя прокрутка: у окна её нет, а содержимое выше маленького экрана.
        val pane = ScrollView(this).apply { addView(box) }

        var word = if (searchAuto) AUTO_WORD else searchWord
        var any = searchAny
        var where = searchWhere

        lateinit var rebuild: () -> Unit
        rebuild = {
            box.removeAllViews()
            // Живое объяснение ЗДЕСЬ, а не в шапке: тут есть место на
            // полную фразу, и человек видит, как меняется смысл запроса,
            // пока перебирает положения.
            box.addView(TextView(this).apply {
                text = VaultQuery.explain(VaultQuery.parse(query),
                    VaultQuery.Options(
                        if (word == AUTO_WORD) VaultQuery.WORD_PREFIX else word,
                        any, where))
                textSize = 13f
                setTextColor(0xFF8FC4D8.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(SURFACE_SUNKEN)
                    setStroke(dp(1), 0x338FC4D8)
                }
            }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) })
            box.addView(searchGroup("Как искать слова", listOf(
                Triple(AUTO_WORD, "Автоматически",
                    "Сначала по началу слова. Не нашлось — ищу внутри слов, " +
                        "потом по любому слову. Что сделал, напишу над списком"),
                Triple(VaultQuery.WORD_PREFIX, "По началу слова",
                    "«машин» найдёт «машина», «машины», «машинам». Обычный поиск"),
                Triple(VaultQuery.WORD_EXACT, "Слово целиком",
                    "«машин» найдёт только «машин». Когда находится слишком много"),
                Triple(VaultQuery.WORD_INSIDE, "Внутри слов",
                    "«асть» найдёт «часть» и «счастье». Когда помнишь середину")
            ), word) { word = it; rebuild() })

            box.addView(searchGroup("Сколько слов должно совпасть", listOf(
                Triple(0, "Все слова",
                    "Страница подходит, только если есть каждое слово запроса"),
                Triple(1, "Хотя бы одно",
                    "Шире: хватит любого слова. Когда не уверен в формулировке")
            ), if (any) 1 else 0) { any = it == 1; rebuild() })

            box.addView(searchGroup("Где искать", listOf(
                Triple(VaultQuery.IN_ALL, "Везде",
                    "Названия, теги и текст всех страниц"),
                Triple(VaultQuery.IN_TITLE, "Только названия",
                    "Быстро: страницы не читаются вовсе"),
                Triple(VaultQuery.IN_TAGS, "Только теги",
                    "Быстро: страницы не читаются вовсе")
            ), where) { where = it; rebuild() })

            box.addView(TextView(this).apply {
                text = "Кавычки ищут точное сочетание:\n" +
                    "\"красная машина\" — только эти слова подряд.\n\n" +
                    "Регистр и ё/е не важны никогда, настраивать это не нужно."
                textSize = 12f
                setTextColor(0xFF8A8A98.toInt())
                setPadding(dp(2), dp(6), 0, dp(4))
            })
        }
        rebuild()

        val d = AlertDialog.Builder(this)
            .setTitle("Как искать")
            .setView(pane)
            .setPositiveButton("Готово") { _, _ ->
                searchAuto = word == AUTO_WORD
                if (!searchAuto) searchWord = word
                searchAny = any
                searchWhere = where
                onChange()
            }
            .setNeutralButton("Сбросить") { _, _ ->
                searchAuto = true
                searchWord = VaultQuery.WORD_PREFIX
                searchAny = false
                searchWhere = VaultQuery.IN_ALL
                onChange()
            }
            .setNegativeButton("Отмена", null)
            .create()
        d.show()
    }

    /**
     * Группа взаимоисключающих положений.
     *
     * Выбранное отличается ЗАЛИВКОЙ, РАМКОЙ И ЗНАЧКОМ сразу: одного
     * признака мало - на трёх группах подряд человек не понимал, что из
     * подсвеченного выбрано им, а что подсвечено само.
     *
     * Под каждым положением стоит пример, а не определение: «машин найдёт
     * машина» понятнее, чем «префиксное совпадение».
     */
    private fun searchGroup(caption: String, opts: List<Triple<Int, String, String>>,
                            current: Int, onPick: (Int) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        box.addView(TextView(this).apply {
            text = caption
            textSize = 12f
            setTextColor(0xFF7A7488.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        for ((value, label, example) in opts) {
            val on = value == current
            val tint = if (on) getColor(R.color.accent_violet_bright) else 0xFF9A94A8.toInt()
            box.addView(TextView(this).apply {
                // Кружок слева говорит «выбрано» без цвета вовсе: цвет
                // читается быстрее, но одного цвета мало.
                text = (if (on) "◉  " else "○  ") + label + "\n" +
                    (if (on) "        " else "        ") + example
                textSize = 13f
                setTextColor(if (on) 0xFFEEEEEE.toInt() else 0xFF83808C.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (on) 0xFF241E33.toInt() else SURFACE_RAISED)
                    setStroke(dp(if (on) 2 else 1), if (on) tint else LINE_EDGE)
                }
                isClickable = true
                setOnClickListener { if (!busy && !on) onPick(value) }
            }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })
        }
        return box
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
            val parsed = VaultQuery.parse(text)

            // САМОПОДБОР. Пробуем от строгого к широкому и останавливаемся
            // на первом, что дало результат. Лишние проходы случаются
            // только когда НЕ НАШЛОСЬ - то есть там, где человек всё равно
            // ждёт и всё равно полез бы крутить настройки руками.
            var opts = VaultQuery.Options(searchWord, searchAny, searchWhere)
            var hits = withContext(Dispatchers.Default) { r.search(text, opts) }
            var relaxed = ""
            if (searchAuto && hits.isEmpty()) {
                val wide = VaultQuery.Options(VaultQuery.WORD_INSIDE, false, searchWhere)
                val h2 = withContext(Dispatchers.Default) { r.search(text, wide) }
                if (h2.isNotEmpty()) {
                    opts = wide; hits = h2; relaxed = "искал внутри слов"
                } else {
                    val any = VaultQuery.Options(VaultQuery.WORD_INSIDE, true, searchWhere)
                    val h3 = withContext(Dispatchers.Default) { r.search(text, any) }
                    if (h3.isNotEmpty()) {
                        opts = any; hits = h3; relaxed = "хватило одного слова из запроса"
                    }
                }
            }
            busy = false
            holder.removeAllViews()
            // Прокрутка наверх: иначе после нового поиска список открылся
            // бы на середине прежнего, и казалось бы, что ничего не нашли.
            listPane?.scrollTo(0, 0)
            status.text = when {
                hits.isEmpty() -> "Ничего не нашлось"
                // Самоподбор обязан признаваться, что сделал: иначе
                // непонятно, почему нашлось не то, что просили.
                relaxed.isNotEmpty() -> "Точно не нашлось — " + relaxed +
                    ". Совпадений: " + hits.size
                else -> "Найдено совпадений: " + hits.size +
                    (if (parsed.words.size > 1) " · сверху те, где совпало больше слов" else "")
            }
            // Что означают тона - словами, иначе цвет остаётся загадкой.
            // Показываем ТОЛЬКО те виды, что реально встретились: полная
            // таблица там, где всё совпало точно, - шум.
            if (hits.isNotEmpty()) {
                val kinds = LinkedHashSet<Int>()
                for (h in hits.take(40)) {
                    for (sp in VaultQuery.spans(h.snippet, parsed, opts)) kinds.add(sp[2])
                }
                if (kinds.size > 1) {
                    val legend = android.text.SpannableStringBuilder()
                    for (k in kinds.sorted()) {
                        val from = legend.length
                        legend.append("● ").append(VaultMark.nameOf(k))
                        legend.setSpan(
                            android.text.style.ForegroundColorSpan(VaultMark.colorOf(k)),
                            from, legend.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        legend.append("   ")
                    }
                    holder.addView(TextView(this@VaultActivity).apply {
                        // this. обязательно: в этой функции есть локальная
                        // `text` с запросом, и она перекрывает свойство
                        // вьюхи - присваивание ушло бы в неё.
                        this.text = legend
                        textSize = 11f
                        setPadding(dp(2), 0, 0, dp(8))
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
            if (hits.isEmpty() && !searchAuto) {
                // Подсказка вместо пустоты: пустой экран не говорит, что
                // делать дальше, а тут вариантов ровно два и оба рядом.
                holder.addView(TextView(this@VaultActivity).apply {
                    this.text = "Попробуй «Внутри слов» или «Любое из слов» — " +
                        "нажми на строку с объяснением сверху."
                    textSize = 13f
                    setTextColor(0xFFE0C08A.toInt())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            for (h in hits) {
                holder.addView(TextView(this@VaultActivity).apply {
                    val head = h.noteTitle +
                        (if (h.inHead) "" else "  ·  стр. " + (h.page + 1)) + "\n"
                    // Подсветка совпадений: глаз находит слово в отрывке
                    // мгновенно, а без неё отрывок приходится вычитывать.
                    val full = android.text.SpannableStringBuilder(head + h.snippet)
                    val dens = resources.displayMetrics.density
                    VaultMark.apply(full, h.snippet, head.length, parsed, opts, dens)
                    // В названии только цвет буквами: рамка в заголовке
                    // спорит с рамкой самой строки результата.
                    for (sp in VaultQuery.spans(h.noteTitle, parsed, opts)) {
                        full.setSpan(
                            android.text.style.ForegroundColorSpan(VaultMark.colorOf(sp[2])),
                            sp[0], sp[1], android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    this.text = full
                    textSize = 15f
                    setTextColor(0xFFDDDDE5.toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(SURFACE_RAISED)
                        // Найденное в названии обведено: это не такой же
                        // результат, как совпадение где-то на сотой странице.
                        setStroke(dp(1), if (h.inHead) 0x66B9A6E8 else LINE_EDGE)
                    }
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    setOnClickListener { previewHit(h, text, parsed, opts) }
                }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
            }
        }
    }

    /**
     * Предпросмотр найденного.
     *
     * ЗАЧЕМ ЛИШНЕЕ ОКНО
     * ----------------
     * Отрывок в списке короткий, и по нему часто не понять, та это заметка
     * или нет. Открывать заметку ради проверки дорого: она греется, список
     * теряется, и надо возвращаться. Окно показывает кусок втрое шире, и
     * отказаться стоит одно нажатие.
     *
     * Переход отсюда идёт на ТО ЖЕ место, что нашёл поиск: смещение
     * передаётся числом, а не ищется заново. Второй поиск по странице мог
     * бы найти другое вхождение, и прыжок ушёл бы не туда.
     */
    private fun previewHit(h: VaultRepo.Hit, query: String,
                           parsed: VaultQuery.Parsed, opts: VaultQuery.Options) {
        val r = repo ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val page = withContext(Dispatchers.Default) { r.readPage(h.noteId, h.page) } ?: ""
            busy = false

            val radius = 320
            var from = maxOf(0, h.at - radius)
            var to = minOf(page.length, h.at + radius)
            while (from > 0 && !page[from].isWhitespace()) from--
            while (to < page.length && !page[to].isWhitespace()) to++
            val piece = page.substring(from, to)

            val body = android.text.SpannableStringBuilder(
                (if (from > 0) "…" else "") + piece + (if (to < page.length) "…" else ""))
            val shift = if (from > 0) 1 else 0
            VaultMark.apply(body, piece, shift, parsed, opts,
                resources.displayMetrics.density)

            val text = TextView(this@VaultActivity).apply {
                this.text = body
                textSize = 15f
                setTextColor(0xFFDDDDE5.toInt())
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            val pane = ScrollView(this@VaultActivity).apply { addView(text) }

            AlertDialog.Builder(this@VaultActivity)
                .setTitle(h.noteTitle + " · стр. " + (h.page + 1))
                .setView(pane)
                .setPositiveButton("Открыть здесь") { _, _ ->
                    pendingFind = query
                    pendingFindAt = h.at
                    openForRead(h.noteId, h.page)
                }
                .setNegativeButton("Не то", null)
                .show()
        }
    }

    /** Открыть заметку на чтение. Правка - по вкладке. */
    private fun openForRead(noteId: Long, idx: Int) {
        openingForRead = true
        readFromEdit = false
        repo?.let { rp ->
            lifecycleScope.launch {
                focusTag = rp.notes().firstOrNull { it.id == noteId }
                    ?.tags?.firstOrNull()?.trim()?.lowercase()
            }
        }
        // Открытие греет заметку. Слабо: открывают и случайно.
        repo?.let { r -> lifecycleScope.launch { r.touch(noteId, VaultHeat.W_OPEN) } }
        openNote(noteId, idx)
    }

    /** Сохранить открытую страницу и уйти. Порядок важен: сначала запись. */
    private fun leavePage(then: () -> Unit) {
        val r = repo
        val text = editor?.text?.toString()   // в просмотре null — сохранять нечего
        val id = openNoteId
        val idx = openIdx
        if (r == null || text == null || id == 0L) { then(); return }
        lifecycleScope.launch {
            try {
                val before = r.readPage(id, idx)
                r.writePage(id, idx, text)
                // Греем только при РЕАЛЬНОЙ правке: заход и выход без
                // изменений не должен поднимать заметку наверх.
                if (before != text) r.touch(id, VaultHeat.W_EDIT)
            } catch (e: Exception) { }
            then()
        }
    }

    private fun openNote(noteId: Long, idx: Int) {
        val r = repo ?: return
        lifecycleScope.launch {
            openPages = maxOf(1, r.pageCount(noteId))
            openTags = r.notes().firstOrNull { it.id == noteId }?.tags ?: emptyList()
            val safeIdx = idx.coerceIn(0, openPages - 1)
            val text = r.readPage(noteId, safeIdx) ?: ""
            val top = r.wordsOf(noteId, safeIdx)
            openNoteId = noteId
            openIdx = safeIdx
            // Заметку открывают ЧИТАТЬ в разы чаще, чем править, поэтому
            // чтение - режим по умолчанию, а правка по вкладке. Пустая
            // страница - исключение: читать там нечего, и лишний тап был
            // бы издевательством.
            if (openingForRead) {
                openingForRead = false
                preview = text.isNotBlank()
            }
            drawPage(text, top)
            // Страница открывается СВЕРХУ: листая вперёд, человек ждёт
            // начала новой страницы, а не той же высоты прокрутки.
            scrollToTop()
        }
    }

    /**
     * Страница в правке. ЖЁСТКИЙ КАРКАС: шапка сверху, инструменты снизу,
     * листается только текст.
     *
     * Раньше листалась вся страница, а поле ввода росло с текстом: чтобы
     * добраться до кнопок, приходилось прокручивать весь текст, а курсор
     * при наборе уводил экран вниз.
     *
     * У поля ввода НЕТ своей ScrollingMovementMethod: она ломает выделение
     * текста - выделить всё становится невозможно. Поле с ограниченной
     * высотой прокручивается само, без всяких подпорок.
     */
    private fun drawPage(text: String, top: List<String> = emptyList()) {
        if (preview) { drawPreview(text, top); return }
        screen = Screen.PAGE
        mount(scrollable = false)
        root.removeAllViews()
        root.setPadding(dp(16), dp(14), dp(16), dp(8))
        val r = repo ?: return
        val noteId = openNoteId

        // В шапке ИМЯ заметки, а не слово "Правка": имя - это то, по чему
        // человек узнаёт, где он. Тап по имени переименовывает, тап по
        // строке страниц - переходит на страницу.
        pageHeader(noteId, "✎ правка")
        chipsRow(openTags, noteId)

        val e = EditText(this).apply {
            setText(text)
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            setTextColor(0xFFEEEEEE.toInt())
            setBackgroundColor(SURFACE_SUNKEN)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setLineSpacing(dp(4).toFloat(), 1f)
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(VaultRepo.MAX_PAGE_CHARS))
        }
        // Вес 1: поле занимает всё, что осталось между шапкой и подвалом.
        root.addView(e, LinearLayout.LayoutParams(-1, 0, 1f))
        editor = e
        pendingFind?.let { q -> pendingFind = null; flashMatch(e, q) }

        // Быстрый прыжок по длинной странице. При двадцати тысячах
        // символов свайпами до конца добираться долго, а мысль обычно
        // дописывается именно в конец.
        val jump = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(jump, LinearLayout.LayoutParams(-1, -2))

        val counter = TextView(this).apply {
            this.text = e.text.length.toString() + " / " + VaultRepo.MAX_PAGE_CHARS
            textSize = 12f
            setTextColor(0xFF7A7A88.toInt())
            setPadding(0, dp(6), 0, dp(2))
        }
        jump.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
        jump.addView(smallJump("↑ верх") {
            e.setSelection(0)
            e.scrollTo(0, 0)
        })
        jump.addView(smallJump("↓ низ") {
            e.setSelection(e.text.length)
            val last = e.layout?.getLineTop(e.lineCount) ?: 0
            e.scrollTo(0, maxOf(0, last - e.height + e.paddingTop + e.paddingBottom))
        })
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                counter.text = (s?.length ?: 0).toString() + " / " + VaultRepo.MAX_PAGE_CHARS
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        linksRow(text)

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        nav.addView(tabButton("Назад", VaultIcon.Kind.PREV, VaultIcon.tintFor(VaultIcon.Kind.PREV)) {
            if (openIdx > 0) leavePage { openNote(noteId, openIdx - 1) }
        })
        nav.addView(tabButton("Вперёд", VaultIcon.Kind.NEXT, VaultIcon.tintFor(VaultIcon.Kind.NEXT)) {
            if (openIdx + 1 < openPages) leavePage { openNote(noteId, openIdx + 1) }
        })
        nav.addView(tabButton("Стр.", VaultIcon.Kind.PAGE_PLUS,
            VaultIcon.tintFor(VaultIcon.Kind.PAGE_PLUS)) {
            leavePage {
                lifecycleScope.launch {
                    val idx = r.addPage(noteId)
                    if (idx < 0) toast("Предел " + VaultRepo.MAX_PAGES + " страниц")
                    else openNote(noteId, idx)
                }
            }
        })
        nav.addView(tabButton("Заголовок", VaultIcon.Kind.HEADING,
            VaultIcon.tintFor(VaultIcon.Kind.HEADING)) {
            val (text2, cur) = VaultText.cycleHeading(e.text.toString(), e.selectionEnd)
            e.setText(text2)
            e.setSelection(cur.coerceIn(0, text2.length))
        })

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tools, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        tools.addView(tabButton("Фото", VaultIcon.Kind.IMAGE, VaultIcon.tintFor(VaultIcon.Kind.IMAGE)) {
            pickImage.launch("image/*")
        })
        tools.addView(tabButton("Чтение", VaultIcon.Kind.EYE, VaultIcon.tintFor(VaultIcon.Kind.EYE)) {
            // Явный переход в чтение с пометкой, откуда пришли: назад
            // вернёт сюда же, в правку.
            leavePage {
                readFromEdit = true
                preview = true
                openingForRead = false
                openNote(noteId, openIdx)
            }
        })
        tools.addView(tabButton("Тропы", VaultIcon.Kind.TRAIL, VaultIcon.tintFor(VaultIcon.Kind.TRAIL)) {
            leavePage { showTrails(noteId, openIdx) }
        })
        tools.addView(tabButton("Ещё", VaultIcon.Kind.LIST, VaultIcon.tintFor(VaultIcon.Kind.LIST)) {
            moreMenu(noteId, e)
        })
    }

    /**
     * Редкие действия страницы одним окном.
     *
     * В подвале помещается восемь вкладок, а действий больше. Частое
     * остаётся под пальцем, редкое уходит сюда - иначе подвал разрастается
     * и снова начинает выталкивать текст.
     */
    /**
     * Редкие действия страницы одним окном.
     *
     * Не голый список строк, а строки со значками и пояснениями: список
     * из четырёх одинаковых надписей читается как текст, и человек
     * каждый раз перечитывает все четыре.
     */
    private fun moreMenu(noteId: Long, e: EditText?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Ещё")
            .setView(box)
            .setNegativeButton("Закрыть", null)
            .create()

        box.addView(menuRow(VaultIcon.Kind.LIST, "Разделы заметки",
            "Заголовки по ВСЕМ страницам — переход к любому") {
            dialog.dismiss()
            showSections(noteId)
        })
        box.addView(menuRow(VaultIcon.Kind.HISTORY, "История правок",
            "Пятьдесят версий этой страницы и развилки") {
            dialog.dismiss()
            leavePage { showHistory(noteId, openIdx) }
        })
        box.addView(menuRow(VaultIcon.Kind.PREV, "К списку заметок",
            "То же, что кнопка назад в шапке") {
            dialog.dismiss()
            leavePage { showNotes() }
        })
        box.addView(menuRow(VaultIcon.Kind.TRASH, "Удалить заметку",
            "Со всеми страницами и картинками. Навсегда") {
            dialog.dismiss()
            askDelete(noteId)
        })
        dialog.show()
    }

    /** Строка меню: значок, название, пояснение. */
    private fun menuRow(icon: VaultIcon.Kind, title: String, explain: String,
                        action: () -> Unit): View {
        val tint = VaultIcon.tintFor(icon)
        val bg = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(SURFACE_SUNKEN)
            setStroke(dp(1), (tint and 0xFFFFFF) or 0x66000000)
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x2AFFFFFF), bg, null)
            setOnClickListener { action() }
        }
        line.addView(ImageView(this).apply {
            setImageDrawable(VaultIcon(icon, tint, dp(20)))
        }, LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(tint)
        })
        col.addView(TextView(this).apply {
            text = explain
            textSize = 12f
            setTextColor(0xFF8A8A98.toInt())
        })
        line.addView(col, LinearLayout.LayoutParams(-1, -2))
        line.layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        return line
    }

    /**
     * Разделы ВСЕЙ заметки, а не одной страницы.
     *
     * В заметке на сотни страниц оглавление отдельной страницы почти
     * бесполезно: искать надо по всему тексту. Раньше здесь было
     * "только в правке" - бесполезный ответ на осмысленный вопрос.
     */
    private fun showSections(noteId: Long) {
        val r = repo ?: return
        busy = true
        toast("Собираю разделы…")
        lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) { r.sections(noteId) }
            busy = false
            if (list.isEmpty()) {
                toast("Заголовков нет. Начни строку с # или нажми «Заголовок»")
                return@launch
            }
            val labels = list.map { s ->
                "  ".repeat(s.level - 1) + s.text + "   · стр. " + (s.page + 1)
            }.toTypedArray()
            AlertDialog.Builder(this@VaultActivity)
                .setTitle("Разделы · всего " + list.size)
                .setItems(labels) { _, which ->
                    val s = list[which]
                    leavePage {
                        pendingFind = s.text
                        openNote(noteId, s.page)
                    }
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
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
        rebuildBottom = null
        rowViews.clear()
        rowTexts.clear()
        selecting = false
        chosen.clear()
        focusTag = null
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
    /**
     * Страница в чтении. Тот же каркас: шапка и подвал закреплены,
     * листается только содержимое.
     */
    private fun drawPreview(text: String, top: List<String>) {
        screen = Screen.PREVIEW
        mount(scrollable = false)
        root.removeAllViews()
        root.setPadding(dp(16), dp(14), dp(16), dp(8))
        val noteId = openNoteId
        val im = images

        pageHeader(noteId, "чтение")
        chipsRow(openTags, noteId)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pane = ScrollView(this).apply {
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))

        for (b in VaultText.blocks(text)) {
            when (b) {
                is VaultText.Block.Head -> body.addView(TextView(this).apply {
                    this.text = b.text
                    textSize = when (b.level) { 1 -> 24f; 2 -> 20f; else -> 17f }
                    setTextColor(0xFFF2F2F7.toInt())
                    setPadding(0, dp(14), 0, dp(6))
                })
                is VaultText.Block.Para -> body.addView(TextView(this).apply {
                    textSize = 16f
                    setTextColor(0xFFDDDDE5.toInt())
                    setPadding(0, dp(4), 0, dp(8))
                    setLineSpacing(dp(4).toFloat(), 1f)
                    // Выделение и переходы конкурируют за одну настройку
                    // поля: абзац со ссылками отдан переходам, без ссылок -
                    // выделению.
                    if (VaultText.linkSpans(b.text).isEmpty()) {
                        this.text = b.text
                        setTextIsSelectable(true)
                    } else {
                        this.text = linkify(b.text)
                        movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    }
                })
                is VaultText.Block.Img -> {
                    val bmp = im?.load(b.id)
                    if (bmp == null) {
                        body.addView(TextView(this).apply {
                            this.text = "[картинка недоступна]"
                            textSize = 14f
                            setTextColor(0xFF7A7A88.toInt())
                            setPadding(0, dp(6), 0, dp(6))
                        })
                    } else {
                        body.addView(ImageView(this).apply {
                            setImageBitmap(bmp)
                            adjustViewBounds = true
                            isClickable = true
                            setOnClickListener { showImage(b.id) }
                        }, LinearLayout.LayoutParams(-1, -2).also {
                            it.topMargin = dp(8); it.bottomMargin = dp(8)
                        })
                    }
                }
            }
        }

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tools, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        tools.addView(tabButton("Назад", VaultIcon.Kind.PREV, VaultIcon.tintFor(VaultIcon.Kind.PREV)) {
            if (openIdx > 0) openNote(noteId, openIdx - 1)
        })
        tools.addView(tabButton("Вперёд", VaultIcon.Kind.NEXT, VaultIcon.tintFor(VaultIcon.Kind.NEXT)) {
            if (openIdx + 1 < openPages) openNote(noteId, openIdx + 1)
        })
        tools.addView(tabButton("Правка", VaultIcon.Kind.PENCIL,
            VaultIcon.tintFor(VaultIcon.Kind.PENCIL)) {
            readFromEdit = false
            preview = false
            openingForRead = false
            openNote(noteId, openIdx)
        })
        tools.addView(tabButton("Ещё", VaultIcon.Kind.LIST, VaultIcon.tintFor(VaultIcon.Kind.LIST)) {
            moreMenu(noteId, null)
        })
    }

    private fun showHistory(noteId: Long, idx: Int) {
        mount(scrollable = true)
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
            setBackgroundColor(SURFACE_SUNKEN)
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
                    setBackgroundColor(SURFACE_RAISED)
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
            secondaryButton("←  Назад к странице").setOnClickListener { goBack() }
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

            Screen.ARCHIVE -> showNotes()

            Screen.GUARD -> showNotes()

            // Из показа частей - только к списку: возврат на экран
            // разделения предложил бы разделить ещё раз, а прежние части
            // к этому моменту уже недействительны.
            Screen.SHARES -> showNotes()

            Screen.SHARE_IN -> showEntrance()

            // Уход с разбора - отказ от импорта целиком: половину списка
            // мы не вливаем молча.
            Screen.IMPORT -> showNotes()

            Screen.ROOTS -> showNotes()

            Screen.TRAILS -> openNote(histNoteId, histIdx)

            Screen.IMAGE -> {
                preview = true
                openNote(openNoteId, openIdx)
            }

            // Чтение - главный вид заметки, из него выходим к списку.
            // Раньше отсюда возвращало в ПРАВКУ: открыл заметку, нажал
            // назад - и оказался в редакторе, которого не просил.
            // Из чтения назад: в правку, если пришли оттуда, иначе к
            // списку. Смотреть оформление не должно стоить повторного
            // открытия заметки.
            Screen.PREVIEW -> if (readFromEdit) {
                readFromEdit = false
                preview = false
                openNote(openNoteId, openIdx)
            } else showNotes()

            // Правка возвращает в чтение, а не сразу к списку: цепочка
            // читается как правка -> чтение -> список, в обе стороны
            // одинаково. Подтверждение больше не нужно - текст
            // сохраняется, и назад ведёт не наружу, а на шаг.
            Screen.PAGE -> leavePage {
                val hadText = (editor?.text?.length ?: 0) > 0
                if (hadText) {
                    preview = true
                    openNote(openNoteId, openIdx)
                } else {
                    showNotes()
                }
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

    /**
     * Картинка на весь экран.
     *
     * Отдельный экран внутри той же Activity, а не диалог: диалог обрезал
     * бы жесты по своим краям, а картинка должна занимать всё.
     */
    private fun showImage(id: String) {
        mount(scrollable = true)
        val im = images ?: return
        val bmp = im.load(id)
        if (bmp == null) { toast("Картинка недоступна"); return }
        screen = Screen.IMAGE
        root.removeAllViews()
        editor = null

        val zoom = VaultZoomView(this)
        zoom.setBitmap(bmp)
        // Высота под экран: вьюха сама вписывает и центрирует картинку.
        root.addView(zoom, LinearLayout.LayoutParams(-1,
            (resources.displayMetrics.heightPixels * 0.78f).toInt()))

        root.addView(TextView(this).apply {
            text = "Щипок — приблизить · двойной тап — туда и обратно · " +
                "перетаскивание — двигать"
            textSize = 12f
            setTextColor(0xFF7A7A88.toInt())
            setPadding(0, dp(8), 0, dp(8))
        })
        secondaryButton("Вписать целиком").setOnClickListener { zoom.reset() }
        secondaryButton("←  Назад к странице").setOnClickListener { goBack() }
    }

    /**
     * Подсветить найденное и погасить подсветку за полторы секунды.
     *
     * Глаз ловит движение раньше, чем читает буквы. Постоянная подсветка
     * через минуту становится мусором на экране, поэтому она гаснет сама:
     * своё дело она уже сделала.
     */
    /**
     * Подсветить и показать найденное место.
     *
     * ЧТО БЫЛО СЛОМАНО
     * ---------------
     * Место искалось ПОДСТРОКОЙ. С переходом на поиск словами запрос
     * «красная машина» подстрокой не находится нигде, и прыжок молча не
     * срабатывал: заметка открывалась на первой строке.
     *
     * Теперь место приходит числом от самого поиска, а если его нет -
     * ищется теми же правилами, какими нашли.
     */
    private fun flashMatch(e: EditText, query: String) {
        val whole = e.text.toString()
        val parsed = VaultQuery.parse(query)
        val opts = VaultQuery.Options(searchWord, searchAny, searchWhere)
        val at = pendingFindAt.also { pendingFindAt = -1 }
        val pos = if (at in 0 until whole.length) at
                  else VaultQuery.firstHit(whole, parsed, opts)
        if (pos < 0) return
        val spans = VaultQuery.spans(whole, parsed, opts)
        val end = spans.firstOrNull { it[0] == pos }?.get(1)
            ?: minOf(e.text.length, pos + query.length)
        val span = android.text.style.BackgroundColorSpan(0xFF6A3B7A.toInt())
        e.text.setSpan(span, pos, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        e.setSelection(pos)

        val anim = android.animation.ValueAnimator.ofFloat(1f, 0f)
        anim.duration = 1500L
        anim.startDelay = 400L
        anim.addUpdateListener { v ->
            val k = v.animatedValue as Float
            val alpha = (k * 255).toInt().coerceIn(0, 255)
            e.text.setSpan(
                android.text.style.BackgroundColorSpan((alpha shl 24) or 0x6A3B7A),
                pos, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        anim.start()
    }

    /**
     * Ссылки [[так]] в режиме просмотра: цветные и нажимаемые.
     *
     * Скобки не прячем. Спрятать их значило бы сделать вид, что заметка
     * набрана в каком-то особом редакторе, — а она набрана обычным
     * текстом, и в правке ты видишь ровно то же, что здесь.
     */
    private fun linkify(text: String): CharSequence {
        val spans = VaultText.linkSpans(text)
        if (spans.isEmpty()) return text
        val sp = android.text.SpannableString(text)
        val accent = getColor(R.color.accent_violet_bright)
        for (r in spans) {
            val name = text.substring(r[0] + 2, r[1] - 2).trim()
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(w: View) = openByTitle(name)
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = accent
                    ds.isUnderlineText = false
                }
            }, r[0], r[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun openByTitle(name: String) {
        val r = repo ?: return
        lifecycleScope.launch {
            val target = r.notes().firstOrNull { VaultText.sameTitle(it.title, name) }
            if (target == null) {
                // Ссылка на несуществующую заметку - не ошибка, а замысел:
                // человек записал название раньше, чем завёл её.
                askText("Заметки «" + name + "» нет. Создать?", name) { v ->
                    lifecycleScope.launch {
                        val id = r.createNote(if (v.isBlank()) name else v)
                        openForRead(id, 0)
                    }
                }
            } else {
                openForRead(target.id, 0)
            }
        }
    }

    /**
     * Тропы от этой страницы. Три уровня близости рисуются по-разному,
     * чтобы список не был стеной одинаковых строк.
     */
    private fun showTrails(noteId: Long, idx: Int) {
        mount(scrollable = true)
        val r = repo ?: return
        screen = Screen.TRAILS
        histNoteId = noteId
        histIdx = idx
        root.removeAllViews()
        editor = null
        title("Тропы")

        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, 0, 0, dp(10))
            text = "Ищу связи…"
        }
        root.addView(status)
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(holder)
        secondaryButton("←  Назад к странице").setOnClickListener { goBack() }

        lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) { r.trails(noteId, idx) }
            status.text = if (list.isEmpty())
                "Связей пока нет. Поставь [[Название]] в тексте — появится тропа."
            else "Связей: " + list.size +
                "\n│ поставил сам   ┆ сослались на тебя   · похожее по словам"
            for (t in list) {
                val mark = when (t.kind) {
                    VaultRepo.TrailKind.DIRECT -> "│  "
                    VaultRepo.TrailKind.BACK -> "┆  "
                    VaultRepo.TrailKind.KIN -> "·  "
                }
                val tail = when (t.kind) {
                    VaultRepo.TrailKind.DIRECT -> "ты сослался"
                    VaultRepo.TrailKind.BACK -> "сослались на тебя · стр. " + (t.page + 1)
                    VaultRepo.TrailKind.KIN -> "общих слов: " + t.strength +
                        " · стр. " + (t.page + 1)
                }
                holder.addView(TextView(this@VaultActivity).apply {
                    text = mark + t.title + "\n" + mark + tail
                    textSize = 15f
                    setTextColor(when (t.kind) {
                        VaultRepo.TrailKind.DIRECT -> 0xFFEEEEEE.toInt()
                        VaultRepo.TrailKind.BACK -> 0xFFCFCFDA.toInt()
                        else -> 0xFF9A9AA5.toInt()
                    })
                    setBackgroundColor(SURFACE_RAISED)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    setOnClickListener { openForRead(t.noteId, t.page) }
                }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })
            }
        }
    }

    /**
     * Оглавление страницы по заголовкам. Не отдельный экран, а список:
     * выбрал — курсор встал на заголовок, страница прокрутилась туда.
     */
    private fun showOutline(e: EditText) {
        val text = e.text.toString()
        val heads = VaultText.outline(text)
        if (heads.isEmpty()) {
            toast("Заголовков нет. Начни строку с # или ##")
            return
        }
        val labels = heads.map { h -> "  ".repeat(h.level - 1) + h.text }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Оглавление")
            .setItems(labels) { _, which ->
                val pos = text.indexOf(heads[which].text)
                if (pos >= 0) {
                    e.requestFocus()
                    e.setSelection(pos)
                }
            }
            .show()
    }

    /**
     * Корни: все классы тайника одним срезом.
     *
     * Тап по жиле фильтрует список заметок по этому классу — картинка не
     * просто красивая, она рабочий указатель.
     */
    /**
     * Корни целиком: числа, закономерности и карта.
     *
     * Экран-лаборатория, а не картинка. Всё считается по заголовкам
     * заметок - текст страниц не читается, поэтому открывается мгновенно.
     */
    private fun showRoots() {
        mount(scrollable = true)
        screen = Screen.ROOTS
        root.removeAllViews()
        root.setPadding(dp(20), dp(20), dp(20), dp(28))
        editor = null
        title("Корни", "Структура тайника целиком")

        val r = repo ?: return
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF9A9AA5.toInt())
            text = "Считаю…"
        }
        root.addView(status)
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(holder)
        secondaryButton("←  К списку заметок").setOnClickListener { goBack() }

        lifecycleScope.launch {
            val lab = withContext(Dispatchers.Default) { r.lab() }
            val s = lab.summary
            status.text = s.notes.toString() + " заметок · " + s.classes + " классов · " +
                s.links + " связей"

            holder.addView(statBlock("Связность", listOf(
                "классов на заметку" to "%.1f".format(s.avgClassesPerNote),
                "заметок с классами" to s.classified.toString() + " из " + s.notes,
                "без классов" to s.lonely.toString(),
                "крупнейший класс" to ((s.biggestClass ?: "—") + " · " + s.biggestClassCount)
            )))

            if (lab.patterns.isNotEmpty()) {
                holder.addView(sectionTitle("Замеченное"))
                for (p in lab.patterns) {
                    holder.addView(patternRow(p))
                }
            }

            if (lab.classes.isNotEmpty()) {
                holder.addView(sectionTitle("Классы"))
                val maxC = lab.classes.maxOf { it.count }
                for (c in lab.classes) {
                    holder.addView(classBar(c, maxC))
                }
            }

            if (lab.together.isNotEmpty()) {
                holder.addView(sectionTitle("Пары классов"))
                for ((pair, w) in lab.together.entries.sortedByDescending { it.value }.take(12)) {
                    holder.addView(TextView(this@VaultActivity).apply {
                        // Число общих заметок, а не выдуманный процент
                        // сходства: считать мы умеем именно это.
                        text = pair.first + "  ↔  " + pair.second + "   ·   общих заметок: " + w
                        textSize = 14f
                        setTextColor(0xFFCFCFDA.toInt())
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    })
                }
            }

            holder.addView(sectionTitle("Карта"))
            holder.addView(TextView(this@VaultActivity).apply {
                text = "Тап по жиле — показать её связи. Второй тап — отобрать класс."
                textSize = 12f
                setTextColor(0xFF7A7A88.toInt())
                setPadding(0, 0, 0, dp(8))
            })
            val view = VaultRootsView(this@VaultActivity)
            val height = (resources.displayMetrics.heightPixels * 0.42f).toInt()
            val scroll = HorizontalScrollView(this@VaultActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(view, FrameLayout.LayoutParams(-2, height))
            }
            holder.addView(scroll, LinearLayout.LayoutParams(-1, height))

            val members = r.classMembers()
            val ordered = lab.classes.sortedByDescending { it.heat }
            view.setData(
                ordered.map { c ->
                    VaultRootsView.Strand(c.name, c.count, VaultHues.color(c.hue, c.count),
                        (members[c.name] ?: emptyList())
                            .map { VaultRootsView.Node(it.first, it.second) })
                },
                lab.together.entries.sortedByDescending { it.value }.take(40)
                    .map { VaultRootsView.Weave(it.key.first, it.key.second, it.value) },
                { name -> pendingTag = name; focusTag = name; showNotes() },
                { id -> openForRead(id, 0) }
            )
        }
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFFF2F0F7.toInt())
        setPadding(0, dp(18), 0, dp(8))
    }

    /** Блок из пар «что — сколько». */
    private fun statBlock(title: String, rows: List<Pair<String, String>>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(SURFACE_SUNKEN)
                setStroke(dp(1), LINE_EDGE)
            }
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFFA9A4BC.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        for ((k, v) in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(TextView(this).apply {
                text = k
                textSize = 14f
                setTextColor(0xFF8A8A98.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(TextView(this).apply {
                text = v
                textSize = 14f
                setTextColor(0xFFEEEEEE.toInt())
            })
            box.addView(line, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })
        }
        box.layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) }
        return box
    }

    /** Найденная закономерность: значок, суть, объяснение. */
    private fun patternRow(p: VaultInsight.Pattern): View {
        val icon = when (p.kind) {
            VaultInsight.Pattern.Kind.TWINS -> VaultIcon.Kind.TRAIL
            VaultInsight.Pattern.Kind.CENTER -> VaultIcon.Kind.ROOTS
            VaultInsight.Pattern.Kind.CHAIN -> VaultIcon.Kind.JUMP
            VaultInsight.Pattern.Kind.ORPHAN -> VaultIcon.Kind.TAG
            else -> VaultIcon.Kind.LIST
        }
        return menuRow(icon, p.title, p.detail) { }
    }

    /** Класс полосой: длина по числу заметок, цвет свой. */
    private fun classBar(c: VaultRepo.ClassInfo, maxCount: Int): View {
        val color = VaultHues.color(c.hue, c.count)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(0, dp(5), 0, dp(5))
            setOnClickListener { pendingTag = c.name; focusTag = c.name; showNotes() }
        }
        line.addView(TextView(this).apply {
            text = c.name
            textSize = 14f
            setTextColor(color)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(dp(110), -2))
        val bar = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(color)
            }
        }
        val share = c.count.toFloat() / maxOf(1, maxCount)
        line.addView(bar, LinearLayout.LayoutParams(0, dp(10), share.coerceAtLeast(0.06f)))
        line.addView(TextView(this).apply {
            text = "  " + c.count
            textSize = 13f
            setTextColor(0xFF8A8A98.toInt())
        })
        // Пустой довесок, чтобы полосы разной длины были сравнимы между
        // собой, а не растягивались каждая на всю ширину.
        line.addView(View(this), LinearLayout.LayoutParams(0, dp(1),
            (1f - share).coerceAtLeast(0.01f)))
        return line
    }

    private fun chipsRow(tags: List<String>, noteId: Long) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, FrameLayout.LayoutParams(-2, -2))
        }

        val plus = chip(if (tags.isEmpty()) "＋ класс" else "＋", 0xFF9FD9A8.toInt()) {
            val r = repo ?: return@chip
            askText("Классы через запятую", tags.joinToString(", ")) { v ->
                // Сначала сохранить страницу: правка классов перечитывает
                // заметку из базы, и несохранённый текст пропал бы.
                leavePage {
                    lifecycleScope.launch {
                        r.setTags(noteId, v)
                        openTags = VaultText.parseTags(v)
                        classHues = r.classes().first.associate { it.name to it.hue }
                        toast("Классы сохранены")
                        openNote(noteId, openIdx)
                    }
                }
            }
        }

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(plus)
        line.addView(scroll, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(line, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })

        for (t in tags) {
            val hue = classHues[t.trim().lowercase()]
            val c = if (hue == null) 0xFF8A8A98.toInt() else VaultHues.color(hue, 12)
            row.addView(chip("#" + t, c) { pendingTag = t; showNotes() })
        }
    }

    /**
     * Полоска ссылок страницы под редактором.
     *
     * В правке текст обычный, и нажать [[ссылку]] прямо в нём нельзя:
     * поле ввода отдано набору. Иконки внутри строки ломали бы набор ещё
     * сильнее. Поэтому ссылки собраны отдельной полоской - переход есть,
     * а текст не тронут.
     */
    private fun linksRow(text: String) {
        val names = VaultText.linkRefs(text)
        if (names.isEmpty()) return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        val accent = getColor(R.color.accent_violet_bright)
        for (n in names) row.addView(chip("→ " + n, accent) { openByTitle(n) })
    }

    /** Квадратная кнопка с одной иконкой. Значок центрируется сам,
     *  потому что это ImageView, а не составной элемент текста. */
    private fun iconButton(icon: VaultIcon.Kind, describe: String, tint: Int,
                           action: () -> Unit): View {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(SURFACE_SUNKEN)
            setStroke(dp(1), LINE_EDGE)
        }
        val v = ImageView(this).apply {
            contentDescription = describe
            setImageDrawable(VaultIcon(icon, tint, dp(22)))
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x2AFFFFFF), bg, null
            )
            setOnClickListener { if (!busy) action() }
        }
        v.layoutParams = LinearLayout.LayoutParams(dp(52), dp(46)).also {
            it.marginStart = dp(8)
        }
        return v
    }

    /** Компактная кнопка прыжка по тексту. */
    private fun smallJump(label: String, action: () -> Unit): TextView {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(SURFACE_SUNKEN)
            setStroke(dp(1), LINE_EDGE)
        }
        val t = TextView(this).apply {
            text = label
            textSize = 12f
            isSingleLine = true
            gravity = Gravity.CENTER
            setTextColor(0xFFA9A4BC.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), bg, null)
            setOnClickListener { action() }
        }
        t.layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(6) }
        return t
    }

    /** Маленькая нажимаемая метка. Цвет несёт смысл, форма одинакова. */
    private fun chip(label: String, color: Int, action: () -> Unit): TextView {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(SURFACE_SUNKEN)
            setStroke(dp(1), (color and 0xFFFFFF) or 0x77000000)
        }
        val t = TextView(this).apply {
            text = label
            textSize = 12f
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            minHeight = dp(34)
            setTextColor(color)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), bg, null
            )
            setOnClickListener { if (!busy) action() }
        }
        t.layoutParams = LinearLayout.LayoutParams(-2, dp(34)).also { it.marginEnd = dp(6) }
        return t
    }

    /**
     * Экран архива: выгрузка и загрузка.
     *
     * Здесь же прямым текстом сказано главное: архив тайника и бэкап
     * StepCore - РАЗНЫЕ файлы, и переносить их надо по отдельности.
     * Это не мелкий шрифт: человек, потерявший телефон с одним из двух,
     * узнает об этом слишком поздно.
     */
    /**
     * Открытие сессии - ЕДИНСТВЕННЫМ путём.
     *
     * Раньше VaultSession.open(), создание repo и images стояли в трёх
     * местах подряд. Появление четвёртой вещи, которую надо сделать при
     * входе (льгота), означало бы три места, где её можно забыть. Теперь
     * место одно.
     */
    private fun openSession(key: ByteArray) {
        VaultSession.open(key, store.read()?.grace ?: VaultFile.GRACE_90S)
        repo = VaultRepo(this, key)
        images = VaultImages(this, key)
    }

    /**
     * Обведённый блок с заголовком.
     *
     * ЗАЧЕМ РАМКИ
     * -----------
     * Экран рос кусками, и получилась лента: строка про число тайников,
     * четыре карточки льготы и красная кнопка стояли подряд одним
     * столбцом. Предупреждение про необратимость зрительно не
     * принадлежало кнопке, к которой относится - между ними просто был
     * отступ, такой же, как между всем остальным.
     *
     * Рамка отвечает на вопрос "где это кончается", отступ - не отвечает.
     *
     * ПОЧЕМУ ТОН РАЗНЫЙ У КАЖДОГО БЛОКА
     * ---------------------------------
     * Тот же довод, что и у значков: цвет опознаётся боковым зрением
     * раньше очертания. Серый блок - справка, янтарный - настройка,
     * красный - необратимое. Различать их по заголовкам значило бы
     * читать экран целиком каждый раз.
     *
     * @return внутренний столбец, куда кладётся содержимое блока.
     */
    private fun guardCard(caption: String, icon: VaultIcon.Kind, tint: Int): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(SURFACE_SUNKEN)
                // Рамка приглушена до трети: полный тон спорил бы с
                // содержимым и превращал экран в набор мишеней.
                setStroke(dp(1), (tint and 0xFFFFFF) or 0x55000000.toInt())
            }
        }
        box.addView(TextView(this).apply {
            text = caption
            textSize = 15f
            setTextColor(tint)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                VaultIcon(icon, tint, dp(17)), null, null, null)
            compoundDrawablePadding = dp(9)
            setPadding(0, 0, 0, dp(10))
        })
        root.addView(box, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(14) })
        return box
    }

    // ------------------------------------------------------------------ защита

    /**
     * Защита тайника.
     *
     * Отдельный экран, а не кнопка в углу списка: опасное действие не
     * должно жить рядом с повседневными, иначе однажды палец промахнётся.
     */
    private fun showGuard() {
        dropKeyboard()
        mount(scrollable = true)
        screen = Screen.GUARD
        root.removeAllViews()
        root.setPadding(dp(24), dp(24), dp(24), dp(32))
        editor = null
        title("Защита", "Что можно сделать с самим тайником")

        // --- блок первый: справка. Ничего не делает, только сообщает.
        val about = guardCard("Файл тайников", VaultIcon.Kind.LIST,
            VaultIcon.tintFor(VaultIcon.Kind.CLOSE))
        val count = store.read()?.vaultCount ?: 0
        about.addView(TextView(this).apply {
            text = "Занято " + count + " из " + VaultFile.MAX_VAULTS
            textSize = 20f
            setTextColor(0xFFCFCFDA.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        dim("Это число лежит открыто: тот, кто добрался до папки " +
            "приложения, увидит, сколько тайников создано. Что внутри — " +
            "не увидит никто.", about)

        // --- блок второй: настройка.
        val grace = guardCard("Пока приложение свёрнуто", VaultIcon.Kind.SHIELD,
            VaultIcon.tintFor(VaultIcon.Kind.SHIELD))
        graceSection(grace)

        // --- блок третий: секрет восстановления.
        val rec = guardCard("Секрет восстановления", VaultIcon.Kind.TRAIL,
            VaultIcon.tintFor(VaultIcon.Kind.TRAIL))
        recoverySection(rec)

        // --- блок четвёртый: снимки экрана.
        val shots = guardCard("Снимки экрана", VaultIcon.Kind.EYE,
            VaultIcon.tintFor(VaultIcon.Kind.EYE))
        shotsSection(shots)

        // --- блок пятый: клавиатура.
        val kbd = guardCard("Клавиатура пароля", VaultIcon.Kind.HEADING,
            VaultIcon.tintFor(VaultIcon.Kind.SEARCH))
        keyboardSection(kbd)

        // --- блок шестой: необратимое. Предупреждение и кнопка ВНУТРИ
        // одной рамки: раньше их связывал только отступ, и связь читалась
        // не с первого взгляда.
        val danger = guardCard("Необратимое", VaultIcon.Kind.TRASH,
            VaultIcon.tintFor(VaultIcon.Kind.TRASH))
        dim("Стираются заметки, страницы, история и картинки этого " +
            "тайника, и только потом оба его ключа — пароль и секрет " +
            "восстановления. Другие тайники не затрагиваются.\n\n" +
            "Восстановить нечем: копии ключа не существует нигде.", danger)
        dangerButton("Удалить этот тайник", danger).setOnClickListener {
            if (!busy) askDestroyVault()
        }

        secondaryButton("←  К списку заметок").setOnClickListener { goBack() }
    }

    private fun recoverySection(into: LinearLayout) {
        dim("Секрет восстановления можно разделить на части и раздать их " +
            "разным людям или разложить по разным местам. Любые K частей " +
            "из N открывают тайник, любые K−1 не дают ничего — это " +
            "свойство математики, а не обещание.", into)

        into.addView(optionButton("Разделить на части",
            "Понадобится пароль. Старая фраза восстановления перестанет " +
                "работать: вместо неё появится новый секрет, и он будет " +
                "существовать только в виде частей.",
            VaultIcon.Kind.TRAIL, VaultIcon.tintFor(VaultIcon.Kind.TRAIL)) {
            if (!busy) askSplitShape()
        }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
    }

    /**
     * Сколько частей и сколько нужно для сборки.
     *
     * Готовые сочетания, а не два счётчика. Счётчики позволяют выбрать
     * «1 из 5» - то есть отдать доступ каждому держателю по отдельности,
     * не заметив этого. Здесь такого варианта просто нет.
     */
    private fun askSplitShape() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Сколько частей?")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Отмена", null)
            .create()

        for ((k, n, why) in listOf(
            Triple(2, 3, "Одну потерять не страшно. Двое сговорившихся войдут."),
            Triple(3, 5, "Запас на две утраты. Обычный выбор."),
            Triple(4, 7, "Для большого круга держателей. Собирать долго.")
        )) {
            box.addView(optionButton("$k из $n",
                why + " Частей будет $n, для входа нужно любые $k.",
                VaultIcon.Kind.TRAIL, VaultIcon.tintFor(VaultIcon.Kind.TRAIL)) {
                dialog.dismiss()
                askSplitPassword(k, n)
            })
        }
        dialog.show()
    }

    /**
     * Пароль как разрешение. Ключ уже в памяти, технически он не нужен -
     * но замена секрета восстановления необратима, и она не должна быть
     * возможна для того, кто взял телефон в льготные минуты.
     */
    private fun askSplitPassword(k: Int, n: Int) {
        val session = VaultSession.key() ?: return
        val field = EditText(this).apply {
            hint = "Пароль этого тайника"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(field)
            addView(TextView(this@VaultActivity).apply {
                text = "После этого прежняя фраза восстановления " +
                    "перестанет открывать тайник. Пароль продолжит работать."
                textSize = 13f
                setTextColor(0xFFE0C08A.toInt())
                setPadding(0, dp(10), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Разделить: $k из $n")
            .setView(holder)
            .setCancelable(false)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Разделить") { _, _ -> doSplit(field.chars(), session, k, n) }
            .show()
    }

    private fun doSplit(secret: CharArray, session: ByteArray, k: Int, n: Int) {
        if (busy || secret.isEmpty()) { secret.fill('\u0000'); return }
        busy = true
        lifecycleScope.launch {
            val parts = withContext(Dispatchers.Default) {
                try {
                    val box = store.read() ?: return@withContext null
                    val opened = VaultFile.openAt(box, secret) ?: return@withContext null
                    val mine = opened.key.contentEquals(session)
                    opened.key.fill(0)
                    if (!mine) return@withContext null

                    val rng = java.security.SecureRandom()
                    val fresh = ByteArray(16).also { rng.nextBytes(it) }
                    val phrase = VaultShamir.secretToText(fresh).toCharArray()

                    // Сначала новый секрет становится рабочим, и только
                    // потом раздаются части. Обратный порядок раздал бы
                    // части от секрета, который не записался.
                    store.write(VaultFile.replaceRecovery(box, opened.slot, phrase, session))

                    val setId = rng.nextInt(65536)
                    val texts = VaultShamir.split(fresh, n, k) { len ->
                        ByteArray(len).also { rng.nextBytes(it) }
                    }.mapIndexed { i, d -> VaultShamir.encodeShare(i + 1, k, setId, d) }
                    fresh.fill(0)
                    phrase.fill('\u0000')
                    texts
                } catch (e: Exception) {
                    null
                } finally {
                    secret.fill('\u0000')
                }
            }
            busy = false
            if (parts == null) toast("Не подходит") else showShares(parts, k)
        }
    }

    /**
     * Показ частей.
     *
     * По одной на карточку, каждую отдельно копировать. Список целиком в
     * буфере - это весь секрет в одном месте, то есть ровно то, от чего
     * разделение и защищает.
     */
    private fun showShares(parts: List<String>, k: Int) {
        dropKeyboard()
        mount(scrollable = true)
        screen = Screen.SHARES
        root.removeAllViews()
        root.setPadding(dp(20), dp(24), dp(20), dp(32))
        editor = null
        title("Части секрета", "Любые " + k + " из " + parts.size + " откроют тайник")

        dim("Показаны ОДИН раз. Закроешь экран — восстановить их будет " +
            "нечем: в приложении они не хранятся. Разложи по разным " +
            "местам; две части рядом — это не две части, а одна.")

        for ((i, text) in parts.withIndex()) {
            val card = guardCard("Часть " + (i + 1) + " из " + parts.size,
                VaultIcon.Kind.TRAIL, VaultIcon.tintFor(VaultIcon.Kind.TRAIL))
            card.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(0xFFEEEEEE.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(8))
            })
            secondaryButton("Скопировать часть " + (i + 1), card).setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("", text))
                toast("Часть " + (i + 1) + " скопирована")
            }
        }

        button("Записал, закрыть").setOnClickListener { showNotes() }
    }

    /** Собранные части: живут только на этом экране. */
    private val gathered = LinkedHashMap<Int, VaultShamir.Share>()

    private fun showShareEntry() {
        dropKeyboard()
        mount(scrollable = true)
        screen = Screen.SHARE_IN
        root.removeAllViews()
        root.setPadding(dp(20), dp(24), dp(20), dp(32))
        gathered.clear()
        title("Вход по частям")

        val field = EditText(this).apply {
            hint = "Впиши одну часть"
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF6A6A75.toInt())
            setBackgroundColor(SURFACE_RAISED)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(field, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) })

        val status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, dp(4), 0, dp(10))
            text = "Части можно вводить в любом порядке."
        }
        root.addView(status)

        button("Добавить часть").setOnClickListener {
            val raw = field.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener
            val sh = VaultShamir.decodeShare(raw)
            if (sh == null) {
                // Опечатка ловится ЗДЕСЬ, а не после сборки: иначе человек
                // видел бы «не подходит» и не знал, какая из частей врёт.
                status.text = "Эта часть не читается. Проверь знаки: " +
                    "в частях не бывает нуля, единицы, I, L, O и U."
                return@setOnClickListener
            }
            val other = gathered.values.firstOrNull()
            if (other != null && other.setId != sh.setId) {
                status.text = "Эта часть от другого разделения. Части " +
                    "разных наборов вместе не работают."
                return@setOnClickListener
            }
            if (gathered.containsKey(sh.idx)) {
                status.text = "Эта часть уже введена. Нужны РАЗНЫЕ части."
                return@setOnClickListener
            }
            gathered[sh.idx] = sh
            field.setText("")
            val need = sh.k - gathered.size
            if (need > 0) {
                status.text = "Принято частей: " + gathered.size +
                    ". Нужно ещё " + need + "."
            } else {
                status.text = "Собираю…"
                tryShares()
            }
        }

        secondaryButton("←  Назад").setOnClickListener { goBack() }
    }

    private fun tryShares() {
        val secret = VaultShamir.combine(gathered.values.map { it.idx to it.data })
        if (secret == null) {
            toast("Части не сходятся")
            return
        }
        val phrase = VaultShamir.secretToText(secret).toCharArray()
        secret.fill(0)
        gathered.clear()
        // Дальше обычный путь входа: собранный секрет ничем не отличается
        // от набранного руками.
        unlock(phrase) {
            toast("Части не открыли тайник")
            showShareEntry()
        }
    }

    /**
     * Запрет снимков экрана.
     *
     * Флаг переключается на лету: пересоздавать экран ради одной настройки
     * значило бы моргнуть и потерять место в списке.
     *
     * На версиях ниже 33 запрет стоит всегда и настройка не показывается:
     * там превью в списке задач и снимок экрана - один и тот же флаг, а
     * превью мы не отдаём ни при каких условиях.
     */
    private fun applyShots(mode: Int) {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (mode == VaultFile.SHOTS_BLOCK) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun shotsSection(into: LinearLayout) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            dim("Снимки экрана запрещены системой: на этой версии Android " +
                "снимок и превью в списке задач - один и тот же запрет, а " +
                "превью тайника не отдаётся никогда.", into)
            return
        }
        val mode = store.read()?.shots ?: VaultFile.SHOTS_ALLOW

        dim("Превью тайника в списке задач не показывается никогда — это " +
            "утечка без всякого твоего действия. Здесь только про снимок, " +
            "который делаешь ты сам.", into)

        into.addView(optionButton(
            if (mode == VaultFile.SHOTS_ALLOW) "Снимки разрешены  ✓" else "Снимки разрешены",
            "Можно сохранить страницу картинкой. Снимок ложится в галерею " +
                "незашифрованным — дальше он живёт своей жизнью.",
            VaultIcon.Kind.IMAGE,
            if (mode == VaultFile.SHOTS_ALLOW) getColor(R.color.accent_violet_bright)
                else VaultIcon.tintFor(VaultIcon.Kind.IMAGE)) {
            if (!busy) setShots(VaultFile.SHOTS_ALLOW)
        }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })

        into.addView(optionButton(
            if (mode == VaultFile.SHOTS_BLOCK) "Снимки запрещены  ✓" else "Снимки запрещены",
            "Система откажет и приложению, и себе самой. Заодно перестанет " +
                "работать запись экрана поверх тайника.",
            VaultIcon.Kind.SHIELD,
            if (mode == VaultFile.SHOTS_BLOCK) getColor(R.color.accent_violet_bright)
                else VaultIcon.tintFor(VaultIcon.Kind.SHIELD)) {
            if (!busy) setShots(VaultFile.SHOTS_BLOCK)
        }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
    }

    private fun setShots(mode: Int) {
        busy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                try {
                    val box = store.read() ?: return@withContext false
                    store.write(VaultFile.withShots(box, mode))
                    true
                } catch (e: Exception) {
                    false
                }
            }
            busy = false
            if (ok) {
                applyShots(mode)
                showGuard()
            } else toast("Не удалось сохранить")
        }
    }

    /**
     * Выбор клавиатуры.
     *
     * ЧЕСТНАЯ ГРАНИЦА
     * ---------------
     * Своя клавиатура защищает от взгляда через плечо и от следов пальца
     * на стекле: движение перестаёт быть подписью пароля. От программного
     * перехвата она не защищает никак, и это сказано на экране - иначе
     * получилась бы точность, которой нет.
     *
     * ПОЧЕМУ ЗДЕСЬ НЕТ ПУНКТА ПРО ЗАМЕТКИ
     * -----------------------------------
     * Своя клавиатура в заметках отнимает выделение, буфер, подсказки и
     * длинные нажатия. Страницу на двадцать тысяч символов ею не написать.
     * Пункт появится, когда будет сделан редактор, который это переживает.
     * Мёртвых пунктов в списке нет.
     */
    private fun keyboardSection(into: LinearLayout) {
        val box = store.read()
        val scope = box?.kbScope ?: VaultFile.KB_OFF
        val current = box?.kbLayout ?: VaultKeys.LAYOUT_NORMAL

        dim("Своя клавиатура при вводе пароля. Скрывает пароль от взгляда " +
            "через плечо и от следов пальца на стекле. От программ, " +
            "перехватывающих ввод, не защищает — это делается не здесь.", into)

        into.addView(optionButton(
            if (scope == VaultFile.KB_OFF) "Системная клавиатура  ✓" else "Системная клавиатура",
            "Обычная клавиатура телефона. Так работало до сих пор.",
            VaultIcon.Kind.CLOSE,
            if (scope == VaultFile.KB_OFF) getColor(R.color.accent_violet_bright)
                else 0xFF9A94A8.toInt()) {
            if (!busy) setKeyboard(current, VaultFile.KB_OFF)
        }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })

        val opts = listOf(
            Triple(VaultKeys.LAYOUT_NORMAL, "Обычная",
                "Привычный порядок букв. Системная клавиатура не " +
                    "участвует во вводе, но подсмотреть через плечо так же легко."),
            Triple(VaultKeys.LAYOUT_SHUFFLED, "Перемешанная",
                "Буквы в случайном порядке, новом при каждом входе. " +
                    "Движение пальца перестаёт быть подписью пароля."),
            Triple(VaultKeys.LAYOUT_GROUPED, "Сгруппированная",
                "По алфавиту, а не по привычной раскладке. Искать глазами " +
                    "легче, чем в хаосе, а мышечная память всё равно не работает."),
            Triple(VaultKeys.LAYOUT_CHAOS, "Полный хаос",
                "Перемешаны буквы, цифры и знаки, и сами клавиши разного " +
                    "размера. Медленно и неудобно — в этом и смысл.")
        )
        for ((mode, name, explain) in opts) {
            val on = scope == VaultFile.KB_PASSWORD && mode == current
            val tint = if (on) getColor(R.color.accent_violet_bright)
                else VaultIcon.tintFor(VaultIcon.Kind.SEARCH)
            into.addView(optionButton(
                if (on) name + "  ✓" else name, explain,
                VaultIcon.Kind.HEADING, tint) {
                if (!busy && !on) setKeyboard(mode, VaultFile.KB_PASSWORD)
            }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
        }

        dim("Гласные, согласные, знаки и цифры окрашены по-разному — " +
            "в перемешанной раскладке глаз находит группу быстрее, чем " +
            "букву. Цвет не выдаёт ничего: буквы и так написаны на " +
            "клавишах.", into)

        dim("Возврат к системной клавиатуре есть всегда — отдельной клавишей " +
            "на самой клавиатуре. Отключить её нельзя: если какой-то знак " +
            "твоего пароля вдруг не наберётся, тайник открыть будет нечем.", into)
    }

    private fun setKeyboard(layout: Int, scope: Int) {
        busy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                try {
                    val box = store.read() ?: return@withContext false
                    store.write(VaultFile.withKeyboard(box, layout, scope))
                    true
                } catch (e: Exception) {
                    false
                }
            }
            busy = false
            if (ok) showGuard() else toast("Не удалось сохранить")
        }
    }

    /**
     * Выбор льготы.
     *
     * Под каждым вариантом стоит его ЦЕНА, а не только удобство: льгота в
     * час означает, что расшифрованный ключ живёт в памяти час. Прятать
     * это в справку нельзя - решение принимается здесь, и объяснение
     * должно быть здесь же.
     */
    private fun graceSection(into: LinearLayout) {
        val current = VaultSession.graceMode
        dim("Сколько тайник остаётся открытым, если свернуть приложение.", into)
        dim("Длинная льгота действует, пока система держит тайник в памяти. " +
            "Тайник живёт отдельно от шагомера, и Android вправе выгрузить " +
            "его раньше срока — тогда пароль спросят снова. Это цена за то, " +
            "что поломка в заметках не роняет счёт шагов.", into)

        val opts = listOf(
            Triple(VaultFile.GRACE_90S, "Полторы минуты",
                "Ответить в мессенджере и вернуться. Ключ живёт в памяти " +
                    "полторы минуты — против того, кто выхватил телефон, " +
                    "этого достаточно."),
            Triple(VaultFile.GRACE_15M, "Пятнадцать минут",
                "Для работы урывками. Ключ живёт в памяти четверть часа."),
            Triple(VaultFile.GRACE_1H, "Час",
                "Удобно и заметно слабее: расшифрованный ключ лежит в " +
                    "памяти целый час. Получивший телефон на это время " +
                    "войдёт без пароля."),
            Triple(VaultFile.GRACE_SCREEN, "Пока горит экран",
                "Времени нет вовсе: погас экран — заперто сразу. Самый " +
                    "строгий вариант и при этом не мешающий, пока телефон " +
                    "в руках.")
        )

        for ((mode, name, explain) in opts) {
            val chosenNow = mode == current
            val tint = if (chosenNow) getColor(R.color.accent_violet_bright)
                else VaultIcon.tintFor(VaultIcon.Kind.SHIELD)
            val label = if (chosenNow) name + "  ✓" else name
            into.addView(
                optionButton(label, explain, VaultIcon.Kind.SHIELD, tint) {
                    if (!busy && !chosenNow) setGrace(mode)
                },
                LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
            )
        }
    }

    /**
     * Смена льготы: файл ключей переписывается целиком и атомарно, слоты
     * при этом не трогаются - пароли остаются прежними.
     */
    private fun setGrace(mode: Int) {
        busy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                try {
                    val box = store.read() ?: return@withContext false
                    store.write(VaultFile.withGrace(box, mode))
                    true
                } catch (e: Exception) {
                    false
                }
            }
            busy = false
            if (ok) {
                VaultSession.setMode(mode)
                showGuard()
            } else {
                toast("Не удалось сохранить")
            }
        }
    }

    /**
     * Пароль спрашивается не потому, что он нужен технически — ключ уже в
     * памяти. Он доказывает, что за экраном владелец, а не тот, кто взял
     * телефон в льготные полторы минуты.
     *
     * Ввод пароля невозможно сделать случайно, поэтому ритуала из трёх
     * предупреждений здесь нет: галочку «я понял» прокликивают не читая,
     * а пароль набирают осознанно.
     */
    private fun askDestroyVault() {
        val session = VaultSession.key() ?: return
        val field = EditText(this).apply {
            hint = "Пароль или секрет этого тайника"
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить тайник")
            .setView(holder)
            .setCancelable(false)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> destroyVault(field.chars(), session) }
            .show()
    }

    /**
     * ПОРЯДОК ОПЕРАЦИЙ — самое важное здесь.
     *
     * Данные, потом картинки, ключи ПОСЛЕДНИМИ. Обрыв на данных оставляет
     * ключ живым: человек входит и повторяет удаление. Обрыв после
     * стирания ключа оставил бы нечитаемый мусор навсегда, и удалить его
     * было бы уже нечем — сбой стал бы невосстановимым.
     *
     * Проверка секрета строгая: он обязан открыть слот, ключ которого
     * СОВПАДАЕТ с ключом текущей сессии. Просто «открыл какой-то слот» не
     * годится — иначе паролем от второго тайника сносился бы первый.
     */
    private fun destroyVault(secret: CharArray, session: ByteArray) {
        if (busy || secret.isEmpty()) {
            secret.fill('\u0000')
            return
        }
        busy = true
        toast("Удаляю…")
        lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) {
                try {
                    val box = store.read() ?: return@withContext null
                    val opened = VaultFile.openAt(box, secret) ?: return@withContext null
                    val mine = opened.key.contentEquals(session)
                    opened.key.fill(0)
                    if (!mine) return@withContext null

                    val done = VaultPurge(this@VaultActivity, session).purge()
                    val rest = VaultFile.removeVault(box, opened.slot)
                    if (rest == null) store.destroy() else store.write(rest)
                    done
                } catch (e: Exception) {
                    null
                } finally {
                    secret.fill('\u0000')
                }
            }
            busy = false
            if (report == null) {
                // Одно сообщение на все случаи, как и при входе: разные
                // ответы выдали бы состояние файла тайников.
                toast("Не подходит")
            } else {
                toast("Удалено: заметок " + report.notes +
                    ", страниц " + report.pages +
                    ", картинок " + report.images)
                closeVault()
            }
        }
    }

    private fun showArchive() {
        mount(scrollable = true)
        screen = Screen.ARCHIVE
        root.removeAllViews()
        root.setPadding(dp(24), dp(24), dp(24), dp(32))
        editor = null
        title("Архив тайника", "Заметки, классы и страницы одним файлом")

        dim("Файл выгружается ТОЛЬКО зашифрованным. Без ключа он бесполезен — " +
            "это свойство файла, а не обещание.")

        val warn = TextView(this).apply {
            text = "Архив тайника и бэкап StepCore — разные файлы.\n\n" +
                "Бэкап шагомера НЕ содержит заметок: иначе пароль тайника " +
                "обходился бы через него. Переносить их надо по отдельности, " +
                "и восстановить одно из другого невозможно."
            textSize = 14f
            setTextColor(0xFFE0C08A.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(SURFACE_SUNKEN)
                setStroke(dp(1), 0x55E0C08A)
            }
        }
        root.addView(warn, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(16) })

        button("Выгрузить все заметки").setOnClickListener { askExport(emptyList()) }
        secondaryButton("Загрузить из файла").setOnClickListener {
            pickFile.launch(arrayOf("application/octet-stream", "*/*"))
        }
        gap()
        dim("Выбрать отдельные заметки: долгое нажатие на заметку в списке.")
        secondaryButton("←  К списку заметок").setOnClickListener { goBack() }
    }

    /**
     * Спросить, чем закрыть архив.
     *
     * Два способа РАЗЛИЧАЮТСЯ поведением, а не галочкой "я понял":
     * ключом тайника - ничего вводить не надо, но файл откроется только
     * здесь; своим паролем - надо ввести дважды и отличный от пароля
     * тайника, зато откроется где угодно. Разное поведение запоминается,
     * ритуал подтверждения - нет.
     */
    private fun askExport(ids: List<Long>) {
        val r = repo ?: return
        val what = if (ids.isEmpty()) "Все заметки" else "Выбрано заметок: " + ids.size

        // Два способа - ДВЕ КНОПКИ со своими рамками и цветом, а не строки
        // списка. Строки читаются как текст, и человек ищет глазами, где
        // же выбор; кнопка видна как кнопка.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(TextView(this).apply {
            text = what
            textSize = 13f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, 0, 0, dp(12))
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Чем закрыть архив?")
            .setView(box)
            .setNegativeButton("Отмена", null)
            // Касание мимо окна ничего не запускает.
            .setCancelable(false)
            .create()

        box.addView(optionButton(
            "Ключом этого тайника",
            "Ничего вводить не надо. Откроется ТОЛЬКО в этом тайнике: " +
                "переустановишь приложение — файл станет бесполезен.",
            VaultIcon.Kind.CLOSE, VaultIcon.tintFor(VaultIcon.Kind.ROOTS)) {
            dialog.dismiss()
            doExport(r, ids, null)
        })
        box.addView(optionButton(
            "Своим паролем",
            "Ввести дважды. Откроется в любом тайнике и на другом " +
                "телефоне. Забудешь пароль файла — архив потерян.",
            VaultIcon.Kind.JUMP, VaultIcon.tintFor(VaultIcon.Kind.IMAGE)) {
            dialog.dismiss()
            askOwnPassword { pw -> doExport(r, ids, pw) }
        })
        dialog.show()
    }

    /**
     * Крупная кнопка выбора: значок, название и объяснение под ним.
     *
     * Объяснение прямо в кнопке, а не отдельной подсказкой: решение
     * принимается в момент нажатия, и справка должна быть там же.
     */
    private fun optionButton(title: String, explain: String,
                             icon: VaultIcon.Kind, tint: Int,
                             action: () -> Unit): View {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(SURFACE_SUNKEN)
            setStroke(dp(2), (tint and 0xFFFFFF) or 0x88000000.toInt())
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x2AFFFFFF), bg, null
            )
            setOnClickListener { action() }
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(tint)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                VaultIcon(icon, tint, dp(18)), null, null, null)
            compoundDrawablePadding = dp(10)
        })
        col.addView(TextView(this).apply {
            text = explain
            textSize = 13f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, dp(6), 0, 0)
        })
        col.layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
        return col
    }

    /** Свой пароль файла: дважды и обязательно не как у тайника. */
    private fun askOwnPassword(done: (CharArray) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val p1 = EditText(this).apply {
            hint = "Пароль файла"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val p2 = EditText(this).apply {
            hint = "Ещё раз"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(p1)
        box.addView(p2)
        box.addView(TextView(this).apply {
            text = "Этот файл НЕ откроется паролем тайника. Забудешь пароль " +
                "файла — архив потерян."
            textSize = 13f
            setTextColor(0xFFE0C08A.toInt())
            setPadding(0, dp(10), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Пароль для файла")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Дальше") { _, _ ->
                val a1 = CharArray(p1.text.length).also { p1.text.getChars(0, p1.text.length, it, 0) }
                val a2 = CharArray(p2.text.length).also { p2.text.getChars(0, p2.text.length, it, 0) }
                val problem = VaultCrypto.checkSecret(a1)
                    ?: if (!a1.contentEquals(a2)) "Пароли не совпадают" else null
                if (problem != null) toast(problem) else done(a1)
            }
            .show()
    }

    private fun doExport(r: VaultRepo, ids: List<Long>, ownPassword: CharArray?) {
        busy = true
        toast("Собираю архив…")
        lifecycleScope.launch {
            val data = withContext(Dispatchers.Default) {
                try {
                    val archive = r.collect(ids)
                    val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_LEN)
                    val n = VaultCrypto.calibrateN(1500L)
                    if (ownPassword == null) {
                        val key = VaultSession.key() ?: return@withContext null
                        VaultArchive.seal(archive, VaultArchive.Lock.BY_VAULT, key, n, salt)
                    } else {
                        val key = VaultArchive.keyFromPassword(ownPassword, salt, n)
                        try {
                            VaultArchive.seal(archive, VaultArchive.Lock.BY_PASSWORD, key, n, salt)
                        } finally {
                            key.fill(0); ownPassword.fill('\u0000')
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            busy = false
            if (data == null) { toast("Не удалось собрать архив"); return@launch }
            pendingExport = data
            createFile.launch("vault-" + System.currentTimeMillis() + ".scva")
        }
    }

    private fun writeExport(uri: android.net.Uri, data: ByteArray) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
            toast("Архив сохранён")
        } catch (e: Exception) {
            toast("Не удалось записать файл")
        }
    }

    private fun readImport(uri: android.net.Uri) {
        val r = repo ?: return
        busy = true
        lifecycleScope.launch {
            val raw = try {
                withContext(Dispatchers.Default) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            } catch (e: Exception) { null }
            val head = raw?.let { VaultArchive.head(it) }
            busy = false
            if (head == null) { toast("Это не архив тайника"); return@launch }
            if (head.lock == VaultArchive.Lock.BY_VAULT) {
                val key = VaultSession.key()
                if (key == null) { toast("Тайник закрыт"); return@launch }
                absorbArchive(r, head, key)
            } else {
                askText("Пароль файла", "") { pw ->
                    lifecycleScope.launch {
                        val key = withContext(Dispatchers.Default) {
                            VaultArchive.keyFromPassword(pw.toCharArray(), head.salt, head.n)
                        }
                        absorbArchive(r, head, key)
                    }
                }
            }
        }
    }

    /**
     * Открыть архив и показать разбор.
     *
     * Ничего не вливается до того, как человек увидел список: импорт
     * добавляет, а не заменяет, и лишний экземпляр всего архива потом
     * вычищается только руками.
     */
    private fun absorbArchive(r: VaultRepo, head: VaultArchive.Head, key: ByteArray) {
        busy = true
        toast("Разбираю архив…")
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.Default) {
                val archive = VaultArchive.open(head, key) ?: return@withContext null
                archive to r.compareArchive(archive)
            }
            busy = false
            if (pair == null) toast("Ключ не подходит к этому файлу")
            else showImportChoice(pair.first, pair.second)
        }
    }

    /**
     * Разбор архива перед вливанием.
     *
     * УМОЛЧАНИЯ ВЫБРАНЫ ЗА ЧЕЛОВЕКА, НО НЕ ВМЕСТО НЕГО
     * ------------------------------------------------
     * Совпавшие слово в слово выключены - именно они и давали второй
     * экземпляр всего. Новые и отличающиеся включены: потерять текст
     * страшнее, чем получить лишнюю заметку.
     *
     * Ни один пункт не заблокирован: если человек ХОЧЕТ второй экземпляр
     * одинаковой заметки, это его дело.
     */
    private fun showImportChoice(a: VaultArchive.Archive, marks: List<Int>) {
        dropKeyboard()
        mount(scrollable = true)
        screen = Screen.IMPORT
        root.removeAllViews()
        root.setPadding(dp(20), dp(24), dp(20), dp(32))
        editor = null

        val take = HashSet<Int>()
        for (i in a.notes.indices) if (marks[i] != VaultRepo.Match.SAME) take.add(i)

        val fresh = marks.count { it == VaultRepo.Match.FRESH }
        val same = marks.count { it == VaultRepo.Match.SAME }
        val similar = marks.count { it == VaultRepo.Match.TITLE_ONLY }

        title("Что вливаем", "В архиве заметок: " + a.notes.size)

        val summary = StringBuilder()
        summary.append("Новых: ").append(fresh)
        if (same > 0) summary.append("\nУже есть слово в слово: ").append(same)
        if (similar > 0) summary.append("\nСовпало название, текст другой: ").append(similar)
        dim(summary.toString())

        if (same > 0) {
            dim("Совпавшие выключены заранее — похоже, этот архив уже " +
                "вливали. Импорт не заменяет, а добавляет, поэтому они " +
                "легли бы вторым экземпляром.")
        }

        val counter = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFFB9A6E8.toInt())
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(counter)

        lateinit var refresh: () -> Unit
        val rows = HashMap<Int, TextView>()

        refresh = {
            counter.text = "Выбрано: " + take.size + " из " + a.notes.size
            for ((i, v) in rows) paintImportRow(v, a.notes[i], marks[i], i in take)
        }

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((label, action) in listOf<Pair<String, () -> Unit>>(
            "Только новые" to {
                take.clear()
                for (i in marks.indices) if (marks[i] == VaultRepo.Match.FRESH) take.add(i)
            },
            "Все" to { take.clear(); take.addAll(a.notes.indices) },
            "Ничего" to { take.clear() }
        )) {
            quick.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF9A94A8.toInt())
                minHeight = dp(40)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(SURFACE_RAISED)
                    setStroke(dp(1), LINE_EDGE)
                }
                isClickable = true
                setOnClickListener { action(); refresh() }
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).also {
                    it.marginStart = dp(3); it.marginEnd = dp(3)
                }
            })
        }
        root.addView(quick, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) })

        for ((i, n) in a.notes.withIndex()) {
            val row = TextView(this).apply {
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener {
                    if (i in take) take.remove(i) else take.add(i)
                    refresh()
                }
            }
            rows[i] = row
            root.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
        }
        refresh()

        button("Влить выбранные").setOnClickListener {
            if (busy) return@setOnClickListener
            if (take.isEmpty()) { toast("Ничего не выбрано"); return@setOnClickListener }
            doAbsorb(a, take.toSet())
        }
        secondaryButton("Отмена").setOnClickListener { showNotes() }
    }

    private fun paintImportRow(v: TextView, n: VaultArchive.Note, mark: Int, on: Boolean) {
        val tint = when (mark) {
            VaultRepo.Match.SAME -> 0xFF9A94A8.toInt()
            VaultRepo.Match.TITLE_ONLY -> 0xFFE0C08A.toInt()
            else -> 0xFF9FD9A8.toInt()
        }
        val what = when (mark) {
            VaultRepo.Match.SAME -> "уже есть слово в слово"
            VaultRepo.Match.TITLE_ONLY -> "название совпало, текст другой"
            else -> "новая"
        }
        v.text = (if (on) "◉  " else "○  ") +
            (if (n.title.isBlank()) "Без названия" else n.title) +
            "\n" + n.pages.size + " стр. · " + what
        v.setTextColor(if (on) 0xFFEEEEEE.toInt() else 0xFF83808C.toInt())
        v.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (on) SURFACE_RAISED else SURFACE_SUNKEN)
            setStroke(dp(if (on) 2 else 1), if (on) tint else LINE_EDGE)
        }
    }

    private fun doAbsorb(a: VaultArchive.Archive, take: Set<Int>) {
        val r = repo ?: return
        busy = true
        lifecycleScope.launch {
            val added = withContext(Dispatchers.Default) { r.absorbChosen(a, take) }
            busy = false
            toast("Добавлено заметок: " + added)
            showNotes()
        }
    }

    /** Удалить выбранные. Подтверждение показывает ЧИСЛО, а не список. */
    private fun askDeleteMany(ids: List<Long>) {
        val r = repo ?: return
        val im = images ?: return
        AlertDialog.Builder(this)
            .setTitle("Удалить заметок: " + ids.size + "?")
            .setMessage("Это навсегда. Восстановить будет нечем: копий нет, " +
                "корзины нет, картинки этих заметок тоже стираются.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    for (id in ids) r.deleteNote(id, im)
                    selecting = false
                    chosen.clear()
                    toast("Удалено: " + ids.size)
                    showNotes()
                }
            }
            .show()
    }

    /**
     * Нарисовать карточку заметки в её нынешнем состоянии.
     *
     * Выбор показан РАМКОЙ и заливкой, а не только значком: значок в углу
     * читается как украшение, а изменившийся фон виден сразу и целиком.
     */
    /**
     * Подпись действия зависит от того, что выбрано.
     *
     * Если среди выбранных есть хоть одна незакреплённая - закрепляем все.
     * Если все уже закреплены - открепляем. Одна кнопка вместо двух: две
     * кнопки в нижней панели заставляют выбирать, а выбор здесь очевиден
     * из самого набора.
     */
    /**
     * Стрелка перестановки. Недоступная не исчезает, а гаснет: пропадающая
     * кнопка сдвигает соседнюю под палец, и следующее нажатие попадает не
     * туда, куда целились.
     */
    private fun arrowButton(glyph: String, enabled: Boolean, action: () -> Unit): View {
        val tint = if (enabled) 0xFFB9A6E8.toInt() else 0xFF3A3646.toInt()
        return TextView(this).apply {
            text = glyph
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(tint)
            minWidth = dp(40)
            minHeight = dp(40)
            isClickable = enabled
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(SURFACE_RAISED)
                setStroke(dp(1), if (enabled) tint and 0x66FFFFFF.toInt() else LINE_EDGE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.marginStart = dp(6)
            }
            if (enabled) setOnClickListener { if (!busy) action() }
        }
    }

    private fun pinActionLabel(): String {
        val any = chosen.any { (rowTexts[it]?.pin ?: 0) == 0 }
        return (if (any) "Закрепить " else "Открепить ") + chosen.size
    }

    private fun togglePins(ids: List<Long>) {
        val r = repo ?: return
        if (ids.isEmpty()) return
        busy = true
        lifecycleScope.launch {
            val all = r.notes()
            val pinnedNow = all.filter { it.pin > 0 }.sortedBy { it.pin }.map { it.id }
            val addAny = ids.any { id -> (all.firstOrNull { it.id == id }?.pin ?: 0) == 0 }

            val ordered: List<Long>
            val unpin: List<Long>
            if (addAny) {
                // Новые уходят в КОНЕЦ блока: закрепление не должно
                // перетасовывать то, что человек уже расставил.
                ordered = pinnedNow + ids.filter { it !in pinnedNow }
                unpin = emptyList()
            } else {
                ordered = pinnedNow.filter { it !in ids }
                unpin = ids
            }
            r.renumberPins(ordered, unpin)
            busy = false
            selecting = false
            chosen.clear()
            showNotes()
        }
    }

    /**
     * Переставить закреплённую заметку на шаг.
     *
     * Стрелки, а не перетаскивание. Перетаскивание внутри прокручиваемого
     * списка - это перехват касаний, автопрокрутка у краёв и подменная
     * строка; там живут ошибки, которые ловятся только пальцем и только
     * через неделю. Закреплённых обычно единицы, и стрелками это столько
     * же нажатий, зато ломаться нечему.
     */
    private fun movePin(id: Long, delta: Int) {
        val r = repo ?: return
        busy = true
        lifecycleScope.launch {
            val pinned = r.notes().filter { it.pin > 0 }.sortedBy { it.pin }
                .map { it.id }.toMutableList()
            val i = pinned.indexOf(id)
            val j = i + delta
            if (i >= 0 && j >= 0 && j < pinned.size) {
                pinned[i] = pinned[j]
                pinned[j] = id
                r.renumberPins(pinned)
            }
            busy = false
            showNotes()
        }
    }

    private fun paintRow(v: TextView, id: Long) {
        val n = rowTexts[id] ?: return
        val picked = selecting && id in chosen
        val hue = n.tags.firstOrNull()?.let { classHues[it.trim().lowercase()] }
        val accent = getColor(R.color.accent_violet_bright)
        val edge = if (picked) accent
                   else if (hue != null) VaultHues.color(hue, 12) and 0x66FFFFFF.toInt()
                   else LINE_EDGE
        v.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (picked) 0xFF241E33.toInt() else SURFACE_RAISED)
            setStroke(dp(if (picked) 2 else 1), edge)
        }
        // Глиф, а не эмодзи: цветную картинку каждая прошивка рисует
        // по-своему, и тон тайника от неё ломается.
        val pinMark = if (n.pin > 0) "⚑ " else ""
        val mark = (if (!selecting) "" else if (picked) "◉  " else "○  ") + pinMark
        val tags = if (n.tags.isEmpty()) "" else "\n#" + n.tags.joinToString(" #")
        v.text = mark + n.title + "\n" + n.pageCount + " стр." + tags
        v.setTextColor(if (picked) 0xFFF2F0F7.toInt() else 0xFFEEEEEE.toInt())
        if (hue != null) {
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(
                GradientDrawable().apply {
                    setColor(VaultHues.color(hue, 12))
                    cornerRadius = dp(2).toFloat()
                    setSize(dp(4), dp(38))
                }, null, null, null)
            v.compoundDrawablePadding = dp(12)
        }
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

    private companion object {
        /**
         * Три глубины фона. Раньше всё было одним тоном, и глаз терялся:
         * непонятно, где кончается одно и начинается другое.
         *
         * BASE   - подложка экрана, самая тёмная.
         * RAISED - то, что лежит НА ней: карточки заметок, поля ввода.
         * SUNKEN - то, что утоплено: вкладки и чипы, они тише всего.
         *
         * Глубина задаётся здесь одним местом: цвет, вписанный руками в
         * десяти местах, через месяц расходится и перестаёт что-либо
         * означать.
         */
        const val SURFACE_BASE = 0xFF0A0A0A.toInt()
        const val SURFACE_RAISED = 0xFF17171C.toInt()
        const val SURFACE_SUNKEN = 0xFF121218.toInt()
        const val LINE_SOFT = 0xFF26232E.toInt()
        const val LINE_EDGE = 0xFF2E2A3A.toInt()

        const val LEVEL_PRIMARY = 0
        const val LEVEL_SECONDARY = 1
        const val LEVEL_DANGER = 2
    }

    private fun toast(t: String) {
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Вкладка в ряду действий.
     *
     * Обычные Button в ряду с весом расползались: у кого текст перенёсся,
     * тот стал выше соседа, и ряд превращался в лесенку. Здесь высота
     * задана жёстко, текст в одну строку и сжимается, если не влезает.
     *
     * Тон приглушённее основных кнопок сознательно. Это не действия
     * первого ряда, а инструменты под рукой: рамка тонкая, заливка почти
     * чёрная, текст спокойный. Тайник и должен быть тише остального
     * приложения - он для того, кто уже знает, что здесь.
     */
    private fun tabButton(label: String, action: () -> Unit): View =
        tabButton(label, false, action)

    private fun tabButton(label: String, icon: VaultIcon.Kind, tint: Int,
                          action: () -> Unit): View =
        tabButton(label, false, icon, tint, action)

    private fun tabButton(label: String, active: Boolean, action: () -> Unit): View =
        tabButton(label, active, null, 0, action)

    private fun tabButton(label: String, active: Boolean, icon: VaultIcon.Kind?,
                          tint: Int, action: () -> Unit): View {
        val color = when {
            active -> getColor(R.color.accent_violet_bright)
            icon != null -> tint
            else -> 0xFFA9A4BC.toInt()
        }
        val bg = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(if (active) 0xFF1E1A2A.toInt() else SURFACE_SUNKEN)
            setStroke(dp(1), if (active) getColor(R.color.accent_violet_bright) else LINE_EDGE)
        }
        val caption = TextView(this).apply {
            text = label
            textSize = 13f
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(color)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Иконка ОТДЕЛЬНОЙ вьюхой, а не составным элементом текста.
        // Составной значок прижимается к краю поля и не центрируется -
        // именно поэтому иконки выглядели съехавшими.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x2AFFFFFF), bg, null
            )
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { if (!busy) action() }
        }
        if (icon != null) {
            box.addView(ImageView(this).apply {
                setImageDrawable(VaultIcon(icon, tint, dp(20)))
            }, LinearLayout.LayoutParams(dp(20), dp(20)).also { it.bottomMargin = dp(4) })
        }
        box.addView(caption, LinearLayout.LayoutParams(-1, -2))
        box.layoutParams = LinearLayout.LayoutParams(
            0, if (icon != null) dp(64) else dp(46), 1f
        ).also { it.marginStart = dp(3); it.marginEnd = dp(3) }
        return box
    }

    // ------------------------------------------------------------------ мелочи

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun EditText.chars(): CharArray {
        val e = text
        val out = CharArray(e.length)
        e.getChars(0, e.length, out, 0)
        return out
    }

    private fun title(t: String) = title(t, null)

    /**
     * Заголовок экрана. Крупный и жирный: он единственная точка, по
     * которой глаз понимает, где находится, а раньше терялся среди
     * прочего текста того же веса.
     */
    private fun title(t: String, sub: String?) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 27f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.01f
            setTextColor(0xFFF2F0F7.toInt())
            setPadding(0, 0, 0, if (sub == null) dp(18) else dp(2))
        })
        if (sub != null) {
            root.addView(TextView(this).apply {
                text = sub
                textSize = 12f
                setTextColor(0xFF8A8A98.toInt())
                setPadding(0, 0, 0, dp(16))
            })
        }
        // Тонкая черта под заголовком: отделяет шапку от содержимого без
        // лишнего отступа, которого на телефоне и так не хватает.
        root.addView(View(this).apply { setBackgroundColor(LINE_SOFT) },
            LinearLayout.LayoutParams(-1, dp(1)).also { it.bottomMargin = dp(12) })
    }

    private fun dim(t: String) = dim(t, root)

    /**
     * Куда класть - параметром. Раньше адрес был зашит в помощник, и любой
     * блок внутри рамки всё равно уезжал в общий столбец.
     */
    private fun dim(t: String, into: LinearLayout) {
        into.addView(TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun gap() {
        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(12)))
    }

    /**
     * Поле секрета. Если включена своя клавиатура, системная гасится и
     * под полем разворачивается наша.
     *
     * Возврат к системной остаётся клавишей на самой клавиатуре и не
     * зависит от настройки: набор символов обязан покрывать всё, но если
     * однажды не покроет, человек не должен остаться запертым.
     */
    private fun secretFieldWithKeyboard(hint: String, done: () -> Unit): EditText {
        val e = secretField(hint)
        val box = store.read()
        val scope = box?.kbScope ?: VaultFile.KB_OFF
        if (scope != VaultFile.KB_PASSWORD) return e

        e.showSoftInputOnFocus = false
        val kb = VaultKeyboard(this, e, onSystem = {
            // Своя клавиатура снимается совсем: две клавиатуры на экране
            // сбивали бы с толку.
            e.showSoftInputOnFocus = true
            keyboardView?.let { v -> (v.parent as? LinearLayout)?.removeView(v) }
            keyboardView = null
            e.requestFocus()
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(e, 0)
        }, onDone = done)
        keyboardView = kb
        root.addView(kb, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        kb.show(box?.kbLayout ?: VaultKeys.LAYOUT_NORMAL)
        e.requestFocus()
        return e
    }

    private fun secretField(hint: String): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF6A6A75.toInt())
            setBackgroundColor(SURFACE_RAISED)
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

    /**
     * Три уровня кнопок вместо одного.
     *
     * Раньше всё было одного цвета, и глаз не отличал «Открыть» от
     * «Удалить». Теперь: главное действие залито, второстепенное только
     * обведено, опасное — приглушённо-красное, без фона и отделённое
     * пустотой. Форма несёт смысл, а не украшает.
     */
    private fun button(label: String): Button = styledButton(label, LEVEL_PRIMARY, root)

    private fun secondaryButton(label: String): Button = styledButton(label, LEVEL_SECONDARY, root)

    private fun dangerButton(label: String): Button = styledButton(label, LEVEL_DANGER, root)

    /** Обычная кнопка внутри блока. */
    private fun secondaryButton(label: String, into: LinearLayout): Button =
        styledButton(label, LEVEL_SECONDARY, into)

    /** Опасная кнопка внутри своей рамки, а не в общем столбце. */
    private fun dangerButton(label: String, into: LinearLayout): Button =
        styledButton(label, LEVEL_DANGER, into)

    private fun styledButton(label: String, level: Int, into: LinearLayout): Button {
        val accent = getColor(R.color.accent_violet_bright)
        val danger = getColor(R.color.accent_red_bright)
        val bg = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            when (level) {
                LEVEL_PRIMARY -> setColor(accent)
                LEVEL_SECONDARY -> {
                    setColor(0x00000000)
                    setStroke(dp(1), 0xFF3A3A46.toInt())
                }
                else -> setColor(0x00000000)
            }
        }
        // Рябь поверх своей заливки. Заменив фон, я убрал стандартный
        // отклик — кнопка нажималась, но выглядела мёртвой, и человек
        // жал ещё раз. Отдача на касание не украшение: без неё непонятно,
        // услышало ли приложение.
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(
                if (level == LEVEL_PRIMARY) 0x33000000 else 0x33FFFFFF
            ),
            bg, null
        )
        val b = Button(this).apply {
            text = label
            textSize = if (level == LEVEL_DANGER) 15f else 16f
            // Значка у опасной кнопки НЕТ: корзина рядом со словом
            // "удалить" читается как два разных средства, и человек ищет
            // разницу между ними. Красный контур и отступ уже отделяют её
            // от остальных достаточно.
            gravity = Gravity.CENTER
            background = ripple
            stateListAnimator = null
            minHeight = dp(48)   // палец, а не курсор
            setTextColor(when (level) {
                LEVEL_PRIMARY -> 0xFF14101A.toInt()
                LEVEL_SECONDARY -> 0xFFCFCFDA.toInt()
                else -> danger
            })
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        // Опасное действие отделено пустотой: промахнуться пальцем по
        // соседней кнопке не должно стоить заметки.
        lp.topMargin = if (level == LEVEL_DANGER) dp(28) else dp(8)
        into.addView(b, lp)
        return b
    }

    /** Второстепенное действие: без заливки, тише основной кнопки. */
    private fun flatButton(label: String): TextView {
        val t = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF8F8FA0.toInt())
            setPadding(dp(8), dp(14), dp(8), dp(14))
            minHeight = dp(48)
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, null
            )
        }
        root.addView(t, LinearLayout.LayoutParams(-1, -2))
        return t
    }
}
