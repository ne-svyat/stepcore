package com.vasil.stepcore

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
    // Пороги heatmap - АБСОЛЮТНЫЕ шаги за день (V11.3).
    //
    // Раньше цвет считался от дневной цели: смена цели перекрашивала весь год
    // задним числом. Статистика обязана опираться на измерение, а не на
    // настройку, иначе прошлое переписывается - тот же класс бага, что чинили
    // в V11.0-11.2.
    //
    // Почему только шаги: км = шаги * длина шага, активное время = шаги *
    // интервал. Это не независимые величины, а шаги, умноженные на константы
    // калибровки. Композит из них не добавил бы информации, зато heatmap
    // перекрашивался бы при каждой перекалибровке.
    //
    // Откуда числа. 10 000 - маркетинг японских шагомеров 1960-х, не медицина.
    // 7 000 - порог, на котором доказательная база фиксирует набранную пользу
    // для здоровья. ~12 500 - начало категории "высокая активность" в работах
    // по смертности, дальше кривая выполаживается. Верхние две ступени
    // подобраны под реальный уровень владельца: обычный день ~20к.
    private val HM_T1 = 7000    // ниже - красный
    private val HM_T2 = 12000   // оранжевый: доказанный достаточный уровень
    private val HM_T3 = 20000   // жёлтый: высокая активность
    private val HM_T4 = 30000   // зелёный: сильный день; выше - ярко-зелёный
    private val activeMin = 1000        // порог "активного дня" для серии

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        // V14.3: дудл-стиль (карточки статистики идут из UiKit — они уже в рамках).
        findViewById<DoodleSceneView>(R.id.doodleHeader).setScene(DoodleSceneView.STATS)
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

            // ---- ГОД В ЦИФРАХ ----
            val year = LocalDate.now().year.toString()
            val yearDays = days.filter { it.date.startsWith(year) }
            if (yearDays.isNotEmpty()) {
                root.addView(sectionTitle("$year В ЦИФРАХ"))
                val w = yearDays.sumOf { it.walkSteps }
                val r = yearDays.sumOf { it.runSteps }
                val total = w + r
                val km = Stats.distanceKm(this@StatsActivity, w, r)
                // оценка часов движения: ~100 шагов/мин
                val hours = total / 100.0 / 60.0
                val avg = total / yearDays.size
                // лучший месяц
                val byMonth = HashMap<String, Int>()
                yearDays.forEach {
                    val m = it.date.substring(0, 7)
                    byMonth[m] = (byMonth[m] ?: 0) + it.walkSteps + it.runSteps
                }
                val best = byMonth.maxByOrNull { it.value }

                root.addView(UiKit.statCard(this@StatsActivity, "Шагов за год",
                    "$total", "ходьба $w · бег $r", R.color.accent_red))
                if (km > 0) root.addView(UiKit.statCard(this@StatsActivity, "Пройдено",
                    "${"%.1f".format(km)} км", "оценка", R.color.accent_blue))
                root.addView(UiKit.statCard(this@StatsActivity, "В движении",
                    "${"%.0f".format(hours)} ч", "оценка по шагам", R.color.accent_blue))
                if (best != null) root.addView(UiKit.statCard(this@StatsActivity, "Самый активный месяц",
                    monthName(best.key), "${best.value} шагов", R.color.accent_red))
                root.addView(UiKit.statCard(this@StatsActivity, "Среднее в день",
                    "$avg шагов", "${yearDays.size} дней с данными", R.color.accent_blue))
            }
        }
    }

    private fun monthName(ym: String): String {
        val names = listOf("январь","февраль","март","апрель","май","июнь",
            "июль","август","сентябрь","октябрь","ноябрь","декабрь")
        val m = ym.substring(5).toIntOrNull() ?: return ym
        return names.getOrElse(m - 1) { ym } + " " + ym.substring(0, 4)
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

        val today = LocalDate.now()
        val mondayThisWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val start = mondayThisWeek.minusWeeks(51) // ~52 недели

        // Данные готовим здесь, рисует их ОДИН холст: 365 отдельных View
        // невозможно анимировать, а один холст стоит как один виджет.
        val list = ArrayList<HeatmapView.Day>(52 * 7)
        for (w in 0..51) {
            for (dow in 0..6) {
                val date = start.plusDays((w * 7 + dow).toLong())
                if (date.isAfter(today)) {
                    list.add(HeatmapView.Day(date, 0, 0))
                    continue
                }
                val rec = byDate[date.toString()]
                val total = if (rec == null) 0 else rec.walkSteps + rec.runSteps
                list.add(HeatmapView.Day(date, levelOf(total), total))
            }
        }

        val map = HeatmapView(this)
        map.setData(list, 52) { day ->
            val msg = if (day.steps == 0) "${day.date}: нет данных"
                      else "${day.date}: ${day.steps} шагов · ${levelWord(day.level)}"
            Toast.makeText(this@StatsActivity, msg, Toast.LENGTH_SHORT).show()
        }
        scroll.addView(map)
        // Текущая неделя - крайняя правая колонка; без прокрутки экран
        // открывался на пустом прошлом годе и heatmap казался пустым.
        scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) }
        return scroll
    }

    /** Уровень дня: 0 нет данных, 1 слабый ... 5 сильный. */
    private fun levelOf(total: Int): Int = when {
        total <= 0 -> 0
        total < HM_T1 -> 1
        total < HM_T2 -> 2
        total < HM_T3 -> 3
        total < HM_T4 -> 4
        else -> 5
    }

    private fun levelWord(level: Int): String = when (level) {
        1 -> "мало"
        2 -> "норма"
        3 -> "хорошо"
        4 -> "отлично"
        5 -> "мощный день"
        else -> "нет данных"
    }

    private fun buildLegend(): View {
        // Старая легенда ставила числа МЕЖДУ квадратами и заканчивалась голым
        // "+" - понять, какой квадрат что значит, было невозможно. Теперь у
        // каждого уровня своя строка: квадрат, диапазон, слово.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val rows = listOf(
            Triple(R.color.hm1, "до ${fmtK(HM_T1)}", "мало"),
            Triple(R.color.hm2, "${fmtK(HM_T1)}–${fmtK(HM_T2)}", "норма"),
            Triple(R.color.hm3, "${fmtK(HM_T2)}–${fmtK(HM_T3)}", "хорошо"),
            Triple(R.color.hm4, "${fmtK(HM_T3)}–${fmtK(HM_T4)}", "отлично"),
            Triple(R.color.hm5, "${fmtK(HM_T4)} и больше", "мощный день"),
        )
        for ((colorRes, range, word) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(3) }
            }
            row.addView(cell(colorRes))
            row.addView(TextView(this).apply {
                text = range
                setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_main))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(dp(110),
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) }
            })
            row.addView(TextView(this).apply {
                text = word
                setTextColor(ContextCompat.getColor(this@StatsActivity, colorRes))
                textSize = 15f
            })
            box.addView(row)
        }
        box.addView(dimText("Цвет — по шагам за день. Пороги постоянные, от дневной цели не зависят.")
            .apply { textSize = 13f })
        box.addView(dimText("Мощные дни светятся, слабые подрагивают. Нажми на день — покажу цифры.")
            .apply { textSize = 13f })
        return box
    }

    /** Число на границе цветов:    /** Число на границе цветов: 7000 -> "7к". */
    private fun fmtK(n: Int): String =
        if (n % 1000 == 0) "${n / 1000}к" else "%.1fк".format(n / 1000f)

    private fun boundLabel(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_dim))
        textSize = 11f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(2); rightMargin = dp(2) }
    }

    // ---------- карточки ----------

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.accent_red))
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
            textSize = 12f; letterSpacing = 0.05f
        })
        col.addView(TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_main))
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

    // Механизм дудл-анимации крутится, пока виден хоть один экран.
    // onStart нового экрана срабатывает РАНЬШЕ onStop старого, поэтому при
    // переходе между вкладками счётчик не касается нуля и анимация не глохнет.
    override fun onStart() {
        super.onStart()
        BoilClock.screenStarted()
    }

    override fun onStop() {
        BoilClock.screenStopped()
        super.onStop()
    }
}
