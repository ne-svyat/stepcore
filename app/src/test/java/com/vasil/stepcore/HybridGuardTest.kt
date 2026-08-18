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
        accRms: Double = 4.0,
        jerk: Double = 80.0
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
            jerkRms = jerk,
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

    private fun strongShake(): EnergyProbe.Summary =
        summary(
            accPeriod = 1279.0,
            gyroPeriod = 1279.0,
            accAuto = 0.95,
            gyroAuto = 0.94,
            gyroRms = 5.41,
            rotRate = 266.8,
            rotMax = 169.0,
            accRms = 9.8,
            jerk = 110.5
        )

    private fun moderateShake(): EnergyProbe.Summary =
        summary(
            accPeriod = 78.0,
            gyroPeriod = 1180.0,
            accFloor = true,
            gyroRms = 3.5,
            rotRate = 180.0,
            rotMax = 140.0,
            accRms = 6.0
        )

    private fun ambiguous(): EnergyProbe.Summary =
        summary(
            accPeriod = 500.0,
            gyroPeriod = 700.0,
            gyroRms = 2.0,
            rotRate = 120.0,
            rotMax = 90.0
        )

    private fun quiet(): EnergyProbe.Summary =
        summary(
            accPeriod = 400.0,
            gyroPeriod = 500.0,
            accAuto = 0.2,
            gyroAuto = 0.2,
            gyroRms = 0.5,
            rotRate = 20.0,
            rotMax = 10.0,
            accRms = 0.4
        )

    private fun establishLatch(g: HybridGuard, start: Long) {
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)
        assertTrue(g.onMotionHint(ctx, start).requestProbe)
        assertTrue(g.onProbe(ctx, strongShake(), start + 3_000L).requestProbe)
        val p2 = g.onProbe(ctx, strongShake(), start + 6_000L)
        assertEquals(0, p2.release)
        assertEquals(0, p2.discarded)
        assertTrue(p2.reason?.contains("SHAKE confirmed") == true)
    }

    @Test
    fun cleanPocketWalkIsGait() {
        assertEquals(HybridGuard.Physical.GAIT, HybridGuard().classify(summary()))
    }

    @Test
    fun coherentRhythmDoesNotHideStrongShake() {
        assertEquals(
            HybridGuard.Physical.STRONG_SHAKE,
            HybridGuard().classify(strongShake())
        )
    }

    @Test
    fun stickyStillNeedsTwoConsecutiveGaitsBeforeRelease() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)
        val chip = g.onChip(ctx, 21, 2_000_000L)
        assertEquals(21, chip.held)
        val gait1 = g.onProbe(ctx, summary(), 2_003_000L)
        assertEquals(0, gait1.release)
        assertEquals(21, gait1.held)
        assertTrue(gait1.requestProbe)
        assertTrue(gait1.reason?.contains("GAIT 1/2") == true)
        val gait2 = g.onProbe(ctx, summary(), 2_006_000L)
        assertEquals(21, gait2.release)
        assertEquals(0, gait2.discarded)
    }

    @Test
    fun stillModeratePlusModerateCanConfirmShake() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)
        assertTrue(g.onChip(ctx, 17, 3_000_000L).requestProbe)
        assertTrue(g.onProbe(ctx, moderateShake(), 3_003_000L).requestProbe)
        val p2 = g.onProbe(ctx, moderateShake(), 3_006_000L)
        assertEquals(17, p2.discarded)
        assertEquals(0, p2.release)
    }

    @Test
    fun latchedShakeHoldsAmbiguousBatchesUntilStrongDropsAll() {
        val g = HybridGuard()
        establishLatch(g, 4_000_000L)
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)
        assertEquals(22, g.onChip(ctx, 22, 4_008_000L).held)
        val a1 = g.onProbe(ctx, ambiguous(), 4_011_000L)
        assertEquals(22, a1.held)
        assertEquals(0, a1.release)
        assertTrue(a1.requestProbe)
        assertEquals(40, g.onChip(ctx, 18, 4_012_000L).held)
        val a2 = g.onProbe(ctx, ambiguous(), 4_014_000L)
        assertEquals(40, a2.held)
        assertEquals(0, a2.release)
        assertEquals(56, g.onChip(ctx, 16, 4_015_000L).held)
        val strong = g.onProbe(ctx, strongShake(), 4_017_000L)
        assertEquals(56, strong.discarded)
        assertEquals(0, strong.release)
    }

    @Test
    fun latchedShakeToLocomotionGaitReleasesImmediately() {
        val g = HybridGuard()
        establishLatch(g, 5_000_000L)
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)
        assertEquals(22, g.onChip(ctx, 22, 5_008_000L).held)
        val gait = g.onProbe(ctx, summary(), 5_011_000L)
        assertEquals(22, gait.release)
        assertEquals(0, gait.discarded)
    }

    @Test
    fun latchedShakeStickyStillAlsoNeedsTwoGaits() {
        val g = HybridGuard()
        establishLatch(g, 6_000_000L)
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)
        assertEquals(20, g.onChip(ctx, 20, 6_008_000L).held)
        val gait1 = g.onProbe(ctx, summary(), 6_011_000L)
        assertEquals(0, gait1.release)
        assertEquals(20, gait1.held)
        assertTrue(gait1.requestProbe)
        val gait2 = g.onProbe(ctx, summary(), 6_014_000L)
        assertEquals(20, gait2.release)
        assertEquals(0, gait2.discarded)
    }

    @Test
    fun latchedAmbiguityEventuallyFailsOpen() {
        val g = HybridGuard()
        establishLatch(g, 7_000_000L)
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)
        assertEquals(18, g.onChip(ctx, 18, 7_008_000L).held)
        g.onProbe(ctx, ambiguous(), 7_011_000L)
        g.onProbe(ctx, ambiguous(), 7_014_000L)
        g.onProbe(ctx, ambiguous(), 7_017_000L)
        g.onProbe(ctx, ambiguous(), 7_020_000L)
        g.onProbe(ctx, ambiguous(), 7_023_000L)
        g.onProbe(ctx, ambiguous(), 7_026_000L)
        val timeout = g.onProbe(ctx, ambiguous(), 7_029_000L)
        assertEquals(18, timeout.release)
        assertEquals(0, timeout.discarded)
        assertTrue(timeout.reason?.contains("fail-open") == true)
    }

    @Test
    fun negativeAmbiguityDoesNotReleaseBeforeBoundedTimeout() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)
        assertTrue(g.onChip(ctx, 15, 8_000_000L).requestProbe)
        assertEquals(0, g.onProbe(ctx, ambiguous(), 8_003_000L).release)
        assertEquals(0, g.onProbe(ctx, ambiguous(), 8_006_000L).release)
        assertEquals(0, g.onProbe(ctx, ambiguous(), 8_009_000L).release)
        val timeout = g.onProbe(ctx, ambiguous(), 8_012_000L)
        assertEquals(15, timeout.release)
        assertEquals(0, timeout.discarded)
    }

    @Test
    fun freshNegativeTransitionGetsChipTailGrace() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 5_000L)
        val d = g.onChip(ctx, 42, 9_000_000L)
        assertEquals(42, d.release)
        assertEquals(0, d.discarded)
    }

    @Test
    fun transportStillNeedsTwoPhysicalNonGaitWindows() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.TRANSPORT, 120_000L)
        assertTrue(g.onChip(ctx, 12, 10_000_000L).requestProbe)
        assertTrue(g.onProbe(ctx, quiet(), 10_003_000L).requestProbe)
        val p2 = g.onProbe(ctx, quiet(), 10_006_000L)
        assertEquals(12, p2.discarded)
        assertEquals(0, p2.release)
    }

    @Test
    fun recoveredHoldIsFailOpen() {
        val g = HybridGuard(recoveredHeld = 33)
        val d = g.recoverFailOpen()
        assertEquals(33, d.release)
        assertEquals(0, g.pendingSteps())
    }

    @Test
    fun locomotionHintDoesNotBecomePermanentTruth() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)
        val d = g.onChip(ctx, 20, 11_000_000L)
        assertEquals(20, d.release)
        assertTrue(d.requestProbe)
        assertFalse(d.discarded > 0)
    }

    @Test
    fun evidenceLineIsPresentOnPhysicalDecision() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)
        g.onMotionHint(ctx, 12_000_000L)
        val d = g.onProbe(ctx, strongShake(), 12_003_000L)
        assertTrue(d.reason?.contains("phys=STRONG_SHAKE") == true)
        assertTrue(d.reason?.contains("gyro=") == true)
        assertTrue(d.reason?.contains("rot=") == true)
        assertTrue(d.reason?.contains("ctx=LOCOMOTION") == true)
    }
}
