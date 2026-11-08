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
    fun idleCurlNarrowsBeforeItFlattens() {
        assertTrue(IslandMotion.dormantWidthShare(0.5f) > IslandMotion.dormantHeightShare(0.5f))
        assertEquals(0f, IslandMotion.dormantHeightShare(0.4f), 0.001f)
        assertEquals(1f, IslandMotion.dormantWidthShare(1f), 0f)
        assertEquals(1f, IslandMotion.dormantHeightShare(1f), 0f)
    }
}
