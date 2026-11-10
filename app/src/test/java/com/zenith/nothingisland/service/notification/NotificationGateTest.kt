package com.zenith.nothingisland.service.notification

import com.zenith.nothingisland.service.IslandSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for "WhatsApp/Discord notifications never show". These are the facts after
 * Android delivers a post; the listener must not restrict its OS types before this gate can run.
 */
class NotificationGateTest {

    private fun decide(
        packageName: String = "com.whatsapp",
        ownPackageName: String = "com.zenith.nothingisland",
        disabledPackages: Set<String> = emptySet(),
        isSecret: Boolean = false,
        isGroupSummary: Boolean = false,
        doNotDisturbNone: Boolean = false,
        importance: Int = NotificationGate.IMPORTANCE_DEFAULT,
        category: String? = "msg",
        isMedia: Boolean = false,
        isOngoing: Boolean = false,
        hasProgress: Boolean = false,
        callsEnabled: Boolean = true,
        notificationsEnabled: Boolean = true,
        downloadsEnabled: Boolean = true,
        navigationEnabled: Boolean = true,
        showSilentNotifications: Boolean = true,
        isClockApp: Boolean = false,
        clockEnabled: Boolean = true,
    ) = NotificationGate.decide(
        packageName = packageName,
        ownPackageName = ownPackageName,
        disabledPackages = disabledPackages,
        isSecret = isSecret,
        isGroupSummary = isGroupSummary,
        doNotDisturbNone = doNotDisturbNone,
        importance = importance,
        category = category,
        isMedia = isMedia,
        isOngoing = isOngoing,
        hasProgress = hasProgress,
        callsEnabled = callsEnabled,
        notificationsEnabled = notificationsEnabled,
        downloadsEnabled = downloadsEnabled,
        navigationEnabled = navigationEnabled,
        showSilentNotifications = showSilentNotifications,
        isClockApp = isClockApp,
        clockEnabled = clockEnabled,
    )

    private fun assertShown(expected: IslandSource, decision: NotificationDecision) {
        assertEquals(expected, decision.source)
        assertNull(decision.drop)
    }

    private fun assertDropped(expected: NotificationDrop, decision: NotificationDecision) {
        assertNull(decision.source)
        assertEquals(expected, decision.drop)
    }

    @Test
    fun ordinaryMessagesAreShownWithoutAnAppAllowlist() {
        // The registry is an opt-out UI, not an allowlist. An unlisted app works too.
        listOf("com.whatsapp", "com.discord", "com.example.unlisted").forEach { packageName ->
            assertShown(IslandSource.Notification, decide(packageName = packageName))
            assertShown(IslandSource.Notification, decide(packageName = packageName, category = null))
            assertShown(IslandSource.Notification, decide(packageName = packageName, importance = 4))
        }
    }

    @Test
    fun silentMessagesAreShownByDefault() {
        listOf("com.whatsapp", "com.discord").forEach { packageName ->
            assertShown(
                IslandSource.Notification,
                decide(
                    packageName = packageName,
                    importance = NotificationGate.IMPORTANCE_LOW,
                    showSilentNotifications = true,
                ),
            )
        }
    }

    @Test
    fun silentMessagesCanBeOptedOutWithoutLosingAlertingMessages() {
        assertDropped(
            NotificationDrop.IMPORTANCE,
            decide(importance = NotificationGate.IMPORTANCE_LOW, showSilentNotifications = false),
        )
        assertShown(
            IslandSource.Notification,
            decide(importance = NotificationGate.IMPORTANCE_DEFAULT, showSilentNotifications = false),
        )
    }

    @Test
    fun minimumImportanceIsAlwaysIgnoredEvenWhenSilentAlertsAreEnabled() {
        listOf(NotificationGate.IMPORTANCE_NONE, NotificationGate.IMPORTANCE_MIN).forEach { importance ->
            assertDropped(NotificationDrop.IMPORTANCE, decide(importance = importance))
        }
    }

    @Test
    fun privacyAndUserOptOutsPrecedeContentRouting() {
        assertDropped(NotificationDrop.OWN_PACKAGE, decide(packageName = "com.zenith.nothingisland"))
        assertDropped(
            NotificationDrop.DISABLED_PACKAGE,
            decide(packageName = "com.discord", disabledPackages = setOf("com.discord")),
        )
        assertDropped(NotificationDrop.SECRET, decide(isSecret = true))
        assertDropped(NotificationDrop.DO_NOT_DISTURB, decide(doNotDisturbNone = true))
        assertDropped(NotificationDrop.NOTIFICATIONS_OFF, decide(notificationsEnabled = false))
    }

    @Test
    fun groupSummaryIsSkippedButItsIndividualMessageIsNot() {
        assertDropped(NotificationDrop.GROUP_SUMMARY, decide(isGroupSummary = true))
        assertShown(IslandSource.Notification, decide(isGroupSummary = false))
    }

    @Test
    fun ongoingCallWinsOverTheGenericOngoingFilter() {
        assertShown(
            IslandSource.Call,
            decide(category = NotificationGate.CATEGORY_CALL, isOngoing = true),
        )
        assertDropped(
            NotificationDrop.CALLS_OFF,
            decide(category = NotificationGate.CATEGORY_CALL, isOngoing = true, callsEnabled = false),
        )
    }

    @Test
    fun silentCallStillUsesTheCallSource() {
        assertShown(
            IslandSource.Call,
            decide(category = NotificationGate.CATEGORY_CALL, importance = NotificationGate.IMPORTANCE_LOW),
        )
    }

    @Test
    fun mediaAndForegroundServiceNotificationsDoNotBecomeMessageAlerts() {
        assertDropped(NotificationDrop.MEDIA, decide(isMedia = true, isOngoing = true))
        assertDropped(
            NotificationDrop.SERVICE,
            decide(category = NotificationGate.CATEGORY_SERVICE, isOngoing = true),
        )
        assertDropped(NotificationDrop.ONGOING, decide(category = null, isOngoing = true))
    }

    @Test
    fun progressNotificationCanBeOngoingAndStillBecomeADownload() {
        assertShown(IslandSource.Download, decide(hasProgress = true, isOngoing = true, category = "progress"))
        assertDropped(
            NotificationDrop.DOWNLOADS_OFF,
            decide(hasProgress = true, isOngoing = true, downloadsEnabled = false),
        )
    }

    @Test
    fun chatMessageWithProgressExtrasIsAnOrdinaryAlertWithoutAPercentage() {
        // Many apps attach progress extras to ordinary messages. That used to become a "%" card.
        assertShown(
            IslandSource.Notification,
            decide(hasProgress = true, isOngoing = false, category = NotificationGate.CATEGORY_MESSAGE),
        )
        assertShown(
            IslandSource.Notification,
            decide(packageName = "com.discord", hasProgress = true, isOngoing = false, category = null),
        )
    }

    @Test
    fun clockTimersAndStopwatchAreActivitiesThatCanBeTurnedOff() {
        val clock = "com.google.android.deskclock"
        assertShown(
            IslandSource.Download,
            decide(packageName = clock, category = null, isOngoing = true, isClockApp = true),
        )
        assertDropped(
            NotificationDrop.CLOCK_OFF,
            decide(
                packageName = clock,
                category = null,
                isOngoing = true,
                isClockApp = true,
                clockEnabled = false,
            ),
        )
    }

    @Test
    fun ringingAlarmIsAnAlertNotAnOngoingActivity() {
        assertShown(
            IslandSource.Notification,
            decide(
                packageName = "com.android.deskclock",
                category = NotificationGate.CATEGORY_ALARM,
                isOngoing = false,
                isClockApp = true,
            ),
        )
    }

    @Test
    fun navigationCategoryAndOngoingMapsHintBothRouteToNavigation() {
        assertShown(
            IslandSource.Navigation,
            decide(category = NotificationGate.CATEGORY_NAVIGATION, isOngoing = true),
        )
        assertShown(
            IslandSource.Navigation,
            decide(packageName = "com.google.android.apps.maps", category = null, isOngoing = true),
        )
        assertShown(
            IslandSource.Navigation,
            decide(packageName = "com.waze", category = null, isOngoing = true),
        )
        assertDropped(
            NotificationDrop.NAVIGATION_OFF,
            decide(category = NotificationGate.CATEGORY_NAVIGATION, navigationEnabled = false),
        )
    }

    @Test
    fun disabledMessageSourceDoesNotDisableCallsDownloadsOrNavigation() {
        assertDropped(NotificationDrop.NOTIFICATIONS_OFF, decide(notificationsEnabled = false))
        assertShown(
            IslandSource.Call,
            decide(notificationsEnabled = false, category = NotificationGate.CATEGORY_CALL),
        )
        assertShown(
            IslandSource.Download,
            decide(notificationsEnabled = false, hasProgress = true, isOngoing = true),
        )
        assertShown(
            IslandSource.Navigation,
            decide(notificationsEnabled = false, category = NotificationGate.CATEGORY_NAVIGATION),
        )
    }

    @Test
    fun totalSilenceDndAlsoBlocksSilentMessages() {
        assertDropped(
            NotificationDrop.DO_NOT_DISTURB,
            decide(importance = NotificationGate.IMPORTANCE_LOW, doNotDisturbNone = true),
        )
    }
}
