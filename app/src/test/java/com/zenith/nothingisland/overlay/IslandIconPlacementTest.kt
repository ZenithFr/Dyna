package com.zenith.nothingisland.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The requested alert presentation: an integrated app's notification puts its icon in the detached
 * bubble and the shell keeps the text; the bubble disappears with the alert because it is driven by
 * the same content slot, not by a second timer.
 */
class IslandIconPlacementTest {

    @Test
    fun notificationAndCallIconsMoveToTheBubbleByDefault() {
        assertTrue(
            IslandIconPlacement.bubbleShowsAppIcon(
                kind = IslandIconKind.ALERT,
                detachedAppIcon = false,
                alertIconInBubble = true,
            ),
        )
        // The shell must not duplicate the icon while the bubble carries it.
        assertFalse(IslandIconPlacement.shellKeepsIcon(IslandIconKind.ALERT, bubbleVisible = true))
        // ...and it comes back if the user turns the bubble off.
        assertTrue(IslandIconPlacement.shellKeepsIcon(IslandIconKind.ALERT, bubbleVisible = false))
    }

    @Test
    fun alertBubbleIsIndependentOfThePersistentActivityBubble() {
        assertFalse(
            IslandIconPlacement.bubbleShowsAppIcon(
                kind = IslandIconKind.ALERT,
                detachedAppIcon = true,
                alertIconInBubble = false,
            ),
        )
        assertTrue(
            IslandIconPlacement.bubbleShowsAppIcon(
                kind = IslandIconKind.ACTIVITY_ICON,
                detachedAppIcon = true,
                alertIconInBubble = false,
            ),
        )
    }

    @Test
    fun mapsAndDownloadsDoNotRepeatTheirIconInTheShell() {
        assertFalse(IslandIconPlacement.shellKeepsIcon(IslandIconKind.ACTIVITY_ICON, bubbleVisible = true))
        assertTrue(IslandIconPlacement.shellKeepsIcon(IslandIconKind.ACTIVITY_ICON, bubbleVisible = false))
    }

    @Test
    fun albumArtAndTimerGlyphStayInTheShell() {
        assertTrue(
            IslandIconPlacement.bubbleShowsAppIcon(
                kind = IslandIconKind.ACTIVITY_ART,
                detachedAppIcon = true,
                alertIconInBubble = false,
            ),
        )
        // Artwork/glyph is content, not the app identity: always kept.
        assertTrue(IslandIconPlacement.shellKeepsIcon(IslandIconKind.ACTIVITY_ART, bubbleVisible = true))
    }

    @Test
    fun batteryConfirmationsNeverSplit() {
        assertFalse(
            IslandIconPlacement.bubbleShowsAppIcon(
                kind = IslandIconKind.NONE,
                detachedAppIcon = true,
                alertIconInBubble = true,
            ),
        )
        assertTrue(IslandIconPlacement.shellKeepsIcon(IslandIconKind.NONE, bubbleVisible = true))
    }
}
