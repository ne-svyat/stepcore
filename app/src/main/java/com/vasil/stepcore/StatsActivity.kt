package com.vasil.stepcore

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Экран статистики: рекорды + heatmap активности за год.
 * Только чтение из БД, ядро детектора не затрагивается.
 */
class StatsActivity : AppCompatActivity() {

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val goal = 10000            // дневная цель (потом в профиль)
    private val activeMin = 1000        // порог "активного дня" для серии

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        val root = findViewById<LinearLayout>(R.id.statsRoot)

        lifecycleScope.launch {
            val days = AppDb.get(this@StatsActivity).dao().allDays()
            val byDate = days.associateBy { it.date }

            // ---- РЕКОРДЫ ----
            root.addView(sectionTitle("РЕКОРДЫ"))
            if (days.isEmpty()) {
                root.addView(dimText("Пока нет данных"))
            } else {
                val bestDay = days.maxByOrNull { it.walkSteps + it.runSteps }!!
                val bestRun = days.maxByOrNull { it.runSteps }!!
                val totalAll = days.sumOf { it.walkSteps + it.runSteps }
                root.addView(recordCard("Лучший день",
                    "${bestDay.walkSteps + bestDay.runSteps} шагов",
                    bestDay.date, R.color.accent_red))
                if (bestRun.runSteps > 0) root.addView(recordCard("Лучший бег",
                    "${bestRun.runSteps} шагов", bestRun.date, R.color.accent_blue))
                root.addView(recordCard("Серия активных дней",
                    "${currentStreak(byDate)} дней подряд",
                    "порог $activeMin шагов/день", R.color.accent_red))
                root.addView(recordCard("Всего пройдено",
                    "$totalAll шагов", "${days.size} дней с данными", R.color.accent_blue))
            }

            // ---- HEATMAP ----
            root.addView(sectionTitle("АКТИВНОСТЬ ЗА ГОД"))
            root.addView(buildHeatmap(byDate))
            root.addView(buildLegend())
        }
    }

    private fun currentStreak(byDate: Map<String, DayRecord>): Int {
        fun active(d: LocalDate): Boolean {
            val r = byDate[d.toString()] ?: return false
            return r.walkSteps + r.runSteps >= activeMin
        }
        var day = LocalDate.now()
        if (!active(day)) day = day.minusDays(1) // сегодня ещё может быть неактивен
        var streak = 0
        while (active(day)) { streak++; day = day.minusDays(1) }
        return streak
    }

    // ---------- heatmap ----------

    private fun levelColorRes(total: Int): Int = when {
        total <= 0 -> R.color.hm_empty
        total < goal * 0.3 -> R.color.hm1
        total < goal * 0.6 -> R.color.hm2
        total < goal * 0.9 -> R.color.hm3
        total <= goal * 1.2 -> R.color.hm4
        else -> R.color.hm5
    }

    private fun cell(colorRes: Int): View {
        val v = View(this)
        val size = dp(13)
        val lp = LinearLayout.LayoutParams(size, size)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        v.layoutParams = lp
        val bg = GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            setColor(ContextCompat.getColor(this@StatsActivity, colorRes))
        }
        v.background = bg
        return v
    }

    private fun buildHeatmap(byDate: Map<String, DayRecord>): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val weeks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val today = LocalDate.now()
        val mondayThisWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val start = mondayThisWeek.minusWeeks(51) // ~52 недели

        for (w in 0..51) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (dow in 0..6) {
                val date = start.plusDays((w * 7 + dow).toLong())
                if (date.isAfter(today)) {
                    col.addView(cell(R.color.hm_empty).apply { visibility = View.INVISIBLE })
                    continue
                }
                val rec = byDate[date.toString()]
                val total = if (rec == null) 0 else rec.walkSteps + rec.runSteps
                val c = cell(levelColorRes(total))
                c.setOnClickListener {
                    Toast.makeText(this@StatsActivity,
                        "${date}: $total шагов", Toast.LENGTH_SHORT).show()
                }
                col.addView(c)
            }
            weeks.addView(col)
        }
        scroll.addView(weeks)
        return scroll
    }

    private fun buildLegend(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        row.addView(dimText("меньше").apply {
            (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = dp(6)
        })
        intArrayOf(R.color.hm1, R.color.hm2, R.color.hm3, R.color.hm4, R.color.hm5)
            .forEach { row.addView(cell(it)) }
        row.addView(dimText("больше").apply {
            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6)
        })
        return row
    }

    // ---------- карточки ----------

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.accent_red))
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textSize = 15f
        letterSpacing = 0.08f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ); lp.topMargin = dp(24); lp.bottomMargin = dp(8); layoutParams = lp
    }

    private fun dimText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_dim))
        textSize = 13f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /** Карточка рекорда: красная боковая полоса + заголовок + крупное значение. */
    private fun recordCard(title: String, value: String, sub: String, accentRes: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@StatsActivity, R.drawable.card_asym)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ); lp.bottomMargin = dp(10); layoutParams = lp
        }
        val stripe = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(ContextCompat.getColor(this@StatsActivity, accentRes))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        col.addView(TextView(this).apply {
            text = title.uppercase()
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_dim))
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            textSize = 12f; letterSpacing = 0.05f
        })
        col.addView(TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_main))
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            textSize = 24f
        })
        col.addView(TextView(this).apply {
            text = sub
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_dim))
            textSize = 12f
        })
        card.addView(stripe)
        card.addView(col)
        return card
    }
}
