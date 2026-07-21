package com.vasil.stepcore

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** SYNX — экран модуля обучения. v198: стихия читает реальное состояние.
 *  Огонь = нет фундамента (профиль/калибровка). Электричество = мало
 *  надёжных уклонных сессий (зовёт собирать). Вода = достаточно. */
class SynxActivity : AppCompatActivity() {

    // Порог "достаточно" для уклона. Выведен, не угадан: провизорно ~10
    // надёжных сессий на каждое направление (в гору/с горы), чтобы оценить
    // личную медиану и разброс признаков уклона без диктата одного выброса.
    // Уточнится, когда L3 определит реальное "достаточно". Сейчас у
    // пользователя ~8 надёжных уклонных -> явный дефицит.
    private val inclineTarget = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_synx)
        val orb = findViewById<SynxOrbView>(R.id.synxHeroOrb)
        val status = findViewById<TextView>(R.id.synxStatus)

        // Фундамент: профиль заполнен, длина шага и темп ходьбы калиброваны.
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val profileDone = prefs.contains("p_weight")
        val strideDone = CalibrationRegistry.isDone(this, CalibrationRegistry.Kind.STRIDE)
        val walkDone = CalibrationRegistry.isDone(this, CalibrationRegistry.Kind.WALK_TEMPO)

        lifecycleScope.launch {
            val reliableIncline =
                AppDb.get(this@SynxActivity).dao().countSessionsInclineReliable()
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
        }
    }
}
