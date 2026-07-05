package com.vasil.stepcore

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Детектор шагов V2: пороговый детектор + карантин по ритму.
 *
 * Проблема V1: одиночный рывок корпуса (повернулся на месте) неотличим
 * от одиночного шага по амплитуде.
 * Решение: настоящая ходьба ритмична. Кандидаты в шаги копятся в буфере
 * и попадают в счётчик только когда 4 подряд интервала между ними
 * стабильны (разброс < 25%). После подтверждения ходьбы шаги считаются
 * сразу, пока ритм не сломается.
 */
class StepDetector {

    // --- оценка гравитации (EMA) ---
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

    // --- карантин по ритму ---
    private val pendingTimesMs = ArrayList<Long>() // кандидаты, ещё не подтверждённые
    private var walking = false                     // режим подтверждённой ходьбы
    private var lastConfirmedMs = 0L

    var stepCount = 0
        private set

    fun restoreCount(saved: Int) { stepCount = saved }

    /**
     * @return сколько шагов ДОБАВИЛОСЬ этим событием (0, 1, или 4 —
     * когда карантинный буфер подтверждается целиком).
     */
    fun onAccel(x: Float, y: Float, z: Float, timeMs: Long): Int {
        gx += gravityAlpha * (x - gx)
        gy += gravityAlpha * (y - gy)
        gz += gravityAlpha * (z - gz)
        val lx = x - gx; val ly = y - gy; val lz = z - gz

        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        if (gn < 1f) return 0
        val vert = (lx * gx + ly * gy + lz * gz) / gn

        window[wIdx] = abs(vert)
        wIdx = (wIdx + 1) % window.size
        if (wFilled < window.size) wFilled++
        val sorted = window.copyOf(wFilled).sortedArray()
        val median = sorted[wFilled / 2]
        val threshold = maxOf(median * 1.4f, 0.6f)

        val sign = if (vert >= 0) 1 else -1
        if (sign != lastSign) { crossedZero = true; lastSign = sign }

        val isPeak = vert > threshold &&
                timeMs - lastPeakMs >= 250 &&
                crossedZero &&
                wFilled >= 50
        if (!isPeak) {
            // Ритм сломался: слишком долгая пауза после последнего шага
            if (walking && timeMs - lastConfirmedMs > WALK_TIMEOUT_MS) walking = false
            if (!walking && pendingTimesMs.isNotEmpty() &&
                timeMs - pendingTimesMs.last() > PENDING_TIMEOUT_MS
            ) pendingTimesMs.clear()
            return 0
        }

        lastPeakMs = timeMs
        crossedZero = false

        // --- режим подтверждённой ходьбы: считаем сразу ---
        if (walking) {
            val interval = timeMs - lastConfirmedMs
            if (interval in 250..WALK_TIMEOUT_MS) {
                lastConfirmedMs = timeMs
                stepCount++
                return 1
            }
            // пауза слишком длинная - выходим из режима ходьбы, кандидат в карантин
            walking = false
        }

        // --- карантин ---
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

    /** Стабильность ритма: все интервалы близки к среднему (разброс < 25%). */
    private fun rhythmIsStable(): Boolean {
        val t = pendingTimesMs
        val intervals = ArrayList<Long>(t.size - 1)
        for (i in 1 until t.size) intervals.add(t[i] - t[i - 1])
        if (intervals.any { it !in 250..WALK_TIMEOUT_MS }) return false
        val mean = intervals.average()
        return intervals.all { abs(it - mean) / mean < 0.25 }
    }

    companion object {
        private const val QUARANTINE_STEPS = 4       // шагов для подтверждения ходьбы
        private const val WALK_TIMEOUT_MS = 1200L    // макс. интервал между шагами
        private const val PENDING_TIMEOUT_MS = 2000L // сброс карантина после паузы
    }
}
