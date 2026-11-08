package com.zenith.nothingisland.overlay

import kotlin.math.max
import kotlin.math.min

/**
 * Density-independent layout rules shared by the window and the canvas renderer.
 *
 * Apple's compact presentation places essential content on the leading/trailing sides of the
 * sensor region. Its expanded presentation keeps that relationship near the sensor, then uses the
 * full width **below** the sensor for controls. A punch-hole is much narrower than the TrueDepth
 * region, so the lower region is the useful extra space on this hardware.
 *
 * References: Apple HIG, Live Activities; WWDC23, Design dynamic Live Activities.
 * Nothing Island keeps its documented tap-to-expand gesture and calibrated compact width.
 */
internal object IslandLayout {
    /** Apple's expanded cards sit close to the screen edge (371 pt on a 393 pt screen). */
    const val SCREEN_MARGIN_DP = 11f
    const val MAX_EXPANDED_WIDTH_DP = 408f

    /** Tighter than Apple's 20 pt text margin: this is a small control surface, not a card list. */
    const val CONTENT_MARGIN_DP = 12f
    const val SENSOR_CLEARANCE_DP = 4f

    /** Lower-region budgets, excluding the sensor row and outer margins. */
    const val MEDIA_CONTENT_HEIGHT_DP = 108f
    const val TIMER_CONTENT_HEIGHT_DP = 44f
    const val NOTIFICATION_CONTENT_HEIGHT_DP = 52f
    const val NOTIFICATION_ACTIONS_HEIGHT_DP = 80f
    const val CALL_CONTENT_HEIGHT_DP = 84f
    const val DOWNLOAD_CONTENT_HEIGHT_DP = 92f
    const val NAVIGATION_CONTENT_HEIGHT_DP = 80f

    fun availableWidthDp(screenWidthDp: Float): Float =
        (screenWidthDp - SCREEN_MARGIN_DP * 2f).coerceAtLeast(1f)

    fun compactWidthDp(requestedWidthDp: Float, screenWidthDp: Float): Float =
        min(requestedWidthDp, availableWidthDp(screenWidthDp))

    fun expandedWidthDp(screenWidthDp: Float, maxWidthDp: Float = MAX_EXPANDED_WIDTH_DP): Float =
        min(maxWidthDp, availableWidthDp(screenWidthDp))

    /** First baseline-safe row below the physical lens. */
    fun expandedContentTopDp(
        holeEnabled: Boolean,
        holeCenterYDp: Float,
        holeRadiusDp: Float,
        marginDp: Float = CONTENT_MARGIN_DP,
        clearanceDp: Float = SENSOR_CLEARANCE_DP,
    ): Float {
        if (!holeEnabled) return marginDp
        return max(marginDp, holeCenterYDp + holeRadiusDp + clearanceDp)
    }

    /**
     * Content-aware height. Normal lens calibration lands inside Apple's 84–160 pt expanded
     * range; an unusually low/large calibrated lens grows the card instead of drawing over it.
     */
    fun expandedHeightDp(
        compactHeightDp: Float,
        lowerContentHeightDp: Float,
        holeEnabled: Boolean,
        holeCenterYDp: Float,
        holeRadiusDp: Float,
        marginDp: Float = CONTENT_MARGIN_DP,
        clearanceDp: Float = SENSOR_CLEARANCE_DP,
    ): Float {
        val top = expandedContentTopDp(holeEnabled, holeCenterYDp, holeRadiusDp, marginDp, clearanceDp)
        return max(compactHeightDp, top + lowerContentHeightDp + marginDp)
    }
}
