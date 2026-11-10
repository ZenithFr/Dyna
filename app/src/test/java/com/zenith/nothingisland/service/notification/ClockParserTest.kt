package com.zenith.nothingisland.service.notification

import com.zenith.nothingisland.service.ClockKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockParserTest {

    private val nowWall = 1_800_000_000_000L
    private val nowElapsed = 500_000L

    private fun input(
        sortKey: String? = null,
        showChronometer: Boolean = false,
        countDown: Boolean = false,
        whenWallMs: Long = nowWall,
        postedAgoMs: Long = 0L,
    ) = ClockParser.Input(
        sortKey = sortKey,
        title = null,
        text = null,
        showChronometer = showChronometer,
        chronometerCountDown = countDown,
        whenWallMs = whenWallMs,
        postTimeWallMs = nowWall - postedAgoMs,
        nowWallMs = nowWall,
        nowElapsedMs = nowElapsed,
    )

    @Test
    fun googleClockRunningTimerEndsAfterItsRemainingTime() {
        // `⏳0:02:56` was posted 4 s ago, so the deadline is 3:00 of elapsed realtime from now.
        val decoded = ClockParser.decode(
            input(sortKey = "1|↺63|RUNNING|▶20:07:02.672|Σ0:03:00|Δ0:00:04|⏳0:02:56", postedAgoMs = 4_000L),
        )!!
        assertEquals(ClockKind.Timer, decoded.kind)
        assertTrue(decoded.running)
        // 2:56 left once the 4 s-old post is taken into account.
        assertEquals(nowElapsed - 4_000L + 176_000L, decoded.anchorElapsedMs)
        assertEquals(0L, decoded.frozenMs)
    }

    @Test
    fun googleClockPausedTimerFreezesAtItsRemainingTime() {
        val decoded = ClockParser.decode(
            input(sortKey = "1|↺63|PAUSED|▶20:07:02.672|Σ0:03:00|Δ0:00:04|⏳0:02:56", postedAgoMs = 60_000L),
        )!!
        assertEquals(ClockKind.Timer, decoded.kind)
        assertFalse(decoded.running)
        assertEquals(176_000L, decoded.frozenMs)
    }

    @Test
    fun googleClockStopwatchUsesItsElapsedTotal() {
        val decoded = ClockParser.decode(
            input(sortKey = "2|RUNNING|Σ0:12:34|Δ0:00:42", postedAgoMs = 10_000L),
        )!!
        assertEquals(ClockKind.Stopwatch, decoded.kind)
        assertTrue(decoded.running)
        // Elapsed 12:34 ten seconds ago means it started 12:44 ago.
        assertEquals(nowElapsed - 764_000L, decoded.anchorElapsedMs)
    }

    @Test
    fun totalAndElapsedWithoutTheTimerGlyphIsNotACountdown() {
        // A stopwatch also reports totals; only the timer's own ↺ marks a countdown.
        val decoded = ClockParser.decode(input(sortKey = "2|RUNNING|Σ0:03:00|Δ0:00:04"))!!
        assertEquals(ClockKind.Stopwatch, decoded.kind)
    }

    @Test
    fun platformCountdownChronometerUsesWhenAsItsDeadline() {
        val decoded = ClockParser.decode(
            input(showChronometer = true, countDown = true, whenWallMs = nowWall + 90_000L),
        )!!
        assertEquals(ClockKind.Timer, decoded.kind)
        assertEquals(nowElapsed + 90_000L, decoded.anchorElapsedMs)
    }

    @Test
    fun platformChronometerWithoutCountDownIsAStopwatch() {
        val decoded = ClockParser.decode(
            input(showChronometer = true, countDown = false, whenWallMs = nowWall - 30_000L),
        )!!
        assertEquals(ClockKind.Stopwatch, decoded.kind)
        assertEquals(nowElapsed - 30_000L, decoded.anchorElapsedMs)
    }

    @Test
    fun aNotificationWithNoClockStateIsNotDecoded() {
        assertNull(ClockParser.decode(input()))
    }

    @Test
    fun hourLongDurationsParse() {
        val decoded = ClockParser.decode(input(sortKey = "↺|RUNNING|⏳1:02:03"))!!
        assertEquals(nowElapsed + 3_723_000L, decoded.anchorElapsedMs)
    }

    @Test
    fun anExpiredTimerIsNotALiveClockCard() {
        // The Clock app keeps the notification up after it rings, with ⏳0:00:00 and EXPIRED.
        assertNull(
            ClockParser.decode(
                input(sortKey = "0 3|↺63|EXPIRED|▶20:34:56.993|Σ0:03:00|Δ0:03:00|⏳0:00:00", postedAgoMs = 1_000L),
            ),
        )
    }

    @Test
    fun parsesElapsedTextFromACustomView() {
        assertEquals(12_340L, ClockParser.parseElapsedText("00:12.34"))
        assertEquals(12_300L, ClockParser.parseElapsedText("0:12.3"))
        assertEquals(3_723_450L, ClockParser.parseElapsedText("1:02:03.45"))
        assertEquals(63_000L, ClockParser.parseElapsedText("1:03"))
        assertEquals(7_000L, ClockParser.parseElapsedText("  0:07  "))
    }

    @Test
    fun rejectsTextThatIsNotADuration() {
        assertNull(ClockParser.parseElapsedText(""))
        assertNull(ClockParser.parseElapsedText("Paused"))
        assertNull(ClockParser.parseElapsedText("12:345"))
        assertNull(ClockParser.parseElapsedText("Tap to open"))
    }
}
