package com.vasil.stepcore

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Профиль (вес/рост/возраст/пол/цель) + паспорт статистики карточками. */
class ProfileActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        // V14.3: дудл-стиль — лагерь в шапке, рамки "от руки" на карточках.
        findViewById<DoodleSceneView>(R.id.doodleHeader).setScene(DoodleSceneView.PROFILE)
        DoodleUi.frame(findViewById(R.id.dataContainer), R.color.accent_violet, R.color.surface, 201L)
        // У контейнера "Паспорт" рамки НЕТ намеренно. Своя рамка у него была
        // бы рамкой вокруг рамок: карточки внутри идут во всю ширину без
        // отступов, и их красные/зелёные контуры ложились ровно на синий
        // контур контейнера - линии наезжали друг на друга. Каждая карточка
        // уже обведена сама, второй обводки не нужно.

        // "ДАННЫЕ" оформляется ниже, рядом с уже объявленной dataToggle:
        // второе объявление той же переменной в одной области видимости
        // Kotlin не допускает.

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val weightIn = findViewById<EditText>(R.id.weightInput)
        val heightIn = findViewById<EditText>(R.id.heightInput)
        val ageIn = findViewById<EditText>(R.id.ageInput)
        val goalIn = findViewById<EditText>(R.id.goalInput)
        val loadIn = findViewById<EditText>(R.id.loadInput)
        val sexM = findViewById<RadioButton>(R.id.sexM)
        val sexF = findViewById<RadioButton>(R.id.sexF)

        if (prefs.contains("p_weight")) weightIn.setText(prefs.getFloat("p_weight", 0f).toString())
        if (prefs.contains("p_height")) heightIn.setText(prefs.getInt("p_height", 0).toString())
        if (prefs.contains("p_age")) ageIn.setText(prefs.getInt("p_age", 0).toString())
        goalIn.setText(prefs.getInt("p_goal", 10000).toString())
        val savedLoad = prefs.getFloat("p_load", 0f)
        if (savedLoad > 0f) loadIn.setText(savedLoad.toString())
        if (prefs.getString("p_sex", "m") == "f") sexF.isChecked = true else sexM.isChecked = true

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val w = weightIn.text.toString().replace(',', '.').toFloatOrNull()
            val h = heightIn.text.toString().toIntOrNull()
            val a = ageIn.text.toString().toIntOrNull()
            val g = goalIn.text.toString().toIntOrNull()
            val l = (loadIn.text.toString().replace(',', '.').toFloatOrNull() ?: 0f)
                .coerceIn(0f, 100f)
            if (w == null || h == null || w !in 20f..400f || h !in 80..250) {
                Toast.makeText(this, "Проверь вес и рост", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // V9.10: защита от опечаток - подтверждать аномальные изменения.
            // Порог не магический: вес взрослого редко меняется >5 кг за
            // сессию правки; рост у взрослого не меняется. Большая дельта =
            // вероятная опечатка (75 -> 7). Прошлые дни защищены снапшотами
            // (V9.9), защищаем и будущие расчёты.
            val oldW = prefs.getFloat("p_weight", w)
            val oldH = prefs.getInt("p_height", h)
            val bigChange = kotlin.math.abs(w - oldW) > 5f ||
                    kotlin.math.abs(h - oldH) > 5
            val save = {
                prefs.edit()
                    .putFloat("p_weight", w)
                    .putInt("p_height", h)
                    .putInt("p_age", a ?: 0)
                    .putInt("p_goal", (g ?: 10000).coerceIn(1000, 100000))
                    .putString("p_sex", if (sexF.isChecked) "f" else "m")
                    .putFloat("p_load", l)
                    .apply()
                // V11: точка истории ПОСЛЕ записи в prefs, иначе снимок старый
                lifecycleScope.launch { ProfileHistory.record(this@ProfileActivity) }
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
                loadPassport()
            }
            if (bigChange && prefs.contains("p_weight")) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Подтверди изменение")
                    .setMessage("Вес: ${oldW.toInt()} \u2192 ${w.toInt()} кг, " +
                            "рост: $oldH \u2192 $h см.\nВсё верно?")
                    .setPositiveButton("Да, сохранить") { _, _ -> save() }
                    .setNegativeButton("Отмена", null)
                    .show()
            } else save()
        }

        val dataToggle = findViewById<android.widget.TextView>(R.id.dataToggle)
        // "ДАННЫЕ" - главное действие экрана, а выглядело серой надписью, мимо
        // которой легко проскочить. Теперь это заметная кнопка: своя рамка,
        // янтарный акцент (единственный такой на экране) и мягкая пульсация.
        DoodleUi.frame(dataToggle, R.color.accent_amber, R.color.surface_amber, 203L)
        dataToggle.setTextColor(ContextCompat.getColor(this, R.color.accent_amber_bright))
        dataToggle.setPadding(dp(16), dp(12), dp(16), dp(12))
        DoodleUi.pulse(dataToggle)
        val dataContainer = findViewById<LinearLayout>(R.id.dataContainer)
        // если профиль пустой — раскрыть сразу, иначе свёрнут
        if (!prefs.contains("p_weight")) {
            dataContainer.visibility = View.VISIBLE
            dataToggle.text = "ДАННЫЕ  ▴"
        }
        dataToggle.setOnClickListener {
            val open = dataContainer.visibility == View.VISIBLE
            dataContainer.visibility = if (open) View.GONE else View.VISIBLE
            dataToggle.text = if (open) "ДАННЫЕ  ▾" else "ДАННЫЕ  ▴"
        }

        loadPassport()
    }

    private fun loadPassport() {
        val box = findViewById<LinearLayout>(R.id.passportContainer)
        lifecycleScope.launch {
            val days = AppDb.get(this@ProfileActivity).dao().allDays()
            if (days.isEmpty()) { box.addView(UiKit.dimText(this@ProfileActivity, "Пока нет данных")); return@launch }
            val totalWalk = days.sumOf { it.walkSteps }
            val totalRun = days.sumOf { it.runSteps }
            val total = totalWalk + totalRun
            val best = days.maxByOrNull { it.walkSteps + it.runSteps }!!
            val avg = total / days.size
            val km = Stats.distanceKm(this@ProfileActivity, totalWalk, totalRun)

            box.addView(UiKit.statCard(this@ProfileActivity, "Всего пройдено",
                "$total шагов", "ходьба $totalWalk · бег $totalRun", R.color.accent_red))
            if (km > 0) box.addView(UiKit.statCard(this@ProfileActivity, "Дистанция",
                "${"%.1f".format(km)} км", "оценка", R.color.accent_blue))
            box.addView(UiKit.statCard(this@ProfileActivity, "Дней с данными",
                "${days.size}", "среднее $avg шагов/день", R.color.accent_blue))
            box.addView(UiKit.statCard(this@ProfileActivity, "Лучший день",
                "${best.walkSteps + best.runSteps} шагов", best.date, R.color.accent_red))
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
