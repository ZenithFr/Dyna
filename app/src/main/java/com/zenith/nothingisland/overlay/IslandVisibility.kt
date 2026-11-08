package com.zenith.nothingisland.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Why the island is currently not allowed on screen. [NONE] means it may render normally. */
enum class IslandSuppression { NONE, LANDSCAPE, FULLSCREEN }

/**
 * Pure visibility policy. Fullscreen means the status bar is hidden: that is the system signal
 * that the top of the screen belongs to immersive video/a game. Navigation-only hiding does not
 * obscure the island, and edge-to-edge apps with a visible status bar are not fullscreen.
 * An unknown inset state never suppresses by itself.
 */
object IslandVisibilityPolicy {
    fun resolve(
        hideInLandscape: Boolean,
        hideInFullscreen: Boolean,
        landscape: Boolean,
        statusBarVisible: Boolean?,
    ): IslandSuppression = when {
        hideInLandscape && landscape -> IslandSuppression.LANDSCAPE
        hideInFullscreen && statusBarVisible == false -> IslandSuppression.FULLSCREEN
        else -> IslandSuppression.NONE
    }
}

/**
 * Observes the status-bar visibility through the insets of a 1 px, transparent, non-touchable
 * accessibility-overlay probe. The probe stays attached while the island is suppressed so the
 * return from fullscreen is still observed. It never reads window content, roots, or titles, so
 * the service keeps canRetrieveWindowContent=false and needs no extra permission or Shizuku.
 */
internal class IslandVisibilityMonitor(
    private val context: Context,
    private val onStatusBarVisibilityChanged: (Boolean?) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var probe: View? = null
    private var lastVisible: Boolean? = null

    fun start() {
        if (probe != null) return
        val manager = windowManager ?: return
        val view = View(context).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnApplyWindowInsetsListener { _, insets ->
                record(insets.isVisible(WindowInsets.Type.statusBars()))
                insets
            }
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "NothingIslandVisibility"
        }
        runCatching { manager.addView(view, params) }
            .onSuccess {
                probe = view
                view.requestApplyInsets()
                refresh()
            }
            .onFailure { Log.w(TAG, "Fullscreen visibility probe unavailable", it) }
    }

    /** Re-read after rotation/display changes; normal changes arrive through inset dispatch. */
    fun refresh() {
        val insets = probe?.rootWindowInsets
            ?: runCatching { windowManager?.currentWindowMetrics?.windowInsets }.getOrNull()
        record(insets?.isVisible(WindowInsets.Type.statusBars()))
    }

    fun stop() {
        val view = probe ?: return
        probe = null
        runCatching { windowManager?.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "Visibility probe was already detached", it) }
        lastVisible = null
    }

    private fun record(visible: Boolean?) {
        if (lastVisible == visible) return
        lastVisible = visible
        onStatusBarVisibilityChanged(visible)
    }

    private companion object {
        const val TAG = "IslandVisibility"
    }
}
