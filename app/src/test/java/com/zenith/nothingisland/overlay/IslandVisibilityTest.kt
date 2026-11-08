package com.zenith.nothingisland.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandVisibilityTest {
    @Test
    fun landscapeSuppressionWinsAndIsIndependentOfPortraitLock() {
        assertEquals(
            IslandSuppression.LANDSCAPE,
            IslandVisibilityPolicy.resolve(true, true, landscape = true, statusBarVisible = true),
        )
        assertEquals(
            IslandSuppression.NONE,
            IslandVisibilityPolicy.resolve(false, true, landscape = true, statusBarVisible = true),
        )
    }

    @Test
    fun fullscreenMeansStatusBarHiddenNotEdgeToEdgeOrNavigationOnly() {
        assertEquals(
            IslandSuppression.FULLSCREEN,
            IslandVisibilityPolicy.resolve(true, true, landscape = false, statusBarVisible = false),
        )
        assertEquals(
            IslandSuppression.NONE,
            IslandVisibilityPolicy.resolve(true, true, landscape = false, statusBarVisible = true),
        )
        assertEquals(
            IslandSuppression.NONE,
            IslandVisibilityPolicy.resolve(true, false, landscape = false, statusBarVisible = false),
        )
    }

    @Test
    fun unknownInsetsNeverSuppressByThemselves() {
        assertEquals(
            IslandSuppression.NONE,
            IslandVisibilityPolicy.resolve(true, true, landscape = false, statusBarVisible = null),
        )
    }
}
