package com.zenith.nothingisland.data

import org.junit.Assert.assertEquals
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
    fun placementKeepsThePillOnScreen() {
        val settings = IslandSettings(offsetXDp = 24)
        assertEquals(165.5f, settings.pillLeftDp(393f, 110f), 0.01f)
        assertEquals(0f, settings.copy(offsetXDp = -24).pillLeftDp(393f, 393f), 0.01f)
    }
}
