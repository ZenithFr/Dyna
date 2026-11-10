package com.zenith.nothingisland.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IslandStateHolderTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        IslandStateHolder.clearAll()
    }

    @After
    fun tearDown() {
        IslandStateHolder.clearAll()
        Dispatchers.resetMain()
    }

    private fun media(title: String = "Track") = IslandContent.Media(
        title = title, artist = "Artist", albumArt = null, isPlaying = true,
        positionMs = 0L, durationMs = 0L, positionUpdatedAtElapsedMs = 0L, speed = 1f,
    )

    private fun timer(remaining: Long = 60_000L) = IslandContent.Timer("TIMER", remaining, true, 0L)

    private fun notification(key: String) = IslandContent.Notification(
        key = key, packageName = "test", appLabel = "Test", icon = null,
        title = "Title", text = "Body", actions = emptyList(), contentIntent = null,
    )

    private fun shownKey(): String? = (IslandStateHolder.current?.content as? IslandContent.Notification)?.key

    @Test
    fun severalQueuedAlertsRotateUntilTheLastOneIsDismissed() = runTest {
        IslandStateHolder.postNotification(notification("a"))
        IslandStateHolder.postNotification(notification("b"))
        assertEquals("b", shownKey())
        assertEquals(2, (IslandStateHolder.current?.content as IslandContent.Notification).stackCount)

        advanceTimeBy(IslandStateHolder.TRANSIENT_DURATION_MS)
        runCurrent()
        assertEquals("a", shownKey())

        advanceTimeBy(IslandStateHolder.TRANSIENT_DURATION_MS)
        runCurrent()
        assertEquals("b", shownKey())

        IslandStateHolder.clearNotification("a")
        assertEquals(1, (IslandStateHolder.current?.content as IslandContent.Notification).stackCount)
        // The last alert keeps rotating until it is dismissed as well.
        advanceTimeBy(IslandStateHolder.TRANSIENT_DURATION_MS)
        runCurrent()
        assertEquals("b", shownKey())

        IslandStateHolder.clearNotification("b")
        assertNull(IslandStateHolder.current)
    }

    @Test
    fun dismissingAQueuedAlertKeepsTheShownOneVisibleAndRecountsIt() = runTest {
        IslandStateHolder.seedNotification(notification("seeded"))
        IslandStateHolder.postNotification(notification("a"))
        IslandStateHolder.clearNotification("seeded")
        assertEquals("a", shownKey())
        assertEquals(1, (IslandStateHolder.current?.content as IslandContent.Notification).stackCount)
    }

    @Test
    fun seededAlertsNeverPopUpOnTheirOwn() = runTest {
        IslandStateHolder.seedNotification(notification("old-1"))
        IslandStateHolder.seedNotification(notification("old-2"))
        assertNull(IslandStateHolder.current)
    }

    @Test
    fun bubbleRetractsAfterItsOwnDurationWhileTheAlertStaysUp() = runTest {
        IslandStateHolder.updateAlertDurations(
            notificationMs = 6_000L, chargingMs = 4_000L, batteryMs = 4_000L, bubbleMs = 3_000L,
        )
        IslandStateHolder.postNotification(notification("a"))
        assertTrue((IslandStateHolder.current?.content as IslandContent.Notification).showBubble)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(IslandSource.Notification, IslandStateHolder.current?.source)
        assertTrue(!(IslandStateHolder.current?.content as IslandContent.Notification).showBubble)
        // Restore the defaults for the other tests in this class.
        IslandStateHolder.updateAlertDurations(
            notificationMs = IslandStateHolder.TRANSIENT_DURATION_MS, chargingMs = IslandStateHolder.TRANSIENT_DURATION_MS,
            batteryMs = IslandStateHolder.TRANSIENT_DURATION_MS, bubbleMs = IslandStateHolder.BUBBLE_DURATION_MS,
        )
    }

    @Test
    fun timerOutranksMediaAndFallsBackWhenCleared() = runTest {
        IslandStateHolder.postMedia(media())
        IslandStateHolder.postTimer(timer())
        assertEquals(IslandSource.Timer, IslandStateHolder.current?.source)
        IslandStateHolder.clearTimer()
        assertEquals(IslandSource.Media, IslandStateHolder.current?.source)
    }

    @Test
    fun notificationCoversTimerThenHandsItBack() = runTest {
        IslandStateHolder.postTimer(timer())
        IslandStateHolder.postNotification(notification("a"))
        assertEquals(IslandSource.Notification, IslandStateHolder.current?.source)
        advanceTimeBy(IslandStateHolder.TRANSIENT_DURATION_MS)
        runCurrent()
        assertEquals(IslandSource.Timer, IslandStateHolder.current?.source)
    }

    @Test
    fun notificationOutranksCharging() = runTest {
        IslandStateHolder.postCharging(IslandContent.Charging(80, false))
        IslandStateHolder.postNotification(notification("a"))
        assertEquals(IslandSource.Notification, IslandStateHolder.current?.source)
        IslandStateHolder.clearNotification("a")
        assertEquals(IslandSource.Charging, IslandStateHolder.current?.source)
    }

    @Test
    fun staleRemovalCannotClearANewerNotification() = runTest {
        IslandStateHolder.postNotification(notification("a"))
        IslandStateHolder.postNotification(notification("b"))
        IslandStateHolder.clearNotification("a")
        assertEquals("b", (IslandStateHolder.current?.content as? IslandContent.Notification)?.key)
    }

    @Test
    fun expansionSurvivesTimerTicks() = runTest {
        IslandStateHolder.postTimer(timer())
        IslandStateHolder.toggleExpanded()
        IslandStateHolder.postTimer(timer(59_000L))
        assertTrue(IslandStateHolder.current?.expanded == true)
    }

    @Test
    fun interactingWithNotificationRearmsExpiry() = runTest {
        IslandStateHolder.postNotification(notification("a"))
        advanceTimeBy(3_000L)
        runCurrent()
        IslandStateHolder.toggleExpanded()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(IslandSource.Notification, IslandStateHolder.current?.source)
        advanceTimeBy(2_000L)
        runCurrent()
        assertNull(IslandStateHolder.current)
    }

    private fun call(key: String = "call") = IslandContent.Call(
        key = key, packageName = "test", appLabel = "Phone", icon = null, caller = "Caller",
        isVideo = false, isIncoming = true, answerIntent = null, declineIntent = null,
        hangUpIntent = null, contentIntent = null, answerColor = 0, declineColor = 0,
    )

    @Test
    fun callOutranksNotificationAndPersistentSources() = runTest {
        IslandStateHolder.postTimer(timer())
        IslandStateHolder.postNotification(notification("a"))
        IslandStateHolder.postCall(call())
        assertEquals(IslandSource.Call, IslandStateHolder.current?.source)
        IslandStateHolder.clearCall("call")
        assertEquals(IslandSource.Notification, IslandStateHolder.current?.source)
    }

    @Test
    fun navigationOutranksDownloadAndMedia() = runTest {
        IslandStateHolder.postMedia(media())
        IslandStateHolder.postDownload(
            IslandContent.Download("d", "test", "Store", null, "Download", "", emptyList(), null),
        )
        IslandStateHolder.postNavigation(
            IslandContent.Navigation("n", "test", "Maps", null, "Turn left", "100 m", null),
        )
        assertEquals(IslandSource.Navigation, IslandStateHolder.current?.source)
        IslandStateHolder.clearNavigation("n")
        assertEquals(IslandSource.Download, IslandStateHolder.current?.source)
        IslandStateHolder.clearDownload("d")
        assertEquals(IslandSource.Media, IslandStateHolder.current?.source)
    }

    @Test
    fun batteryAlertsExpireBackToBase() = runTest {
        IslandStateHolder.postMedia(media())
        IslandStateHolder.postBatteryLow(IslandContent.BatteryLow(15))
        assertEquals(IslandSource.BatteryLow, IslandStateHolder.current?.source)
        advanceTimeBy(IslandStateHolder.TRANSIENT_DURATION_MS)
        runCurrent()
        assertEquals(IslandSource.Media, IslandStateHolder.current?.source)
    }
}
