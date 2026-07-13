package com.vasil.stepcore.survival

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.vasil.stepcore.DoodleIconDrawable
import com.vasil.stepcore.DoodleProgressView
import com.vasil.stepcore.DoodleUi
import androidx.lifecycle.lifecycleScope
import com.vasil.stepcore.R
import com.vasil.stepcore.survival.engine.SurvivalEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * Экран Survival Mode. Три состояния одного layout:
 * - форма старта + архив (активной экспедиции нет);
 * - активная экспедиция (паспорт, догон, журнал);
 * - просмотр завершённой экспедиции из архива (read-only).
 *
 * Догон мира происходит ТОЛЬКО здесь (onResume / кнопка «Обновить») —
 * ноль фоновых вычислений, бюджет батареи режима равен нулю.
 */
class SurvivalActivity : AppCompatActivity() {

    private lateinit var repo: SurvivalRepo

    // выбор в форме старта
    private var season = defaultSeason()
    private var durDays = 60
    private var tempo = 5000
    private var avgSteps = 0

    /** Системный «назад» из просмотра архива возвращает к списку,
     *  а не выбрасывает на главный экран. Включается только в архиве. */
    private lateinit var backToList: OnBackPressedCallback

    /** id экспедиции из архива, открытой на просмотр; -1 = не в архиве. */
    private var viewingId = -1L

    /** id экспедиции, которая СЕЙЧАС на экране (активная или архивная).
     *  Кнопки копирования/шаринга работают с ней. -1 = форма старта. */
    private var shownId = -1L

    private lateinit var startBox: LinearLayout
    private lateinit var activeBox: LinearLayout
    private lateinit var archiveBox: LinearLayout
    private lateinit var seasonBtns: List<Button>
    private lateinit var durBtns: List<Button>
    private lateinit var tempoBtns: List<Button>
    private lateinit var tempoHint: TextView
    private lateinit var expTitle: TextView
    private lateinit var expPassport: TextView
    private lateinit var expState: TextView
    private lateinit var expCountdown: TextView
    private lateinit var syncNote: TextView
    private lateinit var journalBox: LinearLayout
    private lateinit var actionRow: LinearLayout
    private lateinit var refreshBtn: Button
    private lateinit var finishBtn: Button
    private lateinit var backBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var journalActions: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_survival)
        // V14.3: дудл-стиль — лагерь в шапке экрана экспедиции.
        findViewById<com.vasil.stepcore.DoodleSceneView>(R.id.doodleHeader)
            .setScene(com.vasil.stepcore.DoodleSceneView.EXPEDITION)
        repo = SurvivalRepo(this)

        startBox = findViewById(R.id.startBox)
        activeBox = findViewById(R.id.activeBox)
        archiveBox = findViewById(R.id.archiveBox)
        tempoHint = findViewById(R.id.tempoHintText)
        expTitle = findViewById(R.id.expTitleText)
        expPassport = findViewById(R.id.expPassportText)
        expState = findViewById(R.id.expStateText)
        expCountdown = findViewById(R.id.expCountdownText)
        syncNote = findViewById(R.id.syncNoteText)
        journalBox = findViewById(R.id.journalBox)
        actionRow = findViewById(R.id.actionRow)
        refreshBtn = findViewById(R.id.refreshBtn)
        finishBtn = findViewById(R.id.finishBtn)
        backBtn = findViewById(R.id.backBtn)
        copyBtn = findViewById(R.id.copyBtn)
        shareBtn = findViewById(R.id.shareBtn)
        journalActions = findViewById(R.id.journalActions)

        seasonBtns = listOf(
            findViewById(R.id.seasonWinterBtn), findViewById(R.id.seasonSpringBtn),
            findViewById(R.id.seasonSummerBtn), findViewById(R.id.seasonAutumnBtn),
        )
        durBtns = listOf(
            findViewById(R.id.dur30Btn), findViewById(R.id.dur60Btn), findViewById(R.id.dur100Btn),
        )
        tempoBtns = listOf(
            findViewById(R.id.tempo100Btn), findViewById(R.id.tempo1000Btn),
            findViewById(R.id.tempo5000Btn), findViewById(R.id.tempo10000Btn),
        )

        // Сезон узнаётся по значку, а не только по слову.
        icon(seasonBtns[0], DoodleIconDrawable.SNOWFLAKE, R.color.accent_blue_bright)
        icon(seasonBtns[1], DoodleIconDrawable.LEAF, R.color.accent_teal_bright)
        icon(seasonBtns[2], DoodleIconDrawable.SUN, R.color.accent_amber)
        icon(seasonBtns[3], DoodleIconDrawable.AUTUMN, R.color.accent_amber_bright)
        for (b in durBtns) icon(b, DoodleIconDrawable.FLAG, R.color.accent_violet_bright)
        for (b in tempoBtns) icon(b, DoodleIconDrawable.FOOTPRINTS, R.color.accent_violet_bright)

        // Карточка-подсказка: рюкзак как знак снаряжения.
        DoodleUi.frame(tempoHint, R.color.accent_violet, R.color.surface, 801L)
        tempoHint.setCompoundDrawablesWithIntrinsicBounds(
            null, null,
            DoodleIconDrawable(DoodleIconDrawable.BACKPACK,
                ContextCompat.getColor(this, R.color.accent_teal), 
                resources.displayMetrics.density, 34f),
            null)
        tempoHint.compoundDrawablePadding = dp(10)
        tempoHint.setPadding(dp(14), dp(12), dp(14), dp(12))

        // Заголовок архива: сундук.
        findViewById<TextView>(R.id.archiveTitle).apply {
            setCompoundDrawablesWithIntrinsicBounds(
                null, null,
                DoodleIconDrawable(DoodleIconDrawable.CHEST,
                    ContextCompat.getColor(this@SurvivalActivity, R.color.accent_amber),
                    resources.displayMetrics.density, 26f),
                null)
            compoundDrawablePadding = dp(8)
        }

        for (i in seasonBtns.indices) {
            seasonBtns[i].setOnClickListener { season = i; refreshStartControls() }
        }
        val durVals = intArrayOf(30, 60, 100)
        for (i in durBtns.indices) {
            durBtns[i].setOnClickListener { durDays = durVals[i]; refreshStartControls() }
        }
        val tempoVals = intArrayOf(100, 1000, 5000, 10000)
        for (i in tempoBtns.indices) {
            tempoBtns[i].setOnClickListener { tempo = tempoVals[i]; refreshStartControls() }
        }

        findViewById<Button>(R.id.startExpBtn).setOnClickListener { v ->
            v.isEnabled = false
            lifecycleScope.launch {
                repo.start(season, durDays, tempo)
                v.isEnabled = true
                refreshUi(runSync = false)
            }
        }

        refreshBtn.setOnClickListener {
            refreshBtn.isEnabled = false
            lifecycleScope.launch {
                refreshUi(runSync = true)
                refreshBtn.isEnabled = true
            }
        }

        finishBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Завершить экспедицию?")
                .setMessage("Накопленные шаги будут учтены, мир уйдёт в архив. Действие необратимо.")
                .setPositiveButton("Завершить") { _, _ ->
                    finishBtn.isEnabled = false
                    lifecycleScope.launch {
                        repo.finishVoluntary()
                        finishBtn.isEnabled = true
                        refreshUi(runSync = false)
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        backBtn.setOnClickListener {
            viewingId = -1L
            lifecycleScope.launch { refreshUi(runSync = false) }
        }

        copyBtn.setOnClickListener {
            val id = shownId
            if (id < 0) return@setOnClickListener
            lifecycleScope.launch {
                val text = repo.exportText(id)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Экспедиция №" + id, text))
                android.widget.Toast.makeText(this@SurvivalActivity,
                    "Журнал скопирован", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        shareBtn.setOnClickListener {
            val id = shownId
            if (id < 0) return@setOnClickListener
            lifecycleScope.launch {
                val text = repo.exportText(id)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Экспедиция №" + id)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "Поделиться экспедицией"))
            }
        }

        backToList = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                viewingId = -1L
                lifecycleScope.launch { refreshUi(runSync = false) }
            }
        }
        onBackPressedDispatcher.addCallback(this, backToList)

        lifecycleScope.launch {
            avgSteps = repo.avgDailySteps()
            refreshStartControls()
        }
        refreshStartControls()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshUi(runSync = viewingId < 0) }
    }

    /** Единая точка обновления экрана. runSync = догнать мир по шагам. */
    private suspend fun refreshUi(runSync: Boolean) {
        if (viewingId >= 0) {
            val e = repo.byId(viewingId)
            if (e != null) { renderReadOnly(e); return }
            viewingId = -1L
        }
        var e = repo.active()
        var note: String? = null
        if (e != null && runSync) {
            val o = repo.sync()
            if (o != null) {
                if (o.newDays > 0) {
                    note = "+" + o.newDays + " " + SurvivalEngine.daysWord(o.newDays) +
                        " мира · " + o.consumedSteps + " шагов"
                }
                if (o.completed) {
                    // экспедиция закрылась этим догоном: открыть её архивную страницу
                    viewingId = o.expeditionId
                    val done = repo.byId(o.expeditionId)
                    if (done != null) { renderReadOnly(done); return }
                }
            }
            e = repo.active()
        }
        if (e != null) renderActive(e, note) else renderStartForm()
    }

    // ---------- отрисовка состояний ----------

    private suspend fun renderActive(e: Expedition, note: String?) {
        startBox.visibility = View.GONE
        activeBox.visibility = View.VISIBLE
        backBtn.visibility = View.GONE
        backToList.isEnabled = false
        actionRow.visibility = View.VISIBLE
        expCountdown.visibility = View.VISIBLE

        fillHeader(e)
        expCountdown.text = "До следующего дня мира: " +
            (e.stepsPerTick - e.stepRemainder) + " шагов"
        if (note != null) {
            syncNote.text = note
            syncNote.visibility = View.VISIBLE
        } else {
            syncNote.visibility = View.GONE
        }
        shownId = e.id
        journalActions.visibility = View.VISIBLE
        renderJournal(e)
    }

    private suspend fun renderReadOnly(e: Expedition) {
        startBox.visibility = View.GONE
        activeBox.visibility = View.VISIBLE
        backBtn.visibility = View.VISIBLE
        backToList.isEnabled = true
        actionRow.visibility = View.GONE
        expCountdown.visibility = View.GONE

        fillHeader(e)
        val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val label = if (e.status == "done_success") "Завершена по плану" else "Прервана"
        syncNote.text = label + " · " + fmt.format(Date(e.finishedMs))
        syncNote.visibility = View.VISIBLE
        shownId = e.id
        journalActions.visibility = View.VISIBLE
        renderJournal(e)
    }

    private fun fillHeader(e: Expedition) {
        expTitle.text = "Экспедиция №" + e.id
        expPassport.text = "Северная тайга · старт: " +
            SurvivalEngine.SEASON_RU[e.startSeason] +
            " · план " + e.plannedDays + " дн. · темп " + e.stepsPerTick + " шаг/день"
        val nowSeason = if (e.ticksDone == 0) e.startSeason
        else SurvivalEngine(e.seed, e.startSeason, e.startOffset).seasonOf(e.ticksDone)
        expState.text = "День " + e.ticksDone + " из " + e.plannedDays +
            " · " + SurvivalEngine.SEASON_RU[nowSeason]
    }

    private suspend fun renderStartForm() {
        activeBox.visibility = View.GONE
        backToList.isEnabled = false
        startBox.visibility = View.VISIBLE
        shownId = -1L
        journalActions.visibility = View.GONE
        refreshStartControls()

        archiveBox.removeAllViews()
        val arch = repo.archive()
        if (arch.isEmpty()) {
            archiveBox.addView(dimRow("Пока пусто. Первая экспедиция впереди."))
            return
        }
        for (a in arch) {
            archiveBox.addView(archiveRow(a))
        }
    }

    /** Цвет и значок сезона - одни и те же во всём экране. */
    private fun seasonIcon(season: Int): Int = when (season) {
        0 -> DoodleIconDrawable.SNOWFLAKE
        1 -> DoodleIconDrawable.LEAF
        2 -> DoodleIconDrawable.SUN
        else -> DoodleIconDrawable.AUTUMN
    }

    private fun seasonColor(season: Int): Int = when (season) {
        0 -> R.color.accent_blue_bright
        1 -> R.color.accent_teal_bright
        2 -> R.color.accent_amber
        else -> R.color.accent_amber_bright
    }

    /**
     * Строка архива: рамка, значок сезона, текст и ПОЛОСА ПРОГРЕССА с
     * процентом. Раньше это была голая строка текста - по ней невозможно
     * было с одного взгляда понять, далеко ли зашла экспедиция.
     */
    private fun archiveRow(a: Expedition): View {
        val done = if (a.plannedDays > 0)
            a.ticksDone.toFloat() / a.plannedDays else 0f
        val pct = (done * 100f).toInt().coerceIn(0, 100)
        val colorRes = seasonColor(a.startSeason)
        val mark = if (a.status == "done_success") "по плану" else "прервана"

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setOnClickListener {
                viewingId = a.id
                lifecycleScope.launch { refreshUi(runSync = false) }
            }
        }
        DoodleUi.frame(row, colorRes, R.color.surface, 900L + a.id * 13L)

        row.addView(android.widget.ImageView(this).apply {
            setImageDrawable(DoodleIconDrawable(seasonIcon(a.startSeason),
                ContextCompat.getColor(this@SurvivalActivity, colorRes),
                resources.displayMetrics.density, 24f))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })

        row.addView(TextView(this).apply {
            text = "№" + a.id + " · " + SurvivalEngine.SEASON_RU[a.startSeason]
            textSize = 17f
            setTextColor(ContextCompat.getColor(this@SurvivalActivity, colorRes))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = dp(8)
            layoutParams = lp
        })

        row.addView(TextView(this).apply {
            text = a.ticksDone.toString() + "/" + a.plannedDays + " дн. · " + mark
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@SurvivalActivity, R.color.text_dim))
            val lp = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = dp(10)
            layoutParams = lp
        })

        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(72),
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        bars.addView(TextView(this).apply {
            text = "$pct%"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@SurvivalActivity, colorRes))
        })
        bars.addView(DoodleProgressView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(12))
            setProgress(done,
                ContextCompat.getColor(this@SurvivalActivity, colorRes),
                a.id)
        })
        row.addView(bars)
        return row
    }

    // ---------- вспомогательное ----------

    /**
     * Журнал карточками: день = шапка фактов + строки событий, окрашенные
     * по категории (JournalStyle). Новое сверху.
     *
     * Шапка берётся не из базы, а из пересчёта мира по seed — поэтому
     * карточки есть и у экспедиций, прожитых до этого обновления.
     */
    private suspend fun renderJournal(e: Expedition) {
        journalBox.removeAllViews()
        val events = repo.events(e.id)
        if (events.isEmpty()) {
            journalBox.addView(dimRow("Журнал пуст."))
            return
        }
        val byTick = HashMap<Int, MutableList<ExpeditionEvent>>()
        for (ev in events) byTick.getOrPut(ev.tick) { mutableListOf() }.add(ev)
        val heads = repo.days(e).associateBy { it.tick }

        val first = maxOf(0, e.ticksDone - MAX_CARDS + 1)
        for (t in e.ticksDone downTo first) {
            val dayEvents = byTick[t]
                ?.filter { JournalStyle.visibleInCard(it.category) }
                ?: emptyList()
            val head = heads[t]
            if (dayEvents.isEmpty() && head == null) continue
            journalBox.addView(dayCard(t, head, dayEvents))
        }
        if (first > 0) {
            journalBox.addView(dimRow("Показаны последние " + MAX_CARDS +
                " дней. Полный журнал — в «Поделиться»."))
        }
    }

    private fun dayCard(
        tick: Int,
        head: com.vasil.stepcore.survival.engine.DaySnap?,
        events: List<ExpeditionEvent>,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (head != null) {
            top.addView(ImageView(this).apply {
                setImageDrawable(SkyGlyphDrawable(
                    head.cloud, head.wind, head.precip, head.fog,
                    ContextCompat.getColor(this@SurvivalActivity, R.color.accent_blue_bright),
                    resources.displayMetrics.density, tick.toLong(),
                ))
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            })
        }
        top.addView(TextView(this).apply {
            text = if (tick == 0) "Старт" else "Д" + tick
            textSize = 17f
            // Жирное начертание берётся из семейства шрифта (700), а не
            // из синтетического утолщения системой: буквы остаются теми же.
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@SurvivalActivity, R.color.accent_red))
            setPadding(dp(if (head != null) 8 else 0), 0, dp(8), 0)
        })
        if (head != null) {
            top.addView(TextView(this).apply {
                text = SurvivalEngine.headerOf(head)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@SurvivalActivity, R.color.text_dim))
            })
        }
        card.addView(top)

        // Внутри дня события идут по времени суток. Метка фазы появляется
        // только когда время сменилось: дневник, а не расписание.
        var lastPhase = -1
        for (ev in events.sortedWith(compareBy<ExpeditionEvent>({ it.phase }, { it.id }))) {
            if (ev.phase != lastPhase) {
                lastPhase = ev.phase
                card.addView(TextView(this).apply {
                    text = SurvivalEngine.PHASE_RU[ev.phase.coerceIn(0, 3)]
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(
                        this@SurvivalActivity, R.color.text_dim))
                    setPadding(dp(6), dp(6), 0, 0)
                })
            }
            card.addView(TextView(this).apply {
                text = JournalStyle.markOf(ev.category) + " " + ev.text
                textSize = 18f
                setTextColor(ContextCompat.getColor(
                    this@SurvivalActivity, JournalStyle.colorRes(ev.category)))
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(dp(4), dp(2), 0, 0)
            })
        }
        return card
    }

    /** Подсветка выбранных кнопок формы + пересчёт подсказки. */
    private fun refreshStartControls() {
        markGroup(seasonBtns, season)
        markGroup(durBtns, when (durDays) { 30 -> 0; 60 -> 1; else -> 2 })
        markGroup(tempoBtns, when (tempo) { 100 -> 0; 1000 -> 1; 5000 -> 2; else -> 3 })
        val sb = StringBuilder()
        if (avgSteps > 0) {
            val est = (durDays.toLong() * tempo + avgSteps - 1) / avgSteps
            sb.append("Твой средний темп ~").append(avgSteps)
                .append(" шагов/день. Этот план: примерно ").append(est)
                .append(" ").append(SurvivalEngine.daysWord(est.toInt())).append(" реальной ходьбы.")
        } else {
            sb.append("Темп мира не зависит от скорости ходьбы — только от числа шагов.")
        }
        sb.append("\nТемп 100 — тестовый: увидеть жизнь мира за одну прогулку.")
        tempoHint.text = sb.toString()
    }

    /**
     * Выбор показывается РАМКОЙ, а не прозрачностью.
     *
     * Раньше выбранная кнопка отличалась только цветом текста и alpha - на
     * тёмном фоне это почти не читалось, и весь экран выглядел монотонным
     * списком слов. Теперь выбранная - янтарная карточка с заливкой, а
     * невыбранные - тусклые фиолетовые контуры. Разница видна мгновенно.
     */
    private fun markGroup(btns: List<Button>, selected: Int) {
        for (i in btns.indices) {
            val sel = i == selected
            btns[i].alpha = 1f
            if (sel) {
                DoodleUi.frame(btns[i], R.color.accent_amber, R.color.surface_amber,
                    600L + i * 7L)
                btns[i].setTextColor(ContextCompat.getColor(this, R.color.accent_amber_bright))
            } else {
                DoodleUi.frame(btns[i], R.color.accent_violet, R.color.surface,
                    700L + i * 7L)
                btns[i].setTextColor(ContextCompat.getColor(this, R.color.text_dim))
            }
        }
    }

    /** Иконка слева от текста кнопки: кнопка остаётся кнопкой, меняется вид. */
    private fun icon(btn: Button, type: Int, colorRes: Int) {
        val dr = DoodleIconDrawable(type, ContextCompat.getColor(this, colorRes),
            resources.displayMetrics.density)
        btn.setCompoundDrawablesWithIntrinsicBounds(dr, null, null, null)
        btn.compoundDrawablePadding = dp(6)
    }

    private fun dimRow(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@SurvivalActivity, R.color.text_dim))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Сколько последних дней мира показывать карточками. Дальше —
         *  экспорт: сотни View в одном ScrollView не нужны никому. */
        private const val MAX_CARDS = 150

        private fun defaultSeason(): Int = when (LocalDate.now().monthValue) {
            12, 1, 2 -> 0
            3, 4, 5 -> 1
            6, 7, 8 -> 2
            else -> 3
        }
    }

    // Механизм дудл-анимации крутится, пока виден хоть один экран.
    // onStart нового экрана срабатывает РАНЬШЕ onStop старого, поэтому при
    // переходе между вкладками счётчик не касается нуля и анимация не глохнет.
    override fun onStart() {
        super.onStart()
        com.vasil.stepcore.BoilClock.screenStarted()
    }

    override fun onStop() {
        com.vasil.stepcore.BoilClock.screenStopped()
        super.onStop()
    }
}
