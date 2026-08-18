package com.vasil.stepcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridGuardTest {

    private fun summary(
        accPeriod: Double = 1085.0,
        gyroPeriod: Double = 1105.0,
        accAuto: Double = 0.76,
        gyroAuto: Double = 0.54,
        accFloor: Boolean = false,
        gyroFloor: Boolean = false,
        gyroRms: Double = 2.5,
        rotRate: Double = 124.0,
        rotMax: Double = 110.0,
        accRms: Double = 4.0
    ): EnergyProbe.Summary {
        val duration = 3_000L
        return EnergyProbe.Summary(
            scene = 1L,
            trigger = "TEST",
            durationMs = duration,
            accN = 150,
            gyroN = 150,
            rotN = 150,
            accHz = 50.0,
            gyroHz = 50.0,
            accDynRms = accRms,
            accPeak = 10.0,
            jerkRms = 80.0,
            accAxisDom = 0.6,
            accPeriodMs = accPeriod,
            accAuto = accAuto,
            accPeriodFloor = accFloor,
            gyroRms = gyroRms,
            gyroPeak = gyroRms * 2.0,
            gyroAxisDom = 0.7,
            gyroPeriodMs = gyroPeriod,
            gyroAuto = gyroAuto,
            gyroPeriodFloor = gyroFloor,
            periodGapMs = kotlin.math.abs(accPeriod - gyroPeriod),
            rotPathDeg = rotRate * 3.0,
            rotMaxDeg = rotMax,
            wakeOffsetMs = null,
            chipDeliveredOffsetMs = null,
            chipDeliveredDelta = 0
        )
    }

    @Test
    fun cleanPocketWalkIsGait() {
        val g = HybridGuard()
        assertEquals(
            HybridGuard.Physical.GAIT,
            g.classify(summary())
        )
    }

    @Test
    fun coherentRhythmDoesNotHideStrongShake() {
        val g = HybridGuard()
        assertEquals(
            HybridGuard.Physical.STRONG_SHAKE,
            g.classify(
                summary(
                    accPeriod = 1279.0,
                    gyroPeriod = 1279.0,
                    accAuto = 0.95,
                    gyroAuto = 0.94,
                    gyroRms = 5.41,
                    rotRate = 266.8,
                    rotMax = 169.0,
                    accRms = 9.8
                )
            )
        )
    }

    @Test
    fun stickyGoogleStillCannotDeletePhysicalGait() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)

        val chip = g.onChip(ctx, 21, 1_000_000L)
        assertEquals(0, chip.release)
        assertEquals(21, chip.held)
        assertTrue(chip.requestProbe)

        val physical = g.onProbe(ctx, summary(), 1_003_000L)
        assertEquals(21, physical.release)
        assertEquals(0, physical.discarded)
        assertEquals(0, g.pendingSteps())
    }

    @Test
    fun stillPlusTwoModerateWindowsCanConfirmShake() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)
        val moderate = summary(
            accPeriod = 78.0,
            gyroPeriod = 1180.0,
            accFloor = true,
            gyroRms = 3.5,
            rotRate = 180.0,
            rotMax = 140.0,
            accRms = 6.0
        )

        val chip = g.onChip(ctx, 17, 2_000_000L)
        assertTrue(chip.requestProbe)

        val p1 = g.onProbe(ctx, moderate, 2_003_000L)
        assertTrue(p1.requestProbe)
        assertEquals(17, p1.held)

        val p2 = g.onProbe(ctx, moderate, 2_006_000L)
        assertEquals(0, p2.release)
        assertEquals(17, p2.discarded)
        assertEquals(0, g.pendingSteps())
    }

    @Test
    fun stillAloneNeverDropsAmbiguousPhysics() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)
        val ambiguous = summary(
            accPeriod = 500.0,
            gyroPeriod = 700.0,
            gyroRms = 2.0,
            rotRate = 120.0,
            rotMax = 90.0
        )

        val chip = g.onChip(ctx, 15, 3_000_000L)
        assertTrue(chip.requestProbe)

        val p1 = g.onProbe(ctx, ambiguous, 3_003_000L)
        assertTrue(p1.requestProbe)

        val p2 = g.onProbe(ctx, ambiguous, 3_006_000L)
        assertEquals(15, p2.release)
        assertEquals(0, p2.discarded)
    }

    @Test
    fun stillPreflightHoldsFirstChipUntilPhysicalVerdict() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)

        val pre = g.onMotionHint(ctx, 3_500_000L)
        assertTrue(pre.requestProbe)

        val chip = g.onChip(ctx, 10, 3_501_000L)
        assertEquals(0, chip.release)
        assertEquals(10, chip.held)

        val gait = g.onProbe(ctx, summary(), 3_503_000L)
        assertEquals(10, gait.release)
        assertEquals(0, gait.discarded)
    }

    @Test
    fun freshNegativeTransitionGetsChipTailGrace() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 5_000L)

        val d = g.onChip(ctx, 42, 4_000_000L)
        assertEquals(42, d.release)
        assertEquals(0, d.discarded)
    }

    @Test
    fun transportNeedsGoogleAndTwoPhysicalNonGaitWindows() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.TRANSPORT, 120_000L)
        val quiet = summary(
            accPeriod = 400.0,
            gyroPeriod = 500.0,
            accAuto = 0.2,
            gyroAuto = 0.2,
            gyroRms = 0.5,
            rotRate = 20.0,
            rotMax = 10.0,
            accRms = 0.4
        )

        val chip = g.onChip(ctx, 12, 5_000_000L)
        assertTrue(chip.requestProbe)

        val p1 = g.onProbe(ctx, quiet, 5_003_000L)
        assertTrue(p1.requestProbe)

        val p2 = g.onProbe(ctx, quiet, 5_006_000L)
        assertEquals(12, p2.discarded)
        assertEquals(0, p2.release)
    }

    @Test
    fun recoveredHoldIsFailOpen() {
        val g = HybridGuard(recoveredHeld = 33)
        assertEquals(33, g.pendingSteps())

        val d = g.recoverFailOpen()
        assertEquals(33, d.release)
        assertEquals(0, g.pendingSteps())
    }

    @Test
    fun locomotionHintDoesNotBecomePermanentTruth() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)

        val d = g.onChip(ctx, 20, 6_000_000L)
        assertEquals(20, d.release)
        assertTrue(d.requestProbe)
        assertFalse(d.discarded > 0)
    }
}
