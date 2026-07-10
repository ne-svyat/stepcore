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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Экранирование строк для JSON (V11.15). Тексты событий содержат что
     * угодно (диаг-строки со скобками, будущие пользовательские заметки) -
     * без экранирования одна кавычка ломает весь бэкап.
     */
    private fun jsonEsc(t: String) = t
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "")

    /**
     * Полный бэкап, schema 2 (V11.15). Прежний формат терял снапшоты дней
     * (kcal, дистанция, активное время) и ВСЮ почасовую таблицу:
     * восстановление из него пересчитало бы прошлое текущим профилем -
     * ровно тот класс багов, что закрыт в V9.9-V11.9, - и оставило бы
     * Timeline внутри дня пустым. Теперь уходит всё, из чего состоит
     * история. Поле schema позволит импорту различать форматы.
     */
    private val jsonSaver =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) lifecycleScope.launch {
                val dao = AppDb.get(this@HistoryActivity).dao()
                val days = dao.allDays()
                val hours = dao.allHours()
                val events = dao.allEvents()
                val json = buildString {
                    appendLine("{")
                    appendLine("\"schema\":2,")
                    appendLine("\"days\":[")
                    append(days.joinToString(",\n") {
                        "{\"date\":\"${it.date}\",\"walk\":${it.walkSteps},\"run\":${it.runSteps}," +
                        "\"kcalActive\":${it.kcalActive},\"kcalBasal\":${it.kcalBasal}," +
                        "\"distanceM\":${it.distanceM},\"activeSec\":${it.activeSec}}"
                    })
                    appendLine("],")
                    appendLine("\"hours\":[")
                    append(hours.joinToString(",\n") {
                        "{\"dateHour\":\"${it.dateHour}\",\"walk\":${it.walkSteps},\"run\":${it.runSteps}}"
                    })
                    appendLine("],")
                    appendLine("\"events\":[")
                    append(events.joinToString(",\n") {
                        "{\"timeMs\":${it.timeMs},\"date\":\"${it.date}\",\"text\":\"${jsonEsc(it.text)}\"}"
                    })
                    appendLine("]}")
                }
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                toast("JSON сохранён (полный бэкап)")
            }
        }

    private val jsonImporter =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) lifecycleScope.launch { importJson(uri) }
        }

    /**
     * Импорт бэкапа (V11.16). Правила безопасности:
     *   - существующие дни/часы НИКОГДА не перезаписываются - импорт только
     *     добавляет отсутствующее. Локальные данные всегда главнее файла;
     *   - события дедуплицируются по timeMs - повторный импорт того же файла
     *     не плодит копии;
     *   - schema 1 - прежний неполный формат: дни без снапшотов, часов нет
     *     совсем. Принимается, но с честным предупреждением в отчёте;
     *   - битый файл - отказ целиком, база не тронута.
     */
    private suspend fun importJson(uri: android.net.Uri) {
        toast("Импортирую...")
        val report = try {
            withContext(Dispatchers.IO) {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                    ?: return@withContext "Не удалось открыть файл"
                val root = org.json.JSONObject(text)
                val schema = root.optInt("schema", 1)
                val dao = AppDb.get(this@HistoryActivity).dao()
                val haveDates = dao.allDays().map { it.date }.toHashSet()
                val haveHours = dao.allHours().map { it.dateHour }.toHashSet()
                val haveTimes = dao.allEventTimes().toHashSet()
                var dA = 0; var dS = 0; var hA = 0; var hS = 0; var eA = 0; var eS = 0

                val days = root.optJSONArray("days") ?: org.json.JSONArray()
                for (i in 0 until days.length()) {
                    val o = days.getJSONObject(i)
                    val date = o.getString("date")
                    if (date in haveDates) { dS++; continue }
                    dao.insertDayIfAbsent(DayRecord(
                        date = date,
                        walkSteps = o.optInt("walk", 0),
                        runSteps = o.optInt("run", 0),
                        kcalActive = o.optInt("kcalActive", -1),
                        kcalBasal = o.optInt("kcalBasal", -1),
                        distanceM = o.optInt("distanceM", -1),
                        activeSec = o.optInt("activeSec", -1),
                    ))
                    dA++
                }
                val hours = root.optJSONArray("hours") ?: org.json.JSONArray()
                for (i in 0 until hours.length()) {
                    val o = hours.getJSONObject(i)
                    val k = o.getString("dateHour")
                    if (k in haveHours) { hS++; continue }
                    dao.insertHourIfAbsent(HourRecord(
                        dateHour = k,
                        walkSteps = o.optInt("walk", 0),
                        runSteps = o.optInt("run", 0),
                    ))
                    hA++
                }
                val events = root.optJSONArray("events") ?: org.json.JSONArray()
                for (i in 0 until events.length()) {
                    val o = events.getJSONObject(i)
                    val t = o.getLong("timeMs")
                    if (t in haveTimes) { eS++; continue }
                    dao.addEvent(EventRecord(
                        timeMs = t,
                        date = o.getString("date"),
                        text = o.getString("text"),
                    ))
                    eA++
                }
                buildString {
                    append("Дни: +$dA, дубликатов $dS\n")
                    append("Часы: +$hA, дубликатов $hS\n")
                    append("События: +$eA, дубликатов $eS")
                    if (schema < 2) append("\n\nВнимание: это старый неполный бэкап - " +
                        "без калорий, дистанции, активного времени и почасовых данных. " +
                        "Импортированные дни будут считаться по текущему профилю.")
                }
            }
        } catch (e: Exception) {
            "Импорт не удался: файл повреждён или не тот формат. База не изменена."
        }
        // V11.17: порядок и защита. Раньше диалог показывался ДО reload и
        // падал с BadTokenException, если окно Activity уже невалидно (MIUI
        // уводит его за системным пикером файлов, а импорт большого файла
        // долгий). Падение убивало процесс ПОСЛЕ успешной записи в базу:
        // данные были на месте, но экран не обновлялся - выглядело как
        // "импорт не сработал, появилось со второго захода".
        // Теперь: сначала обновить экран, потом диалог - и только если окно
        // живо; иначе тост (он не привязан к окну Activity).
        reload()
        if (!isFinishing && !isDestroyed) {
            try {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Импорт завершён")
                    .setMessage(report)
                    .setPositiveButton("Понятно", null)
                    .show()
            } catch (e: Exception) {
                toast("Импорт завершён. " + report.replace("\n", " · "))
            }
        } else {
            toast("Импорт завершён. " + report.replace("\n", " · "))
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
        findViewById<Button>(R.id.importJsonButton).setOnClickListener {
            jsonImporter.launch(arrayOf(
                "application/json", "application/octet-stream", "text/plain"))
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
            val container = findViewById<LinearLayout>(R.id.daysContainer)
            container.removeAllViews()
            val summary = findViewById<TextView>(R.id.summaryText)

            // 7д/30д - плоский список дней (частый сценарий: недавнее).
            // Год/Всё - иерархия МЕСЯЦЕВ (V9.6): масштаб на годы вперёд.
            if (currentFilterDays == Int.MAX_VALUE || currentFilterDays >= 365) {
                val months = dao.months()
                visibleDays = dao.allDays()
                val w = visibleDays.sumOf { it.walkSteps }
                val r = visibleDays.sumOf { it.runSteps }
                summary.text = "Месяцев: ${months.size}   " +
                        "Всего: ${w + r}   Ходьба: $w   Бег: $r"
                months.forEach { container.addView(makeMonthRow(it)) }
            } else {
                visibleDays = dao.recentDays(currentFilterDays)
                val w = visibleDays.sumOf { it.walkSteps }
                val r = visibleDays.sumOf { it.runSteps }
                summary.text = "Дней: ${visibleDays.size}   " +
                        "Всего: ${w + r}   Ходьба: $w   Бег: $r"
                visibleDays.forEach { day -> container.addView(makeDayRow(day)) }
            }
        }
    }

    /**
     * Верхний уровень МЕСЯЦА (V9.6): карточка с суммой, тап -> ленивая
     * загрузка дней месяца (переиспользует makeDayRow -> часы -> события).
     * Через год это ~12 карточек вместо 365 строк.
     */
    private fun makeMonthRow(m: MonthAgg): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
        }
        val total = m.walk + m.run
        val title = monthTitle(m.ym)
        val header = TextView(this).apply {
            text = "$title    ${fmtNum(total)} шагов · ${m.days} дн  \u25b8"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@HistoryActivity, R.color.text_main))
            setPadding(20, 22, 16, 22)
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                this@HistoryActivity, R.color.surface))
        }
        val daysBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 0, 0, 8)
        }
        header.setOnClickListener {
            if (daysBox.visibility == View.GONE) {
                daysBox.visibility = View.VISIBLE
                header.text = "$title    ${fmtNum(total)} шагов · ${m.days} дн  \u25be"
                lifecycleScope.launch {
                    daysBox.removeAllViews()
                    val days = AppDb.get(this@HistoryActivity).dao().daysOfMonth(m.ym)
                    days.forEach { daysBox.addView(makeDayRow(it)) }
                }
            } else {
                daysBox.visibility = View.GONE
                header.text = "$title    ${fmtNum(total)} шагов · ${m.days} дн  \u25b8"
            }
        }
        col.addView(header)
        col.addView(daysBox)
        return col
    }

    /** "2026-07" -> "Июль 2026". */
    private fun monthTitle(ym: String): String {
        val names = arrayOf("Январь","Февраль","Март","Апрель","Май","Июнь",
            "Июль","Август","Сентябрь","Октябрь","Ноябрь","Декабрь")
        return try {
            val y = ym.substring(0, 4)
            val mo = ym.substring(5, 7).toInt()
            "${names[mo - 1]} $y"
        } catch (e: Exception) { ym }
    }

    private fun fmtNum(n: Int) = "%,d".format(n).replace(',', ' ')

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
            setPadding(8, 20, 0, 20)
            setOnLongClickListener { copyLine(headerLine); true }
        }
        // V9.5: чекбокс выделяет ВЕСЬ день (все события), не раскрывая его.
        val dayCheck = CheckBox(this).apply {
            setOnCheckedChangeListener { _, c ->
                lifecycleScope.launch {
                    val evs = AppDb.get(this@HistoryActivity).dao().eventsOfDay(day.date)
                    evs.forEach { e ->
                        val full = "${day.date} ${timeFmt.format(Date(e.timeMs))}  ${e.text}"
                        if (c) selectedLines.add(full) else selectedLines.remove(full)
                    }
                    updateSelLabel()
                }
            }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(dayCheck)
            addView(header)
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
        col.addView(headerRow)
        col.addView(hoursBox)
        return col
    }

    /** Строка ЧАСА: заголовок "HH:00 (N)" + ленивая загрузка событий часа. */
    private fun makeHourRow(date: String, hc: HourCount): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hourLabel = "%02d:00".format(hc.hour)
        val (hFrom, hTo) = hourRangeMs(date, hc.hour)
        val header = TextView(this).apply {
            text = "  $hourLabel  (${hc.cnt})  \u25b8"
            textSize = 15f
            setPadding(4, 14, 0, 14)
        }
        // V9.5: чекбокс выделяет весь ЧАС (все его события) без раскрытия.
        val hourCheck = CheckBox(this).apply {
            setOnCheckedChangeListener { _, c ->
                lifecycleScope.launch {
                    val evs = AppDb.get(this@HistoryActivity).dao().eventsInRange(hFrom, hTo)
                    evs.forEach { e ->
                        val full = "$date ${timeFmt.format(Date(e.timeMs))}  ${e.text}"
                        if (c) selectedLines.add(full) else selectedLines.remove(full)
                    }
                    updateSelLabel()
                }
            }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hourCheck)
            addView(header)
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
        col.addView(headerRow)
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
