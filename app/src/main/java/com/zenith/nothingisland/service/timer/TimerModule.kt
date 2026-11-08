package com.zenith.nothingisland.service.timer

import android.os.SystemClock
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandSource
import com.zenith.nothingisland.service.IslandStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The countdown controller. One timer at a time, owned by the app process (it keeps running while
 * the Nothing Island app is in the background as long as the process lives).
 *
 * Time is derived from [SystemClock.elapsedRealtime] and a stored **end timestamp**, so ticks never
 * accumulate drift: each publish recomputes `end - now` instead of subtracting a fixed interval.
 * The ticker only exists while the timer runs and the island is visible. Screen-off/doze keeps the
 * end timestamp and at most one completion wake; paused, finished and idle states hold no ticker.
 *
 * [IslandStateHolder] stays the single source of truth for the overlay ([IslandStateHolder.postTimer]
 * on every change). [state] exists so Compose can show the same live value without touching the
 * overlay singleton.
 */
object TimerModule {

    const val DEFAULT_LABEL = "TIMER"

    /** Shown for [FINISHED_HOLD_MS] when the countdown reaches zero. */
    const val FINISHED_LABEL = "TIME'S UP"

    /** How long the finished ("00:00") state stays on the island before it clears itself. */
    const val FINISHED_HOLD_MS = 4_000L

    /** Safety cap on waiting for a covering alert to expire before the finished hold starts. */
    private const val FINISHED_VISIBILITY_TIMEOUT_MS = 30_000L

    /** Longest accepted duration: 24 h, keeps `mm:ss` sane and rejects accidental overflow. */
    private const val MAX_DURATION_MS = 24L * 60L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<IslandContent.Timer?>(null)

    /** Live timer for the Compose UI; null when no timer exists. */
    val state: StateFlow<IslandContent.Timer?> = _state.asStateFlow()

    private var ticker: Job? = null
    private var clearJob: Job? = null
    private var hiddenCompletionJob: Job? = null
    private val visible = MutableStateFlow(true)

    private var label = DEFAULT_LABEL
    private var endTimeElapsedMs = 0L
    private var pausedRemainingMs = 0L
    private var paused = false
    private var finished = false

    /**
     * Screen-off/doze does not need a 1 Hz UI tick. Keep the countdown authoritative, cancel the
     * ticker, and wake once at the end timestamp; returning to the island resumes boundary ticks.
     */
    @Synchronized
    fun setIslandVisible(value: Boolean) {
        if (visible.value == value) return
        visible.value = value
        if (_state.value == null || paused || finished) return
        if (value) {
            hiddenCompletionJob?.cancel()
            hiddenCompletionJob = null
            startTicker()
        } else {
            ticker?.cancel()
            ticker = null
            scheduleHiddenCompletion()
        }
    }

    /** Starts (or replaces) the timer. [label] is shown in the expanded pill. */
    @Synchronized
    fun start(durationMs: Long, label: String = DEFAULT_LABEL) {
        require(durationMs in 1L..MAX_DURATION_MS) { "Timer duration must be between 1 ms and 24 h" }
        val duration = durationMs
        cancelJobs()
        this.label = label.trim().ifEmpty { DEFAULT_LABEL }
        paused = false
        finished = false
        pausedRemainingMs = duration
        endTimeElapsedMs = SystemClock.elapsedRealtime() + duration
        publish(duration)
        startTicker()
    }

    /** Freezes the remaining time. The island keeps showing the paused countdown. */
    @Synchronized
    fun pause() {
        if (_state.value == null || finished || paused) return
        val remaining = remainingNow()
        if (remaining <= 0L) {
            finish()
            return
        }
        pausedRemainingMs = remaining
        paused = true
        ticker?.cancel()
        ticker = null
        hiddenCompletionJob?.cancel()
        hiddenCompletionJob = null
        publish(remaining)
    }

    /** Continues from the frozen remaining time with a fresh end timestamp. */
    @Synchronized
    fun resume() {
        if (_state.value == null || finished || !paused) return
        if (pausedRemainingMs <= 0L) {
            finish()
            return
        }
        paused = false
        endTimeElapsedMs = SystemClock.elapsedRealtime() + pausedRemainingMs
        publish(pausedRemainingMs)
        startTicker()
    }

    @Synchronized
    fun togglePause() {
        if (_state.value == null || finished) return
        if (paused) resume() else pause()
    }

    /** Cancels the timer entirely; the holder falls back to media, else idle. */
    @Synchronized
    fun stop() {
        cancelJobs()
        label = DEFAULT_LABEL
        endTimeElapsedMs = 0L
        pausedRemainingMs = 0L
        paused = false
        finished = false
        if (_state.value != null) _state.value = null
        IslandStateHolder.clearTimer()
    }

    private fun cancelJobs() {
        ticker?.cancel()
        ticker = null
        hiddenCompletionJob?.cancel()
        hiddenCompletionJob = null
        clearJob?.cancel()
        clearJob = null
    }

    /** Remaining time from the stored end stamp; frozen while paused/finished. */
    private fun remainingNow(): Long {
        if (paused || finished) return pausedRemainingMs
        return (endTimeElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    /**
     * Publishes once per whole-second boundary. Delaying by `remaining % 1000` keeps the digits
     * flipping on the boundary instead of drifting a few ms later every tick.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = null
        if (!visible.value) {
            scheduleHiddenCompletion()
            return
        }
        hiddenCompletionJob?.cancel()
        hiddenCompletionJob = null
        ticker = scope.launch {
            while (isActive) {
                val remaining = remainingNow()
                if (remaining <= 0L) {
                    finish()
                    break
                }
                publish(remaining)
                delay(untilNextSecond(remaining))
            }
        }
    }

    private fun scheduleHiddenCompletion() {
        hiddenCompletionJob?.cancel()
        val remaining = remainingNow()
        if (remaining <= 0L) {
            hiddenCompletionJob = null
            finish()
            return
        }
        hiddenCompletionJob = scope.launch {
            delay(remaining)
            hiddenCompletionJob = null
            finish()
        }
    }

    private fun untilNextSecond(remainingMs: Long): Long {
        val toBoundary = remainingMs % 1_000L
        return if (toBoundary == 0L) 1_000L else toBoundary
    }

    /**
     * Reaches zero: show 00:00 / "TIME'S UP", then clear after [FINISHED_HOLD_MS]. The clear job is
     * tracked so [stop] and teardown can cancel it.
     *
     * A transient alert (notification / charging) outranks the timer, so it may be covering the
     * pill at the exact moment the countdown ends. The hold therefore starts only once the
     * finished timer is actually the visible event, so completion is never cleared unseen. Alerts
     * expire on their own after ~4 s; the timeout is a safety net, not a polling loop. A null
     * holder publication is not treated as a visible timer (it can mean the overlay is gone).
     */
    @Synchronized
    private fun finish() {
        ticker?.cancel()
        ticker = null
        paused = false
        finished = true
        pausedRemainingMs = 0L
        endTimeElapsedMs = SystemClock.elapsedRealtime()
        publish(0L)
        clearJob?.cancel()
        clearJob = scope.launch {
            // If the countdown ended while the panel was off, keep 00:00 until the user can see it.
            visible.first { it }
            withTimeoutOrNull(FINISHED_VISIBILITY_TIMEOUT_MS) {
                IslandStateHolder.state.first { it?.source == IslandSource.Timer }
            }
            delay(FINISHED_HOLD_MS)
            stop()
        }
    }

    private fun publish(remainingMs: Long) {
        val snapshot = IslandContent.Timer(
            label = if (finished) FINISHED_LABEL else label,
            remainingMs = remainingMs.coerceAtLeast(0L),
            isRunning = !paused && !finished,
            endTimeElapsedMs = if (paused || finished) 0L else endTimeElapsedMs,
        )
        _state.value = snapshot
        IslandStateHolder.postTimer(snapshot)
    }
}
