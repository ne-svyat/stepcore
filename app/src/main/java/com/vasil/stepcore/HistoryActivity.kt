package com.vasil.stepcore

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        val daysView = findViewById<TextView>(R.id.daysText)
        val eventsView = findViewById<TextView>(R.id.eventsText)

        lifecycleScope.launch {
            val dao = AppDb.get(this@HistoryActivity).dao()

            val days = dao.recentDays(60)
            daysView.text = if (days.isEmpty()) "Пока нет данных" else
                days.joinToString("\n") { d ->
                    val total = d.walkSteps + d.runSteps
                    "${d.date}   $total шагов (ходьба ${d.walkSteps}, бег ${d.runSteps})"
                }

            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val events = dao.eventsOfDay(LocalDate.now().toString())
            eventsView.text = if (events.isEmpty()) "Событий сегодня нет" else
                events.joinToString("\n") { e -> "${fmt.format(Date(e.timeMs))}  ${e.text}" }
        }
    }
}
