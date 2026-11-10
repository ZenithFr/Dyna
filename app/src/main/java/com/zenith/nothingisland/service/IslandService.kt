package com.zenith.nothingisland.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.zenith.nothingisland.data.IslandSettingsRepository
import com.zenith.nothingisland.overlay.IslandInteraction
import com.zenith.nothingisland.overlay.IslandOverlay
import com.zenith.nothingisland.overlay.IslandReplyWindow
import com.zenith.nothingisland.overlay.TransportCommand
import com.zenith.nothingisland.service.charging.ChargingSource
import com.zenith.nothingisland.service.media.MediaSource
import com.zenith.nothingisland.service.timer.TimerModule
import com.zenith.nothingisland.util.ServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AccessibilityService that paints the island window. It does not observe accessibility events
 * or window content: the service is a permission vehicle for TYPE_ACCESSIBILITY_OVERLAY windows,
 * which need no root and no SYSTEM_ALERT_WINDOW grant. Fullscreen suppression reads only the
 * status-bar visibility from the overlay's own WindowInsets, never another app's UI tree.
 *
 * Feature wiring stays thin here: media, notifications and charging own their own modules and
 * publish into [IslandStateHolder]; this class only renders the holder's current event and
 * forwards taps back to the modules.
 */
class IslandService : AccessibilityService(), IslandInteraction {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { IslandSettingsRepository.get(this) }
    private val mediaSource by lazy { MediaSource(applicationContext) }
    private val chargingSource by lazy { ChargingSource(applicationContext) }
    private val replyWindow by lazy { IslandReplyWindow(this) }

    private var overlay: IslandOverlay? = null
    private var settingsJob: Job? = null
    private var stateJob: Job? = null
    private var replyKey: String? = null
    private var dndReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Alerts belong to the previous session; media is re-read below. The timer module owns
        // its own scope, so a running/paused timer survives a service reconnect untouched.
        IslandStateHolder.clearTransient()
        // The holder was cleared on teardown, so put the live timer back before the first frame:
        // a paused timer has no ticker to re-publish itself.
        TimerModule.state.value?.let { IslandStateHolder.postTimer(it) }

        if (overlay == null) {
            overlay = runCatching { IslandOverlay(this) }
                .onFailure { Log.e(TAG, "Unable to create the island window", it) }
                .getOrNull()
                ?.also {
                    it.interaction = this
                    it.onSuppressed = {
                        replyWindow.hide()
                        replyKey = null
                    }
                    it.onScreenActiveChanged = { active ->
                        // The island is not visible while the panel is off/dozing: drop media
                        // callbacks and battery receivers instead of updating a hidden surface.
                        if (active) {
                            TimerModule.setIslandVisible(true)
                            syncPowerObservers()
                        } else {
                            replyWindow.hide()
                            replyKey = null
                            TimerModule.setIslandVisible(false)
                            runCatching { mediaSource.stop() }
                            runCatching { chargingSource.stop() }
                        }
                    }
                }
        }

        settingsJob?.cancel()
        settingsJob = scope.launch {
            repository.settings.collect { snapshot ->
                IslandStateHolder.updateAlertDurations(
                    notificationMs = snapshot.notificationDurationSec * 1000L,
                    chargingMs = snapshot.chargingDurationSec * 1000L,
                    batteryMs = snapshot.batteryAlertDurationSec * 1000L,
                    bubbleMs = snapshot.bubbleDurationSec * 1000L,
                    powerSaveMs = snapshot.powerSaveAlertDurationSec * 1000L,
                )
                chargingSource.updateSettings(snapshot)
                if (overlay?.isScreenActive != false) {
                    mediaSource.updateEnabled(snapshot.mediaEnabled)
                } else {
                    mediaSource.stop()
                }
                overlay?.updateSettings(snapshot)
            }
        }

        stateJob?.cancel()
        stateJob = scope.launch {
            IslandStateHolder.state.collect { event ->
                val notificationKey = (event?.content as? IslandContent.Notification)?.key
                if (replyWindow.isShowing && notificationKey != replyKey) {
                    replyWindow.hide()
                    replyKey = null
                }
                overlay?.render(event)
            }
        }

        syncPowerObservers()
        registerDndReceiver()
    }

    /**
     * Shows the empty shell briefly while the user edits settings, so calibration sliders have a
     * live target even when the island had already retired into the lens.
     */
    fun previewIsland() {
        overlay?.previewIsland()
    }

    /**
     * Explicit overlay-only preview. This bypasses the notification listener (which correctly
     * rejects our own package), posts no system notification, and expires like a real alert.
     * A visible result proves the overlay path, not notification access or app-specific routing.
     */
    fun showTestAlert() {
        val icon = runCatching { packageManager.getApplicationIcon(applicationInfo) }.getOrNull()
        IslandStateHolder.postNotification(
            IslandContent.Notification(
                key = "nothing-island-test:${SystemClock.elapsedRealtime()}",
                packageName = packageName,
                appLabel = "Nothing Island",
                icon = icon,
                title = "Test alert",
                text = "The island is working",
                actions = emptyList(),
                contentIntent = null,
            ),
        )
        Log.i(TAG, "test alert posted directly to overlay (listener bypassed)")
    }

    /** Called by the notification listener when notification access becomes available. */
    fun refreshMedia() {
        if (overlay?.isScreenActive == false) return
        runCatching { mediaSource.refresh() }
            .onFailure { Log.w(TAG, "Media refresh failed", it) }
    }

    private fun syncPowerObservers() {
        TimerModule.setIslandVisible(overlay?.isScreenActive != false)
        if (overlay?.isScreenActive == false) {
            runCatching { mediaSource.stop() }
            runCatching { chargingSource.stop() }
            return
        }
        runCatching { mediaSource.refresh() }
            .onFailure { Log.w(TAG, "Media module failed to start", it) }
        runCatching { chargingSource.start() }
            .onFailure { Log.w(TAG, "Charging module failed to start", it) }
    }

    /**
     * Notification access was revoked (or the listener was unbound): media sessions are no longer
     * readable, so drop the mirrored track instead of leaving a stale pill on screen. The next
     * [onListenerConnected] re-arms the module via [refreshMedia].
     */
    fun onNotificationAccessLost() {
        runCatching { mediaSource.stop() }
            .onFailure { Log.w(TAG, "Media teardown failed", it) }
    }

    // ---------------------------------------------------------------------------------------
    // IslandInteraction
    // ---------------------------------------------------------------------------------------

    override fun onBodyTap() {
        val event = IslandStateHolder.current ?: return
        when (event.source) {
            IslandSource.Notification -> {
                if (event.expanded) openNotification(event) else IslandStateHolder.toggleExpanded()
            }

            IslandSource.Media -> IslandStateHolder.toggleExpanded()
            IslandSource.Timer -> IslandStateHolder.toggleExpanded()
            IslandSource.Call -> IslandStateHolder.toggleExpanded()
            IslandSource.Download -> IslandStateHolder.toggleExpanded()
            IslandSource.Navigation -> IslandStateHolder.toggleExpanded()
            // Charging / battery confirmations have no expanded layout.
            else -> Unit
        }
    }

    override fun onTransport(command: TransportCommand) {
        when (command) {
            TransportCommand.Previous -> mediaSource.previous()
            TransportCommand.PlayPause -> mediaSource.togglePlayPause()
            TransportCommand.Next -> mediaSource.next()
        }
    }

    override fun onTimerToggle() {
        TimerModule.togglePause()
    }

    override fun onTimerStop() {
        TimerModule.stop()
    }

    override fun onCallAnswer() {
        val call = IslandStateHolder.current?.content as? IslandContent.Call ?: return
        fire(call.answerIntent)
    }

    override fun onCallDecline() {
        val call = IslandStateHolder.current?.content as? IslandContent.Call ?: return
        if (fire(call.declineIntent)) IslandStateHolder.clearCall(call.key)
    }

    override fun onCallEnd() {
        val call = IslandStateHolder.current?.content as? IslandContent.Call ?: return
        if (fire(call.hangUpIntent ?: call.declineIntent)) IslandStateHolder.clearCall(call.key)
    }

    override fun onOpenContent() {
        val intent = when (val content = IslandStateHolder.current?.content) {
            is IslandContent.Call -> content.contentIntent
            is IslandContent.Download -> content.contentIntent
            is IslandContent.Navigation -> content.contentIntent
            is IslandContent.Notification -> content.contentIntent
            else -> null
        }
        fire(intent)
    }

    /**
     * Buttons from a notification alert or an ongoing activity (download, clock timer). Only a
     * message alert is dismissed from the island after its action; an activity keeps running and
     * the app re-posts its notification with the new state.
     */
    override fun onNotificationAction(index: Int) {
        val event = IslandStateHolder.current ?: return
        val actions: List<NotificationAction>
        val key: String
        val title: String
        val contentIntent: PendingIntent?
        val dismissOnAction: Boolean
        when (val content = event.content) {
            is IslandContent.Notification -> {
                actions = content.actions
                key = content.key
                title = content.title
                contentIntent = content.contentIntent
                dismissOnAction = true
            }
            is IslandContent.Download -> {
                actions = content.actions
                key = content.key
                title = content.title
                contentIntent = content.contentIntent
                dismissOnAction = false
            }
            else -> return
        }
        val action = actions.getOrNull(index) ?: return

        if (action.remoteInput == null) {
            if (fire(action.pendingIntent) && dismissOnAction) IslandStateHolder.clearNotification(key)
            return
        }
        if (action.pendingIntent == null) return

        val anchor = overlay?.bottomPx ?: 0
        val shown = runCatching {
            replyWindow.show(
                anchorBottomPx = anchor,
                hintText = "Reply to $title",
            ) { text -> sendReply(action, text, key) }
        }.getOrDefault(false)
        if (shown) {
            replyKey = key
        } else {
            // A RemoteInput action expects results. Opening the conversation is safe; sending the
            // bare reply PendingIntent without a result bundle is not a reliable app-UI fallback.
            fire(contentIntent)
        }
    }

    private fun sendReply(action: NotificationAction, text: String, key: String) {
        val remoteInput = action.remoteInput ?: return
        val intent = Intent()
        val results = Bundle().apply {
            putCharSequence(remoteInput.resultKey, text)
        }
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
        val pending = action.pendingIntent ?: return
        val sent = runCatching { pending.send(this, 0, intent) }
            .onFailure { Log.w(TAG, "Inline reply failed", it) }
            .isSuccess
        // Only dismiss the alert that was replied to; a newer notification must not disappear.
        if (sent) IslandStateHolder.clearNotification(key)
    }

    private fun openNotification(event: IslandEvent) {
        val notification = event.content as? IslandContent.Notification ?: return
        if (fire(notification.contentIntent)) IslandStateHolder.clearNotification(notification.key)
    }

    private fun fire(intent: PendingIntent?): Boolean {
        val pending = intent ?: return false
        return runCatching { pending.send() }
            .onFailure { Log.w(TAG, "Notification action failed", it) }
            .isSuccess
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    // Visibility is driven by DisplayManager/configuration changes and the overlay's own inset
    // probe. No accessibility event or window-content capability is needed.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-evaluate landscape/fullscreen suppression first; portrait lock only governs the
        // geometry when landscape hiding is disabled.
        overlay?.refresh()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun teardown() {
        settingsJob?.cancel()
        settingsJob = null
        stateJob?.cancel()
        stateJob = null

        runCatching { replyWindow.hide() }
        replyKey = null
        overlay?.interaction = null
        overlay?.release()
        overlay = null

        unregisterDndReceiver()
        runCatching { mediaSource.stop() }
        runCatching { chargingSource.stop() }
        // A rebind (automation, system restart) must not wipe a countdown, so the app-scoped timer
        // survives; but if the user actually disabled the service it would tick on invisibly.
        // Only drop it in that case; [onServiceConnected] re-posts a surviving snapshot.
        if (!ServiceStatus.isAccessibilityServiceEnabled(this, IslandService::class.java)) {
            runCatching { TimerModule.stop() }
        }
        IslandStateHolder.clearAll()

        if (instance === this) instance = null
    }

    private fun registerDndReceiver() {
        if (dndReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) return
                overlay?.refresh()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            dndReceiver = receiver
        }.onFailure {
            Log.w(TAG, "Unable to register DND receiver", it)
        }
    }

    private fun unregisterDndReceiver() {
        val receiver = dndReceiver ?: return
        dndReceiver = null
        runCatching { unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "DND receiver was already detached", it) }
    }

    companion object {
        private const val TAG = "IslandService"

        /** Live instance for in-process features; null whenever the service is disabled. */
        @Volatile
        var instance: IslandService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }
}
