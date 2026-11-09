package com.zenith.nothingisland.overlay

import com.zenith.nothingisland.data.IslandIdleBehaviour

/**
 * Lifecycle of an island with nothing to show.
 *
 * [CONTENT] is a live activity. [LINGERING] is the short grace window after the last activity ends,
 * during which the shell is still on screen and then curls into the lens. [DORMANT] means the curl
 * finished and the surface was detached — the island is *gone*, not merely transparent.
 */
enum class IslandIdlePhase { CONTENT, LINGERING, DORMANT }

/**
 * Pure idle rules. The overlay used to re-arm the linger countdown on every unrelated refresh
 * (inset dispatch when an app opened/closed, display callbacks caused by its own frame-rate hint,
 * a settings emission), which resurrected a detached idle pill over and over: a visible on/off
 * pulse every couple of seconds and a permanent wake/frame source. A dormant island may only be
 * woken by real content, by the always-visible setting, or by lens calibration.
 */
object IslandIdlePolicy {

    /** Phase after a holder emission. Only a content -> idle transition starts a new linger. */
    fun onEvent(hasContent: Boolean, previous: IslandIdlePhase): IslandIdlePhase = when {
        hasContent -> IslandIdlePhase.CONTENT
        previous == IslandIdlePhase.CONTENT -> IslandIdlePhase.LINGERING
        else -> previous
    }

    /** Time left in the linger window; 0 means "curl now". */
    fun lingerRemainingMs(idleSinceMs: Long, nowMs: Long, delayMs: Long): Long =
        if (idleSinceMs <= 0L) delayMs else (delayMs - (nowMs - idleSinceMs)).coerceAtLeast(0L)

    /** True when an island with no content is allowed to hold a window at all. */
    fun idleAttachAllowed(
        phase: IslandIdlePhase,
        behaviour: IslandIdleBehaviour,
        calibrating: Boolean,
    ): Boolean = when {
        calibrating -> true
        behaviour != IslandIdleBehaviour.HIDDEN -> true
        else -> phase == IslandIdlePhase.LINGERING
    }

    /** True when the settled curl should release the surface instead of keeping it attached. */
    fun shouldDetachIdle(
        hasContent: Boolean,
        dormantSettled: Boolean,
        behaviour: IslandIdleBehaviour,
        calibrating: Boolean,
    ): Boolean = !hasContent && dormantSettled && !idleAttachAllowed(IslandIdlePhase.DORMANT, behaviour, calibrating)
}
