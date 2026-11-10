package com.zenith.nothingisland.service

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.util.Locale

/**
 * Where an island event came from. [priority] orders the *persistent* sources; transient alerts
 * (notification / charging) are layered above the persistent ones so they can surface while music
 * is playing and then collapse back to it.
 */
enum class IslandSource(val priority: Int) {
    Call(9),
    Notification(8),
    Charging(7),
    BatteryLow(6),
    PowerSave(5),
    Timer(4),
    Navigation(3),
    Download(2),
    Media(1),
}

/** A single thing the island can show. Content classes are immutable snapshots. */
sealed interface IslandContent {

    companion object {
        /** Apple's Now Playing waveform falls back to a warm orange when artwork has no color. */
        const val DEFAULT_ACCENT_COLOR = 0xFFFF9F0A.toInt()
    }

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
        /** Transport affordances advertised by the session; unsupported controls are not drawn. */
        val canSeekPrevious: Boolean = true,
        val canPlayPause: Boolean = true,
        val canSeekNext: Boolean = true,
        /** Tint sampled once from the artwork, used by the waveform like Apple's Now Playing pill. */
        val accentColor: Int = IslandContent.DEFAULT_ACCENT_COLOR,
        /**
         * Launcher icon of the app that owns the session. A persistent live activity keeps the
         * shell compact and puts this icon in the detached bubble beside it, the way the system
         * island separates an app from its activity.
         */
        val appIcon: Drawable? = null,
    ) : IslandContent {

        /** Playback position extrapolated from the last callback, without polling the session. */
        fun estimatePositionMs(nowElapsedMs: Long): Long {
            if (durationMs <= 0L || !isPlaying) return positionMs.coerceAtLeast(0L)
            val elapsed = (nowElapsedMs - positionUpdatedAtElapsedMs).coerceAtLeast(0L)
            val safeSpeed = if (speed.isFinite() && speed > 0f) speed else 1f
            val projected = positionMs + (elapsed * safeSpeed).toLong()
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
        /** Messages currently queued from all apps, so the bubble badge matches the OS shade. */
        val stackCount: Int = 1,
        /** False once the detached app-icon bubble has retracted for this alert. */
        val showBubble: Boolean = true,
    ) : IslandContent

    /** Battery gained/lost power. */
    data class Charging(
        val level: Int,
        val isFull: Boolean,
    ) : IslandContent

    /** Low-battery threshold crossing. */
    data class BatteryLow(val level: Int) : IslandContent

    /** System power-save mode changed. */
    data class PowerSave(val enabled: Boolean) : IslandContent

    /** Incoming/ongoing call mirrored from a CallStyle notification. */
    data class Call(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val icon: Drawable?,
        val caller: String,
        val isVideo: Boolean,
        val isIncoming: Boolean,
        val answerIntent: PendingIntent?,
        val declineIntent: PendingIntent?,
        val hangUpIntent: PendingIntent?,
        val contentIntent: PendingIntent?,
        val answerColor: Int,
        val declineColor: Int,
    ) : IslandContent

    /**
     * An ongoing activity that stays visible while it runs: a download or install, or a running
     * clock timer/stopwatch. Progress is deliberately not drawn (estimates glitch); the activity
     * shows the notification's own controls (pause, stop, cancel...) instead.
     */
    data class Download(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val icon: Drawable?,
        val title: String,
        val text: String,
        val actions: List<NotificationAction>,
        val contentIntent: PendingIntent?,
    ) : IslandContent

    /** Turn-by-turn / navigation status from a maps notification. */
    data class Navigation(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val icon: Drawable?,
        val instruction: String,
        val detail: String,
        val contentIntent: PendingIntent?,
    ) : IslandContent

    /** A timer for counting down time. */
    data class Timer(
        val label: String,
        val remainingMs: Long,
        val isRunning: Boolean,
        val endTimeElapsedMs: Long,
    ) : IslandContent {

        /**
         * `mm:ss` / `h:mm:ss` for the time left, rounded **up** so a running countdown never reads 00:00
         * before it is actually done. At zero (completion / stopped) it is exactly 00:00.
         */
        val displayTime: String
            get() {
                val seconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
                val h = seconds / 3600L
                val m = (seconds % 3600L) / 60L
                val s = seconds % 60L
                return if (h > 0L) "%d:%02d:%02d".format(Locale.US, h, m, s)
                else "%02d:%02d".format(Locale.US, m, s)
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
