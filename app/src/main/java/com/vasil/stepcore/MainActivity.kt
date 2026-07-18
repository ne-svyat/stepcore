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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startTracking()
        }

    private lateinit var ring: CrystalRingView
    private lateinit var goalView: TextView
    private lateinit var activeTimeText: TextView
    private lateinit var accuracyBadge: TextView
    private lateinit var cargoChip: TextView
    private lateinit var backpackView: BackpackView
    private lateinit var lapBadgeText: TextView
    private lateinit var walkShareText: TextView
    private lateinit var runShareText: TextView
    private lateinit var heroCompareText: TextView
    private lateinit var distanceValueText: TextView
    private lateinit var activeKcalValueText: TextView
    private lateinit var totalKcalValueText: TextView
    private lateinit var activeKcalChip: View
    private lateinit var totalKcalChip: View
    private lateinit var yesterdayCard: View
    private lateinit var yesterdayDetailsGroup: View
    private lateinit var ySteps: TextView
    private lateinit var yKm: TextView
    private lateinit var yTime: TextView
    private lateinit var yTotalKcal: TextView
    private lateinit var yBreakdown: TextView
    private lateinit var yDiff: TextView
    private lateinit var expeditionCard: View
    private lateinit var expeditionSeasonView: com.vasil.stepcore.survival.SeasonDiamondView
    private lateinit var expeditionDayText: TextView
    private lateinit var expeditionSubText: TextView
    private lateinit var todayCard: View
    private lateinit var tSteps: TextView
    private lateinit var tKm: TextView
    private lateinit var tTime: TextView
    private lateinit var tTotalKcal: TextView
    private lateinit var tBreakdown: TextView
    private lateinit var tToGoal: TextView
    private lateinit var calibrationAccuracyText: TextView

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
        ring = findViewById(R.id.progressRing)
        goalView = findViewById(R.id.goalText)
        activeTimeText = findViewById(R.id.activeTimeText)
        accuracyBadge = findViewById(R.id.accuracyBadge)
        cargoChip = findViewById(R.id.cargoChip)
        backpackView = findViewById(R.id.backpackView)
        // Груз меняют чаще, чем остальной профиль (взял рюкзак - снял), и
        // ради этого не должно требоваться заходить в Профиль.
        cargoChip.setOnClickListener { showCargoDialog() }
        backpackView.setOnClickListener { showCargoDialog() }
        lapBadgeText = findViewById(R.id.lapBadgeText)
        walkShareText = findViewById(R.id.walkShareText)
        runShareText = findViewById(R.id.runShareText)
        heroCompareText = findViewById(R.id.heroCompareText)
        distanceValueText = findViewById(R.id.distanceValueText)
        activeKcalValueText = findViewById(R.id.activeKcalValueText)
        totalKcalValueText = findViewById(R.id.totalKcalValueText)
        activeKcalChip = findViewById(R.id.activeKcalChip)
        totalKcalChip = findViewById(R.id.totalKcalChip)
        yesterdayCard = findViewById(R.id.yesterdayCard)
        yesterdayDetailsGroup = findViewById(R.id.yesterdayDetailsGroup)
        ySteps = findViewById(R.id.ySteps)
        yKm = findViewById(R.id.yKm)
        yTime = findViewById(R.id.yTime)
        yTotalKcal = findViewById(R.id.yTotalKcal)
        yBreakdown = findViewById(R.id.yBreakdown)
        yDiff = findViewById(R.id.yDiff)
        expeditionCard = findViewById(R.id.expeditionCard)
        expeditionSeasonView = findViewById(R.id.expeditionSeasonView)
        expeditionDayText = findViewById(R.id.expeditionDayText)
        expeditionSubText = findViewById(R.id.expeditionSubText)
        todayCard = findViewById(R.id.todayCard)
        tSteps = findViewById(R.id.tSteps)
        tKm = findViewById(R.id.tKm)
        tTime = findViewById(R.id.tTime)
        tTotalKcal = findViewById(R.id.tTotalKcal)
        tBreakdown = findViewById(R.id.tBreakdown)
        tToGoal = findViewById(R.id.tToGoal)
        applyDoodleStyle()
        expeditionCard.setOnClickListener {
            startActivity(Intent(this, com.vasil.stepcore.survival.SurvivalActivity::class.java))
        }

        calibrationAccuracyText = findViewById(R.id.calibrationAccuracyText)
        accuracyBadge.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<View>(R.id.calibrationButton).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        val toggleBtn = findViewById<Button>(R.id.toggleButton)

        // Кнопка перестаёт быть кнопкой и становится сигналом. Пока счёт не
        // идёт, она зелёная и пульсирует; забыть нажать её теперь невозможно.
        // Цена забывчивости - потерянные шаги, то есть единственное, ради чего
        // приложение существует.
        val pulseBg = PulseButtonDrawable(
            density = resources.displayMetrics.density,
            colorGo = ContextCompat.getColor(this, R.color.accent_green),
            colorStop = ContextCompat.getColor(this, R.color.accent_red_bright),
            fillGo = ContextCompat.getColor(this, R.color.surface_green),
            fillStop = ContextCompat.getColor(this, R.color.surface_red),
        )
        toggleBtn.background = pulseBg
        toggleBtn.stateListAnimator = null
        toggleBtn.elevation = 0f
        val historyBtn = findViewById<View>(R.id.historyButton)
        val hapticSwitch = findViewById<SwitchCompat>(R.id.hapticSwitch)
        val detailLogSwitch = findViewById<SwitchCompat>(R.id.detailLogSwitch)
        val toolsToggle = findViewById<TextView>(R.id.toolsToggle)
        val toolsContainer = findViewById<View>(R.id.toolsContainer)

        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        if (prefs.getString(StepService.KEY_DAY, "") == java.time.LocalDate.now().toString()) {
            StepsState.steps.value = prefs.getInt(StepService.KEY_STEPS, 0)
        }
        goal = prefs.getInt("p_goal", 10000)
        refreshCargoChip()

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
        findViewById<View>(R.id.profileButton).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Аналитика (V13.0.1): исправление после полевого теста - тап по
        // "⟲" на устройстве не срабатывал (переворот не воспроизвести в
        // песочнице без Android SDK, гипотеза не подтвердилась). Вместо
        // анимации - одна плитка с двумя ВСЕГДА видимыми зонами, каждая
        // ведёт на свой экран напрямую. Ноль анимационного риска.
        findViewById<View>(R.id.analyticsStatsRow).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        findViewById<View>(R.id.analyticsTimelineRow).setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }

        toolsToggle.setOnClickListener {
            val open = toolsContainer.visibility == View.VISIBLE
            toolsContainer.visibility = if (open) View.GONE else View.VISIBLE
            toolsToggle.text = if (open) "⚙  Инструменты  ▾" else "⚙  Инструменты  ▴"
        }

        // Метки местности (Сегмент 1): ручной уклон -> журнал + TerrainState.
        val inclineUpBtn = findViewById<TextView>(R.id.inclineUpButton)
        val inclineFlatBtn = findViewById<TextView>(R.id.inclineFlatButton)
        val inclineDownBtn = findViewById<TextView>(R.id.inclineDownButton)
        val inclineLabel = findViewById<TextView>(R.id.inclineLabel)
        inclineUpBtn.setOnClickListener { setIncline(TerrainState.Incline.UP) }
        inclineFlatBtn.setOnClickListener { setIncline(TerrainState.Incline.FLAT) }
        inclineDownBtn.setOnClickListener { setIncline(TerrainState.Incline.DOWN) }

        lifecycleScope.launch {
            val y = java.time.LocalDate.now().minusDays(1).toString()
            val d = AppDb.get(this@MainActivity).dao().day(y)
            if (d != null) {
                yRec = d
                yWalk = d.walkSteps; yRun = d.runSteps
                yesterdayTotal = d.walkSteps + d.runSteps
            }
            updateStats()
            refreshRing(StepsState.steps.value)
        }

        lifecycleScope.launch { refreshExpeditionCard() }

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
                // Дёшево - можно на каждый шаг: число и кристалл, без БД.
                launch {
                    var prev = -1
                    StepsState.steps.collect { s ->
                        stepsView.text = s.toString()
                        refreshRing(s)
                        // Число ТОЛКАЕТСЯ на каждом приросте: видно, что счётчик
                        // живой, без единой лишней надписи. Толчок короткий и
                        // мелкий - не отвлекает, но глаз его ловит.
                        if (prev in 0 until s) {
                            stepsView.animate().cancel()
                            stepsView.scaleX = 1.10f; stepsView.scaleY = 1.10f
                            stepsView.animate().scaleX(1f).scaleY(1f)
                                .setDuration(160).start()
                        }
                        prev = s
                    }
                }
                launch {
                    StepsState.serviceRunning.collect { running ->
                        statusView.text = if (running) "Считаю шаги" else "СЧЁТ НЕ ИДЁТ"
                        statusView.setTextColor(ContextCompat.getColor(
                            this@MainActivity,
                            if (running) R.color.text_dim else R.color.accent_red_bright,
                        ))
                        toggleBtn.text = if (running) "Стоп" else "Старт"
                        toggleBtn.setTextColor(ContextCompat.getColor(
                            this@MainActivity,
                            if (running) R.color.accent_red_bright else R.color.accent_green,
                        ))
                        pulseBg.setRunning(running)
                    }
                }
                launch {
                    StepsState.mode.collect { m -> applyModeBadge(modeView, m) }
                }
                launch { StepsState.diag.collect { findViewById<TextView>(R.id.diagText).text = it } }
                launch {
                    TerrainState.incline.collect { v ->
                        inclineLabel.text = when (v) {
                            TerrainState.Incline.UP -> "Уклон: в гору"
                            TerrainState.Incline.DOWN -> "Уклон: с горы"
                            else -> "Уклон: ровно"
                        }
                        inclineUpBtn.alpha = if (v == TerrainState.Incline.UP) 1f else 0.45f
                        inclineFlatBtn.alpha = if (v == TerrainState.Incline.FLAT) 1f else 0.45f
                        inclineDownBtn.alpha = if (v == TerrainState.Incline.DOWN) 1f else 0.45f
                    }
                }
                // Дорого (почасовой расчёт, БД) - по таймеру, не на каждый шаг.
                // 20 с незаметно глазу, нагрузка на порядок ниже (V11.1).
                launch {
                    while (true) {
                        refreshTodayEnergy()
                        updateStats()
                        delay(20_000)
                    }
                }
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
        // V13.0 фаза 3: та же цифра, короткая подпись прямо на плитке -
        // одно вычисление, два места отображения, расхождения быть не может.
        calibrationAccuracyText.text = "точность $pct%"
    }

    /** Груз-чип (V13.0): всегда на виду, чтобы не забыть включённым/выключенным. */
    private fun refreshCargoChip() {
        val load = getSharedPreferences(StepService.PREFS, MODE_PRIVATE).getFloat("p_load", 0f)
        if (::backpackView.isInitialized) backpackView.setLoad(load)
        if (load > 0f) {
            cargoChip.text = "Груз %.1f кг".format(load)
            // Цвет подписи повторяет цвет рюкзака - одна шкала, два места.
            val tone = when {
                load <= 5f -> R.color.accent_green
                load <= 10f -> R.color.accent_amber
                else -> R.color.accent_red
            }
            cargoChip.setTextColor(ContextCompat.getColor(this, tone))
        } else {
            cargoChip.text = "Груз выкл"
            cargoChip.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        }
    }

    /** Ввод груза прямо с главной: то же значение, что в Профиле. */
    private fun showCargoDialog() {
        val prefs = getSharedPreferences(StepService.PREFS, MODE_PRIVATE)
        val cur = prefs.getFloat("p_load", 0f)
        val dp = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "кг, например 6.5"
            if (cur > 0f) setText("%.1f".format(cur))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Груз в рюкзаке")
            .setMessage("Влияет на расход калорий. Прошлые дни не пересчитываются.")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = input.text.toString().replace(',', '.').toFloatOrNull()
                if (v == null || v < 0f || v > 60f) {
                    android.widget.Toast.makeText(
                        this, "Проверь груз: 0-60 кг", android.widget.Toast.LENGTH_SHORT).show()
                } else saveCargo(v)
            }
            .setNeutralButton("Выключить") { _, _ -> saveCargo(0f) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveCargo(kg: Float) {
        getSharedPreferences(StepService.PREFS, MODE_PRIVATE).edit()
            .putFloat("p_load", kg).apply()
        // Точка истории профиля - как при сохранении в Профиле. Без неё
        // сегментированный расчёт не узнает, с какого момента сменился груз.
        lifecycleScope.launch { ProfileHistory.record(this@MainActivity) }
        refreshCargoChip()
        updateStats()
    }

    /**
     * Кристалл + производные текстовые поля вокруг него (V13.0). Виток
     * (currentLap) читается из самого кристалла - единственный источник
     * подсчёта, бейдж не пересчитывает его заново.
     */
    private fun refreshRing(steps: Int) {
        val (walk, run) = todayWalkRun()
        ring.setData(walk, run, goal, yesterdayTotal)
        val pct = steps * 100 / goal
        goalView.text = "$pct% · цель ${"%,d".format(goal).replace(',', ' ')}"
        walkShareText.text = "${fmtNumM(walk)} ходьба"
        runShareText.text = "${fmtNumM(run)} бег"

        val lap = ring.currentLap()
        if (lap >= 1) {
            lapBadgeText.text = "⚡ ×$lap цели"
            lapBadgeText.visibility = View.VISIBLE
        } else {
            lapBadgeText.visibility = View.GONE
        }

        heroCompareText.text = when {
            yesterdayTotal < 0 -> ""
            steps >= yesterdayTotal -> "▲ выше вчерашнего уровня"
            else -> "▼ ниже вчерашнего уровня"
        }
    }

    /**
     * Дистанция/ккал/активное время за сегодня - СЕГМЕНТИРОВАННО (V11.1):
     * каждый прошедший час считается с профилем, который действовал в тот
     * час (груз, вес, калибровка). Смена груза больше не переписывает
     * прошлое. Ходит в БД - поэтому по таймеру, а не на каждый шаг.
     *
     * V13.0: раньше эти три числа теснились ОДНОЙ строкой внутри кольца и
     * переполняли его при росте значений. Теперь три раздельных чипа вне
     * кристалла - переполнение физически невозможно, там просто нет
     * ограниченного контура.
     */
    private suspend fun refreshTodayEnergy() {
        val (walk, run) = todayWalkRun()
        val activeSec = Stats.activeSeconds(this, walk, run)
        activeTimeText.text = if (activeSec > 0) fmtDur(activeSec) else "0 мин"
        val (active, distM) = Stats.segmentedActiveAndDistance(
            this, java.time.LocalDate.now().toString())
        val km = distM / 1000f
        val total = active + Stats.kcalBasalToday(this)
        // Active = расход на движение; Total = Active + базовый обмен (BMR)
        // за прошедшую часть суток. Тап по чипу -> объяснение (V9.10).
        distanceValueText.text = "%.2f км".format(km)
        activeKcalValueText.text = "$active ккал"
        totalKcalValueText.text = "$total ккал"
        // Карточка СЕГОДНЯ - те же посчитанные цифры, второго расчёта нет.
        val basal = total - active
        tSteps.text = "${fmtNumM(walk + run)} шагов"
        tKm.text = "%.2f км".format(km)
        tTime.text = if (activeSec > 0) fmtDur(activeSec) else "0 мин"
        tTotalKcal.text = "$total ккал всего"
        tBreakdown.text = "актив $active · покой $basal"
        val left = goal - (walk + run)
        tToGoal.text = if (left > 0) "ещё ${fmtNumM(left)} до цели"
                       else "цель взята"
        todayCard.setOnClickListener { showCalorieInfo(active, total) }
        val openInfo = View.OnClickListener { showCalorieInfo(active, total) }
        activeKcalChip.setOnClickListener(openInfo)
        totalKcalChip.setOnClickListener(openInfo)
    }

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
     * Карточка «ВЧЕРА» (V13.0): те же данные, что были в статичной строке
     * ДО этого релиза (шаги, км, время, актив/покой/всего ккал, сравнение
     * с сегодня) - просто разложены по отдельным полям вместо одного
     * абзаца. Калории и дистанция читаются из СНАПШОТА закрытого дня
     * (заморожены с параметрами того дня), а не пересчитываются по
     * текущему профилю - иначе смена веса переписала бы вчерашнюю
     * статистику (V9.18).
     */
    private fun updateStats() {
        val (walk, run) = todayWalkRun()
        val rec = yRec
        if (yesterdayTotal < 0 || rec == null) {
            ySteps.text = "нет данных"
            yesterdayDetailsGroup.visibility = View.GONE
            yesterdayCard.setOnClickListener(null)
            return
        }
        val (yActive, yBasal) = Stats.kcalOfDay(this, rec)
        val yKmVal = Stats.distanceOfDayKm(this, rec)
        // V11.9: время вчера - из СНАПШОТА, как калории. Не пересчитывается
        // текущей калибровкой.
        val ySec = Stats.activeSecOfDay(this, rec)
        ySteps.text = "${fmtNumM(yesterdayTotal)} шагов"
        yKm.text = "%.2f км".format(yKmVal)
        yTime.text = fmtDur(ySec)
        yTotalKcal.text = "${yActive + yBasal} ккал"
        yBreakdown.text = "актив $yActive · покой $yBasal ккал"
        val diff = (walk + run) - yesterdayTotal
        yDiff.text = if (diff >= 0) "сегодня уже +${fmtNumM(diff)} к вчера"
                     else "до вчера ещё ${fmtNumM(-diff)} шагов"
        yesterdayDetailsGroup.visibility = View.VISIBLE
        yesterdayCard.setOnClickListener { showCalorieInfo(yActive, yActive + yBasal) }
    }

    /**
     * Дудл-оформление (V14.0): рамки "от руки" вместо ровных shape-drawable
     * и декоративные сцены. Ставится из кода, а не в разметке, потому что
     * у каждой рамки свой сид - иначе все карточки кривились бы ОДИНАКОВО,
     * что мгновенно выдаёт машину. Разные сиды = как нарисовано от руки
     * несколько раз.
     */
    private fun applyDoodleStyle() {
        val d = resources.displayMetrics.density
        fun col(id: Int) = ContextCompat.getColor(this, id)
        fun frame(v: View, stroke: Int, fill: Int, seed: Long,
                  mat: Int = DoodleBorderDrawable.MAT_ROCK) {
            v.background = DoodleBorderDrawable(col(stroke), col(fill), seed, d, mat)
        }
        frame(findViewById(R.id.yesterdayCard), R.color.accent_violet, R.color.surface_violet, 11L,
            DoodleBorderDrawable.MAT_LIGHTNING)
        frame(todayCard, R.color.accent_green, R.color.surface_green, 22L,
            DoodleBorderDrawable.MAT_LIGHTNING)
        frame(expeditionCard, R.color.accent_amber, R.color.surface_amber, 33L,
            DoodleBorderDrawable.MAT_FIRE)
        frame(findViewById(R.id.profileButton), R.color.accent_blue, R.color.surface_blue, 44L)
        frame(findViewById(R.id.calibrationButton), R.color.accent_violet, R.color.surface_violet, 55L)
        frame(findViewById(R.id.historyButton), R.color.axis_dim, R.color.surface, 66L)
        frame(findViewById(R.id.analyticsTile), R.color.accent_green, R.color.surface_green, 77L)
        // Чипы дня - тот же язык, цвет по смыслу: путь синий, время
        // бирюзовое, горение янтарное, суммарный расход фиолетовый.
        frame(findViewById(R.id.distanceChip), R.color.accent_blue, R.color.surface, 101L)
        frame(findViewById(R.id.activeTimeChip), R.color.accent_green, R.color.surface, 102L)
        frame(findViewById(R.id.activeKcalChip), R.color.accent_amber, R.color.surface, 103L)
        frame(findViewById(R.id.totalKcalChip), R.color.accent_violet, R.color.surface, 104L)
        // Грани уклона: цвет по смыслу - тёплый вверх, нейтраль, холодный вниз.
        frame(findViewById(R.id.inclineUpButton), R.color.accent_amber, R.color.surface_amber, 201L)
        frame(findViewById(R.id.inclineFlatButton), R.color.axis_dim, R.color.surface, 202L)
        frame(findViewById(R.id.inclineDownButton), R.color.accent_green, R.color.surface_green, 203L)

        findViewById<DoodleSceneView>(R.id.headerScene).setScene(DoodleSceneView.HEADER)
        findViewById<DoodleSceneView>(R.id.nightScene).setScene(DoodleSceneView.NIGHT)
        findViewById<DoodleSceneView>(R.id.dayScene).setScene(DoodleSceneView.DAY)
        findViewById<DoodleSceneView>(R.id.expeditionScene).setScene(DoodleSceneView.EXPEDITION)
    }

    private fun fmtNumM(n: Int) = "%,d".format(n).replace(',', ' ')

    /**
     * Карточка экспедиции (V13.0, фаза 2). Читает survival.db ТОЛЬКО НА
     * ЧТЕНИЕ через тот же публичный SurvivalRepo, что использует сам
     * режим - никакой отдельной логики чтения здесь нет, один источник
     * истины. Обёрнуто в try/catch намеренно: принцип изоляции режима
     * (сбой Survival Mode не должен ронять главный экран шагомера) -
     * при любой ошибке карточка просто показывает нейтральное состояние
     * вместо падения.
     */
    private suspend fun refreshExpeditionCard() {
        try {
            val repo = com.vasil.stepcore.survival.SurvivalRepo(this)
            val active = repo.active()
            if (active != null) {
                // Сезон тем же способом, что и сам экран экспедиции: тик 0 -
                // стартовый сезон как есть, иначе прогон движка до ticksDone.
                val season = if (active.ticksDone == 0) active.startSeason
                    else com.vasil.stepcore.survival.engine.SurvivalEngine(
                        active.seed, active.startSeason, active.startOffset
                    ).seasonOf(active.ticksDone)
                expeditionSeasonView.setSeason(season)
                expeditionDayText.text = "День ${active.ticksDone} из ${active.plannedDays}"
                expeditionSubText.text = "Северная тайга · " +
                    com.vasil.stepcore.survival.engine.SurvivalEngine.SEASON_RU[season]
            } else {
                expeditionSeasonView.setSeason(-1)
                expeditionDayText.text = "Нет активной экспедиции"
                expeditionSubText.text = "Начать →"
            }
        } catch (e: Exception) {
            expeditionSeasonView.setSeason(-1)
            expeditionDayText.text = "Экспедиция"
            expeditionSubText.text = "Открыть"
        }
    }

    override fun onResume() {
        super.onResume()
        goal = getSharedPreferences(StepService.PREFS, MODE_PRIVATE).getInt("p_goal", 10000)
        if (::accuracyBadge.isInitialized) refreshAccuracyBadge()
        if (::cargoChip.isInitialized) refreshCargoChip()
        if (::ring.isInitialized) refreshRing(StepsState.steps.value)
        updateStats()
        if (::expeditionCard.isInitialized) lifecycleScope.launch { refreshExpeditionCard() }
    }

    /**
     * Ручная метка уклона (Сегмент 1). Пишет строку в журнал и обновляет
     * TerrainState. Пока НЕ влияет на ккал (это Сегмент 2). Значение
     * транзиентно: при перезапуске сбрасывается на FLAT.
     */
    private fun setIncline(v: TerrainState.Incline) {
        if (TerrainState.incline.value == v) return
        TerrainState.incline.value = v
        val text = when (v) {
            TerrainState.Incline.UP -> "Уклон: в гору"
            TerrainState.Incline.DOWN -> "Уклон: с горы"
            else -> "Уклон: ровно"
        }
        lifecycleScope.launch {
            AppDb.get(this@MainActivity).dao().addEvent(
                EventRecord(
                    timeMs = System.currentTimeMillis(),
                    date = java.time.LocalDate.now().toString(),
                    text = text,
                )
            )
        }
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
