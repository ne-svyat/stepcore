package com.vasil.stepcore

import android.graphics.Color
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
 * Read-only витрина над уже посчитанными сессиями: ничего не пересчитывает.
 * Цвет плиты интервала совпадает с цветом его линии на графике - строка и
 * отрезок связаны глазом, без подписей.
 */
class DayProfileActivity : AppCompatActivity() {

    private var byTime = false
    private var dayShift = 0
    private var shown = 20
    private var ofDay: List<SessionRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_profile)
        val d = resources.displayMetrics.density
        // Плита под графиком: каменный язык проекта, график отделён от текста.
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
            byTime = !byTime; load()
        }
        findViewById<TextView>(R.id.prevDay).setOnClickListener {
            dayShift++; shown = 20; load()
        }
        findViewById<TextView>(R.id.nextDay).setOnClickListener {
            if (dayShift > 0) { dayShift--; shown = 20; load() }
        }
        findViewById<TextView>(R.id.moreRows).setOnClickListener {
            shown += 20; renderList()
        }
        load()
    }

    private fun load() {
        val view = findViewById<DayProfileView>(R.id.profileView)
        val head = findViewById<TextView>(R.id.dayTitle)
        findViewById<TextView>(R.id.axisToggle).text = if (byTime) "по времени" else "по шагам"

        lifecycleScope.launch {
            val all = AppDb.get(this@DayProfileActivity).dao().reliableSessions()
            if (all.isEmpty()) {
                head.text = "Данных пока нет"
                view.setData(emptyList(), byTime)
                findViewById<LinearLayout>(R.id.intervalList).removeAllViews()
                return@launch
            }
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val days = all.map { dayFmt.format(Date(it.startMs)) }.distinct()
            val idx = dayShift.coerceIn(0, days.size - 1)
            dayShift = idx
            val day = days[idx]
            ofDay = all.filter { dayFmt.format(Date(it.startMs)) == day }
                .sortedBy { it.startMs }

            val titleFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("ru"))
            val steps = ofDay.sumOf { it.nSamples * 20 }
            head.text = titleFmt.format(Date(ofDay.first().startMs)) +
                "\n" + ofDay.size + " отрезков, ~" + steps + " шагов"

            view.setData(
                ofDay.map {
                    DayProfileView.Seg(it.label, it.nSamples * 20, it.durationMs, it.startMs)
                },
                byTime
            )
            renderList()
        }
    }

    private fun renderList() {
        val box = findViewById<LinearLayout>(R.id.intervalList)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val tFmt = SimpleDateFormat("HH:mm", Locale("ru"))
        val take = ofDay.take(shown)
        for ((i, s) in take.withIndex()) {
            val color = DayProfileView.colorFor(s.label)
            val tv = TextView(this)
            tv.text = tFmt.format(Date(s.startMs)) + " – " + tFmt.format(Date(s.endMs)) +
                "   " + labelRu(s.label) + "\n~" + (s.nSamples * 20) + " шагов · " +
                (s.durationMs / 60000L) + " мин · " + confirmRu(s.confirmState)
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (6 * d).toInt()
            tv.layoutParams = lp
            tv.setOnClickListener { selectRow(i) }
            box.addView(tv)
        }
        findViewById<TextView>(R.id.moreRows).text =
            if (ofDay.size > shown) "показать ещё (" + (ofDay.size - shown) + ")" else ""
    }

    private fun selectRow(i: Int) {
        findViewById<DayProfileView>(R.id.profileView).select(i)
        val s = ofDay[i]
        val f = SimpleDateFormat("EEE, d MMMM yyyy, HH:mm", Locale("ru"))
        val t2 = SimpleDateFormat("HH:mm", Locale("ru"))
        val carry = when {
            s.chipShare >= 0.6f -> "в кармане"
            s.chipShare <= 0.4f -> "в руке"
            else -> "то в руке, то в кармане"
        }
        val det = findViewById<TextView>(R.id.detailText)
        det.setTextColor(DayProfileView.colorFor(s.label))
        det.text = f.format(Date(s.startMs)) + "–" + t2.format(Date(s.endMs)) +
            "\n" + labelRu(s.label) + " · ~" + (s.nSamples * 20) + " шагов · " +
            (s.durationMs / 60000L) + " мин · телефон " + carry +
            "\nОтвет: " + confirmRu(s.confirmState)
    }

    private fun labelRu(l: String) = when (l) {
        "UP" -> "в гору"; "DOWN" -> "с горы"
        "NONE" -> "не отмечено"; else -> "ровно"
    }

    /** Состояние из трёх архивов - видно, какие метки уже подтверждены. */
    private fun confirmRu(c: Int) = when (c) {
        1 -> "подтверждено"
        2 -> "дефект"
        3 -> "не подтверждено"
        else -> "не спрошено"
    }
}
