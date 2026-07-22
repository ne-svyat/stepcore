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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_synx)
        val orb = findViewById<SynxOrbView>(R.id.synxHeroOrb)
        val status = findViewById<TextView>(R.id.synxStatus)
        val learnSwitch = findViewById<SwitchCompat>(R.id.learnSwitch)

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        // Тумблер обучения. Выкл по умолчанию: пока не разрешил - вопросов нет.
        learnSwitch.isChecked = prefs.getBoolean(KEY_LEARN, false)
        learnSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LEARN, checked).apply()
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
            // L3.0: если обучение включено и есть свежая надёжная неспрошенная
            // уклонная сессия - запускаем лесенку. Одна сессия за раз.
            if (prefs.getBoolean(KEY_LEARN, false)) {
                // Уклон в дефиците -> приоритет ему. Но каждый 3-й вопрос про
                // ПЛОСКУЮ сессию: иначе "ровно" навсегда останется меткой по
                // умолчанию ("не нажимал"), а не подтверждённым классом.
                val n = prefs.getInt(KEY_ASK_N, 0)
                val flatTurn = (n % 3) == 2
                val s = if (flatTurn) dao.latestUnaskedFlat() ?: dao.latestUnaskedIncline()
                        else dao.latestUnaskedIncline() ?: dao.latestUnaskedFlat()
                if (s != null) {
                    prefs.edit().putInt(KEY_ASK_N, n + 1).apply()
                    askGate(s)
                }
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
        AlertDialog.Builder(this)
            .setTitle("Прогулка " + shortAnchor(s) + " — что это было?")
            .setItems(modes) { _, which ->
                journal("SYNX режим: " + modes[which] + " (" + anchor(s) + ")")
                askIncline(s)
            }
            .show()
    }

    private fun askIncline(s: SessionRecord) {
        // Честно: FLAT - это метка ПО УМОЛЧАНИЮ ("не нажимал"), а не измерение.
        // Поэтому про плоские спрашиваем иначе, не выдавая догадку за твой выбор.
        val msg = if (s.label == "FLAT")
            "На прогулке " + shortAnchor(s) + " ты не отмечал уклон. Она была ровной?"
        else
            "Прогулка " + shortAnchor(s) + " помечена «" + labelRu(s.label) + "» — верно?"
        AlertDialog.Builder(this)
            .setTitle("Уклон")
            .setMessage(msg)
            .setPositiveButton("Да") { _, _ -> recordAnswer(s, 1, "подтверждено") }
            .setNegativeButton("Нет") { _, _ -> recordAnswer(s, 2, "дефект") }
            .setNeutralButton("Не помню") { _, _ -> recordAnswer(s, 3, "не подтверждено") }
            .show()
    }

    private fun recordAnswer(s: SessionRecord, state: Int, word: String) {
        lifecycleScope.launch {
            AppDb.get(this@SynxActivity).dao().setSessionConfirm(s.id, state)
            journal("SYNX уклон «" + labelRu(s.label) + "»: " + word + " (" + anchor(s) + ")")
        }
        Toast.makeText(this, "Записал, спасибо", Toast.LENGTH_SHORT).show()
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
        "UP" -> "в гору"; "DOWN" -> "с горы"; else -> "ровно"
    }

    companion object {
        private const val KEY_LEARN = "learn_enabled"
        private const val KEY_ASK_N = "learn_ask_n"
    }
}
