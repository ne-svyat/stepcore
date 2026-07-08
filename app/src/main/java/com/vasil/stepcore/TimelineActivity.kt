package com.vasil.stepcore

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalDate

class TimelineActivity : AppCompatActivity() {

    private enum class Scale(val label: String) {
        TODAY("Сегодня"), YESTERDAY("Вчера"), D7("7 дней"),
        D30("30 дней"), YEAR("Год"), ALL("Всё")
    }

    private lateinit var timeline: TimelineView
    private lateinit var summary: TextView
    private lateinit var hint: TextView
    private lateinit var detail: TextView
    private lateinit var axisMax: TextView
    private lateinit var axisMid: TextView
    private var current = Scale.TODAY
    private var lastSegs: List<TimelineView.Seg> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        timeline = findViewById(R.id.timeline)
        summary = findViewById(R.id.timelineSummary)
        hint = findViewById(R.id.timelineHint)
        detail = findViewById(R.id.timelineDetail)
        axisMax = findViewById(R.id.axisMax)
        axisMid = findViewById(R.id.axisMid)

        timeline.onBarTap = { idx -> showDetail(idx) }
        // Тап по любой карточке - объяснение активных/покоя (V9.17).
        summary.setOnClickListener { showKcalInfo() }
        detail.setOnClickListener { showKcalInfo() }

        val row = findViewById<LinearLayout>(R.id.scaleRow)
        Scale.values().forEach { sc -> row.addView(scaleChip(sc)) }
        render()
    }

    /** Детали ОДНОГО столбца по тапу (не за период - см. summary). */
    private fun showDetail(idx: Int) {
        val seg = lastSegs.getOrNull(idx) ?: return
        val total = seg.walk + seg.run
        if (total == 0) {
            detail.text = "ВЫБРАННЫЙ СТОЛБЕЦ · ${seg.label}\nНет активности"
            return
        }
        val km = Stats.distanceKm(this, seg.walk, seg.run)
        val active = Stats.kcalActive(this, seg.walk, seg.run)
        // Покой по прожитым дням, которые покрывает столбец (час=1/24,
        // день=1, сегодня=доля, неделя/месяц=сумма активных дней).
        val basal = (Stats.kcalBasalFullDay(this) * seg.activeDays).toInt()
        val activeSec = Stats.activeSeconds(this, seg.walk, seg.run)
        detail.text = ("ВЫБРАННЫЙ СТОЛБЕЦ · ${seg.label}\n" +
                "Шаги: ${fmtNum(total)}  (ходьба ${fmtNum(seg.walk)}, бег ${fmtNum(seg.run)})\n" +
                "Дистанция: %.2f км\n".format(km) +
                "Активное время: ${fmtDuration(activeSec)}\n" +
                "Активные: $active ккал · Покой: $basal ккал · Всего: ${active + basal} ккал")
    }

    private fun scaleTitle(s: Scale) = when (s) {
        Scale.TODAY -> "сегодня"
        Scale.YESTERDAY -> "вчера"
        Scale.D7 -> "7 дней"
        Scale.D30 -> "30 дней"
        Scale.YEAR -> "год"
        Scale.ALL -> "всё время"
    }

    /** Объяснение покоя/активных - по тапу на любую карточку (V9.17). */
    private fun showKcalInfo() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Активные и покой")
            .setMessage(
                "АКТИВНЫЕ — калории, сожжённые именно на движение (сверх покоя).\n\n" +
                "ПОКОЙ — базовый расход организма: сердце, дыхание, тепло. " +
                "Тратится всегда, даже когда ты сидишь или спишь.\n\n" +
                "ВСЕГО = активные + покой. Это полный расход, по нему считают дефицит.\n\n" +
                "Покой начисляется только за прожитое время, пока StepCore вёл учёт: " +
                "за сегодня — по прошедшей части суток, за прошлые дни с данными — целиком. " +
                "За будущие и пустые дни покой не считается."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun fmtNum(n: Int) = "%,d".format(n).replace(',', ' ')

    private fun fmtDuration(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60
        return when {
            h > 0 -> "$h ч $m мин"
            m > 0 -> "$m мин"
            else -> "$sec сек"
        }
    }

    private val chips = HashMap<Scale, TextView>()

    private fun scaleChip(sc: Scale): TextView = TextView(this).apply {
        text = sc.label
        textSize = 14f
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ); lp.rightMargin = dp(8); layoutParams = lp
        setOnClickListener { current = sc; render() }
        chips[sc] = this
    }

    private fun paintChips() {
        chips.forEach { (sc, tv) ->
            val active = sc == current
            tv.setTextColor(ContextCompat.getColor(this,
                if (active) R.color.text_main else R.color.text_dim))
            tv.setBackgroundColor(ContextCompat.getColor(this,
                if (active) R.color.surface2 else android.R.color.transparent))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        paintChips()
        lifecycleScope.launch {
            val dao = AppDb.get(this@TimelineActivity).dao()
            val segs: List<TimelineView.Seg>
            var labelEvery = 1
            var walkSum = 0; var runSum = 0
            // Дни, за которые начисляем ПОКОЙ (BMR). Не "активные" дни:
            // организм жжёт базовый расход и в покое. Считаем прожитое
            // время, пока StepCore вёл учёт: сегодня = доля прошедших
            // суток (как на главном экране), прошлые дни с данными = 1,
            // пустые и будущие = 0 (V9.17).
            var basalDays = 0f
            val todayFraction = java.time.LocalTime.now().toSecondOfDay() / 86400f

            when (current) {
                Scale.TODAY, Scale.YESTERDAY -> {
                    val day = if (current == Scale.TODAY) LocalDate.now()
                              else LocalDate.now().minusDays(1)
                    val hours = dao.hoursOfDay(day.toString()).associateBy {
                        it.dateHour.takeLast(2).toInt()
                    }
                    segs = (0..23).map { h ->
                        val r = hours[h]
                        val w = r?.walkSteps ?: 0; val rn = r?.runSteps ?: 0
                        walkSum += w; runSum += rn
                        val act = if (w + rn > 0) 1f / 24f else 0f
                        TimelineView.Seg(w, rn, "%02d".format(h), act)
                    }
                    basalDays = if (current == Scale.TODAY) todayFraction else 1f
                    labelEvery = 3
                    val rec = dao.day(day.toString())
                    val dayTotal = (rec?.walkSteps ?: 0) + (rec?.runSteps ?: 0)
                    hint.text = if (dayTotal > walkSum + runSum)
                        "За день всего $dayTotal шагов · почасовая детализация есть не за весь день"
                    else ""
                }
                Scale.D7, Scale.D30 -> {
                    val n = if (current == Scale.D7) 7 else 30
                    val days = dao.recentDays(n).associateBy { it.date }
                    val today = LocalDate.now()
                    segs = (n - 1 downTo 0).map { back ->
                        val date = today.minusDays(back.toLong())
                        val r = days[date.toString()]
                        val w = r?.walkSteps ?: 0; val rn = r?.runSteps ?: 0
                        walkSum += w; runSum += rn
                        // Сегодня - доля прошедших суток (день ещё не прожит целиком)
                        val act = if (w + rn > 0) (if (date == today) todayFraction else 1f) else 0f
                        TimelineView.Seg(w, rn, "%d.%d".format(date.dayOfMonth, date.monthValue), act)
                    }
                    val todayStr = today.toString()
                    basalDays = days.values.filter { it.walkSteps + it.runSteps > 0 }
                        .sumOf { if (it.date == todayStr) todayFraction.toDouble() else 1.0 }
                        .toFloat()
                    labelEvery = if (n == 7) 1 else 4
                    hint.text = ""
                }
                Scale.YEAR -> {
                    val all = dao.allDays().associateBy { it.date }
                    val today = LocalDate.now()
                    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                    val start = monday.minusWeeks(51)
                    segs = (0..51).map { wk ->
                        var w = 0; var rn = 0; var actDaysF = 0f
                        for (dow in 0..6) {
                            val date = start.plusDays((wk * 7 + dow).toLong())
                            if (date.isAfter(today)) continue
                            all[date.toString()]?.let {
                                w += it.walkSteps; rn += it.runSteps
                                if (it.walkSteps + it.runSteps > 0)
                                    actDaysF += if (date == today) todayFraction else 1f
                            }
                        }
                        walkSum += w; runSum += rn
                        val wkStart = start.plusWeeks(wk.toLong())
                        TimelineView.Seg(w, rn, "%d.%d".format(wkStart.dayOfMonth, wkStart.monthValue), actDaysF)
                    }
                    val todayStrY = today.toString()
                    basalDays = all.values.filter { it.walkSteps + it.runSteps > 0 }
                        .sumOf { if (it.date == todayStrY) todayFraction.toDouble() else 1.0 }
                        .toFloat()
                    labelEvery = 4
                    hint.text = "Каждый столбец — неделя."
                }
                Scale.ALL -> {
                    val all = dao.allDays()
                    if (all.isEmpty()) { segs = emptyList() }
                    else {
                        val byMonth = LinkedHashMap<String, IntArray>()
                        val monthDays = LinkedHashMap<String, Float>()
                        val todayS = LocalDate.now().toString()
                        all.sortedBy { it.date }.forEach { d ->
                            val ym = d.date.substring(0, 7)
                            val acc = byMonth.getOrPut(ym) { IntArray(2) }
                            acc[0] += d.walkSteps; acc[1] += d.runSteps
                            if (d.walkSteps + d.runSteps > 0) {
                                val add = if (d.date == todayS) todayFraction else 1f
                                monthDays[ym] = (monthDays[ym] ?: 0f) + add
                            }
                        }
                        segs = byMonth.map { (ym, acc) ->
                            walkSum += acc[0]; runSum += acc[1]
                            TimelineView.Seg(acc[0], acc[1],
                                ym.substring(5) + "." + ym.substring(2, 4), monthDays[ym] ?: 0f)
                        }
                    }
                    val todayStrA = LocalDate.now().toString()
                    basalDays = all.filter { it.walkSteps + it.runSteps > 0 }
                        .sumOf { if (it.date == todayStrA) todayFraction.toDouble() else 1.0 }
                        .toFloat()
                    labelEvery = 1
                    hint.text = "Каждый столбец — месяц."
                }
            }

            timeline.setSegments(segs, labelEvery)
            lastSegs = segs
            val maxV = timeline.maxSegTotal
            axisMax.text = if (maxV >= 1000) "%.1fk".format(maxV / 1000f) else maxV.toString()
            axisMid.text = if (maxV >= 2000) "%.1fk".format(maxV / 2000f)
                else (maxV / 2).toString()
            detail.text = "Нажми на столбец для деталей"
            val total = walkSum + runSum
            if (total == 0) {
                summary.text = "Нет данных за период"
            } else {
                val active = Stats.kcalActive(this@TimelineActivity, walkSum, runSum)
                // basalDays посчитан по масштабу выше: прожитое время с
                // данными, а не активное и не календарные слоты вперёд.
                val basal = (Stats.kcalBasalFullDay(this@TimelineActivity) * basalDays).toInt()
                val activeSec = Stats.activeSeconds(this@TimelineActivity, walkSum, runSum)
                val km = Stats.distanceKm(this@TimelineActivity, walkSum, runSum)
                summary.text = ("ЗА ВЕСЬ ПЕРИОД · ${scaleTitle(current)}\n" +
                        "Шаги: ${fmtNum(total)}  (ходьба ${fmtNum(walkSum)}, бег ${fmtNum(runSum)})\n" +
                        "Дистанция: %.2f км   ·   Активное время: ${fmtDuration(activeSec)}\n" +
                        "Активные: $active ккал · Покой: $basal ккал · Всего: ${active + basal} ккал")
                        .format(km)
            }
        }
    }
}
