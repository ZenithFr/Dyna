package com.zenith.nothingisland.data

import org.junit.Assert.assertEquals
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
    fun placementKeepsThePillOnScreen() {
        val settings = IslandSettings(offsetXDp = 24)
        assertEquals(165.5f, settings.pillLeftDp(393f, 110f), 0.01f)
        assertEquals(0f, settings.copy(offsetXDp = -24).pillLeftDp(393f, 393f), 0.01f)
    }
}
