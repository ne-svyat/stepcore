package com.vasil.stepcore

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

        val row = findViewById<LinearLayout>(R.id.scaleRow)
        Scale.values().forEach { sc ->
            row.addView(scaleChip(sc))
        }
        render()
    }

    /** Детали столбца по тапу: период, шаги, дистанция, калории (V9.8). */
    private fun showDetail(idx: Int) {
        val seg = lastSegs.getOrNull(idx) ?: return
        val total = seg.walk + seg.run
        if (total == 0) { detail.text = "${seg.label}: нет активности"; return }
        val km = Stats.distanceKm(this, seg.walk, seg.run)
        val active = Stats.kcalActive(this, seg.walk, seg.run)
        val basalHour = Stats.kcalBasalFullDay(this) / 24
        detail.text = ("\u25b8 ${seg.label}:  $total шагов " +
                "(ходьба ${seg.walk}, бег ${seg.run})\n" +
                "%.2f км  \u00b7  $active актив + $basalHour покой = ${active + basalHour} ккал").format(km)
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
                        TimelineView.Seg(w, rn, "%02d".format(h))
                    }
                    labelEvery = 3
                    // Если дневная сумма больше почасовой - детализация
                    // покрывает не весь день (почасовка ведётся с V8.8).
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
                        TimelineView.Seg(w, rn, "%d.%d".format(date.dayOfMonth, date.monthValue))
                    }
                    labelEvery = if (n == 7) 1 else 4
                    hint.text = ""
                }
                Scale.YEAR -> {
                    val all = dao.allDays().associateBy { it.date }
                    val today = LocalDate.now()
                    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                    val start = monday.minusWeeks(51)
                    segs = (0..51).map { wk ->
                        var w = 0; var rn = 0
                        for (dow in 0..6) {
                            val date = start.plusDays((wk * 7 + dow).toLong())
                            if (date.isAfter(today)) continue
                            all[date.toString()]?.let { w += it.walkSteps; rn += it.runSteps }
                        }
                        walkSum += w; runSum += rn
                        val wkStart = start.plusWeeks(wk.toLong())
                        TimelineView.Seg(w, rn, "%d.%d".format(wkStart.dayOfMonth, wkStart.monthValue))
                    }
                    labelEvery = 4
                    hint.text = "Каждый столбец — неделя."
                }
                Scale.ALL -> {
                    val all = dao.allDays()
                    if (all.isEmpty()) { segs = emptyList() }
                    else {
                        val byMonth = LinkedHashMap<String, IntArray>()
                        all.sortedBy { it.date }.forEach { d ->
                            val ym = d.date.substring(0, 7) // yyyy-MM
                            val acc = byMonth.getOrPut(ym) { IntArray(2) }
                            acc[0] += d.walkSteps; acc[1] += d.runSteps
                        }
                        segs = byMonth.map { (ym, acc) ->
                            walkSum += acc[0]; runSum += acc[1]
                            TimelineView.Seg(acc[0], acc[1], ym.substring(5) + "." + ym.substring(2, 4))
                        }
                    }
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
            summary.text = if (total == 0) "Нет данных за период"
                else "Всего $total шагов · ходьба $walkSum · бег $runSum"
        }
    }
}
