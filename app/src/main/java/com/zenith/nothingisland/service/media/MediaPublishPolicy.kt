package com.zenith.nothingisland.service.media

/** Playback state reduced to what the policy needs. */
enum class MediaPlaybackState { PLAYING, PAUSED, OTHER }

/** What the island should show for the session that was just polled. */
sealed interface MediaPublish {
    data object Playing : MediaPublish
    data class Paused(val untilElapsedMs: Long) : MediaPublish
    data object Clear : MediaPublish
}

/**
 * Decides whether a card survives a non-playing session. Kept free of Android types so the rule
 * ("a pause holds the card for a while, a stop does not") is testable on the JVM.
 */
object MediaPublishPolicy {
    fun decide(
        state: MediaPlaybackState,
        hasMetadata: Boolean,
        keepPausedMs: Long,
        nowElapsedMs: Long,
        pausedSinceElapsedMs: Long?,
    ): MediaPublish {
        if (!hasMetadata) return MediaPublish.Clear
        return when (state) {
            MediaPlaybackState.PLAYING -> MediaPublish.Playing
            MediaPlaybackState.PAUSED -> {
                // 0 is the old behaviour: any pause cleared the island at once.
                if (keepPausedMs <= 0L) return MediaPublish.Clear
                val deadline = (pausedSinceElapsedMs ?: nowElapsedMs) + keepPausedMs
                if (deadline <= nowElapsedMs) MediaPublish.Clear else MediaPublish.Paused(deadline)
            }
            MediaPlaybackState.OTHER -> MediaPublish.Clear
        }
    }
}
