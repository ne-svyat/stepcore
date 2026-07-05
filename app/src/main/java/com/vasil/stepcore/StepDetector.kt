package com.vasil.stepcore

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Детектор шагов V1.
 *
 * Принцип:
 * 1. Гравитацию оцениваем экспоненциальным сглаживанием (EMA) сырого сигнала.
 * 2. Линейное ускорение = сырое - гравитация.
 * 3. Проецируем его на направление гравитации -> вертикальный импульс шага,
 *    не зависящий от того, как телефон лежит в кармане.
 * 4. Порог адаптивный: медиана |сигнала| за 2 секунды * коэффициент.
 * 5. Анти-дребезг: минимум 250 мс между шагами + обязательное пересечение нуля.
 */
class StepDetector {

    // --- оценка гравитации ---
    private var gx = 0f; private var gy = 0f; private var gz = 9.81f
    private val gravityAlpha = 0.02f // медленное сглаживание ~ фильтр 0.3 Гц

    // --- адаптивный порог ---
    private val window = FloatArray(100) // ~2 сек при 50 Гц
    private var wIdx = 0
    private var wFilled = 0

    // --- анти-дребезг ---
    private var lastStepMs = 0L
    private var crossedZero = true
    private var lastSign = 1

    var stepCount = 0
        private set

    fun reset() { stepCount = 0 }

    /** Вызывается на каждое событие акселерометра. @return true если шаг. */
    fun onAccel(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        // 1-2. гравитация и линейное ускорение
        gx += gravityAlpha * (x - gx)
        gy += gravityAlpha * (y - gy)
        gz += gravityAlpha * (z - gz)
        val lx = x - gx; val ly = y - gy; val lz = z - gz

        // 3. проекция на вертикаль (нормируем вектор гравитации)
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        if (gn < 1f) return false
        val vert = (lx * gx + ly * gy + lz * gz) / gn

        // 4. адаптивный порог по медиане окна
        window[wIdx] = abs(vert)
        wIdx = (wIdx + 1) % window.size
        if (wFilled < window.size) wFilled++
        val sorted = window.copyOf(wFilled).sortedArray()
        val median = sorted[wFilled / 2]
        // шумовой пол 0.6 м/с2 отсекает микротряску; 1.4 - усиление над медианой
        val threshold = maxOf(median * 1.4f, 0.6f)

        // 5. пересечение нуля между пиками (пик без смены знака = вибрация)
        val sign = if (vert >= 0) 1 else -1
        if (sign != lastSign) { crossedZero = true; lastSign = sign }

        val intervalOk = timeMs - lastStepMs >= 250
        if (vert > threshold && intervalOk && crossedZero && wFilled >= 50) {
            lastStepMs = timeMs
            crossedZero = false
            stepCount++
            return true
        }
        return false
    }
}
