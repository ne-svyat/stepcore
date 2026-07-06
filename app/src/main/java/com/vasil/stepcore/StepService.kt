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
    // Экран выключен: MIUI деградирует поток акселерометра (рваные пачки),
    // детектор на нём ложно уходит в TRANSPORT. Поэтому при выключенном
    // экране детектор отключается, считает аппаратный чип.
    @Volatile private var screenOff = false
    private var hwSessionAdded = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    hwSessionAdded = 0
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

    private var lastLoggedMode = "IDLE"
    private var idleSinceMs = 0L

    private var calibrating: String? = null
    private val calIntervals = ArrayList<Long>()
    private var calLastStepMs = 0L

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
        loadProfile()
        StepsState.steps.value = detector.stepCount

        createChannel()
        startForeground(NOTIF_ID, buildNotification(detector.stepCount))

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
        // если прошлая жизнь упала с исключением - имя в журнал
        prefs.getString(KEY_CRASH, null)?.let {
            logEvent("⚠ Падение сервиса: $it")
            prefs.edit().remove(KEY_CRASH).apply()
        }
        val lastAlive = prefs.getLong(KEY_ALIVE, 0L)
        if (lastAlive > 0) {
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

        scope.launch {
            while (true) {
                delay(1000)
                StepsState.diag.value =
                    "чистота %.0f%% | срезано грязью %d | гиро %.2f"
                        .format(detector.cleanliness * 100, detector.rejectedNoisy,
                            detector.gyroRms)
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
                // сброс после перезагрузки телефона (чип стартует с нуля)
                if (hwBaseline < 0 || hwTotal < hwBaseline) {
                    hwBaseline = hwTotal
                    persistHwBase()
                    return
                }
                if (screenOff) {
                    val delta = (hwTotal - hwBaseline).toInt()
                    hwBaseline = hwTotal
                    persistHwBase()
                    if (delta > 0) {
                        rolloverDayIfNeeded()
                        walkSteps += delta
                        hwSessionAdded += delta
                        detector.restoreCount(detector.stepCount + delta)
                        StepsState.steps.value = detector.stepCount
                        persistPrefs()
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_ID, buildNotification(detector.stepCount))
                    }
                    return
                }
                val accelGapMs =
                    if (lastAccelEventMs == 0L) Long.MAX_VALUE
                    else SystemClock.elapsedRealtime() - lastAccelEventMs
                if (!forceBackfill && accelGapMs < 60_000) {
                    // наш детектор жив - аппаратный просто следит
                    hwBaseline = hwTotal
                    persistHwBase()
                } else {
                    // была дыра (экран выключен / сервис спал) - досчитываем
                    val delta = (hwTotal - hwBaseline).toInt()
                    hwBaseline = hwTotal
                    persistHwBase()
                    forceBackfill = false
                    if (delta > 0) {
                        rolloverDayIfNeeded()
                        walkSteps += delta
                        detector.restoreCount(detector.stepCount + delta)
                        StepsState.steps.value = detector.stepCount
                        persistPrefs()
                        persistDb()
                        logEvent("Досчитано аппаратно: $delta шагов (телефон спал ${accelGapMs / 1000} с)")
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_ID, buildNotification(detector.stepCount))
                    }
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
                if (added > 0) {
                    if (calibrating != null) {
                        if (calLastStepMs > 0) calIntervals.add(timeMs - calLastStepMs)
                        calLastStepMs = timeMs
                    }
                    rolloverDayIfNeeded()
                    if (detector.mode == StepDetector.Mode.RUN) runSteps += added
                    else walkSteps += added
                    StepsState.steps.value = detector.stepCount
                    persistPrefs()
                    stepsSinceDbWrite += added
                    if (stepsSinceDbWrite >= 25) { stepsSinceDbWrite = 0; persistDb() }
                    if (StepsState.hapticEnabled.value) {
                        vibrator.vibrate(VibrationEffect.createOneShot(50, 255))
                    }
                    if (detector.stepCount - lastNotifiedSteps >= 10) {
                        lastNotifiedSteps = detector.stepCount
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_ID, buildNotification(detector.stepCount))
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
                    "TRANSPORT" -> "Транспорт — шаги остановлены"
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
        currentDay = today
        walkSteps = 0; runSteps = 0
        detector.restoreCount(0)
    }

    private fun persistHwBase() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_HW_BASE, hwBaseline).apply()
    }

    private fun persistPrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_DAY, currentDay)
            .putInt(KEY_STEPS, detector.stepCount)
            .putInt(KEY_WALK, walkSteps)
            .putInt(KEY_RUN, runSteps)
            .apply()
    }

    private fun persistDb() {
        val d = currentDay; val w = walkSteps; val r = runSteps
        scope.launch {
            AppDb.get(this@StepService).dao().upsertDay(DayRecord(d, w, r))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        runCatching { unregisterReceiver(screenReceiver) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_ALIVE, System.currentTimeMillis()).apply()
        wakeLock?.release(); wakeLock = null
        persistPrefs()
        persistDb()
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
        const val ACTION_CAL_WALK = "cal_walk"
        const val ACTION_CAL_RUN = "cal_run"
        const val ACTION_CAL_STOP = "cal_stop"
        const val ACTION_DIAG_START = "diag_start"
        const val ACTION_DIAG_STOP = "diag_stop"
        private const val IDLE_LOG_DELAY_MS = 4000L
        private const val HEARTBEAT_MS = 30_000L
    }
}
