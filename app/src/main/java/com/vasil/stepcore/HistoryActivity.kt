package com.vasil.stepcore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private val selectedLines = LinkedHashSet<String>()
    private var copySelBtn: Button? = null
    private var currentFilterDays = 7
    private var visibleDays: List<DayRecord> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val csvSaver =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) lifecycleScope.launch {
                val days = AppDb.get(this@HistoryActivity).dao().allDays()
                val csv = buildString {
                    appendLine("date,walk_steps,run_steps,total")
                    days.forEach { appendLine("${it.date},${it.walkSteps},${it.runSteps},${it.walkSteps + it.runSteps}") }
                }
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                toast("CSV сохранён")
            }
        }

    private val jsonSaver =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) lifecycleScope.launch {
                val dao = AppDb.get(this@HistoryActivity).dao()
                val days = dao.allDays()
                val events = dao.allEvents()
                val json = buildString {
                    appendLine("{")
                    appendLine("\"days\":[")
                    append(days.joinToString(",\n") {
                        "{\"date\":\"${it.date}\",\"walk\":${it.walkSteps},\"run\":${it.runSteps}}"
                    })
                    appendLine("],")
                    appendLine("\"events\":[")
                    append(events.joinToString(",\n") {
                        "{\"timeMs\":${it.timeMs},\"date\":\"${it.date}\",\"text\":\"${it.text}\"}"
                    })
                    appendLine("]}")
                }
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                toast("JSON сохранён")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<Button>(R.id.filter7).setOnClickListener { setFilter(7) }
        findViewById<Button>(R.id.filter30).setOnClickListener { setFilter(30) }
        findViewById<Button>(R.id.filter365).setOnClickListener { setFilter(365) }
        findViewById<Button>(R.id.filterAll).setOnClickListener { setFilter(Int.MAX_VALUE) }

        copySelBtn = findViewById(R.id.copySelectedButton)
        updateSelLabel()
        copySelBtn!!.setOnClickListener {
            copyLine(selectedLines.sorted().joinToString("\n"))
        }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copyVisible() }
        findViewById<Button>(R.id.exportCsvButton).setOnClickListener {
            csvSaver.launch("stepcore_days.csv")
        }
        findViewById<Button>(R.id.exportJsonButton).setOnClickListener {
            jsonSaver.launch("stepcore_full.json")
        }

        findViewById<Button>(R.id.deleteButton).setOnClickListener {
            val input = findViewById<EditText>(R.id.deleteConfirmInput).text.toString().trim()
            if (input != "УДАЛИТЬ ДАННЫЕ") {
                toast("Для удаления введи точно: УДАЛИТЬ ДАННЫЕ")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val dao = AppDb.get(this@HistoryActivity).dao()
                dao.deleteAllDays()
                dao.deleteAllEvents()
                    dao.deleteAllHours()
                findViewById<EditText>(R.id.deleteConfirmInput).setText("")
                toast("Вся история удалена")
                reload()
            }
        }

        setFilter(7)
    }

    private fun setFilter(days: Int) {
        currentFilterDays = days
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val dao = AppDb.get(this@HistoryActivity).dao()
            visibleDays = if (currentFilterDays == Int.MAX_VALUE) dao.allDays()
            else dao.recentDays(currentFilterDays)

            val summary = findViewById<TextView>(R.id.summaryText)
            val totalWalk = visibleDays.sumOf { it.walkSteps }
            val totalRun = visibleDays.sumOf { it.runSteps }
            summary.text = "Дней: ${visibleDays.size}   " +
                    "Всего: ${totalWalk + totalRun}   Ходьба: $totalWalk   Бег: $totalRun"

            val container = findViewById<LinearLayout>(R.id.daysContainer)
            container.removeAllViews()
            visibleDays.forEach { day -> container.addView(makeDayRow(day)) }
        }
    }

    /**
     * День -> ленивый список ЧАСОВ (V9.4). Раскрытие дня рендерит только
     * заголовки непустых часов (дёшево), раскрытие часа грузит и рендерит
     * события ЭТОГО часа из БД (eventsInRange). Снимает фриз при 1000+
     * логах: раньше раскрытие дня строило все строки разом.
     */
    private fun makeDayRow(day: DayRecord): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val total = day.walkSteps + day.runSteps
        val headerLine = "${day.date}   $total шагов (ходьба ${day.walkSteps}, бег ${day.runSteps})"
        val header = TextView(this).apply {
            text = "$headerLine  \u25b8"
            textSize = 16f
            setPadding(0, 20, 0, 20)
            setOnLongClickListener { copyLine(headerLine); true }
        }
        val hoursBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 0, 0, 12)
        }
        header.setOnClickListener {
            if (hoursBox.visibility == View.GONE) {
                hoursBox.visibility = View.VISIBLE
                header.text = "$headerLine  \u25be"
                lifecycleScope.launch {
                    hoursBox.removeAllViews()
                    val counts = AppDb.get(this@HistoryActivity).dao().eventHourCounts(day.date)
                    if (counts.isEmpty()) {
                        hoursBox.addView(TextView(this@HistoryActivity).apply {
                            text = "Событий нет"; textSize = 14f
                        })
                    } else {
                        hoursBox.addView(Button(this@HistoryActivity).apply {
                            text = "\u29c9 Копировать весь день"
                            setOnClickListener { copyWholeDay(day, headerLine) }
                        })
                        counts.forEach { hc -> hoursBox.addView(makeHourRow(day.date, hc)) }
                    }
                }
            } else {
                hoursBox.visibility = View.GONE
                header.text = "$headerLine  \u25b8"
            }
        }
        col.addView(header)
        col.addView(hoursBox)
        return col
    }

    /** Строка ЧАСА: заголовок "HH:00 (N)" + ленивая загрузка событий часа. */
    private fun makeHourRow(date: String, hc: HourCount): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hourLabel = "%02d:00".format(hc.hour)
        val header = TextView(this).apply {
            text = "  $hourLabel  (${hc.cnt})  \u25b8"
            textSize = 15f
            setPadding(0, 14, 0, 14)
        }
        val evBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(20, 0, 0, 8)
        }
        header.setOnClickListener {
            if (evBox.visibility == View.GONE) {
                evBox.visibility = View.VISIBLE
                header.text = "  $hourLabel  (${hc.cnt})  \u25be"
                lifecycleScope.launch {
                    evBox.removeAllViews()
                    val (from, to) = hourRangeMs(date, hc.hour)
                    val events = AppDb.get(this@HistoryActivity).dao().eventsInRange(from, to)
                    events.reversed().forEach { e -> evBox.addView(makeEventRow(date, e)) }
                }
            } else {
                evBox.visibility = View.GONE
                header.text = "  $hourLabel  (${hc.cnt})  \u25b8"
            }
        }
        col.addView(header)
        col.addView(evBox)
        return col
    }

    /** Одна строка события: чекбокс выбора + текст (тап = копировать). */
    private fun makeEventRow(date: String, e: EventRecord): View {
        val shown = "${timeFmt.format(Date(e.timeMs))}  ${e.text}"
        val full = "$date $shown"
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(CheckBox(this).apply {
            isChecked = selectedLines.contains(full)
            setOnCheckedChangeListener { _, c ->
                if (c) selectedLines.add(full) else selectedLines.remove(full)
                updateSelLabel()
            }
        })
        row.addView(TextView(this).apply {
            text = shown
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(4, 12, 0, 12)
            setOnClickListener { copyLine(full) }
        })
        return row
    }

    /** Границы часа [from, to) в мс локального времени. */
    private fun hourRangeMs(date: String, hour: Int): Pair<Long, Long> {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH", Locale.getDefault())
        val from = sdf.parse("$date %02d".format(hour))?.time ?: 0L
        return from to (from + 3_600_000L)
    }

    private fun copyWholeDay(day: DayRecord, headerLine: String) {
        lifecycleScope.launch {
            val events = AppDb.get(this@HistoryActivity).dao().eventsOfDay(day.date)
            val text = "$headerLine\n" + events.joinToString("\n") { e ->
                "${timeFmt.format(Date(e.timeMs))}  ${e.text}"
            }
            copyLine(text)
        }
    }

    private fun updateSelLabel() {
        val n = selectedLines.size
        copySelBtn?.apply {
            text = "Копировать выбранные ($n)"
            visibility = if (n > 0) View.VISIBLE else View.GONE
        }
    }

    /** Тап по строке журнала кладёт её одну в буфер обмена. */
    private fun copyLine(line: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("StepCore", line))
        toast("Строка скопирована")
    }

    /** Копия видимого периода: дни + журнал событий каждого дня. */
    private fun copyVisible() {
        lifecycleScope.launch {
            val dao = AppDb.get(this@HistoryActivity).dao()
            val text = buildString {
                appendLine("StepCore — история")
                visibleDays.forEach { d ->
                    appendLine()
                    appendLine("${d.date}  ${d.walkSteps + d.runSteps} шагов (ходьба ${d.walkSteps}, бег ${d.runSteps})")
                    dao.eventsOfDay(d.date).forEach { e ->
                        appendLine("  ${timeFmt.format(Date(e.timeMs))}  ${e.text}")
                    }
                }
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("StepCore", text))
            toast("Скопировано с событиями")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
