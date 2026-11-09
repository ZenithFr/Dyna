package com.zenith.nothingisland.overlay

import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandEvent
import com.zenith.nothingisland.service.IslandSource
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

    @Test
    fun compactMediaPositionTicksAreNotRedraws() {
        val before = IslandEvent(IslandSource.Media, media(positionMs = 1_000L, stamp = 10_000L))
        val tick = IslandEvent(IslandSource.Media, media(positionMs = 2_000L, stamp = 11_000L))
        assertTrue(IslandRenderDemand.isMediaPositionTick(before, tick))
        // The expanded progress row is drawn from the stamp, so it still needs the emission.
        assertFalse(
            IslandRenderDemand.isMediaPositionTick(before.copy(expanded = true), tick.copy(expanded = true)),
        )
        assertFalse(IslandRenderDemand.isMediaPositionTick(null, tick))
    }

    @Test
    fun trackChangesAndPlayStateStillRedraw() {
        val before = IslandEvent(IslandSource.Media, media(positionMs = 1_000L, stamp = 10_000L))
        val nextTrack = IslandEvent(
            IslandSource.Media,
            media(positionMs = 1_000L, stamp = 10_000L).copy(title = "Another track"),
        )
        val paused = IslandEvent(
            IslandSource.Media,
            media(positionMs = 1_000L, stamp = 10_000L).copy(isPlaying = false),
        )
        assertFalse(IslandRenderDemand.isMediaPositionTick(before, nextTrack))
        assertFalse(IslandRenderDemand.isMediaPositionTick(before, paused))
    }

    private fun media(positionMs: Long, stamp: Long) = IslandContent.Media(
        title = "Track",
        artist = "Artist",
        albumArt = null,
        isPlaying = true,
        positionMs = positionMs,
        durationMs = 200_000L,
        positionUpdatedAtElapsedMs = stamp,
        speed = 1f,
    )
}
