package com.zenith.nothingisland.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Why the island is currently not allowed on screen. [NONE] means it may render normally. */
enum class IslandSuppression { NONE, SCREEN_OFF, LANDSCAPE, FULLSCREEN }

/**
 * Pure visibility policy. Fullscreen means the status bar is hidden: that is the system signal
 * that the top of the screen belongs to immersive video/a game. Navigation-only hiding does not
 * obscure the island, and edge-to-edge apps with a visible status bar are not fullscreen.
 * An unknown inset state never suppresses by itself. A non-ON display always suppresses: doze/AOD
 * and screen-off must not keep overlay surfaces or animation loops alive.
 */
object IslandVisibilityPolicy {

    /**
     * App launches, recents and dialog transitions briefly report the status bar as hidden while the
     * window animation runs. Hiding on that first reading made the island blink on every app switch,
     * so a fullscreen hide must be stable for this long before it is committed. Showing the island
     * again is never delayed.
     */
    const val FULLSCREEN_HIDE_DEBOUNCE_MS = 250L

    /** Only the "status bar just disappeared" edge is debounced; screen-off/landscape hide at once. */
    fun shouldDebounceSuppression(current: IslandSuppression, next: IslandSuppression): Boolean =
        current == IslandSuppression.NONE && next == IslandSuppression.FULLSCREEN

    fun resolve(
        hideInLandscape: Boolean,
        hideInFullscreen: Boolean,
        landscape: Boolean,
        statusBarVisible: Boolean?,
        screenOn: Boolean = true,
    ): IslandSuppression = when {
        !screenOn -> IslandSuppression.SCREEN_OFF
        hideInLandscape && landscape -> IslandSuppression.LANDSCAPE
        hideInFullscreen && statusBarVisible == false -> IslandSuppression.FULLSCREEN
        else -> IslandSuppression.NONE
    }
}

/**
 * Fail-open gate for the inset probe. Some overlay windows report `statusBars` as invisible even
 * while the system status bar is visible. Until the probe has actually observed a visible status
 * bar, a hidden reading is treated as unknown instead of suppressing the island everywhere.
 */
internal class StatusBarVisibilityGate {
    var calibrated: Boolean = false
        private set

    fun report(visible: Boolean?): Boolean? {
        if (visible == true) calibrated = true
        return if (visible == false && !calibrated) null else visible
    }

    fun reset() {
        calibrated = false
    }
}

/**
 * Observes the status-bar visibility through the insets of a 1 px, transparent, non-touchable
 * accessibility-overlay probe. The probe stays attached while the island is suppressed so the
 * return from fullscreen is still observed. It never reads window content, roots, or titles, so
 * the service keeps canRetrieveWindowContent=false and needs no extra permission or Shizuku.
 *
 * The probe intentionally does not use FLAG_LAYOUT_IN_SCREEN / cutout-always: those flags let a
 * window occupy the status-bar area and can make its inset visibility read as hidden on some OEM
 * builds. A normal non-focusable overlay is enough to observe whether the status bar is visible.
 */
internal class IslandVisibilityMonitor(
    private val context: Context,
    private val onStatusBarVisibilityChanged: (Boolean?) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val gate = StatusBarVisibilityGate()
    private var probe: View? = null
    private var lastVisible: Boolean? = null

    fun start() {
        if (probe != null) return
        val manager = windowManager ?: return
        gate.reset()
        lastVisible = null
        val view = View(context).apply {
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnApplyWindowInsetsListener { _, insets ->
                record(statusBarVisible(insets), "dispatch")
                insets
            }
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "NothingIslandVisibility"
        }
        runCatching { manager.addView(view, params) }
            .onSuccess {
                probe = view
                view.requestApplyInsets()
                refresh()
            }
            .onFailure {
                Log.w(TAG, "Fullscreen visibility probe unavailable; fullscreen hiding fails open", it)
                record(null, "unavailable")
            }
    }

    /** Re-read after rotation/display changes; normal changes arrive through inset dispatch. */
    fun refresh() {
        // Only trust the attached probe's own insets. Service-context WindowMetrics can describe a
        // different windowing state and must not turn an uncalibrated probe into a hidden reading.
        val insets = probe?.rootWindowInsets
        record(insets?.let(::statusBarVisible), "refresh")
    }

    fun stop() {
        val view = probe ?: return
        probe = null
        runCatching { windowManager?.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "Visibility probe was already detached", it) }
        gate.reset()
        lastVisible = null
    }

    /** A positive status-bar inset also means visible, even if an OEM reports isVisible=false. */
    private fun statusBarVisible(insets: WindowInsets): Boolean =
        insets.isVisible(WindowInsets.Type.statusBars()) ||
            insets.getInsets(WindowInsets.Type.statusBars()).top > 0

    private fun record(visible: Boolean?, source: String) {
        val reported = gate.report(visible)
        if (lastVisible == reported) return
        lastVisible = reported
        Log.d(
            TAG,
            "statusBar source=$source raw=$visible reported=$reported calibrated=${gate.calibrated}",
        )
        onStatusBarVisibilityChanged(reported)
    }

    private companion object {
        const val TAG = "IslandVisibility"
    }
}
