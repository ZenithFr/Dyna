package com.zenith.nothingisland.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for what the island displays.
 *
 * Two layers are kept:
 *  - **persistent**: [IslandSource.Timer] > [IslandSource.Media] > [IslandSource.Call] (the
 *    "base" state; it stays until its owner clears it — a timer is never auto-expired here);
 *  - **transient alerts**: [IslandSource.Notification] > [IslandSource.Charging], each auto-expiring
 *    after [TRANSIENT_DURATION_MS]. The highest active alert is shown, and when it expires the
 *    holder falls back to the base state automatically — that is the "restore the timer / media,
 *    else idle" rule.
 *
 * Every source owns exactly one slot, so a source can be re-posted (new track, battery percent,
 * timer tick) without disturbing the others. All mutations are serialised; the resulting [state]
 * flow is collected by [IslandService][com.zenith.nothingisland.service.IslandService].
 */
object IslandStateHolder {

    /** How long a notification / charging alert stays on screen without interaction. */
    const val TRANSIENT_DURATION_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<IslandEvent?>(null)
    val state: StateFlow<IslandEvent?> = _state.asStateFlow()

    private var timer: IslandEvent? = null
    private var media: IslandEvent? = null
    private var call: IslandEvent? = null
    private var notification: IslandEvent? = null
    private var charging: IslandEvent? = null

    private var notificationJob: Job? = null
    private var chargingJob: Job? = null
    private var notificationGeneration = 0
    private var chargingGeneration = 0

    val current: IslandEvent? get() = _state.value

    /** Persistent media state; expansion is preserved across track/progress updates. */
    @Synchronized
    fun postMedia(content: IslandContent.Media) {
        media = IslandEvent(
            source = IslandSource.Media,
            content = content,
            expanded = media?.expanded == true,
        )
        publishLocked()
    }

    @Synchronized
    fun clearMedia() {
        if (media == null) return
        media = null
        publishLocked()
    }

    /** Reserved for the call module; kept so the priority order is explicit. */
    @Synchronized
    fun postCall(content: IslandContent.Notification) {
        call = IslandEvent(IslandSource.Call, content, expanded = call?.expanded == true)
        publishLocked()
    }

    @Synchronized
    fun clearCall() {
        if (call == null) return
        call = null
        publishLocked()
    }

    /** Posts a notification alert for [TRANSIENT_DURATION_MS] and arms its expiry. */
    @Synchronized
    fun postNotification(content: IslandContent.Notification) {
        val generation = ++notificationGeneration
        notification = IslandEvent(
            source = IslandSource.Notification,
            content = content,
            expanded = false,
            transient = true,
        )
        publishLocked()
        armNotificationTimeout(generation)
    }

    @Synchronized
    fun clearNotification(key: String? = null) {
        val shown = notification ?: return
        if (key != null) {
            val shownKey = (shown.content as? IslandContent.Notification)?.key
            if (shownKey != key) return
        }
        notificationGeneration++
        notificationJob?.cancel()
        notificationJob = null
        notification = null
        publishLocked()
    }

    /** Transient charging confirmation. */
    @Synchronized
    fun postCharging(content: IslandContent.Charging) {
        val generation = ++chargingGeneration
        charging = IslandEvent(
            source = IslandSource.Charging,
            content = content,
            expanded = false,
            transient = true,
        )
        publishLocked()
        armChargingTimeout(generation)
    }

    @Synchronized
    fun clearCharging() {
        chargingGeneration++
        chargingJob?.cancel()
        chargingJob = null
        if (charging == null) return
        charging = null
        publishLocked()
    }

    /**
     * Persistent timer state. The timer module re-posts on every tick and clears on stop, so the
     * holder never expires it on a wall clock — a paused timer must stay visible indefinitely.
     * Expansion survives ticks just like media.
     */
    @Synchronized
    fun postTimer(content: IslandContent.Timer) {
        timer = IslandEvent(
            source = IslandSource.Timer,
            content = content,
            expanded = timer?.expanded == true,
        )
        publishLocked()
    }

    @Synchronized
    fun clearTimer() {
        if (timer == null) return
        timer = null
        publishLocked()
    }

    /**
     * Drops alerts only (used when the accessibility service re-connects). A running or paused
     * timer is persistent state owned by
     * [com.zenith.nothingisland.service.timer.TimerModule], so it survives a reconnect.
     */
    @Synchronized
    fun clearTransient() {
        notificationGeneration++
        chargingGeneration++
        notificationJob?.cancel()
        chargingJob?.cancel()
        notificationJob = null
        chargingJob = null
        val hadAlert = notification != null || charging != null
        notification = null
        charging = null
        if (hadAlert) publishLocked()
    }

    /** Full reset, e.g. when the overlay goes away (the service also stops the timer module). */
    @Synchronized
    fun clearAll() {
        notificationGeneration++
        chargingGeneration++
        notificationJob?.cancel()
        chargingJob?.cancel()
        notificationJob = null
        chargingJob = null
        notification = null
        charging = null
        timer = null
        media = null
        call = null
        publishLocked()
    }

    /**
     * Toggles the expanded rendering of the current event. Any interaction re-arms an alert's
     * auto-collapse timer, so an expanded notification still returns to the previous state after
     * [TRANSIENT_DURATION_MS] and the user is never stuck in a large pill.
     */
    @Synchronized
    fun toggleExpanded() {
        val event = notification ?: charging ?: timer ?: media ?: call ?: return
        when (event.source) {
            IslandSource.Notification -> {
                val next = event.copy(expanded = !event.expanded)
                notification = next
                // Interaction restarts the 4 s window: an alert always collapses back to the
                // previous (timer / media / call / idle) state on its own.
                armNotificationTimeout(notificationGeneration)
            }

            IslandSource.Timer -> timer = event.copy(expanded = !event.expanded)
            IslandSource.Media -> media = event.copy(expanded = !event.expanded)
            IslandSource.Call -> call = event.copy(expanded = !event.expanded)
            IslandSource.Charging -> Unit // charging has no expanded layout
        }
        publishLocked()
    }

    @Synchronized
    fun collapse() {
        notification?.let { if (it.expanded) notification = it.copy(expanded = false) }
        timer?.let { if (it.expanded) timer = it.copy(expanded = false) }
        media?.let { if (it.expanded) media = it.copy(expanded = false) }
        call?.let { if (it.expanded) call = it.copy(expanded = false) }
        publishLocked()
    }

    private fun armNotificationTimeout(generation: Int) {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            delay(TRANSIENT_DURATION_MS)
            synchronized(this@IslandStateHolder) {
                if (notificationGeneration != generation) return@synchronized
                if (notification == null) return@synchronized
                notification = null
                publishLocked()
            }
        }
    }

    private fun armChargingTimeout(generation: Int) {
        chargingJob?.cancel()
        chargingJob = scope.launch {
            delay(TRANSIENT_DURATION_MS)
            synchronized(this@IslandStateHolder) {
                if (chargingGeneration != generation) return@synchronized
                if (charging == null) return@synchronized
                charging = null
                publishLocked()
            }
        }
    }

    /**
     * Alerts sit in a transient layer above the persistent one:
     *
     *  - alert layer: notification > charging (auto-expire after [TRANSIENT_DURATION_MS]);
     *  - base layer:  timer > media > call (stays until the source clears it).
     *
     * That is what makes a notification temporarily cover a running timer (or playing media) and
     * hand it back afterwards. Idle is the empty publication (null), i.e. the plain shell.
     */
    private fun publishLocked() {
        val alert = notification ?: charging
        val base = timer ?: media ?: call
        _state.value = alert ?: base
    }
}
