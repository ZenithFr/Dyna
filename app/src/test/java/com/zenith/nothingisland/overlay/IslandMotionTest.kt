package com.zenith.nothingisland.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IslandMotionTest {
    @Test
    fun springConvergesWithoutDrift() {
        val out = FloatArray(2)
        var position = 0f
        var velocity = 0f
        repeat(240) {
            IslandMotion.stepSpring(position, velocity, 1f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, 1f / 120f, out)
            position = out[0]
            velocity = out[1]
        }
        assertEquals(1f, position, 0.001f)
        assertEquals(0f, velocity, 0.01f)
    }

    @Test
    fun trajectoryIsRefreshRateIndependent() {
        fun simulate(steps: Int): Float {
            val out = FloatArray(2)
            var position = 0f
            var velocity = 0f
            repeat(steps) {
                IslandMotion.stepSpring(position, velocity, 1f, IslandMotion.HEIGHT_OMEGA, IslandMotion.HEIGHT_ZETA, 0.5f / steps, out)
                position = out[0]
                velocity = out[1]
            }
            return position
        }
        assertTrue(abs(simulate(30) - simulate(60)) < 0.0001f)
    }

    @Test
    fun zeroStepPreservesInterruptedVelocity() {
        val out = FloatArray(2)
        IslandMotion.stepSpring(0.4f, 2.5f, 1f, 24f, 0.88f, 0f, out)
        assertEquals(0.4f, out[0], 0f)
        assertEquals(2.5f, out[1], 0f)
    }

    @Test
    fun mitosisNeckPinchesOffBeforeTheIconIsFullyRevealed() {
        assertEquals(1f, IslandMotion.satelliteNeckShare(0f), 0.001f)
        assertEquals(0f, IslandMotion.satelliteNeckShare(IslandMotion.SATELLITE_NECK_BREAK), 0.001f)
        assertEquals(0f, IslandMotion.satelliteIconShare(0f), 0.001f)
        assertEquals(1f, IslandMotion.satelliteIconShare(1f), 0.001f)
        assertTrue(IslandMotion.satelliteNeckShare(0.5f) > IslandMotion.satelliteNeckShare(0.7f))
        assertTrue(IslandMotion.satelliteIconShare(0.5f) < IslandMotion.satelliteIconShare(0.9f))
    }

    @Test
    fun satelliteSpringIsRefreshRateIndependentAndReversible() {
        fun simulate(steps: Int, target: Float, start: Float): Float {
            val out = FloatArray(2)
            var position = start
            var velocity = 0f
            repeat(steps) {
                IslandMotion.stepSpring(position, velocity, target, IslandMotion.SATELLITE_OMEGA, IslandMotion.SATELLITE_ZETA, 0.4f / steps, out)
                position = out[0]
                velocity = out[1]
            }
            return position
        }
        assertTrue(abs(simulate(24, 1f, 0f) - simulate(48, 1f, 0f)) < 0.0001f)
        assertTrue(abs(simulate(24, 0f, 1f) - simulate(48, 0f, 1f)) < 0.0001f)
    }

    @Test
    fun idleCurlNarrowsBeforeItFlattens() {
        assertTrue(IslandMotion.dormantWidthShare(0.5f) > IslandMotion.dormantHeightShare(0.5f))
        assertEquals(0f, IslandMotion.dormantHeightShare(0.4f), 0.001f)
        assertEquals(1f, IslandMotion.dormantWidthShare(1f), 0f)
        assertEquals(1f, IslandMotion.dormantHeightShare(1f), 0f)
    }

    @Test
    fun frameDeltaIsZeroOnTheFirstFrameAndNeverGoesBackwards() {
        // No previous stamp: the shell starts at rest instead of advancing a phantom 1/120 s.
        assertEquals(0f, IslandMotion.frameDelta(0L, 1_000_000_000L), 0f)
        assertEquals(0f, IslandMotion.frameDelta(2_000_000_000L, 1_000_000_000L), 0f)
        assertEquals(0f, IslandMotion.frameDelta(1_000_000_000L, 1_000_000_000L), 0f)
    }

    @Test
    fun frameDeltaMeasuresWallClockTime() {
        assertEquals(0.016f, IslandMotion.frameDelta(1_000_000_000L, 1_016_000_000L), 1e-7f)
        assertEquals(0.120f, IslandMotion.frameDelta(1_000_000_000L, 1_120_000_000L), 1e-7f)
        assertEquals(0.0083f, IslandMotion.frameDelta(1_000_000_000L, 1_008_300_000L), 1e-7f)
    }

    @Test
    fun manySmallStepsMatchOneStepOfTheSameTotalTime() {
        fun simulate(steps: Int, dt: Float): Float {
            val out = FloatArray(2)
            var position = 0f
            var velocity = 0f
            repeat(steps) {
                IslandMotion.stepSpring(position, velocity, 1f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, dt, out)
                position = out[0]
                velocity = out[1]
            }
            return position
        }
        // 16 frames at 16 ms are the same 256 ms as one step; the closed form is exact either way.
        assertEquals(simulate(1, 0.256f), simulate(16, 0.016f), 0.0001f)
    }

    @Test
    fun aHitchStepsTheWallClockTimeNotTheOldClamp() {
        val dt = IslandMotion.frameDelta(1_000_000_000L, 1_120_000_000L)
        assertEquals(0.120f, dt, 1e-7f)

        val out = FloatArray(2)
        IslandMotion.stepSpring(0f, 0f, 1f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, dt, out)
        val hitchPosition = out[0]
        IslandMotion.stepSpring(0f, 0f, 1f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, 0.032f, out)
        // The old 32 ms clamp discarded 88 ms of the hitch; this must land further along.
        assertTrue(hitchPosition > out[0] + 0.05f)

        val wallClock = FloatArray(2)
        IslandMotion.stepSpring(0f, 0f, 1f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, 0.120f, wallClock)
        assertEquals(wallClock[0], hitchPosition, 1e-6f)
    }

    @Test
    fun aLongBackgroundGapIsCappedAndDoesNotTeleport() {
        val dt = IslandMotion.frameDelta(1_000_000_000L, 6_000_000_000L)
        assertEquals(IslandMotion.MAX_FRAME_STEP_SECONDS, dt, 0f)

        val out = FloatArray(2)
        IslandMotion.stepSpring(0f, 0f, 1f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, dt, out)
        // Capped at 250 ms the spring is essentially settled, not flung past the target.
        assertTrue(out[0] in 0.99f..1.01f)
        assertTrue(abs(out[1]) < 0.2f)
    }

    @Test
    fun reversingMidFlightKeepsMomentum() {
        val out = FloatArray(2)
        var position = 0f
        var velocity = 0f
        repeat(5) {
            IslandMotion.stepSpring(position, velocity, 1f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, 1f / 60f, out)
            position = out[0]
            velocity = out[1]
        }
        assertTrue(velocity > 0f)

        val carried = FloatArray(2)
        val cold = FloatArray(2)
        IslandMotion.stepSpring(position, velocity, 0f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, 1f / 120f, carried)
        IslandMotion.stepSpring(position, 0f, 0f, IslandMotion.WIDTH_OMEGA, IslandMotion.WIDTH_ZETA, 1f / 120f, cold)
        // A reversal keeps the outgoing velocity instead of restarting the spring from rest.
        assertTrue(carried[1] > cold[1])
    }
}
