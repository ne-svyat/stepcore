package com.vasil.stepcore

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v295. НЕЗАВИСИМЫЙ измеритель темпа бега.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ КЛАСС, А НЕ ПРАВКА ДЕТЕКТОРА
 * -------------------------------------------
 * Измерено 01.08 на реальной пробежке (журнал `[диаг] кал.run`):
 * из 13 собранных шагов беговую амплитуду (>=8 м/с2) имели ТРИ - 20.1,
 * 12.8, 12.9. Остальные десять - 3.0..6.8, то есть ходьба. Медиана
 * интервала вышла 601 мс = 1.66 Гц, хотя бег у этого человека 2.4-3 Гц.
 *
 * Причина - замкнутый круг в детекторе: он остаётся в режиме WALK, его
 * интервальные ворота ходьбы не пропускают беговой шаг (~300 мс),
 * поэтому подтверждается каждый второй, и интервал выходит ~600 мс.
 * Половинки этих интервалов дают 2.9-4.3 Гц - настоящий бег. А чтобы
 * перейти в RUN, детектору нужна серия беговых интервалов, которую он
 * не видит, потому что он в WALK.
 *
 * Чинить это внутри детектора значит трогать то, от чего зависят
 * классификация, корпус признаков и метки уклона. Поэтому здесь -
 * отдельный измеритель по образцу независимого канала корпуса (v185):
 *
 *   - счёт шагов не задействован вообще (считает чип);
 *   - StepDetector не читается и не меняется;
 *   - живёт только во время калибровки бега и умирает вместе с ней;
 *   - влияет ровно на два числа в профиле бега, и только если пройдены
 *     ворота качества.
 *
 * ПОЧЕМУ ЗДЕСЬ ПРОСТО
 * -------------------
 * У бега сигнал крупный и чистый: 9.2-21.1 м/с2 против 0.8-7.8 у ходьбы
 * (измерено ранее, см. AMP_RUN_FLOOR). Карантин и режимная логика нужны,
 * чтобы не считать тряску за ходьбу; здесь задача уже, а порог высокий,
 * поэтому хватает поиска пиков с рефрактерным окном.
 *
 * Класс - чистый Kotlin без Android: его можно прогонять тестами.
 */
class RunTempoMeter {

    /** Один принятый шаг: когда и с какой амплитудой. */
    data class Peak(val timeMs: Long, val amp: Float)

    private var gx = 0f
    private var gy = 0f
    private var gz = 9.81f
    private var gravityReady = false

    private var lastPeakMs = 0L
    private var rising = false
    private var peakAmp = 0f
    private var peakMs = 0L

    private val peaks = ArrayList<Peak>()
    private val intervals = ArrayList<Long>()

    /** Отброшено пиков: слишком часто (дребезг) и слишком редко (не бег). */
    var rejectedFast = 0
        private set
    var rejectedSlow = 0
        private set

    fun reset() {
        gravityReady = false
        gx = 0f; gy = 0f; gz = 9.81f
        lastPeakMs = 0L; rising = false; peakAmp = 0f; peakMs = 0L
        peaks.clear(); intervals.clear()
        rejectedFast = 0; rejectedSlow = 0
    }

    fun stepCount(): Int = peaks.size
    fun intervalsSnapshot(): List<Long> = ArrayList(intervals)
    fun ampsSnapshot(): List<Float> = peaks.map { it.amp }

    /**
     * Один отсчёт акселерометра. Возвращает true, если только что принят шаг.
     *
     * Гравитация выделяется тем же фильтром, что в детекторе (те же 0.02),
     * чтобы вертикаль считалась одинаково и числа были сравнимы с корпусом.
     */
    fun onAccel(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        if (!gravityReady) {
            gx = x; gy = y; gz = z
            gravityReady = true
            return false
        }
        gx += GRAVITY_ALPHA * (x - gx)
        gy += GRAVITY_ALPHA * (y - gy)
        gz += GRAVITY_ALPHA * (z - gz)
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        if (gn < 1f) return false
        val vert = ((x - gx) * gx + (y - gy) * gy + (z - gz) * gz) / gn
        val a = abs(vert)

        // Поиск вершины: пока сигнал растёт - запоминаем максимум, на спаде
        // принимаем решение. Так берётся именно вершина, а не первый отсчёт
        // выше порога, и амплитуда получается сравнимой с корпусной.
        if (a >= AMP_RUN_FLOOR) {
            if (a > peakAmp) { peakAmp = a; peakMs = timeMs }
            rising = true
            return false
        }
        if (!rising) return false

        // Спад ниже порога: вершина закрыта.
        rising = false
        val amp = peakAmp
        val t = peakMs
        peakAmp = 0f

        if (amp > AMP_SANE_MAX) return false          // удар, не шаг

        if (lastPeakMs > 0L) {
            val iv = t - lastPeakMs
            if (iv < MIN_INTERVAL_MS) { rejectedFast++; return false }
            if (iv > MAX_INTERVAL_MS) {
                // Пауза: серия прервалась. Опору сдвигаем, интервал не берём -
                // иначе пауза между отрезками попала бы в выборку как шаг.
                rejectedSlow++
                lastPeakMs = t
                peaks.add(Peak(t, amp))
                return true
            }
            intervals.add(iv)
        }
        lastPeakMs = t
        peaks.add(Peak(t, amp))
        return true
    }

    /** Честная медиана: при чётном n - среднее двух средних. */
    fun medianIntervalMs(): Long {
        if (intervals.isEmpty()) return 0L
        val s = intervals.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Разброс в процентах от медианы (межквартильный размах). */
    fun spreadPct(): Int {
        if (intervals.size < 4) return 100
        val s = intervals.sorted()
        val med = medianIntervalMs()
        if (med <= 0L) return 100
        return (100L * (s[s.size * 3 / 4] - s[s.size / 4]) / med).toInt()
    }

    fun medianAmp(): Float {
        if (peaks.isEmpty()) return 0f
        val s = peaks.map { it.amp }.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    companion object {
        /** Пол амплитуды бега. Измерен ранее: ходьба 0.8-7.8, бег 9.2-21.1. */
        const val AMP_RUN_FLOOR = 8f
        /** Выше этого - удар или падение телефона, а не шаг. Верх измеренного
         *  бега 21.1, запас взят вдвое. */
        const val AMP_SANE_MAX = 45f
        /** 180 мс = 5.5 шага/с. Быстрее человек не бежит - это дребезг. */
        const val MIN_INTERVAL_MS = 180L
        /** 600 мс = 1.67 Гц. Медленнее - уже не бег; такой интервал и был
         *  причиной ложной медианы 601 мс у детектора. */
        const val MAX_INTERVAL_MS = 600L
        /** Тот же фильтр гравитации, что в детекторе. */
        const val GRAVITY_ALPHA = 0.02f
    }
}
