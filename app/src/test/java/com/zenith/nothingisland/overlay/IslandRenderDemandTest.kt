package com.zenith.nothingisland.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandRenderDemandTest {
    @Test
    fun idleDetachedIslandRequestsNoFrames() {
        assertEquals(
            0f,
            IslandRenderDemand.motionRateHz(
                active = false,
                ambientOnly = false,
                motionAllowed = true,
                ambientFrameRateHz = 30,
                highQualityMotion = true,
            ),
            0f,
        )
        assertTrue(IslandRenderDemand.shouldDetachIdle(hasContent = false, dormantSettled = true))
        assertFalse(IslandRenderDemand.shouldDetachIdle(hasContent = true, dormantSettled = true))
        assertFalse(IslandRenderDemand.shouldDetachIdle(hasContent = false, dormantSettled = false))
    }

    @Test
    fun ambientLoopsAreOptInAndNeverRunWhileDormant() {
        assertFalse(
            IslandRenderDemand.ambientLoopsEnabled(motionAllowed = true, userEnabled = false, dormant = false),
        )
        assertFalse(
            IslandRenderDemand.ambientLoopsEnabled(motionAllowed = true, userEnabled = true, dormant = true),
        )
        assertFalse(
            IslandRenderDemand.ambientLoopsEnabled(motionAllowed = false, userEnabled = true, dormant = false),
        )
        assertTrue(
            IslandRenderDemand.ambientLoopsEnabled(motionAllowed = true, userEnabled = true, dormant = false),
        )
    }

    @Test
    fun morphsKeepHighRefreshButAmbientLoopsUseTheirOwnRate() {
        assertEquals(
            120f,
            IslandRenderDemand.motionRateHz(
                active = true,
                ambientOnly = false,
                motionAllowed = true,
                ambientFrameRateHz = 30,
                highQualityMotion = true,
            ),
            0f,
        )
        assertEquals(
            30f,
            IslandRenderDemand.motionRateHz(
                active = true,
                ambientOnly = true,
                motionAllowed = true,
                ambientFrameRateHz = 30,
                highQualityMotion = true,
            ),
            0f,
        )
        assertEquals(
            0f,
            IslandRenderDemand.motionRateHz(
                active = true,
                ambientOnly = true,
                motionAllowed = false,
                ambientFrameRateHz = 30,
                highQualityMotion = true,
            ),
            0f,
        )
    }
}
