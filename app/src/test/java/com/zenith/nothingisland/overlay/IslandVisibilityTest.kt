package com.zenith.nothingisland.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandVisibilityTest {
    @Test
    fun screenOffSuppressionWinsEvenWhenOtherHidingIsDisabled() {
        assertEquals(
            IslandSuppression.SCREEN_OFF,
            IslandVisibilityPolicy.resolve(false, false, landscape = true, statusBarVisible = false, screenOn = false),
        )
        assertEquals(
            IslandSuppression.SCREEN_OFF,
            IslandVisibilityPolicy.resolve(true, true, landscape = false, statusBarVisible = true, screenOn = false),
        )
    }

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

    @Test
    fun uncalibratedHiddenProbeFailsOpen() {
        val gate = StatusBarVisibilityGate()
        assertFalse(gate.calibrated)
        assertNull(gate.report(false))
        assertNull(gate.report(null))
        assertEquals(
            IslandSuppression.NONE,
            IslandVisibilityPolicy.resolve(true, true, landscape = false, statusBarVisible = gate.report(false)),
        )
    }

    @Test
    fun probeCanSuppressOnlyAfterObservingVisibleStatusBar() {
        val gate = StatusBarVisibilityGate()
        assertNull(gate.report(false))
        assertEquals(true, gate.report(true))
        assertTrue(gate.calibrated)
        assertEquals(false, gate.report(false))
        assertEquals(
            IslandSuppression.FULLSCREEN,
            IslandVisibilityPolicy.resolve(true, true, landscape = false, statusBarVisible = gate.report(false)),
        )
        assertEquals(true, gate.report(true))
    }

    @Test
    fun probeResetReturnsToFailOpen() {
        val gate = StatusBarVisibilityGate()
        gate.report(true)
        gate.report(false)
        gate.reset()
        assertFalse(gate.calibrated)
        assertNull(gate.report(false))
    }
}
