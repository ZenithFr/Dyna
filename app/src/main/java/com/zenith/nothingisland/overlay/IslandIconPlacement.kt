package com.zenith.nothingisland.overlay

/**
 * What a compact row's leading glyph is, which decides whether the detached bubble replaces it.
 *
 * [ALERT] icons (message/call app icons) and [ACTIVITY_ICON] icons (maps/downloads) are the *app's*
 * identity, so when the bubble carries them the shell must not draw them a second time.
 * [ACTIVITY_ART] is content rather than identity — album artwork and the timer glyph stay in the
 * shell even while the owning app sits in the bubble.
 */
enum class IslandIconKind { ALERT, ACTIVITY_ICON, ACTIVITY_ART, NONE }

/**
 * Pure icon-placement rules for the compact island.
 *
 * The bubble is transient for alerts: it appears with the notification and retracts on the same
 * mitosis spring as soon as the alert hold expires and the holder drops the content, so no separate
 * timer is needed to hide it.
 */
object IslandIconPlacement {

    /** Does the detached bubble carry this content's app icon? */
    fun bubbleShowsAppIcon(
        kind: IslandIconKind,
        detachedAppIcon: Boolean,
        alertIconInBubble: Boolean,
    ): Boolean = when (kind) {
        IslandIconKind.ALERT -> alertIconInBubble
        IslandIconKind.ACTIVITY_ICON, IslandIconKind.ACTIVITY_ART -> detachedAppIcon
        IslandIconKind.NONE -> false
    }

    /** Should the compact shell still draw its own leading icon? */
    fun shellKeepsIcon(kind: IslandIconKind, bubbleVisible: Boolean): Boolean = when (kind) {
        IslandIconKind.ALERT, IslandIconKind.ACTIVITY_ICON -> !bubbleVisible
        IslandIconKind.ACTIVITY_ART, IslandIconKind.NONE -> true
    }
}
