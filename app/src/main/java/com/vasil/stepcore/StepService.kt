package com.vasil.stepcore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder

class StepService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val detector = StepDetector()
    private var lastNotifiedSteps = -1

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification(0))
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // SENSOR_DELAY_GAME ~ 50 Гц - достаточно для ходьбы, экономнее 100 Гц
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        StepsState.serviceRunning.value = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val stepped = detector.onAccel(
            event.values[0], event.values[1], event.values[2],
            event.timestamp / 1_000_000
        )
        if (stepped) {
            StepsState.steps.value = detector.stepCount
            // уведомление обновляем не чаще раза в 10 шагов - иначе система душит
            if (detector.stepCount - lastNotifiedSteps >= 10) {
                lastNotifiedSteps = detector.stepCount
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(detector.stepCount))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
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
    }
}
