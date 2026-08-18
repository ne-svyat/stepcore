package com.vasil.stepcore

/**
 * Semantic locomotion subtype.
 *
 * Guard still uses coarse HybridContextHint for PASS/HOLD/DROP.
 * This subtype exists only for WALK/RUN attribution after physical GAIT.
 */
internal enum class HybridLocomotionHint {
    RUNNING,
    WALKING,
    ON_FOOT,
    UNKNOWN
}

internal enum class HybridMotionKind {
    WALK,
    RUN,
    UNKNOWN
}

/**
 * Canonical background WALK/RUN classifier.
 *
 * Important:
 * - it is called only for a physical GAIT verdict;
 * - RUN is never inferred from cadence alone;
 * - fresh on-demand Sampling may override a stale Transition subtype;
 * - cadence only rejects obvious semantic contradictions and becomes a
 *   clean tempo sample after semantics + physical gait agree.
 */
internal class HybridMotionFusion {

    data class Verdict(
        val motion: HybridMotionKind,
        val changed: Boolean,
        val trusted: Boolean,
        val sampleIntervalMs: Long?,
        val sampleEligible: Boolean,
        val needsSemanticRefresh: Boolean,
        val reason: String
    )

    private var current = HybridMotionKind.UNKNOWN
    private var trustedUntilRt = 0L

    fun reset() {
        current = HybridMotionKind.UNKNOWN
        trustedUntilRt = 0L
    }

    fun current(nowRt: Long): HybridMotionKind {
        expire(nowRt)
        return current
    }

    /**
     * Average interval per hardware step from two STEP_COUNTER aggregate
     * events. Sensor timestamps describe the last included step in each event.
     *
     * Long delivery batching is allowed. A pause can only make cadence slower,
     * which may miss RUN but cannot create a false fast RUN.
     */
    fun chipIntervalMs(
        delta: Int,
        prevSensorMs: Long,
        sensorMs: Long
    ): Long? {
        if (delta < MIN_CHIP_STEPS) return null
        if (prevSensorMs <= 0L || sensorMs <= prevSensorMs) return null

        val span = sensorMs - prevSensorMs
        if (span < MIN_CHIP_SPAN_MS || span > MAX_CHIP_SPAN_MS) return null

        val iv = span.toDouble() / delta.toDouble()
        if (iv < SANE_STEP_MIN_MS || iv > SANE_STEP_MAX_MS) return null

        return kotlin.math.round(iv).toLong()
    }

    fun onGait(
        context: HybridContext,
        chipIntervalMs: Long?,
        nowRt: Long
    ): Verdict {
        expire(nowRt)

        val semantic = chooseSemantic(context)
        val candidate = when (semantic) {
            HybridLocomotionHint.RUNNING -> {
                // A stale RUNNING label plus walking-speed chip timing is a
                // conflict. Do not manufacture a run.
                if (
                    chipIntervalMs != null &&
                    chipIntervalMs > RUN_CONTRADICTION_MS
                ) HybridMotionKind.UNKNOWN
                else HybridMotionKind.RUN
            }

            HybridLocomotionHint.WALKING -> {
                // Symmetric sanity gate. 220 ms is far beyond the measured
                // walking corpus; such a value means mixed/broken timing.
                if (
                    chipIntervalMs != null &&
                    chipIntervalMs < WALK_CONTRADICTION_MS
                ) HybridMotionKind.UNKNOWN
                else HybridMotionKind.WALK
            }

            HybridLocomotionHint.ON_FOOT,
            HybridLocomotionHint.UNKNOWN -> HybridMotionKind.UNKNOWN
        }

        if (candidate == HybridMotionKind.UNKNOWN) {
            return Verdict(
                motion = current,
                changed = false,
                trusted = current != HybridMotionKind.UNKNOWN,
                sampleIntervalMs = null,
                sampleEligible = false,
                needsSemanticRefresh = true,
                reason = "GAIT есть, semantic subtype не доказан"
            )
        }

        val changed = candidate != current
        current = candidate
        trustedUntilRt = nowRt + MOTION_TRUST_MS

        val sampleOk = when (candidate) {
            HybridMotionKind.RUN ->
                chipIntervalMs != null &&
                    chipIntervalMs in AUTO_RUN_SAMPLE_MIN_MS..AUTO_RUN_SAMPLE_MAX_MS

            HybridMotionKind.WALK ->
                chipIntervalMs != null &&
                    chipIntervalMs in AUTO_WALK_SAMPLE_MIN_MS..AUTO_WALK_SAMPLE_MAX_MS

            HybridMotionKind.UNKNOWN -> false
        }

        return Verdict(
            motion = current,
            changed = changed,
            trusted = true,
            sampleIntervalMs = if (sampleOk) chipIntervalMs else null,
            sampleEligible = sampleOk,
            needsSemanticRefresh = false,
            reason = buildString {
                append("GAIT + ")
                append(
                    when {
                        context.sampleLocomotion != HybridLocomotionHint.UNKNOWN &&
                            context.sampleAgeMs in 0L..SAMPLE_MAX_AGE_MS &&
                            context.sampleConfidence >= SAMPLE_CONFIDENCE_MIN ->
                            "sample=${context.sampleLocomotion} ${context.sampleConfidence}%"

                        else -> "transition=${context.locomotion}"
                    }
                )
                if (chipIntervalMs != null) {
                    append(" · chip≈")
                    append(chipIntervalMs)
                    append("мс/ш")
                }
            }
        )
    }

    private fun chooseSemantic(context: HybridContext): HybridLocomotionHint {
        val sampleUsable =
            context.sampleLocomotion != HybridLocomotionHint.UNKNOWN &&
                context.sampleAgeMs in 0L..SAMPLE_MAX_AGE_MS &&
                context.sampleConfidence >= SAMPLE_CONFIDENCE_MIN

        if (sampleUsable) return context.sampleLocomotion

        return context.locomotion
    }

    private fun expire(nowRt: Long) {
        if (
            current != HybridMotionKind.UNKNOWN &&
            trustedUntilRt > 0L &&
            nowRt > trustedUntilRt
        ) {
            current = HybridMotionKind.UNKNOWN
            trustedUntilRt = 0L
        }
    }

    companion object {
        const val MOTION_TRUST_MS = 90_000L

        // On-demand GMS Sampling is only a semantic fallback.
        const val SAMPLE_MAX_AGE_MS = 45_000L
        const val SAMPLE_CONFIDENCE_MIN = 70

        const val MIN_CHIP_STEPS = 4
        const val MIN_CHIP_SPAN_MS = 1_000L
        const val MAX_CHIP_SPAN_MS = 120_000L
        const val SANE_STEP_MIN_MS = 150.0
        const val SANE_STEP_MAX_MS = 2_000.0

        // Cadence never creates RUN. It only catches absurdly stale semantics.
        const val RUN_CONTRADICTION_MS = 650L
        const val WALK_CONTRADICTION_MS = 220L

        // Automatic tempo corpus gates. These are broad storage gates,
        // NOT WALK/RUN classification thresholds.
        const val AUTO_RUN_SAMPLE_MIN_MS = 180L
        const val AUTO_RUN_SAMPLE_MAX_MS = 600L
        const val AUTO_WALK_SAMPLE_MIN_MS = 300L
        const val AUTO_WALK_SAMPLE_MAX_MS = 1_600L
    }
}
