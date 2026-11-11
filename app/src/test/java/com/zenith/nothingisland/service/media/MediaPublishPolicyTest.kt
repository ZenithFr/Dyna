package com.zenith.nothingisland.service.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM test: the paused-card hold must not depend on the media session or the clock. */
class MediaPublishPolicyTest {

    private fun decide(
        state: MediaPlaybackState,
        hasMetadata: Boolean = true,
        keepPausedMs: Long = 30_000L,
        nowElapsedMs: Long = 10_000L,
        pausedSinceElapsedMs: Long? = 5_000L,
    ): MediaPublish = MediaPublishPolicy.decide(
        state = state,
        hasMetadata = hasMetadata,
        keepPausedMs = keepPausedMs,
        nowElapsedMs = nowElapsedMs,
        pausedSinceElapsedMs = pausedSinceElapsedMs,
    )

    @Test
    fun playingPublishesPlaying() {
        assertEquals(MediaPublish.Playing, decide(MediaPlaybackState.PLAYING))
    }

    @Test
    fun aPausePublishesAPausedCardUntilTheHoldEnds() {
        assertEquals(
            MediaPublish.Paused(untilElapsedMs = 35_000L),
            decide(MediaPlaybackState.PAUSED),
        )
    }

    @Test
    fun aZeroHoldClearsOnPause() {
        assertEquals(MediaPublish.Clear, decide(MediaPlaybackState.PAUSED, keepPausedMs = 0L))
    }

    @Test
    fun anExpiredHoldClears() {
        // The pause began 40 s ago, so its 30 s hold is already over.
        assertEquals(
            MediaPublish.Clear,
            decide(
                MediaPlaybackState.PAUSED,
                nowElapsedMs = 45_000L,
                pausedSinceElapsedMs = 5_000L,
            ),
        )
    }

    @Test
    fun noMetadataClearsEvenWhilePlaying() {
        assertEquals(MediaPublish.Clear, decide(MediaPlaybackState.PLAYING, hasMetadata = false))
    }

    @Test
    fun stoppedSessionsClear() {
        assertEquals(MediaPublish.Clear, decide(MediaPlaybackState.OTHER))
    }
}
