package com.zenith.nothingisland.service.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timer is the one feature whose failure is silent (a missed deadline costs the user real
 * time), so its state machine is tested without Android: fake clock, fake alarm, fake store.
 */
class TimerCoreTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler()
    private val store = FakeStore()
    private val sink = FakeSink()
    private val core = TimerCore(clock, scheduler, store, sink)

    @Test
    fun `start arms an alarm, persists and publishes`() {
        assertTrue(core.start(60_000L, "Tea"))

        assertEquals(1, scheduler.arms)
        assertEquals(clock.elapsed + 60_000L, scheduler.armedAt)
        assertEquals("Tea", store.record?.label)
        assertTrue(store.record?.paused == false)
        assertEquals(60_000L, sink.last?.remainingMs)
        assertTrue(sink.last?.isRunning == true)
    }

    @Test
    fun `reaching zero finishes once and alerts`() {
        core.start(60_000L)

        clock.advance(59_000L)
        assertEquals(1_000L, core.tick())

        clock.advance(1_000L)
        assertEquals(0L, core.tick())

        assertTrue(core.finished)
        assertEquals(1, sink.alerts.size)
        assertEquals(TimerCore.FINISHED_LABEL, sink.last?.label)
        assertNull("the deadline must not survive completion", store.record)
        assertEquals(-1L, scheduler.armedAt)

        // A second tick (or a late alarm) must not alert twice.
        core.tick()
        core.onAlarm()
        assertEquals(1, sink.alerts.size)
    }

    @Test
    fun `out-of-range durations are rejected without touching the alarm or the store`() {
        assertFalse(core.start(0L))
        assertFalse(core.start(TimerCore.MAX_DURATION_MS + 1L))

        assertEquals(0, scheduler.arms)
        assertNull(store.record)
        assertTrue(sink.snapshots.isEmpty())
        assertFalse(core.active)
    }

    @Test
    fun `pause cancels the alarm and resume re-arms from the frozen remainder`() {
        core.start(90_000L)
        clock.advance(30_000L)
        assertTrue(core.pause())

        assertTrue(core.paused)
        assertEquals(60_000L, core.remainingNow())
        assertEquals(-1L, scheduler.armedAt)
        assertTrue(store.record?.paused == true)

        clock.advance(5_000L)
        assertEquals("paused time must not burn the countdown", 60_000L, core.remainingNow())

        assertTrue(core.resume())
        assertEquals(clock.elapsed + 60_000L, scheduler.armedAt)
        assertFalse(core.paused)
    }

    @Test
    fun `an early alarm re-arms instead of finishing early`() {
        core.start(60_000L)
        clock.advance(10_000L)

        core.onAlarm()

        assertFalse(core.finished)
        assertEquals(2, scheduler.arms)
    }

    @Test
    fun `restore after a passed deadline fires exactly once and marks it late`() {
        store.record = TimerRecord(
            label = "Pasta",
            endWallMs = clock.wall - 120_000L,
            remainingMs = 0L,
            paused = false,
        )

        assertTrue(core.restore())
        assertTrue(core.finished)
        assertEquals(1, sink.alerts.size)
        assertTrue("a two-minute gap is late", sink.alerts.single().second)
        assertNull(store.record)

        assertFalse("restoring again is a no-op", core.restore())
        assertEquals(1, sink.alerts.size)
    }

    @Test
    fun `restore in the future re-arms with the correct remainder`() {
        store.record = TimerRecord(
            label = "Roast",
            endWallMs = clock.wall + 45_000L,
            remainingMs = 45_000L,
            paused = false,
        )

        assertTrue(core.restore())
        assertEquals(45_000L, core.remainingNow())
        assertEquals(clock.elapsed + 45_000L, scheduler.armedAt)
        assertEquals(45_000L, sink.last?.remainingMs)
        assertFalse(core.finished)
    }

    @Test
    fun `a stale record is discarded instead of firing days late`() {
        store.record = TimerRecord(
            label = "Old",
            endWallMs = clock.wall - (TimerCore.STALE_AFTER_MS + 1_000L),
            remainingMs = 0L,
            paused = false,
        )

        assertFalse(core.restore())
        assertTrue(sink.alerts.isEmpty())
        assertNull(store.record)
    }

    @Test
    fun `paused timers survive a restore without an alarm`() {
        core.start(120_000L)
        clock.advance(20_000L)
        core.pause()
        scheduler.arms = 0

        val restored = TimerCore(clock, scheduler, store, sink)
        assertTrue(restored.restore())
        assertTrue(restored.paused)
        assertEquals(100_000L, restored.remainingNow())
        assertEquals("a paused timer must not arm an alarm", 0, scheduler.arms)
    }

    @Test
    fun `inexact alarms are reported to the UI`() {
        scheduler.exact = false
        assertFalse(core.exactAlarmsAllowed)

        scheduler.exact = true
        assertTrue(core.exactAlarmsAllowed)
    }

    @Test
    fun `stop clears the alarm, the store and the island slot`() {
        core.start(30_000L)
        core.stop()

        assertNull(store.record)
        assertEquals(-1L, scheduler.armedAt)
        assertTrue("stop must cancel the pending alarm", scheduler.cancels >= 1)
        assertNull(sink.last)
        assertFalse(core.active)
    }

    // ---------------------------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------------------------

    private class FakeClock(
        var elapsed: Long = 1_000_000L,
        var wall: Long = 1_700_000_000_000L,
    ) : TimerClock {
        override fun elapsedMs(): Long = elapsed
        override fun wallMs(): Long = wall

        fun advance(ms: Long) {
            elapsed += ms
            wall += ms
        }
    }

    private class FakeScheduler : TimerScheduler {
        var exact = true
        var armedAt = -1L
        var arms = 0
        var cancels = 0

        override fun isExact(): Boolean = exact

        override fun arm(atElapsedMs: Long) {
            armedAt = atElapsedMs
            arms++
        }

        override fun cancel() {
            armedAt = -1L
            cancels++
        }
    }

    private class FakeStore : TimerStorePort {
        var record: TimerRecord? = null

        override fun save(record: TimerRecord) {
            this.record = record
        }

        override fun load(): TimerRecord? = record

        override fun clear() {
            record = null
        }
    }

    private class FakeSink : TimerSink {
        val snapshots = mutableListOf<TimerSnapshot?>()
        val alerts = mutableListOf<Pair<String, Boolean>>()

        val last: TimerSnapshot?
            get() = snapshots.lastOrNull()

        override fun publish(snapshot: TimerSnapshot?) {
            snapshots += snapshot
        }

        override fun alert(label: String, late: Boolean) {
            alerts += label to late
        }
    }
}
