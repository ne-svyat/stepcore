package com.vasil.stepcore

internal enum class HybridContextHint {
    LOCOMOTION,
    STILL,
    TRANSPORT,
    UNKNOWN
}

internal data class HybridContext(
    val hint: HybridContextHint,
    val stateAgeMs: Long = -1L
)

/**
 * v439. Pure Kotlin production guard.
 *
 * TYPE_STEP_COUNTER остаётся единственным quantity source.
 * Google = semantic hint.
 * EnergyProbe = independent physical evidence.
 * DROP возможен только после двух физических окон.
 */
internal class HybridGuard(recoveredHeld: Int = 0) {

    enum class Physical {
        GAIT,
        STRONG_SHAKE,
        MODERATE_SHAKE,
        QUIET,
        AMBIGUOUS,
        INVALID
    }

    data class Decision(
        val release: Int = 0,
        val discarded: Int = 0,
        val held: Int = 0,
        val requestProbe: Boolean = false,
        val reason: String? = null
    )

    private var heldSteps = recoveredHeld.coerceAtLeast(0)

    private var verificationActive = false
    private var blockingVerification = false
    private var firstPhysical: Physical? = null

    private var lastPhysical = Physical.INVALID
    private var lastPhysicalAt = 0L
    private var gaitTrustUntil = 0L
    private var lastShakeConfirmedAt = 0L

    fun pendingSteps(): Int = heldSteps

    fun recoverFailOpen(): Decision {
        val n = heldSteps
        clearVerification()
        heldSteps = 0
        return if (n > 0) {
            Decision(
                release = n,
                reason = "RECOVER RELEASE $n · process restart fail-open"
            )
        } else {
            Decision()
        }
    }

    fun onScreenOn(): Decision {
        val n = heldSteps
        heldSteps = 0
        clearVerification()
        gaitTrustUntil = 0L
        lastShakeConfirmedAt = 0L
        return if (n > 0) {
            Decision(
                release = n,
                reason = "RELEASE $n · screen-on, управление вернулось старому guard"
            )
        } else Decision()
    }

    fun onMotionHint(context: HybridContext, nowRt: Long): Decision {
        if (verificationActive) return Decision(held = heldSteps)
        if (lastPhysicalAt > 0L && nowRt - lastPhysicalAt < PREFLIGHT_MIN_GAP_MS) {
            return Decision(held = heldSteps)
        }
        val blocking =
            context.hint == HybridContextHint.STILL ||
                context.hint == HybridContextHint.TRANSPORT
        startVerification(blocking = blocking)
        return Decision(
            held = heldSteps,
            requestProbe = true,
            reason = "preflight · " + context.hint.name +
                (if (blocking) " · HOLD-ready" else "")
        )
    }

    fun onChip(
        context: HybridContext,
        delta: Int,
        nowRt: Long
    ): Decision {
        if (delta <= 0) return Decision(held = heldSteps)

        if (verificationActive) {
            val negativeNow =
                context.hint == HybridContextHint.STILL ||
                    context.hint == HybridContextHint.TRANSPORT
            if (blockingVerification || negativeNow) {
                blockingVerification = true
                heldSteps += delta
                return Decision(
                    held = heldSteps,
                    reason = "HOLD +$delta · проверка уже идёт"
                )
            }
            return Decision(
                release = delta,
                held = heldSteps
            )
        }

        val recentShake =
            lastShakeConfirmedAt > 0L &&
                nowRt - lastShakeConfirmedAt <= SHAKE_RECHECK_MS

        if (recentShake) {
            heldSteps += delta
            startVerification(blocking = true)
            return Decision(
                held = heldSteps,
                requestProbe = true,
                reason = "HOLD +$delta · свежая SHAKE-сцена, перепроверка перехода"
            )
        }

        val gaitFresh =
            lastPhysical == Physical.GAIT &&
                nowRt <= gaitTrustUntil

        if (gaitFresh) {
            return Decision(release = delta, held = heldSteps)
        }

        val physicalAge =
            if (lastPhysicalAt > 0L) nowRt - lastPhysicalAt
            else Long.MAX_VALUE

        val staleAfter = when (context.hint) {
            HybridContextHint.LOCOMOTION -> LOCOMOTION_REFRESH_MS
            HybridContextHint.UNKNOWN -> UNKNOWN_REFRESH_MS
            HybridContextHint.STILL -> NEGATIVE_REFRESH_MS
            HybridContextHint.TRANSPORT -> NEGATIVE_REFRESH_MS
        }

        val negativeHint =
            context.hint == HybridContextHint.STILL ||
                context.hint == HybridContextHint.TRANSPORT

        if (
            negativeHint &&
            context.stateAgeMs in 0L until CHIP_TAIL_GRACE_MS
        ) {
            if (physicalAge > staleAfter) {
                startVerification(blocking = false)
                return Decision(
                    release = delta,
                    held = heldSteps,
                    requestProbe = true,
                    reason = "PASS +$delta · fresh ${context.hint}, tail grace + background probe"
                )
            }
            return Decision(
                release = delta,
                held = heldSteps,
                reason = "PASS +$delta · fresh ${context.hint}, tail grace"
            )
        }

        if (negativeHint) {
            heldSteps += delta
            startVerification(blocking = true)
            return Decision(
                held = heldSteps,
                requestProbe = true,
                reason = "HOLD +$delta · ${context.hint}, ждём физическое подтверждение"
            )
        }

        if (physicalAge > staleAfter) {
            startVerification(blocking = false)
            return Decision(
                release = delta,
                held = heldSteps,
                requestProbe = true,
                reason = "PASS +$delta · periodic physical refresh"
            )
        }

        return Decision(release = delta, held = heldSteps)
    }

    fun onProbe(
        context: HybridContext,
        summary: EnergyProbe.Summary,
        nowRt: Long
    ): Decision {
        if (!verificationActive) {
            return Decision(held = heldSteps)
        }

        val p = classify(summary)
        lastPhysical = p
        lastPhysicalAt = nowRt

        if (p == Physical.INVALID) {
            val n = heldSteps
            heldSteps = 0
            clearVerification()
            return Decision(
                release = n,
                reason = if (n > 0)
                    "RELEASE $n · физический probe invalid, fail-open"
                else
                    "probe invalid · fail-open"
            )
        }

        if (p == Physical.GAIT) {
            val n = heldSteps
            heldSteps = 0
            gaitTrustUntil = nowRt + gaitTrustMs(context.hint)
            lastShakeConfirmedAt = 0L
            clearVerification()
            return Decision(
                release = n,
                reason = if (n > 0)
                    "GAIT · RELEASE $n · Google ${context.hint} не имеет veto"
                else
                    "GAIT · trust ${gaitTrustMs(context.hint) / 1000}s"
            )
        }

        val first = firstPhysical
        if (first == null) {
            firstPhysical = p

            val suspicious =
                p == Physical.STRONG_SHAKE ||
                    p == Physical.MODERATE_SHAKE

            val needSecond =
                blockingVerification ||
                    suspicious

            if (needSecond) {
                if (suspicious) blockingVerification = true
                return Decision(
                    held = heldSteps,
                    requestProbe = true,
                    reason = "probe1=$p · нужен confirm"
                )
            }

            clearVerification()
            return Decision(
                held = heldSteps,
                reason = "probe1=$p · background check завершён"
            )
        }

        val second = p
        val score = shakeScore(first) + shakeScore(second)

        val shakeConfirmed =
            score >= 3 ||
                (
                    context.hint == HybridContextHint.STILL &&
                        score >= 2
                )

        val transportConfirmed =
            context.hint == HybridContextHint.TRANSPORT &&
                isNonGaitSupport(first) &&
                isNonGaitSupport(second)

        val n = heldSteps
        heldSteps = 0
        clearVerification()

        if (shakeConfirmed || transportConfirmed) {
            gaitTrustUntil = 0L
            if (shakeConfirmed) lastShakeConfirmedAt = nowRt
            val why = if (shakeConfirmed) "SHAKE" else "TRANSPORT"
            return Decision(
                discarded = n,
                reason = "$why confirmed · pair=$first→$second" +
                    if (n > 0) " · DROP $n" else ""
            )
        }

        return Decision(
            release = n,
            reason = if (n > 0)
                "pair=$first→$second не доказал veto · RELEASE $n"
            else
                "pair=$first→$second · veto не доказан"
        )
    }

    fun onProbeUnavailable(): Decision {
        val n = heldSteps
        heldSteps = 0
        clearVerification()
        return Decision(
            release = n,
            reason = if (n > 0)
                "RELEASE $n · physical probe unavailable, fail-open"
            else
                "physical probe unavailable · fail-open"
        )
    }

    fun classify(s: EnergyProbe.Summary): Physical {
        if (
            s.durationMs < MIN_PROBE_MS ||
            s.accN < MIN_SAMPLES ||
            s.gyroN < MIN_SAMPLES
        ) {
            return Physical.INVALID
        }

        val rotRate = rotRate(s)

        val periodsInGaitBand =
            s.accPeriodMs in GAIT_PERIOD_MIN_MS..GAIT_PERIOD_MAX_MS &&
                s.gyroPeriodMs in GAIT_PERIOD_MIN_MS..GAIT_PERIOD_MAX_MS

        val gait =
            !s.accPeriodFloor &&
                !s.gyroPeriodFloor &&
                periodsInGaitBand &&
                s.periodGapMs <= GAIT_PERIOD_GAP_MAX_MS &&
                s.accAuto >= GAIT_ACC_AUTO_MIN &&
                s.gyroAuto >= GAIT_GYRO_AUTO_MIN &&
                s.gyroRms <= GAIT_GYRO_MAX &&
                rotRate <= GAIT_ROT_RATE_MAX

        if (gait) return Physical.GAIT

        val strongShake =
            s.gyroRms >= STRONG_SHAKE_GYRO_MIN &&
                rotRate >= STRONG_SHAKE_ROT_RATE_MIN &&
                s.rotMaxDeg >= STRONG_SHAKE_ROT_MAX_MIN

        if (strongShake) return Physical.STRONG_SHAKE

        val rhythmAnomaly =
            s.accPeriodFloor ||
                s.gyroPeriodFloor ||
                s.periodGapMs >= MODERATE_PERIOD_GAP_MIN_MS ||
                !periodsInGaitBand

        val moderateShake =
            s.gyroRms >= MODERATE_SHAKE_GYRO_MIN &&
                rotRate >= MODERATE_SHAKE_ROT_RATE_MIN &&
                rhythmAnomaly

        if (moderateShake) return Physical.MODERATE_SHAKE

        val quiet =
            s.accDynRms <= QUIET_ACC_MAX &&
                s.gyroRms <= QUIET_GYRO_MAX &&
                rotRate <= QUIET_ROT_RATE_MAX

        return if (quiet) Physical.QUIET else Physical.AMBIGUOUS
    }

    private fun startVerification(blocking: Boolean) {
        verificationActive = true
        blockingVerification = blocking
        firstPhysical = null
    }

    private fun clearVerification() {
        verificationActive = false
        blockingVerification = false
        firstPhysical = null
    }

    private fun shakeScore(p: Physical): Int = when (p) {
        Physical.STRONG_SHAKE -> 2
        Physical.MODERATE_SHAKE -> 1
        else -> 0
    }

    private fun isNonGaitSupport(p: Physical): Boolean = when (p) {
        Physical.STRONG_SHAKE,
        Physical.MODERATE_SHAKE,
        Physical.QUIET -> true
        else -> false
    }

    private fun gaitTrustMs(hint: HybridContextHint): Long = when (hint) {
        HybridContextHint.LOCOMOTION -> 75_000L
        HybridContextHint.UNKNOWN -> 45_000L
        HybridContextHint.STILL -> 30_000L
        HybridContextHint.TRANSPORT -> 20_000L
    }

    private fun rotRate(s: EnergyProbe.Summary): Double =
        if (s.durationMs > 0L)
            s.rotPathDeg * 1000.0 / s.durationMs.toDouble()
        else 0.0

    companion object {
        const val MIN_PROBE_MS = 2_500L
        const val MIN_SAMPLES = 100

        const val GAIT_PERIOD_MIN_MS = 650.0
        const val GAIT_PERIOD_MAX_MS = 1_500.0
        const val GAIT_PERIOD_GAP_MAX_MS = 150.0
        const val GAIT_ACC_AUTO_MIN = 0.55
        const val GAIT_GYRO_AUTO_MIN = 0.40
        const val GAIT_GYRO_MAX = 4.8
        const val GAIT_ROT_RATE_MAX = 230.0

        const val STRONG_SHAKE_GYRO_MIN = 5.0
        const val STRONG_SHAKE_ROT_RATE_MIN = 240.0
        const val STRONG_SHAKE_ROT_MAX_MIN = 120.0

        const val MODERATE_SHAKE_GYRO_MIN = 2.9
        const val MODERATE_SHAKE_ROT_RATE_MIN = 150.0
        const val MODERATE_PERIOD_GAP_MIN_MS = 250.0

        const val QUIET_ACC_MAX = 1.0
        const val QUIET_GYRO_MAX = 1.2
        const val QUIET_ROT_RATE_MAX = 70.0

        const val CHIP_TAIL_GRACE_MS = 30_000L
        const val SHAKE_RECHECK_MS = 30_000L
        const val PREFLIGHT_MIN_GAP_MS = 10_000L
        const val NEGATIVE_REFRESH_MS = 30_000L
        const val UNKNOWN_REFRESH_MS = 45_000L
        const val LOCOMOTION_REFRESH_MS = 60_000L
    }
}
