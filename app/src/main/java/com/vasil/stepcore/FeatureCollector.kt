package com.vasil.stepcore

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * L1: сбор расширенных признаков походки для будущего обучения.
 *
 * ЧИСТЫЙ KOTLIN. Ни одного импорта android.* - это гейт на будущее:
 * класс переезжает в модуль :motionlab (L2) без единой правки и уже
 * сейчас гоняется юнит-тестами в песочнице до установки на телефон.
 *
 * ГРАНИЦА ОТВЕТСТВЕННОСТИ: коллектор ничего не решает и ни на что не
 * влияет. Он только копит буферы и по запросу отдаёт снимок. Его отказ
 * не может изменить счёт шагов, режим детектора или калории.
 *
 * ПРИНЦИП "ПРИМИТИВЫ, А НЕ ВЕРДИКТЫ": наружу отдаются медианы, разбросы
 * и сырые оси - но не индексы, не оценки и не классы. Производные
 * (индекс асимметрии, регулярность, ось наибольшей вариации) считаются
 * при обучении и потому пересчитываются по всему старому корпусу без
 * нового featureVersion.
 *
 * ПРИНЦИП "ОТСУТСТВИЕ != НОЛЬ": пока данных мало, поля равны null.
 * Ноль означал бы измеренный ноль и отравил бы обучение.
 *
 * НОЛЬ НОВЫХ ПОРОГОВ: границу серии задаёт детектор своим переходом в
 * IDLE (у него для этого уже есть выверенный таймаут). Коллектор не
 * заводит собственного порога тишины.
 */
class FeatureCollector {

    /** Снимок признаков на момент записи образца. Все поля - примитивы. */
    data class Snapshot(
        val pitchDeg: Float?,
        val rollDeg: Float?,
        val gyroX: Float?,
        val gyroY: Float?,
        val gyroZ: Float?,
        val ampEvenMed: Float?,
        val ampOddMed: Float?,
        val intervalEvenMed: Float?,
        val intervalOddMed: Float?,
        val ampMed: Float?,
        val ampIqr: Float?,
        val intervalMed: Float?,
        val intervalIqr: Float?,
        val windowN: Int?,
        val seriesSteps: Int?,
        val seriesMs: Long?,
        // Независимый канал (v185): считается из сырого акселерометра,
        // БЕЗ участия детектора и его вето по гироскопу. В кармане
        // детектор постоянно в IDLE и не даёт ни амплитуды, ни каденса -
        // а это главные признаки уклона. Эти поля их дают.
        val accRms: Float?,
        val accP90: Float?,
        val accMax: Float?,
        val zcrCadence: Float?,   // каденс по пересечениям нуля, шаг/с
        val sampleHz: Float?,     // фактическая частота сенсора
    )

    // --- гироскоп по осям ---
    // EMA квадратов угловой скорости по каждой оси. Альфа 0.05f взята
    // той же, что у скалярного gyroRmsSq в StepDetector: числа обязаны
    // быть сопоставимы со старым полем gyro, иначе корпус не сшивается.
    private var gxSq = 0f
    private var gySq = 0f
    private var gzSq = 0f
    private var gyroSeen = false

    // --- окно регулярности (32 шага) ---
    // 32 шага при измеренном у пользователя интервале ~540 мс это ~17 с
    // движения. Меньше - медиана шумит на единичном спотыкании, больше -
    // окно перетекает через границы серии и смазывает переходы.
    private val ampWin = FloatArray(REG_WINDOW)
    private val intWin = FloatArray(REG_WINDOW)
    private var winIdx = 0
    private var winFilled = 0

    // --- окно асимметрии (8 шагов) ---
    // Чётность считается ВНУТРИ серии: первый шаг серии = 0. Так левая
    // и правая нога стабильно попадают в свою корзину, пока серия жива.
    private val asymAmp = FloatArray(ASYM_WINDOW)
    private val asymInt = FloatArray(ASYM_WINDOW)
    private val asymEven = BooleanArray(ASYM_WINDOW)
    private var asymIdx = 0
    private var asymFilled = 0

    // --- независимый канал вертикального ускорения (v185) ---
    // Кольцо на ACC_WINDOW отсчётов. При SENSOR_DELAY_GAME (~50 Гц)
    // это около 10 с - столько же, сколько окно правила ShakeHold.
    private val accWin = FloatArray(ACC_WINDOW)
    private val accT = LongArray(ACC_WINDOW)
    private var accIdx = 0
    private var accFilled = 0

    // --- серия ---
    private var seriesSteps = 0
    private var seriesStartMs = 0L
    private var seriesLastMs = 0L
    private var seriesLive = false

    /**
     * Сырой отсчёт акселерометра. Вызывается на КАЖДОМ событии, до и
     * независимо от любых решений детектора: вето по тряске, режим,
     * карантин - ничто из этого сюда не доходит. Гравитация приходит
     * снаружи от детектора, чтобы не заводить второй источник истины.
     */
    fun onAccel(x: Float, y: Float, z: Float,
                gravX: Float, gravY: Float, gravZ: Float, timeMs: Long) {
        val gn = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        if (gn < GRAV_MIN) return
        val lx = x - gravX; val ly = y - gravY; val lz = z - gravZ
        val vert = (lx * gravX + ly * gravY + lz * gravZ) / gn
        accWin[accIdx] = vert
        accT[accIdx] = timeMs
        accIdx = (accIdx + 1) % ACC_WINDOW
        if (accFilled < ACC_WINDOW) accFilled++
    }

    /** Гироскоп: сырые оси. Вызывается на каждое событие сенсора. */
    fun onGyro(x: Float, y: Float, z: Float) {
        gxSq += GYRO_ALPHA * (x * x - gxSq)
        gySq += GYRO_ALPHA * (y * y - gySq)
        gzSq += GYRO_ALPHA * (z * z - gzSq)
        gyroSeen = true
    }

    /**
     * Подтверждённый шаг. amp и intervalMs берутся сглаженными у
     * детектора - те же числа, что уже пишутся в корпус, чтобы старые
     * и новые поля описывали одну и ту же величину.
     */
    fun onStep(amp: Float, intervalMs: Float, timeMs: Long) {
        if (!seriesLive) {
            seriesLive = true
            seriesSteps = 0
            seriesStartMs = timeMs
            asymIdx = 0; asymFilled = 0
        }
        val even = seriesSteps % 2 == 0
        seriesSteps++
        seriesLastMs = timeMs

        ampWin[winIdx] = amp
        intWin[winIdx] = intervalMs
        winIdx = (winIdx + 1) % REG_WINDOW
        if (winFilled < REG_WINDOW) winFilled++

        asymAmp[asymIdx] = amp
        asymInt[asymIdx] = intervalMs
        asymEven[asymIdx] = even
        asymIdx = (asymIdx + 1) % ASYM_WINDOW
        if (asymFilled < ASYM_WINDOW) asymFilled++
    }

    /**
     * Детектор ушёл в IDLE - серия закончилась. Идемпотентно: сервис
     * зовёт это часто, повторный вызов при мёртвой серии бесплатен.
     * Окно регулярности НЕ чистится: оно описывает последние 32 шага
     * движения, а не текущую серию.
     */
    fun breakSeries() {
        if (!seriesLive) return
        seriesLive = false
    }

    /** Полный сброс. Зовётся вместе с resetTransient детектора. */
    fun reset() {
        gxSq = 0f; gySq = 0f; gzSq = 0f; gyroSeen = false
        winIdx = 0; winFilled = 0
        asymIdx = 0; asymFilled = 0
        accIdx = 0; accFilled = 0
        seriesLive = false; seriesSteps = 0
        seriesStartMs = 0L; seriesLastMs = 0L
    }

    /**
     * Снимок. Считается ТОЛЬКО в момент записи образца (1 раз на 20
     * шагов), поэтому две сортировки по 32 элемента здесь дешевле, чем
     * поддержание отсортированных структур на каждом шаге.
     *
     * gravX/gravY/gravZ - сглаженный вектор гравитации детектора.
     * Мгновенный не годится: он дёргается на каждом шаге и ориентация
     * телефона по нему не читается.
     */
    fun snapshot(gravX: Float, gravY: Float, gravZ: Float): Snapshot {
        val gn = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        val hasGrav = gn > GRAV_MIN
        val pitch = if (hasGrav)
            Math.toDegrees(
                atan2(-gravY.toDouble(), sqrt((gravX * gravX + gravZ * gravZ).toDouble()))
            ).toFloat() else null
        val roll = if (hasGrav)
            Math.toDegrees(atan2(gravX.toDouble(), gravZ.toDouble())).toFloat() else null

        val ampSorted = sortedCopy(ampWin, winFilled)
        val intSorted = sortedCopy(intWin, winFilled)
        val regOk = winFilled >= REG_MIN

        return Snapshot(
            pitchDeg = pitch,
            rollDeg = roll,
            gyroX = if (gyroSeen) sqrt(gxSq) else null,
            gyroY = if (gyroSeen) sqrt(gySq) else null,
            gyroZ = if (gyroSeen) sqrt(gzSq) else null,
            ampEvenMed = parityMedian(asymAmp, wantEven = true),
            ampOddMed = parityMedian(asymAmp, wantEven = false),
            intervalEvenMed = parityMedian(asymInt, wantEven = true),
            intervalOddMed = parityMedian(asymInt, wantEven = false),
            ampMed = if (regOk) median(ampSorted) else null,
            ampIqr = if (regOk) iqr(ampSorted) else null,
            intervalMed = if (regOk) median(intSorted) else null,
            intervalIqr = if (regOk) iqr(intSorted) else null,
            windowN = if (regOk) winFilled else null,
            seriesSteps = if (seriesLive) seriesSteps else null,
            seriesMs = if (seriesLive) seriesLastMs - seriesStartMs else null,
            accRms = accStat(0),
            accP90 = accStat(1),
            accMax = accStat(2),
            zcrCadence = accCadence(),
            sampleHz = accHz(),
        )
    }

    /**
     * 0 = RMS, 1 = 90-й процентиль |vert|, 2 = максимум |vert|.
     * RMS даёт среднюю энергию шага, p90 - типичный пик без выбросов,
     * max - самый сильный удар (у спуска он выше, чем у подъёма).
     */
    private fun accStat(kind: Int): Float? {
        if (accFilled < ACC_MIN) return null
        if (kind == 0) {
            var s = 0.0
            for (i in 0 until accFilled) s += accWin[i].toDouble() * accWin[i]
            return sqrt(s / accFilled).toFloat()
        }
        val a = FloatArray(accFilled)
        for (i in 0 until accFilled) a[i] = kotlin.math.abs(accWin[i])
        a.sort()
        return if (kind == 2) a[accFilled - 1] else a[(accFilled * 9) / 10]
    }

    /**
     * Каденс по пересечениям нуля. Два пересечения = один период
     * колебания = один шаг. Метод грубый, но работает там, где детектор
     * молчит, и не требует ни порогов, ни подтверждений.
     */
    private fun accCadence(): Float? {
        if (accFilled < ACC_MIN) return null
        val span = accSpanMs() ?: return null
        if (span <= 0L) return null
        var mean = 0.0
        for (i in 0 until accFilled) mean += accWin[i]
        mean /= accFilled
        var crossings = 0
        var prev = accWin[0] - mean
        for (i in 1 until accFilled) {
            val cur = accWin[i] - mean
            if ((prev < 0 && cur >= 0) || (prev >= 0 && cur < 0)) crossings++
            prev = cur
        }
        return (crossings / 2f) / (span / 1000f)
    }

    /** Фактическая частота сенсора: MIUI её режет, и это надо видеть. */
    private fun accHz(): Float? {
        if (accFilled < ACC_MIN) return null
        val span = accSpanMs() ?: return null
        if (span <= 0L) return null
        return (accFilled - 1) * 1000f / span
    }

    private fun accSpanMs(): Long? {
        if (accFilled < 2) return null
        var lo = Long.MAX_VALUE; var hi = Long.MIN_VALUE
        for (i in 0 until accFilled) {
            val t = accT[i]
            if (t < lo) lo = t
            if (t > hi) hi = t
        }
        return hi - lo
    }

    private fun sortedCopy(src: FloatArray, n: Int): FloatArray {
        if (n <= 0) return FloatArray(0)
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = src[i]
        out.sort()
        return out
    }

    /** Медиана уже отсортированного массива. */
    private fun median(sorted: FloatArray): Float {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    /** Межквартильный размах: мера разброса, устойчивая к выбросам. */
    private fun iqr(sorted: FloatArray): Float {
        val n = sorted.size
        val q1 = sorted[n / 4]
        val q3 = sorted[(3 * n) / 4 - if (n % 4 == 0) 1 else 0]
        return q3 - q1
    }

    /**
     * Медиана одной чётности. null, пока в корзине меньше ASYM_MIN
     * значений: на двух шагах "асимметрия" это шум, а не походка.
     */
    private fun parityMedian(src: FloatArray, wantEven: Boolean): Float? {
        var cnt = 0
        val buf = FloatArray(ASYM_WINDOW)
        for (i in 0 until asymFilled) {
            if (asymEven[i] == wantEven) { buf[cnt] = src[i]; cnt++ }
        }
        if (cnt < ASYM_MIN) return null
        val out = FloatArray(cnt)
        for (i in 0 until cnt) out[i] = buf[i]
        out.sort()
        return median(out)
    }

    companion object {
        const val FEATURE_VERSION = 2
        private const val REG_WINDOW = 32
        private const val REG_MIN = 6
        private const val ASYM_WINDOW = 8
        private const val ASYM_MIN = 3
        private const val GYRO_ALPHA = 0.05f
        // Ниже этой нормы вектор гравитации не сформирован (старт сервиса,
        // свободное падение) и угол из него бессмыслен. 1 м/с2 при норме 9.81.
        private const val GRAV_MIN = 1f
        /** ~10 с при SENSOR_DELAY_GAME (~50 Гц) - столько же, сколько
         *  окно правила ShakeHold, чтобы признаки были сопоставимы. */
        private const val ACC_WINDOW = 512
        /** Ниже двух секунд материала статистика бессмысленна. */
        private const val ACC_MIN = 100
    }
}

