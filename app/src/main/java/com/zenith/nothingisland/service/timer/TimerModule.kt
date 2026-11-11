package com.zenith.nothingisland.service.timer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
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
 * The countdown controller. One timer at a time, owned by the app process.
 *
 * The state machine lives in [TimerCore] (pure, unit-tested). This object is the Android shell:
 * it owns the 1 Hz UI ticker, publishes to [IslandStateHolder], and wires the real clock, the
 * `AlarmManager` and the `SharedPreferences` store.
 *
 * Completion is decided by an alarm, not by a coroutine delay: `delay()` on the main dispatcher
 * counts uptime, so it does not advance in deep sleep, and it dies with the process. The alarm
 * fires [TimerAlarmReceiver] and the durable deadline lives in [TimerStore], so a locked phone, a
 * killed process or a reboot all still alert the user.
 */
object TimerModule {

    const val DEFAULT_LABEL = TimerCore.DEFAULT_LABEL

    /** Shown for [FINISHED_HOLD_MS] when the countdown reaches zero. */
    const val FINISHED_LABEL = TimerCore.FINISHED_LABEL

    /** How long the finished ("00:00") state stays on the island before it clears itself. */
    const val FINISHED_HOLD_MS = 4_000L

    /** Safety cap on waiting for a covering alert to expire before the finished hold starts. */
    private const val FINISHED_VISIBILITY_TIMEOUT_MS = 30_000L

    private const val TAG = "TimerModule"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<IslandContent.Timer?>(null)

    /** Live timer for the Compose UI; null when no timer exists. */
    val state: StateFlow<IslandContent.Timer?> = _state.asStateFlow()

    private var core: TimerCore? = null
    private var appContext: Context? = null
    private var ticker: Job? = null
    private var clearJob: Job? = null
    private val visible = MutableStateFlow(true)

    /** True when the platform will deliver the alarm close to the deadline. */
    val exactAlarmsAllowed: Boolean
        get() = core?.exactAlarmsAllowed ?: true

    /**
     * Binds the Android implementations. Safe to call from every entry point (activity, service,
     * receivers): the first call wins and restores a timer that outlived the process.
     */
    @Synchronized
    fun attach(context: Context) {
        if (core != null) return
        val ctx = context.applicationContext
        appContext = ctx
        core = TimerCore(
            clock = SystemTimerClock,
            scheduler = AlarmTimerScheduler(ctx),
            store = TimerStore(ctx),
            sink = object : TimerSink {
                override fun publish(snapshot: TimerSnapshot?) {
                    val content = snapshot?.let {
                        IslandContent.Timer(
                            label = it.label,
                            remainingMs = it.remainingMs,
                            isRunning = it.isRunning,
                            endTimeElapsedMs = it.endTimeElapsedMs,
                        )
                    }
                    _state.value = content
                    if (content == null) {
                        IslandStateHolder.clearTimer()
                    } else {
                        IslandStateHolder.postTimer(content)
                    }
                }

                override fun alert(label: String, late: Boolean) {
                    // A receiver has ~10 s; reading two settings with a 1 s ceiling is safe on this
                    // thread and keeps the alert independent of whether the island is attached.
                    TimerAlertNotifier.show(ctx, label, late)
                    scheduleFinishedClear()
                    updateBootReceiver(enabled = false)
                }
            },
        )
        restore()
    }

    /**
     * Rehydrates the timer after a process start, service reconnect or reboot. Fires immediately
     * when the deadline passed while nothing was running, so a timer is late but never lost.
     */
    @Synchronized
    fun restore(): Boolean {
        val core = core ?: return false
        val restored = core.restore()
        if (restored && core.active && !core.finished && !core.paused) {
            updateBootReceiver(enabled = true)
            startTicker()
        }
        if (core.active && core.paused) updateBootReceiver(enabled = true)
        if (restored) Log.i(TAG, "Restored timer (paused=${core.paused}, finished=${core.finished})")
        return restored
    }

    /**
     * Screen-off/doze does not need a 1 Hz UI tick: the alarm owns completion now, so the ticker is
     * only cancelled. Returning to the island resumes boundary ticks.
     */
    @Synchronized
    fun setIslandVisible(value: Boolean) {
        if (visible.value == value) return
        visible.value = value
        if (value) {
            startTicker()
        } else {
            ticker?.cancel()
            ticker = null
        }
    }

    /** Starts (or replaces) the timer. [label] is shown in the expanded pill. */
    @Synchronized
    fun start(durationMs: Long, label: String = DEFAULT_LABEL): Boolean {
        val core = core ?: return false
        cancelJobs()
        TimerAlertNotifier.cancel(appContext ?: return false)
        val started = core.start(durationMs, label)
        if (!started) {
            Log.w(TAG, "Rejected timer duration ${durationMs}ms")
            return false
        }
        updateBootReceiver(enabled = true)
        startTicker()
        return true
    }

    /** Freezes the remaining time. The island keeps showing the paused countdown. */
    @Synchronized
    fun pause() {
        val core = core ?: return
        if (!core.pause()) return
        ticker?.cancel()
        ticker = null
    }

    /** Continues from the frozen remaining time with a fresh deadline. */
    @Synchronized
    fun resume() {
        val core = core ?: return
        if (!core.resume()) return
        updateBootReceiver(enabled = true)
        startTicker()
    }

    @Synchronized
    fun togglePause() {
        val core = core ?: return
        if (core.paused) resume() else pause()
    }

    /** Cancels the timer entirely; the holder falls back to media, else idle. */
    @Synchronized
    fun stop() {
        cancelJobs()
        val core = core
        core?.stop()
        _state.value = null
        IslandStateHolder.clearTimer()
        appContext?.let {
            TimerAlertNotifier.cancel(it)
            updateBootReceiver(enabled = false)
        }
    }

    /** The alarm fired (fresh process or a running one). */
    @Synchronized
    fun onAlarmFired(context: Context) {
        attach(context)
        val core = core ?: return
        Log.i(TAG, "Timer alarm fired (remaining=${core.remainingNow()}ms)")
        core.onAlarm()
    }

    /** BOOT_COMPLETED: re-arm a stored timer, or retire the receiver when there is none. */
    @Synchronized
    fun onBootCompleted(context: Context) {
        attach(context)
        val core = core ?: return
        if (!core.active) {
            updateBootReceiver(enabled = false)
            return
        }
        restore()
        startTicker()
    }

    /**
     * The accessibility service is going away. A rebind must not wipe a countdown, so the timer,
     * its store and its alarm survive; only the UI ticker stops. [stop] is the user's cancel.
     */
    @Synchronized
    fun onServiceTornDown() {
        ticker?.cancel()
        ticker = null
        clearJob?.cancel()
        clearJob = null
    }

    // ---------------------------------------------------------------------------------------
    // Ticker and finished hold
    // ---------------------------------------------------------------------------------------

    private fun cancelJobs() {
        ticker?.cancel()
        ticker = null
        clearJob?.cancel()
        clearJob = null
    }

    /**
     * Publishes once per whole-second boundary. Delaying by `remaining % 1000` keeps the digits
     * flipping on the boundary instead of drifting a few ms later every tick.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = null
        val core = core ?: return
        if (!core.active || core.paused || core.finished || !visible.value) return
        ticker = scope.launch {
            while (isActive) {
                val remaining = core.tick()
                if (core.finished) break
                delay(untilNextSecond(remaining))
            }
        }
    }

    private fun untilNextSecond(remainingMs: Long): Long {
        val toBoundary = remainingMs % 1_000L
        return if (toBoundary == 0L) 1_000L else toBoundary
    }

    /**
     * Keeps the finished ("00:00") card visible for [FINISHED_HOLD_MS] once it is actually the
     * visible event, then clears it. The alert itself has already fired by now; this is only the
     * visual, so the system notification is deliberately **not** cancelled here — the user may not
     * have looked at the phone yet.
     */
    private fun scheduleFinishedClear() {
        clearJob?.cancel()
        clearJob = scope.launch {
            // If the countdown ended while the panel was off, keep 00:00 until the user can see it.
            visible.first { it }
            withTimeoutOrNull(FINISHED_VISIBILITY_TIMEOUT_MS) {
                IslandStateHolder.state.first { it?.source == IslandSource.Timer }
            }
            delay(FINISHED_HOLD_MS)
            clearFinishedCard()
        }
    }

    /** Ends the finished card without touching the notification that told the user about it. */
    private fun clearFinishedCard() {
        cancelJobs()
        core?.stop()
        _state.value = null
        IslandStateHolder.clearTimer()
        updateBootReceiver(enabled = false)
    }

    private fun updateBootReceiver(enabled: Boolean) {
        val context = appContext ?: return
        val component = ComponentName(context, BootReceiver::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.w(TAG, "Unable to set the boot receiver state (enabled=$enabled)", it) }
    }

    /** Production clock: uptime for the ticker, wall clock for the durable deadline. */
    private object SystemTimerClock : TimerClock {
        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
        override fun wallMs(): Long = System.currentTimeMillis()
    }
}
