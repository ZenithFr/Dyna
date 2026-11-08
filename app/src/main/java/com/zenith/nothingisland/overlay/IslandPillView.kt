package com.zenith.nothingisland.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.zenith.nothingisland.data.IslandSettings
import com.zenith.nothingisland.data.IslandShape
import com.zenith.nothingisland.data.buildIslandPath
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandEvent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

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
    private var satelliteCornerPx = 0f
    private var satelliteShown = false
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
    private var lastStepMs = 0L

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
    private var aod = false

    /** If a morph somehow never settles, this forces it so the window can never stay oversized. */
    private val settleWatchdog = Runnable { forceSettle() }

    /** Previous activity, crossfaded out when the island switches what it is showing. */
    private var previousContent: IslandContent? = null
    private var swapStartMs = 0L
    private var swapAnimating = false

    private var highQualityMotion = true
    private var requestedRate = 0f
    private var progressAnimating = false

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
    private var activePointerId = -1
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
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
        satelliteCornerPx = (
            satelliteFrame.height() * SATELLITE_CORNER_FRACTION
            ).coerceIn(0f, satelliteFrame.height() / 2f)
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
        if (!motionAllowed) {
            dormantProgress = target
            dormantVelocity = 0f
            dormantSettling = false
            invalidate()
            return
        }
        dormantSettling = true
        lastStepMs = 0L
        requestMotionRate(true)
        removeCallbacks(settleWatchdog)
        postDelayed(settleWatchdog, MORPH_WATCHDOG_MS)
        invalidate()
    }

    /** Always-on display: black on black, so the shell keeps only a hairline outline. */
    fun setAod(enabled: Boolean) {
        if (aod == enabled) return
        aod = enabled
        invalidate()
    }

    /** User customization: source accents, typography, margins, ambient rate and motion speed. */
    fun setCustomization(settings: IslandSettings) {
        val next = settings.coerced()
        if (customization == next) return
        customization = next
        textScalePercent = customization.islandTextScalePercent
        invalidate()
    }

    /** True when motion should request the panel's fastest rate (120 Hz). */
    fun setHighQualityMotion(enabled: Boolean) {
        if (highQualityMotion == enabled) return
        highQualityMotion = enabled
        if (springActive || dormantSettling || animating || revealAnimating) requestMotionRate(true)
    }

    /** True while the shell is still moving (expand/retract or curling into the hole). */
    fun isMorphing(): Boolean = springActive || dormantSettling

    /**
     * Ends a morph immediately at its target. Used by the watchdog and by the owner when it needs
     * a settled frame right away; it always reports back so the window can be re-measured.
     */
    fun forceSettle() {
        if (!springActive && !dormantSettling) return
        val target = if (expanded) 1f else 0f
        widthProgress = target
        heightProgress = target
        widthVelocity = 0f
        heightVelocity = 0f
        springActive = false
        dormantProgress = dormantTarget
        dormantVelocity = 0f
        dormantSettling = false
        removeCallbacks(settleWatchdog)
        requestMotionRate(animating || revealAnimating)
        post { onMorphSettled?.invoke() }
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
        motionAllowed = ValueAnimator.areAnimatorsEnabled()

        // A new notification arrives: reveal its text fluidly (per character, short stagger).
        val notification = newContent as? IslandContent.Notification
        if (notification != null && motionAllowed) {
            val key = "${notification.key}|${notification.title}|${notification.text}"
            if (key != revealTextKey) {
                revealTextKey = key
                revealStartMs = SystemClock.elapsedRealtime()
                revealAnimating = true
            }
        } else {
            revealTextKey = null
            revealAnimating = false
        }

        // The island swaps activity while already settled (timer -> notification, ...): crossfade
        // the old card out instead of hard-cutting, the way a system activity update reads.
        val oldKey = contentKey(content)
        val newKey = contentKey(newContent)
        if (oldKey != newKey || expanded != newExpanded) {
            cancelTouch()
            accessibilityRevision++
        }
        if (motionAllowed && oldKey != null && newKey != null && oldKey != newKey &&
            !springActive && framesReady
        ) {
            previousContent = content
            swapStartMs = SystemClock.elapsedRealtime()
            swapAnimating = true
        } else if (newKey == null || oldKey != newKey) {
            previousContent = null
            swapAnimating = false
        }

        content = newContent
        expanded = newExpanded
        animating = motionAllowed && (newContent as? IslandContent.Media)?.isPlaying == true
        progressAnimating = motionAllowed && (newContent as? IslandContent.Download)?.indeterminate == true
        // Only persistent live activities detach their app into the bubble; a notification alert
        // (or a transient battery/charging confirmation) keeps the single shell.
        satelliteShown = !newExpanded && satelliteIcon(newContent) != null

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
            lastStepMs = 0L
            // Width leads, height follows: start both from where they currently sit.
            if (abs(widthProgress - target) < 0.001f) widthVelocity = 0f
            if (abs(heightProgress - target) < 0.001f) heightVelocity = 0f
            requestMotionRate(true)
            removeCallbacks(settleWatchdog)
            postDelayed(settleWatchdog, MORPH_WATCHDOG_MS)
        }
        invalidate()
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
        is IslandContent.Timer -> "timer"
    }

    // ---------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!framesReady) return
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) return

        val now = SystemClock.elapsedRealtime()
        if (springActive || dormantSettling) advanceMotion(now)
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
        drawShell(canvas, frame, corner, shellShape)

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

        // The detached app-icon bubble belongs to the compact presentation: it arrives with the
        // shell and retracts as the card grows. During an activity swap the outgoing icon fades
        // into the incoming one instead of popping out of the bubble.
        val satelliteOut = compactOut * (1f - widthCage)
        if (satelliteOut > 0.004f) {
            if (swapping) drawSatellite(canvas, outgoing, satelliteOut * (1f - swapT))
            drawSatellite(canvas, current, satelliteOut * (if (swapping) swapT else 1f))
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
        if (springActive || dormantSettling) {
            targets.clear()
        } else if (current != null && current !is IslandContent.Charging &&
            current !is IslandContent.BatteryLow && current !is IslandContent.PowerSave
        ) {
            // Whole-pill fallback target. Charging is a non-interactive confirmation.
            val body = currentFrame()
            targets.add(0, Target(TargetKind.Body, -1, body))
            // The detached bubble is part of the island: tapping it must do what tapping the
            // shell does, never nothing.
            if (satelliteShown && satelliteOut > 0.05f) {
                targets.add(0, Target(TargetKind.Body, -1, RectF(satelliteBounds)))
            }
        }

        val revealRunning = revealAnimating &&
            (now - revealStartMs) < REVEAL_TOTAL_MS
        if (revealAnimating && !revealRunning) revealAnimating = false
        val busy = animating || progressAnimating || springActive || dormantSettling || revealRunning || swapAnimating
        val ambientOnly = (animating || progressAnimating) && !springActive && !dormantSettling && !revealRunning && !swapAnimating
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
        if (alpha < 0.999f) {
            // Bound the offscreen layer to the card, not the whole morph envelope.
            canvas.saveLayerAlpha(
                frame.left, frame.top + risePx, frame.right, frame.bottom + risePx,
                (alpha * 255f).toInt().coerceIn(0, 255),
            )
        }
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
                else drawCompactIconText(canvas, current.icon, current.caller, areas, heightPx, Color.WHITE)

            is IslandContent.Download ->
                if (expandedVariant) drawDownloadExpanded(canvas, current)
                else drawCompactIconText(
                    canvas, current.icon,
                    if (current.indeterminate) "…" else "${current.percent}%",
                    areas, heightPx, customization.downloadColorArgb,
                )

            is IslandContent.Navigation ->
                if (expandedVariant) drawNavigationExpanded(canvas, current)
                else drawCompactIconText(canvas, current.icon, current.instruction, areas, heightPx, Color.WHITE)

            is IslandContent.Timer ->
                if (expandedVariant) {
                    drawTimerExpanded(canvas, current)
                } else {
                    drawTimerCompact(canvas, current, areas, heightPx)
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
    ) {
        // On the always-on display the island is a black shape on a black screen, so only an
        // outline can be seen — and it is forced to a hairline (~2 px) instead of the user width.
        val strokePx = if (aod) min(AOD_STROKE_PX, density) else strokeWidthPx
        val strokeOn = aod || (strokeEnabled && strokeWidthPx > 0f)
        // Without a stroke the shell fills the frame; with one, the outline is centred on the
        // edge, so the shape is inset by half the stroke width.
        val half = if (strokeOn) strokePx / 2f else 0f
        val innerWidth = (frame.width() - half * 2f).coerceAtLeast(1f)
        val innerHeight = (frame.height() - half * 2f).coerceAtLeast(1f)
        buildIslandPath(path, shellShape, innerWidth, innerHeight, cornerPx)
        path.offset(frame.left + half, frame.top + half)
        canvas.drawPath(path, shellPaint)
        if (strokeOn) {
            strokePaint.strokeWidth = strokePx
            strokePaint.color = strokeColorArgb
            canvas.drawPath(path, strokePaint)
        }
    }

    /**
     * The detached bubble: same shell treatment and outline as the island, with the owning app's
     * launcher icon inset inside it. [alpha] rides the compact presentation, so the bubble grows in
     * and retracts with the shell instead of blinking on and off.
     */
    private fun drawSatellite(canvas: Canvas, value: IslandContent?, alpha: Float) {
        if (alpha <= 0.004f) return
        val icon = satelliteIcon(value) ?: return
        val rect = satelliteBounds
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val save = canvas.save()
        if (alpha < 0.995f) {
            canvas.saveLayerAlpha(
                rect.left, rect.top, rect.right, rect.bottom,
                (alpha * 255f).toInt().coerceIn(0, 255),
            )
        }
        // A slight grow-in keeps the arrival soft without a second spring to keep in sync.
        val scale = 0.9f + 0.1f * alpha
        canvas.scale(scale, scale, rect.centerX(), rect.centerY())
        drawShell(canvas, rect, satelliteCornerPx, IslandShape.PILL, path = satellitePath)

        val pad = dp(SATELLITE_ICON_PAD_DP)
        val iconRect = RectF(rect)
        iconRect.inset(pad, pad)
        canvas.save()
        canvas.clipPath(satellitePath)
        drawIcon(canvas, icon, iconRect)
        canvas.restore()
        canvas.restoreToCount(save)
    }

    /**
     * Launcher icon of a *persistent* live activity: the playing app for music, our own icon for
     * the built-in timer, the owning app for maps and downloads. Notifications and the transient
     * charging/battery confirmations return null, so they never split the shell. Returning null
     * here also disables the bubble's hit target, so drawing and touch can never disagree.
     */
    private fun satelliteIcon(value: IslandContent?): Drawable? {
        if (!customization.detachedAppIcon) return null
        return when (value) {
            is IslandContent.Media -> value.appIcon
            is IslandContent.Timer -> timerAppIcon()
            is IslandContent.Navigation -> value.icon
            is IslandContent.Download -> value.icon
            else -> null
        }
    }

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
     * The expand/retract springs run at two different response times (width leads, height trails)
     * and the idle contraction runs a third; all three share one frame clock so they cannot drift.
     */
    private fun advanceMotion(now: Long) {
        val dt = if (lastStepMs == 0L) {
            1f / 120f
        } else {
            ((now - lastStepMs) / 1000f).coerceIn(0.003f, 0.032f)
        }
        lastStepMs = now

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

        if (!springActive && !dormantSettling) {
            removeCallbacks(settleWatchdog)
            requestMotionRate(animating || revealAnimating || progressAnimating, ambientOnly = animating || progressAnimating)
            // Let the owner re-place the touch window now that the shell is at rest.
            post { onMorphSettled?.invoke() }
        }
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
        val rate = when {
            !active || !motionAllowed -> 0f
            ambientOnly -> customization.ambientFrameRateHz.toFloat()
            highQualityMotion -> 120f
            else -> 90f
        }
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
        val elapsed = SystemClock.elapsedRealtime() - revealStartMs
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

    /**
     * Two disjoint areas flanking the hole (6 dp clearance) when it intersects the pill, or one
     * area covering the whole pill. With the hole at an edge (ONE_SIDE) only that side exists and
     * everything is laid out inside it.
     */
    private fun contentAreas(
        widthPx: Float,
        heightPx: Float,
        holeX: Float = holeCenterX,
        holeY: Float = holeCenterY,
    ): Areas {
        val whole = RectF(0f, 0f, widthPx, heightPx)
        if (holeRadiusPx <= 0f) return Areas(null, null, whole)

        val holeLeft = holeX - holeRadiusPx
        val holeRight = holeX + holeRadiusPx
        val holeTop = holeY - holeRadiusPx
        val holeBottom = holeY + holeRadiusPx
        // The hole is outside the pill (horizontally or vertically): no avoidance needed.
        if (holeRight <= 0f || holeLeft >= widthPx || holeBottom <= 0f || holeTop >= heightPx) {
            return Areas(null, null, whole)
        }

        val gap = dp(customization.sensorClearanceDp.toFloat())
        val minWidth = dp(18f)
        val left = RectF(0f, 0f, (holeLeft - gap).coerceAtLeast(0f), heightPx)
        val right = RectF((holeRight + gap).coerceAtMost(widthPx), 0f, widthPx, heightPx)
        val leftOk = left.width() >= minWidth
        val rightOk = right.width() >= minWidth
        return when {
            leftOk && rightOk -> Areas(left, right, whole)
            leftOk -> Areas(null, null, left)
            rightOk -> Areas(null, null, right)
            else -> Areas(null, null, whole)
        }
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
                val pad = dp(7f)
                drawPlayingBars(canvas, right.left + pad, right.right - pad, heightPx, media.accentColor)
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val artRect = drawCompactArt(canvas, media, area, heightPx)
                drawPlayingBars(canvas, artRect.right + dp(8f), area.right - dp(7f), heightPx, media.accentColor)
            }
        }
    }

    private fun drawCompactArt(
        canvas: Canvas,
        media: IslandContent.Media,
        area: RectF,
        heightPx: Float,
    ): RectF {
        val pad = dp(7f)
        val available = (area.width() - pad * 2f).coerceAtLeast(dp(10f))
        val artSize = (heightPx - pad * 2f)
            .coerceAtMost(dp(28f))
            .coerceAtLeast(dp(14f))
            .coerceAtMost(available)
        val left = area.left + pad
        val artRect = RectF(left, (heightPx - artSize) / 2f, left + artSize, (heightPx + artSize) / 2f)
        drawArt(canvas, artRect, dp(5f), media.albumArt)
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
        val iconSize = (heightPx - dp(12f)).coerceIn(dp(14f), dp(20f))
        if (areas.split) {
            val left = areas.left!!
            val right = areas.right!!
            val iconLeft = left.centerX() - iconSize / 2f
            canvas.inArea(left) {
                drawIcon(canvas, notification.icon, RectF(iconLeft, (heightPx - iconSize) / 2f, iconLeft + iconSize, (heightPx + iconSize) / 2f))
            }
            canvas.inArea(right) {
                drawCompactNotificationTitle(canvas, notification.title, right.left + dp(4f), right.right - dp(7f), heightPx)
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val iconLeft = area.left + dp(7f)
                drawIcon(canvas, notification.icon, RectF(iconLeft, (heightPx - iconSize) / 2f, iconLeft + iconSize, (heightPx + iconSize) / 2f))
                drawCompactNotificationTitle(canvas, notification.title, iconLeft + iconSize + dp(6f), area.right - dp(7f), heightPx)
            }
        }
    }

    private fun drawCompactNotificationTitle(
        canvas: Canvas,
        title: CharSequence,
        left: Float,
        right: Float,
        heightPx: Float,
    ) {
        titlePaint.textSize = sp(11.5f)
        val available = (right - left).coerceAtLeast(dp(12f))
        val line = TextUtils.ellipsize(title, titlePaint, available, TextUtils.TruncateAt.END)
        val baseline = heightPx / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
        drawFluidText(canvas, line, left, baseline, titlePaint)
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
            val actions = notification.actions.take(2)
            val gap = dp(8f)
            val visibleHeight = dp(36f)
            val top = area.bottom - visibleHeight
            val width = (area.width() - gap * (actions.size - 1)) / actions.size
            actions.forEachIndexed { index, action ->
                val left = area.left + index * (width + gap)
                val rect = RectF(left, top, left + width, top + visibleHeight)
                canvas.drawRoundRect(rect, dp(18f), dp(18f), buttonPaint)
                actionPaint.textSize = sp(12f)
                val label = TextUtils.ellipsize(action.title, actionPaint, width - dp(16f), TextUtils.TruncateAt.END)
                val baseline = rect.centerY() - (actionPaint.descent() + actionPaint.ascent()) / 2f
                canvas.drawText(label, 0, label.length, rect.centerX() - actionPaint.measureText(label.toString()) / 2f, baseline, actionPaint)
                // The visible capsule stays compact; the touch target grows to 44 dp vertically.
                val hit = RectF(rect)
                hit.inset(0f, -dp(4f))
                targets += Target(TargetKind.Action, index, hit)
            }
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
            canvas.inArea(areas.left!!) { drawBolt(canvas, areas.left.centerX(), heightPx / 2f, dp(16f)) }
            canvas.inArea(areas.right!!) {
                val width = chargingPaint.measureText(text)
                val scale = ((areas.right.width() - dp(8f)) / width.coerceAtLeast(1f)).coerceAtMost(1f)
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
                val scale = ((area.width() - dp(12f)) / total.coerceAtLeast(1f)).coerceAtMost(1f)
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
            canvas.inArea(areas.left!!) { drawTimerGlyph(canvas, areas.left.centerX(), heightPx / 2f, dp(15f), paused) }
            canvas.inArea(areas.right!!) {
                val text = timer.displayTime
                val width = chargingPaint.measureText(text)
                val scale = ((areas.right.width() - dp(6f)) / width.coerceAtLeast(1f)).coerceAtMost(1f)
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
                val scale = ((area.width() - dp(10f)) / total.coerceAtLeast(1f)).coerceAtMost(1f)
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
    ) {
        val iconSize = (heightPx - dp(12f)).coerceIn(dp(14f), dp(20f))
        titlePaint.textSize = sp(11.5f)
        titlePaint.color = color
        if (areas.split) {
            val left = areas.left!!
            val right = areas.right!!
            canvas.inArea(left) {
                val l = left.centerX() - iconSize / 2f
                drawIcon(canvas, icon, RectF(l, (heightPx - iconSize) / 2f, l + iconSize, (heightPx + iconSize) / 2f))
            }
            canvas.inArea(right) {
                val available = (right.width() - dp(10f)).coerceAtLeast(dp(12f))
                val line = TextUtils.ellipsize(text, titlePaint, available, TextUtils.TruncateAt.END)
                val baseline = heightPx / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
                canvas.drawText(line, 0, line.length, right.left + dp(4f), baseline, titlePaint)
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val l = area.left + dp(7f)
                drawIcon(canvas, icon, RectF(l, (heightPx - iconSize) / 2f, l + iconSize, (heightPx + iconSize) / 2f))
                val available = (area.right - l - iconSize - dp(12f)).coerceAtLeast(dp(12f))
                val line = TextUtils.ellipsize(text, titlePaint, available, TextUtils.TruncateAt.END)
                val baseline = heightPx / 2f - (titlePaint.descent() + titlePaint.ascent()) / 2f
                canvas.drawText(line, 0, line.length, l + iconSize + dp(6f), baseline, titlePaint)
            }
        }
        titlePaint.color = Color.WHITE
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
        val glyphWidth = dp(18f)
        if (areas.split) {
            canvas.inArea(areas.left!!) { drawBatteryGlyph(canvas, areas.left.centerX(), heightPx / 2f, glyphWidth, color, fill) }
            canvas.inArea(areas.right!!) {
                val width = chargingPaint.measureText(text)
                val scale = ((areas.right.width() - dp(8f)) / width.coerceAtLeast(1f)).coerceAtMost(1f)
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
                val scale = ((area.width() - dp(12f)) / total.coerceAtLeast(1f)).coerceAtMost(1f)
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
        val title = TextUtils.ellipsize(download.title, titlePaint, area.width() - dp(44f), TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, area.left, area.top + dp(16f), titlePaint)
        val label = if (download.indeterminate) "Working…" else "${download.percent}%"
        smallLabelPaint.textSize = sp(12f)
        smallLabelPaint.color = customization.downloadColorArgb
        canvas.drawText(label, area.right - smallLabelPaint.measureText(label), area.top + dp(16f), smallLabelPaint)
        if (download.text.isNotBlank()) {
            val text = TextUtils.ellipsize(download.text, subtitlePaint, area.width(), TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, area.left, area.top + dp(34f), subtitlePaint)
        }
        val track = RectF(area.left, area.top + dp(44f), area.right, area.top + dp(48f))
        canvas.drawRoundRect(track, dp(2f), dp(2f), trackPaint)
        accentPaint.color = customization.downloadColorArgb
        if (download.indeterminate) {
            val phase = (SystemClock.elapsedRealtime() % 1200L) / 1200f
            val segment = track.width() * 0.28f
            val left = track.left + (track.width() + segment) * phase - segment
            canvas.save()
            canvas.clipRect(track)
            canvas.drawRoundRect(RectF(left, track.top, left + segment, track.bottom), dp(2f), dp(2f), accentPaint)
            canvas.restore()
        } else {
            canvas.drawRoundRect(
                RectF(track.left, track.top, track.left + track.width() * download.percent / 100f, track.bottom),
                dp(2f), dp(2f), accentPaint,
            )
        }
        val buttonTop = (track.bottom + dp(10f)).coerceAtMost(area.bottom - dp(36f))
        val openWidth = dp(96f)
        val open = RectF(area.right - openWidth, buttonTop, area.right, buttonTop + dp(36f))
        drawPillButton(canvas, open, "Open")
        targets += Target(TargetKind.OpenContent, -1, expandedHit(open, dp(44f)))
        if (download.cancelIntent != null) {
            val cancel = RectF(area.left, buttonTop, area.left + dp(96f), buttonTop + dp(36f))
            drawPillButton(canvas, cancel, "Cancel")
            targets += Target(TargetKind.CancelDownload, -1, expandedHit(cancel, dp(44f)))
        }
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
        val available = (right - left).coerceAtLeast(dp(10f))
        val barWidth = dp(2.6f)
        val gap = dp(2.4f)
        val count = 3
        val total = barWidth * count + gap * (count - 1)
        var x = left + ((available - total) / 2f).coerceAtLeast(0f)
        val maxHeight = (heightPx * 0.44f).coerceAtMost(dp(22f))
        val minHeight = (heightPx * 0.16f).coerceAtMost(dp(8f))
        accentPaint.color = color
        val phase = (SystemClock.elapsedRealtime() % 900L) / 900f * (2f * Math.PI).toFloat()

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

    private fun drawIcon(canvas: Canvas, drawable: Drawable?, rect: RectF) {
        if (drawable == null) {
            canvas.drawRoundRect(rect, rect.width() * 0.25f, rect.width() * 0.25f, buttonPaint)
            return
        }
        runCatching {
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
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) { cancelTouch(); return true }
                val dx = event.getX(index) - downX
                val dy = event.getY(index) - downY
                if (dx * dx + dy * dy > touchSlop * touchSlop) cancelTouch()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) cancelTouch()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(activePointerId)
                val down = pressed
                cancelTouch()
                if (index >= 0 && down != null && !isMorphing()) {
                    val up = targetAt(event.getX(index), event.getY(index))
                    if (up != null && down.kind == up.kind && down.index == up.index && down.rect.contains(event.getX(index), event.getY(index))) {
                        dispatch(up)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTouch()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelTouch() {
        pressed = null
        activePointerId = -1
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settleWatchdog)
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
            val name = when (content) {
                is IslandContent.Media -> "media controls"
                is IslandContent.Notification -> "notification"
                is IslandContent.Timer -> "timer"
                is IslandContent.Charging -> "charging status"
                is IslandContent.BatteryLow -> "battery low"
                is IslandContent.PowerSave -> "power save"
                is IslandContent.Call -> "call"
                is IslandContent.Download -> "download"
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
        TargetKind.Action -> (content as? IslandContent.Notification)?.actions?.getOrNull(target.index)?.title ?: "Notification action"
        TargetKind.TimerToggle -> if ((content as? IslandContent.Timer)?.isRunning == true) "Pause timer" else "Resume timer"
        TargetKind.TimerStop -> "Cancel timer"
        TargetKind.CallAnswer -> "Answer call"
        TargetKind.CallDecline -> "Decline call"
        TargetKind.CallEnd -> "End call"
        TargetKind.OpenContent -> "Open"
        TargetKind.CancelDownload -> "Cancel download"
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
        when (target.kind) {
            TargetKind.Body -> callback.onBodyTap()
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
            TargetKind.CancelDownload -> callback.onCancelDownload()
        }
    }

    private enum class TargetKind {
        Body, Previous, PlayPause, Next, Action, TimerToggle, TimerStop,
        CallAnswer, CallDecline, CallEnd, OpenContent, CancelDownload,
    }

    private data class Target(val kind: TargetKind, val index: Int, val rect: RectF)

    internal data class IslandAccessibleTarget(val label: String, val rect: RectF)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics) *
            (textScalePercent / 100f)

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private companion object {
        /**
         * Detached app-icon bubble: squircle corner as a fraction of its height (0.34 keeps it a
         * rounded square rather than a circle) and the icon inset inside the shell.
         */
        const val SATELLITE_CORNER_FRACTION = 0.34f
        const val SATELLITE_ICON_PAD_DP = 5f

        /** Always-on display outline: at most 1 dp and never more than 2 physical px. */
        const val AOD_STROKE_PX = 2f

        /** Never let a lost frame callback strand the island in its oversized morph window. */
        const val MORPH_WATCHDOG_MS = 900L

        const val SWAP_DURATION_MS = IslandMotion.SWAP_DURATION_MS
        val REVEAL_TOTAL_MS = IslandMotion.REVEAL_DURATION_MS.toLong()
        const val customization.timerColorArgb = 0xFFFF9F0A.toInt()
        const val customization.chargingColorArgb = 0xFF30D158.toInt()
    }
}
