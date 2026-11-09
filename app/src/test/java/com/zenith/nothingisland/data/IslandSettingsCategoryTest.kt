package com.zenith.nothingisland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every page owns a reset scope; a knob must never be reset by an unrelated page. */
class IslandSettingsCategoryTest {

    private val customized = IslandSettings(
        widthDp = 200,
        heightDp = 40,
        shellColorArgb = 0xFF101010.toInt(),
        shellOpacityPercent = 80,
        textColorArgb = 0xFFEEEEEE.toInt(),
        monochromeIcons = true,
        holeCenterYDp = 30,
        holeRadiusDp = 10,
        idleBehaviour = IslandIdleBehaviour.PILL,
        idleContractDelaySec = 7,
        tapAction = IslandTapAction.OPEN_APP,
        longPressAction = IslandLongPressAction.NONE,
        hapticsEnabled = false,
        alertIconInBubble = false,
        notificationShowTitle = false,
        notificationDurationSec = 8,
        mediaEnabled = false,
        motionEnabled = false,
        ambientAnimationsEnabled = true,
        ambientFrameRateHz = 90,
        verboseLogging = true,
        disabledPackages = setOf("com.example.app"),
    )

    @Test
    fun appearanceResetKeepsLayoutBehaviourAndSources() {
        val reset = customized.resetCategory(IslandSettingsCategory.APPEARANCE)
        assertEquals(IslandSettings.DEFAULT_SHELL_COLOR_ARGB, reset.shellColorArgb)
        assertEquals(IslandSettings.DEFAULT_SHELL_OPACITY_PERCENT, reset.shellOpacityPercent)
        assertEquals(IslandSettings.DEFAULT_TEXT_COLOR_ARGB, reset.textColorArgb)
        assertTrue(!reset.monochromeIcons)
        // Untouched scopes.
        assertEquals(customized.widthDp, reset.widthDp)
        assertEquals(customized.holeRadiusDp, reset.holeRadiusDp)
        assertEquals(customized.idleBehaviour, reset.idleBehaviour)
        assertEquals(customized.mediaEnabled, reset.mediaEnabled)
        assertEquals(customized.ambientFrameRateHz, reset.ambientFrameRateHz)
    }

    @Test
    fun behaviourResetKeepsAppearanceAndNotificationSettings() {
        val reset = customized.resetCategory(IslandSettingsCategory.BEHAVIOUR)
        assertEquals(IslandIdleBehaviour.HIDDEN, reset.idleBehaviour)
        assertEquals(IslandTapAction.EXPAND, reset.tapAction)
        assertEquals(IslandLongPressAction.OPEN_APP, reset.longPressAction)
        assertTrue(reset.hapticsEnabled)
        assertEquals(IslandSettings.DEFAULT_IDLE_CONTRACT_DELAY_SEC, reset.idleContractDelaySec)
        assertEquals(customized.shellColorArgb, reset.shellColorArgb)
        assertEquals(customized.notificationDurationSec, reset.notificationDurationSec)
        assertEquals(customized.alertIconInBubble, reset.alertIconInBubble)
    }

    @Test
    fun notificationResetClearsAppOptOutsAndAlertContent() {
        val reset = customized.resetCategory(IslandSettingsCategory.NOTIFICATIONS)
        assertTrue(reset.disabledPackages.isEmpty())
        assertTrue(reset.alertIconInBubble)
        assertTrue(reset.notificationShowTitle)
        assertEquals(IslandSettings.DEFAULT_NOTIFICATION_DURATION_SEC, reset.notificationDurationSec)
        // Sources are a different page.
        assertEquals(customized.mediaEnabled, reset.mediaEnabled)
    }

    @Test
    fun motionResetDoesNotTouchIdleBehaviour() {
        val reset = customized.resetCategory(IslandSettingsCategory.MOTION)
        assertTrue(reset.motionEnabled)
        assertTrue(!reset.ambientAnimationsEnabled)
        assertEquals(IslandSettings.DEFAULT_AMBIENT_FRAME_RATE_HZ, reset.ambientFrameRateHz)
        assertEquals(customized.idleBehaviour, reset.idleBehaviour)
    }

    @Test
    fun cameraHoleResetKeepsShellGeometry() {
        val reset = customized.resetCategory(IslandSettingsCategory.CAMERA_HOLE)
        assertEquals(IslandSettings.DEFAULT_HOLE_RADIUS_DP, reset.holeRadiusDp)
        assertEquals(IslandSettings.DEFAULT_HOLE_CENTER_Y_DP, reset.holeCenterYDp)
        assertEquals(customized.widthDp, reset.widthDp)
        assertEquals(customized.heightDp, reset.heightDp)
    }

    @Test
    fun setupHasNoPersistedKnobsAndEveryResetIsIdempotent() {
        assertEquals(customized, customized.resetCategory(IslandSettingsCategory.SETUP))
        IslandSettingsCategory.values().forEach { category ->
            val once = customized.resetCategory(category)
            assertEquals(category.name, once, once.resetCategory(category))
        }
    }

    @Test
    fun resettingEveryCategoryRestoresDefaults() {
        val reset = IslandSettingsCategory.values().fold(customized) { acc, category ->
            acc.resetCategory(category)
        }
        assertEquals(IslandSettings(), reset)
        assertNotEquals(customized, reset)
    }
}
