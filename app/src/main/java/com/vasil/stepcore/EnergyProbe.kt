package com.vasil.stepcore

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v433. Чистая математика короткого физического Energy Probe.
 *
 * Ничего не решает и не знает про WALK/SHAKE/TRANSPORT.
 * Получает сырые XYZ + GAME_ROTATION_VECTOR и возвращает примитивы,
 * которые потом можно сравнивать на размеченном корпусе.
 */
class EnergyProbe {
    data class Summary(
        val scene: Long,
        val trigger: String,
        val durationMs: Long,
        val accN: Int,
        val gyroN: Int,
        val rotN: Int,
        val accHz: Double,
        val gyroHz: Double,
        val accDynRms: Double,
        val accPeak: Double,
        val jerkRms: Double,
        val accAxisDom: Double,
        val accPeriodMs: Double,
        val accAuto: Double,
        val accPeriodFloor: Boolean,
        val gyroRms: Double,
        val gyroPeak: Double,
        val gyroAxisDom: Double,
        val gyroPeriodMs: Double,
        val gyroAuto: Double,
        val gyroPeriodFloor: Boolean,
        val periodGapMs: Double,
        val rotPathDeg: Double,
        val rotMaxDeg: Double,
        val wakeOffsetMs: Long?,
        val chipDeliveredOffsetMs: Long?,
        val chipDeliveredDelta: Int
    )

    private val cap = 256

    private val ax = DoubleArray(cap)
    private val ay = DoubleArray(cap)
    private val az = DoubleArray(cap)
    private val amag = DoubleArray(cap)
    private val at = LongArray(cap)
    private var an = 0

    private val gx = DoubleArray(cap)
    private val gy = DoubleArray(cap)
    private val gz = DoubleArray(cap)
    private val gmag = DoubleArray(cap)
    private val gt = LongArray(cap)
    private var gn = 0

    private var scene = 0L
    private var trigger = ""
    private var startRt = 0L
    private var wakeRt = -1L
    private var chipRt = -1L
    private var chipDeliveredDelta = 0

    private var rotN = 0
    private var firstQ: DoubleArray? = null
    private var prevQ: DoubleArray? = null
    private var rotPathRad = 0.0
    private var rotMaxRad = 0.0

    fun reset(sceneId: Long, triggerName: String, startArrivalMs: Long) {
        scene = sceneId
        trigger = triggerName
        startRt = startArrivalMs
        wakeRt = if (triggerName == "WAKE") startArrivalMs else -1L
        chipRt = -1L
        chipDeliveredDelta = 0
        an = 0
        gn = 0
        rotN = 0
        firstQ = null
        prevQ = null
        rotPathRad = 0.0
        rotMaxRad = 0.0
    }

    fun markWake(arrivalMs: Long) {
        if (wakeRt < 0L) wakeRt = arrivalMs
    }

    /**
     * delta здесь означает только STEP_COUNTER delta, ДОСТАВЛЕННУЮ callback'ом
     * во время probe. Она может содержать старый hardware batch и не означает,
     * что столько физических шагов произошло внутри трёхсекундного окна.
     */
    fun markChip(arrivalMs: Long, delta: Int) {
        if (chipRt < 0L) chipRt = arrivalMs
        chipDeliveredDelta += delta
    }

    fun onAccel(x: Float, y: Float, z: Float, timeMs: Long) {
        if (an >= cap) return
        val xd = x.toDouble()
        val yd = y.toDouble()
        val zd = z.toDouble()
        ax[an] = xd
        ay[an] = yd
        az[an] = zd
        amag[an] = sqrt(xd * xd + yd * yd + zd * zd)
        at[an] = timeMs
        an++
    }

    fun onGyro(x: Float, y: Float, z: Float, timeMs: Long) {
        if (gn >= cap) return
        val xd = x.toDouble()
        val yd = y.toDouble()
        val zd = z.toDouble()
        gx[gn] = xd
        gy[gn] = yd
        gz[gn] = zd
        gmag[gn] = sqrt(xd * xd + yd * yd + zd * zd)
        gt[gn] = timeMs
        gn++
    }

    /**
     * GAME_ROTATION_VECTOR: x,y,z — sin(theta/2)*axis, w — cos(theta/2).
     * q и -q означают одну ориентацию, поэтому угол берётся через abs(dot).
     */
    fun onGameRotation(values: FloatArray) {
        if (values.size < 3) return
        var x = values[0].toDouble()
        var y = values[1].toDouble()
        var z = values[2].toDouble()
        var w = if (values.size >= 4) {
            values[3].toDouble()
        } else {
            sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
        }

        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (norm <= 1e-12) return
        x /= norm; y /= norm; z /= norm; w /= norm
        val q = doubleArrayOf(x, y, z, w)

        val f = firstQ
        val p = prevQ
        if (f == null) {
            firstQ = q
        } else {
            rotMaxRad = max(rotMaxRad, quatAngle(f, q))
        }
        if (p != null) {
            rotPathRad += quatAngle(p, q)
        }
        prevQ = q
        rotN++
    }

    fun finish(endArrivalMs: Long): Summary {
        val accStats = seriesStats(amag, at, an)
        val gyroStats = seriesStats(gmag, gt, gn)

        val accRms = centeredRms(amag, an)
        val accPeak = centeredPeak(amag, an)
        val jerk = jerkRms(amag, at, an)

        val gyroRms = rawRms(gmag, gn)
        val gyroPeak = rawPeak(gmag, gn)

        val accAxis = axisDominance(ax, ay, az, an)
        val gyroAxis = axisDominance(gx, gy, gz, gn)

        val gap = if (accStats.periodMs > 0.0 && gyroStats.periodMs > 0.0) {
            abs(accStats.periodMs - gyroStats.periodMs)
        } else 0.0

        return Summary(
            scene = scene,
            trigger = trigger,
            durationMs = max(0L, endArrivalMs - startRt),
            accN = an,
            gyroN = gn,
            rotN = rotN,
            accHz = accStats.hz,
            gyroHz = gyroStats.hz,
            accDynRms = accRms,
            accPeak = accPeak,
            jerkRms = jerk,
            accAxisDom = accAxis,
            accPeriodMs = accStats.periodMs,
            accAuto = accStats.auto,
            accPeriodFloor = accStats.floorHit,
            gyroRms = gyroRms,
            gyroPeak = gyroPeak,
            gyroAxisDom = gyroAxis,
            gyroPeriodMs = gyroStats.periodMs,
            gyroAuto = gyroStats.auto,
            gyroPeriodFloor = gyroStats.floorHit,
            periodGapMs = gap,
            rotPathDeg = Math.toDegrees(rotPathRad),
            rotMaxDeg = Math.toDegrees(rotMaxRad),
            wakeOffsetMs = if (wakeRt >= 0L) wakeRt - startRt else null,
            chipDeliveredOffsetMs = if (chipRt >= 0L) chipRt - startRt else null,
            chipDeliveredDelta = chipDeliveredDelta
        )
    }

    private data class SeriesStats(
        val hz: Double,
        val periodMs: Double,
        val auto: Double,
        val floorHit: Boolean
    )

    private data class AutoResult(
        val periodMs: Double,
        val score: Double,
        val floorHit: Boolean
    )

    private fun seriesStats(v: DoubleArray, t: LongArray, n: Int): SeriesStats {
        if (n < 8) return SeriesStats(0.0, 0.0, 0.0, false)
        val span = t[n - 1] - t[0]
        val hz = if (span > 0L) (n - 1) * 1000.0 / span.toDouble() else 0.0
        val a = autocorr(v, t, n)
        return SeriesStats(hz, a.periodMs, a.score, a.floorHit)
    }

    /**
     * Не ищем "походочный" диапазон. Берём лучший повтор среди лагов
     * от minLag=4 отсчётов до половины окна.
     *
     * v434: если победил ровно minLag, это отдельно помечается floorHit.
     * Это НЕ классификационный порог. Это честная отметка, что максимум
     * корреляции упёрся в нижнюю границу измерительного поиска.
     */
    private fun autocorr(v: DoubleArray, t: LongArray, n: Int): AutoResult {
        if (n < 16) return AutoResult(0.0, 0.0, false)
        var mean = 0.0
        for (i in 0 until n) mean += v[i]
        mean /= n.toDouble()

        val avgDt = (t[n - 1] - t[0]).toDouble() / (n - 1).toDouble()
        if (avgDt <= 0.0) return AutoResult(0.0, 0.0, false)

        val minLag = 4
        val maxLag = min(n / 2, 100)
        if (maxLag < minLag) return AutoResult(0.0, 0.0, false)

        var bestLag = 0
        var best = -1.0
        for (lag in minLag..maxLag) {
            var num = 0.0
            var da = 0.0
            var db = 0.0
            var i = 0
            while (i + lag < n) {
                val a = v[i] - mean
                val b = v[i + lag] - mean
                num += a * b
                da += a * a
                db += b * b
                i++
            }
            val den = sqrt(da * db)
            if (den <= 1e-12) continue
            val r = num / den
            if (r > best) {
                best = r
                bestLag = lag
            }
        }

        if (bestLag == 0) return AutoResult(0.0, 0.0, false)
        return AutoResult(
            bestLag * avgDt,
            best.coerceIn(-1.0, 1.0),
            bestLag == minLag
        )
    }

    private fun centeredRms(v: DoubleArray, n: Int): Double {
        if (n <= 0) return 0.0
        var mean = 0.0
        for (i in 0 until n) mean += v[i]
        mean /= n.toDouble()
        var s = 0.0
        for (i in 0 until n) {
            val d = v[i] - mean
            s += d * d
        }
        return sqrt(s / n.toDouble())
    }

    private fun centeredPeak(v: DoubleArray, n: Int): Double {
        if (n <= 0) return 0.0
        var mean = 0.0
        for (i in 0 until n) mean += v[i]
        mean /= n.toDouble()
        var p = 0.0
        for (i in 0 until n) p = max(p, abs(v[i] - mean))
        return p
    }

    private fun rawRms(v: DoubleArray, n: Int): Double {
        if (n <= 0) return 0.0
        var s = 0.0
        for (i in 0 until n) s += v[i] * v[i]
        return sqrt(s / n.toDouble())
    }

    private fun rawPeak(v: DoubleArray, n: Int): Double {
        var p = 0.0
        for (i in 0 until n) p = max(p, abs(v[i]))
        return p
    }

    private fun jerkRms(v: DoubleArray, t: LongArray, n: Int): Double {
        if (n < 2) return 0.0
        var s = 0.0
        var k = 0
        for (i in 1 until n) {
            val dt = (t[i] - t[i - 1]).toDouble() / 1000.0
            if (dt <= 0.0) continue
            val j = (v[i] - v[i - 1]) / dt
            s += j * j
            k++
        }
        return if (k > 0) sqrt(s / k.toDouble()) else 0.0
    }

    /**
     * Доля дисперсии в главной оси. 1.0 = движение почти вдоль одной линии,
     * ~0.33 = энергия примерно равномерна по трём ортогональным направлениям.
     * Это PCA без библиотек: крупнейшее собственное значение через power iteration.
     */
    private fun axisDominance(
        x: DoubleArray, y: DoubleArray, z: DoubleArray, n: Int
    ): Double {
        if (n < 4) return 0.0

        var mx = 0.0; var my = 0.0; var mz = 0.0
        for (i in 0 until n) {
            mx += x[i]; my += y[i]; mz += z[i]
        }
        mx /= n; my /= n; mz /= n

        var xx = 0.0; var yy = 0.0; var zz = 0.0
        var xy = 0.0; var xz = 0.0; var yz = 0.0
        for (i in 0 until n) {
            val a = x[i] - mx
            val b = y[i] - my
            val c = z[i] - mz
            xx += a * a; yy += b * b; zz += c * c
            xy += a * b; xz += a * c; yz += b * c
        }

        val trace = xx + yy + zz
        if (trace <= 1e-12) return 0.0

        var vx = 0.577350269
        var vy = 0.577350269
        var vz = 0.577350269

        repeat(10) {
            val nx = xx * vx + xy * vy + xz * vz
            val ny = xy * vx + yy * vy + yz * vz
            val nz = xz * vx + yz * vy + zz * vz
            val norm = sqrt(nx * nx + ny * ny + nz * nz)
            if (norm > 1e-12) {
                vx = nx / norm
                vy = ny / norm
                vz = nz / norm
            }
        }

        val lambda =
            vx * (xx * vx + xy * vy + xz * vz) +
            vy * (xy * vx + yy * vy + yz * vz) +
            vz * (xz * vx + yz * vy + zz * vz)

        return (lambda / trace).coerceIn(0.0, 1.0)
    }

    private fun quatAngle(a: DoubleArray, b: DoubleArray): Double {
        val dot = abs(
            a[0] * b[0] + a[1] * b[1] +
                a[2] * b[2] + a[3] * b[3]
        ).coerceIn(0.0, 1.0)
        return 2.0 * acos(dot)
    }
}
