package com.vasil.stepcore

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Профиль дня: ломаная набора высоты + список интервалов плитами.
 *
 * v214: в ленту добавлены ПОЕЗДКИ. Логика сессий вырезает транспорт намеренно
 * (он не должен учить агента уклону), но для понимания дня дыра между двумя
 * прогулками бесполезна: не разобрать, сидел человек дома или ехал. Поездки
 * берутся прямо из корпуса (mode = TRANSPORT) и рисуются отдельным цветом,
 * плоско. Сессии при этом не трогаются - обучение работает как работало.
 *
 * На оси "по шагам" поездка почти невидима, и это честно: шагов в машине нет.
 * Смотреть поездки нужно на оси "по времени".
 */
class DayProfileActivity : AppCompatActivity() {

    private data class Item(
        val startMs: Long,
        val endMs: Long,
        val label: String,
        val steps: Int,
        val durationMs: Long,
        val confirmState: Int,
        val chipShare: Float,
        val transport: Boolean,
        // v217: сколько строк корпуса дал отрезок. -1 = не считали (режим
        // сессий). 0 в журнальном режиме значит "модель этого не видела".
        val rows: Int = -1,
        val journal: Boolean = false,
        val id: Long = 0,
        val userLabel: String? = null
    )

    // 0 = сессии по шагам, 1 = сессии по времени, 2 = журнал (всегда по
    // времени: шаги при включённом экране в журнал не пишутся).
    private var viewMode = 0
    private val byTime: Boolean get() = viewMode >= 1
    private val sourceJournal: Boolean get() = viewMode == 2
    private var dayShift = 0
    private var shown = 20
    private var dayKey = ""       // yyyy-MM-dd показанного дня
    private var items: List<Item> = emptyList()
    private var selected = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_profile)
        val d = resources.displayMetrics.density
        findViewById<LinearLayout>(R.id.chartPlate).background = DoodleBorderDrawable(
            ContextCompat.getColor(this, R.color.axis_dim),
            ContextCompat.getColor(this, R.color.surface),
            311L, d, DoodleBorderDrawable.MAT_ROCK, DoodleBorderDrawable.RIFT_NONE)
        for (id in intArrayOf(R.id.prevDay, R.id.axisToggle, R.id.nextDay)) {
            findViewById<TextView>(id).background = DoodleBorderDrawable(
                ContextCompat.getColor(this, R.color.axis_dim),
                ContextCompat.getColor(this, R.color.surface),
                (312 + id % 7).toLong(), d,
                DoodleBorderDrawable.MAT_ROCK, DoodleBorderDrawable.RIFT_NONE)
        }
        findViewById<TextView>(R.id.axisToggle).setOnClickListener {
            viewMode = (viewMode + 1) % 3; shown = 20; load()
        }
        findViewById<TextView>(R.id.prevDay).setOnClickListener { dayShift++; shown = 20; load() }
        findViewById<TextView>(R.id.nextDay).setOnClickListener {
            if (dayShift > 0) { dayShift--; shown = 20; load() }
        }
        findViewById<TextView>(R.id.moreRows).setOnClickListener { shown += 20; renderList() }
        val editBtn = findViewById<TextView>(R.id.editLabelButton)
        editBtn.background = DoodleBorderDrawable(
            ContextCompat.getColor(this, R.color.accent_amber),
            ContextCompat.getColor(this, R.color.surface),
            888L, d, DoodleBorderDrawable.MAT_ROCK, DoodleBorderDrawable.RIFT_NONE)
        editBtn.setOnClickListener { askEditLabel() }
        val diagBtn = findViewById<TextView>(R.id.diagButton)
        diagBtn.background = DoodleBorderDrawable(
            ContextCompat.getColor(this, R.color.accent_amber),
            ContextCompat.getColor(this, R.color.surface),
            777L, d, DoodleBorderDrawable.MAT_ROCK, DoodleBorderDrawable.RIFT_NONE)
        diagBtn.setOnClickListener { lifecycleScope.launch { collectDiag() } }
        load()
    }

    private fun load() {
        val view = findViewById<DayProfileView>(R.id.profileView)
        val head = findViewById<TextView>(R.id.dayTitle)
        findViewById<TextView>(R.id.axisToggle).text = when (viewMode) {
            0 -> "по шагам"; 1 -> "по времени"; else -> "журнал"
        }

        lifecycleScope.launch {
            val dao = AppDb.get(this@DayProfileActivity).dao()
            val all = dao.reliableSessions()
            if (all.isEmpty()) {
                head.text = "Данных пока нет"
                view.setData(emptyList(), byTime)
                findViewById<LinearLayout>(R.id.intervalList).removeAllViews()
                return@launch
            }
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val days = all.map { dayFmt.format(Date(it.startMs)) }.distinct()
            dayShift = dayShift.coerceIn(0, days.size - 1)
            val day = days[dayShift]
            dayKey = day
            val ofDay = all.filter { dayFmt.format(Date(it.startMs)) == day }
                .sortedBy { it.startMs }

            if (sourceJournal) {
                items = journalItems(dao, day)
                val titleFmtJ = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru"))
                val blind = items.count { it.rows == 0 }
                head.text = titleFmtJ.format(Date(ofDay.first().startMs)) + "\n" +
                    "журнал: " + items.size + " отрезков" +
                    (if (blind > 0) ", без признаков " + blind else "")
                view.setData(
                    items.map {
                        DayProfileView.Seg(it.label, it.steps, it.durationMs, it.startMs)
                    },
                    true
                )
                renderList()
                return@launch
            }
            val walk = ofDay.map {
                Item(it.startMs, it.endMs, it.label, it.nSamples * 20, it.durationMs,
                    it.confirmState, it.chipShare, false, -1, false, it.id, it.userLabel)
            }
            val rides = transportSpans(dao, ofDay.first().startMs, ofDay.last().endMs)
            items = (walk + rides).sortedBy { it.startMs }

            val titleFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru"))
            val steps = walk.sumOf { it.steps }
            head.text = titleFmt.format(Date(ofDay.first().startMs)) + "\n" +
                walk.size + " отрезков, ~" + steps + " шагов" +
                (if (rides.isEmpty()) "" else ", поездок " + rides.size)

            view.setData(
                items.map {
                    DayProfileView.Seg(effLabel(it), it.steps, it.durationMs, it.startMs)
                },
                byTime
            )
            renderList()
        }
    }

    /** Поездки из корпуса. Соседние образцы транспорта склеиваются в отрезок;
     *  мелкие всплески короче минуты не показываем - это шум, а не поездка. */
    private suspend fun transportSpans(dao: StepDao, from: Long, to: Long): List<Item> {
        val list = dao.transportSamples(from - 3_600_000L, to + 3_600_000L)
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<Item>()
        var start = list.first().timeMs
        var prev = start
        for (i in 1 until list.size) {
            val t = list[i].timeMs
            if (t - prev > 300_000L) {
                if (prev - start >= 60_000L)
                    out.add(Item(start, prev, "TRANSPORT", 0, prev - start, 0, 1f, true))
                start = t
            }
            prev = t
        }
        if (prev - start >= 60_000L)
            out.add(Item(start, prev, "TRANSPORT", 0, prev - start, 0, 1f, true))
        return out
    }

    /** Лента из журнала: интервалы между нажатиями метки.
     *  Времена нажатий - факт, записанный в момент действия человека.
     *  Для каждого интервала считаем строки корпуса: ноль означает, что
     *  отрезок был, но модель его не видела. */
    private suspend fun journalItems(dao: StepDao, day: String): List<Item> {
        val events = dao.eventsOfDay(day)
        if (events.isEmpty()) return emptyList()
        val dayFrom = events.first().timeMs
        val dayTo = events.last().timeMs
        val rows = dao.samplesBetween(dayFrom, dayTo + 60_000L)

        val markAt = ArrayList<Long>()
        val markLab = ArrayList<String>()
        for (e in events) {
            if (!e.text.startsWith("Уклон: ")) continue
            val t = e.text.removePrefix("Уклон: ").replace(" (шторка)", "").trim()
            markAt.add(e.timeMs)
            markLab.add(
                when (t) {
                    "в гору" -> "UP"; "с горы" -> "DOWN"
                    "не отмечено" -> "NONE"; else -> "FLAT"
                }
            )
        }
        if (markAt.isEmpty()) return emptyList()

        val out = ArrayList<Item>()
        for (i in markAt.indices) {
            val from = markAt[i]
            val to = if (i + 1 < markAt.size) markAt[i + 1] else dayTo
            val dur = to - from
            if (dur < 12_000L) continue          // мгновенные перещёлкивания
            val n = rows.count { it.timeMs in from until to }
            out.add(
                Item(from, to, markLab[i], stepsBetween(events, from, to), dur,
                    0, 0f, false, n, true)
            )
        }
        return out
    }

    /** Правка метки. Исходная остаётся: переписывать прошлое нельзя. */
    private fun askEditLabel() {
        val i = selected
        if (i < 0 || i >= items.size) return
        val s = items[i]
        if (s.journal || s.transport || s.id == 0L) return
        val opts = arrayOf("▲ В гору", "━ Ровно", "▼ С горы", "Вернуть как было")
        val codes = arrayOf("UP", "FLAT", "DOWN", "")
        AlertDialog.Builder(this)
            .setCustomTitle(editTitle(s))
            .setItems(opts) { _, which ->
                lifecycleScope.launch {
                    val dao = AppDb.get(this@DayProfileActivity).dao()
                    if (codes[which] == "") dao.clearUserLabel(s.id)
                    else dao.setUserLabel(s.id, codes[which])
                    android.widget.Toast.makeText(
                        this@DayProfileActivity,
                        if (codes[which] == "") "Правка снята" else "Исправлено",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    load()
                }
            }
            .show()
    }

    private fun editTitle(s: Item): View {
        val tv = TextView(this)
        val f = SimpleDateFormat("HH:mm", Locale("ru"))
        tv.text = "Отрезок " + f.format(Date(s.startMs)) + "–" + f.format(Date(s.endMs)) +
            "\nСейчас: " + labelRu(effLabel(s)) +
            "\n\nИсходная метка сохранится — правка ложится рядом."
        tv.textSize = 15f
        tv.setTextColor(getColor(R.color.text_main))
        val pad = (16 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad, pad, pad / 2)
        return tv
    }

    /** Истина для показа и обучения: правка человека главнее исходной метки. */
    private fun effLabel(s: Item): String = s.userLabel ?: s.label

    private fun renderList() {
        val box = findViewById<LinearLayout>(R.id.intervalList)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val tFmt = SimpleDateFormat("HH:mm", Locale("ru"))
        for ((i, s) in items.take(shown).withIndex()) {
            val color = DayProfileView.colorFor(effLabel(s))
            val tv = TextView(this)
            tv.text = tFmt.format(Date(s.startMs)) + " – " + tFmt.format(Date(s.endMs)) +
                "   " + labelRu(effLabel(s)) +
                (if (s.userLabel != null) " ✎" else "") + "\n" +
                (if (s.journal)
                    (s.durationMs / 60000L).toString() + " мин" +
                        (if (s.steps > 0) " · ~" + s.steps + " шагов" else "") +
                        " · " + (if (s.rows == 0) "без признаков" else "строк " + s.rows)
                 else if (s.transport) "поездка · " + (s.durationMs / 60000L) + " мин"
                 else "~" + s.steps + " шагов · " + (s.durationMs / 60000L) + " мин · " +
                     confirmRu(s.confirmState))
            tv.textSize = 14f
            tv.setTextColor(color)
            tv.setLineSpacing(3f * d, 1f)
            val pad = (12 * d).toInt()
            tv.setPadding(pad, pad, pad, pad)
            tv.gravity = Gravity.START
            tv.background = DoodleBorderDrawable(
                color, ContextCompat.getColor(this, R.color.surface),
                400L + i, d, DoodleBorderDrawable.MAT_ROCK,
                when (effLabel(s)) {
                    "UP" -> DoodleBorderDrawable.RIFT_UP
                    "DOWN" -> DoodleBorderDrawable.RIFT_DOWN
                    "FLAT" -> DoodleBorderDrawable.RIFT_FLAT
                    else -> DoodleBorderDrawable.RIFT_NONE
                })
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (6 * d).toInt()
            tv.layoutParams = lp
            tv.setOnClickListener { selectRow(i) }
            box.addView(tv)
        }
        findViewById<TextView>(R.id.moreRows).text =
            if (items.size > shown) "показать ещё (" + (items.size - shown) + ")" else ""
    }

    private fun selectRow(i: Int) {
        findViewById<DayProfileView>(R.id.profileView).select(i)
        selected = i
        val s = items[i]
        // Править можно только настоящую сессию: журнальный отрезок - запись
        // действия человека, а поездка не про уклон.
        findViewById<TextView>(R.id.editLabelButton).visibility =
            if (!s.journal && !s.transport && s.id != 0L) View.VISIBLE else View.GONE
        val f = SimpleDateFormat("EEE, d MMMM yyyy, HH:mm", Locale("ru"))
        val t2 = SimpleDateFormat("HH:mm", Locale("ru"))
        val det = findViewById<TextView>(R.id.detailText)
        det.setTextColor(DayProfileView.colorFor(effLabel(s)))
        det.text = f.format(Date(s.startMs)) + "–" + t2.format(Date(s.endMs)) + "\n" +
            (if (s.journal)
                labelRu(s.label) + " · " + (s.durationMs / 60000L) + " мин" +
                    (if (s.steps > 0) " · ~" + s.steps + " шагов (при выкл. экране)" else "") +
                    "\n" + (if (s.rows == 0)
                        "Строк корпуса нет: отрезок был, но модель его не видела."
                     else "Строк корпуса: " + s.rows)
             else if (s.transport)
                "поездка · " + (s.durationMs / 60000L) + " мин\n" +
                    "Шаги в транспорте не считаются как ходьба, и уклон здесь не учится."
             else {
                val carry = when {
                    s.chipShare >= 0.6f -> "в кармане"
                    s.chipShare <= 0.4f -> "в руке"
                    else -> "то в руке, то в кармане"
                }
                labelRu(effLabel(s)) + " · ~" + s.steps + " шагов · " +
                    (s.durationMs / 60000L) + " мин · телефон " + carry +
                    "\nОтвет: " + confirmRu(s.confirmState) +
                    (if (s.userLabel != null)
                        "\nИсправлено человеком, исходная метка: " + labelRu(s.label)
                     else "")
            })
    }

    /** Сверка журнала с корпусом.
     *  Интервалы меток берутся из событий "Уклон: ..." - это ТОЧНЫЕ времена
     *  нажатий человека, факт, а не реконструкция. Для каждого интервала
     *  считаются шаги (из записей "За время блокировки") и число строк
     *  корпуса. Интервал вида "6 мин, 717 шагов -> 0 строк" и есть дыра. */
    private suspend fun collectDiag() {
        if (dayKey == "") return
        val dao = AppDb.get(this).dao()
        val events = dao.eventsOfDay(dayKey)
        if (events.isEmpty()) {
            android.widget.Toast.makeText(
                this, "Журнал за этот день пуст", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val dayFrom = events.first().timeMs
        val dayTo = events.last().timeMs + 60_000L
        val rows = dao.samplesBetween(dayFrom, dayTo)

        // Интервалы меток: от нажатия до следующего нажатия.
        data class Mark(val at: Long, val label: String)
        val marks = ArrayList<Mark>()
        for (e in events) {
            if (!e.text.startsWith("Уклон: ")) continue
            val t = e.text.removePrefix("Уклон: ").replace(" (шторка)", "").trim()
            val lab = when (t) {
                "в гору" -> "UP"; "с горы" -> "DOWN"
                "не отмечено" -> "NONE"; else -> "FLAT"
            }
            marks.add(Mark(e.timeMs, lab))
        }

        val tf = SimpleDateFormat("HH:mm:ss", Locale("ru"))
        val sb = StringBuilder()
        sb.append("StepCore — диагностика сбора\n")
        sb.append(SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru"))
            .format(Date(dayFrom))).append("\n\n")
        sb.append("Строк корпуса за день: ").append(rows.size)
            .append(" (детектор ").append(rows.count { it.sampleSource == 0 })
            .append(", чип ").append(rows.count { it.sampleSource == 1 }).append(")\n\n")

        if (marks.isEmpty()) {
            sb.append("Нажатий метки в журнале нет.\n")
        } else {
            sb.append("Интервалы меток из журнала:\n")
            val perLabelMin = HashMap<String, Float>()
            val perLabelRows = HashMap<String, Int>()
            for (i in marks.indices) {
                val from = marks[i].at
                val to = if (i + 1 < marks.size) marks[i + 1].at else dayTo
                val lab = marks[i].label
                val mins = (to - from) / 60000f
                if (mins < 0.2f) continue          // мгновенные перещёлкивания
                val inRange = rows.filter { it.timeMs in from until to }
                val steps = stepsBetween(events, from, to)
                perLabelMin[lab] = (perLabelMin[lab] ?: 0f) + mins
                perLabelRows[lab] = (perLabelRows[lab] ?: 0) + inRange.size
                sb.append("  ").append(tf.format(Date(from))).append("–")
                    .append(tf.format(Date(to))).append("  ")
                    .append(labelRu(lab)).append("  ")
                    .append(String.format(Locale.US, "%.1f", mins)).append(" мин · ~")
                    .append(steps).append(" шаг. · строк ").append(inRange.size)
                    .append(" (д ").append(inRange.count { it.sampleSource == 0 })
                    .append(", ч ").append(inRange.count { it.sampleSource == 1 }).append(")")
                if (inRange.isEmpty() && steps > 0) sb.append("   <-- ПУСТО")
                sb.append("\n")
            }
            sb.append("\nСводка по меткам:\n")
            for (lab in arrayOf("UP", "DOWN", "FLAT", "NONE")) {
                val m = perLabelMin[lab] ?: continue
                sb.append("  ").append(labelRu(lab)).append(": ")
                    .append(String.format(Locale.US, "%.1f", m)).append(" мин · строк ")
                    .append(perLabelRows[lab] ?: 0).append("\n")
            }
        }

        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("StepCore diag", sb.toString()))
        android.widget.Toast.makeText(
            this, "Диагностика в буфере", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Шаги внутри интервала - из записей журнала "За время блокировки: N шагов".
     *  Это единственный честный источник: корпус тут как раз и пуст. */
    private fun stepsBetween(events: List<EventRecord>, from: Long, to: Long): Int {
        var sum = 0
        for (e in events) {
            if (e.timeMs < from || e.timeMs >= to) continue
            if (!e.text.startsWith("За время блокировки:")) continue
            val digits = e.text.filter { it.isDigit() || it == ' ' }.trim().split(" ")
            val n = digits.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 0
            sum += n
        }
        return sum
    }

    private fun labelRu(l: String) = when (l) {
        "UP" -> "в гору"; "DOWN" -> "с горы"
        "TRANSPORT" -> "транспорт"
        "NONE" -> "не отмечено"; else -> "ровно"
    }

    private fun confirmRu(c: Int) = when (c) {
        1 -> "подтверждено"
        2 -> "дефект"
        3 -> "не подтверждено"
        else -> "не спрошено"
    }
}
