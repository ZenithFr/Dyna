package com.zenith.nothingisland.data

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/** Where the camera hole sits inside the pill. */
enum class IslandHoleMode { RATIO, ONE_SIDE, SYMMETRIC }

/** Which pill edge holds the hole when [IslandHoleMode.ONE_SIDE] is selected. */
enum class IslandHoleSide { LEFT, RIGHT }

/** Outline of the black island shell. */
enum class IslandShape { PILL, NOTCH }

/** Camera-hole position in dp, relative to the portrait screen (top-left origin). */
data class HoleGeometry(val centerXDp: Float, val centerYDp: Float, val radiusDp: Float)

/**
 * Left edge of the island in dp for a portrait screen [screenWidthDp] wide.
 *
 * The placement rules intentionally keep the hole either centred (SYMMETRIC), at a proportional
 * position inside the body (RATIO), or flush with one edge (ONE_SIDE). [IslandSettings.offsetXDp]
 * is applied afterwards as a fine nudge and the result is clamped so the pill stays on screen.
 */
fun IslandSettings.pillLeftDp(screenWidthDp: Float, pillWidthDp: Float): Float {
    val holeX = screenWidthDp / 2f + holeCenterXDp
    val raw = if (!holeEnabled) {
        (screenWidthDp - pillWidthDp) / 2f
    } else {
        when (holeMode) {
            IslandHoleMode.SYMMETRIC -> holeX - pillWidthDp / 2f
            IslandHoleMode.RATIO -> holeX - pillWidthDp * (holeRatioPercent / 100f)
            IslandHoleMode.ONE_SIDE -> if (holeSide == IslandHoleSide.LEFT) {
                // Hole fully at the left edge, body extends to the right.
                holeX - holeRadiusDp
            } else {
                // Hole fully at the right edge, body extends to the left.
                holeX + holeRadiusDp - pillWidthDp
            }
        }
    } + offsetXDp
    val maxLeft = (screenWidthDp - pillWidthDp).coerceAtLeast(0f)
    return raw.coerceIn(0f, maxLeft)
}

/**
 * Builds the shell outline into [path] at the origin. PILL is the original rounded rectangle,
 * NOTCH keeps a flat top and rounds only the bottom corners. The corner radius affects both.
 */
fun buildIslandPath(
    path: Path,
    shape: IslandShape,
    widthPx: Float,
    heightPx: Float,
    cornerRadiusPx: Float,
): Path {
    path.reset()
    if (widthPx <= 0f || heightPx <= 0f) return path
    when (shape) {
        IslandShape.PILL -> {
            val radius = cornerRadiusPx.coerceIn(0f, min(widthPx, heightPx) / 2f)
            path.addRoundRect(RectF(0f, 0f, widthPx, heightPx), radius, radius, Path.Direction.CW)
        }

        IslandShape.NOTCH -> {
            val radius = cornerRadiusPx.coerceIn(0f, min(widthPx, heightPx) / 2f)
            path.moveTo(0f, 0f)
            path.lineTo(widthPx, 0f)
            path.lineTo(widthPx, heightPx - radius)
            path.quadTo(widthPx, heightPx, widthPx - radius, heightPx)
            path.lineTo(radius, heightPx)
            path.quadTo(0f, heightPx, 0f, heightPx - radius)
            path.close()
        }
    }
    return path
}
