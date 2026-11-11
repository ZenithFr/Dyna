package com.zenith.nothingisland.overlay

import com.zenith.nothingisland.service.ClockKind
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

    @Test
    fun timerTicksAreRepaintsOnlyInBothStates() {
        val before = IslandEvent(IslandSource.Timer, timer(remainingMs = 60_000L))
        val tick = IslandEvent(IslandSource.Timer, timer(remainingMs = 59_000L))
        assertTrue(IslandRenderDemand.isTickOnly(before, tick))
        // The compact pill draws the countdown too, so the expanded state is not special.
        assertTrue(IslandRenderDemand.isTickOnly(before.copy(expanded = true), tick.copy(expanded = true)))
        // The deadline is derived from the remaining time, so it never makes a tick structural.
        val shiftedDeadline = IslandEvent(IslandSource.Timer, timer(remainingMs = 59_000L, endTimeMs = 7_000_000L))
        assertTrue(IslandRenderDemand.isTickOnly(before, shiftedDeadline))
        // Expanding is a morph, not a tick.
        assertFalse(IslandRenderDemand.isTickOnly(before, tick.copy(expanded = true)))
    }

    @Test
    fun clockTextTicksAreRepaintsOnly() {
        val before = IslandEvent(IslandSource.Clock, clock("00:10"))
        val tick = IslandEvent(IslandSource.Clock, clock("00:09"))
        assertTrue(IslandRenderDemand.isTickOnly(before, tick))
        assertTrue(IslandRenderDemand.isTickOnly(before.copy(expanded = true), tick.copy(expanded = true)))
        // A different activity is a different card.
        assertFalse(
            IslandRenderDemand.isTickOnly(before, IslandEvent(IslandSource.Clock, clock("00:09").copy(key = "other"))),
        )
        assertFalse(IslandRenderDemand.isTickOnly(before, IslandEvent(IslandSource.Notification, clock("00:09"))))
    }

    @Test
    fun mediaPositionTicksAreTicksOnlyWhenExpanded() {
        val before = IslandEvent(IslandSource.Media, media(positionMs = 1_000L, stamp = 10_000L))
        val tick = IslandEvent(IslandSource.Media, media(positionMs = 2_000L, stamp = 11_000L))
        // Compact draws no position at all; isMediaPositionTick no-ops that case.
        assertFalse(IslandRenderDemand.isTickOnly(before, tick))
        assertTrue(IslandRenderDemand.isTickOnly(before.copy(expanded = true), tick.copy(expanded = true)))
    }

    @Test
    fun pauseResumeAndRelabelStillRedraw() {
        val timer = IslandEvent(IslandSource.Timer, timer(remainingMs = 60_000L))
        assertFalse(
            IslandRenderDemand.isTickOnly(timer, IslandEvent(IslandSource.Timer, timer(60_000L).copy(isRunning = false))),
        )
        // Pause/resume needs new controls and a structural announcement.
        val clockBefore = IslandEvent(IslandSource.Clock, clock("00:10"))
        assertFalse(
            IslandRenderDemand.isTickOnly(clockBefore, IslandEvent(IslandSource.Clock, clock("00:10").copy(isRunning = false))),
        )
        assertFalse(
            IslandRenderDemand.isTickOnly(timer, IslandEvent(IslandSource.Timer, timer(60_000L).copy(label = "Pasta"))),
        )
        // A new track is a new card even if only the position moved as well.
        val mediaBefore = IslandEvent(IslandSource.Media, media(1_000L, 10_000L), expanded = true)
        val nextTrack = IslandEvent(IslandSource.Media, media(2_000L, 11_000L).copy(title = "Another track"), expanded = true)
        assertFalse(IslandRenderDemand.isTickOnly(mediaBefore, nextTrack))
    }

    @Test
    fun nullTransitionsAreNeverTicks() {
        val timer = IslandEvent(IslandSource.Timer, timer(remainingMs = 60_000L))
        assertFalse(IslandRenderDemand.isTickOnly(null, timer))
        assertFalse(IslandRenderDemand.isTickOnly(timer, null))
        assertFalse(IslandRenderDemand.isTickOnly(null, null))
    }

    private fun timer(remainingMs: Long, endTimeMs: Long = 6_000_000L) = IslandContent.Timer(
        label = "Timer",
        remainingMs = remainingMs,
        isRunning = true,
        endTimeElapsedMs = endTimeMs,
    )

    private fun clock(timeText: String) = IslandContent.ClockActivity(
        key = "clock-key",
        packageName = "com.android.deskclock",
        appLabel = "Clock",
        icon = null,
        kind = ClockKind.Timer,
        isRunning = true,
        anchorElapsedMs = 5_000_000L,
        frozenMs = 0L,
        timeText = timeText,
        actions = emptyList(),
        contentIntent = null,
    )

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
