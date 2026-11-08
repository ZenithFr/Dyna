package com.zenith.nothingisland.service

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/**
 * Where an island event came from. [priority] orders the *persistent* sources; transient alerts
 * (notification / charging) are layered above the persistent ones so they can surface while music
 * is playing and then collapse back to it.
 */
enum class IslandSource(val priority: Int) {
    Timer(5),
    Media(4),
    Call(3),
    Notification(2),
    Charging(1),
}

/** A single thing the island can show. Content classes are immutable snapshots. */
sealed interface IslandContent {

    /** Currently playing track. */
    data class Media(
        val title: String,
        val artist: String,
        val albumArt: Bitmap?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val positionUpdatedAtElapsedMs: Long,
        val speed: Float,
        /**
         * Launcher icon of the app that owns the session. A persistent live activity keeps the
         * shell compact and puts this icon in the detached bubble beside it, the way the system
         * island separates an app from its activity.
         */
        val appIcon: Drawable? = null,
    ) : IslandContent {

        /** Playback position extrapolated from the last callback, without polling the session. */
        fun estimatePositionMs(nowElapsedMs: Long): Long {
            if (durationMs <= 0L) return positionMs.coerceAtLeast(0L)
            val elapsed = (nowElapsedMs - positionUpdatedAtElapsedMs).coerceAtLeast(0L)
            val projected = positionMs + (elapsed * speed).toLong()
            return projected.coerceIn(0L, durationMs)
        }
    }

    /** A mirrored notification. */
    data class Notification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val icon: Drawable?,
        val title: String,
        val text: String,
        val actions: List<NotificationAction>,
        val contentIntent: PendingIntent?,
    ) : IslandContent

    /** Battery gained/lost power. */
    data class Charging(
        val level: Int,
        val isFull: Boolean,
    ) : IslandContent

    /** A timer for counting down time. */
    data class Timer(
        val label: String,
        val remainingMs: Long,
        val isRunning: Boolean,
        val endTimeElapsedMs: Long,
    ) : IslandContent {

        /**
         * `mm:ss` for the time left, rounded **up** so a running countdown never reads 00:00
         * before it is actually done. At zero (completion / stopped) it is exactly 00:00.
         */
        val displayTime: String
            get() {
                val seconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
                return "%02d:%02d".format(seconds / 60L, seconds % 60L)
            }
    }
}

/**
 * A notification action. [remoteInput] is non-null for inline-reply actions, in which case
 * [pendingIntent] is the reply intent that expects the RemoteInput result bundle.
 */
data class NotificationAction(
    val title: String,
    val pendingIntent: PendingIntent?,
    val remoteInput: RemoteInput?,
) {
    val isReply: Boolean get() = remoteInput != null
}

/** What [IslandStateHolder] publishes; the service renders exactly this. */
data class IslandEvent(
    val source: IslandSource,
    val content: IslandContent,
    val expanded: Boolean = false,
    val transient: Boolean = false,
)
