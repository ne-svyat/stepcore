package com.vasil.stepcore

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Детектор V3 = V2 (ритм-карантин) + три стража против тряски.
 *
 * Страж 1 - ГИРОСКОП: рука не может трясти телефон без вращения
 * (кисть - шарнир). Ходьба почти не вращает телефон. RMS вращения
 * выше порога -> блок + залипание блока на 3 сек.
 *
 * Страж 2 - АМПЛИТУДА: удар пятки на телефоне 1-4 м/с2. Тряска рукой
 * легко даёт 8+. Слишком сильный пик - не шаг.
 *
 * Страж 3 - ГРАВИТАЦИЯ: при жёсткой тряске EMA-оценка гравитации
 * разваливается и вертикальная проекция ловит горизонтальные рывки.
 * Если |g_оценка| ушла от 9.81 больше чем на 25% - счёт заморожен,
 * пока оценка не восстановится.
 */
class StepDetector {

    // --- оценка гравитации ---
    private var gx = 0f; private var gy = 0f; private var gz = 9.81f
    private val gravityAlpha = 0.02f

    // --- адаптивный порог ---
    private val window = FloatArray(100)
    private var wIdx = 0
    private var wFilled = 0

    // --- анти-дребезг ---
    private var lastPeakMs = 0L
    private var crossedZero = true
    private var lastSign = 1

    // --- ритм-карантин ---
    private val pendingTimesMs = ArrayList<Long>()
    private var walking = false
    private var lastConfirmedMs = 0L

    // --- страж гироскопа ---
    private var gyroRmsSq = 0f          // EMA квадрата угловой скорости
    private var shakeBlockUntilMs = 0L  // залипание блока после тряски

    var stepCount = 0
        private set

    fun restoreCount(saved: Int) { stepCount = saved }

    /** События гироскопа. Вызывается сервисом. */
    fun onGyro(x: Float, y: Float, z: Float, timeMs: Long) {
        val sq = x * x + y * y + z * z
        gyroRmsSq += 0.05f * (sq - gyroRmsSq) // EMA ~ окно 1 сек при 50 Гц
        if (sqrt(gyroRmsSq) > GYRO_SHAKE_RADS) {
            shakeBlockUntilMs = timeMs + SHAKE_STICKY_MS
            // тряска обесценивает и накопленный карантин, и режим ходьбы
            pendingTimesMs.clear()
            walking = false
        }
    }

    fun onAccel(x: Float, y: Float, z: Float, timeMs: Long): Int {
        gx += gravityAlpha * (x - gx)
        gy += gravityAlpha * (y - gy)
        gz += gravityAlpha * (z - gz)
        val lx = x - gx; val ly = y - gy; val lz = z - gz

        val gn = sqrt(gx * gx + gy * gy + gz * gz)

        // Страж 3: оценка гравитации недостоверна - замораживаем всё
        if (gn < 9.81f * 0.75f || gn > 9.81f * 1.25f) {
            pendingTimesMs.clear()
            walking = false
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

        // Страж 1: активная или недавняя тряска
        if (timeMs < shakeBlockUntilMs) return 0

        val isPeak = vert > threshold &&
                vert < PEAK_CAP &&              // Страж 2: слишком сильный пик - не шаг
                timeMs - lastPeakMs >= MIN_STEP_MS &&
                crossedZero &&
                wFilled >= 50

        if (!isPeak) {
            if (walking && timeMs - lastConfirmedMs > WALK_TIMEOUT_MS) walking = false
            if (!walking && pendingTimesMs.isNotEmpty() &&
                timeMs - pendingTimesMs.last() > PENDING_TIMEOUT_MS
            ) pendingTimesMs.clear()
            return 0
        }

        lastPeakMs = timeMs
        crossedZero = false

        if (walking) {
            val interval = timeMs - lastConfirmedMs
            if (interval in MIN_STEP_MS..WALK_TIMEOUT_MS) {
                lastConfirmedMs = timeMs
                stepCount++
                return 1
            }
            walking = false
        }

        pendingTimesMs.add(timeMs)
        if (pendingTimesMs.size > QUARANTINE_STEPS) pendingTimesMs.removeAt(0)
        if (pendingTimesMs.size == QUARANTINE_STEPS && rhythmIsStable()) {
            walking = true
            lastConfirmedMs = timeMs
            val granted = pendingTimesMs.size
            pendingTimesMs.clear()
            stepCount += granted
            return granted
        }
        return 0
    }

    private fun rhythmIsStable(): Boolean {
        val t = pendingTimesMs
        val intervals = ArrayList<Long>(t.size - 1)
        for (i in 1 until t.size) intervals.add(t[i] - t[i - 1])
        if (intervals.any { it !in MIN_STEP_MS..WALK_TIMEOUT_MS }) return false
        val mean = intervals.average()
        return intervals.all { abs(it - mean) / mean < 0.25 }
    }

    companion object {
        private const val QUARANTINE_STEPS = 4
        private const val WALK_TIMEOUT_MS = 1200L
        private const val PENDING_TIMEOUT_MS = 2000L
        private const val MIN_STEP_MS = 300L        // каденс-потолок 3.3 Гц
        private const val PEAK_CAP = 8f             // м/с2, потолок амплитуды шага
        private const val GYRO_SHAKE_RADS = 3.5f    // рад/с, порог тряски
        private const val SHAKE_STICKY_MS = 3000L   // залипание блока после тряски
    }
}
