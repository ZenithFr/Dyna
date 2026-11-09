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
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandSource
import com.zenith.nothingisland.service.IslandStateHolder
import com.zenith.nothingisland.service.NotificationAction

/**
 * Routes public notification contracts into island sources:
 * CallStyle -> calls, MessagingStyle -> messages, progress/ProgressStyle extras -> downloads,
 * navigation category/maps packages -> maps. No Shizuku or private app API is required.
 */
class NotificationSource(private val context: Context) {

    private val notificationManager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)
    private val packageManager: PackageManager = context.packageManager
    private val keyguardManager: KeyguardManager? = context.getSystemService(KeyguardManager::class.java)

    private val labelCache = mutableMapOf<String, String>()
    private val iconCache = LruCache<String, Drawable>(MAX_PACKAGE_ICONS)
    private val activeKeys = mutableMapOf<String, IslandSource>()
    private val activePackages = mutableMapOf<String, String>()
    private val signatures = mutableMapOf<String, String>()
    private var settings = IslandSettings()

    fun updateSettings(next: IslandSettings) {
        val previous = settings
        val coerced = next.coerced()
        settings = coerced
        if (!coerced.callsEnabled) IslandStateHolder.clearCall()
        if (!coerced.notificationsEnabled) IslandStateHolder.clearNotification()
        if (!coerced.downloadsEnabled) IslandStateHolder.clearDownload()
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
        val decision = route(sbn, notification, ranking)
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
        val redact = settings.redactOnLockscreen && keyguardManager?.isKeyguardLocked == true &&
            notification.visibility != Notification.VISIBILITY_PUBLIC
        val icon = if (redact) appIcon(sbn.packageName) else icon(notification, sbn.packageName)
        // Parse once, then dedupe by signature. The previous path built every accepted notification
        // twice (signature pass + post pass), doubling binder/extras work on busy progress updates.
        val content = when (source) {
            IslandSource.Call -> callContent(sbn, notification, appLabel, icon, redact)
            IslandSource.Notification -> notificationContent(sbn, notification, appLabel, icon, redact)
            IslandSource.Download -> downloadContent(sbn, notification, appLabel, icon, redact)
            IslandSource.Navigation -> navigationContent(sbn, notification, appLabel, icon, redact)
            else -> return
        }
        val signature = when (content) {
            is IslandContent.Call -> content.signature()
            is IslandContent.Notification -> content.signature()
            is IslandContent.Download -> content.signature()
            is IslandContent.Navigation -> content.signature()
            else -> return
        }
        if (signatures[sbn.key] == signature) {
            if (settings.verboseLogging) Log.d(TAG, "drop pkg=${sbn.packageName} reason=${NotificationDrop.UNCHANGED}")
            return
        }
        if (settings.verboseLogging) {
            Log.d(TAG, "show pkg=${sbn.packageName} source=$source")
        }
        signatures[sbn.key] = signature
        activeKeys[sbn.key] = source
        activePackages[sbn.key] = sbn.packageName

        when (content) {
            is IslandContent.Call -> IslandStateHolder.postCall(content, expanded = settings.autoExpandCalls)
            is IslandContent.Notification -> IslandStateHolder.postNotification(content)
            is IslandContent.Download -> IslandStateHolder.postDownload(content)
            is IslandContent.Navigation -> IslandStateHolder.postNavigation(content)
            else -> Unit
        }
    }

    fun onRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        clearKey(key)
    }

    fun clear() {
        activeKeys.keys.toList().forEach { clearKey(it) }
        labelCache.clear()
        iconCache.evictAll()
        signatures.clear()
        activePackages.clear()
        IslandStateHolder.clearNotification()
        IslandStateHolder.clearCall()
        IslandStateHolder.clearDownload()
        IslandStateHolder.clearNavigation()
    }

    private fun clearKey(key: String) {
        when (activeKeys.remove(key)) {
            IslandSource.Call -> IslandStateHolder.clearCall(key)
            IslandSource.Notification -> IslandStateHolder.clearNotification(key)
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
    ): NotificationDecision {
        @Suppress("DEPRECATION")
        val groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        return NotificationGate.decide(
            packageName = sbn.packageName,
            ownPackageName = context.packageName,
            disabledPackages = settings.disabledPackages,
            isSecret = notification.visibility == Notification.VISIBILITY_SECRET,
            isGroupSummary = groupSummary,
            doNotDisturbNone = isDoNotDisturbSilent(),
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
            actions = if (redact) emptyList() else actions(notification),
            contentIntent = if (redact) null else notification.contentIntent,
        )
    }

    private fun downloadContent(
        sbn: StatusBarNotification,
        notification: Notification,
        appLabel: String,
        icon: Drawable?,
        redact: Boolean,
    ): IslandContent.Download {
        val extras = notification.extras ?: Bundle.EMPTY
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100).coerceAtLeast(1)
        val percent = when {
            indeterminate -> -1
            progress < 0 -> -1
            else -> (progress * 100L / max).toInt().coerceIn(0, 100)
        }
        val cancel = notification.actions.orEmpty().firstOrNull { action ->
            val title = action.title?.toString()?.lowercase().orEmpty()
            "cancel" in title || "stop" in title || "pause" in title
        }?.actionIntent
        return IslandContent.Download(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            icon = icon,
            title = if (redact) appLabel else text(notification, Notification.EXTRA_TITLE) ?: appLabel,
            text = if (redact) "" else text(notification, Notification.EXTRA_TEXT) ?: "",
            percent = percent,
            contentIntent = if (redact) null else notification.contentIntent,
            cancelIntent = if (redact) null else cancel,
        )
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

    private fun isDoNotDisturbSilent(): Boolean = runCatching {
        notificationManager?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
    }.getOrDefault(false)

    private fun text(notification: Notification, key: String): String? {
        val value = notification.extras?.getCharSequence(key)?.toString()?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    private fun actions(notification: Notification): List<NotificationAction> {
        val candidates = notification.actions.orEmpty()
            .filter { !it.title.isNullOrBlank() && it.actionIntent != null }
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
        return appIcon(packageName)
    }

    private fun appIcon(packageName: String): Drawable? {
        iconCache.get(packageName)?.let { return it }
        val icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        if (icon != null) iconCache.put(packageName, icon)
        return icon
    }

    private fun appLabel(packageName: String): String = labelCache.getOrPut(packageName) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))
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

    private fun IslandContent.Download.signature(): String =
        listOf(key, title, text, percent).joinToString("\u0000")

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
