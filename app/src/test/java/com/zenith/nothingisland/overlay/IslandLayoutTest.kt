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

    @Test
    fun compactRegionsRespectEdgePaddingAndLensClearance() {
        val areas = IslandLayout.compactAreas(110f, 32f, 8f, true, 55f, 20f, 8f, 4f)
        assertTrue(areas.split)
        assertEquals(8f, areas.leading!!.left, 0.01f)
        assertEquals(43f, areas.leading.right, 0.01f)
        assertEquals(67f, areas.trailing!!.left, 0.01f)
        assertEquals(102f, areas.trailing.right, 0.01f)
    }

    @Test
    fun compactNeverFallsBackAcrossTheLens() {
        val areas = IslandLayout.compactAreas(28f, 24f, 8f, true, 14f, 12f, 12f, 4f)
        assertTrue(!areas.split || areas.single.width <= 0f)
        assertTrue(areas.single.width <= 12f)
    }

    @Test
    fun iconsShrinkInsteadOfClippingAtSmallHeights() {
        assertEquals(12f, IslandLayout.fitIconSizeDp(20f, 30f, 12f), 0.01f)
        assertEquals(0f, IslandLayout.fitIconSizeDp(20f, 0f, 12f), 0.01f)
        val icon = IslandLayout.satelliteIconSizeDp(32f, 3f, 0.5f)
        assertTrue(icon > 0f)
        assertTrue(icon * kotlin.math.sqrt(2f) / 2f + 3.5f <= 16f)
    }

    @Test
    fun satelliteKeepsItsGapOrReportsNoRoom() {
        val fits = IslandLayout.satellitePlacement(141.5f, 110f, 32f, 8f, 32f, 393f)
        assertTrue(fits.fits)
        assertEquals(259.5f, fits.leftDp, 0.01f)
        val crowded = IslandLayout.satellitePlacement(93f, 300f, 32f, 8f, 32f, 393f)
        assertTrue(!crowded.fits)
        assertTrue(crowded.leftDp >= 401f)
    }
}
