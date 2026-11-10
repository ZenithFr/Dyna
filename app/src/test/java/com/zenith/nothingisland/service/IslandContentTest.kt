package com.zenith.nothingisland.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun clock(
        kind: ClockKind,
        running: Boolean = true,
        anchorElapsedMs: Long = 10_000L,
        frozenMs: Long = 0L,
    ) = IslandContent.ClockActivity(
        key = "0|com.google.android.deskclock|1|null|1",
        packageName = "com.google.android.deskclock",
        appLabel = "Clock",
        icon = null,
        kind = kind,
        isRunning = running,
        anchorElapsedMs = anchorElapsedMs,
        frozenMs = frozenMs,
        timeText = "",
        actions = emptyList(),
        contentIntent = null,
    )

    @Test
    fun runningCountdownCountsDownAndRoundsUp() {
        val content = clock(ClockKind.Timer, anchorElapsedMs = 10_000L)
        assertEquals("00:10", content.timeTextAt(0L))
        assertEquals("00:01", content.timeTextAt(9_001L))
        assertEquals("00:00", content.timeTextAt(11_000L))
        assertTrue(content.isFinished(10_000L))
    }

    @Test
    fun runningStopwatchCountsUpAndTruncates() {
        val content = clock(ClockKind.Stopwatch, anchorElapsedMs = 10_000L)
        assertEquals("00:00", content.timeTextAt(10_999L))
        assertEquals("01:01", content.timeTextAt(71_000L))
        assertEquals("1:00:00", content.timeTextAt(10_000L + 3_600_000L))
        assertFalse(content.isFinished(99_999_999L))
    }

    @Test
    fun pausedClockKeepsItsFrozenTime() {
        assertEquals("02:56", clock(ClockKind.Timer, running = false, frozenMs = 176_000L).timeTextAt(999_999L))
        assertEquals("12:34", clock(ClockKind.Stopwatch, running = false, frozenMs = 754_000L).timeTextAt(999_999L))
    }
}
