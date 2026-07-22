package com.vasil.stepcore

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
        val transport: Boolean
    )

    private var byTime = false
    private var dayShift = 0
    private var shown = 20
    private var items: List<Item> = emptyList()

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
        findViewById<TextView>(R.id.axisToggle).setOnClickListener { byTime = !byTime; load() }
        findViewById<TextView>(R.id.prevDay).setOnClickListener { dayShift++; shown = 20; load() }
        findViewById<TextView>(R.id.nextDay).setOnClickListener {
            if (dayShift > 0) { dayShift--; shown = 20; load() }
        }
        findViewById<TextView>(R.id.moreRows).setOnClickListener { shown += 20; renderList() }
        load()
    }

    private fun load() {
        val view = findViewById<DayProfileView>(R.id.profileView)
        val head = findViewById<TextView>(R.id.dayTitle)
        findViewById<TextView>(R.id.axisToggle).text = if (byTime) "по времени" else "по шагам"

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
            val ofDay = all.filter { dayFmt.format(Date(it.startMs)) == day }
                .sortedBy { it.startMs }

            val walk = ofDay.map {
                Item(it.startMs, it.endMs, it.label, it.nSamples * 20, it.durationMs,
                    it.confirmState, it.chipShare, false)
            }
            val rides = transportSpans(dao, ofDay.first().startMs, ofDay.last().endMs)
            items = (walk + rides).sortedBy { it.startMs }

            val titleFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru"))
            val steps = walk.sumOf { it.steps }
            head.text = titleFmt.format(Date(ofDay.first().startMs)) + "\n" +
                walk.size + " отрезков, ~" + steps + " шагов" +
                (if (rides.isEmpty()) "" else ", поездок " + rides.size)

            view.setData(
                items.map { DayProfileView.Seg(it.label, it.steps, it.durationMs, it.startMs) },
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

    private fun renderList() {
        val box = findViewById<LinearLayout>(R.id.intervalList)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val tFmt = SimpleDateFormat("HH:mm", Locale("ru"))
        for ((i, s) in items.take(shown).withIndex()) {
            val color = DayProfileView.colorFor(s.label)
            val tv = TextView(this)
            tv.text = tFmt.format(Date(s.startMs)) + " – " + tFmt.format(Date(s.endMs)) +
                "   " + labelRu(s.label) + "\n" +
                (if (s.transport) "поездка · " + (s.durationMs / 60000L) + " мин"
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
                when (s.label) {
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
        val s = items[i]
        val f = SimpleDateFormat("EEE, d MMMM yyyy, HH:mm", Locale("ru"))
        val t2 = SimpleDateFormat("HH:mm", Locale("ru"))
        val det = findViewById<TextView>(R.id.detailText)
        det.setTextColor(DayProfileView.colorFor(s.label))
        det.text = f.format(Date(s.startMs)) + "–" + t2.format(Date(s.endMs)) + "\n" +
            (if (s.transport)
                "поездка · " + (s.durationMs / 60000L) + " мин\n" +
                    "Шаги в транспорте не считаются как ходьба, и уклон здесь не учится."
             else {
                val carry = when {
                    s.chipShare >= 0.6f -> "в кармане"
                    s.chipShare <= 0.4f -> "в руке"
                    else -> "то в руке, то в кармане"
                }
                labelRu(s.label) + " · ~" + s.steps + " шагов · " +
                    (s.durationMs / 60000L) + " мин · телефон " + carry +
                    "\nОтвет: " + confirmRu(s.confirmState)
            })
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
