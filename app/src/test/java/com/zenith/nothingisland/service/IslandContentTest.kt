package com.zenith.nothingisland.service

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandContentTest {
    private fun media(playing: Boolean = true, speed: Float = 1f) = IslandContent.Media(
        title = "Track", artist = "Artist", albumArt = null, isPlaying = playing,
        positionMs = 1_000L, durationMs = 10_000L, positionUpdatedAtElapsedMs = 1_000L, speed = speed,
    )

    @Test
    fun pausedMediaDoesNotAdvance() {
        assertEquals(1_000L, media(playing = false).estimatePositionMs(5_000L))
    }

    @Test
    fun playingMediaExtrapolatesAndClamps() {
        assertEquals(3_000L, media(speed = 2f).estimatePositionMs(2_000L))
        assertEquals(10_000L, media().estimatePositionMs(99_000L))
        assertEquals(1_000L, media().estimatePositionMs(0L))
    }

    @Test
    fun invalidSpeedFallsBackToRealTime() {
        assertEquals(3_000L, media(speed = Float.NaN).estimatePositionMs(3_000L))
        assertEquals(3_000L, media(speed = -1f).estimatePositionMs(3_000L))
    }

    @Test
    fun timerRoundsUpButNeverShowsNegativeTime() {
        assertEquals("00:01", IslandContent.Timer("TIMER", 1L, true, 0L).displayTime)
        assertEquals("01:00", IslandContent.Timer("TIMER", 59_001L, true, 0L).displayTime)
        assertEquals("00:00", IslandContent.Timer("TIMER", -1L, false, 0L).displayTime)
    }

    @Test
    fun timerFormatsHoursWhenSixtyMinutesOrMore() {
        assertEquals("1:30:00", IslandContent.Timer("TIMER", 5_400_000L, true, 0L).displayTime)
        assertEquals("1:00:00", IslandContent.Timer("TIMER", 3_600_000L, true, 0L).displayTime)
        assertEquals("1:00:00", IslandContent.Timer("TIMER", 3_599_001L, true, 0L).displayTime)
        assertEquals("59:59", IslandContent.Timer("TIMER", 3_599_000L, true, 0L).displayTime)
        assertEquals("01:00", IslandContent.Timer("TIMER", 59_999L, true, 0L).displayTime)
    }
}
