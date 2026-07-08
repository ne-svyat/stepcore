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
    private var distCalibrating = false
    private var gpsCalibrating = false
    private var gpsCalibrator: LocationCalibrator? = null
    private var gpsStepsAtStart = 0
    private lateinit var calGpsBtn: Button
    private val gpsPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startGpsCalibration()
            else StepsState.calibrationState.value = "Без доступа к GPS калибровка невозможна"
        }
    private lateinit var statsView: TextView
    private lateinit var ring: ProgressRingView
    private lateinit var goalView: TextView
    private lateinit var activeTimeText: TextView
    private lateinit var todayKmKcal: TextView
    private var yWalk = 0
    private var yRun = 0
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
        activeTimeText = findViewById(R.id.activeTimeText)
        todayKmKcal = findViewById(R.id.todayKmKcal)
        val toggleBtn = findViewById<Button>(R.id.toggleButton)
        val historyBtn = findViewById<Button>(R.id.historyButton)
        val calWalkBtn = findViewById<Button>(R.id.calWalkButton)
        val calRunBtn = findViewById<Button>(R.id.calRunButton)
        val calDistBtn = findViewById<Button>(R.id.calDistButton)
        val distInput = findViewById<android.widget.EditText>(R.id.distMetresInput)
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
        findViewById<Button>(R.id.timelineButton).setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }

        toolsToggle.setOnClickListener {
            val open = toolsContainer.visibility == View.VISIBLE
            toolsContainer.visibility = if (open) View.GONE else View.VISIBLE
            toolsToggle.text = if (open) "⚙  Инструменты  ▾" else "⚙  Инструменты  ▴"
        }

        lifecycleScope.launch {
            val y = java.time.LocalDate.now().minusDays(1).toString()
            val d = AppDb.get(this@MainActivity).dao().day(y)
            if (d != null) { yWalk = d.walkSteps; yRun = d.runSteps; yesterdayTotal = d.walkSteps + d.runSteps }
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

        calDistBtn.setOnClickListener {
            if (!StepsState.serviceRunning.value) {
                StepsState.calibrationState.value = "Сначала нажми Старт"; return@setOnClickListener
            }
            if (!distCalibrating) {
                val m = distInput.text.toString().replace(',', '.').toFloatOrNull()
                if (m == null || m < 50f) {
                    StepsState.calibrationState.value = "Введи длину отрезка (минимум 50 м)"
                    return@setOnClickListener
                }
                distCalibrating = true
                calDistBtn.text = "Готово (дистанция)"
                startForegroundService(Intent(this, StepService::class.java)
                    .setAction(StepService.ACTION_CAL_DIST_START)
                    .putExtra(StepService.EXTRA_METRES, m))
            } else {
                distCalibrating = false
                calDistBtn.text = "Калибровка дистанции"
                startForegroundService(Intent(this, StepService::class.java)
                    .setAction(StepService.ACTION_CAL_DIST_STOP))
            }
        }

        calGpsBtn = findViewById(R.id.calGpsButton)
        calGpsBtn.setOnClickListener {
            if (!StepsState.serviceRunning.value) {
                StepsState.calibrationState.value = "Сначала нажми Старт"; return@setOnClickListener
            }
            if (!gpsCalibrating) confirmAndStartGps() else finishGpsCalibration()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    StepsState.steps.collect { s ->
                        stepsView.text = s.toString()
                        refreshRing(s)
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

    /** Кольцо ходьба/бег + число% + км/ккал внутри круга. */
    /** Секунды -> "1 ч 52 мин" / "34 мин". */
    private fun fmtDur(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60
        return when {
            h > 0 -> "$h ч $m мин"
            m > 0 -> "$m мин"
            else -> "$sec сек"
        }
    }

    private fun refreshRing(steps: Int) {
        val (walk, run) = todayWalkRun()
        ring.setData(walk, run, goal)
        val pct = steps * 100 / goal
        // Бейдж ×N убран: слои теперь показывают точки в кольце (V9.13).
        goalView.text = "$pct% · цель ${"%,d".format(goal).replace(',', ' ')}"
        val activeSec = Stats.activeSeconds(this, walk, run)
        // Показываем при любой активности (>0), не только >=60 c - иначе
        // вечером при малом числе шагов строка была пустой (V9.14).
        activeTimeText.text = if (activeSec > 0) "\u23f1 ${fmtDur(activeSec)}" else ""
        val km = Stats.distanceKm(this, walk, run)
        val active = Stats.kcalActive(this, walk, run)
        val total = Stats.kcalGrossToday(this, walk, run)
        // Active = расход на движение; Total = Active + базовый обмен (BMR)
        // за прошедшую часть суток. Тап по строке -> объяснение (V9.10).
        todayKmKcal.text = if (km > 0 || active > 0)
            "%.2f км · %d актив · %d всего ккал  \u24d8".format(km, active, total)
        else ""
        todayKmKcal.setOnClickListener { showCalorieInfo(active, total) }
    }

    /** Диалог-объяснение Active/Basal/Total (V9.10). */
    private fun showCalorieInfo(active: Int, total: Int) {
        val basal = total - active
        val msg = """
            StepCore считает калории честно и разделяет их на две части:

            \u2022 АКТИВНЫЕ ($active ккал) — сожжено именно на движение, сверх покоя. Модель LCDA/Margaria учитывает скорость, каденс, вес и груз.

            \u2022 БАЗОВЫЙ ОБМЕН ($basal ккал) — сколько тело тратит просто на жизнь (дыхание, сердце) за прошедшую часть суток. Формула Mifflin\u2013St Jeor по весу, росту, возрасту, полу.

            \u2022 ВСЕГО ($total ккал) = активные + базовый обмен.

            Почему цифры отличаются от других приложений:
            — Xiaomi/Samsung показывают активные + покой только за время ходьбы.
            — Google Fit показывает ВСЕГО за сутки (близко к нашему «всего»).

            Для контроля дефицита ориентируйся на «всего»: это полный расход. Съедая меньше — теряешь вес.
        """.trimIndent()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Как считаются калории")
            .setMessage(msg)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun todayWalkRun(): Pair<Int, Int> {
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()
        return if (prefs.getString(StepService.KEY_DAY, "") == today)
            prefs.getInt(StepService.KEY_WALK, 0) to prefs.getInt(StepService.KEY_RUN, 0)
        else 0 to 0
    }

    /** Нижняя карточка: вчерашний день — шаги · км · ккал + разница. */
    private fun updateStats() {
        val (walk, run) = todayWalkRun()
        val sb = StringBuilder()
        if (yesterdayTotal >= 0) {
            val yKm = Stats.distanceKm(this, yWalk, yRun)
            val yKcal = Stats.kcal(this, yWalk, yRun)
            sb.append("ВЧЕРА · $yesterdayTotal шагов")
            if (yKm > 0) sb.append("\n%.2f км · %d ккал".format(yKm, yKcal))
            val diff = (walk + run) - yesterdayTotal
            sb.append("\n")
            sb.append(if (diff >= 0) "сегодня уже +$diff к вчера"
                      else "до вчера ещё ${-diff} шагов")
        } else {
            sb.append("Вчера нет данных")
        }
        statsView.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        goal = getSharedPreferences(StepService.PREFS, MODE_PRIVATE).getInt("p_goal", 10000)
        if (::ring.isInitialized) refreshRing(StepsState.steps.value)
        if (::statsView.isInitialized) updateStats()
    }

    /** Предупреждение о приватности + подсказка где мерить, затем permission. */
    private fun confirmAndStartGps() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Калибровка по GPS")
            .setMessage(
                "Включит GPS-приёмник на время замера. Координаты нужны только " +
                "для подсчёта метров — никуда не отправляются и не сохраняются, " +
                "StepCore остаётся офлайн.\n\n" +
                "Где мерить для точности:\n" +
                "• открытое небо (не между домами, не в лесу)\n" +
                "• прямой участок, обычный шаг\n" +
                "• идеально 300–500 м\n\n" +
                "GPS в помещении и у высоких зданий врёт."
            )
            .setPositiveButton("Начать") { _, _ ->
                gpsPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startGpsCalibration() {
        val cal = LocationCalibrator(this)
        if (!cal.isGpsEnabled()) {
            StepsState.calibrationState.value = "Включи GPS в настройках телефона"
            return
        }
        gpsCalibrator = cal
        gpsCalibrating = true
        gpsStepsAtStart = StepsState.steps.value
        calGpsBtn.text = "Готово (GPS)"
        cal.onUpdate = { metres, fixes, acc ->
            runOnUiThread {
                val accTxt = if (acc >= 0) "±${acc.toInt()}м" else "—"
                StepsState.calibrationState.value =
                    "GPS: %.0f м, точек %d, сигнал %s".format(metres, fixes, accTxt)
            }
        }
        cal.start()
        StepsState.calibrationState.value = "GPS: иди по прямой… (ждём сигнал)"
    }

    private fun finishGpsCalibration() {
        val cal = gpsCalibrator ?: return
        gpsCalibrating = false
        calGpsBtn.text = "Калибровка по GPS"
        val metres = cal.stop()
        gpsCalibrator = null
        val steps = StepsState.steps.value - gpsStepsAtStart
        if (metres < 100f || steps < 30) {
            StepsState.calibrationState.value =
                "Мало данных (%.0f м, %d шагов). Нужно ≥100 м на открытом небе.".format(metres, steps)
            return
        }
        StrideModel.applyCalibration(this, metres, steps, byGps = true)
        val slCm = StrideModel.measuredStrideCm(this) ?: 0
        StepsState.calibrationState.value =
            "Готово (GPS): %.0f м за %d шагов = длина шага %d см".format(metres, steps, slCm)
        refreshRing(StepsState.steps.value)
    }

    override fun onPause() {
        super.onPause()
        if (gpsCalibrating) { gpsCalibrator?.stop(); gpsCalibrating = false; gpsCalibrator = null }
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
