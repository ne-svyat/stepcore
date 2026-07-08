package com.vasil.stepcore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

class StepService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private val detector = StepDetector()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotifiedSteps = -1
    private var wakeLock: PowerManager.WakeLock? = null

    // Диагностика "почему не считает при выключенном экране":
    // тикер раз в 30 с. Задержка тикера = CPU спал (wakelock игнорируется).
    // Тикер жив, а событий сенсора нет = система усыпила сенсор.
    // Нет ни того ни другого в журнале при дыре в счёте = сервис был убит.
    @Volatile private var lastSensorEventMs = 0L

    // Гибрид: аппаратный TYPE_STEP_COUNTER (чип, считает при спящем CPU).
    // Пока наш детектор жив - его база догоняет аппаратный итог (delta=0).
    // После дыры в событиях акселерометра (сон/убийство) разница
    // база->итог досчитывается как шаги ходьбы.
    private var hwBaseline = -1L
    // зазор считаем ТОЛЬКО по акселерометру: события чипа/гироскопа
    // не должны маскировать дыру (баг V7.4)
    @Volatile private var lastAccelEventMs = 0L
    private var forceBackfill = false
    private var adoptBaselineOnce = false
    // Экран выключен: MIUI деградирует поток акселерометра (рваные пачки),
    // детектор на нём ложно уходит в TRANSPORT. Поэтому при выключенном
    // экране детектор отключается, считает аппаратный чип.
    @Volatile private var screenOff = false
    private var hwSessionAdded = 0
    // Диагностика транспорта (V8.10): что насчитал чип за эпизод блокировки
    @Volatile private var hwLastTotal = -1L
    private var hwAtTransportEnter = -1L
    private var renewalsAtEnter = 0
    private var transportEnterWallMs = 0L

    // Окно расхождения детектор/чип (V8.15, диагностика перед V9).
    // Живой факт 07.07: печать на экране дала детектору +200 при чипе +50.
    // Критерий расхождения обоснован, не подобран: реальная ходьба за
    // 2 мин = 150-250 шагов, чип отстаёт максимум на ~10 (придержка
    // старта серии) - соотношение ~1; тапы дают детектору десятки при
    // чипе ~0. Порог: детектор >= 20 И чип < половины детектора.
    private var divWindowStartMs = 0L
    private var divWindowDet = 0
    private var divWindowChipStart = -1L
    private var lastDivLogMs = 0L   // троттлинг: не чаще строки в 10 мин

    // V9.2: взаимная коррекция источников (данные 07.07):
    // тапы: детектор врёт / чип честен (0) -> чип считает (V9.0);
    // тряска: чип врёт (считает) / детектор честен (тряска x3 = 0 ложных)
    //   -> Guard 1: shake-вето детектора отбрасывает дельты чипа;
    // залипшая метка TRANSPORT при реальной ходьбе (вход N18: чип 21 шаг
    //   под меткой) -> Guard 2: чип >= 5 шагов под меткой снимает её.
    private var shakeGuardUntilElapsed = 0L
    private var transportChipAccum = 0
    // Сверка с чипом (V8.11): якорь чипа на начало дня.
    // Ворота решения V9 "чип считает всегда": N дней автоматических
    // сравнений вместо ручных вечерних записей.
    private var hwDayAnchor = -1L
    private var hwDayPaused = false   // был Стоп/перезагрузка - сверка дня неполная

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    hwSessionAdded = 0
                    divWindowStartMs = 0L   // окно расхождения только при экране вкл
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    if (hwSessionAdded > 0) {
                        logEvent("За время блокировки: $hwSessionAdded шагов (аппаратный чип)")
                    }
                    hwSessionAdded = 0
                    detector.resetTransient()
                    lastLoggedMode = "IDLE"
                    StepsState.mode.value = "IDLE"
                }
            }
        }
    }
    @Volatile private var sensorSilenceLogged = false
    private var currentDay: String = ""

    private var walkSteps = 0
    private var runSteps = 0
    private var stepsSinceDbWrite = 0
    // почасовой аккумулятор (батчится в БД вместе с persistDb)
    private var pendKey = ""
    private var pendW = 0
    private var pendR = 0

    private var lastLoggedMode = "IDLE"
    private var idleSinceMs = 0L

    private var calibrating: String? = null
    private val calIntervals = ArrayList<Long>()
    private var calLastStepMs = 0L
    // Калибровка дистанции (V9.3): якорь чипа + метраж отрезка.
    private var distCalActive = false
    private var distCalChipStart = -1L
    private var distCalMetres = 0f

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibrator = vm.defaultVibrator

        currentDay = LocalDate.now().toString()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_DAY, "") == currentDay) {
            walkSteps = prefs.getInt(KEY_WALK, 0)
            runSteps = prefs.getInt(KEY_RUN, 0)
            detector.restoreCount(walkSteps + runSteps)
        }
        StepsState.hapticEnabled.value = prefs.getBoolean("haptic", false)
        StepsState.detailLog.value = prefs.getBoolean("detail_log", false)
        loadProfile()
        StepsState.steps.value = walkSteps + runSteps

        createChannel()
        startForeground(NOTIF_ID, buildNotification(walkSteps + runSteps))

        // CPU не должен засыпать при выключенном экране, иначе
        // non-wakeup сенсоры перестают доставлять события и счёт стоит.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stepcore:steps")
            .apply { acquire() }

        // wakeup-вариант сенсора (true) как подстраховка; если его нет - обычный
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        detector.hasGyro = gyro != null
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
        hwBaseline = prefs.getLong(KEY_HW_BASE, -1L)
        hwDayAnchor =
            if (prefs.getString(KEY_HW_ANCHOR_DAY, "") == currentDay)
                prefs.getLong(KEY_HW_DAY_ANCHOR, -1L)
            else -1L   // чужой день - переякоримся на первом событии чипа
        hwDayPaused = prefs.getBoolean(KEY_HW_DAY_PAUSED, false)
        // если прошлая жизнь упала с исключением - имя в журнал
        prefs.getString(KEY_CRASH, null)?.let {
            logEvent("⚠ Падение сервиса: $it")
            prefs.edit().remove(KEY_CRASH).apply()
        }
        // Ручной Стоп = осознанная пауза: не «смерть» и без досчёта чипа
        val cleanStop = prefs.getBoolean(KEY_CLEAN_STOP, false)
        if (cleanStop) {
            prefs.edit().remove(KEY_CLEAN_STOP).apply()
            adoptBaselineOnce = true
        }
        val lastAlive = prefs.getLong(KEY_ALIVE, 0L)
        if (lastAlive > 0 && !cleanStop) {
            val deadSec = (System.currentTimeMillis() - lastAlive) / 1000
            if (deadSec > 60) {
                forceBackfill = true
                logEvent("⚠ Сервис был мёртв $deadSec с")
            }
        }
        // отметка «жив» СРАЗУ: жизнь короче 30 с не успевала записаться,
        // серия рестартов меряла смерть от древней метки и каждый раз
        // дёргала принудительный досчёт (шторм 06:35 в журнале)
        prefs.edit().putLong(KEY_ALIVE, System.currentTimeMillis()).apply()
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(
                    KEY_CRASH,
                    "${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull()}"
                ).commit()
            }
            prevHandler?.uncaughtException(t, e)
        }
        val hwCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (hwCounter != null) {
            sensorManager.registerListener(this, hwCounter, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            logEvent("⚠ Аппаратного счётчика шагов нет на устройстве")
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        screenOff = !pm.isInteractive

        StepsState.serviceRunning.value = true
        scope.launch {   // V9.6: автоочистка диаг-логов старше 14 дней
            val cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
            runCatching { AppDb.get(this@StepService).dao().purgeOldDiagLogs(cutoff) }
        }
        scope.launch {
            // V9.19: одноразовый бэкфилл снапшотов для закрытых дней,
            // созданных до V9.9. Без снапшота их калории/дистанция
            // пересчитывались по текущему профилю - смена веса или
            // калибровки переписывала прошлое. Замораживаем как есть
            // сейчас; дальше эти дни неизменны. Идемпотентно: после
            // записи kcalActive >= 0, повторно не подхватятся.
            runCatching {
                val dao = AppDb.get(this@StepService).dao()
                val today = LocalDate.now().toString()
                val pending = dao.daysWithoutSnapshot(today)
                pending.forEach { d ->
                    val (a2, b2, dist) = Stats.snapshotForDay(
                        this@StepService, d.walkSteps, d.runSteps)
                    dao.upsertDay(d.copy(kcalActive = a2, kcalBasal = b2, distanceM = dist))
                }
                if (pending.isNotEmpty())
                    logEvent("Заморожена статистика прошлых дней: ${pending.size}")
            }
        }

        scope.launch {
            while (true) {
                delay(1000)
                StepsState.diag.value =
                    "чистота %.0f%% | грязь %d | каденс %d | гиро %.2f"
                        .format(detector.cleanliness * 100, detector.rejectedNoisy,
                            detector.cadenceLockedSteps, detector.gyroRms)
            }
        }

        scope.launch {
            var stepsAtSnap = detector.stepCount
            var dropsAtSnap = detector.dropCount
            while (true) {
                delay(5000)
                if (!StepsState.detailLog.value || screenOff) {
                    // V9.1: при экране выкл детектор молчит - строка была бы
                    // копией протухшего снимка (12 одинаковых строк в логе 07.07)
                    stepsAtSnap = detector.stepCount; continue
                }
                val d = detector
                val dSteps = d.stepCount - stepsAtSnap
                val dDrops = d.dropCount - dropsAtSnap
                stepsAtSnap = d.stepCount; dropsAtSnap = d.dropCount
                val reason = if (dDrops > 0) " сброс${dDrops}:${d.lastDropReason}" else ""
                logEvent(
                    "[диаг] +${dSteps}ш ${d.mode.name} чист${(d.cleanliness * 100).toInt()}%% " +
                    "гиро%.2f фон%.1f инт${d.lastIntervalMs.toInt()}мс%s грязь${d.rejectedNoisy} кад${d.cadenceLockedSteps}"
                        .format(d.gyroRms, d.recentMean, reason)
                )
            }
        }

        scope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            lastSensorEventMs = lastTick
            while (true) {
                delay(HEARTBEAT_MS)
                val now = SystemClock.elapsedRealtime()
                val tickGap = now - lastTick
                if (tickGap > HEARTBEAT_MS + 15_000) {
                    logEvent("⚠ CPU спал ~${(tickGap - HEARTBEAT_MS) / 1000} с")
                }
                val silence = if (lastAccelEventMs == 0L) 0L else now - lastAccelEventMs
                if (silence > 60_000 && !sensorSilenceLogged) {
                    sensorSilenceLogged = true
                    logEvent("⚠ Датчик молчит ${silence / 1000} с (CPU жив)")
                }
                lastTick = now
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_ALIVE, System.currentTimeMillis()).apply()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAL_WALK -> startCalibration("walk")
            ACTION_CAL_RUN -> startCalibration("run")
            ACTION_CAL_STOP -> finishCalibration()
            ACTION_CAL_DIST_START -> startDistCal(intent.getFloatExtra(EXTRA_METRES, 0f))
            ACTION_CAL_DIST_STOP -> finishDistCal()
            ACTION_DIAG_START -> {
                detector.diagRecording = true
                StepsState.calibrationState.value = "Диагностика пишется — делай тест"
            }
            ACTION_DIAG_STOP -> finishDiag()
        }
        return START_STICKY
    }

    private fun finishDiag() {
        val samples = ArrayList(detector.diagSamples)
        detector.diagRecording = false
        if (samples.isEmpty()) {
            StepsState.calibrationState.value = "Диагностика: пиков не было"
            logEvent("Диагностика: пиков не было")
            return
        }
        fun col(i: Int): String {
            val v = samples.map { it[i] }.sorted()
            return "%.2f/%.2f/%.2f".format(v.first(), v[v.size / 2], v.last())
        }
        val ok = samples.count { it[4] > 0f }
        val line = "Диагностика: пиков ${samples.size}, принято $ok | " +
                "ампл ${col(0)} | фон ${col(1)} | крест ${col(2)} | гиро ${col(3)} " +
                "(мин/мед/макс)"
        logEvent(line)
        StepsState.calibrationState.value = "Диагностика записана в журнал"
    }

    private fun startCalibration(kind: String) {
        calibrating = kind
        calIntervals.clear()
        calLastStepMs = 0L
        StepsState.calibrationState.value = if (kind == "walk") "Калибровка: иди обычным шагом" else "Калибровка: беги"
    }

    private fun finishCalibration() {
        val kind = calibrating ?: return
        calibrating = null
        if (calIntervals.size < 10) {
            StepsState.calibrationState.value = "Мало данных (${calIntervals.size} шагов), профиль не изменён"
            return
        }
        val median = calIntervals.sorted()[calIntervals.size / 2]
        val lo = (median * 0.65).toLong()
        val hi = (median * 1.35).toLong()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("${kind}_min_interval", lo)
            .putLong("${kind}_max_interval", hi)
            .apply()
        loadProfile()
        StepsState.calibrationState.value =
            "Готово: твой ${if (kind == "walk") "шаг" else "бег"} = ${median} мс/шаг (диапазон $lo-$hi)"
    }

    /**
     * Старт калибровки дистанции: фиксируем показание чипа. Пользователь
     * идёт известный отрезок metres, чип считает шаги - в голове считать
     * не нужно. Финиш вычислит длину шага = metres / шаги.
     */
    private fun startDistCal(metres: Float) {
        if (metres <= 0f) {
            StepsState.calibrationState.value = "Укажи длину отрезка"
            return
        }
        distCalActive = true
        distCalMetres = metres
        distCalChipStart = hwLastTotal
        StepsState.calibrationState.value =
            "Калибровка дистанции: пройди ${metres.toInt()} м и нажми Готово"
    }

    private fun finishDistCal() {
        if (!distCalActive) return
        distCalActive = false
        val steps = if (distCalChipStart >= 0 && hwLastTotal >= 0)
            (hwLastTotal - distCalChipStart).toInt() else 0
        if (steps < 20) {
            StepsState.calibrationState.value =
                "Мало шагов ($steps) - калибровка не сохранена. Нужен отрезок подлиннее."
            return
        }
        StrideModel.applyCalibration(this, distCalMetres, steps)
        val slCm = StrideModel.measuredStrideCm(this) ?: 0
        StepsState.calibrationState.value =
            "Готово: ${distCalMetres.toInt()} м за $steps шагов = длина шага $slCm см"
    }

    private fun loadProfile() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val p = detector.profile
        p.walkMinIntervalMs = prefs.getLong("walk_min_interval", p.walkMinIntervalMs)
        p.walkMaxIntervalMs = prefs.getLong("walk_max_interval", p.walkMaxIntervalMs)
        p.runMinIntervalMs = prefs.getLong("run_min_interval", p.runMinIntervalMs)
        p.runMaxIntervalMs = prefs.getLong("run_max_interval", p.runMaxIntervalMs)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowRt = SystemClock.elapsedRealtime()
        lastSensorEventMs = nowRt
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (lastAccelEventMs > 0 && nowRt - lastAccelEventMs > 60_000) {
                logEvent("⚠ Акселерометр молчал ${(nowRt - lastAccelEventMs) / 1000} с")
            }
            lastAccelEventMs = nowRt
            sensorSilenceLogged = false
        }
        val timeMs = event.timestamp / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val hwTotal = event.values[0].toLong()
                hwLastTotal = hwTotal
                if (hwDayAnchor < 0) {                    // первый отсчёт дня
                    hwDayAnchor = hwTotal; persistHwAnchor()
                } else if (hwTotal < hwDayAnchor) {        // перезагрузка телефона
                    hwDayAnchor = hwTotal; hwDayPaused = true; persistHwAnchor()
                }
                // сброс после перезагрузки телефона (чип стартует с нуля)
                if (hwBaseline < 0 || hwTotal < hwBaseline) {
                    hwBaseline = hwTotal
                    persistHwBase()
                    return
                }
                if (adoptBaselineOnce) {
                    // после ручного Стопа: шаги чипа за паузу не добавляем
                    hwBaseline = hwTotal
                    persistHwBase()
                    adoptBaselineOnce = false
                    return
                }
                // ============ V9: ЧИП - ЕДИНСТВЕННЫЙ ИСТОЧНИК СЧЁТА ============
                // Данные 06-07.07: тапы по экрану надували детектор в 3-5 раз
                // (330 против 100 у чипа), при этом чип за все тап- и
                // транспорт-эпизоды дал 0 ложных шагов. Детектор больше не
                // считает - он классифицирует: каждая дельта чипа помечается
                // WALK/RUN по текущему режиму детектора. Счёт никогда не
                // блокируется (TRANSPORT - метка в журнале, не стоп-кран):
                // класс багов "ложный транспорт съел шаги" закрыт архитектурно.
                // Известная цена: чип придерживает первые ~10 шагов серии и
                // отдаёт пачкой; короткие проходки могут не засчитаться
                // (конституция: лучше недосчитать один, чем добавить десять).
                val delta = (hwTotal - hwBaseline).toInt()
                hwBaseline = hwTotal
                persistHwBase()
                if (delta <= 0) return
                if (!screenOff && SystemClock.elapsedRealtime() < shakeGuardUntilElapsed) {
                    // Guard 1: тряска - дельта чипа отбрасывается навсегда
                    logEvent("Тряска: отброшено $delta шагов чипа")
                    return
                }
                if (!screenOff && detector.mode == StepDetector.Mode.TRANSPORT) {
                    // Guard 2: чип идёт под меткой транспорта = человек идёт
                    transportChipAccum += delta
                    if (transportChipAccum >= TRANSPORT_DESTICK_STEPS) {
                        logEvent("Метка транспорта снята: чип насчитал " +
                                "$transportChipAccum шагов - человек идёт")
                        detector.resetTransient()
                        transportChipAccum = 0
                    }
                } else transportChipAccum = 0
                rolloverDayIfNeeded()
                val asRun = !screenOff && detector.mode == StepDetector.Mode.RUN
                if (asRun) { runSteps += delta; bumpHour(0, delta) }
                else { walkSteps += delta; bumpHour(delta, 0) }
                if (screenOff) hwSessionAdded += delta
                StepsState.steps.value = walkSteps + runSteps
                persistPrefs()
                stepsSinceDbWrite += delta
                if (stepsSinceDbWrite >= 25) { stepsSinceDbWrite = 0; persistDb() }
                if (walkSteps + runSteps - lastNotifiedSteps >= 10) {
                    lastNotifiedSteps = walkSteps + runSteps
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(walkSteps + runSteps))
                }
                return
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (screenOff) return
                if (calibrating == null) {
                    detector.onGyro(event.values[0], event.values[1], event.values[2], timeMs)
                }
                return
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (screenOff) return
                val added = detector.onAccel(
                    event.values[0], event.values[1], event.values[2], timeMs
                )
                updateModeWithHysteresis()
                if (detector.isShakeBlocked(timeMs)) {
                    // тряска активна: вето на дельты чипа + 4 c на лаг пачек
                    shakeGuardUntilElapsed =
                        SystemClock.elapsedRealtime() + SHAKE_CHIP_GRACE_MS
                }
                if (added > 0) {
                    trackDivergence(added)
                    if (calibrating != null) {
                        if (calLastStepMs > 0) calIntervals.add(timeMs - calLastStepMs)
                        calLastStepMs = timeMs
                    }
                    // V9: детектор НЕ считает - счёт ведёт чип (ветка
                    // TYPE_STEP_COUNTER). Здесь остаётся обратная связь:
                    // вибрация может тикнуть на ложный шаг (тап), но число
                    // от этого не вырастет. trackDivergence теперь охраняет
                    // обратный риск - недосчёт чипа при реальной ходьбе.
                    if (StepsState.hapticEnabled.value) {
                        vibrator.vibrate(VibrationEffect.createOneShot(50, 255))
                    }
                }
            }
        }
    }

    /**
     * Гистерезис для экрана и журнала:
     * - WALK/RUN показываются и логируются сразу;
     * - TRANSPORT логируется сразу ("Транспорт - шаги остановлены");
     * - IDLE - только после 4 с непрерывной паузы.
     */
    private fun updateModeWithHysteresis() {
        val m = detector.mode.name
        val now = System.currentTimeMillis()

        // V8.10: итог транспорт-эпизода — длительность, продления, счёт чипа
        if (lastLoggedMode == "TRANSPORT" && m != "TRANSPORT" && hwAtTransportEnter >= 0) {
            val chipDelta = if (hwLastTotal >= 0) hwLastTotal - hwAtTransportEnter else -1L
            val renews = detector.transportRenewals - renewalsAtEnter
            val durSec = (now - transportEnterWallMs) / 1000
            logEvent("Транспорт закончился: ${durSec} с, продлений $renews, чип за эпизод: $chipDelta шагов")
            hwAtTransportEnter = -1
        }

        if (m == "IDLE") {
            if (lastLoggedMode == "IDLE") { idleSinceMs = 0L; return }
            if (idleSinceMs == 0L) { idleSinceMs = now; return }
            if (now - idleSinceMs >= IDLE_LOG_DELAY_MS) {
                idleSinceMs = 0L
                lastLoggedMode = "IDLE"
                StepsState.mode.value = "IDLE"
                logEvent("Покой")
            }
            return
        }

        idleSinceMs = 0L
        if (m != lastLoggedMode) {
            lastLoggedMode = m
            StepsState.mode.value = m
            logEvent(
                when (m) {
                    "RUN" -> "Бег"
                    "WALK" -> "Ходьба"
                    "TRANSPORT" -> {
                        hwAtTransportEnter = hwLastTotal
                        renewalsAtEnter = detector.transportRenewals
                        transportEnterWallMs = now
                        "Транспорт (метка, счёт ведёт чип) [вход №${detector.transportEntries}, " +
                            "инт ${detector.lastTransportMeanMs.toInt()} мс, " +
                            "CV ${"%.2f".format(detector.lastTransportCv)}, " +
                            "чистота ${(detector.cleanliness * 100).toInt()}%]"
                    }
                    else -> m
                }
            )
        }
    }

    private fun logEvent(text: String) {
        val now = System.currentTimeMillis()
        val date = LocalDate.now().toString()
        scope.launch {
            AppDb.get(this@StepService).dao().addEvent(
                EventRecord(timeMs = now, date = date, text = text)
            )
        }
    }

    private fun rolloverDayIfNeeded() {
        val today = LocalDate.now().toString()
        if (today == currentDay) return
        persistDb()
        freezeDaySnapshot(currentDay, walkSteps, runSteps)  // V9.9
        logHwComparison("итог дня")
        hwDayAnchor = hwLastTotal
        hwDayPaused = false
        currentDay = today
        persistHwAnchor()
        walkSteps = 0; runSteps = 0
        detector.restoreCount(0)
    }

    /**
     * Диагностика V8.15: скользящее 2-минутное окно "прирост детектора
     * против прироста чипа" при включённом экране. Значимое расхождение
     * пишется в журнал - это поэпизодные данные для решения V9
     * (чип - источник счёта, детектор - классификатор).
     * Поведение счёта НЕ меняет: только наблюдение.
     */
    private fun trackDivergence(added: Int) {
        if (screenOff || hwLastTotal < 0) return
        val now = System.currentTimeMillis()
        if (divWindowStartMs == 0L || now - divWindowStartMs > DIV_WINDOW_MS * 3) {
            // старт нового окна (или окно протухло после паузы активности)
            divWindowStartMs = now
            divWindowDet = 0
            divWindowChipStart = hwLastTotal
        }
        divWindowDet += added
        if (now - divWindowStartMs < DIV_WINDOW_MS) return
        val chipDelta = (hwLastTotal - divWindowChipStart).toInt()
        if (divWindowDet >= DIV_MIN_DET && chipDelta * 2 < divWindowDet &&
            now - lastDivLogMs > DIV_LOG_THROTTLE_MS
        ) {
            lastDivLogMs = now
            logEvent("Расхождение за ${DIV_WINDOW_MS / 60000} мин: " +
                    "детектор +$divWindowDet, чип +$chipDelta (подозрение на ложные шаги)")
        }
        divWindowStartMs = 0L   // окно закрыто, следующее начнётся с нового шага
    }

    /**
     * Строка сверки в журнал: наш дневной счёт против дельты чипа за день.
     * Разница > нескольких % за полный день без пауз - материал решения V9.
     */
    private fun logHwComparison(tag: String) {
        if (hwDayAnchor < 0 || hwLastTotal < 0) return
        val chip = (hwLastTotal - hwDayAnchor).toInt()
        val own = walkSteps + runSteps
        if (chip <= 0 && own <= 0) return
        val diff = own - chip
        val pct = if (chip > 0) 100f * diff / chip else 0f
        val note = if (hwDayPaused) " · день с паузой/перезагрузкой, сверка неполная" else ""
        logEvent("Сверка [$tag]: StepCore $own · чип $chip · разница " +
                (if (diff >= 0) "+" else "") + "$diff (${"%.1f".format(pct)}%)$note")
    }

    /** То же, но синхронно: для onDestroy, где scope.cancel() убьёт launch. */
    private fun logHwComparisonBlocking(tag: String) {
        if (hwDayAnchor < 0 || hwLastTotal < 0) return
        val chip = (hwLastTotal - hwDayAnchor).toInt()
        val own = walkSteps + runSteps
        if (chip <= 0 && own <= 0) return
        val diff = own - chip
        val pct = if (chip > 0) 100f * diff / chip else 0f
        val note = if (hwDayPaused) " · день с паузой/перезагрузкой, сверка неполная" else ""
        val text = "Сверка [$tag]: StepCore $own · чип $chip · разница " +
                (if (diff >= 0) "+" else "") + "$diff (${"%.1f".format(pct)}%)$note"
        runBlocking {
            AppDb.get(this@StepService).dao().addEvent(
                EventRecord(timeMs = System.currentTimeMillis(),
                    date = LocalDate.now().toString(), text = text)
            )
        }
    }

    private fun persistHwAnchor() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_HW_DAY_ANCHOR, hwDayAnchor)
            .putString(KEY_HW_ANCHOR_DAY, currentDay)
            .putBoolean(KEY_HW_DAY_PAUSED, hwDayPaused)
            .apply()
    }

    private fun persistHwBase() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_HW_BASE, hwBaseline).apply()
    }

    private fun persistPrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_DAY, currentDay)
            .putInt(KEY_STEPS, walkSteps + runSteps)
            .putInt(KEY_WALK, walkSteps)
            .putInt(KEY_RUN, runSteps)
            .apply()
    }

    private fun hourKeyNow(): String {
        val n = java.time.LocalDateTime.now()
        return "%04d-%02d-%02d %02d".format(n.year, n.monthValue, n.dayOfMonth, n.hour)
    }

    private fun bumpHour(w: Int, r: Int) {
        val k = hourKeyNow()
        if (k != pendKey) { flushHour(); pendKey = k }
        pendW += w; pendR += r
    }

    private fun flushHour() {
        if (pendKey.isEmpty() || (pendW == 0 && pendR == 0)) return
        val k = pendKey; val w = pendW; val r = pendR
        pendW = 0; pendR = 0
        scope.launch {
            val dao = AppDb.get(this@StepService).dao()
            dao.ensureHour(k); dao.addHour(k, w, r)
        }
    }

    /**
     * V9.9: замораживает энергию/дистанцию закрываемого дня в DayRecord
     * с текущими параметрами. После этого смена веса не пересчитает день.
     */
    private fun freezeDaySnapshot(date: String, w: Int, r: Int) {
        val (active, basal, distM) = Stats.snapshotForDay(this, w, r)
        scope.launch {
            AppDb.get(this@StepService).dao()
                .upsertDay(DayRecord(date, w, r, active, basal, distM))
        }
    }

    private fun persistDb() {
        flushHour()
        val d = currentDay; val w = walkSteps; val r = runSteps
        scope.launch {
            AppDb.get(this@StepService).dao().upsertDay(DayRecord(d, w, r))
        }
    }

    /**
     * Синхронная запись для onDestroy: гарантирует, что почасовой хвост
     * (pendW/pendR, до 25 шагов) и дневная строка не потеряются от
     * scope.cancel(). Две вставки Room - миллисекунды, для завершения
     * сервиса допустимо.
     */
    private fun persistDbBlocking() {
        val k = pendKey; val w = pendW; val r = pendR
        pendW = 0; pendR = 0
        val d = currentDay; val dw = walkSteps; val dr = runSteps
        runBlocking {
            val dao = AppDb.get(this@StepService).dao()
            if (k.isNotEmpty() && (w > 0 || r > 0)) { dao.ensureHour(k); dao.addHour(k, w, r) }
            dao.upsertDay(DayRecord(d, dw, dr))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        runCatching { unregisterReceiver(screenReceiver) }
        logHwComparisonBlocking("стоп")
        hwDayPaused = true; persistHwAnchor()   // чип за паузу насчитает - пометить день
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_ALIVE, System.currentTimeMillis())
            .putBoolean(KEY_CLEAN_STOP, true).apply()
        wakeLock?.release(); wakeLock = null
        persistPrefs()
        persistDbBlocking()   // V8.12: scope.cancel() ниже убивает launch-записи
        StepsState.serviceRunning.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Подсчёт шагов", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(steps: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("StepCore работает")
            .setContentText("Шагов: $steps")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "stepcore_tracking"
        const val NOTIF_ID = 1
        const val PREFS = "stepcore"
        const val KEY_DAY = "day"
        const val KEY_STEPS = "steps"
        const val KEY_WALK = "walk_steps"
        const val KEY_RUN = "run_steps"
        const val KEY_HW_BASE = "hw_baseline"
        const val KEY_ALIVE = "last_alive_ms"
        const val KEY_CRASH = "last_crash"
        const val KEY_CLEAN_STOP = "clean_stop"
        const val KEY_HW_DAY_ANCHOR = "hw_day_anchor"
        const val KEY_HW_ANCHOR_DAY = "hw_anchor_day"
        const val KEY_HW_DAY_PAUSED = "hw_day_paused"
        const val DIV_WINDOW_MS = 120_000L  // окно сравнения детектор/чип
        const val DIV_MIN_DET = 20          // минимум шагов детектора для вывода
        const val DIV_LOG_THROTTLE_MS = 600_000L // журнал не чаще 1 строки / 10 мин
        const val SHAKE_CHIP_GRACE_MS = 4000L   // лаг пачек чипа после тряски
        const val TRANSPORT_DESTICK_STEPS = 5   // > придержки старта чипа (2-4)
        const val ACTION_CAL_WALK = "cal_walk"
        const val ACTION_CAL_RUN = "cal_run"
        const val ACTION_CAL_STOP = "cal_stop"
        const val ACTION_CAL_DIST_START = "cal_dist_start"
        const val ACTION_CAL_DIST_STOP = "cal_dist_stop"
        const val EXTRA_METRES = "metres"
        const val ACTION_DIAG_START = "diag_start"
        const val ACTION_DIAG_STOP = "diag_stop"
        private const val IDLE_LOG_DELAY_MS = 4000L
        private const val HEARTBEAT_MS = 30_000L
    }
}
