package com.zenith.nothingisland.overlay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    /** Compact optical margins: icons never sit on the shell edge or under the lens. */
    const val COMPACT_EDGE_PADDING_DP = 8f
    const val COMPACT_ICON_SIZE_DP = 20f
    const val SATELLITE_SIZE_DP = 32f
    const val SATELLITE_ICON_PADDING_DP = 3f

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

    /** A compact content region in dp. The renderer converts it to a canvas clip. */
    data class CompactRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    data class CompactAreas(
        val leading: CompactRegion?,
        val trailing: CompactRegion?,
        val single: CompactRegion,
    ) {
        val split: Boolean get() = leading != null && trailing != null
    }

    /**
     * Safe compact regions: outer edge padding on both sides, lens clearance on the sensor row.
     * If the lens leaves only one usable side, all content moves there; it never falls back to a
     * region that crosses the lens.
     */
    fun compactAreas(
        widthDp: Float,
        heightDp: Float,
        edgePaddingDp: Float,
        holeEnabled: Boolean,
        holeCenterXDp: Float,
        holeCenterYDp: Float,
        holeRadiusDp: Float,
        sensorClearanceDp: Float,
    ): CompactAreas {
        val pad = edgePaddingDp.coerceAtMost(widthDp / 2f)
        val verticalPad = min(edgePaddingDp, heightDp * 0.18f)
        val top = verticalPad
        val bottom = heightDp - verticalPad
        val left = pad
        val right = widthDp - pad
        val whole = CompactRegion(left, top, right, bottom)
        if (!holeEnabled || holeRadiusDp <= 0f) return CompactAreas(null, null, whole)

        val holeLeft = holeCenterXDp - holeRadiusDp - sensorClearanceDp
        val holeRight = holeCenterXDp + holeRadiusDp + sensorClearanceDp
        val intersectsVertically = holeCenterYDp + holeRadiusDp > top && holeCenterYDp - holeRadiusDp < bottom
        if (!intersectsVertically || holeRight <= left || holeLeft >= right) {
            return CompactAreas(null, null, whole)
        }
        val leading = CompactRegion(left, top, holeLeft.coerceIn(left, right), bottom)
        val trailing = CompactRegion(holeRight.coerceIn(left, right), top, right, bottom)
        return when {
            leading.width > 0f && trailing.width > 0f -> CompactAreas(leading, trailing, whole)
            leading.width > 0f -> CompactAreas(null, null, leading)
            trailing.width > 0f -> CompactAreas(null, null, trailing)
            else -> CompactAreas(null, null, CompactRegion(left, top, left, bottom))
        }
    }

    /** Fit an icon inside the safe region instead of forcing a minimum that clips at 24 dp. */
    fun fitIconSizeDp(requestedDp: Float, availableWidthDp: Float, availableHeightDp: Float): Float =
        min(requestedDp, min(availableWidthDp, availableHeightDp)).coerceAtLeast(0f)

    /** Square app icon inside a circular bubble: the corners must clear the circle, not just the box. */
    fun satelliteIconSizeDp(diameterDp: Float, paddingDp: Float, strokeDp: Float): Float {
        val radius = (diameterDp / 2f - paddingDp - strokeDp).coerceAtLeast(0f)
        return radius * sqrt(2f)
    }

    data class SatellitePlacement(val leftDp: Float, val topDp: Float, val sizeDp: Float, val fits: Boolean)

    /** Keep the configured gap and screen margin; an edge-anchored wide pill must not overlap the bubble. */
    fun satellitePlacement(
        compactLeftDp: Float,
        compactWidthDp: Float,
        compactHeightDp: Float,
        gapDp: Float,
        sizeDp: Float,
        screenWidthDp: Float,
        screenMarginDp: Float = SCREEN_MARGIN_DP,
    ): SatellitePlacement {
        val desired = compactLeftDp + compactWidthDp + gapDp
        val maxLeft = screenWidthDp - screenMarginDp - sizeDp
        val fits = desired <= maxLeft && sizeDp > 0f
        val left = if (fits) desired else maxLeft.coerceAtLeast(compactLeftDp + compactWidthDp + gapDp)
        return SatellitePlacement(left, (compactHeightDp - sizeDp) / 2f, sizeDp, fits)
    }
