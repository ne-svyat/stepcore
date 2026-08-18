package com.vasil.stepcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMotionTest {

    private fun ctx(
        transition: HybridLocomotionHint = HybridLocomotionHint.UNKNOWN,
        sample: HybridLocomotionHint = HybridLocomotionHint.UNKNOWN,
        sampleConfidence: Int = 0,
        sampleAgeMs: Long = -1L
    ) = HybridContext(
        hint = HybridContextHint.LOCOMOTION,
        stateAgeMs = 10_000L,
        locomotion = transition,
        sampleLocomotion = sample,
        sampleConfidence = sampleConfidence,
        sampleAgeMs = sampleAgeMs
    )

    @Test
    fun runningNeedsSemanticEvidenceAfterGait() {
        val f = HybridMotionFusion()

        val v = f.onGait(
            ctx(transition = HybridLocomotionHint.RUNNING),
            chipIntervalMs = 350L,
            nowRt = 1_000_000L
        )

        assertEquals(HybridMotionKind.RUN, v.motion)
        assertTrue(v.changed)
        assertTrue(v.trusted)
        assertTrue(v.sampleEligible)
    }

    @Test
    fun walkingNeedsSemanticEvidenceAfterGait() {
        val f = HybridMotionFusion()

        val v = f.onGait(
            ctx(transition = HybridLocomotionHint.WALKING),
            chipIntervalMs = 560L,
            nowRt = 2_000_000L
        )

        assertEquals(HybridMotionKind.WALK, v.motion)
        assertTrue(v.sampleEligible)
    }

    @Test
    fun cadenceAloneNeverCreatesRun() {
        val f = HybridMotionFusion()

        val v = f.onGait(
            ctx(transition = HybridLocomotionHint.ON_FOOT),
            chipIntervalMs = 330L,
            nowRt = 3_000_000L
        )

        assertEquals(HybridMotionKind.UNKNOWN, v.motion)
        assertTrue(v.needsSemanticRefresh)
        assertFalse(v.sampleEligible)
    }

    @Test
    fun freshSamplingCanRecoverStaleTransitionSubtype() {
        val f = HybridMotionFusion()

        val v = f.onGait(
            ctx(
                transition = HybridLocomotionHint.UNKNOWN,
                sample = HybridLocomotionHint.RUNNING,
                sampleConfidence = 88,
                sampleAgeMs = 2_000L
            ),
            chipIntervalMs = 360L,
            nowRt = 4_000_000L
        )

        assertEquals(HybridMotionKind.RUN, v.motion)
        assertTrue(v.reason.contains("sample=RUNNING"))
    }

    @Test
    fun staleSampleDoesNotOverrideTransition() {
        val f = HybridMotionFusion()

        val v = f.onGait(
            ctx(
                transition = HybridLocomotionHint.WALKING,
                sample = HybridLocomotionHint.RUNNING,
                sampleConfidence = 95,
                sampleAgeMs = 60_000L
            ),
            chipIntervalMs = 540L,
            nowRt = 5_000_000L
        )

        assertEquals(HybridMotionKind.WALK, v.motion)
    }

    @Test
    fun obviousCadenceConflictBlocksStaleRunningLabel() {
        val f = HybridMotionFusion()

        val v = f.onGait(
            ctx(transition = HybridLocomotionHint.RUNNING),
            chipIntervalMs = 900L,
            nowRt = 6_000_000L
        )

        assertEquals(HybridMotionKind.UNKNOWN, v.motion)
        assertTrue(v.needsSemanticRefresh)
    }

    @Test
    fun longHardwareBatchStillProducesAverageStepInterval() {
        val f = HybridMotionFusion()

        val iv = f.chipIntervalMs(
            delta = 130,
            prevSensorMs = 7_000_000L,
            sensorMs = 7_083_200L
        )

        assertEquals(640L, iv)
    }

    @Test
    fun invalidChipProvenanceProducesNoTempo() {
        val f = HybridMotionFusion()

        assertNull(
            f.chipIntervalMs(
                delta = 20,
                prevSensorMs = 0L,
                sensorMs = 8_000_000L
            )
        )
    }

    @Test
    fun trustedMotionExpiresInsteadOfBecomingPermanentTruth() {
        val f = HybridMotionFusion()

        f.onGait(
            ctx(transition = HybridLocomotionHint.RUNNING),
            chipIntervalMs = 350L,
            nowRt = 9_000_000L
        )

        assertEquals(
            HybridMotionKind.RUN,
            f.current(9_050_000L)
        )
        assertEquals(
            HybridMotionKind.UNKNOWN,
            f.current(
                9_000_000L +
                    HybridMotionFusion.MOTION_TRUST_MS +
                    1L
            )
        )
    }
}
