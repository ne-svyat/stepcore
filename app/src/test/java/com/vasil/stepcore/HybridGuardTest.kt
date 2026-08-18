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

    private fun establishLatch(
        g: HybridGuard,
        start: Long = 1_000_000L
    ) {
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)
        assertTrue(g.onMotionHint(ctx, start).requestProbe)
        assertTrue(
            g.onProbe(ctx, strongShake(), start + 3_000L).requestProbe
        )
        val p2 = g.onProbe(ctx, strongShake(), start + 6_000L)
        assertTrue(p2.reason?.contains("SHAKE confirmed") == true)
    }

    @Test
    fun screenOnLockedStillBelongsToHybrid() {
        assertTrue(
            hybridOwnsPipeline(
                interactive = true,
                keyguardLocked = true
            )
        )
        assertTrue(
            hybridOwnsPipeline(
                interactive = false,
                keyguardLocked = false
            )
        )
        assertFalse(
            hybridOwnsPipeline(
                interactive = true,
                keyguardLocked = false
            )
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
            g.classify(strongShake())
        )
    }

    @Test
    fun stickyStillNeedsTwoConsecutiveGaitsBeforeRelease() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)

        val chip = g.onChip(
            ctx, 21, 2_000_000L,
            prevChipSensorMs = 1_990_000L,
            chipSensorMs = 2_000_000L
        )
        assertEquals(21, chip.held)

        val gait1 = g.onProbe(ctx, summary(), 2_003_000L)
        assertEquals(0, gait1.release)
        assertEquals(21, gait1.held)
        assertTrue(gait1.requestProbe)

        val gait2 = g.onProbe(ctx, summary(), 2_006_000L)
        assertEquals(21, gait2.release)
        assertEquals(0, gait2.discarded)
    }

    @Test
    fun boundaryBatchIsReleasedWhenShakeConfirms() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)

        assertTrue(g.onMotionHint(ctx, 3_000_000L).requestProbe)
        assertTrue(
            g.onProbe(ctx, strongShake(), 3_003_000L).requestProbe
        )

        val chip = g.onChip(
            ctx, 192, 3_004_000L,
            prevChipSensorMs = 2_880_000L,
            chipSensorMs = 3_004_000L
        )
        assertEquals(192, chip.held)

        val confirm =
            g.onProbe(ctx, strongShake(), 3_006_000L)

        assertEquals(192, confirm.release)
        assertEquals(0, confirm.discarded)
        assertTrue(confirm.reason?.contains("boundary=192") == true)
    }

    @Test
    fun fullyPostShakeBatchCanBeDropped() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)

        g.onMotionHint(ctx, 4_000_000L)
        g.onProbe(ctx, strongShake(), 4_003_000L)

        val chip = g.onChip(
            ctx, 17, 4_004_000L,
            prevChipSensorMs = 4_001_000L,
            chipSensorMs = 4_004_000L
        )
        assertEquals(17, chip.held)

        val confirm =
            g.onProbe(ctx, strongShake(), 4_006_000L)

        assertEquals(0, confirm.release)
        assertEquals(17, confirm.discarded)
        assertTrue(confirm.reason?.contains("post=17") == true)
    }

    @Test
    fun unknownProvenanceIsFailOpenOnShake() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)

        g.onMotionHint(ctx, 5_000_000L)
        g.onProbe(ctx, strongShake(), 5_003_000L)
        g.onChip(ctx, 50, 5_004_000L)

        val confirm =
            g.onProbe(ctx, strongShake(), 5_006_000L)

        assertEquals(50, confirm.release)
        assertEquals(0, confirm.discarded)
        assertTrue(confirm.reason?.contains("unknown=50") == true)
    }

    @Test
    fun latchedLedgerSplitsBoundaryAndPostShakeBatches() {
        val g = HybridGuard()
        establishLatch(g, 6_000_000L)
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)

        g.onChip(
            ctx, 192, 6_008_000L,
            prevChipSensorMs = 5_850_000L,
            chipSensorMs = 6_008_000L
        )
        g.onProbe(ctx, ambiguous(), 6_011_000L)

        g.onChip(
            ctx, 61, 6_012_000L,
            prevChipSensorMs = 6_008_000L,
            chipSensorMs = 6_012_000L
        )

        val strong =
            g.onProbe(ctx, strongShake(), 6_014_000L)

        assertEquals(192, strong.release)
        assertEquals(61, strong.discarded)
        assertEquals(0, g.pendingSteps())
        assertTrue(strong.reason?.contains("boundary=192") == true)
        assertTrue(strong.reason?.contains("post=61") == true)
    }

    @Test
    fun latchedAmbiguityStillDoesNotRelease() {
        val g = HybridGuard()
        establishLatch(g, 7_000_000L)
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)

        val chip = g.onChip(
            ctx, 22, 7_008_000L,
            prevChipSensorMs = 7_001_000L,
            chipSensorMs = 7_008_000L
        )
        assertEquals(22, chip.held)

        val a1 = g.onProbe(ctx, ambiguous(), 7_011_000L)
        assertEquals(0, a1.release)
        assertEquals(22, a1.held)
        assertTrue(a1.requestProbe)
    }

    @Test
    fun latchedShakeToLocomotionGaitReleasesImmediately() {
        val g = HybridGuard()
        establishLatch(g, 8_000_000L)
        val ctx = HybridContext(HybridContextHint.LOCOMOTION, 100_000L)

        g.onChip(
            ctx, 22, 8_008_000L,
            prevChipSensorMs = 8_001_000L,
            chipSensorMs = 8_008_000L
        )

        val gait = g.onProbe(ctx, summary(), 8_011_000L)
        assertEquals(22, gait.release)
        assertEquals(0, gait.discarded)
    }

    @Test
    fun stillModeratePlusModerateProtectsBoundary() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)

        val chip = g.onChip(
            ctx, 17, 9_000_000L,
            prevChipSensorMs = 8_980_000L,
            chipSensorMs = 9_000_000L
        )
        assertTrue(chip.requestProbe)

        g.onProbe(ctx, moderateShake(), 9_003_000L)
        val p2 = g.onProbe(ctx, moderateShake(), 9_006_000L)

        assertEquals(17, p2.release)
        assertEquals(0, p2.discarded)
    }

    @Test
    fun negativeAmbiguityStillFailsOpenAfterBoundedTimeout() {
        val g = HybridGuard()
        val ctx = HybridContext(HybridContextHint.STILL, 300_000L)

        g.onChip(
            ctx, 15, 10_000_000L,
            prevChipSensorMs = 9_990_000L,
            chipSensorMs = 10_000_000L
        )

        g.onProbe(ctx, ambiguous(), 10_003_000L)
        g.onProbe(ctx, ambiguous(), 10_006_000L)
        g.onProbe(ctx, ambiguous(), 10_009_000L)
        val timeout = g.onProbe(ctx, ambiguous(), 10_012_000L)

        assertEquals(15, timeout.release)
        assertEquals(0, timeout.discarded)
        assertTrue(timeout.reason?.contains("fail-open") == true)
    }

    @Test
    fun transportBoundaryIsAlsoProtected() {
        val g = HybridGuard()
        val ctx = HybridContext(
            HybridContextHint.TRANSPORT,
            stateAgeMs = 20_000L
        )

        g.onChip(
            ctx, 40, 11_000_000L,
            prevChipSensorMs = 10_950_000L,
            chipSensorMs = 11_000_000L
        )

        g.onProbe(ctx, quiet(), 11_003_000L)
        val p2 = g.onProbe(ctx, quiet(), 11_006_000L)

        assertEquals(40, p2.release)
        assertEquals(0, p2.discarded)
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

        val d = g.onChip(
            ctx, 20, 12_000_000L,
            prevChipSensorMs = 11_990_000L,
            chipSensorMs = 12_000_000L
        )

        assertEquals(20, d.release)
        assertTrue(d.requestProbe)
        assertFalse(d.discarded > 0)
    }
}
