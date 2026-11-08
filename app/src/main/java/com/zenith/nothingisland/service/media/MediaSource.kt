package com.zenith.nothingisland.service.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
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
    private var enabled = true
    private var artSource: Bitmap? = null
    private var artScaled: Bitmap? = null
    private var artAccent = IslandContent.DEFAULT_ACCENT_COLOR

    /** Last package whose launcher icon was resolved; icon loading is not free. */
    private var iconPackage: String? = null
    private var iconDrawable: Drawable? = null

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

    fun updateEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) stop() else start()
    }

    /** Safe to call repeatedly; no-ops while notification access is missing. */
    fun start() {
        if (!enabled) return
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
        if (!enabled) return
        if (!hasListenerAccess()) {
            stop()
            return
        }
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
        clearArtCache()
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
        // Keep a session even before its first metadata callback; otherwise a player that starts
        // with an empty metadata bundle is never observed when the metadata arrives.
        val chosen = sessions
            ?.sortedByDescending { it.playbackState?.state.isPlayingState() }
            ?.firstOrNull()
        if (chosen == null) {
            detach()
            playing = false
            clearArtCache()
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
        if (compat == null) {
            playing = false
            clearArtCache()
            IslandStateHolder.clearMedia()
            return
        }
        controller = compat
        runCatching { compat.registerCallback(controllerCallback, mainHandler) }
            .onFailure {
                Log.w(TAG, "Unable to register media callback", it)
                detach()
                IslandStateHolder.clearMedia()
            }
        if (controller != null) publish()
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
            clearArtCache()
            IslandStateHolder.clearMedia()
            return
        }

        val state = playback?.state ?: PlaybackStateCompat.STATE_NONE
        val active = state.isPlayingState()
        playing = state == PlaybackStateCompat.STATE_PLAYING
        if (!active) {
            // Paused/stopped sessions fall back to whatever else the island has to show.
            clearArtCache()
            IslandStateHolder.clearMedia()
            return
        }

        val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            .takeIf { it > 0L }
            ?: 0L
        val actions = playback?.actions ?: 0L
        val art = albumArt(metadata)
        val now = SystemClock.elapsedRealtime()
        val positionStamp = playback?.lastPositionUpdateTime
            ?.takeIf { it > 0L && it <= now }
            ?: now

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
                albumArt = art,
                isPlaying = playing,
                positionMs = (playback?.position ?: 0L).coerceAtLeast(0L),
                durationMs = duration,
                positionUpdatedAtElapsedMs = positionStamp,
                speed = playback?.playbackSpeed?.takeIf { it.isFinite() && it > 0f } ?: 1f,
                canSeekPrevious = (actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0L,
                canPlayPause = (actions and (PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)) != 0L,
                canSeekNext = (actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0L,
                accentColor = artAccent,
                appIcon = appIcon(session.packageName),
            ),
        )
    }

    /**
     * Launcher icon of the playing app, cached per package: it only changes when another app takes
     * over the session, so a metadata update must not hit the PackageManager every time.
     */
    private fun appIcon(packageName: String?): Drawable? {
        val name = packageName?.takeIf { it.isNotBlank() } ?: return null
        if (name == iconPackage) return iconDrawable
        iconPackage = name
        iconDrawable = runCatching { context.packageManager.getApplicationIcon(name) }
            .onFailure { Log.w(TAG, "No launcher icon for $name", it) }
            .getOrNull()
        return iconDrawable
    }

    private fun albumArt(metadata: MediaMetadataCompat): Bitmap? {
        val bitmap = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)
        if (bitmap == null) {
            clearArtCache()
            return null
        }
        if (bitmap === artSource) return artScaled
        val scaled = downscale(bitmap)
        artSource = bitmap
        artScaled = scaled
        artAccent = sampleAccent(scaled)
        return scaled
    }

    private fun clearArtCache() {
        // Bitmaps are owned by the media session; never recycle them here.
        artSource = null
        artScaled = null
        artAccent = IslandContent.DEFAULT_ACCENT_COLOR
    }

    /** One bounded pass over the 128 px thumbnail, only when the artwork identity changes. */
    private fun sampleAccent(bitmap: Bitmap): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0) return IslandContent.DEFAULT_ACCENT_COLOR
        val pixels = IntArray(bitmap.width * bitmap.height)
        runCatching { bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            .onFailure { return IslandContent.DEFAULT_ACCENT_COLOR }
        var bestScore = 0f
        var best = IslandContent.DEFAULT_ACCENT_COLOR
        for (index in pixels.indices step 4) {
            val color = pixels[index]
            if (Color.alpha(color) < 128) continue
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val score = (max - min).toFloat() * max
            if (max >= 48 && max - min >= 24 && score > bestScore) {
                bestScore = score
                val boost = (180f / max).coerceAtLeast(1f)
                best = Color.rgb((r * boost).toInt().coerceAtMost(255), (g * boost).toInt().coerceAtMost(255), (b * boost).toInt().coerceAtMost(255))
            }
        }
        return best
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
