package com.vasil.stepcore

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

/**
 * GPS-калибровка дистанции (V9.15). Single responsibility: измерить
 * пройденное расстояние по GPS-приёмнику для вычисления длины шага.
 *
 * ПРИВАТНОСТЬ (принцип проекта): GPS-приёмник читает спутники локально,
 * это НЕ сеть. Координаты НЕ сохраняются и НЕ отправляются - в памяти
 * живёт только текущая/предыдущая точка и накопленная сумма метров,
 * всё стирается при stop(). Никакого Google Maps / Fused Location /
 * A-GPS (тянет сеть) - только чистый LocationManager.GPS_PROVIDER.
 * Приёмник глушится сразу по stop() (removeUpdates) - не висит фоном.
 *
 * Фильтрация шума GPS:
 *  - точки с accuracy > 20 м отбрасываются (плохой сигнал);
 *  - сегменты со скоростью > 8 м/с отбрасываются (скачок GPS);
 *  - дистанция = сумма Location.distanceTo между валидными точками.
 */
class LocationCalibrator(private val context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var last: Location? = null
    private var metres = 0f
    private var fixes = 0
    private var running = false

    var onUpdate: ((Float, Int, Float) -> Unit)? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!running) return
            if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
            val prev = last
            if (prev != null) {
                val seg = prev.distanceTo(loc)
                val dt = (loc.time - prev.time) / 1000f
                val speed = if (dt > 0) seg / dt else 0f
                if (speed <= MAX_SPEED_MS) {
                    metres += seg
                    fixes++
                }
            }
            last = loc
            onUpdate?.invoke(metres, fixes, if (loc.hasAccuracy()) loc.accuracy else -1f)
        }
        @Deprecated("compat")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    fun isGpsEnabled() = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        last = null; metres = 0f; fixes = 0; running = true
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
    }

    fun stop(): Float {
        if (!running) return metres
        running = false
        lm.removeUpdates(listener)
        val result = metres
        last = null
        return result
    }

    val currentMetres get() = metres
    val fixCount get() = fixes

    companion object {
        private const val MAX_ACCURACY_M = 20f
        private const val MAX_SPEED_MS = 8f
    }
}
