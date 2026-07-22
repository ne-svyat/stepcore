package com.vasil.stepcore

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Профиль дня: ломаная набора высоты + список интервалов.
 * Read-only витрина над уже посчитанными сессиями: ничего не пересчитывает.
 */
class DayProfileActivity : AppCompatActivity() {

    private var byTime = false
    private var dayShift = 0          // 0 = последний день с данными, 1 = предыдущий
    private var shown = 20            // сколько строк списка показано

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_profile)
        findViewById<TextView>(R.id.axisToggle).setOnClickListener {
            byTime = !byTime; shown = 20; load()
        }
        findViewById<TextView>(R.id.prevDay).setOnClickListener {
            dayShift++; shown = 20; load()
        }
        findViewById<TextView>(R.id.nextDay).setOnClickListener {
            if (dayShift > 0) { dayShift--; shown = 20; load() }
        }
        findViewById<TextView>(R.id.moreRows).setOnClickListener {
            shown += 20; load()
        }
        load()
    }

    private fun load() {
        val view = findViewById<DayProfileView>(R.id.profileView)
        val head = findViewById<TextView>(R.id.dayTitle)
        val listView = findViewById<TextView>(R.id.intervalList)
        val toggle = findViewById<TextView>(R.id.axisToggle)
        toggle.text = if (byTime) "ось: по времени" else "ось: по шагам"

        lifecycleScope.launch {
            val dao = AppDb.get(this@DayProfileActivity).dao()
            val all = dao.reliableSessions()          // свежие сверху
            if (all.isEmpty()) {
                head.text = "Данных пока нет"
                view.setData(emptyList(), byTime)
                listView.text = ""
                return@launch
            }
            // Группируем по календарным дням, берём нужный по сдвигу.
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val days = all.map { dayFmt.format(Date(it.startMs)) }.distinct()
            val idx = dayShift.coerceIn(0, days.size - 1)
            val day = days[idx]
            val ofDay = all.filter { dayFmt.format(Date(it.startMs)) == day }
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

            // Список интервалов порциями: не грузим всё разом.
            val tFmt = SimpleDateFormat("HH:mm", Locale("ru"))
            val take = ofDay.take(shown)
            listView.text = take.joinToString("\n") { s ->
                tFmt.format(Date(s.startMs)) + "–" + tFmt.format(Date(s.endMs)) +
                    "  " + labelRu(s.label).padEnd(12) +
                    "~" + (s.nSamples * 20) + " шаг., " +
                    (s.durationMs / 60000L) + " мин"
            }
            findViewById<TextView>(R.id.moreRows).text =
                if (ofDay.size > shown) "показать ещё (" + (ofDay.size - shown) + ")" else ""
        }
    }

    private fun labelRu(l: String) = when (l) {
        "UP" -> "в гору"; "DOWN" -> "с горы"
        "NONE" -> "не отмечено"; else -> "ровно"
    }
}
