package com.vasil.stepcore

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
        UiKit.screenTitle(this, UiKit.ACCENT_TIME)
        // V14.3: дудл-стиль.
        findViewById<DoodleSceneView>(R.id.doodleHeader).setScene(DoodleSceneView.TIMELINE)
        DoodleUi.frame(findViewById(R.id.timelineSummary), R.color.accent_blue, R.color.surface, 301L, DoodleBorderDrawable.MAT_ICE)
        DoodleUi.frame(findViewById(R.id.chartCard), R.color.accent_violet, R.color.surface, 302L, DoodleBorderDrawable.MAT_ICE)
        timeline = findViewById(R.id.timeline)
        summary = findViewById(R.id.timelineSummary)
        hint = findViewById(R.id.timelineHint)
        detail = findViewById(R.id.timelineDetail)
        axisMax = findViewById(R.id.axisMax)
        axisMid = findViewById(R.id.axisMid)

        timeline.onBarTap = { idx -> showDetail(idx) }
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
        // Закрытые дни - из СНАПШОТА (заморожены). Часы текущего дня и
        // дни без снапшота - на лету (V9.19).
        val km = if (seg.distM >= 0) seg.distM / 1000f
                 else Stats.distanceKm(this, seg.walk, seg.run)
        val active = if (seg.kcalA >= 0) seg.kcalA
                     else Stats.kcalActive(this, seg.walk, seg.run)
        val basal = if (seg.kcalB >= 0) seg.kcalB
                    else (Stats.kcalBasalFullDay(this) * seg.activeDays).toInt()
        val activeSec = Stats.activeSeconds(this, seg.walk, seg.run)
        detail.text = ("ВЫБРАННЫЙ СТОЛБЕЦ · ${seg.label}\n" +
                "Шаги: ${fmtNum(total)}  (ходьба ${fmtNum(seg.walk)}, бег ${fmtNum(seg.run)})\n" +
                "Дистанция: %.2f км\n".format(km) +
                "Активное время: ${fmtDuration(activeSec)}\n" +
                "Активные: $active ккал · Покой: $basal ккал · Всего: ${active + basal} ккал")
    }

    /**
     * Энергия и дистанция одного дня (V9.19). Закрытый день -> из
     * СНАПШОТА (заморожен, смена веса/калибровки его не тронет).
     * Сегодняшний -> на лету, покой за прошедшую долю суток.
     */
    /**
     * Энергия и дистанция дня. Закрытый день - из СНАПШОТА, он заморожен.
     * Сегодняшний (открытый) - почасовая сумма с профилем каждого часа
     * (V11.2): груз, снятый вечером, не переписывает утро.
     */
    private suspend fun dayEnergy(rec: DayRecord, isToday: Boolean): Triple<Int, Int, Int> {
        if (isToday) {
            val (active, distM) = Stats.segmentedActiveAndDistance(this, rec.date)
            return Triple(active, Stats.kcalBasalToday(this), distM.toInt())
        }
        val (a, bFull) = Stats.kcalOfDay(this, rec)
        val dm = (Stats.distanceOfDayKm(this, rec) * 1000).toInt()
        return Triple(a, bFull, dm)
    }

    private fun scaleTitle(s: Scale) = when (s) {
        Scale.TODAY -> "сегодня"
        Scale.YESTERDAY -> "вчера"
        Scale.D7 -> "7 дней"
        Scale.D30 -> "30 дней"
        Scale.YEAR -> "год"
        Scale.ALL -> "всё время"
    }

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
                "За будущие и пустые дни покой не считается.\n\n" +
                "Калории и дистанция закрытых дней заморожены: смена веса или новая " +
                "калибровка не меняют прошлое. За сегодня каждый час считается " +
                "с тем профилем, который действовал именно в тот час."
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

    /**
     * v301. Почему пусто. Экран с нулями обязан объяснять причину, иначе
     * поиск превращается в перебор гипотез: данных нет вообще, данные
     * есть но не за этот день, или ключ часа не совпадает с запросом.
     * Все три случая различимы только числами, поэтому они здесь.
     */
    private suspend fun emptyReason(dao: StepDao, day: String): String {
        val all = dao.countHoursAll()
        val today = dao.countHoursOfDay(day)
        val rec = dao.day(day)
        val steps = (rec?.walkSteps ?: 0) + (rec?.runSteps ?: 0)
        val keys = dao.lastHourKeys()
        // v303. ПРОБНАЯ ЗАПИСЬ. Всё остальное - косвенные признаки: они
        // говорят, что строк нет, но не говорят почему. Здесь мы прямо
        // пробуем записать текущий час нулевой дельтой и смотрим, что
        // ответит база. Нулевая дельта ничего не портит: строка часа
        // создаётся с нулями, ровно как её создала бы служба.
        val probe = runCatching {
            val k = java.time.LocalDateTime.now().let {
                "%04d-%02d-%02d %02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour)
            }
            dao.ensureHour(k)
            dao.addHour(k, 0, 0, 0, 0, 0L, 0)
            val after = dao.countHoursOfDay(day)
            // v305. Печатаем сам ключ: раньше проба сообщала «прошла», а
            // строка не появлялась, и по такому отчёту нельзя было понять,
            // что именно писалось.
            "проба: ключ [" + k + "], часов за день стало " + after
        }.getOrElse { e ->
            "ПРОБНАЯ ЗАПИСЬ УПАЛА: " + (e.message ?: e.javaClass.simpleName)
        }
        return probe + ".\n" + when {
            all == 0 && steps == 0 ->
                "Часов нет и шагов за день нет."
            all == 0 ->
                "Шагов за день " + steps + ", но почасовая таблица ПУСТА. " +
                "Значит часы не записываются. Прошедшие часы вернуть нельзя, " +
                "новые появятся по ходу дня."
            today == 0 ->
                "Всего часов в базе " + all + ", но за " + day + " ни одного. " +
                "Последние записанные: " + keys.joinToString(", ") +
                ". Похоже, ключ часа не совпадает с датой дня."
            else ->
                "Часов за день " + today + ", шагов " + steps +
                " - данные есть, но не попали на график."
        }
    }

    /**
     * v304. Дамп таблиц прямо на экран. База лежит в приватной папке
     * приложения, снаружи её не открыть, поэтому единственный способ
     * увидеть настоящие строки - показать их отсюда.
     *
     * Печатаются строки КАК ЕСТЬ: ключ часа, шаги, уклон, каденс. По ним
     * сразу видно, пуста ли таблица, тот ли ключ и нули там или значения.
     */
    private suspend fun dumpTables(dao: StepDao): String {
        val sb = StringBuilder()
        val hours = runCatching { dao.dumpHours() }.getOrElse {
            return "ЧТЕНИЕ hours УПАЛО: " + (it.message ?: it.javaClass.simpleName)
        }
        sb.append("hours: строк ").append(hours.size)
        if (hours.isEmpty()) {
            sb.append(" — ТАБЛИЦА ПУСТА")
        } else {
            for (h in hours.take(12)) {
                sb.append("\n  [").append(h.dateHour).append("] ход ")
                    .append(h.walkSteps).append(" бег ").append(h.runSteps)
                    .append(" вверх ").append(h.upSteps)
                    .append(" вниз ").append(h.downSteps)
                    .append(" кад ").append(h.cadenceStepSum)
            }
        }
        val days = runCatching { dao.dumpDays() }.getOrElse {
            return sb.toString() + "\nЧТЕНИЕ days УПАЛО: " +
                (it.message ?: it.javaClass.simpleName)
        }
        sb.append("\ndays: строк ").append(days.size)
        for (d in days.take(4)) {
            sb.append("\n  [").append(d.date).append("] ход ")
                .append(d.walkSteps).append(" бег ").append(d.runSteps)
        }
        return sb.toString()
    }

    private fun render() {
        paintChips()
        lifecycleScope.launch {
            val dao = AppDb.get(this@TimelineActivity).dao()
            val segs: List<TimelineView.Seg>
            var labelEvery = 1
            var walkSum = 0; var runSum = 0
            // Покой (BMR) начисляем за ПРОЖИТОЕ время с данными: сегодня =
            // доля прошедших суток (как на главном), прошлые дни = 1,
            // пустые и будущие = 0 (V9.17).
            var basalDays = 0f
            val todayFraction = java.time.LocalTime.now().toSecondOfDay() / 86400f
            // Суммы из СНАПШОТОВ закрытых дней (V9.19): прошлое не
            // пересчитывается по текущему профилю/калибровке.
            var sumA = 0; var sumB = 0; var sumD = 0

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
                        // Профиль СВОЕГО часа, не текущий - Stats.energyForHour (V11.2)
                        if (r != null && w + rn > 0) {
                            val (ha, hd) = Stats.energyForHour(this@TimelineActivity, r)
                            TimelineView.Seg(w, rn, "%02d".format(h), act, ha, -1, hd.toInt())
                        } else {
                            TimelineView.Seg(w, rn, "%02d".format(h), act)
                        }
                    }
                    basalDays = if (current == Scale.TODAY) todayFraction else 1f
                    labelEvery = 3
                    val rec = dao.day(day.toString())
                    // Сводка дня: вчера - из снапшота, сегодня - на лету.
                    rec?.let {
                        val e = dayEnergy(it, current == Scale.TODAY)
                        sumA = e.first; sumB = e.second; sumD = e.third
                    }
                    val dayTotal = (rec?.walkSteps ?: 0) + (rec?.runSteps ?: 0)
                    // v302. ПОРЯДОК. В v301 объяснение ставилось выше, а эта
                    // строка его тут же затирала - человек видел общую фразу
                    // про неполную детализацию вместо причины. Присвоение
                    // подписи должно быть ОДНО, иначе побеждает последнее.
                    hint.text = when {
                        walkSum + runSum == 0 ->
                            emptyReason(dao, day.toString()) + "\n\n" + dumpTables(dao)
                        dayTotal > walkSum + runSum ->
                            "За день всего $dayTotal шагов · по часам разложено " +
                            (walkSum + runSum) + " · остальное записано до того, " +
                            "как включился почасовой учёт"
                        else -> ""
                    }
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
                        val isToday = date == today
                        val act = if (w + rn > 0) (if (isToday) todayFraction else 1f) else 0f
                        // Калории/дистанция закрытого дня - из снапшота (V9.19)
                        val e = if (r != null) dayEnergy(r, isToday) else Triple(-1, -1, -1)
                        if (e.first >= 0) { sumA += e.first; sumB += e.second; sumD += e.third }
                        TimelineView.Seg(w, rn, "%d.%d".format(date.dayOfMonth, date.monthValue),
                            act, e.first, e.second, e.third)
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
                        var wA = 0; var wB = 0; var wD = 0
                        for (dow in 0..6) {
                            val date = start.plusDays((wk * 7 + dow).toLong())
                            if (date.isAfter(today)) continue
                            all[date.toString()]?.let {
                                w += it.walkSteps; rn += it.runSteps
                                if (it.walkSteps + it.runSteps > 0) {
                                    actDaysF += if (date == today) todayFraction else 1f
                                    val e = dayEnergy(it, date == today)
                                    wA += e.first; wB += e.second; wD += e.third
                                }
                            }
                        }
                        walkSum += w; runSum += rn
                        sumA += wA; sumB += wB; sumD += wD
                        val wkStart = start.plusWeeks(wk.toLong())
                        TimelineView.Seg(w, rn, "%d.%d".format(wkStart.dayOfMonth, wkStart.monthValue),
                            actDaysF, if (w + rn > 0) wA else -1, wB, wD)
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
                        val monthE = LinkedHashMap<String, IntArray>()  // A, B, distM
                        val todayS = LocalDate.now().toString()
                        all.sortedBy { it.date }.forEach { d ->
                            val ym = d.date.substring(0, 7)
                            val acc = byMonth.getOrPut(ym) { IntArray(2) }
                            acc[0] += d.walkSteps; acc[1] += d.runSteps
                            if (d.walkSteps + d.runSteps > 0) {
                                val add = if (d.date == todayS) todayFraction else 1f
                                monthDays[ym] = (monthDays[ym] ?: 0f) + add
                                val e = dayEnergy(d, d.date == todayS)
                                val me = monthE.getOrPut(ym) { IntArray(3) }
                                me[0] += e.first; me[1] += e.second; me[2] += e.third
                                sumA += e.first; sumB += e.second; sumD += e.third
                            }
                        }
                        segs = byMonth.map { (ym, acc) ->
                            walkSum += acc[0]; runSum += acc[1]
                            val me = monthE[ym]
                            TimelineView.Seg(acc[0], acc[1],
                                ym.substring(5) + "." + ym.substring(2, 4), monthDays[ym] ?: 0f,
                                me?.get(0) ?: -1, me?.get(1) ?: -1, me?.get(2) ?: -1)
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
                // Суммы из снапшотов закрытых дней; fallback - на лету.
                val active = if (sumA > 0) sumA
                             else Stats.kcalActive(this@TimelineActivity, walkSum, runSum)
                val basal = if (sumB > 0) sumB
                            else (Stats.kcalBasalFullDay(this@TimelineActivity) * basalDays).toInt()
                val km = if (sumD > 0) sumD / 1000f
                         else Stats.distanceKm(this@TimelineActivity, walkSum, runSum)
                val activeSec = Stats.activeSeconds(this@TimelineActivity, walkSum, runSum)
                summary.text = ("ЗА ВЕСЬ ПЕРИОД · ${scaleTitle(current)}\n" +
                        "Шаги: ${fmtNum(total)}  (ходьба ${fmtNum(walkSum)}, бег ${fmtNum(runSum)})\n" +
                        "Дистанция: %.2f км   ·   Активное время: ${fmtDuration(activeSec)}\n" +
                        "Активные: $active ккал · Покой: $basal ккал · Всего: ${active + basal} ккал")
                        .format(km)
            }
        }
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
