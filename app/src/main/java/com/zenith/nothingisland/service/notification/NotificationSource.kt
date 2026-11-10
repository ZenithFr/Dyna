package com.zenith.nothingisland.service.notification

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandSource
import com.zenith.nothingisland.service.IslandStateHolder
import com.zenith.nothingisland.service.NotificationAction
import com.zenith.nothingisland.util.isDndSilent

/**
 * Routes public notification contracts into island sources:
 * CallStyle -> calls, MessagingStyle -> messages, progress/ProgressStyle extras -> downloads,
 * navigation category/maps packages -> maps. No Shizuku or private app API is required.
 */
class NotificationSource(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val keyguardManager: KeyguardManager? = context.getSystemService(KeyguardManager::class.java)

    private val labelCache = LruCache<String, String>(MAX_PACKAGE_ICONS)
    private val iconCache = LruCache<String, Drawable>(MAX_PACKAGE_ICONS)
    private val activeKeys = mutableMapOf<String, IslandSource>()
    private val activePackages = mutableMapOf<String, String>()
    private val signatures = mutableMapOf<String, String>()
    /** Debounces the duplicate removal callback Android delivers for a single dismissal. */
    private var lastRemovedKey: String? = null
    private var lastRemovedAtMs = 0L
    private var settings = IslandSettings()

    fun updateSettings(next: IslandSettings) {
        val previous = settings
        val coerced = next.coerced()
        settings = coerced
        if (!coerced.callsEnabled) IslandStateHolder.clearCall()
        if (!coerced.notificationsEnabled) IslandStateHolder.clearNotification()
        if (!coerced.downloadsEnabled) IslandStateHolder.clearDownload()
        if (!coerced.clockEnabled) {
            activePackages.filterValues { it in PopularIntegrations.clockPackages }.keys.toList().forEach { key ->
                clearKey(key)
            }
            IslandStateHolder.clearClock()
        }
        if (!coerced.navigationEnabled) IslandStateHolder.clearNavigation()
        if (coerced.disabledPackages != previous.disabledPackages) {
            val disabled = coerced.disabledPackages
            activePackages.filterValues { it in disabled }.keys.toList().forEach { key ->
                clearKey(key)
            }
        }
    }

    fun onPosted(sbn: StatusBarNotification, ranking: Ranking?) {
        val notification = sbn.notification ?: return
        val clock = decodeClock(sbn, notification)
        val decision = route(sbn, notification, ranking, clock != null)
        val source = decision.source
        if (source == null) {
            if (settings.verboseLogging) {
                Log.d(
                    TAG,
                    "drop pkg=${sbn.packageName} reason=${decision.drop} " +
                        "importance=${ranking?.importance ?: NotificationManager.IMPORTANCE_DEFAULT} " +
                        "ranked=${ranking != null} category=${notification.category.takeIf { it in KNOWN_CATEGORIES } ?: "other"} " +
                        "ongoing=${sbn.isOngoing || notification.flags and Notification.FLAG_ONGOING_EVENT != 0}",
                )
            }
            if (activeKeys.containsKey(sbn.key)) clearKey(sbn.key)
            return
        }
        val appLabel = appLabel(sbn.packageName)
        val redact = redacts(notification)
        val icon = if (redact) appIcon(sbn.packageName) else icon(notification, sbn.packageName)
        // Parse once, then dedupe by signature. The previous path built every accepted notification
        // twice (signature pass + post pass), doubling binder/extras work on busy progress updates.
        val content: IslandContent = when (source) {
            IslandSource.Call -> callContent(sbn, notification, appLabel, icon, redact)
            IslandSource.Notification -> notificationContent(sbn, notification, appLabel, icon, redact)
            IslandSource.Clock ->
                clockContent(sbn, notification, appLabel, icon, redact, clock)
                    ?: downloadContent(sbn, notification, appLabel, icon, redact)
            IslandSource.Download -> downloadContent(sbn, notification, appLabel, icon, redact)
            IslandSource.Navigation -> navigationContent(sbn, notification, appLabel, icon, redact)
            else -> return
        }
        // An undecodable clock notification still shows as a generic activity card.
        val effectiveSource = if (source == IslandSource.Clock && content !is IslandContent.ClockActivity) {
            IslandSource.Download
        } else {
            source
        }
        val signature = when (content) {
            is IslandContent.Call -> content.signature()
            is IslandContent.Notification -> content.signature()
            is IslandContent.ClockActivity -> content.signature()
            is IslandContent.Download -> content.signature()
            is IslandContent.Navigation -> content.signature()
            else -> return
        }
        if (signatures[sbn.key] == signature) {
            if (settings.verboseLogging) Log.d(TAG, "drop pkg=${sbn.packageName} reason=${NotificationDrop.UNCHANGED}")
            return
        }
        if (settings.verboseLogging) {
            Log.d(TAG, "show pkg=${sbn.packageName} source=$effectiveSource")
        }
        signatures[sbn.key] = signature
        activeKeys[sbn.key] = effectiveSource
        activePackages[sbn.key] = sbn.packageName

        when (content) {
            is IslandContent.Call -> IslandStateHolder.postCall(content, expanded = settings.autoExpandCalls)
            is IslandContent.Notification -> IslandStateHolder.postNotification(content)
            is IslandContent.ClockActivity -> IslandStateHolder.postClock(content)
            is IslandContent.Download -> IslandStateHolder.postDownload(content)
            is IslandContent.Navigation -> IslandStateHolder.postNavigation(content)
            else -> Unit
        }
    }

    /**
     * Re-syncs with the shade when the listener connects: drops island alerts the shade no longer
     * has and restores a running clock card, which only posts once. Notifications already in the
     * shade are remembered (so their later updates/removals are handled) but never queued: the
     * island's badge counts alerts that arrive while it is running, not the whole shade history.
     */
    fun seed(active: List<StatusBarNotification>, rankingMap: RankingMap?) {
        // Anything the island still holds that the shade no longer has is stale: drop it first.
        IslandStateHolder.reconcile(active.map { it.key }.toSet())
        active.forEach { sbn ->
            val notification = sbn.notification ?: return@forEach
            // A running timer/stopwatch only posts once: restore it instead of losing the card
            // whenever the listener reconnects (an app update, a reboot, a permission toggle).
            val sealed = decodeClock(sbn, notification)
            if (sealed != null) {
                val redact = redacts(notification)
                val icon = if (redact) appIcon(sbn.packageName) else icon(notification, sbn.packageName)
                val content = clockContent(sbn, notification, appLabel(sbn.packageName), icon, redact, sealed)
                if (content != null) {
                    signatures[sbn.key] = content.signature()
                    activeKeys[sbn.key] = IslandSource.Clock
                    activePackages[sbn.key] = sbn.packageName
                    IslandStateHolder.postClock(content)
                }
                return@forEach
            }
            val ranking = Ranking()
            val known = rankingMap?.getRanking(sbn.key, ranking) == true
            if (route(sbn, notification, if (known) ranking else null).source != IslandSource.Notification) {
                return@forEach
            }
            val redact = redacts(notification)
            val icon = if (redact) appIcon(sbn.packageName) else icon(notification, sbn.packageName)
            val content = notificationContent(sbn, notification, appLabel(sbn.packageName), icon, redact)
            signatures[sbn.key] = content.signature()
            activeKeys[sbn.key] = IslandSource.Notification
            activePackages[sbn.key] = sbn.packageName
        }
    }

    fun onRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        // The framework can deliver the same removal through both listener callbacks; clearing is
        // idempotent, but the duplicate log line makes device verification confusing.
        val now = SystemClock.elapsedRealtime()
        if (key == lastRemovedKey && now - lastRemovedAtMs < 250L) return
        lastRemovedKey = key
        lastRemovedAtMs = now
        if (settings.verboseLogging) Log.d(TAG, "removed pkg=${sbn.packageName}")
        clearKey(key)
        // Also drop it when our bookkeeping never recorded it (a seeded alert, a dropped update):
        // a message dismissed from the panel must never survive on the island.
        IslandStateHolder.clearNotification(key)
        IslandStateHolder.clearClock(key)
        IslandStateHolder.clearDownload(key)
        IslandStateHolder.clearNavigation(key)
    }

    fun clear() {
        activeKeys.keys.toList().forEach { clearKey(it) }
        labelCache.evictAll()
        iconCache.evictAll()
        signatures.clear()
        activePackages.clear()
        IslandStateHolder.clearNotification()
        IslandStateHolder.clearCall()
        IslandStateHolder.clearClock()
        IslandStateHolder.clearDownload()
        IslandStateHolder.clearNavigation()
    }

    private fun redacts(notification: Notification): Boolean =
        settings.redactOnLockscreen && keyguardManager?.isKeyguardLocked == true &&
            notification.visibility != Notification.VISIBILITY_PUBLIC

    private fun clearKey(key: String) {
        when (activeKeys.remove(key)) {
            IslandSource.Call -> IslandStateHolder.clearCall(key)
            IslandSource.Notification -> IslandStateHolder.clearNotification(key)
            IslandSource.Clock -> IslandStateHolder.clearClock(key)
            IslandSource.Download -> IslandStateHolder.clearDownload(key)
            IslandSource.Navigation -> IslandStateHolder.clearNavigation(key)
            else -> Unit
        }
        activePackages.remove(key)
        signatures.remove(key)
    }

    /** Android-side facts in, pure decision out; see [NotificationGate] for the rules. */
    private fun route(
        sbn: StatusBarNotification,
        notification: Notification,
        ranking: Ranking?,
        isClockActivity: Boolean = false,
    ): NotificationDecision {
        @Suppress("DEPRECATION")
        val groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        return NotificationGate.decide(
            packageName = sbn.packageName,
            ownPackageName = context.packageName,
            disabledPackages = settings.disabledPackages,
            isSecret = notification.visibility == Notification.VISIBILITY_SECRET,
            isGroupSummary = groupSummary,
            doNotDisturbNone = context.isDndSilent(),
            importance = ranking?.importance ?: NotificationManager.IMPORTANCE_DEFAULT,
            category = notification.category,
            isMedia = isMedia(notification),
            isOngoing = sbn.isOngoing || notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            hasProgress = hasProgress(notification),
            callsEnabled = settings.callsEnabled,
            notificationsEnabled = settings.notificationsEnabled,
            downloadsEnabled = settings.downloadsEnabled,
            navigationEnabled = settings.navigationEnabled,
            showSilentNotifications = settings.showSilentNotifications,
            isClockActivity = isClockActivity,
            clockEnabled = settings.clockEnabled,
        )
    }

    private fun callContent(
        sbn: StatusBarNotification,
        notification: Notification,
        appLabel: String,
        icon: Drawable?,
        redact: Boolean,
    ): IslandContent.Call {
        val extras = notification.extras ?: Bundle.EMPTY
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_INCOMING)
        val person = parcelableExtra(extras, Notification.EXTRA_CALL_PERSON, Person::class.java)
        val caller = when {
            redact -> if (callType == Notification.CallStyle.CALL_TYPE_INCOMING) "Incoming call" else appLabel
            else -> person?.name?.toString()?.takeIf { it.isNotBlank() }
                ?: text(notification, Notification.EXTRA_TITLE)
                ?: appLabel
        }
        return IslandContent.Call(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            icon = icon,
            caller = caller,
            isVideo = extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO, false),
            isIncoming = callType == Notification.CallStyle.CALL_TYPE_INCOMING || callType == Notification.CallStyle.CALL_TYPE_SCREENING,
            answerIntent = pendingIntentExtra(extras, Notification.EXTRA_ANSWER_INTENT),
            declineIntent = pendingIntentExtra(extras, Notification.EXTRA_DECLINE_INTENT),
            hangUpIntent = pendingIntentExtra(extras, Notification.EXTRA_HANG_UP_INTENT),
            contentIntent = notification.contentIntent,
            answerColor = extras.getInt(Notification.EXTRA_ANSWER_COLOR, settings.callAnswerColorArgb),
            declineColor = extras.getInt(Notification.EXTRA_DECLINE_COLOR, settings.callDeclineColorArgb),
        )
    }

    private fun notificationContent(
        sbn: StatusBarNotification,
        notification: Notification,
        appLabel: String,
        icon: Drawable?,
        redact: Boolean,
    ): IslandContent.Notification {
        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()
        val latest = messaging?.messages?.lastOrNull()
        val sender = latest?.person?.name?.toString() ?: latest?.sender?.toString()
        val title = when {
            redact -> appLabel
            messaging?.conversationTitle?.isNotBlank() == true -> messaging.conversationTitle.toString()
            sender?.isNotBlank() == true -> sender
            else -> text(notification, Notification.EXTRA_CONVERSATION_TITLE)
                ?: text(notification, Notification.EXTRA_TITLE)
                ?: appLabel
        }
        val body = when {
            redact -> "Notification"
            latest?.text?.isNotBlank() == true -> latest.text.toString()
            else -> text(notification, Notification.EXTRA_BIG_TEXT)
                ?: text(notification, Notification.EXTRA_TEXT)
                ?: text(notification, Notification.EXTRA_SUMMARY_TEXT)
                ?: ""
        }
        return IslandContent.Notification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            icon = icon,
            title = title,
            text = body,
            actions = if (redact) emptyList() else actions(notification, clock = isClock(sbn)),
            contentIntent = if (redact) null else notification.contentIntent,
        )
    }

    private fun downloadContent(
        sbn: StatusBarNotification,
        notification: Notification,
        appLabel: String,
        icon: Drawable?,
        redact: Boolean,
    ): IslandContent.Download = IslandContent.Download(
        key = sbn.key,
        packageName = sbn.packageName,
        appLabel = appLabel,
        icon = icon,
        title = if (redact) appLabel else text(notification, Notification.EXTRA_TITLE) ?: appLabel,
        text = if (redact) "" else text(notification, Notification.EXTRA_TEXT) ?: "",
        // The notification's own buttons (pause, stop, cancel, snooze...) replace the progress bar.
        actions = if (redact) emptyList() else actions(notification, clock = isClock(sbn)),
        contentIntent = if (redact) null else notification.contentIntent,
    )

    /**
     * A running timer or stopwatch from a Clock app. Returns null when the notification carries no
     * clock state the parser recognises, so the caller can fall back to a generic activity card.
     */
    private fun clockContent(
        sbn: StatusBarNotification,
        notification: Notification,
        appLabel: String,
        icon: Drawable?,
        redact: Boolean,
        decoded: ClockParser.Decoded?,
    ): IslandContent.ClockActivity? {
        val state = decoded ?: return null
        val nowElapsed = SystemClock.elapsedRealtime()
        val content = IslandContent.ClockActivity(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            icon = icon,
            kind = state.kind,
            isRunning = state.running,
            anchorElapsedMs = state.anchorElapsedMs,
            frozenMs = state.frozenMs,
            timeText = "",
            actions = if (redact) emptyList() else actions(notification, clock = true),
            contentIntent = if (redact) null else notification.contentIntent,
        )
        return content.copy(timeText = content.timeTextAt(nowElapsed))
    }

    private fun navigationContent(
        sbn: StatusBarNotification,
        notification: Notification,
        appLabel: String,
        icon: Drawable?,
        redact: Boolean,
    ): IslandContent.Navigation = IslandContent.Navigation(
        key = sbn.key,
        packageName = sbn.packageName,
        appLabel = appLabel,
        icon = icon,
        instruction = if (redact) appLabel else text(notification, Notification.EXTRA_TITLE)
            ?: text(notification, Notification.EXTRA_TEXT)
            ?: appLabel,
        detail = if (redact) "" else text(notification, Notification.EXTRA_TEXT)
            ?: text(notification, Notification.EXTRA_SUB_TEXT)
            ?: "",
        contentIntent = if (redact) null else notification.contentIntent,
    )

    private fun hasProgress(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        return extras.containsKey(Notification.EXTRA_PROGRESS) ||
            extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
    }

    private fun isMedia(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        return notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }

    private fun text(notification: Notification, key: String): String? {
        val value = notification.extras?.getCharSequence(key)?.toString()?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    /** The clock state this notification carries, or null when it is not a running clock card. */
    private fun decodeClock(
        sbn: StatusBarNotification,
        notification: Notification,
    ): ClockParser.Decoded? {
        if (sbn.packageName !in PopularIntegrations.clockPackages) return null
        val extras = notification.extras ?: return null
        val decoded = ClockParser.decode(
            ClockParser.Input(
                sortKey = sortKeyOf(notification),
                title = text(notification, Notification.EXTRA_TITLE),
                text = text(notification, Notification.EXTRA_TEXT),
                showChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
                chronometerCountDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
                whenWallMs = notification.`when`,
                postTimeWallMs = sbn.postTime,
                nowWallMs = System.currentTimeMillis(),
                nowElapsedMs = SystemClock.elapsedRealtime(),
            ),
        )
        // Google Clock's stopwatch publishes no state at all: its elapsed time only exists inside
        // the notification's custom view. Read that before giving up on the card.
        return decoded ?: ClockRemoteViews.stopwatch(context, notification)
    }

    /**
     * The notification's sort key, which Clock apps use to carry their live timer/stopwatch state.
     * [Notification.getSortKey] is public SDK, so this needs no reflection.
     */
    private fun sortKeyOf(notification: Notification): String? =
        runCatching { notification.sortKey }.getOrNull()

    private fun isClock(sbn: StatusBarNotification): Boolean =
        sbn.packageName in PopularIntegrations.clockPackages

    /**
     * Button order. Replies come first as before; for clock apps the dismiss/stop and snooze
     * controls lead, so an alarm or timer can be silenced without expanding anything.
     */
    private fun actions(notification: Notification, clock: Boolean): List<NotificationAction> {
        val candidates = notification.actions.orEmpty()
            .filter { !it.title.isNullOrBlank() && it.actionIntent != null }
            .sortedWith(
                compareBy<Notification.Action>(
                    { if (clock) clockRank(it.title.toString()) else 0 },
                    { it.remoteInputs.isNullOrEmpty() },
                ),
            )
        return candidates.take(MAX_ACTIONS).map { action ->
            NotificationAction(
                title = action.title.toString(),
                pendingIntent = action.actionIntent,
                remoteInput = action.remoteInputs?.firstOrNull(),
            )
        }
    }

    private fun clockRank(title: String): Int {
        val lower = title.lowercase()
        return when {
            "stop" in lower || "dismiss" in lower || "pause" in lower || "reset" in lower -> 0
            "snooze" in lower -> 1
            else -> 2
        }
    }

    private fun icon(notification: Notification, packageName: String): Drawable? {
        notification.smallIcon?.let { icon ->
            runCatching { icon.loadDrawable(context) }.getOrNull()?.let { return it }
        }
        notification.getLargeIcon()?.let { icon ->
            runCatching { icon.loadDrawable(context) }.getOrNull()?.let { return it }
        }
        return appIcon(packageName)
    }

    private fun appIcon(packageName: String): Drawable? {
        iconCache.get(packageName)?.let { return it }
        val icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        if (icon != null) iconCache.put(packageName, icon)
        return icon
    }

    private fun appLabel(packageName: String): String =
        labelCache.get(packageName) ?: run {
            val label = runCatching {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName.substringAfterLast('.'))
            labelCache.put(packageName, label)
            label
        }

    private fun pendingIntentExtra(extras: Bundle, key: String): PendingIntent? =
        parcelableExtra(extras, key, PendingIntent::class.java)

    private fun <T> parcelableExtra(extras: Bundle, key: String, type: Class<T>): T? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(key, type)
        } else {
            @Suppress("DEPRECATION")
            type.cast(extras.getParcelable(key))
        }
    }.getOrNull()

    private fun IslandContent.Call.signature(): String =
        listOf(key, caller, isIncoming, isVideo, answerIntent != null, declineIntent != null, hangUpIntent != null)
            .joinToString("\u0000")

    private fun IslandContent.Notification.signature(): String =
        listOf(key, title, text, actions.joinToString("|") { it.title }).joinToString("\u0000")

    /** A clock card re-posts with a re-anchored deadline, so the anchor belongs in the signature. */
    private fun IslandContent.ClockActivity.signature(): String =
        listOf(
            key,
            kind,
            isRunning,
            anchorElapsedMs / 1_000L,
            frozenMs / 1_000L,
            actions.joinToString("|") { it.title },
        ).joinToString("\u0000")

    private fun IslandContent.Download.signature(): String =
        listOf(key, title, text, actions.joinToString("|") { it.title }).joinToString("\u0000")

    private fun IslandContent.Navigation.signature(): String =
        listOf(key, instruction, detail).joinToString("\u0000")

    private companion object {
        const val TAG = "IslandNotifications"
        const val MAX_ACTIONS = 2
        const val MAX_PACKAGE_ICONS = 12
        // Categories are app-supplied strings. Log only standard values, never an app's free text.
        val KNOWN_CATEGORIES = setOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_MESSAGE,
            Notification.CATEGORY_EMAIL,
            Notification.CATEGORY_EVENT,
            Notification.CATEGORY_PROMO,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SOCIAL,
            Notification.CATEGORY_ERROR,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_REMINDER,
            Notification.CATEGORY_RECOMMENDATION,
            Notification.CATEGORY_STATUS,
            Notification.CATEGORY_NAVIGATION,
        )
    }
}

internal inline fun guardNotifications(tag: String, block: () -> Unit) {
    runCatching(block).onFailure { Log.w(tag, "Dropped malformed notification", it) }
}
