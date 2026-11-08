package com.zenith.nothingisland.service.notification

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import com.zenith.nothingisland.service.IslandService

/**
 * Notification access vehicle. It mirrors alerting notifications into the island and also tells
 * the media module to re-read its sessions once access is granted (media sessions are only
 * readable with notification-listener permission).
 */
class IslandNotificationListener : NotificationListenerService() {

    private var source: NotificationSource? = null

    private val notifications: NotificationSource
        get() = source ?: NotificationSource(this).also { source = it }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Media sessions become readable exactly now, so refresh instead of waiting for an event.
        IslandService.instance?.refreshMedia()
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
        source?.clear()
        source = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "IslandNotificationListener"
    }
}
