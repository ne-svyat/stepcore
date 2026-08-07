package com.vasil.stepcore.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private enum class Screen { ENTRANCE, CREATE, NOTES, PAGE, PREVIEW, HISTORY, IMAGE, TRAILS, ROOTS, ARCHIVE }
    private var screen = Screen.ENTRANCE
    private var histNoteId = 0L
    private var histIdx = 0

    /** Что подсветить на странице после перехода из поиска. */
    private var pendingFind: String? = null

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
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }
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
     * Пересобрать способ показа: жёсткий каркас или прокрутка.
     *
     * Каркас нужен главному экрану: только там вес списка обязан
     * ограничиваться высотой окна. Внутри прокрутки такое ограничение
     * недостижимо в принципе.
     */
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

    /**
     * Главный экран тайника: список сверху, корни снизу.
     *
     * Верх - заметки со своей прокруткой, низ - живая карта классов.
     * Половина экрана раньше пустовала, а карта была отдельной кнопкой,
     * куда никто не заходит. Теперь низ управляет верхом: тап по жиле
     * отбирает класс, тап по узелку открывает заметку.
     */
    private fun showNotes(query: String = "") {
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
            hint = "Поиск по тексту, или #класс"
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
            if (!busy) runSearch(q.text.toString(), holder, status)
        })

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
            addView(holder, LinearLayout.LayoutParams(-1, -2))
        }
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

        // Панель выбора СТРОИТСЯ ВМЕСТО обычной, но экран на этом не
        // обрывается: раньше здесь стоял return, и вместе с панелью
        // пропадали список и корни - их заполнение идёт ниже по коду.
        if (selecting) {
            // В режиме выбора нижняя панель меняется целиком: показывать
            // рядом "новая заметка" и "удалить выбранные" опасно.
            val selRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(selRow, LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = dp(10)
            })
            selRow.addView(tabButton("Выгрузить " + chosen.size,
                VaultIcon.Kind.JUMP, VaultIcon.tintFor(VaultIcon.Kind.JUMP)) {
                askExport(chosen.toList())
            })
            selRow.addView(tabButton("Удалить " + chosen.size,
                VaultIcon.Kind.TRASH, VaultIcon.tintFor(VaultIcon.Kind.TRASH)) {
                askDeleteMany(chosen.toList())
            })
            selRow.addView(tabButton("Отмена", VaultIcon.Kind.CLOSE,
                VaultIcon.tintFor(VaultIcon.Kind.CLOSE)) {
                selecting = false
                chosen.clear()
                showNotes()
            })
        } else {

        // Нижняя панель только навигационная: поиск уехал наверх к полю.
        // Оттенок сообщает ТИП действия - создание, просмотр, уход.
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        bottom.addView(tabButton("Новая", VaultIcon.Kind.PLUS,
            VaultIcon.tintFor(VaultIcon.Kind.PLUS)) {
            if (!busy) askText("Название заметки", "") { name ->
                lifecycleScope.launch {
                    val id = r.createNote(if (name.isBlank()) "Без названия" else name)
                    r.touch(id, VaultHeat.W_CREATE)
                    openForRead(id, 0)
                }
            }
        })
        bottom.addView(tabButton("Корни", VaultIcon.Kind.ROOTS, VaultIcon.tintFor(VaultIcon.Kind.ROOTS)) {
            if (!busy) showRoots()
        })
        bottom.addView(tabButton("Архив", VaultIcon.Kind.JUMP,
            VaultIcon.tintFor(VaultIcon.Kind.JUMP)) {
            if (!busy) showArchive()
        })
        bottom.addView(tabButton("Закрыть", VaultIcon.Kind.CLOSE, VaultIcon.tintFor(VaultIcon.Kind.CLOSE)) {
            closeVault()
        })
        }

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
        val list = when (sortBy) {
            // «Живые» по умолчанию: то, чем занимаешься сейчас, а не то,
            // что случайно завёл последним.
            SortBy.HOT -> raw.sortedWith(
                compareByDescending<VaultRepo.NoteHead> { it.heat }
                    .thenByDescending { it.updatedMs })
            SortBy.NEW -> raw.sortedByDescending { it.id }
            SortBy.OLD -> raw.sortedBy { it.id }
            SortBy.TITLE -> raw.sortedBy { it.title.lowercase() }
        }
        holder.removeAllViews()
        status.text = if (list.isEmpty()) "Пусто. Заметки этого тайника видны только с его паролем."
                      else "Заметок: " + list.size
        for (n in list) {
            val tags = if (n.tags.isEmpty()) "" else "\n#" + n.tags.joinToString(" #")
            val mark = if (!selecting) "" else if (n.id in chosen) "◉  " else "○  "
            // Полоса слева - тон первого класса. Список перестаёт быть
            // стеной одинаковых прямоугольников: тему видно боковым
            // зрением, ещё не читая названий.
            val hue = n.tags.firstOrNull()?.let { classHues[it.trim().lowercase()] }
            val row = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(SURFACE_RAISED)
                if (hue != null) {
                    setStroke(dp(1), VaultHues.color(hue, 12) and 0x66FFFFFF.toInt())
                }
            }
            holder.addView(TextView(this).apply {
                text = mark + n.title + "\n" + n.pageCount + " стр." + tags
                textSize = 17f
                setTextColor(0xFFEEEEEE.toInt())
                background = row
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                // Долгое нажатие включает выбор - привычный жест списка.
                // Обычный тап при этом продолжает открывать заметку, а в
                // режиме выбора начинает ставить и снимать отметку.
                setOnLongClickListener {
                    selecting = true
                    chosen.add(n.id)
                    showNotes()
                    true
                }
                setOnClickListener {
                    if (selecting) {
                        if (!chosen.remove(n.id)) chosen.add(n.id)
                        if (chosen.isEmpty()) selecting = false
                        showNotes()
                    } else openForRead(n.id, 0)
                }
                if (hue != null) {
                    val bar = android.text.SpannableString(text)
                    setText(bar)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        GradientDrawable().apply {
                            setColor(VaultHues.color(hue, 12))
                            cornerRadius = dp(2).toFloat()
                            setSize(dp(4), dp(38))
                        }, null, null, null
                    )
                    compoundDrawablePadding = dp(12)
                }
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
                    setBackgroundColor(SURFACE_RAISED)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    setOnClickListener { pendingFind = text; openForRead(h.noteId, h.page) }
                }, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
            }
        }
    }

    /** Открыть заметку на чтение. Правка - по вкладке. */
    private fun openForRead(noteId: Long, idx: Int) {
        openingForRead = true
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
        }
    }

    private fun drawPage(text: String, top: List<String> = emptyList()) {
        if (preview) { drawPreview(text, top); return }
        screen = Screen.PAGE
        mount(scrollable = true)
        root.removeAllViews()
        val r = repo ?: return
        val noteId = openNoteId

        // Подпись страницы: три частых слова. Пусто у страниц, не
        // пересохранявшихся после появления подписи, — это честнее, чем
        // выдумывать её задним числом.
        title("Правка · стр. " + (openIdx + 1) + "/" + openPages,
            if (top.isEmpty()) "Тап по заголовку — перейти на страницу"
            else top.joinToString(" · ") + "   ·   тап по заголовку — перейти")
        root.getChildAt(0).also {
            it.isClickable = true
            it.setOnClickListener { askJump() }
        }

        chipsRow(openTags, noteId)

        val e = EditText(this).apply {
            setText(text)
            textSize = 16f
            gravity = Gravity.TOP
            setTextColor(0xFFEEEEEE.toInt())
            setBackgroundColor(SURFACE_SUNKEN)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setLineSpacing(dp(4).toFloat(), 1f)
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
        pendingFind?.let { q -> pendingFind = null; flashMatch(e, q) }

        linksRow(text)

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
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        nav.addView(tabButton("Назад", VaultIcon.Kind.PREV, VaultIcon.tintFor(VaultIcon.Kind.PREV)) {
            if (openIdx > 0) leavePage { openNote(noteId, openIdx - 1) }
        })
        nav.addView(tabButton("Вперёд", VaultIcon.Kind.NEXT, VaultIcon.tintFor(VaultIcon.Kind.NEXT)) {
            if (openIdx + 1 < openPages) leavePage { openNote(noteId, openIdx + 1) }
        })
        nav.addView(tabButton("Стр.", VaultIcon.Kind.PAGE_PLUS, VaultIcon.tintFor(VaultIcon.Kind.PAGE_PLUS)) {
            leavePage {
                lifecycleScope.launch {
                    val idx = r.addPage(noteId)
                    if (idx < 0) toast("Предел " + VaultRepo.MAX_PAGES + " страниц")
                    else openNote(noteId, idx)
                }
            }
        })
        // Заголовок по кругу: обычная -> # -> ## -> ### -> обычная.
        // Одна кнопка вместо памяти о числе решёток и без ухода от
        // клавиатуры. Разметка остаётся обычным текстом.
        nav.addView(tabButton("Заголовок", VaultIcon.Kind.HEADING,
            VaultIcon.tintFor(VaultIcon.Kind.HEADING)) {
            val (text2, cur) = VaultText.cycleHeading(e.text.toString(), e.selectionEnd)
            e.setText(text2)
            e.setSelection(cur.coerceIn(0, text2.length))
        })

        button("Копировать страницу").setOnClickListener {
            val sel = e.selectionEnd - e.selectionStart
            val whole = e.text.toString()
            val part = if (sel > 0) whole.substring(e.selectionStart, e.selectionEnd) else whole
            copy(part)
            toast(if (sel > 0) "Скопирован выделенный кусок" else "Скопирована страница")
        }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tools, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        tools.addView(tabButton("Фото", VaultIcon.Kind.IMAGE, VaultIcon.tintFor(VaultIcon.Kind.IMAGE)) {
            // Метка вставляется отдельной строкой: в просмотре картинка
            // станет отдельным блоком, а не разорвёт предложение.
            pickImage.launch("image/*")
        })
        tools.addView(tabButton(
            if (preview) "Правка" else "Чтение",
            if (preview) VaultIcon.Kind.PENCIL else VaultIcon.Kind.EYE,
            VaultIcon.tintFor(if (preview) VaultIcon.Kind.PENCIL else VaultIcon.Kind.EYE)) {
            preview = !preview
            leavePage { openNote(noteId, openIdx) }
        })

        tools.addView(tabButton("Тропы", VaultIcon.Kind.TRAIL, VaultIcon.tintFor(VaultIcon.Kind.TRAIL)) {
            leavePage { showTrails(noteId, openIdx) }
        })
        tools.addView(tabButton("Разделы", VaultIcon.Kind.LIST, VaultIcon.tintFor(VaultIcon.Kind.LIST)) {
            showOutline(e)
        })

        secondaryButton("←  К списку заметок").setOnClickListener { leavePage { showNotes() } }
        secondaryButton("История правок").setOnClickListener {
            leavePage { showHistory(noteId, openIdx) }
        }
        dangerButton("Удалить заметку").setOnClickListener { askDelete(noteId) }
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
    private fun drawPreview(text: String, top: List<String>) {
        screen = Screen.PREVIEW
        mount(scrollable = true)
        root.removeAllViews()
        val noteId = openNoteId
        val im = images

        title("Стр. " + (openIdx + 1) + "/" + openPages,
            if (top.isEmpty()) "Тап по заголовку — перейти на страницу"
            else top.joinToString(" · ") + "   ·   тап по заголовку — перейти")
        root.getChildAt(0).also {
            it.isClickable = true
            it.setOnClickListener { askJump() }
        }

        chipsRow(openTags, noteId)

        for (b in VaultText.blocks(text)) {
            when (b) {
                is VaultText.Block.Head -> root.addView(TextView(this).apply {
                    this.text = b.text
                    textSize = when (b.level) { 1 -> 24f; 2 -> 20f; else -> 17f }
                    setTextColor(0xFFF2F2F7.toInt())
                    setPadding(0, dp(14), 0, dp(6))
                })
                is VaultText.Block.Para -> root.addView(TextView(this).apply {
                    textSize = 16f
                    setTextColor(0xFFDDDDE5.toInt())
                    setPadding(0, dp(4), 0, dp(8))
                    // setTextIsSelectable сбрасывает movementMethod на свой
                    // и убивает нажатие по ссылке. Поэтому абзац со
                    // ссылками отдаём переходам, а без ссылок - выделению.
                    // Совместить нельзя: это одно и то же поле.
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
            preview = false
            openNote(noteId, openIdx)
        })
        secondaryButton("←  К списку заметок").setOnClickListener { showNotes() }
        dangerButton("Удалить заметку").setOnClickListener { askDelete(noteId) }
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

            Screen.ROOTS -> showNotes()

            Screen.TRAILS -> openNote(histNoteId, histIdx)

            Screen.IMAGE -> {
                preview = true
                openNote(openNoteId, openIdx)
            }

            // Чтение - главный вид заметки, из него выходим к списку.
            // Раньше отсюда возвращало в ПРАВКУ: открыл заметку, нажал
            // назад - и оказался в редакторе, которого не просил.
            Screen.PREVIEW -> showNotes()

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
    private fun flashMatch(e: EditText, query: String) {
        val pos = VaultText.find(e.text.toString(), query)
        if (pos < 0) return
        val end = minOf(e.text.length, pos + query.length)
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
    private fun showRoots() {
        mount(scrollable = true)
        val r = repo ?: return
        screen = Screen.ROOTS
        root.removeAllViews()
        editor = null
        title("Корни")

        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF9A9AA5.toInt())
            setPadding(0, 0, 0, dp(8))
            text = "Считаю классы…"
        }
        root.addView(status)

        // Полотно обязано лежать В ПРОКРУТКЕ, как и на главном экране.
        // Здесь я это упустил: вьюха сама считает нужную ширину, но в
        // обычной колонке лишнее просто обрезается - листать нечем.
        val view = VaultRootsView(this)
        val height = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(view, FrameLayout.LayoutParams(-2, height))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, height))
        secondaryButton("←  К списку заметок").setOnClickListener { goBack() }

        lifecycleScope.launch {
            val (list, together) = r.classes()
            classHues = list.associate { it.name to it.hue }
            if (list.isEmpty()) {
                status.text = "Классов пока нет. Открой заметку, нажми «Теги заметки» " +
                    "и впиши слово — оно станет классом и получит свой оттенок."
                return@launch
            }
            status.text = "Классов: " + list.size + " · заметок с классами: " +
                list.sumOf { it.count } + "\nТолще жила — больше заметок. " +
                "Дуга внизу — классы часто идут вместе. Тап по жиле — отбор."

            // Порядок жил по тону, а не по весу: тогда соседние жилы
            // похожи по цвету, и близкие темы стоят рядом физически.
            val ordered = list.sortedBy { it.hue }
            view.setData(
                ordered.map {
                    VaultRootsView.Strand(it.name, it.count, VaultHues.color(it.hue, it.count))
                },
                together.entries
                    .sortedByDescending { it.value }
                    .take(24)
                    .map { VaultRootsView.Weave(it.key.first, it.key.second, it.value) },
                // Оба обработчика ЯВНО. Висячая лямбда после появления
                // четвёртого параметра стала привязываться к note вместо
                // pick, и это не поймал бы никакой беглый взгляд.
                { name -> pendingTag = name; showNotes() },
                { id -> openForRead(id, 0) }
            )
        }
    }

    /**
     * Чипы классов заметки: тап — сразу список этого класса.
     *
     * Раньше теги были кнопкой в самом низу и одной серой строкой. Теперь
     * они наверху, каждый своим оттенком, и работают переходом. Чип «＋»
     * открывает правку тегов — отдельная кнопка внизу больше не нужна.
     */
    private fun chipsRow(tags: List<String>, noteId: Long) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })

        for (t in tags) {
            val hue = classHues[t.trim().lowercase()]
            val c = if (hue == null) 0xFF8A8A98.toInt() else VaultHues.color(hue, 12)
            row.addView(chip("#" + t, c) { pendingTag = t; showNotes() })
        }
        row.addView(chip(if (tags.isEmpty()) "＋ класс" else "＋", 0xFF8A8A98.toInt()) {
            val r = repo ?: return@chip
            askText("Классы через запятую", tags.joinToString(", ")) { v ->
                // СНАЧАЛА сохранить страницу, потом менять классы.
                //
                // Правка классов перечитывает заметку из базы, а текст в
                // редакторе к этому моменту ещё не записан - он пропадал
                // целиком при каждом добавлении класса. Уход со страницы
                // всегда идёт через leavePage, и здесь тоже.
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
        })
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
        AlertDialog.Builder(this)
            .setTitle(what + ". Чем закрыть архив?")
            .setItems(arrayOf(
                "Ключом этого тайника — откроется только в нём",
                "Своим паролем — откроется в любом тайнике"
            )) { _, which ->
                if (which == 0) doExport(r, ids, null)
                else askOwnPassword { pw -> doExport(r, ids, pw) }
            }
            .setNegativeButton("Отмена", null)
            // Касание мимо окна не должно ничего запускать. Раньше окно
            // закрывалось, а уведомление о выгрузке показывалось сразу,
            // не дожидаясь выбора - выглядело так, будто выгрузка пошла.
            .setCancelable(false)
            .show()
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

    private fun absorbArchive(r: VaultRepo, head: VaultArchive.Head, key: ByteArray) {
        busy = true
        lifecycleScope.launch {
            val added = withContext(Dispatchers.Default) {
                val archive = VaultArchive.open(head, key) ?: return@withContext -1
                r.absorb(archive)
            }
            busy = false
            if (added < 0) toast("Ключ не подходит к этому файлу")
            else {
                toast("Добавлено заметок: " + added)
                showNotes()
            }
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
    private fun button(label: String): Button = styledButton(label, LEVEL_PRIMARY)

    private fun secondaryButton(label: String): Button = styledButton(label, LEVEL_SECONDARY)

    private fun dangerButton(label: String): Button = styledButton(label, LEVEL_DANGER)

    private fun styledButton(label: String, level: Int): Button {
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
            if (level == LEVEL_DANGER) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    VaultIcon(VaultIcon.Kind.TRASH,
                        VaultIcon.tintFor(VaultIcon.Kind.TRASH), dp(18)), null, null, null)
                compoundDrawablePadding = dp(8)
            }
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
