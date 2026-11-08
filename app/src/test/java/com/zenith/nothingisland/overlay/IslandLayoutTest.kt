package com.zenith.nothingisland.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLayoutTest {
    @Test
    fun expandedWidthFollowsAppleScreenClassesAndCapsAt408() {
        assertEquals(371f, IslandLayout.expandedWidthDp(393f), 0.01f)
        assertEquals(408f, IslandLayout.expandedWidthDp(430f), 0.01f)
        assertEquals(408f, IslandLayout.expandedWidthDp(600f), 0.01f)
    }

    @Test
    fun narrowDisplaysNeverOverflow() {
        assertEquals(258f, IslandLayout.expandedWidthDp(280f), 0.01f)
        assertEquals(258f, IslandLayout.compactWidthDp(300f, 280f), 0.01f)
        assertEquals(110f, IslandLayout.compactWidthDp(110f, 393f), 0.01f)
    }

    @Test
    fun defaultLensReservesAFullWidthLowerRegion() {
        val top = IslandLayout.expandedContentTopDp(true, 26f, 8f)
        assertEquals(38f, top, 0.01f)
        val height = IslandLayout.expandedHeightDp(32f, IslandLayout.MEDIA_CONTENT_HEIGHT_DP, true, 26f, 8f)
        assertEquals(158f, height, 0.01f)
        assertTrue(top >= 26f + 8f + IslandLayout.SENSOR_CLEARANCE_DP)
        assertTrue(height - IslandLayout.CONTENT_MARGIN_DP - top >= IslandLayout.MEDIA_CONTENT_HEIGHT_DP)
    }

    @Test
    fun extremeCalibrationGrowsInsteadOfCoveringTheLens() {
        val height = IslandLayout.expandedHeightDp(32f, IslandLayout.TIMER_CONTENT_HEIGHT_DP, true, 60f, 20f)
        val top = IslandLayout.expandedContentTopDp(true, 60f, 20f)
        assertEquals(84f, top, 0.01f)
        assertTrue(height - IslandLayout.CONTENT_MARGIN_DP - top >= IslandLayout.TIMER_CONTENT_HEIGHT_DP)
    }

    @Test
    fun disabledHoleUsesTheNormalMargin() {
        assertEquals(12f, IslandLayout.expandedContentTopDp(false, 60f, 20f), 0.01f)
    }
}
