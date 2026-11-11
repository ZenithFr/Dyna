package com.zenith.nothingisland.service.timer

/**
 * The countdown state machine, with every platform dependency behind a seam.
 *
 * It exists because the timer is the one feature where a silent failure costs the user real time:
 * the old implementation decided completion with a coroutine `delay()` on the main dispatcher,
 * which is **uptime** based (so it did not fire in deep sleep) and lived only in process memory (so
 * a process kill lost the timer). This class never schedules by itself — it asks
 * [TimerScheduler] to arm an `AlarmManager` alarm and [TimerStorePort] to persist a wall-clock
 * deadline, and it reports completion through [TimerSink] so the alert does not depend on the
 * island being visible.
 *
 * Pure Kotlin: no Android, no coroutines. Unit tests drive it with a fake clock.
 */
class TimerCore(
    private val clock: TimerClock,
    private val scheduler: TimerScheduler,
    private val store: TimerStorePort,
    private val sink: TimerSink,
) {

    companion object {
        const val DEFAULT_LABEL = "TIMER"

        /** Shown for the finished hold when the countdown reaches zero. */
        const val FINISHED_LABEL = "TIME'S UP"

        /** Longest accepted duration: 24 h. Rejects accidental overflow and `mm:ss` overflow. */
        const val MAX_DURATION_MS = 24L * 60L * 60L * 1000L

        /** A stored deadline older than this is abandoned rather than fired late. */
        const val STALE_AFTER_MS = 24L * 60L * 60L * 1000L
    }

    var label: String = DEFAULT_LABEL
        private set

    /** Wall-clock deadline. The durable half of the timer; survives process death. */
    var endWallMs: Long = 0L
        private set

    var pausedRemainingMs: Long = 0L
        private set

    var paused: Boolean = false
        private set

    var finished: Boolean = false
        private set

    /** A timer exists (running or paused). Cleared by [stop] and by a stale restore. */
    var active: Boolean = false
        private set

    /** False when the platform refused an exact alarm; the UI surfaces this as a warning. */
    val exactAlarmsAllowed: Boolean
        get() = scheduler.isExact()

    // ---------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------

    /** Starts (or replaces) the timer. Returns false for an out-of-range duration. */
    fun start(durationMs: Long, label: String = DEFAULT_LABEL): Boolean {
        if (durationMs !in 1L..MAX_DURATION_MS) return false
        this.label = label.trim().ifEmpty { DEFAULT_LABEL }
        paused = false
        finished = false
        active = true
        pausedRemainingMs = durationMs
        endWallMs = clock.wallMs() + durationMs
        persist()
        armAlarm()
        publish(durationMs)
        return true
    }

    /** Freezes the remaining time; the island keeps showing the paused countdown. */
    fun pause(): Boolean {
        if (!active || finished || paused) return false
        val remaining = remainingNow()
        if (remaining <= 0L) {
            finish(late = false)
            return true
        }
        pausedRemainingMs = remaining
        paused = true
        scheduler.cancel()
        persist()
        publish(remaining)
        return true
    }

    /** Continues from the frozen remaining time with a fresh deadline. */
    fun resume(): Boolean {
        if (!active || finished || !paused) return false
        if (pausedRemainingMs <= 0L) {
            finish(late = false)
            return true
        }
        paused = false
        endWallMs = clock.wallMs() + pausedRemainingMs
        persist()
        armAlarm()
        publish(pausedRemainingMs)
        return true
    }

    /** Cancels the timer entirely; the island falls back to media, else idle. */
    fun stop() {
        scheduler.cancel()
        store.clear()
        label = DEFAULT_LABEL
        endWallMs = 0L
        pausedRemainingMs = 0L
        paused = false
        finished = false
        active = false
        sink.publish(null)
    }

    /**
     * The 1 Hz UI tick. Returns the remaining time so the caller can decide whether to repaint;
     * completion (and therefore the alert) is decided here, not by the caller's delay.
     */
    fun tick(): Long {
        if (!active || finished) return 0L
        if (paused) return pausedRemainingMs
        val remaining = remainingNow()
        if (remaining <= 0L) {
            finish(late = false)
            return 0L
        }
        publish(remaining)
        return remaining
    }

    /**
     * The alarm fired. A fire that arrives early (inexact alarms can be batched, but some OEMs
     * deliver the wake-up before the deadline) re-arms instead of finishing early.
     */
    fun onAlarm() {
        if (!active || finished) return
        if (remainingNow() > 0L) {
            armAlarm()
            return
        }
        finish(late = false)
    }

    /**
     * Rehydrates from [TimerStorePort] after a process start, a service reconnect or a reboot.
     * Returns true when a timer was restored or fired.
     */
    fun restore(): Boolean {
        if (active) return false
        val record = store.load() ?: return false
        label = record.label.ifEmpty { DEFAULT_LABEL }
        if (record.paused) {
            active = true
            paused = true
            pausedRemainingMs = record.remainingMs.coerceAtLeast(0L)
            publish(pausedRemainingMs)
            return true
        }
        val remaining = record.endWallMs - clock.wallMs()
        if (remaining <= 0L) {
            // Late but not lost: the deadline passed while nothing was running.
            val lateBy = -remaining
            if (lateBy > STALE_AFTER_MS) {
                store.clear()
                return false
            }
            active = true
            pausedRemainingMs = 0L
            // Under 5 s late is just scheduling noise; a real gap means the alert missed the moment.
            finish(late = lateBy > 5_000L)
            return true
        }
        active = true
        endWallMs = record.endWallMs
        armAlarm()
        publish(remaining)
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    /** Remaining time from the wall deadline; frozen while paused/finished. */
    fun remainingNow(): Long {
        if (paused || finished) return pausedRemainingMs
        return (endWallMs - clock.wallMs()).coerceAtLeast(0L)
    }

    private fun armAlarm() {
        scheduler.arm(clock.elapsedMs() + remainingNow())
    }

    private fun persist() {
        store.save(
            TimerRecord(
                label = label,
                endWallMs = if (paused) 0L else endWallMs,
                remainingMs = if (paused) pausedRemainingMs else remainingNow(),
                paused = paused,
            ),
        )
    }

    private fun finish(late: Boolean) {
        scheduler.cancel()
        store.clear()
        paused = false
        finished = true
        pausedRemainingMs = 0L
        endWallMs = clock.wallMs()
        sink.publish(snapshot(0L))
        sink.alert(label, late)
    }

    private fun publish(remainingMs: Long) {
        sink.publish(snapshot(remainingMs.coerceAtLeast(0L)))
    }

    private fun snapshot(remainingMs: Long) = TimerSnapshot(
        label = if (finished) FINISHED_LABEL else label,
        remainingMs = remainingMs,
        isRunning = active && !paused && !finished,
        endTimeElapsedMs = if (paused || finished) 0L else clock.elapsedMs() + remainingMs,
    )
}

/** Uptime + wall clock reads. Split so the store can outlive a reboot (wall) and the ticker cannot drift (uptime). */
interface TimerClock {
    fun elapsedMs(): Long
    fun wallMs(): Long
}

/** The alarm seam: `AlarmManager` in production, a fake in tests. */
interface TimerScheduler {
    /** True when the platform will deliver close to the requested instant. */
    fun isExact(): Boolean

    /** Arms a wake-up for [atElapsedMs] (`ELAPSED_REALTIME_WAKEUP` semantics). */
    fun arm(atElapsedMs: Long)

    fun cancel()
}

/** Persistence seam: a small `SharedPreferences` file in production. */
interface TimerStorePort {
    fun save(record: TimerRecord)
    fun load(): TimerRecord?
    fun clear()
}

/** Output seam: the island publication and the finished alert. */
interface TimerSink {
    fun publish(snapshot: TimerSnapshot?)
    fun alert(label: String, late: Boolean)
}

/** The durable half of a timer: a wall-clock deadline plus the paused remainder. */
data class TimerRecord(
    val label: String,
    val endWallMs: Long,
    val remainingMs: Long,
    val paused: Boolean,
)

/** What the island draws. Mirrors `IslandContent.Timer` without depending on it. */
data class TimerSnapshot(
    val label: String,
    val remainingMs: Long,
    val isRunning: Boolean,
    val endTimeElapsedMs: Long,
)
