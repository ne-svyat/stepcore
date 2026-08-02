package com.vasil.stepcore

/**
 * v311. ТЕНЕВОЙ НАБЛЮДАТЕЛЬ БЕГА.
 *
 * Зачем: метка «бег» сейчас ставится только когда экран включён И детектор
 * находится в режиме RUN. В кармане детектор молчит из-за вето по тряске,
 * с выключенным экраном не работает вовсе - поэтому бег в кармане не
 * распознаётся никогда. Измерено 02.08: 10 шагов бега за 20 минут бега.
 *
 * Независимый измеритель пиков (RunTempoMeter) от этих вето не зависит и
 * уже сверен с реальностью: на контрольной пробежке 43 интервала при
 * 40-43 шагах, посчитанных вслух.
 *
 * НО включать его в метку сразу нельзя: ошибка увела бы часть ходьбы в
 * бег, а это портит статистику дня. Поэтому сначала он работает В ТЕНИ:
 * считает, что пометил бы, и пишет это в журнал рядом с тем, что решил
 * детектор. Человек ходит день, мы сверяем, и только потом включаем.
 *
 * Класс не знает ни про Android, ни про службу - его поведение проверено
 * тестами до сборки.
 */
class ShadowRunWatch(
    private val windowMs: Long = 60_000L,
    private val minPeaks: Int = 10
) {

    private var windowStart = 0L
    private var peaks = 0
    private val intervals = ArrayList<Long>()
    private var lastPeakMs = 0L
    private var chipSteps = 0
    private var chipRun = 0

    /** Пик беговой амплитуды, замеченный измерителем. */
    fun onPeak(timeMs: Long) {
        if (windowStart == 0L) windowStart = timeMs
        if (lastPeakMs > 0L) {
            val iv = timeMs - lastPeakMs
            // Пауза длиннее секунды - это не соседние шаги бега, а разрыв
            // серии: такой интервал в оценку темпа не идёт.
            if (iv in 150L..1000L) intervals.add(iv)
        }
        lastPeakMs = timeMs
        peaks++
    }

    /** Дельта чипа и то, как её пометил ДЕТЕКТОР - для сравнения. */
    fun onChipDelta(steps: Int, markedRunByDetector: Boolean) {
        chipSteps += steps
        if (markedRunByDetector) chipRun += steps
    }

    /**
     * Закрыть окно, если пришло время. Возвращает строку отчёта или null.
     *
     * Отчёт пишется, только если бег реально был: иначе журнал заполнится
     * пустыми строками про покой, и в нём станет невозможно искать.
     */
    fun poll(nowMs: Long): String? {
        if (windowStart == 0L) { windowStart = nowMs; return null }
        if (nowMs - windowStart < windowMs) return null
        val report = if (peaks >= minPeaks) buildReport() else null
        reset(nowMs)
        return report
    }

    private fun buildReport(): String {
        val med = medianInterval()
        val hz = if (med > 0L) 1000f / med else 0f
        return "[тень] бег: пиков " + peaks +
            (if (med > 0L) " · темп " + "%.2f".format(hz) + " Гц (" + med + " мс)" else "") +
            " · чип за окно " + chipSteps +
            " · детектор пометил бегом " + chipRun
    }

    private fun medianInterval(): Long {
        if (intervals.isEmpty()) return 0L
        val s = intervals.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun reset(nowMs: Long) {
        windowStart = nowMs
        peaks = 0
        intervals.clear()
        lastPeakMs = 0L
        chipSteps = 0
        chipRun = 0
    }

    /** Полный сброс - при старте калибровки, чтобы не мешались. */
    fun clear() = reset(0L)
}
