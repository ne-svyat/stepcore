package com.vasil.stepcore

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** SYNX — экран модуля обучения.
 *  v198: стихия читает состояние (огонь/электричество/вода).
 *  L3.0: тумблер обучения + лесенка вопросов при открытии
 *  (ворота -> режим -> подтверждение метки уклона) -> три архива. */
class SynxActivity : AppCompatActivity() {

    // Порог "достаточно" для уклона. Выведен, не угадан: провизорно ~10
    // надёжных сессий на каждое направление (в гору/с горы), чтобы оценить
    // личную медиану и разброс признаков уклона без диктата одного выброса.
    // Уточнится, когда L3 определит реальное "достаточно".
    private val inclineTarget = 20

    // Сколько вопросов подряд за один заход. Потолок, а не цель.
    private var askedInVisit = 0
    private val MAX_ASK_PER_VISIT = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_synx)
        val orb = findViewById<SynxOrbView>(R.id.synxHeroOrb)
        val status = findViewById<TextView>(R.id.synxStatus)
        val learnSwitch = findViewById<SwitchCompat>(R.id.learnSwitch)
        findViewById<TextView>(R.id.dayProfileButton).setOnClickListener {
            startActivity(android.content.Intent(this, DayProfileActivity::class.java))
        }

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        // Тумблер обучения. Выкл по умолчанию: пока не разрешил - вопросов нет.
        learnSwitch.isChecked = prefs.getBoolean(KEY_LEARN, false)
        learnSwitch.setOnCheckedChangeListener { _, checked ->
            // Включение - явное согласие: снимает и паузу "не беспокоить".
            val e = prefs.edit().putBoolean(KEY_LEARN, checked)
            if (checked) e.putLong(KEY_SNOOZE, 0L)
            e.apply()
        }

        // Фундамент: профиль заполнен, длина шага и темп ходьбы калиброваны.
        val profileDone = prefs.contains("p_weight")
        val strideDone = CalibrationRegistry.isDone(this, CalibrationRegistry.Kind.STRIDE)
        val walkDone = CalibrationRegistry.isDone(this, CalibrationRegistry.Kind.WALK_TEMPO)

        lifecycleScope.launch {
            val dao = AppDb.get(this@SynxActivity).dao()
            val reliableIncline = dao.countSessionsInclineReliable()
            when {
                !profileDone || !strideDone || !walkDone -> {
                    orb.setElement(SynxOrbView.Element.FIRE, 0.7f)
                    val miss = ArrayList<String>()
                    if (!profileDone) miss.add("профиль")
                    if (!strideDone) miss.add("длину шага")
                    if (!walkDone) miss.add("темп ходьбы")
                    status.text = "Огонь: сначала заложи фундамент — заполни " +
                        miss.joinToString(", ") + ". Без этого цифры — только оценка."
                }
                reliableIncline < inclineTarget -> {
                    val deficit = 1f - reliableIncline.toFloat() / inclineTarget
                    orb.setElement(SynxOrbView.Element.ELECTRIC, deficit.coerceIn(0.35f, 1f))
                    status.text = "Электричество: мало надёжных сессий уклона (" +
                        reliableIncline + " из ~" + inclineTarget +
                        "). Отмечай «в гору» и «с горы» на прогулке — каждая метка на счету."
                }
                else -> {
                    orb.setElement(SynxOrbView.Element.WATER, 0.5f)
                    status.text = "Вода: данных для уклона достаточно. Можно двигаться дальше."
                }
            }
            // L3.1: зрелость агента - по нижней границе Уилсона, не по сырому проценту.
            val upN = prefs.getInt("ia_up_n", 0)
            val downN = prefs.getInt("ia_down_n", 0)
            if (upN + downN > 0) {
                status.text = status.text.toString() + "\n\nАгент уклона: в гору — " +
                    InclineAgent.maturity(prefs.getInt("ia_up_ok", 0), upN) +
                    " (" + prefs.getInt("ia_up_ok", 0) + "/" + upN + "), с горы — " +
                    InclineAgent.maturity(prefs.getInt("ia_down_ok", 0), downN) +
                    " (" + prefs.getInt("ia_down_ok", 0) + "/" + downN + ")"
            }
            // L3.0: если обучение включено и есть свежая надёжная неспрошенная
            // уклонная сессия - запускаем лесенку. Одна сессия за раз.
            // Пауза видна человеку: иначе молчание выглядит как поломка.
            val snoozeUntil = prefs.getLong(KEY_SNOOZE, 0L)
            if (snoozeUntil > System.currentTimeMillis()) {
                val f = java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale("ru"))
                status.text = status.text.toString() + "\n\nОпрос на паузе до " +
                    f.format(java.util.Date(snoozeUntil)) +
                    ". Вернуть раньше — выключи и включи тумблер."
            }
            // Пауза "не беспокоить" уважается наравне с тумблером.
            val snoozed = prefs.getLong(KEY_SNOOZE, 0L) > System.currentTimeMillis()
            if (prefs.getBoolean(KEY_LEARN, false) && !snoozed) {
                // Уклон в дефиците -> приоритет ему. Но каждый 3-й вопрос про
                // ПЛОСКУЮ сессию: иначе "ровно" навсегда останется меткой по
                // умолчанию ("не нажимал"), а не подтверждённым классом.
                val s = nextCandidate(dao)
                if (s != null) askGate(s)
            }
        }
    }

    // --- L3.0: лесенка ворота -> режим -> подтверждение метки уклона ---

    private fun askGate(s: SessionRecord) {
        val longWalk = s.durationMs >= 20 * 60_000L
        val head = "Твоя прогулка:\n" + anchor(s) + "\n\n"
        val q = if (longWalk) head + "Долгая вышла — устал? Уделишь пару вопросов про неё?"
                else head + "Уделишь пару коротких вопросов про неё?"
        AlertDialog.Builder(this)
            .setTitle("SYNX учится")
            .setMessage(q)
            .setPositiveButton("Да") { _, _ ->
                if (longWalk) journal("SYNX ворота: усталость отмечена")
                askMode(s)
            }
            .setNegativeButton("Не сейчас", null)  // сессию не трогаем, спросим позже
            .show()
    }

    private fun askMode(s: SessionRecord) {
        val modes = arrayOf("Ходьба", "Бег", "Машина", "Покой")
        // Полная дата обязательна: по одному времени невозможно понять, какой
        // это был день, и легко ответить не про ту прогулку.
        AlertDialog.Builder(this)
            .setCustomTitle(dialogTitle("Что это было?\n\n" + anchor(s)))
            .setItems(modes) { _, which ->
                journal("SYNX режим: " + modes[which] + " (" + anchor(s) + ")")
                askIncline(s)
            }
            .show()
    }

    /** Фактический состав меток внутри сессии. Метка сессии берётся с первого
     *  образца, а одиночные чужие образцы поглощаются (защита от мис-тапа).
     *  Поэтому показываем состав: проверяемость вместо доверия на слово. */
    private suspend fun labelBreakdown(s: SessionRecord): String {
        val dao = AppDb.get(this).dao()
        val list = dao.samplesBetween(s.startMs, s.endMs)
        if (list.isEmpty()) return ""
        val counts = LinkedHashMap<String, Int>()
        for (x in list) counts[x.label] = (counts[x.label] ?: 0) + 1
        val parts = ArrayList<String>()
        for ((k, v) in counts) parts.add(labelRu(k) + " " + v)
        return "Образцов " + list.size + ": " + parts.joinToString(", ")
    }

    private fun askIncline(s: SessionRecord) {
        lifecycleScope.launch {
            // Агент говорит только про уже отмеченный уклон: отличить "ровно"
            // от "в гору" по амплитуде нельзя (диапазоны перекрываются).
            var verdict = InclineAgent.Verdict.NO_BASIS
            if (s.label != "FLAT" && s.label != "NONE") {
                val dao = AppDb.get(this@SynxActivity).dao()
                val near = dao.sessionsAround(
                    s.startMs - InclineAgent.WALK_GAP_MS,
                    s.startMs + InclineAgent.WALK_GAP_MS
                )
                verdict = InclineAgent.predict(
                    InclineAgent.Input(s.startMs, s.chipShare, s.ampMed ?: 0f),
                    near.map { InclineAgent.Input(it.startMs, it.chipShare, it.ampMed ?: 0f) }
                ).verdict
            }
            val guess = when (verdict) {
                InclineAgent.Verdict.UP -> "UP"
                InclineAgent.Verdict.DOWN -> "DOWN"
                else -> ""
            }
            showInclineDialog(s, guess, labelBreakdown(s))
        }
    }

    private fun showInclineDialog(s: SessionRecord, guess: String, breakdown: String) {
        val head = anchor(s) + (if (breakdown == "") "" else "\n" + breakdown) + "\n\n"
        // Агент не согласен с меткой -> спрашиваем, что было на самом деле.
        if (guess != "" && guess != s.label) {
            val opts = arrayOf("В гору", "С горы", "Не помню")
            AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle(head + "Помечена «" + labelRu(s.label) +
                    "», но по признакам похоже на «" + labelRu(guess) +
                    "». Что было на самом деле?"))
                .setItems(opts) { _, which ->
                    val truth = if (which == 0) "UP" else if (which == 1) "DOWN" else ""
                    if (truth == "") {
                        recordAnswer(s, 3, "не подтверждено")
                    } else {
                        scoreAgent(guess, truth)
                        if (truth == s.label) recordAnswer(s, 1, "подтверждено")
                        else recordAnswer(s, 2, "дефект (метка не та)")
                    }
                }
                .show()
            return
        }
        val msg = head + (if (s.label == "NONE")
            "Уклон не отмечен. Она была ровной?"
        else if (s.label == "FLAT")
            "Помечена «ровно» — верно?"
        else if (guess != "")
            "Помечена «" + labelRu(s.label) + "», и признаки согласны. Верно?"
        else
            "Помечена «" + labelRu(s.label) + "» — верно?")
        AlertDialog.Builder(this)
            .setTitle("Уклон")
            .setMessage(msg)
            .setPositiveButton("Да") { _, _ ->
                if (guess != "") scoreAgent(guess, s.label)
                recordAnswer(s, 1, "подтверждено")
            }
            .setNegativeButton("Нет") { _, _ -> recordAnswer(s, 2, "дефект") }
            .setNeutralButton("Не помню") { _, _ -> recordAnswer(s, 3, "не подтверждено") }
            .show()
    }

    /** Счёт точности агента - отдельно по направлениям (общий процент прячет
     *  "в гору отлично, с горы мимо"). Считаем только когда известна правда. */
    private fun scoreAgent(guess: String, truth: String) {
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val kk = "ia_" + truth.lowercase() + "_ok"
        val nk = "ia_" + truth.lowercase() + "_n"
        val ok = prefs.getInt(kk, 0) + (if (guess == truth) 1 else 0)
        val n = prefs.getInt(nk, 0) + 1
        prefs.edit().putInt(kk, ok).putInt(nk, n).apply()
    }

    /** Кандидат на вопрос. Уклон в приоритете (он в дефиците), но каждый
     *  третий вопрос - про неуклонную сессию, иначе "ровно" никогда не станет
     *  подтверждённым классом. */
    private suspend fun nextCandidate(dao: StepDao): SessionRecord? {
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val n = prefs.getInt(KEY_ASK_N, 0)
        val flatTurn = (n % 3) == 2
        val s = if (flatTurn) dao.latestUnaskedFlat() ?: dao.latestUnaskedIncline()
                else dao.latestUnaskedIncline() ?: dao.latestUnaskedFlat()
        if (s != null) prefs.edit().putInt(KEY_ASK_N, n + 1).apply()
        return s
    }

    private fun recordAnswer(s: SessionRecord, state: Int, word: String) {
        lifecycleScope.launch {
            val dao = AppDb.get(this@SynxActivity).dao()
            dao.setSessionConfirm(s.id, state)
            journal("SYNX уклон «" + labelRu(s.label) + "»: " + word + " (" + anchor(s) + ")")
            // Человек уже в потоке - предлагаем следующую сразу, не заставляя
            // выходить и заходить. Потолок бережёт внимание: устал - закрыл.
            askedInVisit++
            if (askedInVisit < MAX_ASK_PER_VISIT) {
                val next = nextCandidate(dao)
                if (next != null) { offerNext(next); return@launch }
            }
            Toast.makeText(
                this@SynxActivity, "Записал, спасибо", Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Человек решает сам, продолжать ли. Молчаливое авто-продолжение
     *  превращает помощь в назойливость. */
    private fun offerNext(next: SessionRecord) {
        AlertDialog.Builder(this)
            .setTitle("Записал, спасибо")
            .setMessage("Есть ещё одна прогулка:\n" + anchor(next))
            .setPositiveButton("Ещё вопрос") { _, _ -> askMode(next) }
            .setNegativeButton("Хватит", null)
            .setNeutralButton("Не беспокоить") { _, _ -> askSnooze() }
            .show()
    }

    private fun askSnooze() {
        val groups = arrayOf("Минуты", "Часы", "Дни", "Выключить обучение")
        AlertDialog.Builder(this)
            .setCustomTitle(dialogTitle("Не беспокоить\n\nВопросы вернутся сами. " +
                "Раньше срока — переключи тумблер обучения выкл/вкл."))
            .setItems(groups) { _, which ->
                when (which) {
                    0 -> snoozePick("Минуты", intArrayOf(5, 10, 15, 30, 60), 60_000L, "мин")
                    1 -> snoozePick("Часы", intArrayOf(1, 2, 4, 8, 16, 24), 3_600_000L, "ч")
                    2 -> snoozePick("Дни", intArrayOf(2, 4, 6, 8, 10), 86_400_000L, "дн")
                    else -> {
                        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
                        prefs.edit().putBoolean(KEY_LEARN, false).apply()
                        findViewById<SwitchCompat>(R.id.learnSwitch).isChecked = false
                        journal("SYNX: обучение выключено")
                        Toast.makeText(this, "Обучение выключено", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** Второй уровень: конкретный срок внутри выбранной единицы. */
    private fun snoozePick(unit: String, values: IntArray, mult: Long, suffix: String) {
        val names = Array(values.size) { values[it].toString() + " " + suffix }
        AlertDialog.Builder(this)
            .setCustomTitle(dialogTitle("Не беспокоить: " + unit.lowercase()))
            .setItems(names) { _, which ->
                val until = System.currentTimeMillis() + values[which] * mult
                getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
                    .edit().putLong(KEY_SNOOZE, until).apply()
                journal("SYNX: пауза опроса на " + names[which])
                Toast.makeText(this, "Пауза: " + names[which], Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Заголовок-текст для диалогов СО СПИСКОМ.
     *  setMessage() и setItems() делят одну область: если задать оба, список
     *  не отрисуется и нажать будет нечего (баг v205). setCustomTitle живёт в
     *  своей области и со списком не конфликтует. */
    private fun dialogTitle(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(getColor(R.color.text_main))
        val pad = (16 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad + pad / 2, pad, pad + pad / 2, pad / 2)
        return tv
    }

    private fun journal(text: String) {
        lifecycleScope.launch {
            AppDb.get(this@SynxActivity).dao().addEvent(
                EventRecord(
                    timeMs = System.currentTimeMillis(),
                    date = java.time.LocalDate.now().toString(),
                    text = text
                )
            )
        }
    }

    /** Полный якорь для памяти: дата (день недели, число, месяц, год),
     *  интервал начало-конец, минуты, ~шаги и как нёс телефон (из доли чипа).
     *  Дистанции/маршрута в строке сессии нет - не выдумываем. */
    private fun anchor(s: SessionRecord): String {
        val ru = java.util.Locale("ru")
        val dfDate = java.text.SimpleDateFormat("EEE, d MMMM yyyy", ru)
        val dfTime = java.text.SimpleDateFormat("HH:mm", ru)
        val date = dfDate.format(java.util.Date(s.startMs))
        val from = dfTime.format(java.util.Date(s.startMs))
        val to = dfTime.format(java.util.Date(s.endMs))
        val mins = (s.durationMs / 60_000L).toInt()
        val steps = s.nSamples * 20
        // chipShare - доля образцов от чипа (карман). Пороги описательные,
        // не решают счёт: это зацепка памяти, а не измерение.
        val carry = when {
            s.chipShare >= 0.6f -> "телефон в основном в кармане"
            s.chipShare <= 0.4f -> "телефон в основном в руке"
            else -> "телефон то в руке, то в кармане"
        }
        return date + ", " + from + "–" + to +
            " (~" + mins + " мин, ~" + steps + " шаг., " + carry + ")"
    }

    private fun shortAnchor(s: SessionRecord): String {
        val dfTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru"))
        return dfTime.format(java.util.Date(s.startMs)) + "–" +
            dfTime.format(java.util.Date(s.endMs))
    }

    private fun labelRu(l: String) = when (l) {
        "UP" -> "в гору"; "DOWN" -> "с горы"
        "NONE" -> "не отмечено"; else -> "ровно"
    }

    companion object {
        private const val KEY_LEARN = "learn_enabled"
        private const val KEY_ASK_N = "learn_ask_n"
        private const val KEY_SNOOZE = "learn_snooze_until"
    }
}
