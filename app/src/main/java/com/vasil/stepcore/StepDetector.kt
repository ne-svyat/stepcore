package com.vasil.stepcore

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Детектор V6.4.
 *
 * Фикс дребезга WALK<->RUN (терял ~14% беговых шагов):
 * 1. Гистерезис режимов: в RUN при emaInterval < 380 мс, обратно в WALK
 *    только при > 500 мс. Зона 380-500 - мёртвая: режим держится.
 *    Раньше граница была одна (400 мс), беговой каденс 350-420 мс
 *    болтался на ней, и каждый флип ронял детектор в карантин.
 * 2. В подтверждённом движении интервал шага проверяется по
 *    ОБЪЕДИНЁННОМУ диапазону (runMin..walkMax): смена темпа не
 *    выбрасывает шаг, режим догоняет через гистерезис.
 */
class StepDetector {

    enum class Mode { IDLE, WALK, RUN }

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

    private var gx = 0f; private var gy = 0f; private var gz = 9.81f
    private val gravityAlpha = 0.02f

    private val window = FloatArray(100)
    private var wIdx = 0
    private var wFilled = 0

    private var lastPeakMs = 0L
    private var crossedZero = true
    private var lastSign = 1

    private var prevAboveHalf = false

    private val pendingTimesMs = ArrayList<Long>()
    private val pendingAmps = ArrayList<Float>()
    private var lastConfirmedMs = 0L

    private var emaIntervalMs = 0f
    private var emaAmp = 0f

    private var gyroRmsSq = 0f
    private var shakeBlockUntilMs = 0L

    var stepCount = 0
        private set

    fun restoreCount(saved: Int) { stepCount = saved }

    fun onGyro(x: Float, y: Float, z: Float, timeMs: Long) {
        val sq = x * x + y * y + z * z
        gyroRmsSq += 0.05f * (sq - gyroRmsSq)
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

        val wasAboveHalf = prevAboveHalf
        prevAboveHalf = vert > threshold * 0.5f

        val sign = if (vert >= 0) 1 else -1
        if (sign != lastSign) { crossedZero = true; lastSign = sign }

        if (timeMs < shakeBlockUntilMs) return 0

        // В движении потолки - от "мягчайшего" из режимов, чтобы смена
        // темпа не резала пики до того, как гистерезис переключит режим
        val peakCap = if (mode != Mode.IDLE) profile.runPeakCap
        else maxOf(profile.walkPeakCap, profile.runPeakCap * 0.6f)
        val minInterval = if (mode != Mode.IDLE) profile.runMinIntervalMs else 280L

        val widthOk = vert >= WIDTH_EXEMPT_AMP || wasAboveHalf

        val isPeak = vert > threshold &&
                vert < peakCap &&
                widthOk &&
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

        if (mode != Mode.IDLE) {
            val interval = timeMs - lastConfirmedMs
            // объединённый диапазон обоих режимов
            if (interval in profile.runMinIntervalMs..profile.walkMaxIntervalMs) {
                lastConfirmedMs = timeMs
                updateEstimates(interval.toFloat(), vert)
                reclassify()
                stepCount++
                return 1
            }
            mode = Mode.IDLE
        }

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

    private fun classifyPending(): Mode {
        val t = pendingTimesMs
        val intervals = ArrayList<Long>(t.size - 1)
        for (i in 1 until t.size) intervals.add(t[i] - t[i - 1])
        val mean = intervals.average()
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

    /**
     * Гистерезис: границы входа и выхода из RUN разнесены.
     * < RUN_ENTER_MS -> RUN; > RUN_EXIT_MS -> WALK; между - держим режим.
     */
    private fun reclassify() {
        mode = when {
            emaIntervalMs < RUN_ENTER_MS -> Mode.RUN
            emaIntervalMs > RUN_EXIT_MS -> Mode.WALK
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

    private fun maxTimeout() =
        if (mode == Mode.RUN) profile.runMaxIntervalMs + 400 else profile.walkMaxIntervalMs + 300

    companion object {
        private const val QUARANTINE_STEPS = 4
        private const val PENDING_TIMEOUT_MS = 2000L
        private const val SHAKE_STICKY_MS = 3000L
        private const val WIDTH_EXEMPT_AMP = 3.5f
        private const val RUN_ENTER_MS = 380f
        private const val RUN_EXIT_MS = 500f
    }
}
