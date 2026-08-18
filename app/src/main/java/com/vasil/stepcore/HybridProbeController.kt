package com.vasil.stepcore

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

internal class HybridProbeController(
    context: Context,
    private val sensorManager: SensorManager,
    private val onResult: (EnergyProbe.Summary, String) -> Unit,
    private val onSignificant: () -> Unit,
    private val log: (String) -> Unit
) {
    private val accel =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gameRot =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val significant =
        sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private val handler = Handler(Looper.getMainLooper())
    private val probe = EnergyProbe()

    private val wakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "stepcore:hybrid_probe"
            )

    private var screenOff = false
    private var significantEnabled = true
    private var significantArmed = false
    private var active = false
    private var currentReason = ""
    private var probeSeq = 0L

    private val finishRunnable = Runnable {
        finishProbe()
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!active) return
            val t = event.timestamp / 1_000_000L
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    probe.onAccel(
                        event.values[0],
                        event.values[1],
                        event.values[2],
                        t
                    )

                Sensor.TYPE_GYROSCOPE ->
                    probe.onGyro(
                        event.values[0],
                        event.values[1],
                        event.values[2],
                        t
                    )

                Sensor.TYPE_GAME_ROTATION_VECTOR ->
                    probe.onGameRotation(event.values)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            significantArmed = false
            if (!screenOff || !significantEnabled) return

            log("[гибрид] SIGNIFICANT · дешёвый preflight")
            runCatching { onSignificant() }
                .onFailure {
                    log(
                        "[гибрид] preflight callback error: " +
                            (it.message ?: it.javaClass.simpleName)
                    )
                }

            handler.postDelayed(
                { armSignificant() },
                SIGNIFICANT_REARM_MS
            )
        }
    }

    fun setScreenOff(off: Boolean) {
        screenOff = off
        if (off) {
            armSignificant()
        } else {
            cancelSignificant()
            cancelProbe()
        }
    }

    fun setSignificantEnabled(enabled: Boolean) {
        significantEnabled = enabled
        if (!enabled) cancelSignificant()
        else if (screenOff) armSignificant()
    }

    fun requestProbe(reason: String): Boolean {
        if (!screenOff) return false
        if (active) return true

        val a = accel ?: return false
        val g = gyro ?: return false

        val now = SystemClock.elapsedRealtime()
        probeSeq += 1L
        currentReason = reason
        probe.reset(probeSeq, "HYBRID", now)

        val okA = sensorManager.registerListener(
            sensorListener,
            a,
            SensorManager.SENSOR_DELAY_GAME
        )
        val okG = sensorManager.registerListener(
            sensorListener,
            g,
            SensorManager.SENSOR_DELAY_GAME
        )
        if (!okA || !okG) {
            sensorManager.unregisterListener(sensorListener)
            return false
        }

        gameRot?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        active = true
        runCatching {
            wakeLock.acquire(PROBE_WAKE_TIMEOUT_MS)
        }

        handler.removeCallbacks(finishRunnable)
        handler.postDelayed(finishRunnable, PROBE_WINDOW_MS)

        log("[гибрид] probe START · $reason")
        return true
    }

    fun stop() {
        cancelSignificant()
        cancelProbe()
        handler.removeCallbacksAndMessages(null)
    }

    private fun finishProbe() {
        if (!active) return

        active = false
        handler.removeCallbacks(finishRunnable)
        sensorManager.unregisterListener(sensorListener)

        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
        }

        val now = SystemClock.elapsedRealtime()
        val summary = probe.finish(now)
        val reason = currentReason
        currentReason = ""

        runCatching {
            onResult(summary, reason)
        }.onFailure {
            log(
                "[гибрид] probe callback error: " +
                    (it.message ?: it.javaClass.simpleName)
            )
        }
    }

    private fun cancelProbe() {
        if (!active) return
        active = false
        handler.removeCallbacks(finishRunnable)
        sensorManager.unregisterListener(sensorListener)
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
        }
        currentReason = ""
    }

    private fun armSignificant() {
        if (
            !screenOff ||
            !significantEnabled ||
            significantArmed
        ) return

        val s = significant ?: return
        if (s.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT) return

        val ok = runCatching {
            sensorManager.requestTriggerSensor(triggerListener, s)
        }.getOrDefault(false)

        significantArmed = ok
    }

    private fun cancelSignificant() {
        val s = significant
        if (s != null) {
            runCatching {
                sensorManager.cancelTriggerSensor(triggerListener, s)
            }
        }
        significantArmed = false
    }

    companion object {
        const val PROBE_WINDOW_MS = 3_000L
        const val PROBE_WAKE_TIMEOUT_MS = 5_000L
        const val SIGNIFICANT_REARM_MS = 20_000L
    }
}
