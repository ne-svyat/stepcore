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
 * v440. Hybrid production guard with temporal state.
 *
 * TYPE_STEP_COUNTER remains the only quantity source.
 * Google is semantic context. EnergyProbe is physical evidence.
 * No single weak/ambiguous source can DROP steps.
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
    private var heldSinceRt = 0L

    private var verificationActive = false
    private var blockingVerification = false
    private var firstPhysical: Physical? = null
    private var verificationWindows = 0

    private var lastPhysical = Physical.INVALID
    private var lastPhysicalAt = 0L
    private var gaitTrustUntil = 0L

    // v440: memory of an already proven shake scene.
    private var shakeLatchUntil = 0L

    // Prevent immediate HOLD/release/HOLD thrashing after bounded fail-open.
    private var failOpenBypassUntil = 0L

    fun pendingSteps(): Int = heldSteps

    fun recoverFailOpen(): Decision {
        val n = heldSteps
        clearHeld()
        clearVerification()
        shakeLatchUntil = 0L
        failOpenBypassUntil = 0L
        return if (n > 0) {
            Decision(
                release = n,
                reason = "RECOVER RELEASE $n · process restart fail-open"
            )
        } else Decision()
    }

    fun onScreenOn(): Decision {
        val n = heldSteps
        clearHeld()
        clearVerification()
        gaitTrustUntil = 0L
        shakeLatchUntil = 0L
        failOpenBypassUntil = 0L
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
            latchActive(nowRt) ||
                context.hint == HybridContextHint.STILL ||
                context.hint == HybridContextHint.TRANSPORT

        startVerification(blocking)
        return Decision(
            held = heldSteps,
            requestProbe = true,
            reason = "preflight · ${context.hint}" +
                if (latchActive(nowRt)) " · SHAKE-LATCH"
                else if (blocking) " · HOLD-ready"
                else ""
        )
    }

    fun onChip(
        context: HybridContext,
        delta: Int,
        nowRt: Long
    ): Decision {
        if (delta <= 0) return Decision(held = heldSteps)

        if (heldSteps > 0 && holdTimedOut(nowRt)) {
            val old = heldSteps
            clearHeld()
            clearVerification()
            shakeLatchUntil = 0L
            failOpenBypassUntil = nowRt + FAIL_OPEN_BYPASS_MS
            return Decision(
                release = old + delta,
                reason = "TIMEOUT fail-open · RELEASE ${old + delta} · " +
                    "bypass ${FAIL_OPEN_BYPASS_MS / 1000}s"
            )
        }

        if (nowRt < failOpenBypassUntil) {
            return Decision(release = delta, held = heldSteps)
        }

        val negativeNow = isNegative(context.hint)

        if (verificationActive) {
            if (blockingVerification || negativeNow || latchActive(nowRt)) {
                blockingVerification = true
                addHeld(delta, nowRt)
                return Decision(
                    held = heldSteps,
                    reason = "HOLD +$delta · verification active" +
                        if (latchActive(nowRt)) " · SHAKE-LATCH" else ""
                )
            }
            return Decision(release = delta, held = heldSteps)
        }

        // Proven shake remains active across later Xiaomi chip batches.
        if (latchActive(nowRt)) {
            addHeld(delta, nowRt)
            startVerification(blocking = true)
            return Decision(
                held = heldSteps,
                requestProbe = true,
                reason = "HOLD +$delta · SHAKE-LATCH · перепроверка"
            )
        }

        val gaitFresh =
            lastPhysical == Physical.GAIT && nowRt <= gaitTrustUntil
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

        // Fresh semantic negative cannot execute a late STEP_COUNTER tail.
        if (negativeNow && context.stateAgeMs in 0L until CHIP_TAIL_GRACE_MS) {
            if (physicalAge > staleAfter) {
                startVerification(blocking = false)
                return Decision(
                    release = delta,
                    held = heldSteps,
                    requestProbe = true,
                    reason = "PASS +$delta · fresh ${context.hint}, " +
                        "tail grace + background probe"
                )
            }
            return Decision(
                release = delta,
                held = heldSteps,
                reason = "PASS +$delta · fresh ${context.hint}, tail grace"
            )
        }

        if (negativeNow) {
            addHeld(delta, nowRt)
            startVerification(blocking = true)
            return Decision(
                held = heldSteps,
                requestProbe = true,
                reason = "HOLD +$delta · ${context.hint}, нужно физическое подтверждение"
            )
        }

        // Positive/unknown context still gets periodic physics refresh.
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
        if (!verificationActive) return Decision(held = heldSteps)

        val p = classify(summary)
        lastPhysical = p
        lastPhysicalAt = nowRt
        verificationWindows += 1

        val evidence = evidenceLine(summary, p, context, nowRt)

        if (p == Physical.INVALID) {
            return failOpen(nowRt, "$evidence · invalid probe")
        }

        val latched = latchActive(nowRt)
        val negative = isNegative(context.hint)

        // GAIT path. Under stale-prone STILL/TRANSPORT one rhythmic window
        // is not enough, because v439 showed shake can look like one GAIT.
        if (p == Physical.GAIT) {
            if (!negative) {
                return acceptGait(context, nowRt, "$evidence · GAIT accepted")
            }

            if (firstPhysical == Physical.GAIT) {
                return acceptGait(
                    context,
                    nowRt,
                    "$evidence · GAIT×2 overrides ${context.hint}"
                )
            }

            firstPhysical = Physical.GAIT
            blockingVerification = true
            return Decision(
                held = heldSteps,
                requestProbe = true,
                reason = "$evidence · GAIT 1/2 under ${context.hint}"
            )
        }

        val previous = firstPhysical

        // Already confirmed SHAKE: ambiguity is memory, not amnesia.
        if (latched) {
            when (p) {
                Physical.STRONG_SHAKE -> {
                    return confirmLatchedShake(
                        nowRt,
                        "$evidence · latched STRONG"
                    )
                }

                Physical.MODERATE_SHAKE -> {
                    if (
                        previous == Physical.STRONG_SHAKE ||
                        previous == Physical.MODERATE_SHAKE
                    ) {
                        return confirmLatchedShake(
                            nowRt,
                            "$evidence · latched shake pair $previous→$p"
                        )
                    }

                    firstPhysical = p
                    blockingVerification = true
                    return continueOrFailOpen(
                        nowRt,
                        "$evidence · latched MODERATE 1/2"
                    )
                }

                Physical.AMBIGUOUS,
                Physical.QUIET -> {
                    firstPhysical = p
                    if (heldSteps <= 0) {
                        clearVerification()
                        return Decision(
                            reason = "$evidence · latch preserved, no HOLD"
                        )
                    }
                    return continueOrFailOpen(
                        nowRt,
                        "$evidence · latch keeps HOLD; ambiguity is NOT release"
                    )
                }

                else -> Unit
            }
        }

        // No latch yet: establish SHAKE from multiple windows.
        val currentShake = shakeScore(p)
        if (currentShake > 0) {
            val previousShake =
                if (previous == null) 0 else shakeScore(previous)

            if (previousShake > 0) {
                val score = previousShake + currentShake
                val shakeConfirmed =
                    score >= 3 ||
                        (context.hint == HybridContextHint.STILL && score >= 2)

                if (shakeConfirmed) {
                    return establishShakeLatch(
                        nowRt,
                        "$evidence · SHAKE confirmed $previous→$p"
                    )
                }
            }

            firstPhysical = p
            blockingVerification = true
            return Decision(
                held = heldSteps,
                requestProbe = true,
                reason = "$evidence · shake evidence 1/2"
            )
        }

        // Transport remains a true hybrid veto: Google + two non-gait windows.
        if (
            context.hint == HybridContextHint.TRANSPORT &&
            previous != null &&
            isNonGaitSupport(previous) &&
            isNonGaitSupport(p)
        ) {
            val n = heldSteps
            clearHeld()
            clearVerification()
            return Decision(
                discarded = n,
                reason = "$evidence · TRANSPORT confirmed $previous→$p" +
                    if (n > 0) " · DROP $n" else ""
            )
        }

        // Negative-context ambiguity no longer means RELEASE.
        if (blockingVerification || negative) {
            firstPhysical = p
            blockingVerification = true

            if (heldSteps <= 0 && verificationWindows >= 2) {
                clearVerification()
                return Decision(
                    reason = "$evidence · preflight unresolved, no HOLD"
                )
            }

            return continueOrFailOpen(
                nowRt,
                "$evidence · unresolved negative context; HOLD preserved"
            )
        }

        clearVerification()
        return Decision(
            held = heldSteps,
            reason = "$evidence · background check complete"
        )
    }

    fun onProbeUnavailable(): Decision {
        val n = heldSteps
        clearHeld()
        clearVerification()
        shakeLatchUntil = 0L
        failOpenBypassUntil = 0L
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
        ) return Physical.INVALID

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

    private fun acceptGait(
        context: HybridContext,
        nowRt: Long,
        prefix: String
    ): Decision {
        val n = heldSteps
        clearHeld()
        clearVerification()
        shakeLatchUntil = 0L
        failOpenBypassUntil = 0L
        gaitTrustUntil = nowRt + gaitTrustMs(context.hint)
        return Decision(
            release = n,
            reason = prefix +
                if (n > 0) " · RELEASE $n"
                else " · trust ${gaitTrustMs(context.hint) / 1000}s"
        )
    }

    private fun establishShakeLatch(nowRt: Long, prefix: String): Decision {
        val n = heldSteps
        clearHeld()
        clearVerification()
        gaitTrustUntil = 0L
        failOpenBypassUntil = 0L
        shakeLatchUntil = nowRt + SHAKE_LATCH_MS
        return Decision(
            discarded = n,
            reason = prefix + " · latch ${SHAKE_LATCH_MS / 1000}s" +
                if (n > 0) " · DROP $n" else ""
        )
    }

    private fun confirmLatchedShake(nowRt: Long, prefix: String): Decision {
        val n = heldSteps
        clearHeld()
        clearVerification()
        gaitTrustUntil = 0L
        failOpenBypassUntil = 0L
        shakeLatchUntil = nowRt + SHAKE_LATCH_MS
        return Decision(
            discarded = n,
            reason = prefix + " · latch renewed ${SHAKE_LATCH_MS / 1000}s" +
                if (n > 0) " · DROP $n" else ""
        )
    }

    private fun continueOrFailOpen(nowRt: Long, prefix: String): Decision {
        if (heldSteps > 0 && holdTimedOut(nowRt)) {
            return failOpen(nowRt, "$prefix · HOLD timeout")
        }
        return Decision(
            held = heldSteps,
            requestProbe = true,
            reason = prefix
        )
    }

    private fun failOpen(nowRt: Long, prefix: String): Decision {
        val n = heldSteps
        clearHeld()
        clearVerification()
        shakeLatchUntil = 0L
        failOpenBypassUntil = nowRt + FAIL_OPEN_BYPASS_MS
        return Decision(
            release = n,
            reason = prefix +
                if (n > 0)
                    " · RELEASE $n · bypass ${FAIL_OPEN_BYPASS_MS / 1000}s"
                else " · fail-open"
        )
    }

    private fun addHeld(delta: Int, nowRt: Long) {
        if (heldSteps <= 0) heldSinceRt = nowRt
        heldSteps += delta
    }

    private fun clearHeld() {
        heldSteps = 0
        heldSinceRt = 0L
    }

    private fun startVerification(blocking: Boolean) {
        verificationActive = true
        blockingVerification = blocking
        firstPhysical = null
        verificationWindows = 0
    }

    private fun clearVerification() {
        verificationActive = false
        blockingVerification = false
        firstPhysical = null
        verificationWindows = 0
    }

    private fun latchActive(nowRt: Long): Boolean =
        shakeLatchUntil > 0L && nowRt < shakeLatchUntil

    private fun isNegative(hint: HybridContextHint): Boolean =
        hint == HybridContextHint.STILL || hint == HybridContextHint.TRANSPORT

    private fun holdTimedOut(nowRt: Long): Boolean {
        if (heldSteps <= 0 || heldSinceRt <= 0L) return false
        val max = if (latchActive(nowRt)) LATCH_HOLD_MAX_MS else NEGATIVE_HOLD_MAX_MS
        return nowRt - heldSinceRt >= max
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

    private fun f1(v: Double): String =
        java.lang.String.format(java.util.Locale.US, "%.1f", v)

    private fun f2(v: Double): String =
        java.lang.String.format(java.util.Locale.US, "%.2f", v)

    private fun evidenceLine(
        summary: EnergyProbe.Summary,
        p: Physical,
        context: HybridContext,
        nowRt: Long
    ): String {
        val floor =
            (if (summary.accPeriodFloor) "A" else "-") +
                (if (summary.gyroPeriodFloor) "G" else "-")

        return "phys=$p" +
            " · gyro=${f2(summary.gyroRms)}" +
            " rot=${f1(rotRate(summary))}°/s" +
            " max=${f1(summary.rotMaxDeg)}°" +
            " · p=${f1(summary.accPeriodMs)}/${f1(summary.gyroPeriodMs)}ms" +
            " auto=${f2(summary.accAuto)}/${f2(summary.gyroAuto)}" +
            " gap=${f1(summary.periodGapMs)}" +
            " floor=$floor" +
            " · acc=${f2(summary.accDynRms)}" +
            " jerk=${f1(summary.jerkRms)}" +
            " axis=${f2(summary.accAxisDom)}/${f2(summary.gyroAxisDom)}" +
            " · ctx=${context.hint}" +
            " latch=" + (if (latchActive(nowRt)) "ON" else "off") +
            " hold=$heldSteps"
    }

    companion object {
        const val MIN_PROBE_MS = 2_500L
        const val MIN_SAMPLES = 100

        // Physical thresholds from v439 are deliberately frozen in v440.
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
        const val PREFLIGHT_MIN_GAP_MS = 10_000L
        const val NEGATIVE_REFRESH_MS = 30_000L
        const val UNKNOWN_REFRESH_MS = 45_000L
        const val LOCOMOTION_REFRESH_MS = 60_000L

        // Field-derived temporal policy from the v439 composite test.
        const val SHAKE_LATCH_MS = 30_000L
        const val LATCH_HOLD_MAX_MS = 20_000L
        const val NEGATIVE_HOLD_MAX_MS = 12_000L
        const val FAIL_OPEN_BYPASS_MS = 15_000L
    }
}
