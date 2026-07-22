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
    private var lastGyroMs = 0L

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
    private var lastAccMs = 0L

    // --- собственная оценка гравитации (v189) ---
    // Раньше вектор приходил снаружи, от детектора. При выключенном
    // экране детектор не работает вовсе (и работать не должен - он
    // заморожен), а коллектор работает окнами. Значит источник
    // гравитации обязан быть свой, иначе в фоне он был бы протухшим.
    // Фильтр тот же, что у детектора, на том же входе - оценки сходятся.
    private var grX = 0f
    private var grY = 0f
    private var grZ = 9.81f
    private var gravSeeded = false

    // --- серия ---
    private var seriesSteps = 0
    private var seriesStartMs = 0L
    private var seriesLastMs = 0L
    private var seriesLive = false
    private var lastStepMs = 0L

    /**
     * Сырой отсчёт акселерометра. Вызывается на КАЖДОМ событии, до и
     * независимо от любых решений детектора: вето по тряске, режим,
     * карантин - ничто из этого сюда не доходит. Гравитация приходит
     * снаружи от детектора, чтобы не заводить второй источник истины.
     */
    fun onAccel(x: Float, y: Float, z: Float, timeMs: Long) {
        // Разрыв потока: при выключенном экране обработка идёт окнами, и
        // между ними проходят десятки секунд. Старое содержимое буфера
        // растянуло бы окно измерения на весь перерыв - частота и каденс
        // вышли бы заниженными в разы. Поэтому разрыв обнуляет окно, а
        // гравитация пересевается по первому отсчёту: за перерыв телефон
        // мог сменить положение, и доводить оценку фильтром уже поздно.
        if (!gravSeeded || (lastAccMs > 0L && timeMs - lastAccMs > ACC_GAP_MS)) {
            accIdx = 0; accFilled = 0
            grX = x; grY = y; grZ = z
            gravSeeded = true
        } else {
            grX += GRAV_ALPHA * (x - grX)
            grY += GRAV_ALPHA * (y - grY)
            grZ += GRAV_ALPHA * (z - grZ)
        }
        val gn = sqrt(grX * grX + grY * grY + grZ * grZ)
        if (gn < GRAV_MIN) return
        val lx = x - grX; val ly = y - grY; val lz = z - grZ
        val vert = (lx * grX + ly * grY + lz * grZ) / gn
        lastAccMs = timeMs
        accWin[accIdx] = vert
        accT[accIdx] = timeMs
        accIdx = (accIdx + 1) % ACC_WINDOW
        if (accFilled < ACC_WINDOW) accFilled++
    }

    /** Гироскоп: сырые оси. Вызывается на каждое событие сенсора. */
    fun onGyro(x: Float, y: Float, z: Float, timeMs: Long) {
        lastGyroMs = timeMs
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
        lastStepMs = timeMs
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
        grX = 0f; grY = 0f; grZ = 9.81f; gravSeeded = false
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
    fun snapshot(nowMs: Long): Snapshot {
        // v186: ПРИЗНАКИ ПРОТУХАЮТ ЯВНО.
        //
        // При выключенном экране обработчик акселерометра и гироскопа в
        // StepService выходит сразу, и коллектор перестаёт получать данные.
        // Ветка записи корпуса по дельтам чипа при этом продолжает
        // работать - чип считает всегда. Без этой проверки в корпус
        // уходила бы амплитуда десятисекундной давности с сегодняшней
        // меткой уклона: не пропуск, а тихая ложь, которая хуже пропуска.
        //
        // Два срока, потому что источники живут в разном темпе:
        //  - сенсорные признаки (наклон, оси гироскопа, амплитуда, каденс)
        //    обновляются десятки раз в секунду, 15 с - уже вечность;
        //  - шаговые (асимметрия, регулярность) обновляются раз в шаг и
        //    обязаны переживать светофор, поэтому им дано 60 с.
        val sensorFresh = lastAccMs > 0L && nowMs - lastAccMs <= SENSOR_STALE_MS
        val gyroFresh = gyroSeen && lastGyroMs > 0L && nowMs - lastGyroMs <= SENSOR_STALE_MS
        val stepFresh = lastStepMs > 0L && nowMs - lastStepMs <= STEP_STALE_MS
        val gn = sqrt(grX * grX + grY * grY + grZ * grZ)
        // Гравитация теперь своя и живёт тем же потоком, что амплитуда:
        // свежа ровно тогда, когда свежи отсчёты.
        val hasGrav = gn > GRAV_MIN && sensorFresh && gravSeeded
        val pitch = if (hasGrav)
            Math.toDegrees(
                atan2(-grY.toDouble(), sqrt((grX * grX + grZ * grZ).toDouble()))
            ).toFloat() else null
        val roll = if (hasGrav)
            Math.toDegrees(atan2(grX.toDouble(), grZ.toDouble())).toFloat() else null

        val ampSorted = sortedCopy(ampWin, winFilled)
        val intSorted = sortedCopy(intWin, winFilled)
        val regOk = winFilled >= REG_MIN

        return Snapshot(
            pitchDeg = pitch,
            rollDeg = roll,
            gyroX = if (gyroFresh) sqrt(gxSq) else null,
            gyroY = if (gyroFresh) sqrt(gySq) else null,
            gyroZ = if (gyroFresh) sqrt(gzSq) else null,
            ampEvenMed = if (stepFresh) parityMedian(asymAmp, true) else null,
            ampOddMed = if (stepFresh) parityMedian(asymAmp, false) else null,
            intervalEvenMed = if (stepFresh) parityMedian(asymInt, true) else null,
            intervalOddMed = if (stepFresh) parityMedian(asymInt, false) else null,
            ampMed = if (regOk && stepFresh) median(ampSorted) else null,
            ampIqr = if (regOk && stepFresh) iqr(ampSorted) else null,
            intervalMed = if (regOk && stepFresh) median(intSorted) else null,
            intervalIqr = if (regOk && stepFresh) iqr(intSorted) else null,
            windowN = if (regOk && stepFresh) winFilled else null,
            seriesSteps = if (seriesLive) seriesSteps else null,
            seriesMs = if (seriesLive) seriesLastMs - seriesStartMs else null,
            accRms = if (sensorFresh) accStat(0) else null,
            accP90 = if (sensorFresh) accStat(1) else null,
            accMax = if (sensorFresh) accStat(2) else null,
            zcrCadence = if (sensorFresh) accCadence() else null,
            sampleHz = if (sensorFresh) accHz() else null,
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
     * Каденс. Триггер Шмитта с гистерезисом от RMS.
     *
     * ПОЧЕМУ НЕ ПРОСТОЕ ПЕРЕСЕЧЕНИЕ СРЕДНЕГО (метод v185, ошибочный).
     * На синтетической синусоиде оно давало верный ответ, и я на этом
     * успокоился. Реальный вертикальный сигнал ходьбы синусоидой не
     * является: в одном цикле шага есть удар пятки и толчок, то есть
     * сильная вторая гармоника, и она добавляет лишние пересечения.
     * Замер на устройстве 19.07: записано 3.74 ш/с при истинных 1.85 -
     * ровно вдвое.
     *
     * Гистерезис требует уйти выше +thr, а потом ниже -thr: колебания
     * около среднего перестают считаться. Проверено на сигналах с долей
     * второй гармоники 0 / 0.5 / 1.0 и шумом до 0.4 - ответ 1.80 везде,
     * бег 2.85 при истинных 2.90.
     *
     * Пол по RMS обязателен: на чистом шуме гистерезис не спасает, потому
     * что порог масштабируется от самого шума (проверено: 17.25 ш/с).
     * Измеренная ходьба дала RMS 1.47, пол 0.2 даёт семикратный запас.
     */
    private fun accCadence(): Float? {
        if (accFilled < ACC_MIN) return null
        val span = accSpanMs() ?: return null
        if (span <= 0L) return null
        var mean = 0.0
        for (i in 0 until accFilled) mean += accWin[i]
        mean /= accFilled
        var sq = 0.0
        for (i in 0 until accFilled) {
            val d = accWin[i] - mean; sq += d * d
        }
        val rms = sqrt(sq / accFilled)
        if (rms < CAD_MIN_RMS) return null
        val thr = CAD_HYST_K * rms
        var full = 0
        var state = 0
        for (i in 0 until accFilled) {
            val d = accWin[i] - mean
            if (state <= 0 && d > thr) {
                if (state == -1) full++
                state = 1
            } else if (state >= 0 && d < -thr) {
                if (state == 1) full++
                state = -1
            }
        }
        return (full / 2f) / (span / 1000f)
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
        // 3: каденс считается с гистерезисом (в версии 2 он был завышен
        // вдвое) и все признаки помечаются null при протухании.
        // v204: 4 = метка FLAT стала осознанной (появился NONE).
        // Старые строки (<=3) хранят неоднозначный FLAT - при обучении
        // на третий класс брать только >= 4.
        // v213: 5 = в строке появился курс (headingDeg/headingAcc).
        // Строки <= 4 курса не содержат - это не пропуск, а эпоха.
        const val FEATURE_VERSION = 5
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
        /** Постоянная фильтра гравитации. Та же, что у детектора. */
        private const val GRAV_ALPHA = 0.02f
        /** Разрыв потока длиннее этого обнуляет окно измерения.
         *  Две секунды: заведомо больше любого пропуска внутри окна и
         *  заведомо меньше паузы duty-цикла (48 с). */
        private const val ACC_GAP_MS = 2_000L
        /** Доля RMS для гистерезиса. 0.5 проверено на сигналах с долей
         *  второй гармоники 0...1.0 и шумом до 0.4. */
        private const val CAD_HYST_K = 0.5f
        /** Ниже этого RMS движения нет и каденс не определён.
         *  Измеренная ходьба дала 1.47 - запас семикратный. */
        private const val CAD_MIN_RMS = 0.2f
        /** Сенсорные признаки живут десятки раз в секунду. */
        private const val SENSOR_STALE_MS = 15_000L
        /** Шаговые обновляются раз в шаг и обязаны пережить светофор. */
        private const val STEP_STALE_MS = 60_000L
    }
}

