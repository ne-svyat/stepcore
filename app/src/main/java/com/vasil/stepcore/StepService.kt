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

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibrator = vm.defaultVibrator

        // восстановить шаги за сегодня
        currentDay = LocalDate.now().toString()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_DAY, "") == currentDay) {
            detector.restoreCount(prefs.getInt(KEY_STEPS, 0))
        }
        StepsState.steps.value = detector.stepCount

        createChannel()
        startForeground(NOTIF_ID, buildNotification(detector.stepCount))
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        StepsState.serviceRunning.value = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val added = detector.onAccel(
            event.values[0], event.values[1], event.values[2],
            event.timestamp / 1_000_000
        )
        if (added > 0) {
            // смена суток - обнуление
            val today = LocalDate.now().toString()
            if (today != currentDay) {
                currentDay = today
                detector.restoreCount(0)
            }
            StepsState.steps.value = detector.stepCount
            persist()
            if (StepsState.hapticEnabled.value) {
                vibrator.vibrate(VibrationEffect.createOneShot(15, 120))
            }
            if (detector.stepCount - lastNotifiedSteps >= 10) {
                lastNotifiedSteps = detector.stepCount
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(detector.stepCount))
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
    }
}
