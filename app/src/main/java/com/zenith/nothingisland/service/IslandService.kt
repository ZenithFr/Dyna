package com.zenith.nothingisland.service

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
 * AccessibilityService that paints the island window. It intentionally does not observe
 * accessibility events or window content: the service is only a permission vehicle for a
 * TYPE_ACCESSIBILITY_OVERLAY window, which needs no root and no SYSTEM_ALERT_WINDOW grant.
 *
 * Feature wiring stays thin here: media, notifications and charging own their own modules and
 * publish into [IslandStateHolder]; this class only renders the holder's current event and
 * forwards taps back to the modules.
 */
class IslandService : AccessibilityService(), IslandInteraction {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { IslandSettingsRepository.get(this) }
    private val mediaSource by lazy { MediaSource(this) }
    private val chargingSource by lazy { ChargingSource(this) }
    private val replyWindow by lazy { IslandReplyWindow(this) }

    private var overlay: IslandOverlay? = null
    private var settingsJob: Job? = null
    private var stateJob: Job? = null

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
                ?.also { it.interaction = this }
        }

        settingsJob?.cancel()
        settingsJob = scope.launch {
            repository.settings.collect { snapshot ->
                overlay?.updateSettings(snapshot)
            }
        }

        stateJob?.cancel()
        stateJob = scope.launch {
            IslandStateHolder.state.collect { event ->
                overlay?.render(event)
            }
        }

        runCatching { mediaSource.start() }
            .onFailure { Log.w(TAG, "Media module failed to start", it) }
        runCatching { chargingSource.start() }
            .onFailure { Log.w(TAG, "Charging module failed to start", it) }
    }

    /** Called by the notification listener when notification access becomes available. */
    fun refreshMedia() {
        runCatching { mediaSource.refresh() }
            .onFailure { Log.w(TAG, "Media refresh failed", it) }
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
            // Charging has no expanded layout; idle pills do nothing.
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

    override fun onNotificationAction(index: Int) {
        val event = IslandStateHolder.current ?: return
        val notification = event.content as? IslandContent.Notification ?: return
        val action = notification.actions.getOrNull(index) ?: return

        if (action.remoteInput == null) {
            fire(action.pendingIntent)
            return
        }

        val anchor = overlay?.bottomPx ?: 0
        val shown = runCatching {
            replyWindow.show(
                anchorBottomPx = anchor,
                hintText = "Reply to ${notification.title}",
            ) { text -> sendReply(action, text, notification.key) }
        }.getOrDefault(false)

        // Fallback for devices that refuse IME focus on accessibility overlays: fire the action's
        // intent, which opens the app's own reply UI for most messaging apps.
        if (!shown) fire(action.pendingIntent)
    }

    private fun sendReply(action: NotificationAction, text: String, key: String) {
        val remoteInput = action.remoteInput ?: return
        val intent = Intent()
        val results = Bundle().apply {
            putCharSequence(remoteInput.resultKey, text)
        }
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
        runCatching { action.pendingIntent?.send(this, 0, intent) }
            .onFailure { Log.w(TAG, "Inline reply failed", it) }
        // Only dismiss the alert that was replied to; a newer notification must not disappear.
        IslandStateHolder.clearNotification(key)
    }

    private fun openNotification(event: IslandEvent) {
        val notification = event.content as? IslandContent.Notification ?: return
        fire(notification.contentIntent)
        IslandStateHolder.clearNotification(notification.key)
    }

    private fun fire(intent: PendingIntent?) {
        val pending = intent ?: return
        runCatching { pending.send() }
            .onFailure { Log.w(TAG, "Notification action failed", it) }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rect = Rect()
        event?.source?.window?.getBoundsInScreen(rect) ?: return
        overlay?.reportWindowEvent(rect)
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Portrait lock makes this a no-op geometry-wise: the same params are re-applied, which
        // also keeps the window attached without a visible jump.
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
        overlay?.interaction = null
        overlay?.release()
        overlay = null

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
