package com.zenith.nothingisland.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.Trace
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.zenith.nothingisland.data.IslandLongPressAction
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.data.IslandTapAction
import com.zenith.nothingisland.data.IslandShape
import com.zenith.nothingisland.data.buildIslandPath
import com.zenith.nothingisland.service.ClockKind
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandEvent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SWIPE_TAG = "IslandSwipe"

/**
 * The pill. Draws the shell plus the content of the current [IslandEvent] and hit-tests the few
 * tappable regions (expand/collapse, transport buttons, notification actions).
 *
 * Everything is drawn on a canvas instead of inflating views: the window is tiny, re-laid out on
 * every state change and must stay cheap. The view is never rotated.
 */
internal class IslandPillView(context: Context) : View(context) {

    var listener: IslandInteraction? = null

    private val density = resources.displayMetrics.density
    private var textScalePercent = 100
    private var customization = IslandSettings()

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(38, 255, 255, 255)
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(150, 255, 255, 255)
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(22, 255, 255, 255)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(48, 255, 255, 255)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeColorArgb = 0xFFFF453A.toInt()
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        textSize = sp(10.5f)
    }
    private val actionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(9.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val chargingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val expandedTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(28f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val smallLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val holeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.rgb(215, 25, 33)
    }
    private val holeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(26, 215, 25, 33)
    }

    private val iconPath = Path()
    private val boltPath = Path()
    private val shellPath = Path()
    private var strokeEnabled = true
    private var strokeWidthPx = 1f * density
    private var strokeColorArgb = Color.argb(38, 255, 255, 255)
    private var shape = IslandShape.PILL

    /** Compact and expanded frames in window-local px; the window itself never resizes. */
    private val compactBounds = RectF()
    private val expandedBounds = RectF()
    private var compactCornerPx = 0f
    private var expandedCornerPx = 0f
    private var framesReady = false

    /**
     * Detached app-icon bubble that sits to the right of the compact shell while a persistent
     * live activity (music, timer) is running — the app is separated from its activity, the way
     * the system island does it. Notification alerts never split.
     */
    private val satelliteBounds = RectF()
    private val satellitePath = Path()
    private val mitosisPath = Path()
    private val neckPath = Path()
    private var satelliteShown = false
    private var satelliteProgress = 0f
    private var satelliteVelocity = 0f
    private var satelliteTarget = 0f
    private var satelliteActive = false
    private var satelliteDrawable: Drawable? = null
    private var previousSatelliteDrawable: Drawable? = null
    private var satelliteSwapStartMs = 0L
    private var satelliteSwapAnimating = false
    private var ownAppIcon: Drawable? = null
    private var ownAppIconLoaded = false

    /**
     * Two coupled springs on the same 0..1 morph: the width leads and the height follows about
     * 80 ms behind. That lag is what gives the shell the system island's liquid read — it stretches
     * sideways first, then settles into the taller expanded card (and squishes the other way when
     * it retracts). Both springs are tuned just under critically damped for a barely-there bounce.
     */
    /** Spring-driven morph: 0 = compact pill, 1 = expanded view. Overshoots slightly. */
    private var widthProgress = 0f
    private var widthVelocity = 0f
    private var heightProgress = 0f
    private var heightVelocity = 0f
    private var springActive = false
    /**
     * Start of the previous frame on the shared animation clock, in [View.getDrawingTime] units
     * (uptime ns). 0 means "no previous frame"; every spring start resets it.
     */
    private var lastFrameNs = 0L
    /** The current frame's start time in uptime ms; every time-driven draw reads this one clock. */
    private var frameNowMs = 0L

    /** Reused (pos, vel) pair so the spring does not allocate on every frame. */
    private val springOut = FloatArray(2)

    /**
     * Idle contraction: 0 = the compact pill, 1 = curled up behind the camera hole so the island
     * disappears into the punch-hole when there is nothing to show. One spring drives it, but the
     * horizontal and vertical travel of the shell are read from it through two different curves
     * (see [dormantWidthCage]) so the pill narrows before it flattens.
     */
    private var dormantProgress = 1f
    private var dormantVelocity = 0f
    private var dormantTarget = 0f
    private var dormantSettling = false
    private val dormantBounds = RectF()
    private var dormantCornerPx = 1f

    /** True while the panel is in an always-on/doze state. */

    /** If a morph somehow never settles, this forces it so the window can never stay oversized. */
    private val settleWatchdog = Runnable { forceSettle() }

    /**
     * Single removable "morph settled" notification. Anonymous post {} lambdas could not be
     * cancelled, so a queued callback from a finished morph used to run after the island was
     * detached and re-attach an empty shell.
     */
    private val notifyMorphSettled = Runnable { onMorphSettled?.invoke() }

    /**
     * The expanded progress row only needs a repaint when the clock second rolls over, so this
     * single self-rescheduling runnable replaces what would otherwise be a frame loop.
     */
    private val secondTick = Runnable {
        invalidate()
        scheduleSecondTick()
    }

    /** Previous activity, crossfaded out when the island switches what it is showing. */
    private var previousContent: IslandContent? = null
    private var swapStartMs = 0L
    private var swapAnimating = false

    private var highQualityMotion = true
    private var requestedRate = 0f

    /** Overall morph position; 0 = compact pill, 1 = expanded card. */
    private val progress: Float
        get() = (widthProgress + heightProgress) / 2f

    /** Fired (posted) when the expand/collapse spring settles, so the owner can resize the window. */
    var onMorphSettled: (() -> Unit)? = null

    /** Fluidity of incoming notification text. */
    private var revealStartMs = 0L
    private var revealTextKey: String? = null
    private var revealAnimating = false

    /** Camera hole in pill-local px; radius 0 means "no hole / no avoidance". */
    private var holeCenterX = 0f
    private var holeCenterY = 0f
    private var holeRadiusPx = 0f
    private var holeGuideVisible = false

    private var content: IslandContent? = null
    private var expanded = false
    private var animating = false

    private var pressed: Target? = null
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        val target = pressed ?: return@Runnable
        if (target.kind != TargetKind.Body) return@Runnable
        longPressFired = true
        pressed = null
        activePointerId = -1
        haptic()
        val callback = listener
        if (callback != null) {
            when (customization.longPressAction) {
                IslandLongPressAction.OPEN_APP -> callback.onOpenContent()
                IslandLongPressAction.EXPAND -> callback.onBodyTap()
                IslandLongPressAction.NONE -> Unit
            }
        }
        invalidate()
    }
    private var activePointerId = -1
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Horizontal swipe-to-dismiss on an expanded message alert. */
    private var swipeCandidate = false
    private var swipeDragging = false
    private var swipeOffsetPx = 0f
    private var swipeStartX = 0f
    private var swipeSettling = false
    private var swipeSettleFrom = 0f
    private var swipeSettleStartMs = 0L
    private val swipeDismissThresholdPx get() = dp(72f)

    /** Expanded clock roll: the previous time is still sliding out while the new one drops in. */
    private var clockRollFrom: String? = null
    private var clockRollStartMs = 0L
    private var clockRollRunning = false
    private val targets = mutableListOf<Target>()
    private val animatedFrame = RectF()
    private var motionAllowed = true
    /** Bumped only when the accessible content/controls change, not on timer ticks. */
    var accessibilityRevision = 0
        private set

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * The envelope is fixed for both states; these are the two rectangles the shell morphs between.
     * Corner radii are carried separately because the expanded view is much rounder.
     */
    fun setFrames(
        compactFrame: RectF,
        expandedFrame: RectF,
        dormantFrame: RectF,
        satelliteFrame: RectF,
        compactCornerPx: Float,
        expandedCornerPx: Float,
    ) {
        if (framesReady && compactBounds == compactFrame && expandedBounds == expandedFrame &&
            dormantBounds == dormantFrame && satelliteBounds == satelliteFrame &&
            this.compactCornerPx == compactCornerPx && this.expandedCornerPx == expandedCornerPx
        ) {
            return
        }
        compactBounds.set(compactFrame)
        expandedBounds.set(expandedFrame)
        dormantBounds.set(dormantFrame)
        satelliteBounds.set(satelliteFrame)
        dormantCornerPx = dormantFrame.height() / 2f
        this.compactCornerPx = compactCornerPx
        this.expandedCornerPx = expandedCornerPx
        if (!framesReady) {
            framesReady = true
            // First frame: snap to the current state instead of animating from zero.
            val start = if (expanded) 1f else 0f
            widthProgress = start
            heightProgress = start
            widthVelocity = 0f
            heightVelocity = 0f
            springActive = false
            dormantProgress = dormantTarget
            dormantVelocity = 0f
            dormantSettling = false
            satelliteProgress = satelliteTarget
            satelliteVelocity = 0f
            satelliteActive = false
        } else {
            updateSatelliteTarget()
        }
        invalidate()
    }

    /**
     * Curls the shell up behind the camera hole ([dormant] = true) or back out into the compact
     * pill. Nothing is drawn while dormant, so the island is invisible on every wallpaper — it is
     * literally under the lens, exactly like the system island hides inside the notch.
     */
    fun setDormant(dormant: Boolean) {
        val target = if (dormant) 1f else 0f
        if (dormantTarget == target) return
        dormantTarget = target
        if (!framesReady) return
        refreshAmbientLoops()
        if (!motionAllowed) {
            dormantProgress = target
            dormantVelocity = 0f
            dormantSettling = false
            updateSatelliteTarget()
            invalidate()
            postMorphSettled()
            return
        }
        updateSatelliteTarget()
        dormantSettling = true
        lastFrameNs = 0L
        requestMotionRate(true)
        removeCallbacks(settleWatchdog)
        postDelayed(settleWatchdog, MORPH_WATCHDOG_MS)
        invalidate()
    }

    /** User customization: source accents, typography, margins, ambient rate and motion speed. */
    fun setCustomization(settings: IslandSettings) {
        val next = settings.coerced()
        if (customization == next) return
        customization = next
        textScalePercent = customization.islandTextScalePercent
        applyPalette()
        motionAllowed = customization.motionEnabled && ValueAnimator.areAnimatorsEnabled()
        if (!motionAllowed) {
            // Snapping is also the cheapest path: no springs, no reveal, no crossfade frames.
            revealAnimating = false
            swapAnimating = false
            previousContent = null
            satelliteSwapAnimating = false
            previousSatelliteDrawable = null
            forceSettle()
        }
        updateSatelliteTarget()
        refreshAmbientLoops()
        invalidate()
    }

    /** True when motion should request the panel's fastest rate (120 Hz). */
    fun setHighQualityMotion(enabled: Boolean) {
        if (highQualityMotion == enabled) return
        highQualityMotion = enabled
        if (springActive || dormantSettling || satelliteActive || animating || revealAnimating) requestMotionRate(true)
    }

    /** True while the shell is still moving (expand/retract or curling into the hole). */
    fun isMorphing(): Boolean = springActive || dormantSettling || satelliteActive

    /** True once the idle curl has fully landed behind the lens and the surface can be detached. */
    fun isDormant(): Boolean = dormantTarget == 1f && !dormantSettling && dormantProgress >= 0.999f

    /** Stops every frame source while the window is suppressed; re-entry snaps to live content. */
    fun onHidden() {
        removeCallbacks(settleWatchdog)
        removeCallbacks(notifyMorphSettled)
        removeCallbacks(secondTick)
        cancelTouch()
        framesReady = false
        springActive = false
        dormantSettling = false
        satelliteActive = false
        widthVelocity = 0f
        heightVelocity = 0f
        dormantVelocity = 0f
        satelliteVelocity = 0f
        swapAnimating = false
        previousContent = null
        revealAnimating = false
        revealTextKey = null
        satelliteSwapAnimating = false
        previousSatelliteDrawable = null
        clearSatelliteIcon()
        // Do not retain notification PendingIntents/RemoteInputs, media bitmaps, or drawables in a
        // detached view. The owner keeps the single live event needed to restore the island.
        content = null
        expanded = false
        animating = false
        satelliteTarget = 0f
        satelliteProgress = 0f
        targets.clear()
        requestMotionRate(false)
    }

    /**
     * Ends a morph immediately at its target. Used by the watchdog and by the owner when it needs
     * a settled frame right away; it always reports back so the window can be re-measured.
     */
    fun forceSettle() {
        if (!springActive && !dormantSettling && !satelliteActive) return
        val target = if (expanded) 1f else 0f
        widthProgress = target
        heightProgress = target
        widthVelocity = 0f
        heightVelocity = 0f
        springActive = false
        dormantProgress = dormantTarget
        dormantVelocity = 0f
        dormantSettling = false
        satelliteProgress = satelliteTarget
        satelliteVelocity = 0f
        satelliteActive = false
        if (satelliteTarget == 0f) clearSatelliteIcon()
        removeCallbacks(settleWatchdog)
        requestMotionRate(
            animating || revealAnimating || satelliteSwapAnimating,
            ambientOnly = animating,
        )
        postMorphSettled()
    }

    /** Bottom edge of the drawn shell, for anchoring the inline reply window. */
    fun visualBottomPx(): Float =
        compactBounds.bottom + (expandedBounds.bottom - compactBounds.bottom) * heightProgress

    /** Outline settings; called by [IslandOverlay] with the calibrated values. */
    fun setStroke(enabled: Boolean, widthPx: Float, colorArgb: Int) {
        if (strokeEnabled == enabled && strokeWidthPx == widthPx && strokeColorArgb == colorArgb) return
        strokeEnabled = enabled
        strokeWidthPx = widthPx
        strokeColorArgb = colorArgb
        invalidate()
    }

    /** Shell outline: pill or notch. */
    fun setShape(newShape: IslandShape) {
        if (shape == newShape) return
        shape = newShape
        invalidate()
    }

    /**
     * Camera hole in pill-local px, supplied by [IslandOverlay] after every apply. Radius 0 means
     * the hole is disabled (or does not intersect the pill), which skips all avoidance work.
     */
    fun setHole(centerXPx: Float, centerYPx: Float, radiusPx: Float, guideVisible: Boolean) {
        if (holeCenterX == centerXPx && holeCenterY == centerYPx &&
            holeRadiusPx == radiusPx && holeGuideVisible == guideVisible
        ) {
            return
        }
        holeCenterX = centerXPx
        holeCenterY = centerYPx
        holeRadiusPx = radiusPx
        holeGuideVisible = guideVisible
        invalidate()
    }

    /** Called by [IslandOverlay] for every holder emission. */
    fun render(event: IslandEvent?) {
        val newContent = event?.content
        val newExpanded = event?.expanded == true
        motionAllowed = customization.motionEnabled && ValueAnimator.areAnimatorsEnabled()

        // A new notification arrives: reveal its text fluidly (per character, short stagger).
        val notification = newContent as? IslandContent.Notification
        if (notification != null && motionAllowed) {
            val key = "${notification.key}|${notification.title}|${notification.text}"
            if (key != revealTextKey) {
                revealTextKey = key
                revealStartMs = SystemClock.uptimeMillis()
                revealAnimating = true
            }
        } else {
            revealTextKey = null
            revealAnimating = false
        }

        // A countdown/stopwatch card re-renders once a second. Keep the outgoing text so the
        // expanded card can roll the changed digits instead of hard-cutting the whole row.
        val previousClock = content as? IslandContent.ClockActivity
        val nextClock = newContent as? IslandContent.ClockActivity
        clockRollRunning = false
        clockRollFrom = null
        if (motionAllowed && newExpanded && previousClock != null && nextClock != null &&
            previousClock.key == nextClock.key && previousClock.timeText != nextClock.timeText
        ) {
            clockRollFrom = previousClock.timeText
            clockRollStartMs = SystemClock.uptimeMillis()
            clockRollRunning = true
        }

        // The island swaps activity while already settled (timer -> notification, ...): crossfade
        // the old card out instead of hard-cutting, the way a system activity update reads.
        val oldKey = contentKey(content)
        val newKey = contentKey(newContent)
        if (oldKey != newKey || expanded != newExpanded) {
            cancelTouch()
            // A swipe belongs to the alert it started on; a different card must not inherit it.
            swipeDragging = false
            swipeCandidate = false
            swipeSettling = false
            swipeOffsetPx = 0f
            accessibilityRevision++
        }
        if (motionAllowed && oldKey != null && newKey != null && oldKey != newKey &&
            !springActive && framesReady
        ) {
            previousContent = content
            swapStartMs = SystemClock.uptimeMillis()
            swapAnimating = true
        } else if (newKey == null || oldKey != newKey) {
            previousContent = null
            swapAnimating = false
        }

        content = newContent
        expanded = newExpanded
        refreshAmbientLoops()
        // Keep the outgoing icon while the daughter circle re-absorbs into the shell. A new
        // persistent activity replaces it; alerts/battery confirmations only retract it.
        val nextSatelliteIcon = satelliteIcon(newContent)
        if (nextSatelliteIcon != null && nextSatelliteIcon !== satelliteDrawable) {
            previousSatelliteDrawable = satelliteDrawable
            satelliteDrawable = nextSatelliteIcon
            satelliteSwapStartMs = SystemClock.uptimeMillis()
            satelliteSwapAnimating = motionAllowed && previousSatelliteDrawable != null
        }
        updateSatelliteTarget()

        val target = if (newExpanded) 1f else 0f
        if (!motionAllowed) {
            widthProgress = target
            heightProgress = target
            widthVelocity = 0f
            heightVelocity = 0f
            springActive = false
            dormantProgress = dormantTarget
            dormantVelocity = 0f
            dormantSettling = false
            swapAnimating = false
            previousContent = null
            revealAnimating = false
            removeCallbacks(settleWatchdog)
            requestMotionRate(false)
        } else if (framesReady && !springActive && abs(progress - target) > 0.001f) {
            springActive = true
            lastFrameNs = 0L
            // Width leads, height follows: start both from where they currently sit.
            if (abs(widthProgress - target) < 0.001f) widthVelocity = 0f
            if (abs(heightProgress - target) < 0.001f) heightVelocity = 0f
            requestMotionRate(true)
            removeCallbacks(settleWatchdog)
            postDelayed(settleWatchdog, MORPH_WATCHDOG_MS)
        }
        invalidate()
        scheduleSecondTick()
    }

    /**
     * Repaints a per-second tick: the countdown digits and the expanded progress row come from the
     * stored snapshot, so nothing is re-measured. Deliberately skips the accessibility revision —
     * announcing a running timer every second would be hostile; structural changes still use render().
     */
    fun applyTick(event: IslandEvent?) {
        val newContent = event?.content
        if (event == null || newContent == null) {
            scheduleSecondTick()
            return
        }
        motionAllowed = customization.motionEnabled && ValueAnimator.areAnimatorsEnabled()
        // Same digit roll as render(): keep the outgoing text so the row rolls instead of hard-cutting.
        val previousClock = content as? IslandContent.ClockActivity
        val nextClock = newContent as? IslandContent.ClockActivity
        clockRollRunning = false
        clockRollFrom = null
        if (motionAllowed && event.expanded && previousClock != null && nextClock != null &&
            previousClock.key == nextClock.key && previousClock.timeText != nextClock.timeText
        ) {
            clockRollFrom = previousClock.timeText
            clockRollStartMs = SystemClock.uptimeMillis()
            clockRollRunning = true
        }
        content = newContent
        invalidate()
        scheduleSecondTick()
    }

    /**
     * Arms one repaint for the next whole second, only while an expanded playing card is actually
     * on screen. A paused card is frozen, so it schedules nothing and costs nothing.
     */
    private fun scheduleSecondTick() {
        removeCallbacks(secondTick)
        val media = content as? IslandContent.Media ?: return
        if (!expanded || !isAttachedToWindow || !isShown) return
        if (!media.isPlaying || media.isPaused) return
        // No duration means a live stream: estimatePositionMs is frozen, so a tick would repaint
        // the same frame forever.
        if (media.durationMs <= 0L) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (!power.isInteractive) return
        val position = media.estimatePositionMs(SystemClock.elapsedRealtime())
        postDelayed(secondTick, 1_000L - (position % 1_000L))
    }

    /**
     * Shell and text palette. Every colour the user can change is applied here once per settings
     * emission, never per frame; per-source accents are still assigned by the drawing helpers.
     */
    private fun applyPalette() {
        val alpha = (255f * customization.shellOpacityPercent / 100f).toInt().coerceIn(0, 255)
        shellPaint.color = (alpha shl 24) or (customization.shellColorArgb and 0x00FFFFFF)
        val primary = customization.textColorArgb
        val secondary = customization.secondaryTextColorArgb
        titlePaint.color = primary
        actionPaint.color = primary
        whitePaint.color = primary
        progressPaint.color = primary
        chargingPaint.color = primary
        expandedTimePaint.color = primary
        subtitlePaint.color = secondary
        mutedPaint.color = secondary
    }

    /** Decorative loops are opt-in, visible-only, and never run while curled behind the lens. */
    private fun refreshAmbientLoops() {
        val enabled = IslandRenderDemand.ambientLoopsEnabled(
            motionAllowed = motionAllowed,
            userEnabled = customization.ambientAnimationsEnabled,
            dormant = dormantTarget == 1f,
        )
        animating = enabled && (content as? IslandContent.Media)?.isPlaying == true
        if (!animating) {
            requestMotionRate(isMorphing() || revealAnimating || satelliteSwapAnimating)
        }
    }

    /** Identity of what the island is showing, ignoring per-second tick values. */
    private fun contentKey(value: IslandContent?): String? = when (value) {
        null -> null
        is IslandContent.Media -> "media:${value.title}:${value.artist}"
        is IslandContent.Notification -> "notification:${value.key}"
        is IslandContent.Charging -> "charging"
        is IslandContent.BatteryLow -> "battery-low"
        is IslandContent.PowerSave -> "power-save:${value.enabled}"
        is IslandContent.Call -> "call:${value.key}"
        is IslandContent.Download -> "download:${value.key}"
        is IslandContent.Navigation -> "navigation:${value.key}:${value.instruction}"
        is IslandContent.ClockActivity -> "clock:${value.key}:${value.kind}:${value.isRunning}"
        is IslandContent.Timer -> "timer"
    }

    // ---------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Trace sections are free when no trace is running (Unit 1, docs/perf/PERF_BUDGET.md).
        Trace.beginSection("island.onDraw")
        try {
            drawFrame(canvas)
        } finally {
            Trace.endSection()
        }
    }

    private fun drawFrame(canvas: Canvas) {
        if (!framesReady) return
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) return

        // One clock for the whole frame: the frame's own start time, which is what the vsync
        // actually presented, instead of sampling mid-draw (that added sampling jitter to every
        // spring). Falls back to uptime when the platform has not stamped a frame yet.
        val nowNs = frameTimeNs()
        val now = nowNs / 1_000_000L
        frameNowMs = now
        if (springActive || dormantSettling || satelliteActive) advanceMotion(nowNs)
        if (clockRollRunning &&
            (now - clockRollStartMs) >= CLOCK_ROLL_MS
        ) {
            clockRollRunning = false
            clockRollFrom = null
        } else if (clockRollRunning) {
            postInvalidateOnAnimation()
        }
        if (!swipeDragging && swipeSettling) {
            val t = ((now - swipeSettleStartMs) / SWIPE_SETTLE_MS.toFloat()).coerceIn(0f, 1f)
            swipeOffsetPx = swipeSettleFrom * (1f - easeOutCubic(t))
            if (t >= 1f) {
                swipeSettling = false
                swipeOffsetPx = 0f
            } else {
                postInvalidateOnAnimation()
            }
        }
        val p = progress.coerceIn(0f, 1f)
        val widthCage = dormantWidthCage(dormantProgress)
        val frame = currentFrame()
        val corner = lerp(
            compactCornerPx + (expandedCornerPx - compactCornerPx) * p,
            dormantCornerPx,
            widthCage,
        )
        // Contracted to a circle behind the lens it must be a circle whatever the shape setting is.
        val shellShape = if (widthCage > 0.35f) IslandShape.PILL else shape

        // Staged content, the way the system island hands over between presentations: the shell
        // always leads, the card's content follows it in with a fade, a 6% scale-up and a few dp
        // of rise; retracting reverses the order so nothing lingers inside a shrinking shell.
        val expandedIn = smoothStep((p - 0.06f) / 0.52f)
        val compactOut = 1f - smoothStep(p / 0.55f)

        val swapT = if (swapAnimating) {
            ((now - swapStartMs) / SWAP_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        if (swapAnimating && swapT >= 1f) {
            swapAnimating = false
            previousContent = null
        }

        val current = content
        val outgoing = previousContent
        val swapping = outgoing != null && swapAnimating && swapT < 1f

        val satelliteP = satelliteProgress.coerceIn(0f, 1f)
        val satelliteVisible = satelliteDrawable != null && satelliteP > 0.001f && !satelliteBounds.isEmpty
        val satelliteSwapT = if (satelliteSwapAnimating) {
            ((now - satelliteSwapStartMs) / SWAP_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        if (satelliteSwapAnimating && satelliteSwapT >= 1f) {
            satelliteSwapAnimating = false
            previousSatelliteDrawable = null
        }
        if (satelliteVisible && satelliteP < IslandMotion.SATELLITE_NECK_BREAK) {
            // One union silhouette while the neck exists: no seam, no double stroke, no fade.
            drawShell(canvas, frame, corner, shellShape, paintShell = false)
            drawMitosis(canvas, frame, satelliteP, satelliteSwapT)
        } else {
            drawShell(canvas, frame, corner, shellShape)
            if (satelliteVisible) drawDetachedSatellite(canvas, frame, satelliteP, satelliteSwapT)
        }
        targets.clear()

        canvas.save()
        canvas.clipPath(shellPath)
        // Content fades out with the narrowing (not with the flattening), so nothing is left as a
        // hard-clipped sliver inside an already thin shell.
        if (swapping) {
            drawContentStack(
                canvas, outgoing, expandedIn, compactOut, widthCage,
                stackAlpha = 1f - swapT,
            )
        }
        if (current != null) {
            drawContentStack(
                canvas, current, expandedIn, compactOut, widthCage,
                stackAlpha = if (swapping) swapT else 1f,
            )
        }
        canvas.restore()

        if (holeGuideVisible && holeRadiusPx > 0f) drawHoleGuide(canvas)

        // No hit targets while morphing: a half-grown button should never be tappable.
        if (springActive || dormantSettling || satelliteActive) {
            targets.clear()
        } else if (current != null && current !is IslandContent.Charging &&
            current !is IslandContent.BatteryLow && current !is IslandContent.PowerSave
        ) {
            // Whole-pill fallback target. Charging is a non-interactive confirmation.
            val body = currentFrame()
            targets.add(0, Target(TargetKind.Body, -1, body))
            // The detached bubble is part of the island: tapping it must do what tapping the
            // shell does, never nothing.
            if (satelliteShown && satelliteP > 0.995f) {
                targets.add(0, Target(TargetKind.Body, -1, RectF(satelliteBounds)))
            }
        }

        val revealRunning = revealAnimating &&
            (now - revealStartMs) < REVEAL_TOTAL_MS
        if (revealAnimating && !revealRunning) revealAnimating = false
        val busy = animating || springActive || dormantSettling || satelliteActive ||
            revealRunning || swapAnimating || satelliteSwapAnimating || clockRollRunning
        val ambientOnly = animating && !springActive && !dormantSettling &&
            !satelliteActive && !revealRunning && !swapAnimating && !satelliteSwapAnimating
        requestMotionRate(busy, ambientOnly)
        if (busy) postInvalidateOnAnimation()
    }

    /** One activity in both presentations, crossfaded by [stackAlpha] when activities swap. */
    private fun drawContentStack(
        canvas: Canvas,
        value: IslandContent,
        expandedIn: Float,
        compactOut: Float,
        cage: Float,
        stackAlpha: Float,
    ) {
        if (stackAlpha <= 0.002f) return
        // Content rides the shell's growth out of the hole instead of appearing on a dot.
        val contentIn = 1f - cage
        val compactAlpha = compactOut * stackAlpha * contentIn
        val expandedAlpha = expandedIn * stackAlpha * contentIn
        if (compactAlpha > 0.002f) {
            drawContentLayer(
                canvas = canvas,
                current = value,
                expandedVariant = false,
                frame = compactBounds,
                alpha = compactAlpha,
                // The compact layout shrinks a touch as it hands over, so the transition reads as
                // one surface changing shape rather than two cards swapping.
                scale = 1f - 0.05f * (1f - compactOut),
                risePx = 0f,
            )
        }
        if (expandedAlpha > 0.002f) {
            drawContentLayer(
                canvas = canvas,
                current = value,
                expandedVariant = true,
                frame = expandedBounds,
                alpha = expandedAlpha,
                scale = 0.94f + 0.06f * expandedIn,
                risePx = (1f - expandedIn) * dp(5f),
            )
        }
    }

    private fun drawContentLayer(
        canvas: Canvas,
        current: IslandContent,
        expandedVariant: Boolean,
        frame: RectF,
        alpha: Float,
        scale: Float = 1f,
        risePx: Float = 0f,
    ) {
        val firstTarget = targets.size
        val save = canvas.save()
        // Swipe-to-dismiss moves the message card out from under the shell, fading as it goes.
        val swipe = if (current is IslandContent.Notification) swipeOffsetPx else 0f
        val swipeFade = if (swipe == 0f) 1f else
            (1f - 0.55f * (abs(swipe) / swipeDismissThresholdPx).coerceIn(0f, 1f))
        if (alpha < 0.999f || swipeFade < 0.999f) {
            // Bound the offscreen layer to the card, not the whole morph envelope.
            canvas.saveLayerAlpha(
                frame.left, frame.top + risePx, frame.right, frame.bottom + risePx,
                (alpha * swipeFade * 255f).toInt().coerceIn(0, 255),
            )
        }
        if (swipe != 0f) canvas.translate(swipe, 0f)
        if (scale != 1f) canvas.scale(scale, scale, frame.centerX(), frame.centerY())
        if (risePx != 0f) canvas.translate(0f, risePx)
        canvas.translate(frame.left, frame.top)
        val widthPx = frame.width()
        val heightPx = frame.height()
        val areas = contentAreas(
            widthPx,
            heightPx,
            holeCenterX - frame.left,
            holeCenterY - frame.top,
        )
        when (current) {
            is IslandContent.Media ->
                if (expandedVariant) {
                    drawMediaExpanded(canvas, current)
                } else {
                    drawMediaCompact(canvas, current, areas, heightPx)
                }

            is IslandContent.Notification ->
                if (expandedVariant) {
                    drawNotificationExpanded(canvas, current)
                } else {
                    drawNotificationCompact(canvas, current, areas, heightPx)
                }

            is IslandContent.Charging -> drawCharging(canvas, current, areas, heightPx)
            is IslandContent.BatteryLow -> drawBatteryLow(canvas, current, areas, heightPx)
            is IslandContent.PowerSave -> drawPowerSave(canvas, current, areas, heightPx)

            is IslandContent.Call ->
                if (expandedVariant) drawCallExpanded(canvas, current)
                else drawCompactIconText(
                    canvas, current.icon, current.caller, areas, heightPx,
                    customization.textColorArgb, iconDetached = shellIconDetached(current),
                )

            is IslandContent.Download ->
                if (expandedVariant) drawDownloadExpanded(canvas, current)
                else drawCompactIconText(
                    canvas, current.icon,
                    current.title.ifBlank { current.appLabel },
                    areas, heightPx, customization.downloadColorArgb,
                    iconDetached = shellIconDetached(current),
                )

            is IslandContent.Navigation ->
                if (expandedVariant) drawNavigationExpanded(canvas, current)
                else drawCompactIconText(
                    canvas, current.icon, current.instruction, areas, heightPx,
                    customization.textColorArgb, iconDetached = shellIconDetached(current),
                )

            is IslandContent.Timer ->
                if (expandedVariant) {
                    drawTimerExpanded(canvas, current)
                } else {
                    drawTimerCompact(canvas, current, areas, heightPx)
                }

            is IslandContent.ClockActivity ->
                if (expandedVariant) {
                    drawClockExpanded(canvas, current)
                } else {
                    drawClockCompact(canvas, current, areas, heightPx)
                }
        }
        canvas.restoreToCount(save)
        // Helpers record hit boxes while the canvas is translated to the frame origin; move them
        // into window space so a frame offset can never shift a button's touch area.
        if (frame.left != 0f || frame.top != 0f) {
            for (index in firstTarget until targets.size) {
                targets[index].rect.offset(frame.left, frame.top)
            }
        }
    }

    private fun drawShell(
        canvas: Canvas,
        frame: RectF,
        cornerPx: Float,
        shellShape: IslandShape,
        path: Path = shellPath,
        paintShell: Boolean = true,
    ) {
        // Screen-off/doze never renders the island, so the shell always uses the user's stroke.
        val strokePx = strokeWidthPx
        val strokeOn = strokeEnabled && strokeWidthPx > 0f
        // Without a stroke the shell fills the frame; with one, the outline is centred on the
        // edge, so the shape is inset by half the stroke width.
        val half = if (strokeOn) strokePx / 2f else 0f
        val innerWidth = (frame.width() - half * 2f).coerceAtLeast(1f)
        val innerHeight = (frame.height() - half * 2f).coerceAtLeast(1f)
        buildIslandPath(path, shellShape, innerWidth, innerHeight, cornerPx)
        path.offset(frame.left + half, frame.top + half)
        if (!paintShell) return
        canvas.drawPath(path, shellPaint)
        if (strokeOn) {
            strokePaint.strokeWidth = strokePx
            strokePaint.color = strokeColorArgb
            canvas.drawPath(path, strokePaint)
        }
    }

    /** Resting circle geometry interpolated from inside the trailing shell edge. */
    private fun satelliteCenterX(frame: RectF, progress: Float, radius: Float): Float =
        lerp(frame.right - radius, satelliteBounds.centerX(), progress)

    private fun satelliteCenterY(frame: RectF, progress: Float): Float =
        lerp(frame.centerY(), satelliteBounds.centerY(), progress)

    private fun satelliteRadius(progress: Float): Float =
        satelliteBounds.height() / 2f * (0.72f + 0.28f * progress)

    private fun shellStrokeHalf(): Float {
        val strokePx = strokeWidthPx
        val strokeOn = strokeEnabled && strokeWidthPx > 0f
        return if (strokeOn) strokePx / 2f else 0f
    }

    /**
     * Cell-division detach/attach: the daughter circle grows out of the shell, a Bézier neck
     * thins and pinches off, and the same spring reverses the union on re-attachment. Path.union
     * keeps the outline continuous; all paths are reused and no full-window layer is allocated.
     */
    private fun drawMitosis(canvas: Canvas, frame: RectF, progress: Float, swapT: Float) {
        val radius = satelliteRadius(progress)
        val cx = satelliteCenterX(frame, progress, radius)
        val cy = satelliteCenterY(frame, progress)
        val half = shellStrokeHalf()
        satellitePath.reset()
        satellitePath.addCircle(cx, cy, (radius - half).coerceAtLeast(0f), Path.Direction.CW)
        mitosisPath.set(shellPath)
        mitosisPath.op(satellitePath, Path.Op.UNION)

        val neck = IslandMotion.satelliteNeckShare(progress)
        val shellRight = frame.right - half
        val circleLeft = cx - radius + half
        if (neck > 0.001f && circleLeft > shellRight - dp(1f)) {
            val neckHalf = radius * 0.62f * neck
            val startX = shellRight - dp(1f)
            val endX = circleLeft + dp(1f)
            val c1 = lerp(startX, endX, 0.45f)
            val c2 = lerp(startX, endX, 0.72f)
            val topEnd = cy - radius * 0.72f
            val bottomEnd = cy + radius * 0.72f
            neckPath.reset()
            neckPath.moveTo(startX, cy - neckHalf)
            neckPath.cubicTo(c1, cy - neckHalf, c2, topEnd, endX, topEnd)
            neckPath.lineTo(endX, bottomEnd)
            neckPath.cubicTo(c2, bottomEnd, c1, cy + neckHalf, startX, cy + neckHalf)
            neckPath.close()
            mitosisPath.op(neckPath, Path.Op.UNION)
        }
        canvas.drawPath(mitosisPath, shellPaint)
        if (half > 0f) {
            strokePaint.strokeWidth = half * 2f
            strokePaint.color = strokeColorArgb
            canvas.drawPath(mitosisPath, strokePaint)
        }
        drawSatelliteIcon(canvas, cx, cy, radius, IslandMotion.satelliteIconShare(progress), swapT)
    }

    private fun drawDetachedSatellite(canvas: Canvas, frame: RectF, progress: Float, swapT: Float) {
        val radius = satelliteRadius(progress)
        val cx = satelliteCenterX(frame, progress, radius)
        val cy = satelliteCenterY(frame, progress)
        val half = shellStrokeHalf()
        satellitePath.reset()
        satellitePath.addCircle(cx, cy, (radius - half).coerceAtLeast(0f), Path.Direction.CW)
        canvas.drawPath(satellitePath, shellPaint)
        if (half > 0f) {
            strokePaint.strokeWidth = half * 2f
            strokePaint.color = strokeColorArgb
            canvas.drawPath(satellitePath, strokePaint)
        }
        val iconShare = IslandMotion.satelliteIconShare(progress)
        drawSatelliteIcon(canvas, cx, cy, radius, iconShare, swapT)
        val stacked = (content as? IslandContent.Notification)?.stackCount ?: 1
        if (stacked > 1) drawStackBadge(canvas, cx, cy, radius, stacked, iconShare)
    }

    /** Count of queued message alerts, drawn on the bubble's top-right like the system badge. */
    private fun drawStackBadge(canvas: Canvas, cx: Float, cy: Float, radius: Float, count: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        val opacity = (alpha * 255f).toInt().coerceIn(0, 255)
        val badgeRadius = dp(7.5f)
        val bx = cx + radius * 0.72f
        val by = cy - radius * 0.72f
        badgePaint.color = badgeColorArgb
        badgePaint.alpha = opacity
        canvas.drawCircle(bx, by, badgeRadius, badgePaint)
        badgeTextPaint.textSize = sp(9f)
        badgeTextPaint.color = Color.WHITE
        badgeTextPaint.alpha = opacity
        val label = if (count > 9) "9+" else count.toString()
        val baseline = by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText(label, bx, baseline, badgeTextPaint)
    }

    private fun drawSatelliteIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        alpha: Float,
        swapT: Float,
    ) {
        val icon = satelliteDrawable ?: return
        if (alpha <= 0.004f) return
        val size = dp(
            IslandLayout.satelliteIconSizeDp(
                diameterDp = radius * 2f / density,
                paddingDp = customization.satelliteIconPaddingDp.toFloat(),
                strokeDp = shellStrokeHalf() / density,
            ),
        )
        if (size <= 0f) return
        val rect = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        canvas.save()
        canvas.clipPath(satellitePath)
        val previous = previousSatelliteDrawable
        if (previous != null && swapT < 1f) drawSatelliteIconLayer(canvas, previous, rect, alpha * (1f - swapT))
        drawSatelliteIconLayer(canvas, icon, rect, alpha * (if (previous != null) swapT else 1f))
        canvas.restore()
    }

    private fun drawSatelliteIconLayer(canvas: Canvas, icon: Drawable, rect: RectF, alpha: Float) {
        if (alpha <= 0.004f) return
        val save = if (alpha < 0.995f) {
            canvas.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, (alpha * 255f).toInt().coerceIn(0, 255))
        } else {
            canvas.save()
        }
        drawIcon(canvas, icon, rect)
        canvas.restoreToCount(save)
    }

    private fun updateSatelliteTarget() {
        val eligible = !expanded && dormantTarget == 0f &&
            !satelliteBounds.isEmpty && satelliteIcon(content) != null
        val target = if (eligible) 1f else 0f
        satelliteShown = eligible
        if (satelliteTarget == target && !satelliteActive) return
        satelliteTarget = target
        if (!framesReady) return
        if (!motionAllowed) {
            satelliteProgress = target
            satelliteVelocity = 0f
            satelliteActive = false
            satelliteSwapAnimating = false
            previousSatelliteDrawable = null
            if (target == 0f) clearSatelliteIcon()
            invalidate()
            return
        }
        if (abs(satelliteProgress - target) > 0.001f || abs(satelliteVelocity) > 0.05f) {
            satelliteActive = true
            lastFrameNs = 0L
            requestMotionRate(true)
            removeCallbacks(settleWatchdog)
            postDelayed(settleWatchdog, MORPH_WATCHDOG_MS)
            invalidate()
        }
    }

    private fun clearSatelliteIcon() {
        satelliteDrawable = null
        previousSatelliteDrawable = null
        satelliteSwapAnimating = false
        satelliteShown = false
    }

    /** Compact-row glyph class; see [IslandIconPlacement] for the placement rules. */
    private fun iconKind(value: IslandContent?): IslandIconKind = when (value) {
        is IslandContent.Notification, is IslandContent.Call -> IslandIconKind.ALERT
        is IslandContent.Download, is IslandContent.Navigation -> IslandIconKind.ACTIVITY_ICON
        // The clock card carries its own timer/stopwatch glyph, so it is content, not identity.
        is IslandContent.Media, is IslandContent.Timer,
        is IslandContent.ClockActivity -> IslandIconKind.ACTIVITY_ART
        else -> IslandIconKind.NONE
    }

    private fun bubbleTakesIcon(value: IslandContent?): Boolean = IslandIconPlacement.bubbleShowsAppIcon(
        kind = iconKind(value),
        detachedAppIcon = customization.detachedAppIcon,
        alertIconInBubble = customization.alertIconInBubble,
    )

    /**
     * Which icon (if any) rides in the detached bubble.
     *
     * Persistent activities (music, timer, maps, downloads) use [IslandSettings.detachedAppIcon].
     * Notification/call alerts use [IslandSettings.alertIconInBubble]: the app icon leaves the shell
     * so the compact pill shows the message text, and because the alert is transient the bubble
     * retracts with the same mitosis spring the moment its hold expires and the holder drops it.
     */
    private fun satelliteIcon(value: IslandContent?): Drawable? {
        if (!bubbleTakesIcon(value)) return null
        return when (value) {
            is IslandContent.Media -> value.appIcon
            is IslandContent.Timer -> timerAppIcon()
            is IslandContent.Navigation -> value.icon
            is IslandContent.Download -> value.icon
            // A countdown's own glyph already says "timer", so the Clock app icon never detaches.
            is IslandContent.ClockActivity -> null
            is IslandContent.Notification -> if (value.showBubble) value.icon else null
            is IslandContent.Call -> value.icon
            else -> null
        }
    }

    /**
     * True while this row's app icon lives in the bubble, so the shell draws text only. The bubble
     * target (not its animated progress) is authoritative: the icon must not flash back into the
     * shell for the few frames the bubble spends retracting.
     */
    private fun shellIconDetached(value: IslandContent?): Boolean =
        satelliteTarget > 0f && bubbleTakesIcon(value) &&
            !IslandIconPlacement.shellKeepsIcon(iconKind(value), bubbleVisible = true)

    /** The island's own launcher icon, loaded once. */
    private fun timerAppIcon(): Drawable? {
        if (!ownAppIconLoaded) {
            ownAppIconLoaded = true
            ownAppIcon = runCatching {
                context.applicationInfo.loadIcon(context.packageManager)
            }.getOrNull()
        }
        return ownAppIcon
    }

    /** True while the detached app-icon bubble is part of the current compact presentation. */
    fun hasSatellite(): Boolean = satelliteShown

    /** Single centred area, or left/right around the hole. */
    private class Areas(val left: RectF?, val right: RectF?, val single: RectF) {
        val split: Boolean get() = left != null && right != null

        /** Wider of the two sides when split; the only usable area otherwise. */
        val widest: RectF
            get() = if (split) {
                if (left!!.width() >= right!!.width()) left else right
            } else {
                single
            }
    }

    // ---------------------------------------------------------------------------------------
    // Motion
    // ---------------------------------------------------------------------------------------

    /**
     * The frame's start time, in uptime nanoseconds. `getDrawingTime()` is the time the hierarchy
     * started drawing this frame; it is set by the window's traversal, so it is valid for the
     * accessibility-overlay window too. A 0 stamp (first frame / detached) uses uptime, which is
     * the same base, so the clock stays continuous.
     */
    private fun frameTimeNs(): Long {
        val drawingMs = drawingTime
        return if (drawingMs > 0L) {
            drawingMs * 1_000_000L
        } else {
            SystemClock.uptimeMillis() * 1_000_000L
        }
    }

    /**
     * The expand/retract springs run at two different response times (width leads, height trails)
     * and the idle contraction runs a third; all three share one frame clock so they cannot drift.
     */
    private fun advanceMotion(nowNs: Long) {
        Trace.beginSection("island.motion")
        try {
            advanceMotionLocked(nowNs)
        } finally {
            Trace.endSection()
        }
    }

    private fun advanceMotionLocked(nowNs: Long) {
        val dt = IslandMotion.frameDelta(lastFrameNs, nowNs)
        lastFrameNs = nowNs

        if (springActive) {
            val target = if (expanded) 1f else 0f

            val motionScale = customization.motionScalePercent / 100f
            stepSpring(widthProgress, widthVelocity, target, IslandMotion.WIDTH_OMEGA * motionScale, IslandMotion.WIDTH_ZETA, dt)
            widthProgress = springOut[0]
            widthVelocity = springOut[1]

            stepSpring(heightProgress, heightVelocity, target, IslandMotion.HEIGHT_OMEGA * motionScale, IslandMotion.HEIGHT_ZETA, dt)
            heightProgress = springOut[0]
            heightVelocity = springOut[1]

            val settled = abs(target - widthProgress) < 0.0015f &&
                abs(widthVelocity) < 0.05f &&
                abs(target - heightProgress) < 0.0015f &&
                abs(heightVelocity) < 0.05f
            if (settled) {
                widthProgress = target
                heightProgress = target
                widthVelocity = 0f
                heightVelocity = 0f
                springActive = false
            }
        }

        if (satelliteActive) {
            stepSpring(
                satelliteProgress, satelliteVelocity, satelliteTarget,
                IslandMotion.SATELLITE_OMEGA * (customization.motionScalePercent / 100f),
                IslandMotion.SATELLITE_ZETA, dt,
            )
            satelliteProgress = springOut[0]
            satelliteVelocity = springOut[1]
            if (abs(satelliteTarget - satelliteProgress) < 0.0015f && abs(satelliteVelocity) < 0.05f) {
                satelliteProgress = satelliteTarget
                satelliteVelocity = 0f
                satelliteActive = false
                if (satelliteTarget == 0f) clearSatelliteIcon()
            }
        }

        if (dormantSettling) {
            stepSpring(
                dormantProgress, dormantVelocity, dormantTarget,
                IslandMotion.DORMANT_OMEGA * (customization.motionScalePercent / 100f), IslandMotion.DORMANT_ZETA, dt,
            )
            dormantProgress = springOut[0]
            dormantVelocity = springOut[1]
            if (abs(dormantTarget - dormantProgress) < 0.0015f &&
                abs(dormantVelocity) < 0.05f
            ) {
                dormantProgress = dormantTarget
                dormantVelocity = 0f
                dormantSettling = false
            }
        }

        if (!springActive && !dormantSettling && !satelliteActive) {
            removeCallbacks(settleWatchdog)
            requestMotionRate(
                animating || revealAnimating || satelliteSwapAnimating,
                ambientOnly = animating,
            )
            // Let the owner re-place the touch window now that the shell is at rest.
            postMorphSettled()
        }
    }

    /** Coalesced so only the last settle of a frame notifies the owner, and hide() can cancel it. */
    private fun postMorphSettled() {
        removeCallbacks(notifyMorphSettled)
        post(notifyMorphSettled)
    }

    private fun stepSpring(
        position: Float,
        velocity: Float,
        target: Float,
        omega: Float,
        zeta: Float,
        dt: Float,
    ) {
        IslandMotion.stepSpring(position, velocity, target, omega, zeta, dt, springOut)
    }

    private fun currentFrame(): RectF {
        // Horizontal edges follow the leading spring, vertical edges the trailing one.
        val w = widthProgress
        val h = heightProgress
        val frame = animatedFrame
        frame.set(
            lerp(compactBounds.left, expandedBounds.left, w),
            lerp(compactBounds.top, expandedBounds.top, h),
            lerp(compactBounds.right, expandedBounds.right, w),
            lerp(compactBounds.bottom, expandedBounds.bottom, h),
        )
        // Then the idle contraction wraps that frame into the circle behind the camera hole: the
        // sides close in first and the top/bottom follow, which is what makes the shell slide up
        // into the lens instead of shrinking around its own centre.
        if (dormantProgress > 0.001f) {
            val widthCage = dormantWidthCage(dormantProgress)
            val heightCage = dormantHeightCage(dormantProgress)
            frame.set(
                lerp(frame.left, dormantBounds.left, widthCage),
                lerp(frame.top, dormantBounds.top, heightCage),
                lerp(frame.right, dormantBounds.right, widthCage),
                lerp(frame.bottom, dormantBounds.bottom, heightCage),
            )
        }
        return frame
    }

    /**
     * Horizontal share of the curl. Ease-out, so most of the width is gone early: by the time the
     * height starts to collapse more than half of the sideways travel is done. It still finishes
     * exactly at 1, so the last of the width closes while the height does.
     */
    private fun dormantWidthCage(cage: Float): Float {
        val t = cage.coerceIn(0f, 1f)
        return IslandMotion.dormantWidthShare(t)
    }

    /**
     * Vertical share of the curl. It holds still for the first [DORMANT_HEIGHT_START] of the glide
     * (the shell is only narrowing there) and then runs smoothly to the hole's height, which is
     * also the moment the shell drops behind the lens.
     */
    private fun dormantHeightCage(cage: Float): Float {
        val t = cage.coerceIn(0f, 1f)
        return IslandMotion.dormantHeightShare(t)
    }

    /**
     * Asks the panel for the frame rate this view needs. Motion (morph, reveal, playing bars) runs
     * at 120 Hz — the pill is a small window, so it costs one small layer per frame — and the hint
     * is dropped to 0 the moment the island is still, so an idle pill never pins the panel high.
     */
    private fun requestMotionRate(active: Boolean, ambientOnly: Boolean = false) {
        val rate = IslandRenderDemand.motionRateHz(
            active = active,
            ambientOnly = ambientOnly,
            motionAllowed = motionAllowed,
            ambientFrameRateHz = customization.ambientFrameRateHz,
            highQualityMotion = highQualityMotion,
        )
        if (rate == requestedRate) return
        requestedRate = rate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            setRequestedFrameRate(rate)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notification text reveal
    // ---------------------------------------------------------------------------------------

    /** 0 at start, 1 when settled; eased so the movement lands softly instead of linearly. */
    private fun smoothStep(value: Float): Float {
        return IslandMotion.smoothStep(value)
    }

    private fun easeOutCubic(value: Float): Float {
        return IslandMotion.easeOutCubic(value)
    }

    /** Whole-line content replacement: preserves shaping/kerning and allocates nothing per glyph. */
    private fun drawFluidText(
        canvas: Canvas,
        text: CharSequence,
        x: Float,
        baseline: Float,
        paint: TextPaint,
    ) {
        val elapsed = frameNowMs - revealStartMs
        if (!revealAnimating || elapsed >= IslandMotion.REVEAL_DURATION_MS) {
            canvas.drawText(text, 0, text.length, x, baseline, paint)
            return
        }
        val t = IslandMotion.easeOutCubic(elapsed / IslandMotion.REVEAL_DURATION_MS)
        val alpha = paint.alpha
        paint.alpha = (alpha * t).toInt().coerceIn(0, 255)
        canvas.drawText(text, 0, text.length, x, baseline + (1f - t) * dp(3f), paint)
        paint.alpha = alpha
    }

    /** Safe compact regions from the shared dp layout: edge padding + lens clearance, never under the hole. */
    private fun contentAreas(
        widthPx: Float,
        heightPx: Float,
        holeX: Float = holeCenterX,
        holeY: Float = holeCenterY,
    ): Areas {
        val regions = IslandLayout.compactAreas(
            widthDp = widthPx / density,
            heightDp = heightPx / density,
            edgePaddingDp = customization.compactEdgePaddingDp.toFloat(),
            holeEnabled = holeRadiusPx > 0f,
            holeCenterXDp = holeX / density,
            holeCenterYDp = holeY / density,
            holeRadiusDp = holeRadiusPx / density,
            sensorClearanceDp = customization.sensorClearanceDp.toFloat(),
        )
        fun rect(region: IslandLayout.CompactRegion): RectF = RectF(
            region.left * density, region.top * density, region.right * density, region.bottom * density,
        )
        return Areas(regions.leading?.let(::rect), regions.trailing?.let(::rect), rect(regions.single))
    }

    /** Full-width region below the sensor; unlike compact content it is never split into columns. */
    private fun expandedLowerArea(): RectF {
        val width = expandedBounds.width()
        val height = expandedBounds.height()
        val margin = dp(customization.contentMarginDp.toFloat())
        val holeBottom = holeCenterY - expandedBounds.top + holeRadiusPx
        val top = if (holeRadiusPx > 0f) {
            maxOf(margin, holeBottom + dp(customization.sensorClearanceDp.toFloat()))
        } else {
            margin
        }.coerceAtMost(height - margin - dp(24f))
        return RectF(margin, top, width - margin, height - margin)
    }

    private inline fun Canvas.inArea(area: RectF, block: () -> Unit) {
        save()
        clipRect(area)
        block()
        restore()
    }

    /** Calibration-only red ring + crosshair; hit targets are unaffected. */
    private fun drawHoleGuide(canvas: Canvas) {
        val radius = holeRadiusPx
        holeRingPaint.strokeWidth = 1.5f * density
        canvas.drawCircle(holeCenterX, holeCenterY, radius, holeFillPaint)
        canvas.drawCircle(holeCenterX, holeCenterY, radius, holeRingPaint)
        val arm = radius * 0.78f
        canvas.drawLine(holeCenterX - arm, holeCenterY, holeCenterX + arm, holeCenterY, holeRingPaint)
        canvas.drawLine(holeCenterX, holeCenterY - arm, holeCenterX, holeCenterY + arm, holeRingPaint)
    }

    private fun drawMediaCompact(
        canvas: Canvas,
        media: IslandContent.Media,
        areas: Areas,
        heightPx: Float,
    ) {
        if (areas.split) {
            val left = areas.left!!
            val right = areas.right!!
            canvas.inArea(left) { drawCompactArt(canvas, media, left, heightPx) }
            canvas.inArea(right) {
                drawPlayingBars(canvas, right.left, right.right, heightPx, media.accentColor)
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val artRect = drawCompactArt(canvas, media, area, heightPx)
                drawPlayingBars(canvas, artRect.right + dp(6f), area.right, heightPx, media.accentColor)
            }
        }
    }

    private fun drawCompactArt(
        canvas: Canvas,
        media: IslandContent.Media,
        area: RectF,
        heightPx: Float,
    ): RectF {
        val artSize = dp(
            IslandLayout.fitIconSizeDp(customization.compactIconSizeDp.toFloat(), area.width() / density, area.height() / density),
        )
        val left = if (area.width() > artSize + dp(12f)) area.left else area.centerX() - artSize / 2f
        val artRect = RectF(left, area.centerY() - artSize / 2f, left + artSize, area.centerY() + artSize / 2f)
        drawArt(canvas, artRect, artSize * 0.25f, media.albumArt)
        return artRect
    }

    /**
     * Apple-style Now Playing card: artwork/title/waveform above a progress row and unboxed
     * transport controls. Everything lives below the lens, so the lower width is not split.
     */
    private fun drawMediaExpanded(canvas: Canvas, media: IslandContent.Media) {
        val area = expandedLowerArea()
        val artSize = dp(44f).coerceAtMost(area.height() * 0.42f)
        val artRect = RectF(area.left, area.top, area.left + artSize, area.top + artSize)
        drawArt(canvas, artRect, dp(9f), media.albumArt)

        val waveWidth = dp(20f)
        val textLeft = artRect.right + dp(10f)
        val textRight = (area.right - waveWidth - dp(10f)).coerceAtLeast(textLeft + dp(24f))
        titlePaint.textSize = sp(14f)
        subtitlePaint.textSize = sp(12f)
        val title = TextUtils.ellipsize(media.title, titlePaint, textRight - textLeft, TextUtils.TruncateAt.END)
        val artist = TextUtils.ellipsize(
            media.artist.ifBlank { "Now playing" }, subtitlePaint, textRight - textLeft, TextUtils.TruncateAt.END,
        )
        canvas.drawText(title, 0, title.length, textLeft, area.top + dp(17f), titlePaint)
        canvas.drawText(artist, 0, artist.length, textLeft, area.top + dp(35f), subtitlePaint)
        canvas.save()
        canvas.translate(0f, area.top)
        drawPlayingBars(canvas, area.right - waveWidth, area.right, artSize, media.accentColor)
        canvas.restore()

        val position = media.estimatePositionMs(SystemClock.elapsedRealtime())
        smallLabelPaint.textSize = sp(10f)
        smallLabelPaint.color = Color.argb(170, 255, 255, 255)
        val elapsedText = formatMediaTime(position)
        val remainingText = if (media.durationMs > 0L) "-${formatMediaTime(media.durationMs - position)}" else ""
        val remainingWidth = smallLabelPaint.measureText(remainingText)
        val progressY = area.top + artSize + dp(12f)
        val trackLeft = area.left + dp(22f)
        val trackRight = (area.right - remainingWidth - dp(8f)).coerceAtLeast(trackLeft + dp(24f))
        canvas.drawText(elapsedText, area.left, progressY + dp(3f), smallLabelPaint)
        val track = RectF(trackLeft, progressY, trackRight, progressY + dp(3f))
        canvas.drawRoundRect(track, dp(2f), dp(2f), trackPaint)
        if (media.durationMs > 0L) {
            val fraction = (position.toFloat() / media.durationMs.toFloat()).coerceIn(0f, 1f)
            canvas.drawRoundRect(
                RectF(track.left, track.top, track.left + track.width() * fraction, track.bottom),
                dp(2f), dp(2f), progressPaint,
            )
        }
        if (remainingText.isNotEmpty()) {
            canvas.drawText(remainingText, area.right - remainingWidth, progressY + dp(3f), smallLabelPaint)
        }

        val buttonSize = dp(44f)
        val buttonTop = (progressY + dp(12f)).coerceAtMost(area.bottom - buttonSize)
        val spacing = dp(64f).coerceAtMost(area.width() / 3f)
        val centerX = area.centerX()
        drawControlButton(
            canvas, centerX - spacing - buttonSize / 2f, buttonTop, buttonSize,
            TransportCommand.Previous, isPlaying = false, background = false, enabled = media.canSeekPrevious,
        )
        drawControlButton(
            canvas, centerX - buttonSize / 2f, buttonTop, buttonSize,
            TransportCommand.PlayPause, isPlaying = media.isPlaying, background = false, enabled = media.canPlayPause,
        )
        drawControlButton(
            canvas, centerX + spacing - buttonSize / 2f, buttonTop, buttonSize,
            TransportCommand.Next, isPlaying = false, background = false, enabled = media.canSeekNext,
        )
    }

    private fun formatMediaTime(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

    private fun drawNotificationCompact(
        canvas: Canvas,
        notification: IslandContent.Notification,
        areas: Areas,
        heightPx: Float,
    ) {
        val detached = shellIconDetached(notification)
        if (detached && !customization.notificationShowTitle) return
        if (areas.split) {
            val left = areas.left!!
            val right = areas.right!!
            if (detached) {
                // The icon is in the bubble: the widest lens-safe region carries the text alone.
                val area = areas.widest
                canvas.inArea(area) {
                    drawCompactNotificationTitle(canvas, notification.title, area.left, area.right, heightPx, centered = true)
                }
                return
            }
            val fitted = compactGlyphSize(left, customization.compactIconSizeDp.toFloat())
            val iconLeft = left.centerX() - fitted / 2f
            canvas.inArea(left) {
                drawIcon(canvas, notification.icon, RectF(iconLeft, (heightPx - fitted) / 2f, iconLeft + fitted, (heightPx + fitted) / 2f))
            }
            canvas.inArea(right) {
                drawCompactNotificationTitle(canvas, notification.title, right.left, right.right, heightPx)
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                if (detached) {
                    drawCompactNotificationTitle(canvas, notification.title, area.left, area.right, heightPx, centered = true)
                    return@inArea
                }
                val fitted = compactGlyphSize(area, customization.compactIconSizeDp.toFloat())
                val iconLeft = area.left
                drawIcon(canvas, notification.icon, RectF(iconLeft, (heightPx - fitted) / 2f, iconLeft + fitted, (heightPx + fitted) / 2f))
                drawCompactNotificationTitle(canvas, notification.title, iconLeft + fitted + dp(6f), area.right, heightPx)
            }
        }
    }

    private fun drawCompactNotificationTitle(
        canvas: Canvas,
        title: CharSequence,
        left: Float,
        right: Float,
        heightPx: Float,
        centered: Boolean = false,
    ) {
        titlePaint.textSize = sp(11.5f)
        titlePaint.color = customization.textColorArgb
        if (right <= left) return
        val available = right - left
        val line = TextUtils.ellipsize(title, titlePaint, available, TextUtils.TruncateAt.END)
        val baseline = heightPx / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
        val x = if (centered) {
            left + ((available - titlePaint.measureText(line, 0, line.length)) / 2f).coerceAtLeast(0f)
        } else {
            left
        }
        drawFluidText(canvas, line, x, baseline, titlePaint)
    }

    private fun drawNotificationExpanded(canvas: Canvas, notification: IslandContent.Notification) {
        val area = expandedLowerArea()
        val iconSize = dp(20f)
        val textLeft = area.left + iconSize + dp(8f)
        val textMax = (area.right - textLeft).coerceAtLeast(dp(24f))
        titlePaint.textSize = sp(14f)
        subtitlePaint.textSize = sp(12f)
        val title = TextUtils.ellipsize(notification.title, titlePaint, textMax, TextUtils.TruncateAt.END)
        val body = TextUtils.ellipsize(
            notification.text.ifBlank { notification.appLabel }, subtitlePaint, textMax, TextUtils.TruncateAt.END,
        )
        val hasActions = notification.actions.isNotEmpty()
        val textTop = if (hasActions) area.top else area.top + (area.height() - dp(38f)) / 2f
        drawIcon(canvas, notification.icon, RectF(area.left, textTop + dp(2f), area.left + iconSize, textTop + dp(2f) + iconSize))
        drawFluidText(canvas, title, textLeft, textTop + dp(16f), titlePaint)
        drawFluidText(canvas, body, textLeft, textTop + dp(34f), subtitlePaint)

        if (hasActions) {
            val buttons = notification.actions.take(2).mapIndexed { index, action ->
                ActionButton(action.title, TargetKind.Action, index)
            }
            drawActionButtons(canvas, area, buttons)
        }
    }

    private data class ActionButton(val label: String, val kind: TargetKind, val index: Int)

    /** Capsule buttons along the bottom of an expanded card; the touch target is 44 dp tall. */
    private fun drawActionButtons(canvas: Canvas, area: RectF, buttons: List<ActionButton>) {
        if (buttons.isEmpty()) return
        val gap = dp(8f)
        val visibleHeight = dp(36f)
        val top = area.bottom - visibleHeight
        val width = (area.width() - gap * (buttons.size - 1)) / buttons.size
        buttons.forEachIndexed { slot, button ->
            val left = area.left + slot * (width + gap)
            val rect = RectF(left, top, left + width, top + visibleHeight)
            canvas.drawRoundRect(rect, dp(18f), dp(18f), buttonPaint)
            actionPaint.textSize = sp(12f)
            val label = TextUtils.ellipsize(button.label, actionPaint, width - dp(16f), TextUtils.TruncateAt.END)
            val baseline = rect.centerY() - (actionPaint.descent() + actionPaint.ascent()) / 2f
            canvas.drawText(label, 0, label.length, rect.centerX() - actionPaint.measureText(label.toString()) / 2f, baseline, actionPaint)
            val hit = RectF(rect)
            hit.inset(0f, -dp(4f))
            targets += Target(button.kind, button.index, hit)
        }
    }

    private fun drawCharging(
        canvas: Canvas,
        charging: IslandContent.Charging,
        areas: Areas,
        heightPx: Float,
    ) {
        val text = when {
            charging.isFull -> "FULL"
            charging.level >= 0 -> "${charging.level}%"
            else -> "CHARGING"
        }
        chargingPaint.textSize = sp(13f)
        chargingPaint.color = Color.WHITE
        if (areas.split) {
            val left = areas.left!!
            canvas.inArea(left) { drawBolt(canvas, left.centerX(), heightPx / 2f, compactGlyphSize(left, 16f)) }
            canvas.inArea(areas.right!!) {
                val width = chargingPaint.measureText(text)
                val scale = (areas.right.width() / width.coerceAtLeast(1f)).coerceAtMost(1f)
                canvas.save()
                canvas.scale(scale, scale, areas.right.centerX(), heightPx / 2f)
                val baseline = heightPx / 2f - (chargingPaint.descent() + chargingPaint.ascent()) / 2f
                canvas.drawText(text, areas.right.centerX() - width / 2f, baseline, chargingPaint)
                canvas.restore()
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val bolt = dp(12f)
                val gap = dp(6f)
                val width = chargingPaint.measureText(text)
                val total = bolt + gap + width
                val scale = (area.width() / total.coerceAtLeast(1f)).coerceAtMost(1f)
                canvas.save()
                canvas.translate(area.centerX(), heightPx / 2f)
                canvas.scale(scale, scale)
                drawBolt(canvas, -total / 2f + bolt / 2f, 0f, bolt)
                val baseline = -(chargingPaint.descent() + chargingPaint.ascent()) / 2f
                canvas.drawText(text, -total / 2f + bolt + gap, baseline, chargingPaint)
                canvas.restore()
            }
        }
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        accentPaint.color = customization.chargingColorArgb
        boltPath.reset()
        boltPath.moveTo(cx + size * 0.12f, cy - size / 2f)
        boltPath.lineTo(cx - size * 0.34f, cy + size * 0.08f)
        boltPath.lineTo(cx - size * 0.02f, cy + size * 0.08f)
        boltPath.lineTo(cx - size * 0.12f, cy + size / 2f)
        boltPath.lineTo(cx + size * 0.34f, cy - size * 0.1f)
        boltPath.lineTo(cx + size * 0.02f, cy - size * 0.1f)
        boltPath.close()
        canvas.drawPath(boltPath, accentPaint)
    }

    /** Compact timer: leading timer/pause glyph, trailing tabular countdown. */
    private fun drawTimerCompact(
        canvas: Canvas,
        timer: IslandContent.Timer,
        areas: Areas,
        heightPx: Float,
    ) {
        val paused = !timer.isRunning && timer.remainingMs > 0L
        chargingPaint.textSize = sp(13f)
        chargingPaint.color = customization.timerColorArgb
        if (areas.split) {
            val left = areas.left!!
            canvas.inArea(left) { drawTimerGlyph(canvas, left.centerX(), heightPx / 2f, compactGlyphSize(left, 15f), paused) }
            canvas.inArea(areas.right!!) {
                val text = timer.displayTime
                val width = chargingPaint.measureText(text)
                val scale = (areas.right.width() / width.coerceAtLeast(1f)).coerceAtMost(1f)
                canvas.save()
                canvas.scale(scale, scale, areas.right.centerX(), heightPx / 2f)
                val baseline = heightPx / 2f - (chargingPaint.descent() + chargingPaint.ascent()) / 2f
                canvas.drawText(text, areas.right.centerX() - width / 2f, baseline, chargingPaint)
                canvas.restore()
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val glyph = dp(15f)
                val gap = dp(6f)
                val text = timer.displayTime
                val width = chargingPaint.measureText(text)
                val total = glyph + gap + width
                val scale = (area.width() / total.coerceAtLeast(1f)).coerceAtMost(1f)
                canvas.save()
                canvas.translate(area.centerX(), heightPx / 2f)
                canvas.scale(scale, scale)
                drawTimerGlyph(canvas, -total / 2f + glyph / 2f, 0f, glyph, paused)
                val baseline = -(chargingPaint.descent() + chargingPaint.ascent()) / 2f
                canvas.drawText(text, -total / 2f + glyph + gap, baseline, chargingPaint)
                canvas.restore()
            }
        }
    }

    /**
     * Compact clock card: the same glyph-plus-time row as the in-app timer, so a countdown from the
     * Clock app reads exactly like one the island started itself.
     */
    private fun drawClockCompact(
        canvas: Canvas,
        clock: IslandContent.ClockActivity,
        areas: Areas,
        heightPx: Float,
    ) {
        val paused = !clock.isRunning
        chargingPaint.textSize = sp(13f)
        chargingPaint.color = customization.timerColorArgb
        if (areas.split) {
            val left = areas.left!!
            canvas.inArea(left) {
                drawClockGlyph(canvas, left.centerX(), heightPx / 2f, compactGlyphSize(left, 15f), clock.kind, paused)
            }
            canvas.inArea(areas.right!!) {
                val text = clock.timeText
                val width = chargingPaint.measureText(text)
                val scale = (areas.right.width() / width.coerceAtLeast(1f)).coerceAtMost(1f)
                canvas.save()
                canvas.scale(scale, scale, areas.right.centerX(), heightPx / 2f)
                val baseline = heightPx / 2f - (chargingPaint.descent() + chargingPaint.ascent()) / 2f
                canvas.drawText(text, areas.right.centerX() - width / 2f, baseline, chargingPaint)
                canvas.restore()
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val glyph = dp(15f)
                val gap = dp(6f)
                val text = clock.timeText
                val width = chargingPaint.measureText(text)
                val total = glyph + gap + width
                val scale = (area.width() / total.coerceAtLeast(1f)).coerceAtMost(1f)
                canvas.save()
                canvas.translate(area.centerX(), heightPx / 2f)
                canvas.scale(scale, scale)
                drawClockGlyph(canvas, -total / 2f + glyph / 2f, 0f, glyph, clock.kind, paused)
                val baseline = -(chargingPaint.descent() + chargingPaint.ascent()) / 2f
                canvas.drawText(text, -total / 2f + glyph + gap, baseline, chargingPaint)
                canvas.restore()
            }
        }
    }

    private fun drawClockGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        kind: ClockKind,
        paused: Boolean,
    ) {
        if (kind == ClockKind.Timer) {
            drawTimerGlyph(canvas, cx, cy, size, paused)
            return
        }
        accentPaint.color = customization.timerColorArgb
        accentStrokePaint.color = customization.timerColorArgb
        accentStrokePaint.strokeWidth = dp(1.8f)
        // Stopwatch: crown, dial and a hand pointing up-right.
        canvas.drawCircle(cx, cy + size * 0.08f, size * 0.36f, accentStrokePaint)
        canvas.drawLine(cx, cy - size * 0.5f, cx, cy - size * 0.3f, accentStrokePaint)
        canvas.drawLine(cx - size * 0.16f, cy - size * 0.5f, cx + size * 0.16f, cy - size * 0.5f, accentStrokePaint)
        if (!paused) {
            canvas.drawLine(cx, cy + size * 0.08f, cx + size * 0.16f, cy - size * 0.1f, accentStrokePaint)
        }
    }

    /** Expanded clock card: centred time over its label, with the notification's own controls. */
    private fun drawClockExpanded(canvas: Canvas, clock: IslandContent.ClockActivity) {
        val area = expandedLowerArea()

        smallLabelPaint.textSize = sp(12f)
        smallLabelPaint.color = customization.timerColorArgb
        expandedTimePaint.textSize = sp(28f)
        expandedTimePaint.color = customization.timerColorArgb

        // Label above, time below it: both centred on the card, so the digits stay the hero.
        val label = when {
            !clock.isRunning -> "PAUSED"
            clock.kind == ClockKind.Stopwatch -> "STOPWATCH"
            else -> "TIMER"
        }
        val labelWidth = smallLabelPaint.measureText(label)
        canvas.drawText(label, area.centerX() - labelWidth / 2f, area.top + dp(12f), smallLabelPaint)

        val baseline = area.top + dp(46f)
        val width = expandedTimePaint.measureText(clock.timeText)
        val scale = (area.width() / width.coerceAtLeast(1f)).coerceIn(0.6f, 1f)
        canvas.save()
        canvas.scale(scale, scale, area.centerX(), baseline)
        drawRollingClockTime(canvas, clock, area.centerX(), baseline)
        canvas.restore()

        val buttons = clock.actions.take(2).mapIndexed { index, action ->
            ActionButton(action.title, TargetKind.Action, index)
        } + ActionButton("Open", TargetKind.OpenContent, -1)
        drawActionButtons(canvas, area, buttons)
    }

    /**
     * Draws the card's time centred at [cx]. After a tick, only the characters that changed roll:
     * a countdown's digits fall away below and the next digit drops in from above, like a
     * mechanical counter; a stopwatch rolls the other way, matching the numbers counting up.
     */
    private fun drawRollingClockTime(
        canvas: Canvas,
        clock: IslandContent.ClockActivity,
        cx: Float,
        baseline: Float,
    ) {
        val paint = expandedTimePaint
        val next = clock.timeText
        val previous = clockRollFrom
        val width = paint.measureText(next)
        val left = cx - width / 2f
        val progress = if (clockRollRunning) {
            ((frameNowMs - clockRollStartMs) / CLOCK_ROLL_MS.toFloat())
                .coerceIn(0f, 1f)
        } else {
            1f
        }
        if (!clockRollRunning || previous == null || previous.length != next.length || progress >= 1f) {
            canvas.drawText(next, left, baseline, paint)
            return
        }
        val eased = easeOutCubic(progress)
        val lineHeight = paint.textSize * 1.2f
        val downward = clock.kind == ClockKind.Timer
        val save = canvas.save()
        // Clip to the text line so the outgoing digit is hidden as it leaves the row.
        canvas.clipRect(
            left - dp(4f),
            baseline + paint.ascent() - dp(2f),
            left + width + dp(4f),
            baseline + paint.descent() + dp(2f),
        )
        var x = left
        for (index in next.indices) {
            val current = next[index]
            val before = previous[index]
            if (current == before) {
                canvas.drawText(current.toString(), x, baseline, paint)
            } else {
                val shift = lineHeight * eased
                val enter = lineHeight * (1f - eased)
                if (downward) {
                    canvas.drawText(before.toString(), x, baseline + shift, paint)
                    canvas.drawText(current.toString(), x, baseline - enter, paint)
                } else {
                    canvas.drawText(before.toString(), x, baseline - shift, paint)
                    canvas.drawText(current.toString(), x, baseline + enter, paint)
                }
            }
            x += paint.measureText(current.toString())
        }
        canvas.restoreToCount(save)
    }

    private fun drawTimerGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, paused: Boolean) {
        accentPaint.color = customization.timerColorArgb
        accentStrokePaint.color = customization.timerColorArgb
        accentStrokePaint.strokeWidth = dp(1.8f)
        if (paused) {
            val bar = size * 0.18f
            val gap = size * 0.16f
            canvas.drawRoundRect(RectF(cx - gap - bar, cy - size * 0.38f, cx - gap, cy + size * 0.38f), bar / 2f, bar / 2f, accentPaint)
            canvas.drawRoundRect(RectF(cx + gap, cy - size * 0.38f, cx + gap + bar, cy + size * 0.38f), bar / 2f, bar / 2f, accentPaint)
        } else {
            canvas.drawCircle(cx, cy + size * 0.06f, size * 0.38f, accentStrokePaint)
            canvas.drawLine(cx, cy - size * 0.46f, cx, cy - size * 0.3f, accentStrokePaint)
            canvas.drawLine(cx, cy + size * 0.06f, cx + size * 0.18f, cy - size * 0.12f, accentStrokePaint)
        }
    }

    /** Apple-style timer card: circular pause/cancel controls leading, large orange time trailing. */
    private fun drawTimerExpanded(canvas: Canvas, timer: IslandContent.Timer) {
        val area = expandedLowerArea()
        val finished = timer.remainingMs <= 0L
        val visibleSize = dp(40f)
        val hitSize = dp(44f)
        val gap = dp(10f)
        val cy = area.centerY()
        val stopLeft = area.left + if (finished) 0f else visibleSize + gap
        val stopRect = RectF(stopLeft, cy - visibleSize / 2f, stopLeft + visibleSize, cy + visibleSize / 2f)
        if (!finished) {
            val toggleRect = RectF(area.left, cy - visibleSize / 2f, area.left + visibleSize, cy + visibleSize / 2f)
            drawTimerButton(canvas, toggleRect, TimerGlyph.Toggle(timer.isRunning))
            targets += Target(TargetKind.TimerToggle, -1, expandedHit(toggleRect, hitSize))
        }
        drawTimerButton(canvas, stopRect, TimerGlyph.Stop)
        targets += Target(TargetKind.TimerStop, -1, expandedHit(stopRect, hitSize))

        expandedTimePaint.textSize = sp(28f)
        expandedTimePaint.color = customization.timerColorArgb
        val time = timer.displayTime
        val timeWidth = expandedTimePaint.measureText(time)
        val timeScale = ((area.right - stopRect.right - dp(12f)) / timeWidth.coerceAtLeast(1f)).coerceIn(0.6f, 1f)
        val baseline = cy - (expandedTimePaint.descent() + expandedTimePaint.ascent()) / 2f
        canvas.save()
        canvas.scale(timeScale, timeScale, area.right, cy)
        canvas.drawText(time, area.right - timeWidth, baseline, expandedTimePaint)
        canvas.restore()
        smallLabelPaint.textSize = sp(12f)
        smallLabelPaint.color = customization.timerColorArgb
        val label = if (finished) "TIME'S UP" else "Timer"
        val labelWidth = smallLabelPaint.measureText(label)
        val labelRight = area.right - timeWidth * timeScale - dp(8f)
        if (labelRight - labelWidth > stopRect.right + dp(8f)) {
            canvas.drawText(label, labelRight - labelWidth, baseline - dp(2f), smallLabelPaint)
        }
    }

    private fun expandedHit(visible: RectF, size: Float): RectF {
        val rect = RectF(visible)
        // Grow small touch targets to the minimum, but never shrink a wide capsule button.
        val dx = ((size - rect.width()) / 2f).coerceAtLeast(0f)
        val dy = ((size - rect.height()) / 2f).coerceAtLeast(0f)
        rect.inset(-dx, -dy)
        return rect
    }

    private fun drawTimerButton(canvas: Canvas, rect: RectF, glyph: TimerGlyph) {
        val toggle = glyph is TimerGlyph.Toggle
        accentPaint.color = if (toggle) Color.argb(64, 255, 159, 10) else Color.rgb(44, 44, 46)
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, accentPaint)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val size = dp(16f)
        accentPaint.color = if (toggle) customization.timerColorArgb else Color.WHITE
        accentStrokePaint.color = Color.WHITE
        accentStrokePaint.strokeWidth = dp(2.2f)
        when (glyph) {
            is TimerGlyph.Toggle -> if (glyph.pause) {
                val bar = size * 0.2f
                val gap = size * 0.14f
                canvas.drawRoundRect(RectF(cx - gap - bar, cy - size / 2f, cx - gap, cy + size / 2f), bar / 2f, bar / 2f, accentPaint)
                canvas.drawRoundRect(RectF(cx + gap, cy - size / 2f, cx + gap + bar, cy + size / 2f), bar / 2f, bar / 2f, accentPaint)
            } else {
                iconPath.reset()
                iconPath.moveTo(cx - size * 0.3f, cy - size / 2f)
                iconPath.lineTo(cx + size * 0.5f, cy)
                iconPath.lineTo(cx - size * 0.3f, cy + size / 2f)
                iconPath.close()
                canvas.drawPath(iconPath, accentPaint)
            }
            TimerGlyph.Stop -> {
                canvas.drawLine(cx - size * 0.36f, cy - size * 0.36f, cx + size * 0.36f, cy + size * 0.36f, accentStrokePaint)
                canvas.drawLine(cx + size * 0.36f, cy - size * 0.36f, cx - size * 0.36f, cy + size * 0.36f, accentStrokePaint)
            }
        }
    }

    private sealed interface TimerGlyph {
        data class Toggle(val pause: Boolean) : TimerGlyph
        data object Stop : TimerGlyph
    }


    /** Generic compact leading icon / trailing text used by calls, downloads and maps. */
    private fun drawCompactIconText(
        canvas: Canvas,
        icon: Drawable?,
        text: String,
        areas: Areas,
        heightPx: Float,
        color: Int,
        iconDetached: Boolean = false,
    ) {
        if (iconDetached) {
            // The app icon is in the detached bubble; the shell keeps only the status text.
            val area = areas.widest
            titlePaint.textSize = sp(11.5f)
            titlePaint.color = color
            canvas.inArea(area) {
                if (area.width() <= 0f) return@inArea
                val line = TextUtils.ellipsize(text, titlePaint, area.width(), TextUtils.TruncateAt.END)
                val baseline = heightPx / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
                val left = area.left +
                    ((area.width() - titlePaint.measureText(line, 0, line.length)) / 2f).coerceAtLeast(0f)
                canvas.drawText(line, 0, line.length, left, baseline, titlePaint)
            }
            titlePaint.color = customization.textColorArgb
            return
        }
        val iconSize = dp(
            IslandLayout.fitIconSizeDp(customization.compactIconSizeDp.toFloat(), areas.widest.width() / density, areas.widest.height() / density),
        )
        titlePaint.textSize = sp(11.5f)
        titlePaint.color = color
        if (areas.split) {
            val left = areas.left!!
            val right = areas.right!!
            canvas.inArea(left) {
                val fitted = compactGlyphSize(left, customization.compactIconSizeDp.toFloat())
                val l = left.centerX() - fitted / 2f
                drawIcon(canvas, icon, RectF(l, (heightPx - fitted) / 2f, l + fitted, (heightPx + fitted) / 2f))
            }
            canvas.inArea(right) {
                val available = right.width()
                if (available <= 0f) return@inArea
                val line = TextUtils.ellipsize(text, titlePaint, available, TextUtils.TruncateAt.END)
                val baseline = heightPx / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
                canvas.drawText(line, 0, line.length, right.left, baseline, titlePaint)
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val l = area.left
                drawIcon(canvas, icon, RectF(l, (heightPx - iconSize) / 2f, l + iconSize, (heightPx + iconSize) / 2f))
                val available = (area.right - l - iconSize - dp(6f)).coerceAtLeast(0f)
                if (available <= 0f) return@inArea
                val line = TextUtils.ellipsize(text, titlePaint, available, TextUtils.TruncateAt.END)
                val baseline = heightPx / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
                canvas.drawText(line, 0, line.length, l + iconSize + dp(6f), baseline, titlePaint)
            }
        }
        titlePaint.color = customization.textColorArgb
    }

    private fun drawBatteryLow(canvas: Canvas, content: IslandContent.BatteryLow, areas: Areas, heightPx: Float) {
        drawBatteryStatus(canvas, "${content.level}%", customization.batteryLowColorArgb, areas, heightPx, content.level / 100f)
    }

    private fun drawPowerSave(canvas: Canvas, content: IslandContent.PowerSave, areas: Areas, heightPx: Float) {
        drawBatteryStatus(
            canvas,
            if (content.enabled) "SAVER ON" else "SAVER OFF",
            customization.powerSaveColorArgb,
            areas,
            heightPx,
            if (content.enabled) 0.35f else 0.8f,
        )
    }

    private fun drawBatteryStatus(canvas: Canvas, text: String, color: Int, areas: Areas, heightPx: Float, fill: Float) {
        chargingPaint.textSize = sp(12f)
        chargingPaint.color = Color.WHITE
        val glyphWidth = compactGlyphSize(areas.widest, 18f)
        if (areas.split) {
            val left = areas.left!!
            canvas.inArea(left) { drawBatteryGlyph(canvas, left.centerX(), heightPx / 2f, glyphWidth, color, fill) }
            canvas.inArea(areas.right!!) {
                val width = chargingPaint.measureText(text)
                val scale = (areas.right.width() / width.coerceAtLeast(1f)).coerceAtMost(1f)
                canvas.save()
                canvas.scale(scale, scale, areas.right.centerX(), heightPx / 2f)
                val baseline = heightPx / 2f - (chargingPaint.descent() + chargingPaint.ascent()) / 2f
                canvas.drawText(text, areas.right.centerX() - width / 2f, baseline, chargingPaint)
                canvas.restore()
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val gap = dp(6f)
                val width = chargingPaint.measureText(text)
                val total = glyphWidth + gap + width
                val scale = (area.width() / total.coerceAtLeast(1f)).coerceAtMost(1f)
                canvas.save()
                canvas.translate(area.centerX(), heightPx / 2f)
                canvas.scale(scale, scale)
                drawBatteryGlyph(canvas, -total / 2f + glyphWidth / 2f, 0f, glyphWidth, color, fill)
                val baseline = -(chargingPaint.descent() + chargingPaint.ascent()) / 2f
                canvas.drawText(text, -total / 2f + glyphWidth + gap, baseline, chargingPaint)
                canvas.restore()
            }
        }
    }

    private fun compactGlyphSize(area: RectF, requestedDp: Float): Float = dp(
        IslandLayout.fitIconSizeDp(requestedDp, area.width() / density, area.height() / density),
    )

    private fun drawBatteryGlyph(canvas: Canvas, cx: Float, cy: Float, width: Float, color: Int, fill: Float) {
        accentStrokePaint.color = color
        accentStrokePaint.strokeWidth = dp(1.5f)
        accentPaint.color = color
        val batteryHeight = width * 0.52f
        val body = RectF(cx - width / 2f, cy - batteryHeight / 2f, cx + width / 2f - dp(2f), cy + batteryHeight / 2f)
        canvas.drawRoundRect(body, dp(2f), dp(2f), accentStrokePaint)
        canvas.drawRect(body.right, cy - batteryHeight * 0.22f, body.right + dp(2f), cy + batteryHeight * 0.22f, accentPaint)
        val inner = RectF(body.left + dp(1.5f), body.top + dp(1.5f), body.right - dp(1.5f), body.bottom - dp(1.5f))
        canvas.drawRect(inner.left, inner.top, inner.left + inner.width() * fill.coerceIn(0f, 1f), inner.bottom, accentPaint)
    }

    private fun drawCallExpanded(canvas: Canvas, call: IslandContent.Call) {
        val area = expandedLowerArea()
        titlePaint.textSize = sp(15f)
        subtitlePaint.textSize = sp(12f)
        val caller = TextUtils.ellipsize(call.caller, titlePaint, area.width(), TextUtils.TruncateAt.END)
        val subtitle = when {
            call.isIncoming -> if (call.isVideo) "Incoming video call" else "Incoming call"
            else -> call.appLabel
        }
        canvas.drawText(caller, 0, caller.length, area.left, area.top + dp(16f), titlePaint)
        canvas.drawText(subtitle, 0, subtitle.length, area.left, area.top + dp(34f), subtitlePaint)

        val size = dp(44f)
        val top = (area.bottom - size).coerceAtLeast(area.top + dp(42f))
        if (call.isIncoming && call.answerIntent != null && call.declineIntent != null) {
            val decline = RectF(area.left, top, area.left + size, top + size)
            val answer = RectF(area.right - size, top, area.right, top + size)
            drawCallButton(canvas, decline, call.declineColor, answer = false)
            drawCallButton(canvas, answer, call.answerColor, answer = true)
            targets += Target(TargetKind.CallDecline, -1, decline)
            targets += Target(TargetKind.CallAnswer, -1, answer)
        } else {
            val end = RectF(area.centerX() - size / 2f, top, area.centerX() + size / 2f, top + size)
            drawCallButton(canvas, end, call.declineColor, answer = false)
            targets += Target(TargetKind.CallEnd, -1, end)
        }
    }

    private fun drawCallButton(canvas: Canvas, rect: RectF, color: Int, answer: Boolean) {
        accentPaint.color = color
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, accentPaint)
        canvas.save()
        canvas.translate(rect.centerX(), rect.centerY())
        canvas.rotate(if (answer) -45f else 135f)
        val w = rect.width() * 0.46f
        val h = rect.width() * 0.18f
        canvas.drawRoundRect(RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), h / 2f, h / 2f, whitePaint)
        canvas.drawCircle(-w / 2f, 0f, h * 0.62f, whitePaint)
        canvas.drawCircle(w / 2f, 0f, h * 0.62f, whitePaint)
        canvas.restore()
    }

    private fun drawDownloadExpanded(canvas: Canvas, download: IslandContent.Download) {
        val area = expandedLowerArea()
        titlePaint.textSize = sp(14f)
        subtitlePaint.textSize = sp(12f)
        val title = TextUtils.ellipsize(download.title, titlePaint, area.width(), TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, area.left, area.top + dp(16f), titlePaint)
        if (download.text.isNotBlank()) {
            val text = TextUtils.ellipsize(download.text, subtitlePaint, area.width(), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, area.left, area.top + dp(34f), subtitlePaint)
        }
        // The notification's own controls (pause, stop, cancel, snooze...) instead of a progress
        // bar that could only be estimated. Open always stays available.
        val buttons = download.actions.take(2).mapIndexed { index, action ->
            ActionButton(action.title, TargetKind.Action, index)
        } + ActionButton("Open", TargetKind.OpenContent, -1)
        drawActionButtons(canvas, area, buttons)
    }

    private fun drawNavigationExpanded(canvas: Canvas, navigation: IslandContent.Navigation) {
        val area = expandedLowerArea()
        titlePaint.textSize = sp(15f)
        subtitlePaint.textSize = sp(12f)
        val instruction = TextUtils.ellipsize(navigation.instruction, titlePaint, area.width(), TextUtils.TruncateAt.END)
        canvas.drawText(instruction, 0, instruction.length, area.left, area.top + dp(18f), titlePaint)
        val detail = TextUtils.ellipsize(
            navigation.detail.ifBlank { navigation.appLabel }, subtitlePaint, area.width(), TextUtils.TruncateAt.END,
        )
        canvas.drawText(detail, 0, detail.length, area.left, area.top + dp(38f), subtitlePaint)
        val open = RectF(area.right - dp(110f), area.bottom - dp(36f), area.right, area.bottom)
        drawPillButton(canvas, open, "Open maps")
        targets += Target(TargetKind.OpenContent, -1, expandedHit(open, dp(44f)))
    }

    private fun drawPillButton(canvas: Canvas, rect: RectF, label: String) {
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, buttonPaint)
        actionPaint.textSize = sp(12f)
        val line = TextUtils.ellipsize(label, actionPaint, rect.width() - dp(16f), TextUtils.TruncateAt.END)
        val baseline = rect.centerY() - (actionPaint.descent() + actionPaint.ascent()) / 2f
        canvas.drawText(line, 0, line.length, rect.centerX() - actionPaint.measureText(line.toString()) / 2f, baseline, actionPaint)
    }

    private fun drawControlButton(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        transport: TransportCommand,
        isPlaying: Boolean,
        clip: RectF? = null,
        background: Boolean = true,
        enabled: Boolean = true,
    ) {
        val rect = RectF(left, top, left + size, top + size)
        if (background) canvas.drawRoundRect(rect, dp(9f), dp(9f), buttonPaint)
        val kind = when (transport) {
            TransportCommand.Previous -> TargetKind.Previous
            TransportCommand.PlayPause -> TargetKind.PlayPause
            TransportCommand.Next -> TargetKind.Next
        }
        val iconPaint = if (!enabled) mutedPaint else if (pressed?.kind == kind) mutedPaint else whitePaint
        val cx = rect.centerX()
        val cy = rect.centerY()
        val unit = size * 0.22f

        when (transport) {
            TransportCommand.PlayPause -> {
                if (isPlaying) {
                    val barWidth = unit * 0.7f
                    val halfHeight = unit
                    canvas.drawRoundRect(
                        RectF(cx - unit * 0.75f, cy - halfHeight, cx - unit * 0.75f + barWidth, cy + halfHeight),
                        0.6f, 0.6f, iconPaint,
                    )
                    canvas.drawRoundRect(
                        RectF(cx + unit * 0.75f - barWidth, cy - halfHeight, cx + unit * 0.75f, cy + halfHeight),
                        0.6f, 0.6f, iconPaint,
                    )
                } else {
                    iconPath.reset()
                    iconPath.moveTo(cx - unit * 0.55f, cy - unit)
                    iconPath.lineTo(cx + unit * 0.95f, cy)
                    iconPath.lineTo(cx - unit * 0.55f, cy + unit)
                    iconPath.close()
                    canvas.drawPath(iconPath, iconPaint)
                }
            }

            TransportCommand.Previous, TransportCommand.Next -> {
                val direction = if (transport == TransportCommand.Previous) -1f else 1f
                val barX = cx + direction * unit * 0.95f
                canvas.drawRoundRect(
                    RectF(barX - unit * 0.14f, cy - unit, barX + unit * 0.14f, cy + unit),
                    0.6f, 0.6f, iconPaint,
                )
                iconPath.reset()
                // Bar sits on the far side, triangle points toward it: |◀ for previous, ▶| for next.
                iconPath.moveTo(cx - direction * unit * 0.62f, cy - unit)
                iconPath.lineTo(cx + direction * unit * 0.62f, cy)
                iconPath.lineTo(cx - direction * unit * 0.62f, cy + unit)
                iconPath.close()
                canvas.drawPath(iconPath, iconPaint)
            }
        }

        if (!enabled) return
        // The drawing is clipped to the controls area, so the hit target must be too; otherwise a
        // cut-off button could still be tapped over the camera hole.
        val target = RectF(rect)
        if (clip == null || target.intersect(clip)) {
            targets += Target(kind, -1, target)
        }
    }

    private fun drawPlayingBars(canvas: Canvas, left: Float, right: Float, heightPx: Float, color: Int = Color.WHITE) {
        val available = right - left
        if (available <= 0f) return
        val count = 3
        val fit = min(1f, available / (dp(2.6f) * count + dp(2.4f) * (count - 1)))
        val barWidth = dp(2.6f) * fit
        val gap = dp(2.4f) * fit
        val total = barWidth * count + gap * (count - 1)
        var x = left + ((available - total) / 2f).coerceAtLeast(0f)
        val maxHeight = (heightPx * 0.44f).coerceAtMost(dp(22f))
        val minHeight = (heightPx * 0.16f).coerceAtMost(dp(8f))
        accentPaint.color = color
        val phase = (frameNowMs % 900L) / 900f * (2f * Math.PI).toFloat()

        repeat(count) { index ->
            val factor = if (animating) {
                val wave = sin(phase + index * 0.9f)
                0.5f + 0.5f * wave
            } else {
                when (index) {
                    0 -> 0.45f
                    1 -> 0.85f
                    else -> 0.3f
                }
            }
            val barHeight = minHeight + (maxHeight - minHeight) * factor
            val top = (heightPx - barHeight) / 2f
            canvas.drawRoundRect(
                RectF(x, top, x + barWidth, top + barHeight),
                barWidth / 2f,
                barWidth / 2f,
                accentPaint,
            )
            x += barWidth + gap
        }
    }

    private fun drawArt(canvas: Canvas, rect: RectF, radius: Float, bitmap: Bitmap?) {
        if (bitmap == null) {
            canvas.drawRoundRect(rect, radius, radius, buttonPaint)
            canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.16f, mutedPaint)
            return
        }
        canvas.save()
        iconPath.reset()
        iconPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(iconPath)
        val scale = maxOf(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val dst = RectF(
            rect.centerX() - drawWidth / 2f,
            rect.centerY() - drawHeight / 2f,
            rect.centerX() + drawWidth / 2f,
            rect.centerY() + drawHeight / 2f,
        )
        canvas.drawBitmap(bitmap, null, dst, null)
        canvas.restore()
    }

    private val monochromeFilter by lazy {
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    private fun drawIcon(canvas: Canvas, drawable: Drawable?, rect: RectF) {
        if (drawable == null) {
            canvas.drawRoundRect(rect, rect.width() * 0.25f, rect.width() * 0.25f, buttonPaint)
            return
        }
        runCatching {
            // Nothing-style monochrome icons are optional; the filter object is created once.
            drawable.colorFilter = if (customization.monochromeIcons) monochromeFilter else null
            drawable.setBounds(
                rect.left.toInt(),
                rect.top.toInt(),
                rect.right.toInt(),
                rect.bottom.toInt(),
            )
            drawable.draw(canvas)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isMorphing()) return false
                val target = targetAt(event.x, event.y) ?: return false
                pressed = target
                longPressFired = false
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                // A message can be slid away in either presentation, compact or expanded.
                swipeCandidate = content is IslandContent.Notification
                swipeDragging = false
                swipeSettling = false
                swipeOffsetPx = 0f
                swipeStartX = event.x
                if (target.kind == TargetKind.Body && customization.longPressAction != IslandLongPressAction.NONE) {
                    removeCallbacks(longPressRunnable)
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) { cancelTouch(); return true }
                val dx = event.getX(index) - downX
                val dy = event.getY(index) - downY
                if (swipeCandidate && !swipeDragging) {
                    // A mostly horizontal drag on a message becomes a dismiss gesture. Only the
                    // press is cancelled here: cancelTouch() clears the pointer id this gesture
                    // still needs to track.
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        swipeDragging = true
                        removeCallbacks(longPressRunnable)
                        pressed = null
                        longPressFired = false
                        Log.d(SWIPE_TAG, "swipe start dx=${dx.roundToInt()}")
                    }
                }
                if (swipeDragging) {
                    swipeOffsetPx = event.getX(index) - swipeStartX
                    invalidate()
                    return true
                }
                if (dx * dx + dy * dy > touchSlop * touchSlop) cancelTouch()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) cancelTouch()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (swipeDragging) {
                    val swept = swipeOffsetPx
                    swipeDragging = false
                    swipeCandidate = false
                    cancelTouch()
                    Log.d(
                        SWIPE_TAG,
                        "swipe up swept=${swept.roundToInt()} threshold=${
                            swipeDismissThresholdPx.roundToInt()
                        }",
                    )
                    if (abs(swept) >= swipeDismissThresholdPx) {
                        swipeOffsetPx = 0f
                        listener?.onNotificationDismiss()
                    } else {
                        swipeSettleFrom = swept
                        swipeSettleStartMs = SystemClock.uptimeMillis()
                        swipeSettling = true
                    }
                    invalidate()
                    return true
                }
                val index = event.findPointerIndex(activePointerId)
                val down = pressed
                val consumedByLongPress = longPressFired
                cancelTouch()
                if (!consumedByLongPress && index >= 0 && down != null && !isMorphing()) {
                    val up = targetAt(event.getX(index), event.getY(index))
                    if (up != null && down.kind == up.kind && down.index == up.index && down.rect.contains(event.getX(index), event.getY(index))) {
                        dispatch(up)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (swipeDragging) {
                    swipeDragging = false
                    swipeCandidate = false
                    swipeSettleFrom = swipeOffsetPx
                    swipeSettleStartMs = SystemClock.uptimeMillis()
                    swipeSettling = true
                }
                cancelTouch()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelTouch() {
        removeCallbacks(longPressRunnable)
        pressed = null
        activePointerId = -1
        longPressFired = false
        invalidate()
    }

    /** Optional confirmation tick; the system's haptic setting still wins. */
    private fun haptic() {
        if (!customization.hapticsEnabled) return
        runCatching { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // render() usually runs before the window exists; arm the per-second repaint now that it does.
        scheduleSecondTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settleWatchdog)
        removeCallbacks(notifyMorphSettled)
        removeCallbacks(secondTick)
        cancelTouch()
        requestMotionRate(false)
        super.onDetachedFromWindow()
    }

    /** Stable-at-rest virtual controls for the input window's accessibility provider. */
    internal fun accessibilityTargets(): List<IslandAccessibleTarget> {
        if (isMorphing() || content == null) return emptyList()
        // The detached bubble shares the shell's Body action; expose it once, not twice.
        var bodySeen = false
        return targets.mapNotNull { target ->
            if (target.kind == TargetKind.Body) {
                if (bodySeen) return@mapNotNull null
                bodySeen = true
            }
            IslandAccessibleTarget(accessibilityLabel(target), RectF(target.rect))
        }
    }

    internal fun activateAccessibilityTarget(index: Int): Boolean {
        if (isMorphing()) return false
        val target = targets.getOrNull(index) ?: return false
        dispatch(target)
        return true
    }

    private fun accessibilityLabel(target: Target): String = when (target.kind) {
        TargetKind.Body -> {
            val shown = content
            val name = when (shown) {
                is IslandContent.Media -> "media controls"
                is IslandContent.Notification -> "notification"
                is IslandContent.Timer -> "timer"
                is IslandContent.Charging -> "charging status"
                is IslandContent.BatteryLow -> "battery low"
                is IslandContent.PowerSave -> "power save"
                is IslandContent.Call -> "call"
                is IslandContent.Download -> "download"
                is IslandContent.ClockActivity ->
                    if (shown.kind == ClockKind.Stopwatch) "stopwatch" else "clock timer"
                is IslandContent.Navigation -> "navigation"
                null -> "island"
            }
            when {
                content is IslandContent.Notification && expanded -> "Open notification"
                expanded -> "Collapse $name"
                else -> "Expand $name"
            }
        }
        TargetKind.Previous -> "Previous track"
        TargetKind.PlayPause -> if ((content as? IslandContent.Media)?.isPlaying == true) "Pause" else "Play"
        TargetKind.Next -> "Next track"
        TargetKind.Action -> {
            val actions = when (val current = content) {
                is IslandContent.Notification -> current.actions
                is IslandContent.Download -> current.actions
                is IslandContent.ClockActivity -> current.actions
                else -> emptyList()
            }
            actions.getOrNull(target.index)?.title ?: "Activity action"
        }
        TargetKind.TimerToggle -> if ((content as? IslandContent.Timer)?.isRunning == true) "Pause timer" else "Resume timer"
        TargetKind.TimerStop -> "Cancel timer"
        TargetKind.CallAnswer -> "Answer call"
        TargetKind.CallDecline -> "Decline call"
        TargetKind.CallEnd -> "End call"
        TargetKind.OpenContent -> "Open"
    }

    private fun targetAt(x: Float, y: Float): Target? {
        val specific = targets.lastOrNull {
            it.kind != TargetKind.Body && it.rect.contains(x, y)
        }
        if (specific != null) return specific
        return targets.lastOrNull { it.kind == TargetKind.Body && it.rect.contains(x, y) }
    }

    private fun dispatch(target: Target) {
        val callback = listener ?: return
        if (target.kind == TargetKind.Body && customization.tapAction == IslandTapAction.NONE) return
        haptic()
        when (target.kind) {
            TargetKind.Body -> when (customization.tapAction) {
                IslandTapAction.EXPAND -> callback.onBodyTap()
                IslandTapAction.OPEN_APP -> callback.onOpenContent()
                IslandTapAction.NONE -> Unit
            }
            TargetKind.Previous -> callback.onTransport(TransportCommand.Previous)
            TargetKind.PlayPause -> callback.onTransport(TransportCommand.PlayPause)
            TargetKind.Next -> callback.onTransport(TransportCommand.Next)
            TargetKind.Action -> callback.onNotificationAction(target.index)
            TargetKind.TimerToggle -> callback.onTimerToggle()
            TargetKind.TimerStop -> callback.onTimerStop()
            TargetKind.CallAnswer -> callback.onCallAnswer()
            TargetKind.CallDecline -> callback.onCallDecline()
            TargetKind.CallEnd -> callback.onCallEnd()
            TargetKind.OpenContent -> callback.onOpenContent()
        }
    }

    private enum class TargetKind {
        Body, Previous, PlayPause, Next, Action, TimerToggle, TimerStop,
        CallAnswer, CallDecline, CallEnd, OpenContent,
    }

    private data class Target(val kind: TargetKind, val index: Int, val rect: RectF)

    internal data class IslandAccessibleTarget(val label: String, val rect: RectF)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics) *
            (textScalePercent / 100f)

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private companion object {
        /** Always-on display outline: at most 1 dp and never more than 2 physical px. */

        /** Never let a lost frame callback strand the island in its oversized morph window. */
        const val MORPH_WATCHDOG_MS = 900L

        const val SWAP_DURATION_MS = IslandMotion.SWAP_DURATION_MS
        /** Time for an un-dismissed swipe to slide back under the shell. */
        const val SWIPE_SETTLE_MS = 180L
        /** Duration of one digit roll on the expanded clock card. */
        const val CLOCK_ROLL_MS = 280L
        val REVEAL_TOTAL_MS = IslandMotion.REVEAL_DURATION_MS.toLong()
    }
}
