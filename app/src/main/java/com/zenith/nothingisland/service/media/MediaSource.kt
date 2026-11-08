package com.zenith.nothingisland.service.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandStateHolder
import com.zenith.nothingisland.service.notification.IslandNotificationListener

/**
 * Mirrors the active media session into [IslandStateHolder].
 *
 * Callbacks only: the active-session listener tells us when another app takes over and every
 * [MediaControllerCompat.Callback] refreshes the island. There is no polling loop. Session access
 * needs notification-listener permission, so the module stays inert (and silent) without it.
 */
class MediaSource(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionManager: MediaSessionManager? =
        context.getSystemService(MediaSessionManager::class.java)

    private val listenerComponent =
        ComponentName(context, IslandNotificationListener::class.java)

    private var controller: MediaControllerCompat? = null
    private var started = false
    private var playing = false

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(controllers)
        }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = publish()
        override fun onSessionDestroyed() {
            detach()
            IslandStateHolder.clearMedia()
            refresh()
        }
    }

    /** Safe to call repeatedly; no-ops while notification access is missing. */
    fun start() {
        if (!started) {
            if (!hasListenerAccess()) {
                Log.i(TAG, "Notification access missing; media module idle")
                return
            }
            val manager = sessionManager ?: return
            started = runCatching {
                manager.addOnActiveSessionsChangedListener(
                    activeSessionsListener,
                    listenerComponent,
                )
            }.onFailure {
                Log.w(TAG, "Unable to observe active media sessions", it)
            }.isSuccess
            if (!started) return
        }
        refreshSessions()
    }

    /** Re-reads the active sessions, e.g. when notification access is granted mid-session. */
    fun refresh() {
        if (!started) {
            start()
            return
        }
        refreshSessions()
    }

    private fun refreshSessions() {
        val manager = sessionManager ?: return
        val sessions = runCatching { manager.getActiveSessions(listenerComponent) }
            .onFailure { Log.w(TAG, "getActiveSessions failed", it) }
            .getOrNull()
            ?: return
        attach(sessions)
    }

    fun stop() {
        val manager = sessionManager
        if (started && manager != null) {
            runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        }
        started = false
        detach()
        playing = false
        IslandStateHolder.clearMedia()
    }

    fun togglePlayPause() {
        val transport = controller?.transportControls ?: return
        runCatching {
            if (playing) transport.pause() else transport.play()
        }.onFailure { Log.w(TAG, "play/pause failed", it) }
    }

    fun previous() {
        runCatching { controller?.transportControls?.skipToPrevious() }
            .onFailure { Log.w(TAG, "skipToPrevious failed", it) }
    }

    fun next() {
        runCatching { controller?.transportControls?.skipToNext() }
            .onFailure { Log.w(TAG, "skipToNext failed", it) }
    }

    private fun attach(sessions: List<MediaController>?) {
        if (!started) return
        val chosen = sessions
            ?.filter { it.metadata != null }
            ?.sortedByDescending { it.playbackState?.state.isPlayingState() }
            ?.firstOrNull()
        if (chosen == null) {
            detach()
            playing = false
            IslandStateHolder.clearMedia()
            return
        }
        val currentSession = controller?.sessionToken?.token
        if (currentSession == chosen.sessionToken) {
            publish()
            return
        }
        detach()
        val compat = runCatching {
            MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(chosen.sessionToken))
        }
            .onFailure { Log.w(TAG, "Unable to wrap media controller", it) }
            .getOrNull()
            ?: return
        controller = compat
        runCatching { compat.registerCallback(controllerCallback, mainHandler) }
            .onFailure { Log.w(TAG, "Unable to register media callback", it) }
        publish()
    }

    private fun detach() {
        controller?.let { runCatching { it.unregisterCallback(controllerCallback) } }
        controller = null
    }

    private fun publish() {
        val session = controller ?: return
        val metadata = runCatching { session.metadata }.getOrNull()
        val playback = runCatching { session.playbackState }.getOrNull()
        if (metadata == null) {
            playing = false
            IslandStateHolder.clearMedia()
            return
        }

        val state = playback?.state ?: PlaybackStateCompat.STATE_NONE
        playing = state.isPlayingState()
        if (!playing) {
            // Paused/stopped sessions fall back to whatever else the island has to show.
            IslandStateHolder.clearMedia()
            return
        }

        val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            .takeIf { it > 0L }
            ?: 0L

        IslandStateHolder.postMedia(
            IslandContent.Media(
                title = metadata.text(MediaMetadataCompat.METADATA_KEY_TITLE)
                    ?: metadata.description?.title?.toString()
                    ?: metadata.text(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
                    ?: "Unknown track",
                artist = metadata.text(MediaMetadataCompat.METADATA_KEY_ARTIST)
                    ?: metadata.text(MediaMetadataCompat.METADATA_KEY_ALBUM)
                    ?: metadata.description?.subtitle?.toString()
                    ?: "",
                albumArt = albumArt(metadata),
                isPlaying = true,
                positionMs = playback?.position ?: 0L,
                durationMs = duration,
                positionUpdatedAtElapsedMs = SystemClock.elapsedRealtime(),
                speed = playback?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            ),
        )
    }

    private fun albumArt(metadata: MediaMetadataCompat): Bitmap? {
        val bitmap = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        return downscale(bitmap)
    }

    /** Keeps the overlay cheap: album art never enters a window larger than 128 px. */
    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_ART_PX) return bitmap
        val ratio = MAX_ART_PX.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bitmap, width, height, true) }
            .getOrDefault(bitmap)
    }

    private fun hasListenerAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    private fun Int?.isPlayingState(): Boolean = when (this) {
        PlaybackStateCompat.STATE_PLAYING,
        PlaybackStateCompat.STATE_BUFFERING,
        PlaybackStateCompat.STATE_CONNECTING,
        PlaybackStateCompat.STATE_FAST_FORWARDING,
        PlaybackStateCompat.STATE_REWINDING,
        PlaybackStateCompat.STATE_SKIPPING_TO_NEXT,
        PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> true

        else -> false
    }

    private fun MediaMetadataCompat.text(key: String): String? =
        getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "IslandMedia"
        const val MAX_ART_PX = 128
    }
}
