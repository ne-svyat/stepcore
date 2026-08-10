package com.vasil.stepcore

import kotlin.math.sqrt

/**
 * L3.3 — ИЗМЕРИТЕЛЬ ПРИЗНАКОВ ПОХОДКИ. Только меряет, ничего не решает.
 *
 * ЗАЧЕМ. Разбор трёх вечеров бега (07-09.08) показал, что оба признака,
 * на которых стоит различение бега и ходьбы, пробиваются:
 *
 *   - ЧАСТОТА. Ходьба под горку с телефоном в кармане дала 3.0-3.2 Гц
 *     пиков (журнал 07.08, 21:05-21:12) — ровно беговой диапазон.
 *   - АМПЛИТУДА. Удар пятки на спуске жёстче, чем по ровному, и лезет
 *     выше AMP_RUN_FLOOR = 8. Порог разделял бег и ходьбу ПО РОВНОМУ;
 *     на уклоне разделения нет.
 *
 * Признак, которого у нас нет и который не зависит ни от того, ни от
 * другого: ФАЗА ПОЛЁТА. При беге есть момент, когда обе ноги в воздухе
 * и тело падает свободно; при ходьбе хотя бы одна нога всегда на земле.
 * В свободном падении модуль полного ускорения проваливается к нулю.
 * Уклон на это не влияет: под горку идут, а не летят.
 *
 * ЧТО СЧИТАЕТСЯ (всё — гипотезы, ни на что не влияющие):
 *
 *  1. dipG        минимум модуля |a| между пиками, в долях g.
 *                 Бег ожидается < 0.5g, ходьба — никогда.
 *  2. periodMs    период движения по автокорреляции.
 *  3. strength    сила отклика автокорреляции, 0..1. Это и есть честная
 *                 РИТМИЧНОСТЬ: ей не нужны пики и потому она не зависит
 *                 от порога их поиска.
 *  4. contactMs   длительность опоры (|a| выше веса тела).
 *  5. dutyFactor  contactMs / periodMs. Ниже 0.5 — есть полёт.
 *  6. accelRms / gyroRms  энергия; задел под опознание места телефона.
 *  7. hz          фактическая частота отсчётов датчика.
 *
 * ПОЧЕМУ АВТОКОРРЕЛЯЦИЯ, А НЕ ПИКИ. Пики ищутся порогом, а порог привязан
 * к месту телефона и к человеку. Автокорреляция ищет повторяемость самой
 * формы сигнала: лишний пик от размаха руки периода не образует и силу
 * отклика не поднимает. На калибровке 07.08 это видно прямо: по пикам
 * медиана 319 мс и разброс 30%, а без лишних пиков — 329 мс и 24%.
 *
 * ГРАНИЦЫ ЧЕСТНОСТИ:
 *  - При 50 Гц разрешение по времени 20 мс. Время контакта бега
 *    150-250 мс, значит погрешность до 13%. Класс отдаёт hz, и любой
 *    вывод по contactMs обязан её учитывать.
 *  - dipG считается по МОДУЛЮ, а не по вертикали: в свободном падении к
 *    нулю идёт весь вектор, проекция для этого не нужна. Это же делает
 *    признак нечувствительным к ориентации телефона.
 *  - Есть «бег без фазы полёта» (grounded running) на малых скоростях.
 *    Признак его не увидит — известное ограничение, а не ошибка.
 *
 * ПРОВЕРЕНО (26 проверок на синтетике, kotlinc 1.9.24, до сборки):
 *   бег            провал 0.06g  доля 0.34  сила 0.97
 *   быстрая ходьба провал 0.64g  доля 0.70  сила 0.95   <- период и пик
 *                                                          как у бега
 *   ходьба         провал 0.84g  доля 0.69  сила 0.94
 *   тряска         провал 0.40g  доля 0.05  сила 0.08
 *
 * Чистый Kotlin, ноль android.* — проверяется тестами до сборки.
 */
class GaitFeatures {

    data class Snapshot(
        val samples: Int,
        val hz: Float,
        val dipG: Float,
        val peakG: Float,
        val periodMs: Int,
        val strength: Float,
        val contactMs: Int,
        val dutyFactor: Float,
        val accelRms: Float,
        val gyroRms: Float,
    ) {
        /** Строка для журнала. Помечена как гипотеза: на счёт не влияет. */
        fun toLogLine(tag: String): String {
            val d = if (periodMs > 0) "%.2f".format(dutyFactor) else "—"
            val p = if (periodMs > 0) periodMs.toString() else "—"
            return "[гип] походка" + tag + ": провал " + "%.2f".format(dipG) +
                "g пик " + "%.2f".format(peakG) + "g · период " + p +
                "мс сила " + "%.2f".format(strength) +
                " · опора " + contactMs + "мс доля " + d +
                " · акс " + "%.1f".format(accelRms) +
                " гиро " + "%.2f".format(gyroRms) +
                " · " + samples + " отсч " + "%.0f".format(hz) + "Гц"
        }
    }

    private val mag = FloatArray(CAP)
    private val tMs = LongArray(CAP)
    private var n = 0
    private var gyroSq = 0.0
    private var gyroN = 0

    /** Модуль ускорения. Гравитацию НЕ вычитаем: провал к нулю есть
     *  свойство полного вектора, и вычитание его бы уничтожило. */
    fun onAccel(x: Float, y: Float, z: Float, timeMs: Long) {
        if (n >= CAP) return
        mag[n] = sqrt(x * x + y * y + z * z)
        tMs[n] = timeMs
        n++
    }

    fun onGyro(x: Float, y: Float, z: Float) {
        gyroSq += (x * x + y * y + z * z).toDouble()
        gyroN++
    }

    fun reset() { n = 0; gyroSq = 0.0; gyroN = 0 }

    fun ready(): Boolean = n >= MIN_SAMPLES

    fun snapshot(): Snapshot? {
        if (n < MIN_SAMPLES) return null
        val spanMs = tMs[n - 1] - tMs[0]
        if (spanMs <= 0L) return null
        val hz = (n - 1) * 1000f / spanMs

        var mn = Float.MAX_VALUE
        var mx = 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = mag[i]
            if (v < mn) mn = v
            if (v > mx) mx = v
            sum += v.toDouble()
        }
        val mean = (sum / n).toFloat()

        var vs = 0.0
        for (i in 0 until n) { val d = mag[i] - mean; vs += (d * d).toDouble() }
        val rms = sqrt(vs / n).toFloat()

        val pr = autocorrelate(mean, hz)
        val periodMs = pr.first
        val strength = pr.second
        val contactMs = contactTime(hz)
        val duty = if (periodMs > 0) contactMs.toFloat() / periodMs else 0f
        val gRms = if (gyroN > 0) sqrt(gyroSq / gyroN).toFloat() else 0f

        return Snapshot(n, hz, mn / G, mx / G, periodMs, strength,
            contactMs, duty, rms, gRms)
    }

    /**
     * Автокорреляция по сдвигам, отвечающим периодам 200..1500 мс.
     * Нормировка на нулевой сдвиг: сила отклика лежит в 0..1 и сравнима
     * между окнами и между людьми. Берём ПЕРВЫЙ выраженный максимум, а не
     * глобальный: глобальный часто приходится на удвоенный период (шаг
     * против цикла из двух шагов).
     */
    private fun autocorrelate(mean: Float, hz: Float): Pair<Int, Float> {
        if (hz <= 0f) return Pair(0, 0f)
        var minLag = (MIN_PERIOD_MS * hz / 1000f).toInt()
        if (minLag < 2) minLag = 2
        var maxLag = (MAX_PERIOD_MS * hz / 1000f).toInt()
        if (maxLag > n / 2) maxLag = n / 2
        if (maxLag <= minLag) return Pair(0, 0f)

        var r0 = 0.0
        for (i in 0 until n) { val d = mag[i] - mean; r0 += (d * d).toDouble() }
        if (r0 <= 0.0) return Pair(0, 0f)

        var bestLag = 0
        var bestVal = 0.0
        var prev = 0.0
        var rising = false
        for (lag in minLag..maxLag) {
            var r = 0.0
            for (i in 0 until n - lag) {
                r += (mag[i] - mean).toDouble() * (mag[i + lag] - mean).toDouble()
            }
            val norm = r / r0
            if (norm > prev) rising = true
            else if (rising && prev > MIN_STRENGTH) { bestLag = lag - 1; bestVal = prev; break }
            else rising = false
            if (norm > bestVal) { bestVal = norm; bestLag = lag }
            prev = norm
        }
        if (bestLag <= 0) return Pair(0, 0f)
        val periodMs = (bestLag * 1000f / hz).toInt()
        var s = bestVal.toFloat()
        if (s < 0f) s = 0f
        if (s > 1f) s = 1f
        return Pair(periodMs, s)
    }

    /**
     * Время опоры: доля окна, где модуль выше веса тела (1 g), делённая
     * на число циклов. Грубо, но независимо от подобранных порогов:
     * критерий — сам вес, величина физическая.
     */
    private fun contactTime(hz: Float): Int {
        if (hz <= 0f || n <= 0) return 0
        var above = 0
        var crossings = 0
        var wasAbove = mag[0] > G
        for (i in 0 until n) {
            val a = mag[i] > G
            if (a) above++
            if (a && !wasAbove) crossings++
            wasAbove = a
        }
        if (crossings == 0) return 0
        val totalMs = above * 1000f / hz
        return (totalMs / crossings).toInt()
    }

    companion object {
        const val G = 9.81f
        /** Ёмкость окна: 15 с при 50 Гц с запасом на всплески частоты. */
        const val CAP = 1200
        /** Меньше двух секунд материала — судить не о чем. */
        const val MIN_SAMPLES = 100
        const val MIN_PERIOD_MS = 200f
        const val MAX_PERIOD_MS = 1500f
        /** Ниже этого отклик считаем шумом, а не периодом. */
        const val MIN_STRENGTH = 0.30
    }
}
