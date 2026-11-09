package com.zenith.nothingisland.overlay

import com.zenith.nothingisland.data.IslandIdleBehaviour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the "island pops in and out every few seconds" bug: a dormant island was
 * resurrected by every unrelated refresh, which re-armed the linger and restarted the curl.
 */
class IslandIdlePolicyTest {

    @Test
    fun contentKeepsTheIslandAlive() {
        assertEquals(
            IslandIdlePhase.CONTENT,
            IslandIdlePolicy.onEvent(hasContent = true, previous = IslandIdlePhase.DORMANT),
        )
        assertEquals(
            IslandIdlePhase.CONTENT,
            IslandIdlePolicy.onEvent(hasContent = true, previous = IslandIdlePhase.LINGERING),
        )
    }

    @Test
    fun onlyTheContentToIdleEdgeStartsALinger() {
        assertEquals(
            IslandIdlePhase.LINGERING,
            IslandIdlePolicy.onEvent(hasContent = false, previous = IslandIdlePhase.CONTENT),
        )
        // Repeated idle emissions must not restart the countdown.
        assertEquals(
            IslandIdlePhase.LINGERING,
            IslandIdlePolicy.onEvent(hasContent = false, previous = IslandIdlePhase.LINGERING),
        )
        assertEquals(
            IslandIdlePhase.DORMANT,
            IslandIdlePolicy.onEvent(hasContent = false, previous = IslandIdlePhase.DORMANT),
        )
    }

    @Test
    fun dormantIslandIsNeverAttachedByARefresh() {
        assertFalse(
            IslandIdlePolicy.idleAttachAllowed(
                phase = IslandIdlePhase.DORMANT,
                behaviour = IslandIdleBehaviour.HIDDEN,
                calibrating = false,
            ),
        )
        assertTrue(
            IslandIdlePolicy.idleAttachAllowed(
                phase = IslandIdlePhase.LINGERING,
                behaviour = IslandIdleBehaviour.HIDDEN,
                calibrating = false,
            ),
        )
    }

    @Test
    fun alwaysVisibleShellAndLensCalibrationMayHoldAWindow() {
        assertTrue(
            IslandIdlePolicy.idleAttachAllowed(
                phase = IslandIdlePhase.DORMANT,
                behaviour = IslandIdleBehaviour.PILL,
                calibrating = false,
            ),
        )
        assertTrue(
            IslandIdlePolicy.idleAttachAllowed(
                phase = IslandIdlePhase.DORMANT,
                behaviour = IslandIdleBehaviour.HIDDEN,
                calibrating = true,
            ),
        )
    }

    @Test
    fun lingerCountsDownFromTheMomentContentEnded() {
        assertEquals(4_000L, IslandIdlePolicy.lingerRemainingMs(idleSinceMs = 0L, nowMs = 99_000L, delayMs = 4_000L))
        assertEquals(2_500L, IslandIdlePolicy.lingerRemainingMs(idleSinceMs = 1_000L, nowMs = 2_500L, delayMs = 4_000L))
        assertEquals(0L, IslandIdlePolicy.lingerRemainingMs(idleSinceMs = 1_000L, nowMs = 9_000L, delayMs = 4_000L))
    }

    @Test
    fun settledCurlReleasesTheSurfaceOnlyWhenIdleAndHidden() {
        assertTrue(
            IslandIdlePolicy.shouldDetachIdle(
                hasContent = false,
                dormantSettled = true,
                behaviour = IslandIdleBehaviour.HIDDEN,
                calibrating = false,
            ),
        )
        assertFalse(
            IslandIdlePolicy.shouldDetachIdle(
                hasContent = true,
                dormantSettled = true,
                behaviour = IslandIdleBehaviour.HIDDEN,
                calibrating = false,
            ),
        )
        assertFalse(
            IslandIdlePolicy.shouldDetachIdle(
                hasContent = false,
                dormantSettled = false,
                behaviour = IslandIdleBehaviour.HIDDEN,
                calibrating = false,
            ),
        )
        assertFalse(
            IslandIdlePolicy.shouldDetachIdle(
                hasContent = false,
                dormantSettled = true,
                behaviour = IslandIdleBehaviour.PILL,
                calibrating = false,
            ),
        )
        assertFalse(
            IslandIdlePolicy.shouldDetachIdle(
                hasContent = false,
                dormantSettled = true,
                behaviour = IslandIdleBehaviour.HIDDEN,
                calibrating = true,
            ),
        )
    }
}
