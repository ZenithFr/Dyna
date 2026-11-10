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
 * Call is the top live slot: an incoming/ongoing call is never buried by a message alert. Below it,
 * transient confirmations (notification > charging > battery-low > power-save) auto-expire and hand
 * back to the persistent base (timer > navigation > download > media). Every source owns one slot.
 */
object IslandStateHolder {
    const val TRANSIENT_DURATION_MS = 4_000L
    /** How long an alert's app-icon bubble stays out before it retracts into the shell. */
    const val BUBBLE_DURATION_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<IslandEvent?>(null)
    val state: StateFlow<IslandEvent?> = _state.asStateFlow()

    private var call: IslandEvent? = null
    private var notification: IslandEvent? = null
    /** Every accepted message alert, so the island can rotate and count them like the OS shade. */
    private val notificationAlerts = AlertStack<IslandContent.Notification>()
    private var charging: IslandEvent? = null
    private var batteryLow: IslandEvent? = null
    private var powerSave: IslandEvent? = null
    private var timer: IslandEvent? = null
    private var navigation: IslandEvent? = null
    private var download: IslandEvent? = null
    private var media: IslandEvent? = null

    private var notificationJob: Job? = null
    private var notificationBubbleJob: Job? = null
    private var chargingJob: Job? = null
    private var batteryLowJob: Job? = null
    private var powerSaveJob: Job? = null
    private var notificationGeneration = 0
    private var chargingGeneration = 0
    private var batteryLowGeneration = 0
    private var powerSaveGeneration = 0

    private var notificationDurationMs = TRANSIENT_DURATION_MS
    private var notificationBubbleMs = BUBBLE_DURATION_MS
    private var chargingDurationMs = TRANSIENT_DURATION_MS
    private var batteryAlertDurationMs = TRANSIENT_DURATION_MS
    private var powerSaveAlertDurationMs = TRANSIENT_DURATION_MS

    val current: IslandEvent? get() = _state.value

    @Synchronized
    fun updateAlertDurations(
        notificationMs: Long,
        chargingMs: Long,
        batteryMs: Long,
        bubbleMs: Long,
        powerSaveMs: Long,
    ) {
        notificationBubbleMs = bubbleMs.coerceAtLeast(500L)
        notificationDurationMs = notificationMs.coerceAtLeast(500L)
        chargingDurationMs = chargingMs.coerceAtLeast(500L)
        batteryAlertDurationMs = batteryMs.coerceAtLeast(500L)
        powerSaveAlertDurationMs = powerSaveMs.coerceAtLeast(500L)
    }

    @Synchronized
    fun postCall(content: IslandContent.Call, expanded: Boolean = false) {
        call = IslandEvent(IslandSource.Call, content, expanded = call?.expanded ?: expanded)
        publishLocked()
    }

    @Synchronized
    fun clearCall(key: String? = null) {
        val shown = call ?: return
        if (key != null && (shown.content as? IslandContent.Call)?.key != key) return
        call = null
        publishLocked()
    }

    @Synchronized
    fun postMedia(content: IslandContent.Media) {
        media = IslandEvent(IslandSource.Media, content, expanded = media?.expanded == true)
        publishLocked()
    }

    @Synchronized
    fun clearMedia() {
        if (media == null) return
        media = null
        publishLocked()
    }

    /**
     * Queues the alert and shows it now. A post made while another alert is queued starts the
     * rotation: the island cycles through every queued alert until the last one is dismissed.
     */
    @Synchronized
    fun postNotification(content: IslandContent.Notification) {
        notificationAlerts.upsert(content.key, content)
        showNotificationLocked(content.key, bubble = true)
    }

    /**
     * Records an alert that was already in the shade when the listener connected. It only feeds
     * the count; it never pops up or starts a rotation.
     */
    @Synchronized
    fun seedNotification(content: IslandContent.Notification) {
        notificationAlerts.seed(content.key, content)
    }

    /** [key] = a dismissed or handled alert; null = the whole queue (service stop, feature off). */
    @Synchronized
    fun clearNotification(key: String? = null) {
        if (key == null) {
            notificationAlerts.clear()
            hideNotificationLocked()
            return
        }
        if (!notificationAlerts.remove(key)) return
        val shownKey = (notification?.content as? IslandContent.Notification)?.key
        val next = notificationAlerts.nextKey(key)
        when {
            next == null -> hideNotificationLocked()
            key == shownKey && notificationAlerts.rotating -> showNotificationLocked(next, bubble = true)
            key == shownKey -> hideNotificationLocked()
            else -> refreshNotificationCountLocked()
        }
    }

    @Synchronized
    fun postCharging(content: IslandContent.Charging) {
        val generation = ++chargingGeneration
        charging = IslandEvent(IslandSource.Charging, content, expanded = false, transient = true)
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

    @Synchronized
    fun postBatteryLow(content: IslandContent.BatteryLow) {
        val generation = ++batteryLowGeneration
        batteryLow = IslandEvent(IslandSource.BatteryLow, content, expanded = false, transient = true)
        publishLocked()
        armBatteryLowTimeout(generation)
    }

    @Synchronized
    fun clearBatteryLow() {
        batteryLowGeneration++
        batteryLowJob?.cancel()
        batteryLowJob = null
        if (batteryLow == null) return
        batteryLow = null
        publishLocked()
    }

    @Synchronized
    fun postPowerSave(content: IslandContent.PowerSave) {
        val generation = ++powerSaveGeneration
        powerSave = IslandEvent(IslandSource.PowerSave, content, expanded = false, transient = true)
        publishLocked()
        armPowerSaveTimeout(generation)
    }

    @Synchronized
    fun clearPowerSave() {
        powerSaveGeneration++
        powerSaveJob?.cancel()
        powerSaveJob = null
        if (powerSave == null) return
        powerSave = null
        publishLocked()
    }

    @Synchronized
    fun postTimer(content: IslandContent.Timer) {
        timer = IslandEvent(IslandSource.Timer, content, expanded = timer?.expanded == true)
        publishLocked()
    }

    @Synchronized
    fun clearTimer() {
        if (timer == null) return
        timer = null
        publishLocked()
    }

    @Synchronized
    fun postNavigation(content: IslandContent.Navigation) {
        navigation = IslandEvent(IslandSource.Navigation, content, expanded = navigation?.expanded == true)
        publishLocked()
    }

    @Synchronized
    fun clearNavigation(key: String? = null) {
        val shown = navigation ?: return
        if (key != null && (shown.content as? IslandContent.Navigation)?.key != key) return
        navigation = null
        publishLocked()
    }

    @Synchronized
    fun postDownload(content: IslandContent.Download) {
        download = IslandEvent(IslandSource.Download, content, expanded = download?.expanded == true)
        publishLocked()
    }

    @Synchronized
    fun clearDownload(key: String? = null) {
        val shown = download ?: return
        if (key != null && (shown.content as? IslandContent.Download)?.key != key) return
        download = null
        publishLocked()
    }

    /** Hides the visible alerts. Queued notifications stay counted until they are dismissed. */
    @Synchronized
    fun clearTransient() {
        chargingGeneration++
        batteryLowGeneration++
        powerSaveGeneration++
        chargingJob?.cancel()
        batteryLowJob?.cancel()
        powerSaveJob?.cancel()
        chargingJob = null
        batteryLowJob = null
        powerSaveJob = null
        val hadAlert = charging != null || batteryLow != null || powerSave != null
        charging = null
        batteryLow = null
        powerSave = null
        hideNotificationLocked()
        if (hadAlert) publishLocked()
    }

    @Synchronized
    fun clearAll() {
        clearTransient()
        notificationAlerts.clear()
        call = null
        timer = null
        navigation = null
        download = null
        media = null
        publishLocked()
    }

    @Synchronized
    fun toggleExpanded() {
        val event = current ?: return
        when (event.source) {
            IslandSource.Notification -> {
                notification = event.copy(expanded = !event.expanded)
                armNotificationTimeout(notificationGeneration)
            }
            IslandSource.Call -> call = event.copy(expanded = !event.expanded)
            IslandSource.Timer -> timer = event.copy(expanded = !event.expanded)
            IslandSource.Media -> media = event.copy(expanded = !event.expanded)
            IslandSource.Navigation -> navigation = event.copy(expanded = !event.expanded)
            IslandSource.Download -> download = event.copy(expanded = !event.expanded)
            IslandSource.Charging, IslandSource.BatteryLow, IslandSource.PowerSave -> Unit
        }
        publishLocked()
    }

    @Synchronized
    fun collapse() {
        call?.let { if (it.expanded) call = it.copy(expanded = false) }
        notification?.let { if (it.expanded) notification = it.copy(expanded = false) }
        timer?.let { if (it.expanded) timer = it.copy(expanded = false) }
        navigation?.let { if (it.expanded) navigation = it.copy(expanded = false) }
        download?.let { if (it.expanded) download = it.copy(expanded = false) }
        media?.let { if (it.expanded) media = it.copy(expanded = false) }
        publishLocked()
    }

    /** Shows [key] from the queue. [bubble] = the app-icon bubble detaches again for this alert. */
    private fun showNotificationLocked(key: String, bubble: Boolean) {
        val content = notificationAlerts.get(key)
        if (content == null) {
            hideNotificationLocked()
            return
        }
        val generation = ++notificationGeneration
        notification = IslandEvent(
            IslandSource.Notification,
            content.copy(stackCount = notificationAlerts.size, showBubble = bubble),
            expanded = false,
            transient = true,
        )
        publishLocked()
        armNotificationTimeout(generation)
        if (bubble) armNotificationBubble(generation) else notificationBubbleJob?.cancel()
    }

    private fun hideNotificationLocked() {
        notificationGeneration++
        notificationJob?.cancel()
        notificationBubbleJob?.cancel()
        notificationJob = null
        notificationBubbleJob = null
        if (notification == null) return
        notification = null
        publishLocked()
    }

    /** Only the count changed (another alert was dismissed), so keep the visible alert as it is. */
    private fun refreshNotificationCountLocked() {
        val shown = notification ?: return
        val content = shown.content as? IslandContent.Notification ?: return
        notification = shown.copy(content = content.copy(stackCount = notificationAlerts.size))
        publishLocked()
    }

    private fun armNotificationTimeout(generation: Int) {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            delay(notificationDurationMs)
            synchronized(this@IslandStateHolder) {
                if (notificationGeneration != generation || notification == null) return@synchronized
                advanceNotificationLocked()
            }
        }
    }

    /** The hold ran out: rotate to the next queued alert, or retract the alert once it is alone. */
    private fun advanceNotificationLocked() {
        if (!notificationAlerts.rotating) {
            hideNotificationLocked()
            return
        }
        val currentKey = (notification?.content as? IslandContent.Notification)?.key
        val next = notificationAlerts.nextKey(currentKey)
        when {
            next == null -> hideNotificationLocked()
            // The last queued alert keeps the island until it is dismissed, but it must not re-arm
            // its own timeout: re-showing itself every hold would publish forever.
            next == currentKey -> notificationJob = null
            else -> showNotificationLocked(next, bubble = true)
        }
    }

    private fun armNotificationBubble(generation: Int) {
        notificationBubbleJob?.cancel()
        notificationBubbleJob = scope.launch {
            delay(notificationBubbleMs)
            synchronized(this@IslandStateHolder) {
                if (notificationGeneration != generation) return@synchronized
                val shown = notification ?: return@synchronized
                val content = shown.content as? IslandContent.Notification ?: return@synchronized
                notification = shown.copy(content = content.copy(showBubble = false))
                publishLocked()
            }
        }
    }

    private fun armChargingTimeout(generation: Int) {
        chargingJob?.cancel()
        chargingJob = scope.launch {
            delay(chargingDurationMs)
            synchronized(this@IslandStateHolder) {
                if (chargingGeneration != generation || charging == null) return@synchronized
                charging = null
                publishLocked()
            }
        }
    }

    private fun armBatteryLowTimeout(generation: Int) {
        batteryLowJob?.cancel()
        batteryLowJob = scope.launch {
            delay(batteryAlertDurationMs)
            synchronized(this@IslandStateHolder) {
                if (batteryLowGeneration != generation || batteryLow == null) return@synchronized
                batteryLow = null
                publishLocked()
            }
        }
    }

    private fun armPowerSaveTimeout(generation: Int) {
        powerSaveJob?.cancel()
        powerSaveJob = scope.launch {
            delay(powerSaveAlertDurationMs)
            synchronized(this@IslandStateHolder) {
                if (powerSaveGeneration != generation || powerSave == null) return@synchronized
                powerSave = null
                publishLocked()
            }
        }
    }

    private fun publishLocked() {
        val alert = call ?: notification ?: charging ?: batteryLow ?: powerSave
        val base = timer ?: navigation ?: download ?: media
        _state.value = alert ?: base
    }
}
