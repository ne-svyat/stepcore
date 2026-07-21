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
                val s = dao.latestUnaskedIncline()
                if (s != null) askGate(s)
            }
        }
    }

    // --- L3.0: лесенка ворота -> режим -> подтверждение метки уклона ---

    private fun askGate(s: SessionRecord) {
        val longWalk = s.durationMs >= 20 * 60_000L
        val q = if (longWalk) "Долгая прогулка вышла — устал?"
                else "Ты тут? Уделишь пару коротких вопросов?"
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
            .setTitle("Тогда — что это было?")
            .setItems(modes) { _, which ->
                journal("SYNX режим: " + modes[which] + " (" + anchor(s) + ")")
                askIncline(s)
            }
            .show()
    }

    private fun askIncline(s: SessionRecord) {
        AlertDialog.Builder(this)
            .setTitle("Уклон")
            .setMessage("Прогулка " + anchor(s) + " помечена «" + labelRu(s.label) +
                "» — верно?")
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

    private fun anchor(s: SessionRecord): String {
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(s.startMs))
        val mins = (s.durationMs / 60_000L).toInt()
        val steps = s.nSamples * 20
        return "около " + time + ", ~" + mins + " мин, ~" + steps + " шаг."
    }

    private fun labelRu(l: String) = when (l) {
        "UP" -> "в гору"; "DOWN" -> "с горы"; else -> "ровно"
    }

    companion object {
        private const val KEY_LEARN = "learn_enabled"
    }
}
