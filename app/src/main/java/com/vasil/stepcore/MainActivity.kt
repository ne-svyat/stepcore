package com.vasil.stepcore

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val stepsView = findViewById<TextView>(R.id.stepsText)
        val statusView = findViewById<TextView>(R.id.statusText)
        val modeView = findViewById<TextView>(R.id.modeText)
        val calView = findViewById<TextView>(R.id.calText)
        val toggleBtn = findViewById<Button>(R.id.toggleButton)
        val historyBtn = findViewById<Button>(R.id.historyButton)
        val calWalkBtn = findViewById<Button>(R.id.calWalkButton)
        val calRunBtn = findViewById<Button>(R.id.calRunButton)
        val hapticSwitch = findViewById<SwitchCompat>(R.id.hapticSwitch)

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        if (prefs.getString(StepService.KEY_DAY, "") == java.time.LocalDate.now().toString()) {
            StepsState.steps.value = prefs.getInt(StepService.KEY_STEPS, 0)
        }

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

        calWalkBtn.setOnClickListener { toggleCalibration("walk", calWalkBtn, calRunBtn) }
        calRunBtn.setOnClickListener { toggleCalibration("run", calRunBtn, calWalkBtn) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { StepsState.steps.collect { stepsView.text = it.toString() } }
                launch {
                    StepsState.serviceRunning.collect { running ->
                        statusView.text = if (running) "Считаю шаги" else "Остановлено"
                        toggleBtn.text = if (running) "Стоп" else "Старт"
                    }
                }
                launch {
                    StepsState.mode.collect { m ->
                        modeView.text = when (m) {
                            "WALK" -> "Ходьба"
                            "RUN" -> "Бег"
                            "TRANSPORT" -> "Транспорт"
                            else -> "Покой"
                        }
                    }
                }
                launch { StepsState.calibrationState.collect { calView.text = it } }
                launch { StepsState.diag.collect { findViewById<TextView>(R.id.diagText).text = it } }
            }
        }
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

    private fun startTracking() {
        // Без Doze-whitelist система игнорирует wakelock сервиса
        // при выключенном экране - счёт останавливается.
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
