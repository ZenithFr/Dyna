package com.zenith.nothingisland.service.notification

import com.zenith.nothingisland.service.IslandSource

/** Why a posted notification never reached the island. Logged verbatim under verbose logging. */
enum class NotificationDrop {
    OWN_PACKAGE,
    DISABLED_PACKAGE,
    SECRET,
    GROUP_SUMMARY,
    DO_NOT_DISTURB,
    IMPORTANCE,
    MEDIA,
    SERVICE,
    ONGOING,
    CALLS_OFF,
    NOTIFICATIONS_OFF,
    DOWNLOADS_OFF,
    NAVIGATION_OFF,
    CLOCK_OFF,
    // Delivery/deduplication reasons emitted outside the gate.
    OVERLAY_STOPPED,
    UNCHANGED,
}

/** Routing outcome: exactly one of [source] / [drop] is non-null. */
data class NotificationDecision(val source: IslandSource?, val drop: NotificationDrop?) {
    init {
        require((source == null) != (drop == null)) { "A decision must either show a source or give a drop reason" }
    }

    companion object {
        fun show(source: IslandSource) = NotificationDecision(source, null)
        fun drop(reason: NotificationDrop) = NotificationDecision(null, reason)
    }
}

/**
 * Pure notification routing, split out from [NotificationSource] so the rules that decide whether a
 * WhatsApp/Discord message is shown can be unit-tested without Android.
 *
 * Importance mirrors `NotificationManager` values and categories mirror `Notification.CATEGORY_*`
 * strings, so this file needs no framework imports.
 *
 * The important behaviour change: a **silent** (importance LOW) message is a normal case for muted
 * chats and "gentle" notifications. It used to be dropped unconditionally, which — combined with the
 * OS-level listener filter — could make messaging apps look broken. It is now controlled by
 * `showSilentNotifications`; only IMPORTANCE_MIN/NONE is always ignored.
 */
object NotificationGate {

    const val IMPORTANCE_NONE = 0
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_DEFAULT = 3

    const val CATEGORY_CALL = "call"
    const val CATEGORY_SERVICE = "service"
    const val CATEGORY_NAVIGATION = "navigation"
    const val CATEGORY_PROGRESS = "progress"
    const val CATEGORY_ALARM = "alarm"
    const val CATEGORY_MESSAGE = "msg"

    @Suppress("LongParameterList")
    fun decide(
        packageName: String,
        ownPackageName: String,
        disabledPackages: Set<String>,
        isSecret: Boolean,
        isGroupSummary: Boolean,
        doNotDisturbNone: Boolean,
        importance: Int,
        category: String?,
        isMedia: Boolean,
        isOngoing: Boolean,
        hasProgress: Boolean,
        callsEnabled: Boolean,
        notificationsEnabled: Boolean,
        downloadsEnabled: Boolean,
        navigationEnabled: Boolean,
        showSilentNotifications: Boolean,
        isClockActivity: Boolean,
        clockEnabled: Boolean,
    ): NotificationDecision {
        if (packageName == ownPackageName) return NotificationDecision.drop(NotificationDrop.OWN_PACKAGE)
        if (packageName in disabledPackages) return NotificationDecision.drop(NotificationDrop.DISABLED_PACKAGE)
        if (isSecret) return NotificationDecision.drop(NotificationDrop.SECRET)
        // The summary of a group repeats its children; the children carry the real message.
        if (isGroupSummary) return NotificationDecision.drop(NotificationDrop.GROUP_SUMMARY)
        if (doNotDisturbNone) return NotificationDecision.drop(NotificationDrop.DO_NOT_DISTURB)
        if (importance <= IMPORTANCE_MIN) return NotificationDecision.drop(NotificationDrop.IMPORTANCE)
        if (importance <= IMPORTANCE_LOW && !showSilentNotifications) {
            return NotificationDecision.drop(NotificationDrop.IMPORTANCE)
        }

        // Calls win over everything, including the ongoing rule below.
        if (category == CATEGORY_CALL) {
            return if (callsEnabled) NotificationDecision.show(IslandSource.Call)
            else NotificationDecision.drop(NotificationDrop.CALLS_OFF)
        }
        if (isMedia) return NotificationDecision.drop(NotificationDrop.MEDIA)
        // A running clock timer or stopwatch is a live activity that stays until it ends or is
        // dismissed; a ringing alarm has no clock state and stays a plain alert. Clock apps do not
        // mark their timer notification ongoing, so the caller decides from the decoded state.
        if (isClockActivity) {
            return if (clockEnabled) NotificationDecision.show(IslandSource.Clock)
            else NotificationDecision.drop(NotificationDrop.CLOCK_OFF)
        }
        if (category == CATEGORY_SERVICE) return NotificationDecision.drop(NotificationDrop.SERVICE)
        if (category == CATEGORY_NAVIGATION || (isOngoing && packageName in PopularIntegrations.mapsPackages)) {
            return if (navigationEnabled) NotificationDecision.show(IslandSource.Navigation)
            else NotificationDecision.drop(NotificationDrop.NAVIGATION_OFF)
        }
        // Determinate/indeterminate progress only counts as an activity while it is ongoing. A chat
        // message that happens to carry progress extras is an ordinary alert with no percentage.
        if (hasProgress && (isOngoing || category == CATEGORY_PROGRESS)) {
            return if (downloadsEnabled) NotificationDecision.show(IslandSource.Download)
            else NotificationDecision.drop(NotificationDrop.DOWNLOADS_OFF)
        }
        // A plain ongoing notification is a foreground-service status, not an alert.
        if (isOngoing) return NotificationDecision.drop(NotificationDrop.ONGOING)
        return if (notificationsEnabled) NotificationDecision.show(IslandSource.Notification)
        else NotificationDecision.drop(NotificationDrop.NOTIFICATIONS_OFF)
    }
}
