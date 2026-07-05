package com.vasil.stepcore

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Детектор V4: два режима (WALK / RUN) с автоматическим переключением.
 *
 * Физика: у бега каденс 2.4-4 Гц и амплитуда 6-20 м/с2, у ходьбы
 * 1.4-2.5 Гц и 1-4 м/с2. Один набор порогов не покрывает оба режима:
 * потолки, спасающие от тряски рукой, режут бег.
 *
 * Решение: детектор ведёт скользящий каденс и медианную амплитуду
 * ПОДТВЕРЖДЁННЫХ шагов. Профиль совпал с бегом -> потолки амплитуды
 * и гироскопа поднимаются. Тряска всё равно блокируется: каденс > 4 Гц,
 * или гироскоп выше даже бегового потолка, или ритм нестабилен.
 *
 * Персональная калибровка: диапазоны можно заменить измеренными
 * у конкретного человека (см. setProfile).
 */
class StepDetector {

    enum class Mode { IDLE, WALK, RUN }

    // --- профили порогов (могут быть переопределены калибровкой) ---
    data class Profile(
        var walkMinIntervalMs: Long = 400,
        var walkMaxIntervalMs: Long = 1200,
        var walkPeakCap: Float = 6f,
        var walkGyroCap: Float = 3.5f,
        var runMinIntervalMs: Long = 250,
        var runMaxIntervalMs: Long = 420,
        var runPeakCap: Float = 22f,
        var runGyroCap: Float = 8f,
    )
    var profile = Profile()

    var mode = Mode.IDLE
        private set

    // --- гравитация ---
    private var gx = 0f; private var gy = 0f; private var gz = 9.81f
    private val gravityAlpha = 0.02f

    // --- адаптивный порог ---
    private val window = FloatArray(100)
    private var wIdx = 0
    private var wFilled = 0

    // --- пики ---
    private var lastPeakMs = 0L
    private var crossedZero = true
    private var lastSign = 1

    // --- карантин ---
    private val pendingTimesMs = ArrayList<Long>()
    private val pendingAmps = ArrayList<Float>()
    private var lastConfirmedMs = 0L

    // --- скользящие оценки для классификации ---
    private var emaIntervalMs = 0f   // средний интервал подтверждённых шагов
    private var emaAmp = 0f          // средняя амплитуда подтверждённых шагов

    // --- гироскоп ---
    private var gyroRmsSq = 0f
    private var shakeBlockUntilMs = 0L

    var stepCount = 0
        private set

    fun restoreCount(saved: Int) { stepCount = saved }

    fun onGyro(x: Float, y: Float, z: Float, timeMs: Long) {
        val sq = x * x + y * y + z * z
        gyroRmsSq += 0.05f * (sq - gyroRmsSq)
        // Потолок гироскопа зависит от режима: бегу разрешено трястись сильнее
        val cap = if (mode == Mode.RUN) profile.runGyroCap else profile.walkGyroCap
        if (sqrt(gyroRmsSq) > cap) {
            shakeBlockUntilMs = timeMs + SHAKE_STICKY_MS
            pendingTimesMs.clear(); pendingAmps.clear()
            mode = Mode.IDLE
        }
    }

    fun onAccel(x: Float, y: Float, z: Float, timeMs: Long): Int {
        gx += gravityAlpha * (x - gx)
        gy += gravityAlpha * (y - gy)
        gz += gravityAlpha * (z - gz)
        val lx = x - gx; val ly = y - gy; val lz = z - gz

        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        // Страж гравитации. При беге допуск шире: оценка болтается сильнее.
        val gravTol = if (mode == Mode.RUN) 0.45f else 0.25f
        if (gn < 9.81f * (1 - gravTol) || gn > 9.81f * (1 + gravTol)) {
            if (mode != Mode.RUN) { pendingTimesMs.clear(); pendingAmps.clear(); mode = Mode.IDLE }
            return 0
        }

        val vert = (lx * gx + ly * gy + lz * gz) / gn

        window[wIdx] = abs(vert)
        wIdx = (wIdx + 1) % window.size
        if (wFilled < window.size) wFilled++
        val sorted = window.copyOf(wFilled).sortedArray()
        val median = sorted[wFilled / 2]
        val threshold = maxOf(median * 1.4f, 0.6f)

        val sign = if (vert >= 0) 1 else -1
        if (sign != lastSign) { crossedZero = true; lastSign = sign }

        if (timeMs < shakeBlockUntilMs) return 0

        // Абсолютный потолок: даже бег не даёт больше runPeakCap
        val peakCap = if (mode == Mode.RUN) profile.runPeakCap else
            maxOf(profile.walkPeakCap, profile.runPeakCap * 0.6f) // окно для входа в бег
        val minInterval = if (mode == Mode.RUN) profile.runMinIntervalMs else 280L

        val isPeak = vert > threshold &&
                vert < peakCap &&
                timeMs - lastPeakMs >= minInterval &&
                crossedZero &&
                wFilled >= 50

        if (!isPeak) {
            val timeout = maxTimeout()
            if (mode != Mode.IDLE && timeMs - lastConfirmedMs > timeout) mode = Mode.IDLE
            if (mode == Mode.IDLE && pendingTimesMs.isNotEmpty() &&
                timeMs - pendingTimesMs.last() > PENDING_TIMEOUT_MS
            ) { pendingTimesMs.clear(); pendingAmps.clear() }
            return 0
        }

        lastPeakMs = timeMs
        crossedZero = false

        // --- подтверждённый режим: шаг сразу, плюс обновление оценок ---
        if (mode != Mode.IDLE) {
            val interval = timeMs - lastConfirmedMs
            if (interval in minIntervalFor(mode)..maxIntervalFor(mode)) {
                lastConfirmedMs = timeMs
                updateEstimates(interval.toFloat(), vert)
                reclassify()
                stepCount++
                return 1
            }
            mode = Mode.IDLE
        }

        // --- карантин ---
        pendingTimesMs.add(timeMs)
        pendingAmps.add(vert)
        if (pendingTimesMs.size > QUARANTINE_STEPS) {
            pendingTimesMs.removeAt(0); pendingAmps.removeAt(0)
        }
        if (pendingTimesMs.size == QUARANTINE_STEPS) {
            val cls = classifyPending()
            if (cls != Mode.IDLE) {
                mode = cls
                lastConfirmedMs = timeMs
                emaIntervalMs = avgPendingInterval()
                emaAmp = pendingAmps.average().toFloat()
                val granted = pendingTimesMs.size
                pendingTimesMs.clear(); pendingAmps.clear()
                stepCount += granted
                return granted
            }
        }
        return 0
    }

    /** Классификация карантинного буфера: WALK, RUN или отказ. */
    private fun classifyPending(): Mode {
        val t = pendingTimesMs
        val intervals = ArrayList<Long>(t.size - 1)
        for (i in 1 until t.size) intervals.add(t[i] - t[i - 1])
        val mean = intervals.average()
        // ритм стабилен?
        if (!intervals.all { abs(it - mean) / mean < 0.25 }) return Mode.IDLE
        val amp = pendingAmps.average().toFloat()
        return when {
            mean >= profile.walkMinIntervalMs && mean <= profile.walkMaxIntervalMs &&
                    amp < profile.walkPeakCap -> Mode.WALK
            mean >= profile.runMinIntervalMs && mean <= profile.runMaxIntervalMs &&
                    amp < profile.runPeakCap -> Mode.RUN
            else -> Mode.IDLE
        }
    }

    /** Переключение WALK<->RUN на лету по скользящим оценкам. */
    private fun reclassify() {
        mode = when {
            emaIntervalMs <= profile.runMaxIntervalMs && emaAmp > profile.walkPeakCap * 0.8f -> Mode.RUN
            emaIntervalMs >= profile.walkMinIntervalMs -> Mode.WALK
            else -> mode
        }
    }

    private fun updateEstimates(intervalMs: Float, amp: Float) {
        emaIntervalMs += 0.3f * (intervalMs - emaIntervalMs)
        emaAmp += 0.3f * (amp - emaAmp)
    }

    private fun avgPendingInterval(): Float {
        var s = 0L
        for (i in 1 until pendingTimesMs.size) s += pendingTimesMs[i] - pendingTimesMs[i - 1]
        return s.toFloat() / (pendingTimesMs.size - 1)
    }

    private fun minIntervalFor(m: Mode) =
        if (m == Mode.RUN) profile.runMinIntervalMs else profile.walkMinIntervalMs
    private fun maxIntervalFor(m: Mode) =
        if (m == Mode.RUN) profile.runMaxIntervalMs + 200 else profile.walkMaxIntervalMs
    private fun maxTimeout() =
        if (mode == Mode.RUN) profile.runMaxIntervalMs + 400 else profile.walkMaxIntervalMs + 300

    companion object {
        private const val QUARANTINE_STEPS = 4
        private const val PENDING_TIMEOUT_MS = 2000L
        private const val SHAKE_STICKY_MS = 3000L
    }
}
