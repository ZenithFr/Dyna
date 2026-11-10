package com.zenith.nothingisland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandSettingsTest {
    @Test
    fun persistedValuesAreClamped() {
        val settings = IslandSettings(widthDp = 999, heightDp = 0, holeRadiusDp = 99, idleContractDelaySec = -4).coerced()
        assertEquals(IslandSettings.WIDTH_RANGE.last, settings.widthDp)
        assertEquals(IslandSettings.HEIGHT_RANGE.first, settings.heightDp)
        assertEquals(IslandSettings.HOLE_RADIUS_RANGE.last, settings.holeRadiusDp)
        assertEquals(IslandSettings.IDLE_CONTRACT_DELAY_RANGE.first, settings.idleContractDelaySec)
    }

    @Test
    fun detachedBubbleKnobsAreClampedAndDefaultOn() {
        val defaults = IslandSettings()
        assertTrue(defaults.detachedAppIcon)
        assertEquals(IslandSettings.DEFAULT_SATELLITE_GAP_DP, defaults.satelliteGapDp)
        val settings = IslandSettings(satelliteGapDp = 99).coerced()
        assertEquals(IslandSettings.SATELLITE_GAP_RANGE.last, settings.satelliteGapDp)
        assertEquals(
            IslandSettings.SATELLITE_GAP_RANGE.first,
            IslandSettings(satelliteGapDp = 0).coerced().satelliteGapDp,
        )
    }

    @Test
    fun visibilityAndCompactLayoutDefaultsAreIntentionalAndClamped() {
        val defaults = IslandSettings()
        assertTrue(defaults.hideInLandscape)
        assertTrue(defaults.hideInFullscreen)
        assertFalse(defaults.suppressDuringDnd)
        assertEquals(IslandSettings.DEFAULT_COMPACT_ICON_SIZE_DP, defaults.compactIconSizeDp)
        assertEquals(IslandSettings.DEFAULT_COMPACT_EDGE_PADDING_DP, defaults.compactEdgePaddingDp)
        assertEquals(IslandSettings.DEFAULT_SATELLITE_SIZE_DP, defaults.satelliteSizeDp)
        val settings = IslandSettings(
            compactIconSizeDp = 99,
            compactEdgePaddingDp = 0,
            satelliteSizeDp = 99,
            satelliteIconPaddingDp = -1,
        ).coerced()
        assertEquals(IslandSettings.COMPACT_ICON_SIZE_RANGE.last, settings.compactIconSizeDp)
        assertEquals(IslandSettings.COMPACT_EDGE_PADDING_RANGE.first, settings.compactEdgePaddingDp)
        assertEquals(IslandSettings.SATELLITE_SIZE_RANGE.last, settings.satelliteSizeDp)
        assertEquals(IslandSettings.SATELLITE_ICON_PADDING_RANGE.first, settings.satelliteIconPaddingDp)
    }

    @Test
    fun ambientLoopsAreOffByDefaultAndRateIsClamped() {
        val defaults = IslandSettings()
        assertFalse(defaults.ambientAnimationsEnabled)
        assertEquals(30, defaults.ambientFrameRateHz)
        val settings = IslandSettings(ambientFrameRateHz = 999).coerced()
        assertEquals(IslandSettings.AMBIENT_FRAME_RATE_RANGE.last, settings.ambientFrameRateHz)
    }

    @Test
    fun batteryFirstAndAlertBubbleDefaultsAreIntentional() {
        val defaults = IslandSettings()
        assertEquals(IslandIdleBehaviour.HIDDEN, defaults.idleBehaviour)
        assertEquals(IslandTapAction.EXPAND, defaults.tapAction)
        assertEquals(IslandLongPressAction.OPEN_APP, defaults.longPressAction)
        // The requested alert presentation is on out of the box.
        assertTrue(defaults.alertIconInBubble)
        assertTrue(defaults.notificationShowTitle)
        assertTrue(defaults.showSilentNotifications)
        assertTrue(defaults.motionEnabled)
        assertTrue(defaults.hapticsEnabled)
        assertFalse(defaults.monochromeIcons)
        assertFalse(defaults.verboseLogging)
        assertEquals(100, defaults.shellOpacityPercent)
        assertEquals(IslandSettings.DEFAULT_SHELL_COLOR_ARGB, defaults.shellColorArgb)
    }

    @Test
    fun silentNotificationOptOutSurvivesCoercion() {
        val settings = IslandSettings(showSilentNotifications = false).coerced()
        assertFalse(settings.showSilentNotifications)
        assertTrue(settings.notificationsEnabled)
    }

    @Test
    fun shellOpacityIsClamped() {
        assertEquals(
            IslandSettings.SHELL_OPACITY_RANGE.first,
            IslandSettings(shellOpacityPercent = 0).coerced().shellOpacityPercent,
        )
        assertEquals(
            IslandSettings.SHELL_OPACITY_RANGE.last,
            IslandSettings(shellOpacityPercent = 250).coerced().shellOpacityPercent,
        )
    }

    @Test
    fun bubbleHoldDefaultsToThreeSecondsAndIsClamped() {
        assertEquals(3, IslandSettings().bubbleDurationSec)
        assertEquals(1, IslandSettings(bubbleDurationSec = 0).coerced().bubbleDurationSec)
        assertEquals(10, IslandSettings(bubbleDurationSec = 99).coerced().bubbleDurationSec)
        assertTrue(IslandSettings().clockEnabled)
    }

    @Test
    fun powerSaveHoldDefaultsToFourSecondsAndIsClamped() {
        assertEquals(IslandSettings.DEFAULT_POWER_SAVE_ALERT_DURATION_SEC, IslandSettings().powerSaveAlertDurationSec)
        assertEquals(
            IslandSettings.ALERT_DURATION_RANGE.first,
            IslandSettings(powerSaveAlertDurationSec = 0).coerced().powerSaveAlertDurationSec,
        )
        assertEquals(
            IslandSettings.ALERT_DURATION_RANGE.last,
            IslandSettings(powerSaveAlertDurationSec = 99).coerced().powerSaveAlertDurationSec,
        )
    }

    @Test
    fun placementKeepsThePillOnScreen() {
        val settings = IslandSettings(offsetXDp = 24)
        assertEquals(165.5f, settings.pillLeftDp(393f, 110f), 0.01f)
        assertEquals(0f, settings.copy(offsetXDp = -24).pillLeftDp(393f, 393f), 0.01f)
    }
}
