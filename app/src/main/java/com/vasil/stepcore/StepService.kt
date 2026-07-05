package com.vasil.stepcore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.time.LocalDate

class StepService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private val detector = StepDetector()
    private var lastNotifiedSteps = -1
    private var currentDay: String = ""

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
            detector.restoreCount(prefs.getInt(KEY_STEPS, 0))
        }
        // ФИКС: сервис сам читает настройку вибрации из prefs,
        // а не ждёт, пока её выставит открытый экран
        StepsState.hapticEnabled.value = prefs.getBoolean("haptic", false)
        loadProfile()
        StepsState.steps.value = detector.stepCount

        createChannel()
        startForeground(NOTIF_ID, buildNotification(detector.stepCount))

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
        StepsState.serviceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAL_WALK -> startCalibration("walk")
            ACTION_CAL_RUN -> startCalibration("run")
            ACTION_CAL_STOP -> finishCalibration()
        }
        return START_STICKY
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
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit()
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
        val timeMs = event.timestamp / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (calibrating == null) {
                    detector.onGyro(event.values[0], event.values[1], event.values[2], timeMs)
                }
                return
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val added = detector.onAccel(
                    event.values[0], event.values[1], event.values[2], timeMs
                )
                if (added > 0) {
                    if (calibrating != null) {
                        if (calLastStepMs > 0) calIntervals.add(timeMs - calLastStepMs)
                        calLastStepMs = timeMs
                    }
                    val today = LocalDate.now().toString()
                    if (today != currentDay) {
                        currentDay = today
                        detector.restoreCount(0)
                    }
                    StepsState.steps.value = detector.stepCount
                    StepsState.mode.value = detector.mode.name
                    persist()
                    if (StepsState.hapticEnabled.value) {
                        // ФИКС: 50 мс на максимальной амплитуде -
                        // короче/тише мотор Xiaomi не отрабатывает
                        vibrator.vibrate(VibrationEffect.createOneShot(50, 255))
                    }
                    if (detector.stepCount - lastNotifiedSteps >= 10) {
                        lastNotifiedSteps = detector.stepCount
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_ID, buildNotification(detector.stepCount))
                    }
                } else {
                    val m = detector.mode.name
                    if (StepsState.mode.value != m) StepsState.mode.value = m
                }
            }
        }
    }

    private fun persist() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_DAY, currentDay)
            .putInt(KEY_STEPS, detector.stepCount)
            .apply()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        persist()
        StepsState.serviceRunning.value = false
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
        const val ACTION_CAL_WALK = "cal_walk"
        const val ACTION_CAL_RUN = "cal_run"
        const val ACTION_CAL_STOP = "cal_stop"
    }
}
