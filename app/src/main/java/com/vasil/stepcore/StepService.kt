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
import android.graphics.drawable.Icon
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
    private val features = FeatureCollector()
    private val shakeHold = ShakeHold()
    private var lastSampleChip = -1L
    private var l1Logged = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotifiedSteps = -1
    private var wakeLock: PowerManager.WakeLock? = null

    // v191: датчики движения держим в полях - их приходится
    // подписывать и отписывать по ходу жизни, а не один раз при старте.
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var hwDetSensor: Sensor? = null
    private var motionRegistered = false


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
                    updateMotionSensors()
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
                    features.reset()
                    lastLoggedMode = "IDLE"
                    StepsState.mode.value = "IDLE"
                    updateMotionSensors()
                }
            }
        }
    }
    @Volatile private var sensorSilenceLogged = false
    private var currentDay: String = ""

    private var walkSteps = 0
    private var runSteps = 0
    private var stepsSinceDbWrite = 0
    private var samplesSinceStep = 0
    private var sampleCountSession = 0
    // Прореживание корпуса уклона: 1 образец на N подтверждённых шагов.
    private val terrainSampleEvery = 20
    private var chipSinceSample = 0
    /** До какого момента собирать признаки из-за свежей метки (v190). */
    private var labelWindowUntilElapsed = 0L

    /**
     * L1.1: окно фоновой обработки.
     *
     * BG_WINDOW_MS из каждых BG_PERIOD_MS. Фаза считается от часов
     * загрузки, состояния не требует и переживает любые перезапуски.
     *
     * Откуда числа. Снизу окно ограничено тем, сколько нужно, чтобы
     * статистика имела смысл: коллектор требует 100 отсчётов (около 2 с)
     * и меряет каденс по окну; 12 с дают около 600 отсчётов и полтора
     * десятка шагов - этого хватает и на амплитуду, и на ритм. Сверху -
     * долей времени: 12 из 60 это пятая часть, то есть плата
     * процессором впятеро меньше непрерывной обработки.
     * Период в минуту согласован с тем, как часто вообще нужна строка
     * корпуса: она пишется раз в 10-20 шагов, то есть раз в 5-10 секунд
     * ходьбы, и одного окна в минуту заведомо достаточно.
     */
    /**
     * Окно сбора признаков при выключенном экране.
     *
     * Два независимых основания:
     *  - окно метки: человек только что нажал уклон, значит следующие
     *    минуты размечены его рукой и стоят дороже всего. Работает
     *    ВСЕГДА, даже если фоновый сбор выключен - иначе кнопки в шторке
     *    давали бы калории, но не корпус;
     *  - duty-цикл: BG_WINDOW_MS из каждых BG_PERIOD_MS, и только при
     *    включённом флаге, цена которого по батарее ещё не измерена.
     */
    /**
     * v191: нужны ли сейчас датчики движения и бодрый процессор.
     *
     * Три основания, любого достаточно:
     *  - экран включён: человек смотрит, детектор обязан работать;
     *  - включён фоновый сбор: это его объявленная цена, флаг для того и
     *    сделан, чтобы её можно было измерить;
     *  - открыто окно свежей метки уклона: человек только что сказал
     *    «здесь склон», две минуты процессора того стоят.
     *
     * Во всех остальных случаях телефон спит, а шаги считает чип.
     */
    private fun motionNeeded(): Boolean =
        !screenOff || StepsState.bgAccel.value ||
            SystemClock.elapsedRealtime() < labelWindowUntilElapsed

    /**
     * Приводит подписку на датчики и wakelock к нужному состоянию.
     * Идемпотентно: повторный вызов в том же состоянии ничего не делает.
     *
     * Чип (TYPE_STEP_COUNTER) здесь НЕ упоминается намеренно - он
     * подписан всегда и остаётся единственным счётчиком.
     */
    private fun updateMotionSensors() {
        val need = motionNeeded()
        if (need == motionRegistered) return
        if (need) {
            accelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            gyroSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            // STEP_DETECTOR нужен только как диагностика при калибровке, а
            // она идёт при включённом экране. В фоне он висел на FASTEST
            // и будил процессор впустую.
            hwDetSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } else {
            accelSensor?.let { sensorManager.unregisterListener(this, it) }
            gyroSensor?.let { sensorManager.unregisterListener(this, it) }
            hwDetSensor?.let { sensorManager.unregisterListener(this, it) }
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        motionRegistered = need
    }

    private fun inBgWindow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < labelWindowUntilElapsed) return true
        return StepsState.bgAccel.value && now % BG_PERIOD_MS < BG_WINDOW_MS
    }


    /**
     * v187: когда детектор в последний раз подтвердил шаг (часы с
     * загрузки - те же, что у сенсорных меток времени).
     */
    private var lastConfirmedElapsed = 0L

    /**
     * Сколько времени доверие детектора остаётся в силе.
     *
     * Откуда число: дельты чипа приходят пачками примерно раз в 10 с,
     * поэтому первая дельта под тряской может прийти уже через 10-12 с
     * после её начала. Во всех семи эпизодах 19.07 детектор подтверждал
     * ходьбу не более чем за 5 с до тряски. 15 с покрывает и лаг пачек,
     * и разброс, но не превращается в бессрочный кредит.
     */
    private val carryTrustMs = 15_000L

    /**
     * Шаг прореживания корпуса по каналу чипа. При включённой подробной
     * диагностике пишем вдвое плотнее: это режим исследования, его
     * включают осознанно и ненадолго, и цена (строка ~200 байт на
     * 10 шагов) заведомо меньше цены непонятой прогулки.
     */
    private fun chipSampleEvery(): Int =
        if (StepsState.detailLog.value) 10 else terrainSampleEvery

    // почасовой аккумулятор (батчится в БД вместе с persistDb)
    private var pendKey = ""
    private var pendW = 0
    private var pendR = 0
    private var pendUp = 0
    private var pendDown = 0

    private var lastLoggedMode = "IDLE"
    private var idleSinceMs = 0L

    private var calibrating: String? = null
    private val calIntervals = ArrayList<Long>()
    // Диагностика V11.12: амплитуда удара и фон гироскопа НА КАЖДЫЙ принятый
    // шаг калибровки, параллельно calIntervals. По этим данным проектируется
    // различение бег/ходьба (по темпу у этого пользователя они неразличимы).
    private val calAmps = ArrayList<Float>()
    private val calGyros = ArrayList<Float>()
    private var calLastStepMs = 0L
    private var calUiTick = 0   // троттлинг живого прогресса, V11.4
    private var calRejected = 0 // отброшено мусорных интервалов, V11.5
    private var calReadyBuzzed = false // сигнал готовности уже дан, V11.6
    // Диагностика STEP_DETECTOR (V11.8): сырые интервалы его событий во время
    // калибровки. На этом устройстве (MIUI) сенсор отдал walk=774, run=775,
    // разброс 0% - метки времени ставятся при ДОСТАВКЕ пачки, а не при шаге.
    // Копим и пишем в журнал, чтобы решать про карман/бег по данным.
    private val hwDetDiag = ArrayList<Long>()
    private var hwDetLastMs = 0L
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
        StepsState.bgAccel.value = prefs.getBoolean("bg_accel", false)
        StepsState.detailLog.value = prefs.getBoolean("detail_log", false)
        loadProfile()
        StepsState.steps.value = walkSteps + runSteps

        createChannel()
        startForeground(NOTIF_ID, buildNotification(walkSteps + runSteps))

        // v191: wakelock больше не берётся навсегда. Им управляет
        // updateMotionSensors: он нужен только тогда, когда мы реально
        // обрабатываем движение. Счёт шагов от него не зависит - его
        // ведёт чип, который считает и при спящем процессоре.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stepcore:steps")

        // v191: ОБЫЧНЫЕ, не wakeup-версии. Раньше здесь стоял
        // getDefaultSensor(type, true) - wakeup-сенсор будит процессор на
        // каждой порции данных, и при 50 Гц телефон не спал никогда.
        // Измерено: 20% батареи в час на лежащем телефоне.
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        detector.hasGyro = gyroSensor != null
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
        // TYPE_STEP_DETECTOR: один аппаратный импульс на КАЖДЫЙ шаг, с меткой
        // времени, в реальном времени. Тот же чип, что и STEP_COUNTER (та же
        // надёжность в кармане и на бегу), но вместо "сколько всего" даёт
        // "вот шаг, вот когда". Нужен ТОЛЬКО для калибровки темпа (V11.7):
        // раньше темп мерился по детектору-на-акселерометре, который врёт в
        // кармане и выдаёт бег пачками - отсюда "карман 50%, бег не калибруется".
        // Вне калибровки события игнорируются (см. onSensorChanged), счёт
        // по-прежнему только на STEP_COUNTER.
        hwDetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        // v191: подписка отдана updateMotionSensors. В фоне этот сенсор
        // не нужен: он лишь диагностика при калибровке, а она идёт при
        // включённом экране. На SENSOR_DELAY_FASTEST он будил процессор.
        updateMotionSensors()
        if (hwDetSensor == null) {
            logEvent("⚠ Аппаратного детектора шагов нет - калибровка темпа по акселерометру")
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
                    val (a2, b2, dist) = Stats.snapshotForDaySegmented(
                        this@StepService, d.date, d.walkSteps, d.runSteps)
                    val aSec2 = Stats.segmentedActiveSeconds(
                        this@StepService, d.date, d.walkSteps, d.runSteps).toInt()
                    dao.upsertDay(d.copy(kcalActive = a2, kcalBasal = b2,
                        distanceM = dist, activeSec = aSec2))
                }
                if (pending.isNotEmpty())
                    logEvent("Заморожена статистика прошлых дней: ${pending.size}")
            }
        }

        scope.launch {
            while (true) {
                delay(1000)
                StepsState.diag.value =
                    "чистота %.0f%% | грязь %d | каденс %d | гиро %.2f | обр %d"
                        .format(detector.cleanliness * 100, detector.rejectedNoisy,
                            detector.cadenceLockedSteps, detector.gyroRms, sampleCountSession)
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
                StepsState.diagRecording.value = true
                StepsState.calibrationState.value = "Диагностика пишется — делай тест"
            }
            ACTION_DIAG_STOP -> finishDiag()
            ACTION_RECONCILE -> logHwComparison("сейчас")
            ACTION_INCLINE_UP -> applyIncline(TerrainState.Incline.UP, true)
            ACTION_INCLINE_FLAT -> applyIncline(TerrainState.Incline.FLAT, true)
            ACTION_INCLINE_DOWN -> applyIncline(TerrainState.Incline.DOWN, true)
        }
        return START_STICKY
    }

    private fun finishDiag() {
        val samples = ArrayList(detector.diagSamples)
        detector.diagRecording = false
        StepsState.diagRecording.value = false
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
        calAmps.clear()
        calGyros.clear()
        calLastStepMs = 0L
        calUiTick = 0
        calRejected = 0
        calReadyBuzzed = false
        hwDetDiag.clear()
        hwDetLastMs = 0L
        // Ключевой вывод из реальных замеров (V11.6): когда пользователь
        // смотрит в экран, он подстраивает шаг под цифру и разброс скачет до
        // 18%. Смотрит на дорогу - идёт естественно, разброс 3%. Экран сам
        // портит то, что измеряет. Поэтому обратная связь тут ТАКТИЛЬНАЯ:
        // тик на шаг, двойной сигнал на готовность. Смотреть в телефон не надо.
        StepsState.calibrationState.value = if (kind == "walk")
            "Калибровка ходьбы: смотри на дорогу, иди обычным шагом. Телефон тикнет дважды, когда готово."
        else
            "Калибровка бега: беги на улице в своём темпе. Телефон тикнет дважды, когда готово."
    }

    /**
     * Живая обратная связь при калибровке темпа, V11.4. Раньше экран молчал
     * до нажатия "Готово" - в отличие от GPS-калибровки длины шага, которая
     * пишет прогресс прямо на ходу.
     *
     * Показываем медиану и межквартильный разброс - ровно те числа, по которым
     * потом строится профиль. Пользователь видит то, что получит, а не догадку.
     * Разброс важнее счётчика: медиана из рваного ритма бесполезна, а по одному
     * лишь числу шагов этого не понять.
     *
     * Сортировка идёт раз в CAL_UI_EVERY шагов, не на каждом: onSensorChanged -
     * горячий путь, лишней работы на нём быть не должно.
     */
    /**
     * Сбор ОДНОГО интервала калибровки, V11.5. Раньше в выборку летел любой
     * added>0 - и это завышало разброс на ровном пути:
     *
     *   - детектор подтверждает шаги ПАЧКОЙ (выход из карантина, added=4):
     *     все с одним timeMs. Сырой сбор писал один интервал вместо четырёх,
     *     а следующий реальный растягивался на всю пачку -> выброс;
     *   - после паузы (IDLE, потеря ритма) calLastStepMs помнил старый шаг,
     *     и в статистику попадал интервал в 2-3 секунды посреди ходьбы.
     *
     * Оба - не про походку, а про то, что интервал брался не оттуда. Лечение:
     * учитывать только ОДИНОЧНЫЙ шаг (added==1) и только физиологически
     * правдоподобный интервал. Границы абсолютные (200..2000 мс), НЕ из
     * профиля: калибровать шаг по старому же профилю шага - замкнутый круг,
     * кривая калибровка заворачивала бы починку. 200..2000 покрывает всё от
     * быстрого бега до очень медленной ходьбы, режется только явный мусор.
     * Реальную вариативность шага НЕ трогаем - иначе подделаем разброс.
     *
     * Пачку не выбрасываем целиком: она сдвигает опору calLastStepMs, чтобы
     * следующий одиночный шаг мерился от верного момента, но сама в выборку
     * не идёт.
     */
    private fun collectCalInterval(kind: String, added: Int, timeMs: Long) {
        if (calLastStepMs > 0 && added == 1) {
            val iv = timeMs - calLastStepMs
            if (iv in CAL_MIN_STEP_MS..CAL_MAX_STEP_MS) {
                calIntervals.add(iv)
                calAmps.add(detector.lastStepAmp)
                calGyros.add(detector.gyroRms)
                // Тихий тик "шаг зачтён" - чтобы пользователь понимал, что
                // калибровка идёт, НЕ глядя в экран. Слабее обычной haptic.
                vibrator.vibrate(VibrationEffect.createOneShot(CAL_TICK_MS, CAL_TICK_AMP))
                maybeSignalReady()
            } else calRejected++
        }
        calLastStepMs = timeMs
        calUiTick++
        if (calUiTick == 1 || calUiTick % CAL_UI_EVERY == 0) publishCalProgress(kind)
    }

    /**
     * Двойной сигнал "можно завершать", один раз за сессию. Условие строже,
     * чем просто "хватит шагов": нужен ровный ритм (иначе медиана ненадёжна).
     * Это тактильный аналог "ритм ровный · можно завершать", но пользователю
     * не нужно смотреть в экран, чтобы это увидеть.
     */
    private fun maybeSignalReady() {
        if (calReadyBuzzed || calIntervals.size < CAL_READY_STEPS) return
        val sorted = calIntervals.sorted()
        val n = sorted.size
        val median = sorted[n / 2]
        if (median <= 0) return
        val spreadPct = (100L * (sorted[n * 3 / 4] - sorted[n / 4]) / median).toInt()
        if (spreadPct > CAL_SPREAD_OK_PCT) return
        calReadyBuzzed = true
        vibrator.vibrate(VibrationEffect.createWaveform(CAL_READY_PATTERN, -1))
    }

    private fun publishCalProgress(kind: String) {
        val n = calIntervals.size
        val label = if (kind == "walk") "Ходьба" else "Бег"
        if (n < MIN_CAL_INTERVALS) {
            StepsState.calibrationState.value =
                "$label: ${n + 1} шагов · нужно ещё ${MIN_CAL_INTERVALS - n}"
            return
        }
        val sorted = calIntervals.sorted()
        val median = sorted[n / 2]
        val spreadPct =
            if (median > 0) (100L * (sorted[n * 3 / 4] - sorted[n / 4]) / median).toInt() else 0
        val rhythm = when {
            spreadPct <= CAL_SPREAD_GOOD_PCT -> "ритм ровный"
            spreadPct <= CAL_SPREAD_OK_PCT -> "ритм неровный"
            else -> "ритм рваный, иди спокойнее"
        }
        val noise = if (calRejected > 0) " · отброшено $calRejected" else ""
        StepsState.calibrationState.value =
            "$label: чистых шагов $n · темп $median мс · $rhythm$noise · можно завершать"
    }

    private fun finishCalibration() {
        val kind = calibrating ?: return
        calibrating = null
        // V11.8: сырьё обоих источников в журнал - по нему решаем про
        // карман/бег. Автоочистка [диаг] через 14 дней штатная.
        if (hwDetDiag.isNotEmpty()) {
            logEvent("[диаг] кал.$kind STEP_DETECTOR (${hwDetDiag.size}): " +
                hwDetDiag.joinToString(","))
        }
        if (calIntervals.isNotEmpty()) {
            logEvent("[диаг] кал.$kind акселерометр (${calIntervals.size}): " +
                calIntervals.joinToString(","))
            // Тройки мс/амплитуда/гиро - главные данные для различения
            // бег/ходьба. Индексы совпадают с calIntervals.
            logEvent("[диаг] кал.$kind шаги мс/амп/гиро: " +
                calIntervals.indices.joinToString(" ") { i ->
                    "%d/%.1f/%.1f".format(calIntervals[i],
                        calAmps.getOrElse(i) { 0f }, calGyros.getOrElse(i) { 0f })
                })
        }
        if (calIntervals.size < MIN_CAL_INTERVALS) {
            StepsState.calibrationState.value =
                "Мало данных (${calIntervals.size + 1} шагов), профиль не изменён"
            return
        }
        val sorted = calIntervals.sorted()
        val n = sorted.size
        val median = sorted[n / 2]
        val spreadPct =
            if (median > 0) (100L * (sorted[n * 3 / 4] - sorted[n / 4]) / median).toInt() else 0
        // V11.14: та же проверка разброса, что решает про двойной сигнал
        // готовности (CAL_SPREAD_OK_PCT), теперь стоит и на сохранении.
        //
        // Найдено разбором реальной сессии пользователя: калибровка ходьбы
        // шла ~84 с без остановки, вобрала и обычный шаг, и случайные более
        // быстрые куски (переход дороги и т.п.) в ОДНУ корзину. Сбор шагов
        // не смотрит на текущий режим детектора - только на границы
        // CAL_MIN/MAX_STEP_MS, поэтому смешение возможно физически всегда.
        // Экран честно показывал "ритм рваный" (разброс 37% > 25%), но
        // сохранение проверяло только КОЛИЧЕСТВО шагов, не КАЧЕСТВО ритма -
        // медиана из смеси легла между двумя кластерами, а диапазон
        // ±35% от неё (339-706 мс) наехал на диапазон бега (251-522 мс).
        // Раздельные режимы после такого физически невозможны - это не
        // баг классификации, это испорченный вход.
        //
        // Отказ от сохранения плохих данных - принцип StepCore напрямую
        // (ARCHITECTURE_RULES: "лучше не посчитать один шаг, чем добавить
        // десять ложных"): лучше не откалибровать, чем откалибровать лживо.
        if (spreadPct > CAL_SPREAD_OK_PCT) {
            StepsState.calibrationState.value =
                "Ритм слишком нестабильный ($spreadPct%), профиль не изменён. " +
                "Похоже, в выборку попал разный темп (например, часть шагов " +
                "быстрее обычного). Пройди/пробеги ${MIN_CAL_INTERVALS}+ шагов " +
                "БЕЗ остановок и ускорений, одним ровным темпом."
            return
        }
        val lo = (median * 0.65).toLong()
        val hi = (median * 1.35).toLong()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("${kind}_min_interval", lo)
            .putLong("${kind}_max_interval", hi)
            .apply()
        loadProfile()
        scope.launch { ProfileHistory.record(this@StepService) }   // V11
        CalibrationRegistry.markDone(this,
            if (kind == "walk") CalibrationRegistry.Kind.WALK_TEMPO
            else CalibrationRegistry.Kind.RUN_TEMPO)
        StepsState.calibrationState.value =
            "Готово: твой ${if (kind == "walk") "шаг" else "бег"} = $median мс/шаг " +
            "по ${n + 1} шагам · разброс $spreadPct% · диапазон $lo-$hi"
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
        scope.launch { ProfileHistory.record(this@StepService) }   // V11
        CalibrationRegistry.markDone(this, CalibrationRegistry.Kind.STRIDE)
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
            Sensor.TYPE_STEP_DETECTOR -> {
                // V11.8: НЕ источник калибровки. Гипотеза V11.7 провалена на
                // устройстве: MIUI отдаёт события пачками по ~750 мс, метка
                // времени = момент доставки, не шага (walk==run, разброс 0%).
                // Оставлен только как диагностика: сырые интервалы уходят в
                // журнал при завершении калибровки.
                if (calibrating != null) {
                    val t = event.timestamp / 1_000_000L
                    if (hwDetLastMs > 0 && hwDetDiag.size < HW_DET_DIAG_CAP) {
                        hwDetDiag.add(t - hwDetLastMs)
                    }
                    hwDetLastMs = t
                }
                return
            }
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
                var delta = (hwTotal - hwBaseline).toInt()
                hwBaseline = hwTotal
                persistHwBase()
                if (delta <= 0) return
                val nowElapsed = SystemClock.elapsedRealtime()
                if (!screenOff && nowElapsed < shakeGuardUntilElapsed) {
                    // Guard 1 (v184): КАРАНТИН ВМЕСТО РАССТРЕЛА.
                    // Раньше дельта отбрасывалась навсегда, и телефон в
                    // кармане при включённом экране терял всё: 728 шагов
                    // за 6 минут (журнал 19.07). Порогом гироскопа это не
                    // лечится - карман 2.1-4.7 перекрывается с тряской
                    // 3.6-8.0. Разделяет ровность темпа чипа; решение
                    // вынесено в ShakeHold, где пороги измерены.
                    // v187: локомоция уже доказана детектором - не заставляем
                    // доказывать заново. Правило ровности продолжает
                    // работать и оборвёт счёт, когда наберётся материал.
                    if (!shakeHold.isConfirmed && lastConfirmedElapsed > 0L &&
                        nowElapsed - lastConfirmedElapsed <= carryTrustMs
                    ) {
                        if (shakeHold.carryOver()) {
                            logEvent("Тряска: ходьба уже подтверждена детектором, счёт открыт сразу")
                        }
                    }
                    val v = shakeHold.onShakenDelta(nowElapsed, delta)
                    v.reason?.let { logEvent("Тряска: " + it) }
                    if (v.discarded > 0) {
                        logEvent("Тряска: отброшено ${v.discarded} шагов чипа")
                    }
                    if (v.release <= 0) return
                    delta = v.release
                } else if (shakeHold.heldSteps > 0) {
                    // Тряска кончилась, ритм так и не подтвердился -
                    // отбрасываем, ровно как до v184.
                    val lost = shakeHold.onShakeEnded()
                    logEvent("Тряска кончилась: отброшено $lost шагов чипа")
                }
                if (!screenOff && detector.mode == StepDetector.Mode.TRANSPORT) {
                    // Guard 2: чип идёт под меткой транспорта = человек идёт
                    transportChipAccum += delta
                    if (transportChipAccum >= TRANSPORT_DESTICK_STEPS) {
                        logEvent("Метка транспорта снята: чип насчитал " +
                                "$transportChipAccum шагов - человек идёт")
                        detector.resetTransient()
                        features.reset()
                        transportChipAccum = 0
                    }
                } else transportChipAccum = 0
                rolloverDayIfNeeded()
                val asRun = !screenOff && detector.mode == StepDetector.Mode.RUN
                if (asRun) { runSteps += delta; bumpHour(0, delta) }
                else { walkSteps += delta; bumpHour(delta, 0) }
                if (screenOff) hwSessionAdded += delta
                // v185: корпус в кармане. Детектор здесь молчит (вето по
                // гироскопу), но метка уклона, оси гироскопа, наклон
                // телефона и амплитуда из сырого канала существуют и без
                // него. Без этой ветки они были бы потеряны.
                if (detector.mode != StepDetector.Mode.WALK &&
                    detector.mode != StepDetector.Mode.RUN) {
                    chipSinceSample += delta
                    if (chipSinceSample >= chipSampleEvery()) {
                        chipSinceSample = 0
                        writeTerrainSample(detector.mode.name, 0f, 0f, source = 1)
                    }
                }
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
                if (screenOff) {
                    // L1.1: в фоне оси гироскопа нужны корпусу - именно они
                    // отличают карман от руки. Детектор не трогаем.
                    if (inBgWindow() && calibrating == null) {
                        features.onGyro(
                            event.values[0], event.values[1], event.values[2], timeMs
                        )
                    }
                    return
                }
                if (calibrating == null) {
                    detector.onGyro(event.values[0], event.values[1], event.values[2], timeMs)
                    features.onGyro(event.values[0], event.values[1], event.values[2], timeMs)
                }
                return
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (screenOff) {
                    // L1.1: детектор в фоне НЕ работает и работать не
                    // должен - он заморожен, а его поведение проверено
                    // только при живом экране. Кормим один сборщик
                    // признаков, и то окнами (см. inBgWindow).
                    if (inBgWindow()) {
                        features.onAccel(
                            event.values[0], event.values[1], event.values[2], timeMs
                        )
                    }
                    return
                }
                val added = detector.onAccel(
                    event.values[0], event.values[1], event.values[2], timeMs
                )
                // Сырой канал: не зависит ни от вето по тряске, ни от
                // режима, ни от карантина детектора - поэтому работает и
                // в кармане, где детектор молчит. Гравитацию коллектор
                // с v189 считает сам: в фоне детекторная была бы протухшей.
                features.onAccel(
                    event.values[0], event.values[1], event.values[2], timeMs
                )
                updateModeWithHysteresis()
                // L1: границу серии задаёт детектор своим уходом в IDLE -
                // у него для этого уже есть выверенный таймаут. Своего
                // порога тишины коллектор не заводит. Вызов идемпотентен.
                if (detector.mode == StepDetector.Mode.IDLE) features.breakSeries()
                if (detector.isShakeBlocked(timeMs)) {
                    // тряска активна: вето на дельты чипа + 4 c на лаг пачек
                    shakeGuardUntilElapsed =
                        SystemClock.elapsedRealtime() + SHAKE_CHIP_GRACE_MS
                }
                if (added > 0) {
                    trackDivergence(added)
                    lastConfirmedElapsed = SystemClock.elapsedRealtime()
                    // L1: один вызов на событие, а не на каждый из added.
                    // Пачка >1 приходит только при выходе из карантина, то
                    // есть в начале серии: чётность может стартовать со
                    // сдвигом, но асимметрия сравнивает корзины между собой,
                    // и обмен корзин местами её величину не меняет.
                    features.onStep(detector.smoothedAmp, detector.lastIntervalMs, timeMs)
                    // Сегмент 3: прореженный сбор помеченного корпуса уклона.
                    samplesSinceStep += added
                    if (samplesSinceStep >= terrainSampleEvery) {
                        samplesSinceStep = 0
                        val sm = detector.mode
                        if (sm == StepDetector.Mode.WALK || sm == StepDetector.Mode.RUN) {
                            writeTerrainSample(
                                sm.name, detector.smoothedAmp,
                                detector.lastIntervalMs, source = 0)
                        }
                    }
                    // V11.8: калибровка темпа ВОЗВРАЩЕНА на детектор-акселерометр.
                    // STEP_DETECTOR на этом устройстве непригоден (см. ветку выше).
                    // В руке акселерометр честен: разброс 3-7% по замерам V11.6.
                    // Карман и бег - открытая проблема, решение по диаг-данным.
                    val calKind = calibrating
                    if (calKind != null) collectCalInterval(calKind, added, timeMs)
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

    /**
     * Единственное место, где рождается строка корпуса (v185).
     *
     * source = 0 - шаги подтвердил детектор: amp и intervalMs измерены.
     * source = 1 - детектор молчал (карман, вето по гироскопу), счёт вёл
     *              чип. amp/intervalMs НЕ измерялись и пишутся нулями;
     *              честную амплитуду и каденс несут accRms/accP90/
     *              zcrCadence из независимого канала. Флаг обязателен:
     *              без него обучение приняло бы нули за измерение.
     */
    /**
     * v188: в журнале "-" вместо нуля, когда признака нет. Ноль обязан
     * означать измеренный ноль, а не отсутствие измерения - это правило
     * корпуса, и в журнале оно должно соблюдаться тоже.
     */
    private fun one(v: Float?): String = if (v == null) "-" else v.toInt().toString()
    private fun two(v: Float?): String = if (v == null) "-" else "%.2f".format(v)

    private fun writeTerrainSample(mode: String, amp: Float, interval: Float, source: Int) {
        // v186: часы для проверки протухания обязаны совпадать с теми, по
        // которым коллектор получает отсчёты. Внутрь идёт
        // event.timestamp / 1e6, то есть время с загрузки, а не
        // стенные часы: сравнивать с currentTimeMillis нельзя.
        val fx = features.snapshot(SystemClock.elapsedRealtime())
        // v188: строка без признаков бесполезна и вводит в заблуждение.
        // Так выглядели первые образцы после старта сервиса: метка есть,
        // буфер акселерометра ещё не набрал двух секунд, все сенсорные
        // поля null. Канал чипа без признаков не пишем вовсе.
        if (source == 1 && fx.accRms == null) return
        val chipD = if (hwLastTotal >= 0 && lastSampleChip >= 0)
            (hwLastTotal - lastSampleChip).toInt() else null
        if (hwLastTotal >= 0) lastSampleChip = hwLastTotal
        val sample = TerrainSample(
            timeMs = System.currentTimeMillis(),
            label = TerrainState.incline.value.name,
            mode = mode,
            amp = amp,
            intervalMs = interval,
            gyro = detector.gyroRms,
            featureVersion = FeatureCollector.FEATURE_VERSION,
            pitchDeg = fx.pitchDeg,
            rollDeg = fx.rollDeg,
            gyroX = fx.gyroX,
            gyroY = fx.gyroY,
            gyroZ = fx.gyroZ,
            ampEvenMed = fx.ampEvenMed,
            ampOddMed = fx.ampOddMed,
            intervalEvenMed = fx.intervalEvenMed,
            intervalOddMed = fx.intervalOddMed,
            ampMed = fx.ampMed,
            ampIqr = fx.ampIqr,
            intervalMed = fx.intervalMed,
            intervalIqr = fx.intervalIqr,
            windowN = fx.windowN,
            seriesSteps = fx.seriesSteps,
            seriesMs = fx.seriesMs,
            screenOn = !screenOff,
            chipDelta = chipD,
            accRms = fx.accRms,
            accP90 = fx.accP90,
            accMax = fx.accMax,
            zcrCadence = fx.zcrCadence,
            sampleHz = fx.sampleHz,
            sampleSource = source,
        )
        if (!l1Logged) {
            l1Logged = true
            logEvent(
                "[диаг] корпус живой: накл " +
                one(fx.pitchDeg) + "/" + one(fx.rollDeg) +
                ", ампл " + two(fx.accRms) +
                ", кад " + two(fx.zcrCadence) +
                ", Гц " + one(fx.sampleHz) +
                ", ист " + source
            )
        }
        sampleCountSession++
        scope.launch { AppDb.get(this@StepService).dao().insertSample(sample) }
    }

    private fun bumpHour(w: Int, r: Int) {
        val k = hourKeyNow()
        if (k != pendKey) { flushHour(); pendKey = k }
        pendW += w; pendR += r
        // Сегмент 2: атрибуция шагов текущему уклону (read-only метка из UI).
        val d = w + r
        when (TerrainState.incline.value) {
            TerrainState.Incline.UP -> pendUp += d
            TerrainState.Incline.DOWN -> pendDown += d
            else -> {}
        }
    }

    private fun flushHour() {
        if (pendKey.isEmpty() || (pendW == 0 && pendR == 0)) return
        val k = pendKey; val w = pendW; val r = pendR; val up = pendUp; val down = pendDown
        pendW = 0; pendR = 0; pendUp = 0; pendDown = 0
        scope.launch {
            val dao = AppDb.get(this@StepService).dao()
            dao.ensureHour(k); dao.addHour(k, w, r, up, down)
        }
    }

    /**
     * Замораживает энергию/дистанцию закрываемого дня в DayRecord. После
     * этого смена веса не пересчитает день (V9.9).
     *
     * V11.2: считает ПОЧАСОВО, каждый час со своим профилем из истории.
     * Раньше брался профиль на момент полуночи и применялся ко всем шагам
     * суток - день замерзал с неверной цифрой навсегда.
     */
    private fun freezeDaySnapshot(date: String, w: Int, r: Int) {
        scope.launch {
            val (active, basal, distM) =
                Stats.snapshotForDaySegmented(this@StepService, date, w, r)
            // V11.9: активное время замораживается вместе с калориями -
            // новая калибровка темпа больше не переписывает прошлые дни.
            val aSec = Stats.segmentedActiveSeconds(this@StepService, date, w, r)
            AppDb.get(this@StepService).dao()
                .upsertDay(DayRecord(date, w, r, active, basal, distM, aSec.toInt()))
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
        val k = pendKey; val w = pendW; val r = pendR; val up = pendUp; val down = pendDown
        pendW = 0; pendR = 0; pendUp = 0; pendDown = 0
        val d = currentDay; val dw = walkSteps; val dr = runSteps
        runBlocking {
            val dao = AppDb.get(this@StepService).dao()
            if (k.isNotEmpty() && (w > 0 || r > 0)) { dao.ensureHour(k); dao.addHour(k, w, r, up, down) }
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
        val nm = getSystemService(NotificationManager::class.java)
        // Настройки канала неизменяемы после создания: менять видимость у
        // существующего бесполезно, система проигнорирует. Поэтому старый
        // канал удаляется, а уведомление переезжает в новый.
        runCatching { nm.deleteNotificationChannel(CHANNEL_ID_OLD) }
        val ch = NotificationChannel(
            CHANNEL_ID, "Подсчёт шагов", NotificationManager.IMPORTANCE_LOW
        ).apply {
            // Без этого система прячет уведомление с экрана блокировки -
            // а метку уклона надо ставить, НЕ разблокируя телефон.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * Единственное место, где меняется метка уклона (v190).
     *
     * Раньше состояние и запись в журнал делал экран. С появлением кнопок
     * в шторке источников стало бы два, и они разъехались бы на первой же
     * правке. Теперь авторитет один - сервис.
     */
    private fun applyIncline(v: TerrainState.Incline, fromShade: Boolean) {
        // Нажатие той же метки - не событие, но окно сбора продлеваем:
        // человек подтверждает, что участок тот же.
        labelWindowUntilElapsed = SystemClock.elapsedRealtime() + LABEL_WINDOW_MS
        // Окно открылось - датчики нужны прямо сейчас, даже если экран
        // погашен. И нужен таймер, который погасит их обратно: без него
        // wakelock остался бы висеть до включения экрана.
        updateMotionSensors()
        scope.launch {
            kotlinx.coroutines.delay(LABEL_WINDOW_MS + 1_000L)
            updateMotionSensors()
        }
        if (TerrainState.incline.value == v) return
        TerrainState.incline.value = v
        val name = when (v) {
            TerrainState.Incline.UP -> "в гору"
            TerrainState.Incline.DOWN -> "с горы"
            else -> "ровно"
        }
        logEvent("Уклон: " + name + (if (fromShade) " (шторка)" else ""))
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(walkSteps + runSteps))
    }

    private fun buildNotification(steps: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val inc = TerrainState.incline.value
        val incName = when (inc) {
            TerrainState.Incline.UP -> "в гору"
            TerrainState.Incline.DOWN -> "с горы"
            else -> "ровно"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("StepCore работает")
            // Текущая метка видна прямо в шторке: иначе, нажимая кнопки
            // не глядя на экран, невозможно понять, что сейчас стоит.
            .setContentText("Шагов: " + steps + " · уклон: " + incName)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pi)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Цвет всей полосы уведомления по текущей метке: покрасить
            // кнопки по отдельности Android не позволяет, такого API нет.
            // setColorized действует только у уведомлений переднего
            // сервиса - у нас как раз оно.
            .setColorized(true)
            .setColor(
                getColor(
                    when (inc) {
                        TerrainState.Incline.UP -> R.color.accent_amber
                        TerrainState.Incline.DOWN -> R.color.accent_green
                        else -> R.color.surface2
                    }
                )
            )
            .addAction(inclineAction(TerrainState.Incline.UP, "▲ В гору", 11))
            .addAction(inclineAction(TerrainState.Incline.FLAT, "━ Ровно", 12))
            .addAction(inclineAction(TerrainState.Incline.DOWN, "▼ С горы", 13))
            .build()
    }

    /**
     * Кнопка метки в уведомлении.
     *
     * requestCode у каждой свой: одинаковый код с одинаковыми флагами
     * отдал бы один и тот же PendingIntent на все три кнопки - дефект,
     * на котором горят регулярно. Action тоже различается, так что
     * защита двойная.
     *
     * getForegroundService, а не getService: уведомление живёт только
     * при работающем переднем сервисе, и это честное объявление намерения.
     *
     * Активная метка отмечена точкой - при узкой шторке текст обрезается,
     * а точка видна всегда.
     */
    private fun inclineAction(
        v: TerrainState.Incline, title: String, req: Int
    ): Notification.Action {
        val act = when (v) {
            TerrainState.Incline.UP -> ACTION_INCLINE_UP
            TerrainState.Incline.DOWN -> ACTION_INCLINE_DOWN
            else -> ACTION_INCLINE_FLAT
        }
        val pi = PendingIntent.getForegroundService(
            this, req,
            Intent(this, StepService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mark = if (TerrainState.incline.value == v) "• " else ""
        return Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_directions),
            mark + title, pi
        ).build()
    }

    companion object {
        const val CHANNEL_ID = "stepcore_tracking_v2"
        /** Канал до v192: создавался без lockscreenVisibility и
         *  прятался с экрана блокировки. Настройки канала после
         *  создания неизменяемы, поэтому заведён новый, а этот
         *  удаляется. */
        const val CHANNEL_ID_OLD = "stepcore_tracking"
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
        /** v188: печать сверки с чипом по требованию, без остановки счёта. */
        const val ACTION_RECONCILE = "reconcile"
        /** v190: метка уклона со шторки. Три отдельных действия, а не
         *  одно с параметром: PendingIntent различаются по action, и
         *  так исключён классический дефект «все кнопки делают одно». */
        const val ACTION_INCLINE_UP = "incline_up"
        const val ACTION_INCLINE_FLAT = "incline_flat"
        const val ACTION_INCLINE_DOWN = "incline_down"
        /** Сколько собирать признаки после нажатия метки. Две минуты:
         *  строка корпуса пишется раз в 10-20 шагов, то есть раз в
         *  5-10 секунд ходьбы - за окно набирается около двух десятков
         *  строк именно этого участка. Больше не нужно, меньше - мало
         *  для медиан и разбросов.
         */
        const val LABEL_WINDOW_MS = 120_000L
        /** L1.1: период и длительность окна фоновой обработки. */
        const val BG_PERIOD_MS = 60_000L
        const val BG_WINDOW_MS = 12_000L
        // Калибровка темпа. MIN_CAL_INTERVALS оставлен прежним, 10: менять
        // порог выборки надо по собранным данным, а не по ощущению.
        private const val MIN_CAL_INTERVALS = 10
        // Живой прогресс печатаем не на каждый шаг: onSensorChanged горячий,
        // sorted на нём - лишняя работа. Раз в 4 шага глазу достаточно.
        private const val CAL_UI_EVERY = 4
        // Межквартильный разброс к медиане, проценты. Ориентир от детектора:
        // он считает ритм стабильным при отклонении интервалов до 25% от
        // среднего. IQR теснее размаха, поэтому пороги ниже.
        private const val CAL_SPREAD_GOOD_PCT = 12
        private const val CAL_SPREAD_OK_PCT = 25
        // Абсолютные границы правдоподобного человеческого шага, мс. НЕ из
        // профиля (иначе калибровка зависела бы от прежней калибровки).
        // 200 мс = 5 шагов/с (спринт), 2000 мс = очень медленный шаг.
        // Диагностика STEP_DETECTOR: потолок выборки, чтобы длинная
        // калибровка не раздувала память и строку журнала.
        private const val HW_DET_DIAG_CAP = 300
        private const val CAL_MIN_STEP_MS = 200L
        private const val CAL_MAX_STEP_MS = 2000L
        // Тактильная калибровка (V11.6). Тик слабее обычной haptic (255),
        // чтобы не сбивать с шага - лёгкое подтверждение, а не удар.
        private const val CAL_TICK_MS = 25L
        private const val CAL_TICK_AMP = 90
        // Готовность: 20 чистых интервалов при ровном шаге ~510 мс это ~10 с.
        // Больше 10 (прежний минимум) - медиана заметно устойчивее, а десять
        // секунд ходьбы пользователю необременительны.
        private const val CAL_READY_STEPS = 20
        // Двойной "дзынь": вибро-пауза-вибро. Ни с чем не спутать.
        private val CAL_READY_PATTERN = longArrayOf(0, 120, 90, 120)
        private const val IDLE_LOG_DELAY_MS = 4000L
        private const val HEARTBEAT_MS = 30_000L
    }
}
