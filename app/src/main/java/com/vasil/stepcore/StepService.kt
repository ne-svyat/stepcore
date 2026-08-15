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

    // --- v391: приборы походки. Ничего не решают, только пишут. ---
    private val gait = GaitFeatures()
    private var gaitWindowStart = 0L
    /** Кольцо сырых отсчётов для окна вокруг события. */
    private val rawMag = FloatArray(RAW_RING)
    private val rawT = LongArray(RAW_RING)
    private var rawIdx = 0
    private var rawFilled = 0
    /** Бюджет окон сырья: RAW_PER_WINDOW штук на RAW_BUDGET_MS. */
    private var rawSpent = 0
    private var rawBudgetStart = 0L
    /** Предыдущий приход дельты чипа — для журнала решений. */
    private var lastChipDeltaElapsed = 0L
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
    // v213: курс. ROTATION_VECTOR - сплав акселерометра, гироскопа и компаса:
    // он устойчивее сырого магнитометра и уже сглажен системой.
    private var rotSensor: Sensor? = null
    @Volatile private var headingDeg: Float? = null
    @Volatile private var headingAcc: Int? = null
    @Volatile private var headingAtMs = 0L
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
            // Курсу частота не нужна: повороты тела - это доли секунды, а не
            // миллисекунды. NORMAL вместо GAME экономит батарею.
            rotSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
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
            rotSensor?.let { sensorManager.unregisterListener(this, it) }
            hwDetSensor?.let { sensorManager.unregisterListener(this, it) }
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        motionRegistered = need
    }

    private fun inBgWindow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < labelWindowUntilElapsed) return true
        // v268. Пока идёт замер уклона - непрерывно. Duty-цикл экономит
        // батарею при круглосуточном сборе; в двухминутном замере он
        // только протухляет признаки и растягивает калибровку.
        if (slopeActive) return true
        // v316. Темповая калибровка и калибровка бега - тоже замер, а не
        // круглосуточный сбор. Измерено 04.08: с заблокированным экраном
        // калибровка вставала и ждала разблокировки, потому что
        // акселерометр работал окнами 12 с из 60. Замер длится минуту-две,
        // и на это время экономия окнами вредна: она растягивает замер и
        // делает голос бессмысленным - человек всё равно достаёт телефон.
        if (calibrating != null) return true
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
        // v268. Во время замера уклона экономить нечего: это две минуты
        // по явной команде человека, а не круглосуточный сбор. Частые
        // строки дают нужные 8 признаков за ~40 шагов вместо 250.
        if (slopeActive && StepsState.slopeStage.value == "REC") SLOPE_SAMPLE_EVERY
        else if (StepsState.detailLog.value) 10 else terrainSampleEvery

    // почасовой аккумулятор (батчится в БД вместе с persistDb)
    private var pendKey = ""
    private var pendW = 0
    private var pendR = 0
    private var pendUp = 0
    // v220. Взвешенная сумма интервалов шага текущего часа и число этих шагов.
    private var pendCadSum = 0L
    private var pendCadN = 0
    private var pendDown = 0

    private var lastLoggedMode = "IDLE"
    private var idleSinceMs = 0L

    private var calibrating: String? = null
    // v295. Независимый измеритель темпа бега. Живёт только во время
    // калибровки бега, детектор не читает и не трогает. В этом релизе
    // работает в режиме ДИАГНОСТИКИ: показывает и пишет в журнал, но
    // профиль бега НЕ меняет - сначала смотрим на числа с живой пробежки.
    private val runMeter = RunTempoMeter()

    // v300. Почасовой учёт вынесен из службы. Служба только отдаёт дельту -
    // как и с корпусом признаков и измерителем бега.
    private val hourAcc by lazy {
        HourAccumulator(
            nowKey = { hourKeyNow() },
            write = { k, w, r, up, down, cadSum, cadN ->
                val dao = AppDb.get(this@StepService).dao()
                dao.ensureHour(k)
                dao.addHour(k, w, r, up, down, cadSum, cadN)
            },
            onError = { msg -> logEvent("ОШИБКА записи часа: " + msg) }
        )
    }
    private var runUiTick = 0
    // v314. Реплика «половина» звучит один раз за замер.
    private var calHalfSaid = false

    // v311. Теневой наблюдатель бега. Отдельный экземпляр измерителя -
    // калибровочный трогать нельзя, иначе теневой счёт испортит замер.
    // Метку не меняет: только пишет в журнал, что пометил бы.
    private val shadowMeter = RunTempoMeter()
    private val shadowWatch = ShadowRunWatch()
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
    /** Последняя озвученная сотня шагов замера: ступень звучит один раз. */
    private var distCalStepMark = 0
    /** Текущий замер длины шага - беговой, а не ходовой. */
    private var distCalIsRun = false
    private var distCalMetres = 0f
    // v242. Время старта замера по метражу: каденс считаем из него,
    // а не из профиля - иначе в историю попадает чужое число.
    private var distCalStartMs = 0L

    // v250. Калибровка уклона. Автомат здесь, а не в Activity: телефон
    // уходит в карман, экран гаснет, и HyperOS морозит активность.
    // Служба живёт всегда, поэтому отрезок не может "замереть".
    // v251. Панель меток: смахнули - вернём, когда снова пойдёт.
    private var marksHidden = false
    private var marksRepostAtSteps = 0
    // v259. Панель - инструмент для ходьбы, а не житель шторки.
    // Появляется при движении, уходит в покое, перерисовывается только
    // при реальном изменении текста.
    private var marksShown = false
    private var marksLastStepMs = 0L
    private var marksLastText = ""

    private var slopeActive = false
    private var slopeStartSteps = 0
    private var slopeStartMs = 0L
    private var slopeArmSteps = 0
    // v258. Забытая калибровка опасна: автомат ждёт движения и однажды
    // запишет чужой отрезок как эталон. Считаем время без активности.
    private var slopeIdleSinceMs = 0L
    private var slopeEndMs = 0L
    // v267. Медиана считается по СТРОКАМ корпуса, поэтому и порог должен
    // быть в строках. Плотность у Markus: ~1 строка на 30 шагов, то есть
    // прежние 40 шагов давали всего 1-2 строки.
    private var slopeRows = 0
    // Строки, пришедшие от детектора: признак того, что телефон в руке.
    private var slopeHandRows = 0

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
        StepsState.decisionLog.value = prefs.getBoolean("decision_log", false)
        StepsState.gaitLog.value = prefs.getBoolean("gait_log", false)
        StepsState.rawLog.value = prefs.getBoolean("raw_log", false)
        loadProfile()
        StepsState.steps.value = walkSteps + runSteps

        createChannel()
        startForeground(NOTIF_ID, buildNotification(walkSteps + runSteps))
        // v251. Панель меток - отдельным обычным уведомлением, иначе
        // HyperOS прячет её на локскрине вместе со служебным.
        createMarksChannel()
        // v259. При старте панель НЕ показываем: человек может просто
        // включить счёт и заниматься своими делами. Появится, когда пойдёт.
        marksLastStepMs = System.currentTimeMillis()
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                guard("покой панели") { marksIdleCheck() }
            }
        }

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
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
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
        scope.launch {
            // v298. Разовая чистка негодных точек профиля. Одна такая точка
            // (вес 0, записана до заполнения профиля) обнуляла дистанцию и
            // активные калории всего дня. После чистки день пересчитается
            // сам: снимки дня хранятся, а текущий день считается на лету.
            runCatching {
                val n = AppDb.get(this@StepService).dao().purgeUnusableProfiles()
                if (n > 0) logEvent("Убрано пустых точек профиля: " + n +
                    " - дистанция и калории пересчитаются")
            }
        }
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

        // v391: признаки походки. В отличие от подробного журнала работает
        // и при погашенном экране — но там акселерометр живёт окнами
        // (inBgWindow), и это честно помечается в строке.
        scope.launch {
            while (true) {
                delay(GAIT_WINDOW_MS)
                if (!StepsState.gaitLog.value) { gait.reset(); continue }
                val snap = gait.snapshot()
                gait.reset()
                if (snap == null) continue
                val tag = if (screenOff) " (фон, окнами)" else ""
                logEvent(snap.toLogLine(tag))
                // v392. Место телефона — предположение, на счёт не влияет.
                // Все пороги проекта мерились в одном положении и в другом
                // врут; пока место неизвестно, любой порог — лотерея.
                val where = PlacementGuess.of(
                    snap.dipG, snap.peakG, snap.strength, snap.periodMs)
                if (where != PlacementGuess.Where.UNKNOWN) {
                    val conf = PlacementGuess.confidence(snap.dipG, snap.peakG, where)
                    logEvent("[гип] телефон: " + PlacementGuess.ru(where) +
                        " (уверенность " + "%.0f".format(conf * 100) + "%)")
                } else {
                    logEvent("[гип] телефон: не знаю (ритм " +
                        "%.2f".format(snap.strength) + ")")
                }
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
        // v260. КРИТИЧНО. Кнопки панели зовут startForegroundService(), а
        // Android требует после КАЖДОГО такого вызова получить
        // startForeground() в течение 5 секунд - даже если служба уже на
        // переднем плане. Раньше мы просто обрабатывали действие и
        // выходили, поэтому система убивала службу: нажатие метки в
        // шторке ВЫКЛЮЧАЛО счёт шагов. Для работающей службы вызов
        // идемпотентен - он лишь подтверждает её статус.
        runCatching {
            startForeground(NOTIF_ID, buildNotification(walkSteps + runSteps))
        }
        // v265. Ни одна команда не имеет права уронить службу: счёт шагов -
        // фундамент, всё остальное надстройка. Раньше исключение внутри
        // обработчика убивало службу целиком, и счётчик обнулялся.
        guard("команда " + (intent?.action ?: "пусто")) {
            when (intent?.action) {
                ACTION_CAL_WALK -> startCalibration("walk")
                ACTION_CAL_RUN -> startCalibration("run")
                ACTION_CAL_STOP -> finishCalibration()
                ACTION_CAL_DIST_START -> {
                distCalIsRun = intent.getBooleanExtra(EXTRA_IS_RUN, false)
                startDistCal(intent.getFloatExtra(EXTRA_METRES, 0f))
            }
                ACTION_CAL_DIST_STOP -> finishDistCal()
                ACTION_MARKS_DISMISSED -> onMarksDismissed()
                ACTION_SLOPE_PICK ->
                    slopeStart(intent.getStringExtra(EXTRA_SLOPE_TARGET) ?: "")
                ACTION_SLOPE_CONFIRM -> slopeConfirm()
                ACTION_SLOPE_CANCEL -> slopeCancel()
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
                ACTION_INCLINE_NONE -> applyIncline(TerrainState.Incline.NONE, false)
            }
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
        calHalfSaid = false
        // Тень на время калибровки замолкает и стартует с чистого листа.
        shadowMeter.reset(); shadowWatch.clear()
        if (kind == "run") { runMeter.reset(); runUiTick = 0 }
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
        // v312. Голос вместо «достань телефон и посмотри». Реплика звучит
        // на старте и на финише - то есть ровно там, где раньше нужно было
        // вынимать телефон из кармана и портить замер.
        Voice.say(this, if (kind == "walk") "cal_walk_start" else "cal_run_start")
        StepsState.calibrationState.value = if (kind == "walk")
            "Калибровка ходьбы. Убери телефон в карман и иди обычным шагом " +
            "по прямой. Нужно около " + (MIN_CAL_INTERVALS + 1) + " шагов. " +
            "Смотреть в экран не надо: голос и вибрация скажут, когда готово."
        else
            "Калибровка бега. Закрепи телефон - карман куртки, боковой карман " +
            "рюкзака или пояс. В свободной руке и в шортах он болтается, и " +
            "замер не выйдет. Нужно около " + RUN_METER_MIN_STEPS + " шагов " +
            "ровного бега."
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
        // v296. РАЗДЕЛЕНИЕ ПРОЦЕССОВ. Калибровка бега больше не зависит от
        // детектора ни в чём: ни сбор, ни прогресс, ни отчёт. Измерено на
        // контрольной пробежке 02.08 - человек насчитал 40-43 шага, детектор
        // за то же время дал ДВА интервала, независимый измеритель - 44 пика.
        // Смешивать источник, который работает, с источником, который на
        // этом режиме молчит, значит портить первый вторым.
        if (kind == "run") return
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
        // v314. Половина набрана - короткая реплика, чтобы человек понимал,
        // что идёт правильно, и не гадал, сколько ещё. Звучит ровно один
        // раз за замер: повтор каждые несколько шагов раздражал бы сильнее,
        // чем молчание.
        if (!calHalfSaid && calIntervals.size * 2 >= MIN_CAL_INTERVALS) {
            calHalfSaid = true
            Voice.say(this, "cal_halfway")
        }
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
        if (kind == "run") { publishRunProgress(); return }
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

    /**
     * v295. Прогресс бега берётся у независимого измерителя. Показываем
     * СЫРЫЕ числа: сколько беговых шагов поймано и какой темп выходит.
     * Пока это диагностика - человек смотрит и говорит, похоже ли на правду.
     */
    private fun publishRunProgress() {
        val n = runMeter.stepCount()
        val med = runMeter.medianIntervalMs()
        StepsState.calibrationState.value = if (med <= 0L) {
            "Бег: беговых шагов " + n + " · нужно ещё " + RUN_METER_MIN_STEPS
        } else {
            val hz = 1000f / med
            "Бег: " + n + " шагов · " + "%.2f".format(hz) + " Гц · разброс " +
                runMeter.spreadPct() + "%"
        }
    }


    /**
     * v296. Завершение калибровки бега. Отдельная функция намеренно:
     * у бега свой источник, свои пороги и свой текст, и ни одна строка
     * отсюда не должна влиять на ходьбу.
     *
     * Данные пишутся в профиль, потому что измеритель сверен с реальностью:
     * на контрольной пробежке человек насчитал 40-43 шага, измеритель дал
     * 44 пика (43 интервала). Ворота качества те же, что у ходьбы, - при
     * рваном ритме профиль НЕ меняется.
     */
    private fun finishRunCalibration() {
        val ivs = runMeter.intervalsSnapshot()
        val amps = runMeter.ampsSnapshot()
        if (ivs.isNotEmpty()) {
            // Сырьё в журнал ВСЕГДА, даже если ворота не пройдены: эти
            // данные нельзя собрать задним числом, а разбирать неудачные
            // попытки важнее, чем удачные.
            logEvent("[диаг] изм.бег интервалы (" + ivs.size + "): " +
                ivs.joinToString(","))
            logEvent("[диаг] изм.бег амплитуды: " +
                amps.joinToString(" ") { "%.1f".format(it) })
        }
        val med = runMeter.medianIntervalMs()
        val steps = runMeter.stepCount()
        if (steps < RUN_METER_MIN_STEPS || med <= 0L) {
            Voice.say(this, "cal_need_more")
            StepsState.calibrationState.value =
                "Беговых шагов набралось " + steps + ", нужно " +
                RUN_METER_MIN_STEPS + ". Профиль не изменён."
            return
        }
        val spread = runMeter.spreadPct()
        // v315. У БЕГА СВОЙ ПОРОГ РАЗБРОСА.
        // Измерено на семи замерах: удачные дали 23% и 25%, неудачные -
        // 37, 38, 41, 47 и 50%. Между 25 и 37 в данных пусто, порог стоит
        // посреди этого промежутка. Прежние 25% были взяты от ходьбы и
        // отсекали даже ровный бег: у бега шаг физически неровнее.
        if (spread > RUN_SPREAD_OK_PCT) {
            Voice.say(this, "cal_rejected")
            StepsState.calibrationState.value =
                "Темп получился " + "%.2f".format(1000f / med) + " Гц, но ритм " +
                "рваный (разброс " + spread + "%, допустимо " + RUN_SPREAD_OK_PCT +
                "%). Профиль НЕ изменён.\n\nЧаще всего это телефон в руке: " +
                "размах руки добавляет пики. Попробуй убрать его в карман и " +
                "пробежать ровным темпом без ускорений."
            return
        }
        val lo = (med * 0.65).toLong()
        val hi = (med * 1.35).toLong()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("run_min_interval", lo)
            .putLong("run_max_interval", hi)
            .apply()
        loadProfile()
        scope.launch { ProfileHistory.record(this@StepService) }
        CalibrationRegistry.markDone(this, CalibrationRegistry.Kind.RUN_TEMPO)
        Voice.say(this, "cal_run_done")
        // v391. Отброшенные интервалы решают, можно ли верить разбросу:
        // он считается по ОБРЕЗАННОЙ выборке (окно 180-600 мс), и без
        // числа отброшенных занижен систематически.
        logEvent("[диаг] изм.бег отброшено: быстрых " + runMeter.rejectedFast +
            ", медленных " + runMeter.rejectedSlow)
        logEvent("Калибровка бега: " + "%.2f".format(1000f / med) + " Гц (" +
            med + " мс), шагов " + steps + ", разброс " + spread + "%")
        StepsState.calibrationState.value =
            "Готово: твой бег = " + "%.2f".format(1000f / med) + " Гц (" + med +
            " мс/шаг), диапазон " + lo + "-" + hi + " мс.\nШагов " + steps +
            ", разброс " + spread + "%."
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
        // v296. Бег: полностью свой путь. Сверено с контрольным счётом
        // 02.08 - 43 интервала при 40-43 шагах, посчитанных вслух. Один пик
        // равен одному шагу, удвоения нет. Медиана 329 мс = 3.04 Гц.
        if (kind == "run") { finishRunCalibration(); return }
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
        // v314. Озвучка успеха ходьбы. Файлы для неё уже лежали, а вызова
        // не было - самый частый замер оставался немым, и телефон всё равно
        // приходилось доставать.
        Voice.say(this, if (kind == "walk") "cal_walk_done" else "cal_run_done")
        StepsState.calibrationState.value =
            // v270. Было "твой шаг = 562 мс/шаг" - читается как ДЛИНА шага,
            // хотя это темп. Длина лежит рядом в сантиметрах, и человек
            // решил, что она изменилась.
            "Готово: твой темп ${if (kind == "walk") "ходьбы" else "бега"} = " +
            "$median мс между шагами " +
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
        distCalStepMark = 0
        // v317. Замер длины шага - самый длинный из всех: сотни шагов по
        // прямой. Без голоса человек идёт вслепую и не знает, сколько ещё,
        // поэтому здесь озвучены не только начало и конец, но и ступени
        // по сотням шагов.
        Voice.say(this, "cal_stride_start")
        distCalMetres = metres
        distCalStartMs = System.currentTimeMillis()
        distCalChipStart = hwLastTotal
        StepsState.calibrationState.value =
            "Калибровка дистанции: пройди ${metres.toInt()} м и нажми Готово"
    }

    // ---- v250: калибровка уклона ----

    /** Звук: вибрацию в кармане не слышно и не чувствуешь (проверено).
     *  Канал будильника выбран специально - он звучит, даже когда
     *  громкость уведомлений прикручена. */
    private fun beep(times: Int, low: Boolean = false) {
        // Вибрация вместе со звуком: если телефон в кармане и громкость
        // прикручена, останется хотя бы один канал.
        runCatching {
            // v315. Раньше сигнал различался ЧИСЛОМ коротких импульсов -
            // в кармане на ходу их не сосчитать, и «две вибрации» читались
            // как одна. Теперь различается ДЛИТЕЛЬНОСТЬ: чем важнее
            // событие, тем длиннее один толчок. Один толчок различим,
            // серия - нет.
            val pat = ArrayList<Long>()
            pat.add(0L)
            pat.add(when {
                times >= 3 -> 900L   // отказ: длинный, ни с чем не спутать
                times == 2 -> 550L   // готово
                else -> 200L         // начали
            })
            vibrator.vibrate(android.os.VibrationEffect.createWaveform(
                pat.toLongArray(), -1))
        }
        scope.launch {
            var tg: android.media.ToneGenerator? = null
            try {
                tg = android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_ALARM, 90)
                val tone = if (low) android.media.ToneGenerator.TONE_PROP_NACK
                    else android.media.ToneGenerator.TONE_PROP_BEEP
                // v269. В кармане короткий писк не слышно: тон длиннее и
                // повторяется, вибрация идёт одновременно.
                for (i in 0 until times) {
                    tg.startTone(tone, if (low) 700 else 450)
                    kotlinx.coroutines.delay(if (low) 850L else 600L)
                }
            } catch (e: Exception) {
                // без звука калибровка всё равно работает
            } finally {
                runCatching { tg?.release() }
            }
        }
    }

    /** Начать замер ОДНОГО класса. Очереди больше нет: гора не обязана
     *  давать подъём, ровное и спуск подряд. */
    private fun slopeStart(target: String) {
        if (target != "UP" && target != "FLAT" && target != "DOWN") return
        slopeActive = true
        StepsState.slopeTarget.value = target
        StepsState.slopeResult.value = ""
        slopeIdleSinceMs = System.currentTimeMillis()
        slopeArm()
        updateMotionSensors()   // включить акселерометр на время замера
        refreshPanel()
        scope.launch {
            while (slopeActive) {
                kotlinx.coroutines.delay(60_000L)
                guard("простой калибровки") { slopeIdleCheck() }
            }
        }
    }

    private fun slopeArm() {
        StepsState.slopeStage.value = "ARM"
        StepsState.slopeSteps.value = 0
        slopeRows = 0
        slopeHandRows = 0
        StepsState.slopeRows.value = 0
        slopeArmSteps = walkSteps + runSteps
    }

    /** Автомат живёт в службе: телефон в кармане, экран гаснет, а
     *  активность HyperOS замораживает. */
    private fun slopeTick(total: Int) {
        if (!slopeActive) return
        slopeIdleSinceMs = System.currentTimeMillis()
        when (StepsState.slopeStage.value) {
            "ARM" -> {
                if (total - slopeArmSteps >= SLOPE_START_STEPS) {
                    slopeStartSteps = slopeArmSteps
                    slopeStartMs = System.currentTimeMillis()
                    StepsState.slopeStage.value = "REC"
                    StepsState.slopeSteps.value = total - slopeStartSteps
                    Voice.say(this@StepService,
                        if (StepsState.slopeTarget.value == "UP") "cal_up_start"
                        else "cal_down_start")
                    beep(1)
                    refreshPanel()
                }
            }
            "REC" -> {
                val done = total - slopeStartSteps
                val prev = StepsState.slopeSteps.value
                StepsState.slopeSteps.value = done
                if (done / 10 != prev / 10) refreshPanel()
                // Признаки идут? Если шаги есть, а строк нет - смысла
                // ждать нет, честнее сказать сразу.
                // Строки идут от детектора - телефон в руке. Тянуть замер
                // бессмысленно: в руке амплитуда сглажена.
                if (slopeRows == 0 && slopeHandRows >= 2) {
                    slopeStop("Телефон в руке — уклон так не измеряется. " +
                        "Убери телефон в карман и начни отрезок заново.")
                    return
                }
                if (slopeRows == 0 && done >= SLOPE_DRY_STEPS) {
                    slopeStop("Признаки не пишутся: проверь тумблер сбора " +
                        "при выключенном экране в SYNX. Замер остановлен.")
                    return
                }
                if (slopeRows >= SLOPE_MIN_ROWS) {
                    // Окно закрываем здесь: пока человек достаёт телефон и
                    // идёт обратно, лишнее в замер не попадёт.
                    slopeEndMs = System.currentTimeMillis()
                    StepsState.slopeStage.value = "DONE"
                    Voice.say(this@StepService,
                        if (StepsState.slopeTarget.value == "UP") "cal_up_done"
                        else "cal_down_done")
                    beep(2)
                    refreshPanel()
                }
            }
        }
    }

    /** Подтверждение: считаем медиану окна и сохраняем ЭТОТ якорь.
     *  Остальные не трогаем - каждый живёт сам по себе. */
    private fun slopeConfirm() {
        if (!slopeActive) return
        if (StepsState.slopeStage.value != "DONE") return
        val target = StepsState.slopeTarget.value
        val from = slopeStartMs; val to = slopeEndMs
        slopeActive = false
        StepsState.slopeStage.value = "CALC"
        updateMotionSensors()   // вернуть обычный режим сбора
        refreshPanel()
        scope.launch {
            val a = AppDb.get(this@StepService).dao().samplesBetween(from, to)
                .filter { it.sampleSource == 1 }
                .mapNotNull { it.accP90 ?: it.accRms }
                .sorted()
            val msg: String
            if (a.size < 3) {   // подстраховка: окно могло не сойтись
                msg = "Признаков не хватило (" + a.size + " строк). Обычно это " +
                    "значит, что телефон был в руке или сбор при выключенном " +
                    "экране отключён."
            } else {
                // v281. Честная медиана: прежний a[size/2] при чётном числе
                // строк брал верхний из двух средних.
                // v306. Сырьё уклона в журнал ВСЕГДА, ещё до ворот: неудачную
                // попытку разбирать важнее удачной, а собрать её задним
                // числом нельзя. Печатаем отсортированные амплитуды - по ним
                // сразу видно, один это кластер или два склеенных.
                logEvent("[диаг] уклон " + target + " амплитуды (" + a.size + "): " +
                    a.joinToString(" ") { "%.2f".format(it) })
                val med = StrideModel.medianOf(a)
                // v281. ВОРОТА КАЧЕСТВА НА СОХРАНЕНИЕ - та же защита, что
                // стоит у темповой калибровки с v11.14, но здесь её не было.
                // Отрезок с рваной амплитудой (остановки, смена рельефа,
                // телефон сместился в кармане) даёт медиану между двумя
                // кластерами, и якорь выходит лживым. Классы уклона
                // расходятся примерно на 20% уровня, поэтому собственный
                // разброс отрезка выше 25% не способен их разрешить.
                val nn = a.size
                val spreadPct = if (med > 0f)
                    (100f * (a[nn * 3 / 4] - a[nn / 4]) / med).toInt() else 0
                if (spreadPct > SLOPE_SPREAD_OK_PCT) {
                    msg = "Амплитуда рваная (разброс " + spreadPct + "%): в отрезок " +
                        "попал разный рельеф, остановки или телефон сместился. " +
                        "Якорь НЕ сохранён. Пройди участок одного уклона ровным " +
                        "шагом без остановок."
                    logEvent("Калибровка уклона отклонена: " + slopeRu(target) +
                        " разброс " + spreadPct + "%")
                    StepsState.slopeResult.value = msg
                    StepsState.slopeStage.value = "RESULT"
                    StepsState.slopeTarget.value = ""
                    Voice.say(this@StepService, "cal_rejected")
                    beep(3)
                    endSlopePanel()
                    toastMain(msg)
                    return@launch
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putFloat("slope_anchor_" + target.lowercase(), med)
                    .putLong("slope_anchor_" + target.lowercase() + "_ms",
                        System.currentTimeMillis())
                    .apply()
                msg = "Записано: " + slopeRu(target) + " = " +
                    String.format(java.util.Locale.US, "%.2f", med) +
                    "  (строк " + a.size + " · разброс " + spreadPct + "%)"
                logEvent("Калибровка уклона: " + slopeRu(target) + " = " +
                    String.format(java.util.Locale.US, "%.2f", med))
            }
            StepsState.slopeResult.value = msg
            StepsState.slopeStage.value = "RESULT"
            StepsState.slopeTarget.value = ""
            beep(3)
            endSlopePanel()
            toastMain(msg)
        }
    }

    /** Шаги идут, а признаков нет: телефон в руке или выключен сбор при
     *  погашенном экране. Молчать до конца замера нечестно. */
    private fun slopeStop(msg: String) {
        slopeActive = false
        StepsState.slopeTarget.value = ""
        StepsState.slopeStage.value = "RESULT"
        updateMotionSensors()   // вернуть обычный режим сбора
        StepsState.slopeResult.value = msg
        beep(1, low = true)
        logEvent("Калибровка уклона остановлена: " + msg)
        toastMain(msg)
        endSlopePanel()
    }

    private fun slopeRu(t: String) = when (t) {
        "UP" -> "в гору"; "DOWN" -> "с горы"; else -> "ровно"
    }

    private fun slopeCancel() {
        slopeActive = false
        Voice.say(this, "cal_cancelled")
        StepsState.slopeTarget.value = ""
        StepsState.slopeStage.value = "ARM"
        updateMotionSensors()   // вернуть обычный режим сбора
        beep(1, low = true)
        endSlopePanel()
    }

    /** Простой: ни шагов, ни подтверждений. Отменяем сами - кривой
     *  эталон хуже отсутствующего. */
    private fun slopeIdleCheck() {
        if (!slopeActive) return
        if (slopeIdleSinceMs <= 0L) return
        if (System.currentTimeMillis() - slopeIdleSinceMs < SLOPE_IDLE_MS) return
        logEvent("Калибровка уклона отменена: долгий простой")
        slopeCancel()
    }

    private fun finishDistCal() {
        if (!distCalActive) return
        distCalActive = false
        val steps = if (distCalChipStart >= 0 && hwLastTotal >= 0)
            (hwLastTotal - distCalChipStart).toInt() else 0
        if (steps >= 20) Voice.say(this, "cal_stride_done")
        else Voice.say(this, "cal_need_more")
        if (steps < 20) {
            StepsState.calibrationState.value =
                "Мало шагов ($steps) - калибровка не сохранена. Нужен отрезок подлиннее."
            return
        }
        // Фактический каденс замера: шаги / длительность (как в GPS-пути).
        val durSec = (System.currentTimeMillis() - distCalStartMs) / 1000f
        val measuredCad = if (durSec > 0f) steps / durSec else 0f
        // v318. Беговой замер идёт своим путём: у него другая величина,
        // другие ворота и другой ключ. Ходьба не задета.
        if (distCalIsRun) {
            val durSec = (System.currentTimeMillis() - distCalStartMs) / 1000f
            val msg = StrideModel.applyRunCalibration(
                this, distCalMetres, steps, false, durSec)
            StepsState.calibrationState.value = msg
            logEvent("Калибровка бегового шага: " + msg)
            Voice.say(this, if (msg.startsWith("Готово")) "cal_stride_done" else "cal_rejected")
            distCalIsRun = false
            return
        }
        StrideModel.applyCalibration(this, distCalMetres, steps,
            measuredCadence = measuredCad)
        scope.launch { ProfileHistory.record(this@StepService) }   // V11
        CalibrationRegistry.markDone(this, CalibrationRegistry.Kind.STRIDE)
        val slCm = StrideModel.measuredStrideCm(this) ?: 0
        StepsState.calibrationState.value =
            "Готово: ${distCalMetres.toInt()} м за $steps шагов = " +
            "длина шага $slCm см (темп " +
            String.format(java.util.Locale.US, "%.2f", measuredCad) + " Гц)"
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
                    val gapMs = if (lastChipDeltaElapsed > 0L)
                        nowElapsed - lastChipDeltaElapsed else -1L
                    lastChipDeltaElapsed = nowElapsed
                    val v = shakeHold.onShakenDelta(nowElapsed, delta)
                    if (StepsState.decisionLog.value) {
                        // Главное подозрение v391: дельты чипа на беге
                        // приходят пачками, и разброс ломается ВЫДАЧЕЙ,
                        // а не человеком. Пауза между приходами — прямая
                        // проверка этой гипотезы.
                        logEvent("[реш] чип +" + delta + "ш · пауза " +
                            (if (gapMs >= 0L) gapMs.toString() + "мс" else "первая") +
                            " · в карантине " + shakeHold.heldSteps +
                            " · " + (if (shakeHold.isConfirmed) "счёт открыт" else "карантин"))
                        if (shakeHold.lastReport.isNotEmpty()) {
                            logEvent("[реш] страж: " + shakeHold.lastReport)
                        }
                    }
                    v.reason?.let { logEvent("Тряска: " + it) }
                    if (v.discarded > 0) {
                        logEvent("Тряска: отброшено ${v.discarded} шагов чипа")
                        dumpRaw("вето стража")
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
                // Интервал шага копим только если детектор его реально мерил
                // (ходьба/бег с акселерометром). В кармане mode иной - каденс
                // не искажаем, час останется с нулём и откатится на константу.
                val iv = detector.lastIntervalMs
                if (!asRun && iv in 250f..2000f) {
                    pendCadSum += (iv.toLong()) * delta
                    pendCadN += delta
                }
                // v311. Отдаём тени дельту чипа и решение ДЕТЕКТОРА - чтобы
                // в журнале два мнения стояли рядом и их можно было сверить.
                shadowWatch.onChipDelta(delta, asRun)
                // v317. Ступени замера длины шага. Реплика на каждой сотне
                // и ровно один раз: повтор на каждом шаге раздражал бы
                // сильнее, чем молчание.
                if (distCalActive && distCalChipStart >= 0) {
                    val done = (hwLastTotal - distCalChipStart).toInt()
                    val mark = done / 100
                    if (mark > distCalStepMark && mark in 1..3) {
                        distCalStepMark = mark
                        Voice.say(this, "cal_stride_" + (mark * 100) + "_steps")
                    }
                }
                if (asRun) {
                    runSteps += delta; bumpHour(0, delta)
                    // v280. Отметка "бег видели". Нужна, чтобы общая точность
                    // не занижалась у того, кто не бегает. Порог отсекает
                    // случайные пары шагов, помеченных бегом на торможении.
                    if (runSteps >= RUN_SEEN_STEPS) {
                        val pr = getSharedPreferences(PREFS, MODE_PRIVATE)
                        val last = pr.getLong(CalibrationRegistry.KEY_RUN_SEEN, 0L)
                        if (System.currentTimeMillis() - last > 6 * 3600_000L) {
                            pr.edit().putLong(CalibrationRegistry.KEY_RUN_SEEN,
                                System.currentTimeMillis()).apply()
                        }
                    }
                }
                else { walkSteps += delta; bumpHour(delta, 0) }
                if (screenOff) hwSessionAdded += delta
                // v185: корпус в кармане. Детектор здесь молчит (вето по
                // гироскопу), но метка уклона, оси гироскопа, наклон
                // телефона и амплитуда из сырого канала существуют и без
                // него. Без этой ветки они были бы потеряны.
                // v216. Раньше здесь стояло просто "детектор не в ходьбе".
                // Это открывало дыру: нажатие метки будит экран, детектор
                // встаёт в WALK, экран гаснет - и режим ЗАЛИПАЕТ, потому что
                // разбудить и сбросить его некому. Канал оставался закрытым,
                // сам детектор спал, строки не писались вовсе. Измерено
                // 23 июля: 717 и 795 шагов подъёма дали ноль строк.
                // Теперь режим блокирует канал, только пока он СВЕЖИЙ:
                // без подтверждённого шага дольше MODE_STALE_MS "ходьба" -
                // воспоминание, а не наблюдение.
                val modeFresh = lastConfirmedElapsed > 0L &&
                    SystemClock.elapsedRealtime() - lastConfirmedElapsed <= MODE_STALE_MS
                val detectorBusy = modeFresh &&
                    (detector.mode == StepDetector.Mode.WALK ||
                        detector.mode == StepDetector.Mode.RUN)
                if (!detectorBusy) {
                    chipSinceSample += delta
                    if (chipSinceSample >= chipSampleEvery()) {
                        chipSinceSample = 0
                        // Режим в строке - честный: если детектор протух,
                        // пишем IDLE, а не его несвежее мнение.
                        val modeName =
                            if (modeFresh) detector.mode.name
                            else StepDetector.Mode.IDLE.name
                        writeTerrainSample(modeName, 0f, 0f, source = 1)
                    }
                }
                StepsState.steps.value = walkSteps + runSteps
                slopeTick(walkSteps + runSteps)
                marksTick(walkSteps + runSteps)
                persistPrefs()
                stepsSinceDbWrite += delta
                if (stepsSinceDbWrite >= 25) { stepsSinceDbWrite = 0; persistDb() }
                // v259. Было каждые 10 шагов - это раз в 6 секунд на ходу.
                // Каждый notify() система может показать заново, отсюда
                // ощущение постоянных уведомлений.
                if (walkSteps + runSteps - lastNotifiedSteps >= NOTIF_STEP_STRIDE) {
                    lastNotifiedSteps = walkSteps + runSteps
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(walkSteps + runSteps))
                }
                return
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                // На части устройств вектор приходит из 5 чисел, а матрице
                // нужно не больше 4 - копируем ровно столько, сколько надо.
                val v = FloatArray(4)
                val n = if (event.values.size < 4) event.values.size else 4
                System.arraycopy(event.values, 0, v, 0, n)
                val rm = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rm, v)
                val ori = FloatArray(3)
                SensorManager.getOrientation(rm, ori)
                var az = Math.toDegrees(ori[0].toDouble()).toFloat()
                if (az < 0f) az += 360f
                headingDeg = az
                headingAtMs = SystemClock.elapsedRealtime()
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
                    if (StepsState.gaitLog.value && inBgWindow()) {
                        gait.onGyro(event.values[0], event.values[1], event.values[2])
                    }
                    return
                }
                if (calibrating == null) {
                    detector.onGyro(event.values[0], event.values[1], event.values[2], timeMs)
                    features.onGyro(event.values[0], event.values[1], event.values[2], timeMs)
                }
                if (StepsState.gaitLog.value) {
                    gait.onGyro(event.values[0], event.values[1], event.values[2])
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
                        feedProbes(event.values[0], event.values[1],
                            event.values[2], timeMs)
                        // v316. Измеритель пиков от детектора не зависит и
                        // его заморозки не нарушает: он читает сырой канал.
                        // Поэтому в фоне он работать МОЖЕТ и должен - иначе
                        // калибровка в кармане невозможна, а тень слепа.
                        // Измерено 04.08: при заблокированном экране тень
                        // видела 0.04 пика на шаг против 0.96 при включённом.
                        if (calibrating == "run") {
                            if (runMeter.onAccel(event.values[0], event.values[1],
                                    event.values[2], timeMs)) {
                                runUiTick++
                                if (runUiTick == 1 || runUiTick % CAL_UI_EVERY == 0) {
                                    publishRunProgress()
                                }
                            }
                        } else if (calibrating == null) {
                            if (shadowMeter.onAccel(event.values[0], event.values[1],
                                    event.values[2], timeMs)) {
                                shadowWatch.onPeak(timeMs)
                            }
                            // Охват: в фоне смотрим BG_WINDOW_MS из BG_PERIOD_MS.
                            shadowWatch.coveragePct =
                                (100L * BG_WINDOW_MS / BG_PERIOD_MS).toInt()
                            shadowWatch.poll(timeMs)?.let { logEvent(it) }
                        }
                    }
                    return
                }
                // v295. Отдельный канал калибровки бега. Стоит ДО детектора
                // и от него не зависит: у детектора замкнутый круг с режимом
                // RUN (измерено 01.08), из-за которого он подтверждает каждый
                // второй беговой шаг.
                // v311. Тень: тот же поток отсчётов, никаких новых сенсоров
                // и никакой лишней батареи. Во время калибровки молчит,
                // чтобы не мешать замеру.
                if (calibrating == null) {
                    if (shadowMeter.onAccel(
                            event.values[0], event.values[1], event.values[2], timeMs)) {
                        shadowWatch.onPeak(timeMs)
                    }
                    shadowWatch.coveragePct = 100
                    shadowWatch.poll(timeMs)?.let { logEvent(it) }
                }
                if (calibrating == "run") {
                    // Прогресс идёт отсюда же: раньше экран обновлялся по
                    // тикам детектора и застревал на 15 шагах, пока измеритель
                    // набирал 44. Свой процесс - свой прогресс.
                    if (runMeter.onAccel(
                            event.values[0], event.values[1], event.values[2], timeMs)) {
                        runUiTick++
                        if (runUiTick == 1 || runUiTick % CAL_UI_EVERY == 0) {
                            publishRunProgress()
                        }
                    }
                }
                feedProbes(event.values[0], event.values[1],
                    event.values[2], timeMs)
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
                    // v269. Во время замера уклона частят ОБА канала.
                    // Раньше ускорен был только чиповый, а он молчит,
                    // пока детектор ведёт шаги - замер не кончался.
                    val every = if (slopeActive &&
                        StepsState.slopeStage.value == "REC") SLOPE_SAMPLE_EVERY
                        else terrainSampleEvery
                    if (samplesSinceStep >= every) {
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

    /** Показать сообщение ИЗ ЛЮБОГО потока.
     *  Toast работает только в главном потоке, а служба считает и пишет
     *  в базу на Dispatchers.IO. Прямой вызов оттуда роняет службу:
     *  "Can't toast on a thread that has not called Looper.prepare()" -
     *  именно это убивало счёт при подтверждении калибровки. */
    private fun toastMain(text: String) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        this, text, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) {
            // Сообщение не важнее счёта: молча пропускаем.
        }
    }

    /** Выполнить и не дать упасть. Причина пишется в журнал: логи с
     *  телефона снимать неудобно, а в Истории видно сразу. */
    private fun guard(what: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            val at = t.stackTrace.firstOrNull { it.className.contains("stepcore") }
            val where = if (at == null) "" else
                " @ " + at.fileName + ":" + at.lineNumber
            val msg = "СБОЙ (" + what + "): " +
                t.javaClass.simpleName + " " + (t.message ?: "") + where
            runCatching { logEvent(msg) }
            toastMain(msg)
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

    // ================= v391: приборы. Ничего не решают. =================

    /**
     * Кормит измеритель признаков и кольцо сырья. Вызывается из обоих
     * путей акселерометра (экран включён и фоновое окно), поэтому
     * гарантия стоит в воронке, а не в обработчиках.
     */
    private fun feedProbes(x: Float, y: Float, z: Float, timeMs: Long) {
        if (StepsState.gaitLog.value) gait.onAccel(x, y, z, timeMs)
        if (StepsState.rawLog.value) {
            val m = kotlin.math.sqrt(x * x + y * y + z * z)
            rawMag[rawIdx] = m
            rawT[rawIdx] = timeMs
            rawIdx = (rawIdx + 1) % RAW_RING
            if (rawFilled < RAW_RING) rawFilled++
        }
    }

    /**
     * Выгрузка окна сырья вокруг события.
     *
     * Бюджет обязателен: на пробежке вето стража срабатывает десятками, и
     * без ограничения первые две минуты съели бы весь журнал, а до отрезков
     * с карманом и рюкзаком прибор бы не дожил. RAW_PER_WINDOW окон на
     * каждые RAW_BUDGET_MS дают равномерное покрытие всей пробежки.
     */
    private fun dumpRaw(why: String) {
        if (!StepsState.rawLog.value || rawFilled < 20) return
        val now = SystemClock.elapsedRealtime()
        if (rawBudgetStart == 0L || now - rawBudgetStart > RAW_BUDGET_MS) {
            rawBudgetStart = now
            rawSpent = 0
        }
        if (rawSpent >= RAW_PER_WINDOW) return
        rawSpent++
        val sb = StringBuilder()
        sb.append("[сыр] ").append(why).append(" · ").append(rawFilled).append(" отсч: ")
        val start = if (rawFilled < RAW_RING) 0 else rawIdx
        for (k in 0 until rawFilled) {
            if (k > 0) sb.append(",")
            sb.append("%.1f".format(rawMag[(start + k) % RAW_RING]))
        }
        logEvent(sb.toString())
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
        // v267. Замер уклона ждёт именно строки признаков: считаем их
        // здесь, в точке записи. Берём только чиповые - это карман, где
        // уклон и читается.
        if (slopeActive && StepsState.slopeStage.value == "REC") {
            // Чиповые строки - карман, там уклон и читается. Детекторные -
            // телефон в руке: амплитуда сглажена, для уклона негодна.
            if (source == 1) {
                slopeRows++
                StepsState.slopeRows.value = slopeRows
            } else {
                slopeHandRows++
            }
        }
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
            // v257. У КУРСА свой порог свежести. Измерено: курс был лишь у
            // 55% образцов - ровно там, где работал акселерометр. Датчик
            // поворота включается вместе с ним, а в фоне тот живёт 12 с из
            // 60. Чиповые строки пишутся весь цикл, и к ним курс приходил
            // протухшим. Из-за этого на горе (карман, экран гаснет, строки
            // чиповые) курса не было именно на размеченных подъёмах.
            //
            // Почему 60 с безопасно: курс меняется МЕДЛЕННО, человек не
            // крутится вокруг оси, и азимут минутной давности всё ещё
            // говорит, куда он идёт. Амплитуде такой допуск не годится -
            // она меняется каждый шаг, поэтому её порог не трогаем.
            // Держать датчик дольше не стали: расход батареи важнее
            // лишней точности там, где её всё равно не требуется.
            headingDeg = if (headingAtMs > 0L &&
                SystemClock.elapsedRealtime() - headingAtMs <= HEADING_STALE_MS)
                headingDeg else null,
            headingAcc = if (headingAtMs > 0L &&
                SystemClock.elapsedRealtime() - headingAtMs <= HEADING_STALE_MS)
                headingAcc else null,
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

    /**
     * v300. Час пишется СРАЗУ, а не копится в памяти службы.
     *
     * Прежняя схема отдавала час в базу только при смене часа или в
     * `persistDb`. Любой путь, где поля обнулялись или служба
     * перезапускалась, терял час молча - и таблица часов оставалась
     * пустой при живом счёте шагов. Timeline и посегментная дистанция
     * читают именно её, поэтому пустыми оказывались они, а не шаги.
     *
     * Накопление осталось только для каденса: он приходит не с каждой
     * дельтой и суммируется до ближайшей записи.
     */
    private fun bumpHour(w: Int, r: Int) {
        val d = w + r
        var up = 0; var down = 0
        when (TerrainState.incline.value) {
            TerrainState.Incline.UP -> up = d
            TerrainState.Incline.DOWN -> down = d
            else -> {}
        }
        val cs = pendCadSum; val cn = pendCadN
        pendCadSum = 0L; pendCadN = 0
        scope.launch { hourAcc.add(w, r, up, down, cs, cn) }
        return
    }

    private fun bumpHourLegacyUnused(w: Int, r: Int) {
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
        val cadSum = pendCadSum; val cadN = pendCadN
        pendW = 0; pendR = 0; pendUp = 0; pendDown = 0
        pendCadSum = 0L; pendCadN = 0
        scope.launch {
            // v299. Запись часа больше не может провалиться молча. Раньше
            // исключение здесь просто гасило корутину, и человек видел
            // пустой Timeline и нулевую дистанцию без единого намёка на
            // причину. Сбой в журнал - его видно в Истории.
            val res = runCatching {
                val dao = AppDb.get(this@StepService).dao()
                dao.ensureHour(k); dao.addHour(k, w, r, up, down, cadSum, cadN)
            }
            res.exceptionOrNull()?.let { e ->
                logEvent("ОШИБКА записи часа " + k + ": " +
                    (e.message ?: e.javaClass.simpleName))
            }
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
            AppDb.get(this@StepService).dao().saveDaySteps(d, w, r)
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
        // v221: каденс сбрасываем здесь тоже - иначе при аварийном сохранении
        // накопленные интервалы утекут в следующий час.
        val pcs = pendCadSum; val pcn = pendCadN
        pendW = 0; pendR = 0; pendUp = 0; pendDown = 0
        pendCadSum = 0L; pendCadN = 0
        val d = currentDay; val dw = walkSteps; val dr = runSteps
        runBlocking {
            val dao = AppDb.get(this@StepService).dao()
            if (k.isNotEmpty() && (w > 0 || r > 0)) { dao.ensureHour(k); dao.addHour(k, w, r, up, down, pcs, pcn) }
            dao.saveDaySteps(d, dw, dr)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Точность компаса меняется на ходу (металл, арматура, машина).
        // Пишем её рядом с курсом: иначе честный курс не отличить от вранья.
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) headingAcc = accuracy
    }

    override fun onDestroy() {
        // Панель меток живёт ровно столько, сколько идёт счёт.
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_MARKS)
        }
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
            TerrainState.Incline.NONE -> "не отмечено"
            else -> "ровно"
        }
        logEvent("Уклон: " + name + (if (fromShade) " (шторка)" else ""))
        // Во время калибровки панель занята ею - не затираем.
        if (!marksHidden && !slopeActive) showMarks()
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
            TerrainState.Incline.NONE -> "не отмечено"
            else -> "ровно"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("StepCore работает")
            // Текущая метка видна прямо в шторке: иначе, нажимая кнопки
            // не глядя на экран, невозможно понять, что сейчас стоит.
            .setContentText("Шагов: " + steps + " · уклон: " + incName)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)   // v259: не показывать заново на каждом обновлении
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
            // v251: кнопки уклона переехали в отдельное уведомление -
            // служебное отвечает только за "служба жива" и шаги.
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
    // ---- v251: метки уклона в собственном уведомлении ----

    /** Отдельный канал. Служебное уведомление foreground service HyperOS
     *  прячет на локскрине; обычное уведомление с importance DEFAULT -
     *  показывает, как у любого другого приложения. Звук и вибрация
     *  выключены: это тихая панель кнопок, а не оповещение. */
    private fun createMarksChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_MARKS, "Метки уклона", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Кнопки «в гору / ровно / с горы» на экране блокировки"
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildMarksNotification(): Notification {
        val cur = when (TerrainState.incline.value) {
            TerrainState.Incline.UP -> "сейчас: в гору"
            TerrainState.Incline.DOWN -> "сейчас: с горы"
            TerrainState.Incline.FLAT -> "сейчас: ровно"
            else -> "уклон не отмечен"
        }
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        // Смахнули - не спорим. Вернём, когда человек снова пойдёт.
        val gone = PendingIntent.getForegroundService(
            this, 21,
            Intent(this, StepService::class.java).setAction(ACTION_MARKS_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_MARKS)
            .setContentTitle("Уклон")
            .setContentText(cur)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(open)
            .setDeleteIntent(gone)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(inclineAction(TerrainState.Incline.UP, "▲ В гору", 11))
            .addAction(inclineAction(TerrainState.Incline.FLAT, "━ Ровно", 12))
            .addAction(inclineAction(TerrainState.Incline.DOWN, "▼ С горы", 13))
            .build()
    }

    /** Панель калибровки уклона. Живёт на том же уведомлении, что метки:
     *  пока идёт калибровка, метки всё равно ставить нельзя, а место на
     *  локскрине одно. Кнопки работают с заблокированного экрана - ради
     *  этого всё и затевалось. */
    private fun buildSlopeNotification(): Notification {
        val stage = StepsState.slopeStage.value
        val steps = StepsState.slopeSteps.value
        val name = slopeRu(StepsState.slopeTarget.value)
        val title = "Калибровка уклона · " + name
        val body = when (stage) {
            "ARM" -> "«" + name + "» — иди, запись начнётся сама"
            "REC" -> "«" + name + "» — признаков " +
                StepsState.slopeRows.value + " из " + SLOPE_MIN_ROWS +
                "  (" + steps + " шагов)"
            "DONE" -> "«" + name + "» записан (" + steps + " шагов) — подтверди"
            "CALC" -> "считаю якоря…"
            else -> "«" + name + "»"
        }
        val open = PendingIntent.getActivity(
            this, 30, Intent(this, SlopeCalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL_MARKS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(open)
            .setOngoing(true)      // калибровку не смахивают случайно
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (stage == "DONE") {
            b.addAction(slopeAction(ACTION_SLOPE_CONFIRM, "✓ Подтвердить", 31))
        }
        b.addAction(slopeAction(ACTION_SLOPE_CANCEL, "Отмена", 33))
        return b.build()
    }

    private fun slopeAction(action: String, title: String, req: Int):
        Notification.Action {
        val pi = PendingIntent.getForegroundService(
            this, req,
            Intent(this, StepService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_directions),
            title, pi).build()
    }

    /** Обновить панель: во время калибровки показываем её, иначе метки. */
    private fun refreshPanel() {
        if (marksHidden && !slopeActive) return
        // Оформление панели не стоит того, чтобы из-за него умирал счёт.
        guard("панель") {
            val n = if (slopeActive || StepsState.slopeStage.value == "CALC")
                buildSlopeNotification() else buildMarksNotification()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID_MARKS, n)
        }
    }

    /** Единственный выход из режима калибровки для шторки.
     *  Панель калибровки несмахиваемая, поэтому оставить её нельзя ни
     *  при каких обстоятельствах: либо на её место встают метки, либо
     *  уведомление снимается совсем. Раньше при смахнутых метках она
     *  висела в шторке навсегда. */
    private fun endSlopePanel() {
        if (marksHidden) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .cancel(NOTIF_ID_MARKS)
            }
            marksShown = false
            marksLastText = ""
        } else {
            marksLastText = ""   // заставить перерисовать поверх калибровки
            showMarks()
        }
    }

    /** Показать панель меток. Тихо: setOnlyAlertOnce + канал без звука. */
    private fun showMarks() {
        marksHidden = false
        val txt = marksText()
        if (marksShown && txt == marksLastText) return   // нечего перерисовывать
        marksLastText = txt
        marksShown = true
        guard("панель меток") {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID_MARKS, buildMarksNotification())
        }
    }

    /** Текущая подпись панели - по ней решаем, нужно ли перерисовывать. */
    private fun marksText(): String = when (TerrainState.incline.value) {
        TerrainState.Incline.UP -> "сейчас: в гору"
        TerrainState.Incline.DOWN -> "сейчас: с горы"
        TerrainState.Incline.FLAT -> "сейчас: ровно"
        else -> "уклон не отмечен"
    }

    /** Убрать панель: человек стоит, кнопки ему не нужны. */
    private fun hideMarks() {
        if (!marksShown) return
        marksShown = false
        marksLastText = ""
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_MARKS)
        }
    }

    /** Человек смахнул панель. Возвращаем не сразу и не по таймеру, а
     *  когда он снова прошёл заметное расстояние: смахнул стоя - пусть
     *  полежит, пошёл - метки снова под рукой. */
    private fun onMarksDismissed() {
        marksHidden = true
        marksRepostAtSteps = walkSteps + runSteps + MARKS_REPOST_STEPS
    }

    /** Вызывается на каждом обновлении счёта. Решает судьбу панели:
     *  идёт человек - панель нужна, стоит - не нужна. Так шторка пуста,
     *  пока ей нечего сказать. */
    private fun marksTick(total: Int) {
        slopeIdleCheck()
        if (slopeActive) return          // панель занята калибровкой
        marksLastStepMs = System.currentTimeMillis()
        if (marksHidden) {
            // Смахнули - вернём, когда прошёл заметное расстояние.
            if (total >= marksRepostAtSteps) { marksHidden = false; showMarks() }
            return
        }
        showMarks()
    }

    /** Покой: панель уходит. Проверяется таймером, потому что в покое
     *  шаговых событий нет и marksTick не позовут. */
    private fun marksIdleCheck() {
        if (slopeActive) return
        if (!marksShown) return
        if (marksLastStepMs <= 0L) return
        if (System.currentTimeMillis() - marksLastStepMs < MARKS_IDLE_MS) return
        hideMarks()
    }

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
        const val CHANNEL_MARKS = "stepcore_marks"
        const val NOTIF_ID_MARKS = 2
        const val ACTION_MARKS_DISMISSED = "marks_dismissed"
        /** Через столько шагов после смахивания панель возвращается.
         *  ~2 минуты ходьбы: достаточно, чтобы не мозолить, и мало,
         *  чтобы метка была под рукой к следующему склону. */
        const val MARKS_REPOST_STEPS = 200
        /** Столько без шагов - панель уходит из шторки. 6 минут: дольше
         *  светофора и кофе, короче настоящей остановки. */
        const val MARKS_IDLE_MS = 6 * 60 * 1000L
        /** Через столько шагов перерисовываем служебное уведомление.
         *  Было 10 - раз в 6 секунд на ходу, слишком часто. */
        const val NOTIF_STEP_STRIDE = 40
        const val ACTION_SLOPE_PICK = "slope_pick"
        const val EXTRA_SLOPE_TARGET = "slope_target"
        const val ACTION_SLOPE_CONFIRM = "slope_confirm"
        const val ACTION_SLOPE_CANCEL = "slope_cancel"
        /** Столько шагов подряд считаем началом движения. */
        /** Порог свежести КУРСА. Больше общего (15 с), потому что курс
         *  меняется медленно, а duty-цикл акселерометра - 12 с из 60. */
        const val HEADING_STALE_MS = 60_000L
        const val SLOPE_START_STEPS = 4
        /** На 40 шагах медиана уже устойчива, а отрезок найти реально. */
        /** Сколько СТРОК признаков нужно для устойчивой медианы.
         *  Плотность у этого пользователя ~1 строка на 30 шагов, то есть
         *  8 строк - примерно 2 минуты ходьбы. Прежний порог в 40 шагов
         *  давал 1-2 строки и калибровка не сходилась. */
        /** Как часто пишем строку признаков ВО ВРЕМЯ замера уклона.
         *  Обычный режим - раз в 20 шагов, ради батареи при сборе весь
         *  день. В замере это давало 8 строк лишь к 250 шагам. Раз в 5
         *  шагов - те же 8 строк за ~40, как в калибровке длины шага. */
        const val SLOPE_SAMPLE_EVERY = 5
        // v315. Было 8 строк = 40 шагов. Склон - самый шумный замер: на
        // нём меняется и амплитуда, и длина шага, а сам участок редко
        // бывает однородным. 20 строк = 100 шагов, это тот порядок, что
        // человек и проходит на реальной горе.
        const val SLOPE_MIN_ROWS = 20
        /** Если за столько шагов не пришло ни одной строки - что-то не
         *  так со сбором, ждать дальше бессмысленно. При частоте 1/5
         *  первая строка обязана прийти к пятому шагу. */
        const val SLOPE_DRY_STEPS = 25
        /** Столько без шагов и подтверждений - калибровка отменяется.
         *  15 минут: дольше человек на склоне не стоит, а забыть -
         *  запросто. */
        const val SLOPE_IDLE_MS = 15 * 60 * 1000L
        const val EXTRA_METRES = "metres"
        const val EXTRA_IS_RUN = "is_run"
        /** v391. Окно признаков походки. Десять секунд — компромисс:
         *  меньше даёт слабую автокорреляцию, больше смазывает смену
         *  режима на границе отрезков теста. */
        const val GAIT_WINDOW_MS = 10_000L
        /** Кольцо сырья: 2 с при 50 Гц. */
        const val RAW_RING = 100
        /** Бюджет окон сырья: RAW_PER_WINDOW штук на RAW_BUDGET_MS. */
        const val RAW_PER_WINDOW = 4
        const val RAW_BUDGET_MS = 300_000L
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
        const val ACTION_INCLINE_NONE = "incline_none"
        /** Сколько собирать признаки после нажатия метки. Две минуты:
         *  строка корпуса пишется раз в 10-20 шагов, то есть раз в
         *  5-10 секунд ходьбы - за окно набирается около двух десятков
         *  строк именно этого участка. Больше не нужно, меньше - мало
         *  для медиан и разбросов.
         */
        const val LABEL_WINDOW_MS = 120_000L
        /** L1.1: период и длительность окна фоновой обработки. */
        // v216. Через сколько без подтверждённого шага режим детектора
        // считается протухшим и перестаёт блокировать чиповый канал.
        // 15 с - тот же порог, что у сенсорных признаков: за это время
        // идущий человек делает ~28 шагов, то есть живой детектор
        // подтвердил бы их многократно. Молчание дольше означает, что
        // детектор спит, а не что человек стоит.
        const val MODE_STALE_MS = 15_000L
        const val BG_PERIOD_MS = 60_000L
        const val BG_WINDOW_MS = 12_000L
        // Калибровка темпа. MIN_CAL_INTERVALS оставлен прежним, 10: менять
        // порог выборки надо по собранным данным, а не по ощущению.
        // v315. Было 10. Проверено бутстрапом на 75 реальных интервалах
        // бега: при 10 точках медиана гуляет +-24 мс (8% от значения), при
        // 40 - +-11 мс (3%), при 60 - +-8 мс. После сорока выигрыш почти
        // исчезает, поэтому сорок и берём: дальше человек ходит зря.
        private const val MIN_CAL_INTERVALS = 40
        // Живой прогресс печатаем не на каждый шаг: onSensorChanged горячий,
        // sorted на нём - лишняя работа. Раз в 4 шага глазу достаточно.
        private const val CAL_UI_EVERY = 4
        // Межквартильный разброс к медиане, проценты. Ориентир от детектора:
        // он считает ритм стабильным при отклонении интервалов до 25% от
        // среднего. IQR теснее размаха, поэтому пороги ниже.
        private const val CAL_SPREAD_GOOD_PCT = 12
        private const val CAL_SPREAD_OK_PCT = 25
        /** Порог разброса для БЕГА. Взят из пустого промежутка в данных
         *  (удачные замеры 23-25%, неудачные 37-50%). */
        private const val RUN_SPREAD_OK_PCT = 31
        // v281. Тот же порог для уклона: классы расходятся на ~20% уровня,
        // отрезок с большим собственным разбросом их не разрешает.
        private const val SLOPE_SPREAD_OK_PCT = 25
        // v280. Сколько беговых шагов за день считать настоящим бегом.
        // Меньше - это вспышки Бег/Ходьба на торможении, известный хвост.
        private const val RUN_SEEN_STEPS = 300
        // Абсолютные границы правдоподобного человеческого шага, мс. НЕ из
        // профиля (иначе калибровка зависела бы от прежней калибровки).
        // 200 мс = 5 шагов/с (спринт), 2000 мс = очень медленный шаг.
        // Диагностика STEP_DETECTOR: потолок выборки, чтобы длинная
        // калибровка не раздувала память и строку журнала.
        private const val HW_DET_DIAG_CAP = 300
        // v295. Сколько беговых шагов нужно измерителю. 20 при темпе
        // 2.8 Гц это около 7 секунд бега - на синтетике разброс на такой
        // выборке уже устойчив.
        // v315. Было 20 - по той же причине, что и у ходьбы.
        private const val RUN_METER_MIN_STEPS = 40
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
