package com.zenith.nothingisland.service.notification

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import com.zenith.nothingisland.data.IslandSettingsRepository
import com.zenith.nothingisland.service.IslandService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notification access vehicle. Routes public notification contracts into the island and tells
 * the media module to re-read its sessions once access is granted.
 *
 * The manifest deliberately does not restrict notification types: silent messages and ongoing
 * calls/downloads must reach [NotificationGate], where the user's settings decide what is shown.
 */
class IslandNotificationListener : NotificationListenerService() {

    private var source: NotificationSource? = null
    private var settingsJob: Job? = null
    private var verboseLogging = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val notifications: NotificationSource
        get() = source ?: NotificationSource(applicationContext).also { source = it }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedInstance = this
        Log.i(TAG, "listener connected")
        migrateAllTypesOnce()
        // Media sessions become readable exactly now, so refresh instead of waiting for an event.
        IslandService.instance?.refreshMedia()
        // Android can reconnect the listener without destroying the service. Cancel the previous
        // collector first; otherwise every reconnect leaks another DataStore subscription.
        settingsJob?.cancel()
        source?.clear()
        settingsJob = scope.launch {
            IslandSettingsRepository.get(this@IslandNotificationListener).settings.collect { snapshot ->
                verboseLogging = snapshot.verboseLogging
                notifications.updateSettings(snapshot)
            }
        }
    }

    /**
     * Removing the manifest default only fixes fresh installs: Android keeps the old
     * conversations|alerting mask for an already-granted listener. Migrate that mask once after
     * upgrading. minSdk is 31, so every supported release has migrateNotificationFilter().
     *
     * The marker is essential: do not repeat this on every reconnect and overwrite a type filter
     * the user subsequently chooses in system settings. A fresh install already allows all types.
     */
    private fun migrateAllTypesOnce() {
        val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(KEY_ALL_TYPES_MIGRATED, false)) return
        runCatching {
            migrateNotificationFilter(ALL_NOTIFICATION_TYPES, emptyList())
            preferences.edit().putBoolean(KEY_ALL_TYPES_MIGRATED, true).apply()
            Log.i(TAG, "all notification types migration requested")
        }.onFailure { Log.w(TAG, "Notification type migration failed; will retry on reconnect", it) }
    }

    override fun onListenerDisconnected() {
        if (connectedInstance === this) connectedInstance = null
        Log.i(TAG, "listener disconnected")
        settingsJob?.cancel()
        settingsJob = null
        source?.clear()
        IslandService.instance?.onNotificationAccessLost()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val posted = sbn ?: return
        if (!IslandService.isRunning) {
            // This is a delivery drop, not an app filter. Permission can be granted while the
            // accessibility service is stopped; diagnostics must distinguish those two states.
            if (verboseLogging) {
                Log.d(TAG, "drop pkg=${posted.packageName} reason=${NotificationDrop.OVERLAY_STOPPED}")
            }
            return
        }
        guardNotifications(TAG) {
            val ranking = Ranking()
            val known = rankingMap?.getRanking(posted.key, ranking) == true
            notifications.onPosted(posted, if (known) ranking else null)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        if (!IslandService.isRunning) {
            if (verboseLogging) {
                Log.d(TAG, "removal ignored pkg=${removed.packageName} reason=OVERLAY_STOPPED")
            }
            return
        }
        guardNotifications(TAG) { notifications.onRemoved(removed) }
    }

    override fun onDestroy() {
        if (connectedInstance === this) connectedInstance = null
        settingsJob?.cancel()
        settingsJob = null
        scope.cancel()
        source?.clear()
        source = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IslandNotificationListener"
        private const val PREFERENCES = "notification_listener"
        private const val KEY_ALL_TYPES_MIGRATED = "all_types_migrated_v1"
        private const val ALL_NOTIFICATION_TYPES =
            NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS or
                NotificationListenerService.FLAG_FILTER_TYPE_ALERTING or
                NotificationListenerService.FLAG_FILTER_TYPE_SILENT or
                NotificationListenerService.FLAG_FILTER_TYPE_ONGOING

        // Identity, rather than a boolean an older instance could clear after a replacement binds.
        @Volatile
        private var connectedInstance: IslandNotificationListener? = null

        /** Actual bind state in this process, not merely the user's notification-access grant. */
        val isConnected: Boolean
            get() = connectedInstance != null
    }
}
