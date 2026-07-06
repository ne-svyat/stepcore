package com.vasil.stepcore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
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

    private fun makeDayRow(day: DayRecord): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val total = day.walkSteps + day.runSteps
        val headerLine = "${day.date}   $total шагов (ходьба ${day.walkSteps}, бег ${day.runSteps})"
        val header = TextView(this).apply {
            text = "$headerLine  ▸"
            textSize = 16f
            setPadding(0, 20, 0, 20)
            setOnLongClickListener { copyLine(headerLine); true }
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(24, 0, 0, 12)
        }
        header.setOnClickListener {
            if (details.visibility == View.GONE) {
                details.visibility = View.VISIBLE
                header.text = "$headerLine  ▾"
                lifecycleScope.launch {
                    details.removeAllViews()
                    val events = AppDb.get(this@HistoryActivity).dao().eventsOfDay(day.date)
                    if (events.isEmpty()) {
                        details.addView(TextView(this@HistoryActivity).apply {
                            text = "Событий нет"; textSize = 14f
                        })
                    } else {
                        val allLines = "$headerLine\n" + events.joinToString("\n") { e ->
                            "${timeFmt.format(Date(e.timeMs))}  ${e.text}"
                        }
                        details.addView(Button(this@HistoryActivity).apply {
                            text = "⧉ Копировать весь день (${events.size})"
                            setOnClickListener { copyLine(allLines) }
                        })
                    }
                    events.forEach { e ->
                        val line = "${timeFmt.format(Date(e.timeMs))}  ${e.text}"
                        details.addView(TextView(this@HistoryActivity).apply {
                            text = line
                            textSize = 14f
                            typeface = android.graphics.Typeface.MONOSPACE
                            setPadding(0, 6, 0, 6)
                            setOnClickListener { copyLine(line) }
                        })
                    }
                }
            } else {
                details.visibility = View.GONE
                header.text = "$headerLine  ▸"
            }
        }
        col.addView(header)
        col.addView(details)
        return col
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
