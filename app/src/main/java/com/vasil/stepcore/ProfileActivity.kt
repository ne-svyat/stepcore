package com.vasil.stepcore

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Профиль (вес/рост/возраст/пол/цель) + паспорт статистики карточками. */
class ProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val weightIn = findViewById<EditText>(R.id.weightInput)
        val heightIn = findViewById<EditText>(R.id.heightInput)
        val ageIn = findViewById<EditText>(R.id.ageInput)
        val goalIn = findViewById<EditText>(R.id.goalInput)
        val sexM = findViewById<RadioButton>(R.id.sexM)
        val sexF = findViewById<RadioButton>(R.id.sexF)

        if (prefs.contains("p_weight")) weightIn.setText(prefs.getFloat("p_weight", 0f).toString())
        if (prefs.contains("p_height")) heightIn.setText(prefs.getInt("p_height", 0).toString())
        if (prefs.contains("p_age")) ageIn.setText(prefs.getInt("p_age", 0).toString())
        goalIn.setText(prefs.getInt("p_goal", 10000).toString())
        if (prefs.getString("p_sex", "m") == "f") sexF.isChecked = true else sexM.isChecked = true

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val w = weightIn.text.toString().replace(',', '.').toFloatOrNull()
            val h = heightIn.text.toString().toIntOrNull()
            val a = ageIn.text.toString().toIntOrNull()
            val g = goalIn.text.toString().toIntOrNull()
            if (w == null || h == null || w !in 20f..400f || h !in 80..250) {
                Toast.makeText(this, "Проверь вес и рост", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putFloat("p_weight", w)
                .putInt("p_height", h)
                .putInt("p_age", a ?: 0)
                .putInt("p_goal", (g ?: 10000).coerceIn(1000, 100000))
                .putString("p_sex", if (sexF.isChecked) "f" else "m")
                .apply()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
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
}
