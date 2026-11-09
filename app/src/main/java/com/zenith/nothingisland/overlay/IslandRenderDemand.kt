package com.zenith.nothingisland.overlay

import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandEvent

/**
 * Pure render-demand rules shared by the overlay and JVM tests. The island should ask the panel
 * for frames only while something is visibly changing; a settled, dormant, or hidden island must
 * not keep a frame loop or high-refresh hint alive.
 */
object IslandRenderDemand {

    /** Decorative loops are opt-in and never run while the shell is curled behind the lens. */
    fun ambientLoopsEnabled(
        motionAllowed: Boolean,
        userEnabled: Boolean,
        dormant: Boolean,
    ): Boolean = motionAllowed && userEnabled && !dormant

    /**
     * True when two emissions differ only by a playing track's position stamp.
     *
     * Many players publish a playback-state callback about once a second. The compact pill draws no
     * position at all, so those used to re-measure the window, re-place the touch window and fire an
     * accessibility event every second for nothing. The expanded card still needs them: its progress
     * row is drawn from the stored stamp.
     */
    fun isMediaPositionTick(previous: IslandEvent?, next: IslandEvent?): Boolean {
        if (previous == null || next == null || next.expanded) return false
        if (previous.source != next.source || previous.expanded != next.expanded) return false
        val before = previous.content as? IslandContent.Media ?: return false
        val after = next.content as? IslandContent.Media ?: return false
        return before.copy(
            positionMs = after.positionMs,
            positionUpdatedAtElapsedMs = after.positionUpdatedAtElapsedMs,
        ) == after
    }

    /** 0 means "no preference / let the panel idle"; ambient loops never pin the morph rate. */
    fun motionRateHz(
        active: Boolean,
        ambientOnly: Boolean,
        motionAllowed: Boolean,
        ambientFrameRateHz: Int,
        highQualityMotion: Boolean,
    ): Float = when {
        !active || !motionAllowed -> 0f
        ambientOnly -> ambientFrameRateHz.coerceIn(1, 120).toFloat()
        highQualityMotion -> 120f
        else -> 90f
    }
}
