package com.vasil.stepcore

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Профиль (вес/рост/возраст/пол) для калорий и дистанции + паспорт
 * накопленной статистики. Ядро детектора не затрагивается.
 */
class ProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val weightIn = findViewById<EditText>(R.id.weightInput)
        val heightIn = findViewById<EditText>(R.id.heightInput)
        val ageIn = findViewById<EditText>(R.id.ageInput)
        val sexM = findViewById<RadioButton>(R.id.sexM)
        val sexF = findViewById<RadioButton>(R.id.sexF)

        if (prefs.contains("p_weight")) weightIn.setText(prefs.getFloat("p_weight", 0f).toString())
        if (prefs.contains("p_height")) heightIn.setText(prefs.getInt("p_height", 0).toString())
        if (prefs.contains("p_age")) ageIn.setText(prefs.getInt("p_age", 0).toString())
        if (prefs.getString("p_sex", "m") == "f") sexF.isChecked = true else sexM.isChecked = true

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val w = weightIn.text.toString().replace(',', '.').toFloatOrNull()
            val h = heightIn.text.toString().toIntOrNull()
            val a = ageIn.text.toString().toIntOrNull()
            if (w == null || h == null || w !in 20f..400f || h !in 80..250) {
                Toast.makeText(this, "Проверь вес и рост", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putFloat("p_weight", w)
                .putInt("p_height", h)
                .putInt("p_age", a ?: 0)
                .putString("p_sex", if (sexF.isChecked) "f" else "m")
                .apply()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }

        loadPassport()
    }

    private fun loadPassport() {
        val view = findViewById<TextView>(R.id.passportText)
        lifecycleScope.launch {
            val days = AppDb.get(this@ProfileActivity).dao().allDays()
            if (days.isEmpty()) { view.text = "Пока нет данных"; return@launch }
            val totalWalk = days.sumOf { it.walkSteps }
            val totalRun = days.sumOf { it.runSteps }
            val total = totalWalk + totalRun
            val best = days.maxByOrNull { it.walkSteps + it.runSteps }!!
            val avg = total / days.size
            val today = LocalDate.now().toString()
            val km = Stats.distanceKm(this@ProfileActivity, totalWalk, totalRun)
            view.text = buildString {
                appendLine("Всего:      $total шагов")
                if (km > 0) appendLine("Дистанция:  ${"%.1f".format(km)} км (оценка)")
                appendLine("Ходьба/бег: $totalWalk / $totalRun")
                appendLine("Дней:       ${days.size}")
                appendLine("Среднее:    $avg шагов/день")
                appendLine("Лучший:     ${best.date} — ${best.walkSteps + best.runSteps}")
                if (best.date == today) appendLine("Лучший день — сегодня.")
            }
        }
    }
}
