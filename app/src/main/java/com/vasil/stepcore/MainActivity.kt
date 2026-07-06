package com.vasil.stepcore

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startTracking()
        }

    private var calibrating = false
    private lateinit var statsView: TextView
    private lateinit var ring: ProgressRingView
    private lateinit var goalView: TextView
    private var yesterdayTotal = -1

    private var goal = 10000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val stepsView = findViewById<TextView>(R.id.stepsText)
        val statusView = findViewById<TextView>(R.id.statusText)
        val modeView = findViewById<TextView>(R.id.modeText)
        val calView = findViewById<TextView>(R.id.calText)
        statsView = findViewById(R.id.statsText)
        ring = findViewById(R.id.progressRing)
        goalView = findViewById(R.id.goalText)
        val toggleBtn = findViewById<Button>(R.id.toggleButton)
        val historyBtn = findViewById<Button>(R.id.historyButton)
        val calWalkBtn = findViewById<Button>(R.id.calWalkButton)
        val calRunBtn = findViewById<Button>(R.id.calRunButton)
        val hapticSwitch = findViewById<SwitchCompat>(R.id.hapticSwitch)
        val detailLogSwitch = findViewById<SwitchCompat>(R.id.detailLogSwitch)
        val toolsToggle = findViewById<TextView>(R.id.toolsToggle)
        val toolsContainer = findViewById<View>(R.id.toolsContainer)

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        if (prefs.getString(StepService.KEY_DAY, "") == java.time.LocalDate.now().toString()) {
            StepsState.steps.value = prefs.getInt(StepService.KEY_STEPS, 0)
        }
        goal = prefs.getInt("p_goal", 10000)

        hapticSwitch.isChecked = prefs.getBoolean("haptic", false)
        StepsState.hapticEnabled.value = hapticSwitch.isChecked
        hapticSwitch.setOnCheckedChangeListener { _, checked ->
            StepsState.hapticEnabled.value = checked
            prefs.edit().putBoolean("haptic", checked).apply()
            if (checked) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(200, 255))
            }
        }

        toggleBtn.setOnClickListener {
            if (StepsState.serviceRunning.value) {
                stopService(Intent(this, StepService::class.java))
            } else {
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.ACTIVITY_RECOGNITION,
                    Manifest.permission.POST_NOTIFICATIONS
                ))
            }
        }

        historyBtn.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.profileButton).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<Button>(R.id.statsButton).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        toolsToggle.setOnClickListener {
            val open = toolsContainer.visibility == View.VISIBLE
            toolsContainer.visibility = if (open) View.GONE else View.VISIBLE
            toolsToggle.text = if (open) "⚙  Инструменты  ▾" else "⚙  Инструменты  ▴"
        }

        lifecycleScope.launch {
            val y = java.time.LocalDate.now().minusDays(1).toString()
            val d = AppDb.get(this@MainActivity).dao().day(y)
            yesterdayTotal = if (d == null) -1 else d.walkSteps + d.runSteps
            updateStats()
        }

        val diagBtn = findViewById<Button>(R.id.diagButton)
        var diagOn = false
        detailLogSwitch.isChecked = prefs.getBoolean("detail_log", false)
        StepsState.detailLog.value = detailLogSwitch.isChecked
        detailLogSwitch.setOnCheckedChangeListener { _, checked ->
            StepsState.detailLog.value = checked
            prefs.edit().putBoolean("detail_log", checked).apply()
        }

        diagBtn.setOnClickListener {
            if (!StepsState.serviceRunning.value) {
                StepsState.calibrationState.value = "Сначала нажми Старт"
                return@setOnClickListener
            }
            diagOn = !diagOn
            diagBtn.text = if (diagOn) "Диагностика: СТОП" else "Диагностика: старт"
            startForegroundService(Intent(this, StepService::class.java)
                .setAction(if (diagOn) StepService.ACTION_DIAG_START
                    else StepService.ACTION_DIAG_STOP))
        }

        calWalkBtn.setOnClickListener { toggleCalibration("walk", calWalkBtn, calRunBtn) }
        calRunBtn.setOnClickListener { toggleCalibration("run", calRunBtn, calWalkBtn) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    StepsState.steps.collect { s ->
                        stepsView.text = s.toString()
                        ring.setProgress(s.toFloat() / goal)
                        goalView.text = "${s * 100 / goal}% · цель ${"%,d".format(goal).replace(',', ' ')}"
                        updateStats()
                    }
                }
                launch {
                    StepsState.serviceRunning.collect { running ->
                        statusView.text = if (running) "Считаю шаги" else "Остановлено"
                        toggleBtn.text = if (running) "Стоп" else "Старт"
                    }
                }
                launch {
                    StepsState.mode.collect { m -> applyModeBadge(modeView, m) }
                }
                launch { StepsState.calibrationState.collect { calView.text = it } }
                launch { StepsState.diag.collect { findViewById<TextView>(R.id.diagText).text = it } }
            }
        }
    }

    /** Бейдж режима: Покой серый, Ходьба синий, Бег красный. */
    private fun applyModeBadge(view: TextView, m: String) {
        val (label, colorRes) = when (m) {
            "WALK" -> "ХОДЬБА" to R.color.accent_blue
            "RUN" -> "БЕГ" to R.color.accent_red
            "TRANSPORT" -> "ТРАНСПОРТ" to R.color.text_dim
            else -> "ПОКОЙ" to R.color.text_dim
        }
        view.text = label
        val bg = GradientDrawable().apply {
            cornerRadius = 18f * resources.displayMetrics.density
            setColor(ContextCompat.getColor(this@MainActivity, R.color.surface2))
            setStroke(
                (1.5f * resources.displayMetrics.density).toInt(),
                ContextCompat.getColor(this@MainActivity, colorRes)
            )
        }
        view.background = bg
        view.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun toggleCalibration(kind: String, self: Button, other: Button) {
        if (!StepsState.serviceRunning.value) {
            StepsState.calibrationState.value = "Сначала нажми Старт"
            return
        }
        val action = if (!calibrating) {
            calibrating = true
            self.text = if (kind == "walk") "Готово (шаг)" else "Готово (бег)"
            other.isEnabled = false
            if (kind == "walk") StepService.ACTION_CAL_WALK else StepService.ACTION_CAL_RUN
        } else {
            calibrating = false
            self.text = if (kind == "walk") "Калибровка: шаг" else "Калибровка: бег"
            other.isEnabled = true
            StepService.ACTION_CAL_STOP
        }
        startForegroundService(Intent(this, StepService::class.java).setAction(action))
    }

    /** км · ккал · сравнение со вчера. Оценки до калибровки V9. */
    private fun updateStats() {
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()
        val walk: Int; val run: Int
        if (prefs.getString(StepService.KEY_DAY, "") == today) {
            walk = prefs.getInt(StepService.KEY_WALK, 0)
            run = prefs.getInt(StepService.KEY_RUN, 0)
        } else { walk = 0; run = 0 }
        val km = Stats.distanceKm(this, walk, run)
        val kcal = Stats.kcal(this, walk, run)
        val sb = StringBuilder()
        if (km > 0) sb.append("%.2f км  ·  %d ккал".format(km, kcal))
        else sb.append("Заполни профиль для км и ккал")
        if (yesterdayTotal >= 0) {
            val diff = (walk + run) - yesterdayTotal
            sb.append("\nвчера $yesterdayTotal (")
            sb.append(if (diff >= 0) "+$diff" else "$diff").append(")")
        }
        statsView.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        goal = getSharedPreferences(StepService.PREFS, MODE_PRIVATE).getInt("p_goal", 10000)
        if (::ring.isInitialized) {
            val s = StepsState.steps.value
            ring.setProgress(s.toFloat() / goal)
            goalView.text = "${s * 100 / goal}% · цель ${"%,d".format(goal).replace(',', ' ')}"
        }
        if (::statsView.isInitialized) updateStats()
    }

    private fun startTracking() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + packageName)
                )
            )
        }
        startForegroundService(Intent(this, StepService::class.java))
    }
}
