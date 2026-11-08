package com.zenith.nothingisland.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.data.IslandShape
import com.zenith.nothingisland.data.pillLeftDp
import com.zenith.nothingisland.service.IslandEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns the accessibility overlay window and is the control surface for the feature modules.
 *
 * Geometry is derived from the **portrait** display width whenever [IslandSettings.lockPortrait]
 * is set: rotating to landscape yields the same natural width, so the pill keeps exactly the same
 * size and anchor instead of jumping/resizing. The view is never rotated and the display cutout is
 * never used as an anchor (the only valid anchor is portrait top-centre + calibration offsets).
 *
 * All calls must happen on the main thread, which matches the AccessibilityService and
 * WindowManager contracts, so no synchronisation is needed.
 */
class IslandOverlay(private val serviceContext: Context) {

    // Must stay the AccessibilityService context: its WindowManager carries the window token
    // installed by AccessibilityService.init(). The application context's token is null and
    // TYPE_ACCESSIBILITY_OVERLAY would be rejected with BadTokenException.
    private val windowManager: WindowManager =
        serviceContext.getSystemService(WindowManager::class.java)
            ?: error("WindowManager is unavailable")

    private val displayManager: DisplayManager =
        serviceContext.getSystemService(DisplayManager::class.java)
            ?: error("DisplayManager is unavailable")

    private val pill = IslandPillView(serviceContext)

    /**
     * Input-only window sitting exactly over the visible shell.
     *
     * The island window itself is a permanent envelope (both states plus the springs' overshoot)
     * and is [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]: a morph therefore never resizes the
     * surface — a resize makes the compositor stretch the stale buffer for a frame, which is the
     * "jump" you see on expansion — and the empty part of the envelope can never swallow a
     * status-bar gesture. This transparent window is the only touch consumer; it replays every
     * event into the island with the offset between the two windows, so hit boxes keep matching
     * the pixels.
     */
    private val touchShield = TouchShield(serviceContext)

    private var layoutParams: WindowManager.LayoutParams? = null
    private var touchParams: WindowManager.LayoutParams? = null
    /** Settled geometry from the last [apply]; the touch window is placed from these. */
    private var compactFootprint: Footprint? = null
    private var expandedFootprint: Footprint? = null
    private var settings = IslandSettings().coerced()
    private var event: IslandEvent? = null
    private var currentEvent: IslandEvent? = null
    private var hiddenByFullscreen: Boolean = false
    /** Millisecond timestamp when the island first became idle (no content). */
    private var idleStartMs = 0L
    private val idleHandler = Handler(Looper.getMainLooper())

    /**
     * Curls the idle shell into the camera hole. Posted [IslandSettings.idleContractDelaySec]
     * after the island went idle and cancelled the moment new content arrives, so the countdown
     * is real time instead of "whenever the next layout happens".
     */
    private val contractWhenIdle = Runnable {
        if (event?.content != null || layoutParams == null) return@Runnable
        // Only start the spring here. The shell morphs inside the envelope that is already
        // installed, so a full apply() would just re-measure and re-invalidate on the frame the
        // motion starts (visible as a hitch); the settled-morph callback re-places the touch
        // window afterwards.
        pill.setDormant(true)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) apply()
        }
    }

    init {
        // When the morph settles the touch window can snap from the union of both frames to the
        // shell that actually ended up visible.
        pill.onMorphSettled = { apply() }
        // AOD is a display state, not a broadcast the accessibility service reliably sees: listen
        // to the panel so the hairline outline appears the moment the screen dozes.
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    /** True when the panel is in always-on/doze (Nothing OS renders the AOD in this state). */
    private fun isAlwaysOn(): Boolean = runCatching {
        val state = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state
        state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND
    }.getOrDefault(false)

    /** Tap callbacks; set by [com.zenith.nothingisland.service.IslandService]. */
    var interaction: IslandInteraction?
        get() = pill.listener
        set(value) {
            pill.listener = value
        }

    val isShowing: Boolean
        get() = layoutParams != null

    /** Bottom edge of the island in screen pixels; used to anchor the inline-reply window. */
    val bottomPx: Int
        get() = layoutParams?.let { it.y + pill.visualBottomPx().roundToInt() } ?: 0

    /** Applies calibration changes without changing what is displayed. */
    fun updateSettings(newSettings: IslandSettings) {
        settings = newSettings.coerced()
        apply()
    }

    /** Renders the holder's current event (null = idle pill). */
    fun render(newEvent: IslandEvent?) {
        event = newEvent
        if (newEvent != null) currentEvent = newEvent
        // The window already spans both states, so a morph starts without resizing the surface:
        // the first animated frame lands in the same, still-valid buffer.
        Log.w(TAG, "render content=${newEvent?.content?.javaClass?.simpleName} expanded=${newEvent?.expanded}")
        pill.render(newEvent)
        apply()
        Log.w(TAG, "after apply dormant=${event?.content == null} window=${layoutParams?.let { "${it.width}x${it.height}@${it.x},${it.y}" }}")
    }

    /** Re-shows the pill after a fullscreen hide. */
    /** Re-shows the pill after a fullscreen hide. */
    fun showAgain() {
        if (currentEvent != null && !hiddenByFullscreen) return
        hiddenByFullscreen = false
        val toShow = currentEvent
        if (toShow != null) {
            event = toShow
            pill.render(toShow)
            apply()
        }
    }

    /**
     * Called by [com.zenith.nothingisland.service.IslandService].onAccessibilityEvent when the
     * accessibility event reports a window-state change. If the source window covers ≥95% of the
     * display while the app is NOT hiding in fullscreen, hide the pill; otherwise re-show it via
     * showAgain().
     */
    fun reportWindowEvent(sourceBounds: Rect?) {
        if (sourceBounds == null) return
        val screenBounds = windowManager.currentWindowMetrics.bounds
        val metricW = screenBounds.width().coerceAtLeast(1)
        val metricH = screenBounds.height().coerceAtLeast(1)
        val coveredW = sourceBounds.width().toFloat() / metricW
        val coveredH = sourceBounds.height().toFloat() / metricH
        if (!settings.hideInFullscreen && coveredW >= 0.95f && coveredH >= 0.95f) {
            hiddenByFullscreen = true
            return
        }
        if (hiddenByFullscreen && !(coveredW >= 0.95f || coveredH >= 0.95f)) {
            showAgain()
            hiddenByFullscreen = false
        }
    }

    fun hide() {
        if (layoutParams == null) return
        cancelIdleContraction()
        layoutParams = null
        removeTouchWindow()
        runCatching { windowManager.removeViewImmediate(pill) }
            .onFailure { Log.w(TAG, "Island window was already detached", it) }
    }

    /** Full teardown: detach the windows and stop watching the display. */
    fun release() {
        cancelIdleContraction()
        idleStartMs = 0L
        hide()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
            .onFailure { Log.w(TAG, "Display listener was already detached", it) }
    }

    /** Recomputes geometry, e.g. after a rotation or fold. Identical params in portrait lock. */
    fun refresh() {
        if (isShowing) apply()
    }

    private fun apply() {
        val compact = footprint(expanded = false)
        val expandedFrame = footprint(expanded = true)
        val dormant = dormantFootprint()
        compactFootprint = compact
        expandedFootprint = expandedFrame

        // One fixed window for both states, added once: expanding and retracting only ever move
        // rectangles inside it, so no frame is ever composited into a differently sized surface.
        val window = envelopeOf(compact, expandedFrame, dormant)
        val existing = layoutParams
        if (existing == null) {
            val params = buildLayoutParams(window, touchable = false)
            runCatching { windowManager.addView(pill, params) }
                .onSuccess { layoutParams = params }
                .onFailure { Log.e(TAG, "Unable to add island overlay", it) }
        } else if (
            existing.width != window.widthPx ||
            existing.height != window.heightPx ||
            existing.x != window.x ||
            existing.y != window.y
        ) {
            existing.width = window.widthPx
            existing.height = window.heightPx
            existing.x = window.x
            existing.y = window.y
            runCatching { windowManager.updateViewLayout(pill, existing) }
                .onFailure {
                    // The system may have dropped the window (service re-bind, display change).
                    // Detach so the next update re-adds it cleanly.
                    Log.w(TAG, "updateViewLayout failed; island will be re-attached", it)
                    hide()
                    return
                }
        }

        // The pill morphs inside the window, so expanding/collapsing never triggers a relayout
        // mid-animation: the two frames are just rectangles inside the current window.
        pill.setFrames(
            compactFrame = rectIn(compact, window),
            expandedFrame = rectIn(expandedFrame, window),
            dormantFrame = rectIn(dormant, window),
            compactCornerPx = dp(compact.cornerRadiusDp.toFloat()).toFloat(),
            expandedCornerPx = dp(expandedFrame.cornerRadiusDp.toFloat()).toFloat(),
        )
        pill.setShape(settings.islandShape)
        pill.setStroke(
            enabled = settings.strokeEnabled,
            widthPx = settings.strokeWidthDp.toFloat() * resources().displayMetrics.density,
            colorArgb = settings.strokeColorArgb,
        )
        pill.setHighQualityMotion(settings.highQualityMotion)
        pill.setAod(isAlwaysOn())
        // Idle contraction: the shell lingers for the configured delay, then curls up behind the
        // camera hole. Any content (notification, media, timer, charging) cancels the countdown
        // and springs the shell back out instead of leaving it hidden inside the lens.
        if (event?.content == null) {
            if (idleStartMs == 0L) idleStartMs = SystemClock.elapsedRealtime()
            scheduleIdleContraction()
        } else {
            idleStartMs = 0L
            cancelIdleContraction()
            pill.setDormant(false)
        }
        Log.w(TAG, "apply content=${event?.content?.javaClass?.simpleName} exp=${event?.expanded} morph=${pill.isMorphing()}")

        // Hole in portrait screen px, converted to window-local coordinates for the renderer.
        val density = resources().displayMetrics.density
        val holeX = portraitWidthPx() / 2f + settings.holeCenterXDp * density
        val holeY = settings.holeCenterYDp * density
        pill.setHole(
            centerXPx = holeX - window.x,
            centerYPx = holeY - window.y,
            radiusPx = if (settings.holeEnabled) settings.holeRadiusDp * density else 0f,
            guideVisible = settings.showHoleGuide && settings.holeEnabled,
        )

        updateTouchWindow(compact, expandedFrame)
    }

    /**
     * Posts the contraction for whatever is left of the idle window, or starts it right away if
     * the window already elapsed (e.g. the service woke up after the phone was asleep). Raising
     * the delay while the shell is already curled up springs it back out and re-arms the rest.
     */
    private fun scheduleIdleContraction() {
        val remainingMs = settings.idleContractDelaySec * 1000L -
            (SystemClock.elapsedRealtime() - idleStartMs)
        if (remainingMs <= 0L) {
            pill.setDormant(true)
            return
        }
        pill.setDormant(false)
        // Reposting always (rather than only when nothing is queued) keeps a slider change to the
        // delay honest: the pending callback would otherwise fire on the old value.
        idleHandler.removeCallbacks(contractWhenIdle)
        idleHandler.postDelayed(contractWhenIdle, remainingMs)
    }

    private fun cancelIdleContraction() {
        idleHandler.removeCallbacks(contractWhenIdle)
    }

    /** The circle the idle island curls up into: the camera hole itself. */
    private fun dormantFootprint(): Envelope {
        val density = resources().displayMetrics.density
        val portraitWidth = portraitWidthPx()
        if (settings.holeEnabled) {
            val centerX = portraitWidth / 2f + settings.holeCenterXDp * density
            val centerY = settings.holeCenterYDp * density
            val radius = (settings.holeRadiusDp * density).coerceAtLeast(dp(2f).toFloat())
            return Envelope(
                widthPx = (radius * 2f).roundToInt(),
                heightPx = (radius * 2f).roundToInt(),
                x = (centerX - radius).roundToInt().coerceIn(0, portraitWidth),
                y = (centerY - radius).roundToInt().coerceAtLeast(0),
            )
        }
        // Hole disabled: a small dot in the middle of where the pill normally sits, so an idle
        // island still disappears instead of leaving a stray circle on screen.
        val compact = footprint(expanded = false)
        val size = dp(6f)
        return Envelope(
            widthPx = size * 2,
            heightPx = size * 2,
            x = compact.x + compact.widthPx / 2 - size,
            y = compact.y + compact.heightPx / 2 - size,
        )
    }

    /**
     * Sizes the input window to the shell that is actually on screen: the union of both frames
     * while the springs run (so a moving edge never hands back a half-tap), the settled frame
     * otherwise. The envelope itself stays untouchable, which is what keeps the status bar alive.
     */
    private fun updateTouchWindow(compact: Footprint, expandedFrame: Footprint) {
        if (layoutParams == null) return
        // Fully idle: the shell is curled up behind the lens and has nothing to tap, so drop the
        // input window entirely instead of leaving a dead rectangle at the top of the screen.
        if (event?.content == null) {
            removeTouchWindow()
            return
        }
        val rect = if (pill.isMorphing()) {
            val left = min(compact.x, expandedFrame.x)
            val top = min(compact.y, expandedFrame.y)
            Envelope(
                widthPx = max(compact.x + compact.widthPx, expandedFrame.x + expandedFrame.widthPx) - left,
                heightPx = max(compact.y + compact.heightPx, expandedFrame.y + expandedFrame.heightPx) - top,
                x = left,
                y = top,
            )
        } else if (event?.expanded == true) {
            expandedFrame.window()
        } else {
            compact.window()
        }

        val existing = touchParams
        if (existing == null) {
            val params = buildLayoutParams(rect, touchable = true)
            runCatching { windowManager.addView(touchShield, params) }
                .onSuccess { touchParams = params }
                .onFailure { Log.e(TAG, "Unable to add island touch window", it) }
        } else if (
            existing.width != rect.widthPx ||
            existing.height != rect.heightPx ||
            existing.x != rect.x ||
            existing.y != rect.y
        ) {
            existing.width = rect.widthPx
            existing.height = rect.heightPx
            existing.x = rect.x
            existing.y = rect.y
            runCatching { windowManager.updateViewLayout(touchShield, existing) }
                .onFailure {
                    Log.w(TAG, "updateViewLayout (touch) failed; island will be re-attached", it)
                    hide()
                }
        }
    }

    private fun removeTouchWindow() {
        if (touchParams == null) return
        touchParams = null
        runCatching { windowManager.removeViewImmediate(touchShield) }
            .onFailure { Log.w(TAG, "Touch window was already detached", it) }
    }

    /**
     * A window big enough for the compact pill, the expanded view, and the springs' overshoot.
     * It is installed once and never resized by a morph: only settings or a display change move it.
     */
    private fun envelopeOf(compact: Footprint, expanded: Footprint, dormant: Envelope): Envelope {
        // Pad both axes by the overshoot the springs can actually produce, so a wide pill cannot
        // be clipped by a fixed pad that was sized for a small one.
        val padX = dp(ENVELOPE_PAD_DP + abs(expanded.widthPx - compact.widthPx) * SPRING_OVERSHOOT_DP)
        val padY = dp(ENVELOPE_PAD_DP + abs(expanded.heightPx - compact.heightPx) * SPRING_OVERSHOOT_DP)
        val portraitWidth = portraitWidthPx()
        // The dormant circle can sit above (or beside) the pill, so it is part of the union too:
        // the window is installed once and never resized by a morph.
        val left = (min(compact.x, min(expanded.x, dormant.x)) - padX).coerceAtLeast(0)
        val top = (min(compact.y, min(expanded.y, dormant.y)) - padY).coerceAtLeast(0)
        val right = (
            max(compact.x + compact.widthPx, max(expanded.x + expanded.widthPx, dormant.x + dormant.widthPx)) +
                padX
            ).coerceAtMost(portraitWidth)
        val bottom = max(
            compact.y + compact.heightPx,
            max(expanded.y + expanded.heightPx, dormant.y + dormant.heightPx),
        ) + padY
        return Envelope(
            widthPx = (right - left).coerceAtLeast(1),
            heightPx = (bottom - top).coerceAtLeast(1),
            x = left,
            y = top,
        )
    }

    /** Settled window: exactly the shell, so only the island is touchable. */
    private fun Footprint.window(): Envelope =
        Envelope(widthPx = widthPx, heightPx = heightPx, x = x, y = y)

    private fun rectIn(footprint: Footprint, window: Envelope): RectF = RectF(
        (footprint.x - window.x).toFloat(),
        (footprint.y - window.y).toFloat(),
        (footprint.x - window.x + footprint.widthPx).toFloat(),
        (footprint.y - window.y + footprint.heightPx).toFloat(),
    )

    private fun rectIn(area: Envelope, window: Envelope): RectF = RectF(
        (area.x - window.x).toFloat(),
        (area.y - window.y).toFloat(),
        (area.x - window.x + area.widthPx).toFloat(),
        (area.y - window.y + area.heightPx).toFloat(),
    )

    private fun buildLayoutParams(window: Envelope, touchable: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            window.widthPx,
            window.heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            if (touchable) FLAGS else FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = window.x
            y = window.y
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = if (touchable) "NothingIslandTouch" else "NothingIsland"
        }

    private fun footprint(expanded: Boolean): Footprint {
        val portraitWidth = portraitWidthPx()
        val screenWidthDp = portraitWidth / resources().displayMetrics.density

        val widthDp = if (expanded) {
            val maxWidth = (screenWidthDp - EXPANDED_SCREEN_MARGIN_DP).toInt()
            // The calibrated width is the pill's maximum; the pressed/full view always grows past
            // it so tapping always reveals more island, never less.
            min(
                max(settings.widthDp + EXPANDED_GROWTH_DP, EXPANDED_MIN_WIDTH_DP),
                max(maxWidth, settings.widthDp),
            )
        } else {
            settings.widthDp
        }
        val heightDp = if (expanded) {
            max(settings.heightDp, EXPANDED_HEIGHT_DP)
        } else {
            settings.heightDp
        }
        val cornerDp = if (expanded) {
            max(settings.cornerRadiusDp, EXPANDED_CORNER_RADIUS_DP)
                .coerceAtMost(heightDp / 2)
        } else {
            settings.cornerRadiusDp
        }

        val widthPx = dp(widthDp.toFloat())
        val heightPx = dp(heightDp.toFloat())
        val leftDp = settings.pillLeftDp(screenWidthDp, widthDp.toFloat())
        val leftPx = dp(leftDp).coerceIn(0, (portraitWidth - widthPx).coerceAtLeast(0))
        return Footprint(
            widthPx = widthPx,
            heightPx = heightPx,
            // Hole-aware placement: SYMMETRIC centres the body on the lens, RATIO puts the lens
            // at a proportional position and ONE_SIDE flushes it to an edge. offsetXDp is the
            // fine nudge; both offsets remain independent of rotation (portrait width anchor).
            x = leftPx,
            // NOTCH hangs from the top bezel, so it ignores the vertical offset.
            y = if (settings.islandShape == IslandShape.PILL) {
                dp(settings.offsetYDp.toFloat())
            } else {
                0
            },
            cornerRadiusDp = cornerDp,
        )
    }

    /**
     * Width of the display in its natural (portrait) orientation.
     *
     * With [IslandSettings.lockPortrait] the geometry must be identical in every rotation, so when
     * the panel is turned 90°/270° we swap the current bounds back before centring (the display
     * metrics report landscape width/height there). The overlay's own width/height are never
     * swapped and the view is never rotated. With the lock off, the current (rotation-aware)
     * width is used instead.
     */
    private fun portraitWidthPx(): Int {
        val current = windowManager.currentWindowMetrics.bounds
        if (!settings.lockPortrait) return current.width()
        return if (isQuarterTurn()) current.height() else current.width()
    }

    /** True for the two display rotations whose metrics have width/height swapped. */
    private fun isQuarterTurn(): Boolean {
        val rotation = displayRotation()
        if (rotation != null) {
            return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        }
        // Fallback only (DisplayManager should always resolve the default display): the
        // configuration orientation tracks the same rotation.
        return resources().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Rotation of the default display. Resolved through DisplayManager because a Service context is
     * not a visual context and [Context.getDisplay] may return null there.
     */
    private fun displayRotation(): Int? = runCatching {
        serviceContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
    }.getOrNull()

    private fun resources() = serviceContext.resources

    private fun dp(value: Float): Int =
        (value * serviceContext.resources.displayMetrics.density).roundToInt()

    private data class Footprint(
        val widthPx: Int,
        val heightPx: Int,
        val x: Int,
        val y: Int,
        val cornerRadiusDp: Int,
    )

    /** Fixed window rectangle that contains both the compact pill and the expanded view. */
    private data class Envelope(
        val widthPx: Int,
        val heightPx: Int,
        val x: Int,
        val y: Int,
    )

    /**
     * Invisible, shell-sized input window above the island. It forwards each touch into the pill
     * at the offset between the two windows, so press states and hit boxes stay where the pixel is.
     */
    private inner class TouchShield(context: Context) : View(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // [View.layoutParams] would shadow the overlay's window params here.
            val island = this@IslandOverlay.layoutParams ?: return false
            val self = touchParams ?: return false
            val forwarded = MotionEvent.obtain(event)
            forwarded.offsetLocation((self.x - island.x).toFloat(), (self.y - island.y).toFloat())
            return try {
                pill.dispatchTouchEvent(forwarded)
            } finally {
                forwarded.recycle()
            }
        }
    }

    private companion object {
        const val TAG = "IslandOverlay"

        /** Expanded layouts need room for artwork + three transport buttons. */
        const val EXPANDED_MIN_WIDTH_DP = 300
        const val EXPANDED_GROWTH_DP = 80
        const val EXPANDED_HEIGHT_DP = 84
        const val EXPANDED_CORNER_RADIUS_DP = 22
        const val EXPANDED_SCREEN_MARGIN_DP = 12

        /** Slack around both states so the spring overshoot is never clipped by the window. */
        const val ENVELOPE_PAD_DP = 4f

        /**
         * Overshoot of the morph springs as a fraction of the distance travelled (ζ ≈ 0.88 gives
         * well under 1%, so this is generous). Scaled by the frame delta so a 300 dp pill gets a
         * bigger envelope than a 28 dp one.
         */
        const val SPRING_OVERSHOOT_DP = 0.06f

        /**
         * Non-focusable, non-fullscreen, laid out against the physical screen including cutouts.
         * (The cutout is never used for positioning; the flag only lets the pill be drawn at y=0.)
         */
        const val FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }
}
