package com.zenith.nothingisland.service.notification

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.zenith.nothingisland.data.IslandSettingsRepository
import com.zenith.nothingisland.service.IslandService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notification access vehicle. It mirrors alerting notifications into the island and also tells
 * the media module to re-read its sessions once access is granted (media sessions are only
 * readable with notification-listener permission).
 */
class IslandNotificationListener : NotificationListenerService() {

    private var source: NotificationSource? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val notifications: NotificationSource
        get() = source ?: NotificationSource(this).also { source = it }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Media sessions become readable exactly now, so refresh instead of waiting for an event.
        IslandService.instance?.refreshMedia()
        scope.launch {
            IslandSettingsRepository.get(this@IslandNotificationListener).settings.collect { snapshot ->
                notifications.updateSettings(snapshot)
            }
        }
    }

    override fun onListenerDisconnected() {
        source?.clear()
        IslandService.instance?.onNotificationAccessLost()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val posted = sbn ?: return
        guardNotifications(TAG) {
            val ranking = Ranking()
            val known = rankingMap?.getRanking(posted.key, ranking) == true
            notifications.onPosted(posted, if (known) ranking else null)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        guardNotifications(TAG) { notifications.onRemoved(removed) }
    }

    override fun onDestroy() {
        scope.cancel()
        source?.clear()
        source = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "IslandNotificationListener"
    }
}
