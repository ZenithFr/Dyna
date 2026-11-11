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

    /**
     * True when two emissions differ only in a value that is DRAWN, not laid out: a countdown's
     * remaining time or a playing track's position. The pill can repaint those from the stored
     * snapshot, so the window is never re-measured and no accessibility event is fired for them.
     * Anything structural (pause/resume, a new card, a label change) still needs the full render.
     */
    fun isTickOnly(previous: IslandEvent?, next: IslandEvent?): Boolean {
        if (previous == null || next == null) return false
        if (previous.source != next.source || previous.expanded != next.expanded) return false
        val before = previous.content
        val after = next.content
        if (before::class != after::class) return false
        if (identityKey(before) != identityKey(after)) return false
        val neutralised = when (before) {
            // A countdown tick; the deadline is derived from the remaining time.
            is IslandContent.Timer -> before.copy(
                remainingMs = (after as IslandContent.Timer).remainingMs,
                endTimeElapsedMs = after.endTimeElapsedMs,
            )
            is IslandContent.ClockActivity -> before.copy(
                timeText = (after as IslandContent.ClockActivity).timeText,
            )
            is IslandContent.Media -> if (next.expanded) {
                before.copy(
                    positionMs = (after as IslandContent.Media).positionMs,
                    positionUpdatedAtElapsedMs = after.positionUpdatedAtElapsedMs,
                )
            } else {
                // The compact media pill draws no position; isMediaPositionTick already no-ops it.
                return false
            }
            else -> return false
        }
        return previous.copy(content = neutralised) == next
    }

    /** Stable card identity, mirroring what the pill treats as one activity (its contentKey). */
    private fun identityKey(content: IslandContent): String = when (content) {
        is IslandContent.Media -> "media:${content.title}:${content.artist}"
        is IslandContent.ClockActivity -> "clock:${content.key}"
        is IslandContent.Timer -> "timer"
        else -> content::class.qualifiedName ?: content::class.java.name
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
