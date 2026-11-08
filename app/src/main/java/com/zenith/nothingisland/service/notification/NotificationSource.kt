package com.zenith.nothingisland.service.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandStateHolder
import com.zenith.nothingisland.service.NotificationAction

/**
 * Turns system notifications into island alerts.
 *
 * Everything is callback-driven. Filtering happens before anything touches the state holder:
 *  - own package, ongoing/foreground, group summaries, low-importance channels;
 *  - media and call categories (owned by the media module / reserved call module);
 *  - Do Not Disturb set to "none".
 *
 * Any failure is swallowed: a broken notification must never take down the listener.
 */
class NotificationSource(private val context: Context) {

    private val notificationManager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private val packageManager: PackageManager = context.packageManager

    private val labelCache = mutableMapOf<String, String>()

    private var shownKey: String? = null
    private var shownSignature: String? = null

    fun onPosted(sbn: StatusBarNotification, ranking: Ranking?) {
        val notification = sbn.notification ?: return
        if (!accept(sbn, notification, ranking)) return

        val appLabel = appLabel(sbn.packageName)
        val title = text(notification, Notification.EXTRA_CONVERSATION_TITLE)
            ?: text(notification, Notification.EXTRA_TITLE)
            ?: appLabel
        val body = text(notification, Notification.EXTRA_BIG_TEXT)
            ?: text(notification, Notification.EXTRA_TEXT)
            ?: text(notification, Notification.EXTRA_SUMMARY_TEXT)
            ?: ""

        val actions = actions(notification)
        val signature = listOf(title, body, actions.joinToString("|") { it.title }).joinToString("\u0000")
        if (sbn.key == shownKey && signature == shownSignature) return

        shownKey = sbn.key
        shownSignature = signature

        IslandStateHolder.postNotification(
            IslandContent.Notification(
                key = sbn.key,
                packageName = sbn.packageName,
                appLabel = appLabel,
                icon = icon(notification, sbn.packageName),
                title = title,
                text = body,
                actions = actions,
                contentIntent = notification.contentIntent,
            ),
        )
    }

    fun onRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        if (key != shownKey) return
        shownKey = null
        shownSignature = null
        IslandStateHolder.clearNotification(key)
    }

    /** Listener disconnected / access revoked: drop the mirrored alert. */
    fun clear() {
        shownKey = null
        shownSignature = null
        IslandStateHolder.clearNotification()
    }

    private fun accept(
        sbn: StatusBarNotification,
        notification: Notification,
        ranking: Ranking?,
    ): Boolean {
        if (sbn.packageName == context.packageName) return false
        if (sbn.isOngoing) return false
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        @Suppress("DEPRECATION")
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (isMediaOrCall(notification)) return false
        if (isDoNotDisturbSilent()) return false
        if (isLowImportance(ranking)) return false
        return true
    }

    private fun isMediaOrCall(notification: Notification): Boolean {
        val category = notification.category
        if (category == Notification.CATEGORY_TRANSPORT ||
            category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_MISSED_CALL ||
            category == Notification.CATEGORY_SERVICE
        ) {
            return true
        }
        return notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }

    private fun isDoNotDisturbSilent(): Boolean = runCatching {
        notificationManager?.currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_NONE
    }.getOrDefault(false)

    /**
     * Channel importance is per-package, so it cannot be read for other apps through
     * NotificationManager. The listener's [Ranking] carries the effective importance instead.
     */
    private fun isLowImportance(ranking: Ranking?): Boolean =
        (ranking?.importance ?: NotificationManager.IMPORTANCE_DEFAULT) <=
            NotificationManager.IMPORTANCE_LOW

    private fun text(notification: Notification, key: String): String? {
        val extras = notification.extras ?: return null
        val value = extras.getCharSequence(key)?.toString()?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    /** Up to two actions, inline-reply first so messaging actions are always reachable. */
    private fun actions(notification: Notification): List<NotificationAction> {
        val candidates = notification.actions.orEmpty()
            .filter { action ->
                !action.title.isNullOrBlank() &&
                    (action.actionIntent != null || !action.remoteInputs.isNullOrEmpty())
            }
            .sortedByDescending { !it.remoteInputs.isNullOrEmpty() }
        return candidates.take(MAX_ACTIONS).map { action ->
            NotificationAction(
                title = action.title.toString(),
                pendingIntent = action.actionIntent,
                remoteInput = action.remoteInputs?.firstOrNull(),
            )
        }
    }

    private fun icon(notification: Notification, packageName: String): Drawable? {
        notification.smallIcon?.let { icon ->
            runCatching { icon.loadDrawable(context) }.getOrNull()?.let { return it }
        }
        notification.getLargeIcon()?.let { icon ->
            runCatching { icon.loadDrawable(context) }.getOrNull()?.let { return it }
        }
        return runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    private fun appLabel(packageName: String): String = labelCache.getOrPut(packageName) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))
    }

    /** Only used to keep callers honest about the dependency; no notification is posted. */
    @Suppress("unused")
    private fun hasListenerAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private companion object {
        const val TAG = "IslandNotifications"
        const val MAX_ACTIONS = 2
    }
}

/** Convenience for the listener: never let a malformed notification crash the binder thread. */
internal inline fun guardNotifications(tag: String, block: () -> Unit) {
    runCatching(block).onFailure { Log.w(tag, "Dropped malformed notification", it) }
}
