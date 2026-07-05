package com.vasil.stepcore

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Детектор V7.1 = V7 + фикс регрессии тапов.
 *
 * Дыра V6.5-V7: пик >= WIDTH_EXEMPT_AMP (3.5) полностью обходил
 * анти-тап по ширине (освобождение вводилось ради крутого фронта бега).
 * Сильный ритмичный таппинг пробивал 3.5 и проходил карантин как RUN.
 *
 * Фикс 1 (гиро-пол): узкий пик без ширины принимается только если
 * телефон реально вращается (бег в руке/кармане качает корпус,
 * RMS гироскопа - единицы рад/с; при таппинге телефон почти неподвижен,
 * десятые доли). Инверсия гироскоп-стража: он режет слишком сильное
 * вращение, здесь режем слишком слабое при сильных узких пиках.
 * Без гироскопа на устройстве - поведение V7 (освобождение по амплитуде).
 *
 * Фикс 2 (тёплый вход): 2 шагов в окне 5 с мало - дополнительно
 * амплитуда должна совпасть с профилем прежней сессии (±50% от EMA).
 * Тапы сразу после остановки ходьбы больше не наследуют дешёвый вход.
 */
class StepDetector {

    enum class Mode { IDLE, WALK, RUN, TRANSPORT }

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

    /** Есть ли гироскоп на устройстве (ставит StepService при старте). */
    var hasGyro = false

    // диагностика отбраковок (пока без UI; выводить при разборе провалов)
    var rejectedNoRotation = 0
        private set
    var rejectedWarmAmp = 0
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

    private var motionLostAtMs = 0L
    private var lastMotionMode = Mode.IDLE
    private var lastMotionAmp = 0f   // EMA-амплитуда прежней сессии для тёплого входа

    private var emaIntervalMs = 0f
    private var emaAmp = 0f

    private var gyroRmsSq = 0f
    private var shakeBlockUntilMs = 0L

    // --- транспорт ---
    private val candidatePeaksMs = ArrayDeque<Long>()
    private var lastConfirmInWindowMs = 0L
    private var transportBlockUntilMs = 0L

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
            motionLostAtMs = 0L
            dropMode(warm = false, timeMs = timeMs)
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
            if (mode != Mode.RUN) {
                pendingTimesMs.clear(); pendingAmps.clear()
                dropMode(warm = false, timeMs = timeMs)
            }
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

        val peakCap = if (mode == Mode.WALK || mode == Mode.RUN) profile.runPeakCap
        else maxOf(profile.walkPeakCap, profile.runPeakCap * 0.6f)
        val minInterval = if (mode == Mode.WALK || mode == Mode.RUN) profile.runMinIntervalMs else 280L

        // Фикс тапов: освобождение по амплитуде теперь требует вращения корпуса.
        // Бег качает телефон (RMS гироскопа много выше GYRO_FLOOR_FOR_EXEMPT),
        // тап по неподвижному телефону - нет. Без гироскопа - как в V7.
        val rotationOk = !hasGyro || sqrt(gyroRmsSq) >= GYRO_FLOOR_FOR_EXEMPT
        val widthOk = wasAboveHalf || (vert >= WIDTH_EXEMPT_AMP && rotationOk)
        if (!wasAboveHalf && vert >= WIDTH_EXEMPT_AMP && !rotationOk) rejectedNoRotation++

        val isPeak = vert > threshold &&
                vert < peakCap &&
                widthOk &&
                timeMs - lastPeakMs >= minInterval &&
                crossedZero &&
                wFilled >= 50

        if (!isPeak) {
            val timeout = maxTimeout()
            if ((mode == Mode.WALK || mode == Mode.RUN) &&
                timeMs - lastConfirmedMs > timeout
            ) dropMode(warm = true, timeMs = timeMs)
            if (mode == Mode.IDLE && pendingTimesMs.isNotEmpty() &&
                timeMs - pendingTimesMs.last() > PENDING_TIMEOUT_MS
            ) { pendingTimesMs.clear(); pendingAmps.clear() }
            if (mode == Mode.TRANSPORT &&
                (candidatePeaksMs.isEmpty() ||
                        timeMs - candidatePeaksMs.last() > TRANSPORT_EXIT_SILENCE_MS)
            ) mode = Mode.IDLE
            return 0
        }

        lastPeakMs = timeMs
        crossedZero = false

        candidatePeaksMs.addLast(timeMs)
        while (candidatePeaksMs.isNotEmpty() &&
            timeMs - candidatePeaksMs.first() > TRANSPORT_WINDOW_MS
        ) candidatePeaksMs.removeFirst()

        val transportSignature = candidatePeaksMs.size >= TRANSPORT_PEAKS_MIN &&
                timeMs - lastConfirmInWindowMs > TRANSPORT_WINDOW_MS
        if (transportSignature || mode == Mode.TRANSPORT) {
            if (transportSignature) {
                mode = Mode.TRANSPORT
                transportBlockUntilMs = timeMs + TRANSPORT_STICKY_MS
                motionLostAtMs = 0L
                pendingTimesMs.clear(); pendingAmps.clear()
            }
            if (timeMs < transportBlockUntilMs) return 0
            mode = Mode.IDLE
        }

        if (mode == Mode.WALK || mode == Mode.RUN) {
            val interval = timeMs - lastConfirmedMs
            if (interval in profile.runMinIntervalMs..profile.walkMaxIntervalMs) {
                lastConfirmedMs = timeMs
                lastConfirmInWindowMs = timeMs
                updateEstimates(interval.toFloat(), vert)
                reclassify()
                stepCount++
                return 1
            }
            dropMode(warm = true, timeMs = timeMs)
        }

        pendingTimesMs.add(timeMs)
        pendingAmps.add(vert)
        val needed = quarantineNeeded(timeMs)
        while (pendingTimesMs.size > needed) { pendingTimesMs.removeAt(0); pendingAmps.removeAt(0) }
        if (pendingTimesMs.size == needed && needed >= 2) {
            val cls = classifyPending(timeMs)
            if (cls == Mode.WALK || cls == Mode.RUN) {
                mode = cls
                lastConfirmedMs = timeMs
                lastConfirmInWindowMs = timeMs
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

    private fun quarantineNeeded(timeMs: Long): Int =
        if (motionLostAtMs > 0L && timeMs - motionLostAtMs < WARM_WINDOW_MS &&
            (lastMotionMode == Mode.WALK || lastMotionMode == Mode.RUN)
        ) WARM_QUARANTINE_STEPS else QUARANTINE_STEPS

    private fun dropMode(warm: Boolean, timeMs: Long) {
        if (mode == Mode.WALK || mode == Mode.RUN) {
            lastMotionMode = mode
            lastMotionAmp = emaAmp
            motionLostAtMs = if (warm) timeMs else 0L
        }
        if (mode != Mode.TRANSPORT) mode = Mode.IDLE
    }

    private fun classifyPending(timeMs: Long): Mode {
        val t = pendingTimesMs
        val intervals = ArrayList<Long>(t.size - 1)
        for (i in 1 until t.size) intervals.add(t[i] - t[i - 1])
        val mean = intervals.average()
        if (!intervals.all { abs(it - mean) / mean < 0.25 }) return Mode.IDLE
        val amp = pendingAmps.average().toFloat()
        val byRange = when {
            mean >= profile.walkMinIntervalMs && mean <= profile.walkMaxIntervalMs &&
                    amp < profile.walkPeakCap -> Mode.WALK
            mean >= profile.runMinIntervalMs && mean <= profile.runMaxIntervalMs &&
                    amp < profile.runPeakCap -> Mode.RUN
            else -> Mode.IDLE
        }
        if (byRange != Mode.IDLE && quarantineNeeded(timeMs) == WARM_QUARANTINE_STEPS) {
            // тёплый вход: тот же режим И тот же амплитудный профиль (±50%)
            if (byRange != lastMotionMode) return Mode.IDLE
            if (lastMotionAmp > 0f &&
                (amp < lastMotionAmp * WARM_AMP_LO || amp > lastMotionAmp * WARM_AMP_HI)
            ) { rejectedWarmAmp++; return Mode.IDLE }
        }
        return byRange
    }

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
        private const val WARM_QUARANTINE_STEPS = 2
        private const val WARM_WINDOW_MS = 5000L
        private const val PENDING_TIMEOUT_MS = 2000L
        private const val SHAKE_STICKY_MS = 3000L
        private const val WIDTH_EXEMPT_AMP = 3.5f
        // Пол вращения для освобождённых пиков. Бег: RMS в разы выше.
        // Таппинг по неподвижному телефону: около нуля. При провале бега
        // крутить ТОЛЬКО эту константу вниз (0.5), один раз.
        private const val GYRO_FLOOR_FOR_EXEMPT = 0.8f
        // Допуск амплитуды тёплого входа относительно прежней сессии
        private const val WARM_AMP_LO = 0.5f
        private const val WARM_AMP_HI = 1.5f
        private const val RUN_ENTER_MS = 380f
        private const val RUN_EXIT_MS = 500f
        private const val TRANSPORT_WINDOW_MS = 15000L
        private const val TRANSPORT_PEAKS_MIN = 15
        private const val TRANSPORT_STICKY_MS = 10000L
        private const val TRANSPORT_EXIT_SILENCE_MS = 5000L
    }
}
