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

    private lateinit var statsView: TextView
    private lateinit var ring: ProgressRingView
    private lateinit var goalView: TextView
    private lateinit var activeTimeText: TextView
    private lateinit var accuracyBadge: TextView
    private lateinit var todayKmKcal: TextView
    private var yWalk = 0
    private var yRun = 0
    private var yesterdayTotal = -1
    // Полная запись вчера: нужна для СНАПШОТА калорий/дистанции (V9.9).
    // Вчерашний день закрыт -> его цифры заморожены и не должны
    // пересчитываться по текущему профилю при смене веса (V9.18).
    private var yRec: DayRecord? = null

    private var goal = 10000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val stepsView = findViewById<TextView>(R.id.stepsText)
        val statusView = findViewById<TextView>(R.id.statusText)
        val modeView = findViewById<TextView>(R.id.modeText)
        statsView = findViewById(R.id.statsText)
        ring = findViewById(R.id.progressRing)
        goalView = findViewById(R.id.goalText)
        activeTimeText = findViewById(R.id.activeTimeText)
        accuracyBadge = findViewById(R.id.accuracyBadge)
        accuracyBadge.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.calibrationButton).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        todayKmKcal = findViewById(R.id.todayKmKcal)
        val toggleBtn = findViewById<Button>(R.id.toggleButton)
        val historyBtn = findViewById<Button>(R.id.historyButton)
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
            if (d != null) {
                yRec = d
                yWalk = d.walkSteps; yRun = d.runSteps
                yesterdayTotal = d.walkSteps + d.runSteps
            }
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

    /** Бейдж точности данных -> ведёт на экран Калибровки (V10). */
    private fun refreshAccuracyBadge() {
        val pct = CalibrationRegistry.overallPercent(this)
        accuracyBadge.text = "Точность данных $pct% · настроить"
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
    /** Диалог-объяснение Active/Basal/Total. */
    private fun showCalorieInfo(active: Int, total: Int) {
        val basal = total - active
        // ВАЖНО: обычные строки, не raw (тройные кавычки) - в raw-строках
        // Kotlin не обрабатывает escape-последовательности, и \u-коды
        // печатались бы буквально (баг до V9.18). Символы вставлены
        // напрямую в UTF-8.
        val msg = "StepCore считает калории честно и разделяет их на две части:\n\n" +
            "• АКТИВНЫЕ ($active ккал) — сожжено именно на движение, сверх покоя. " +
            "Модель LCDA/Margaria учитывает скорость, каденс, вес и груз.\n\n" +
            "• ПОКОЙ ($basal ккал) — базовый расход организма: сердце, дыхание, тепло. " +
            "Тратится всегда, даже в полном покое. Формула Mifflin–St Jeor " +
            "по весу, росту, возрасту, полу.\n\n" +
            "• ВСЕГО ($total ккал) = активные + покой.\n\n" +
            "Почему цифры отличаются от других приложений:\n" +
            "— Xiaomi/Samsung показывают активные + покой только за время ходьбы.\n" +
            "— Google Fit показывает ВСЕГО за сутки (близко к нашему «всего»).\n\n" +
            "Для контроля дефицита ориентируйся на «всего»: это полный расход. " +
            "Съедая меньше — теряешь вес."
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

    /**
     * Нижняя карточка: вчерашний день. Калории и дистанция читаются из
     * СНАПШОТА закрытого дня (заморожены с параметрами того дня), а не
     * пересчитываются по текущему профилю - иначе смена веса переписала
     * бы вчерашнюю статистику (V9.18).
     */
    private fun updateStats() {
        val (walk, run) = todayWalkRun()
        val rec = yRec
        if (yesterdayTotal < 0 || rec == null) {
            statsView.text = "Вчера нет данных"
            statsView.setOnClickListener(null)
            return
        }
        val (yActive, yBasal) = Stats.kcalOfDay(this, rec)
        val yKm = Stats.distanceOfDayKm(this, rec)
        val ySec = Stats.activeSeconds(this, yWalk, yRun)
        val sb = StringBuilder()
        sb.append("ВЧЕРА · ${fmtNumM(yesterdayTotal)} шагов")
        sb.append("\n")
        sb.append("%.2f км · ⏱ ${fmtDur(ySec)}".format(yKm))
        sb.append("\n")
        sb.append("Активные $yActive · Покой $yBasal · Всего ${yActive + yBasal} ккал")
        val diff = (walk + run) - yesterdayTotal
        sb.append("\n")
        sb.append(if (diff >= 0) "сегодня уже +${fmtNumM(diff)} к вчера"
                  else "до вчера ещё ${fmtNumM(-diff)} шагов")
        statsView.text = sb.toString()
        statsView.setOnClickListener { showCalorieInfo(yActive, yActive + yBasal) }
    }

    private fun fmtNumM(n: Int) = "%,d".format(n).replace(',', ' ')

    override fun onResume() {
        super.onResume()
        goal = getSharedPreferences(StepService.PREFS, MODE_PRIVATE).getInt("p_goal", 10000)
        if (::accuracyBadge.isInitialized) refreshAccuracyBadge()
        if (::ring.isInitialized) refreshRing(StepsState.steps.value)
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
