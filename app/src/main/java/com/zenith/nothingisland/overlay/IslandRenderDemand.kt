package com.zenith.nothingisland.overlay

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

    /** Once the idle curl lands with no activity, detach the surface instead of compositing black. */
    fun shouldDetachIdle(hasContent: Boolean, dormantSettled: Boolean): Boolean =
        !hasContent && dormantSettled

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
