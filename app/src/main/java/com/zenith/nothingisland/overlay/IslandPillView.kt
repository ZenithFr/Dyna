package com.zenith.nothingisland.overlay

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
import com.zenith.nothingisland.data.IslandShape
import com.zenith.nothingisland.data.buildIslandPath
import com.zenith.nothingisland.service.IslandContent
import com.zenith.nothingisland.service.IslandEvent
import kotlin.math.abs
import kotlin.math.max
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
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
     * disappears into the punch-hole when there is nothing to show.
     */
    private var dormantProgress = 1f
    private var dormantVelocity = 0f
    private var dormantTarget = 0f
    private var dormantSettling = false
    /** True once the shell has been told to curl into the camera hole (mid-flight or settled). */
    fun isDormant(): Boolean = dormantTarget > 0.5f
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
    private val targets = mutableListOf<Target>()

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
        compactCornerPx: Float,
        expandedCornerPx: Float,
    ) {
        compactBounds.set(compactFrame)
        expandedBounds.set(expandedFrame)
        dormantBounds.set(dormantFrame)
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

        // A new notification arrives: reveal its text fluidly (per character, short stagger).
        val notification = newContent as? IslandContent.Notification
        if (notification != null) {
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
        if (oldKey != null && newKey != null && oldKey != newKey &&
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
        animating = (newContent as? IslandContent.Media)?.isPlaying == true

        val target = if (newExpanded) 1f else 0f
        if (framesReady && !springActive && abs(progress - target) > 0.001f) {
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
        is IslandContent.Media -> "media:${value.title}:${value.artist}:${value.isPlaying}"
        is IslandContent.Notification -> "notification:${value.key}"
        is IslandContent.Charging -> "charging"
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
        val cage = dormantProgress.coerceIn(0f, 1f)
        val frame = currentFrame()
        val corner = lerp(
            compactCornerPx + (expandedCornerPx - compactCornerPx) * p,
            dormantCornerPx,
            cage,
        )

        // Contracted to a circle behind the lens it must be a circle whatever the shape setting is.
        val shellShape = if (cage > 0.35f) IslandShape.PILL else shape
        drawShell(canvas, frame, corner, shellShape)
        targets.clear()

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
        canvas.save()
        canvas.clipPath(shellPath)
        val outgoing = previousContent
        val swapping = outgoing != null && swapAnimating && swapT < 1f
        if (swapping) {
            drawContentStack(
                canvas, outgoing, expandedIn, compactOut, cage,
                widthPx, heightPx, stackAlpha = 1f - swapT,
            )
        }
        if (current != null) {
            drawContentStack(
                canvas, current, expandedIn, compactOut, cage,
                widthPx, heightPx, stackAlpha = if (swapping) swapT else 1f,
            )
        }
        canvas.restore()

        if (holeGuideVisible && holeRadiusPx > 0f) drawHoleGuide(canvas)

        // No hit targets while morphing: a half-grown button should never be tappable.
        if (springActive || dormantSettling) {
            targets.clear()
        } else if (current != null) {
            // Whole-pill fallback target. Inserted first so the specific transport / action
            // targets added above are found first by [targetAt].
            val body = currentFrame()
            targets.add(0, Target(TargetKind.Body, -1, body))
        }

        val revealRunning = revealAnimating &&
            (now - revealStartMs) < REVEAL_TOTAL_MS
        if (revealAnimating && !revealRunning) revealAnimating = false
        val busy = animating || springActive || dormantSettling || revealRunning || swapAnimating
        requestMotionRate(busy)
        if (busy) postInvalidateOnAnimation()
    }

    /** One activity in both presentations, crossfaded by [stackAlpha] when activities swap. */
    private fun drawContentStack(
        canvas: Canvas,
        value: IslandContent,
        expandedIn: Float,
        compactOut: Float,
        cage: Float,
        viewWidth: Float,
        viewHeight: Float,
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
                viewWidth = viewWidth,
                viewHeight = viewHeight,
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
                viewWidth = viewWidth,
                viewHeight = viewHeight,
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
        viewWidth: Float,
        viewHeight: Float,
        scale: Float = 1f,
        risePx: Float = 0f,
    ) {
        val firstTarget = targets.size
        val save = canvas.save()
        if (alpha < 0.999f) {
            canvas.saveLayerAlpha(
                0f, 0f, viewWidth, viewHeight,
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
                    drawMediaExpanded(canvas, current, areas, heightPx)
                } else {
                    drawMediaCompact(canvas, current, areas, heightPx)
                }

            is IslandContent.Notification ->
                if (expandedVariant) {
                    drawNotificationExpanded(canvas, current, areas, heightPx)
                } else {
                    drawNotificationCompact(canvas, current, areas, heightPx)
                }

            is IslandContent.Charging -> drawCharging(canvas, current, areas, heightPx)

            is IslandContent.Timer ->
                if (expandedVariant) {
                    drawTimerExpanded(canvas, current, areas, heightPx)
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

    private fun drawShell(canvas: Canvas, frame: RectF, cornerPx: Float, shellShape: IslandShape) {
        // On the always-on display the island is a black shape on a black screen, so only an
        // outline can be seen — and it is forced to a hairline (~2 px) instead of the user width.
        val strokePx = if (aod) min(AOD_STROKE_PX, density) else strokeWidthPx
        val strokeOn = aod || (strokeEnabled && strokeWidthPx > 0f)
        // Without a stroke the shell fills the frame; with one, the outline is centred on the
        // edge, so the shape is inset by half the stroke width.
        val half = if (strokeOn) strokePx / 2f else 0f
        val innerWidth = (frame.width() - half * 2f).coerceAtLeast(1f)
        val innerHeight = (frame.height() - half * 2f).coerceAtLeast(1f)
        buildIslandPath(shellPath, shellShape, innerWidth, innerHeight, cornerPx)
        shellPath.offset(frame.left + half, frame.top + half)
        canvas.drawPath(shellPath, shellPaint)
        if (strokeOn) {
            strokePaint.strokeWidth = strokePx
            strokePaint.color = strokeColorArgb
            canvas.drawPath(shellPath, strokePaint)
        }
    }

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

            stepSpring(widthProgress, widthVelocity, target, WIDTH_OMEGA, WIDTH_ZETA, dt)
            widthProgress = springOut[0]
            widthVelocity = springOut[1]

            stepSpring(heightProgress, heightVelocity, target, HEIGHT_OMEGA, HEIGHT_ZETA, dt)
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
                DORMANT_OMEGA, DORMANT_ZETA, dt,
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
            requestMotionRate(animating || revealAnimating)
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
        val accel = omega * omega * (target - position) - 2f * zeta * omega * velocity
        val nextVelocity = velocity + accel * dt
        springOut[0] = position + nextVelocity * dt
        springOut[1] = nextVelocity
    }

    private fun currentFrame(): RectF {
        // Horizontal edges follow the leading spring, vertical edges the trailing one.
        val w = widthProgress
        val h = heightProgress
        val frame = RectF(
            lerp(compactBounds.left, expandedBounds.left, w),
            lerp(compactBounds.top, expandedBounds.top, h),
            lerp(compactBounds.right, expandedBounds.right, w),
            lerp(compactBounds.bottom, expandedBounds.bottom, h),
        )
        // Then the idle contraction wraps that frame into the circle behind the camera hole.
        val cage = dormantProgress.coerceIn(0f, 1f)
        if (cage > 0.001f) {
            frame.set(
                lerp(frame.left, dormantBounds.left, cage),
                lerp(frame.top, dormantBounds.top, cage),
                lerp(frame.right, dormantBounds.right, cage),
                lerp(frame.bottom, dormantBounds.bottom, cage),
            )
        }
        return frame
    }

    /**
     * Asks the panel for the frame rate this view needs. Motion (morph, reveal, playing bars) runs
     * at 120 Hz — the pill is a small window, so it costs one small layer per frame — and the hint
     * is dropped to 0 the moment the island is still, so an idle pill never pins the panel high.
     */
    private fun requestMotionRate(active: Boolean) {
        val rate = when {
            !active -> 0f
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
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun easeOutCubic(value: Float): Float {
        val inv = 1f - value
        return 1f - inv * inv * inv
    }

    private fun drawFluidText(
        canvas: Canvas,
        text: CharSequence,
        x: Float,
        baseline: Float,
        paint: TextPaint,
    ) {
        val string = text.toString()
        val elapsed = SystemClock.elapsedRealtime() - revealStartMs
        if (!revealAnimating || elapsed >= REVEAL_TOTAL_MS) {
            canvas.drawText(string, x, baseline, paint)
            return
        }
        val baseAlpha = paint.alpha
        var cursor = x
        var index = 0
        var offset = 0
        while (offset < string.length) {
            val codePoint = string.codePointAt(offset)
            val length = Character.charCount(codePoint)
            val chunk = string.substring(offset, offset + length)
            val delay = min(index * REVEAL_STAGGER_MS, REVEAL_MAX_STAGGER_MS)
            val t = ((elapsed - delay) / REVEAL_DURATION_MS).coerceIn(0f, 1f)
            val eased = easeOutCubic(t)
            paint.alpha = (baseAlpha * eased).toInt().coerceIn(0, 255)
            canvas.drawText(chunk, cursor, baseline + (1f - eased) * dp(3.5f), paint)
            cursor += paint.measureText(chunk)
            offset += length
            index++
        }
        paint.alpha = baseAlpha
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

        val gap = dp(6f)
        val minWidth = dp(24f)
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
                drawPlayingBars(canvas, right.left + pad, right.right - pad, heightPx)
            }
        } else {
            val area = areas.single
            canvas.inArea(area) {
                val artRect = drawCompactArt(canvas, media, area, heightPx)
                drawPlayingBars(canvas, artRect.right + dp(8f), area.right - dp(7f), heightPx)
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

    private fun drawMediaExpanded(
        canvas: Canvas,
        media: IslandContent.Media,
        areas: Areas,
        heightPx: Float,
    ) {
        val split = areas.split
        val textArea = if (split) areas.left!! else areas.single
        val controlsArea = if (split) areas.right!! else areas.single
        val pad = dp(12f)

        // A split body can be as narrow as ~86 dp per side (equal split, expanded pill), so the
        // artwork and transport buttons shrink instead of spilling into the excluded hole area.
        val artSize = if (split) {
            (textArea.width() - pad * 2f - dp(32f)).coerceIn(dp(20f), dp(46f))
        } else {
            dp(46f)
        }
        val artRect = RectF(
            textArea.left + pad,
            (heightPx - artSize) / 2f,
            textArea.left + pad + artSize,
            (heightPx + artSize) / 2f,
        )
        drawArt(canvas, artRect, dp(8f), media.albumArt)

        val buttonGap = if (split) dp(4f) else dp(5f)
        val buttonSpace = (controlsArea.width() - pad * 2f - buttonGap * 2f)
            .coerceAtLeast(dp(3f))
        val buttonSize = (buttonSpace / 3f).coerceIn(dp(16f), dp(30f))
        val controlsWidth = buttonSize * 3 + buttonGap * 2
        val controlsLeft = (controlsArea.right - pad - controlsWidth)
            .coerceAtLeast(controlsArea.left + dp(2f))
        val controlsTop = (heightPx - buttonSize) / 2f - dp(3f)

        val textLeft = artRect.right + dp(10f)
        val textRight = if (split) textArea.right - dp(8f) else controlsLeft - dp(8f)

        val titleMax = (textRight - textLeft).coerceAtLeast(dp(24f))
        titlePaint.textSize = sp(12.5f)
        val title = TextUtils.ellipsize(media.title, titlePaint, titleMax, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, textLeft, pad + titlePaint.textSize, titlePaint)

        subtitlePaint.textSize = sp(10.5f)
        val subtitleText = media.artist.ifBlank { "Now playing" }
        val subtitle = TextUtils.ellipsize(
            subtitleText,
            subtitlePaint,
            titleMax,
            TextUtils.TruncateAt.END,
        )
        canvas.drawText(
            subtitle,
            0,
            subtitle.length,
            textLeft,
            pad + titlePaint.textSize + dp(15f),
            subtitlePaint,
        )

        canvas.inArea(controlsArea) {
            drawControlButton(
                canvas, controlsLeft, controlsTop, buttonSize,
                TransportCommand.Previous, isPlaying = false, clip = controlsArea,
            )
            drawControlButton(
                canvas, controlsLeft + buttonSize + buttonGap, controlsTop, buttonSize,
                TransportCommand.PlayPause, isPlaying = media.isPlaying, clip = controlsArea,
            )
            drawControlButton(
                canvas, controlsLeft + (buttonSize + buttonGap) * 2, controlsTop, buttonSize,
                TransportCommand.Next, isPlaying = false, clip = controlsArea,
            )
        }

        val progressArea = areas.widest
        val progressTop = heightPx - dp(9f)
        val trackRect = RectF(
            progressArea.left + pad,
            progressTop,
            progressArea.right - pad,
            progressTop + dp(2.5f),
        )
        canvas.drawRoundRect(trackRect, dp(1.5f), dp(1.5f), trackPaint)
        if (media.durationMs > 0L) {
            val fraction = (
                media.estimatePositionMs(SystemClock.elapsedRealtime()).toFloat() /
                    media.durationMs.toFloat()
                ).coerceIn(0f, 1f)
            if (fraction > 0f) {
                val filled = RectF(
                    trackRect.left,
                    trackRect.top,
                    trackRect.left + trackRect.width() * fraction,
                    trackRect.bottom,
                )
                canvas.drawRoundRect(filled, dp(1.5f), dp(1.5f), progressPaint)
            }
        }
    }

    private fun drawNotificationCompact(
        canvas: Canvas,
        notification: IslandContent.Notification,
        areas: Areas,
        heightPx: Float,
    ) {
        // No app icon here: the status bar already shows the same icon right above the island.
        // The wider half of a split layout carries the title; a one-sided layout has one area.
        val area = areas.widest
        canvas.inArea(area) {
            drawCompactNotificationTitle(
                canvas,
                notification.title,
                area.left + dp(11f),
                area.right - dp(8f),
                heightPx,
            )
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

    private fun drawNotificationExpanded(
        canvas: Canvas,
        notification: IslandContent.Notification,
        areas: Areas,
        heightPx: Float,
    ) {
        val split = areas.split
        val textArea = if (split) areas.left!! else areas.single
        val actionsArea = if (split) areas.right!! else areas.single
        val pad = dp(12f)
        val hasActions = notification.actions.isNotEmpty()

        // The app icon is deliberately omitted: the status bar shows it just above the island.
        val textLeft = textArea.left + pad
        val textRight = textArea.right - pad
        val textMax = (textRight - textLeft).coerceAtLeast(dp(24f))

        titlePaint.textSize = sp(12.5f)
        subtitlePaint.textSize = sp(10.5f)
        val title = TextUtils.ellipsize(
            notification.title,
            titlePaint,
            textMax,
            TextUtils.TruncateAt.END,
        )
        val body = notification.text.ifBlank { notification.appLabel }
        val line = TextUtils.ellipsize(body, subtitlePaint, textMax, TextUtils.TruncateAt.END)

        // With buttons the text sits at the top; without them the two lines are centred so the
        // expanded pill does not look top-heavy.
        val blockHeight = titlePaint.textSize + dp(15f)
        val titleBaseline = if (hasActions) {
            pad + titlePaint.textSize
        } else {
            (heightPx - blockHeight) / 2f + titlePaint.textSize
        }
        canvas.inArea(textArea) {
            drawFluidText(canvas, title, textLeft, titleBaseline, titlePaint)
            drawFluidText(canvas, line, textLeft, titleBaseline + dp(15f), subtitlePaint)
        }

        if (hasActions) {
            val pillHeight = dp(21f)
            val pillTop = heightPx - pad - pillHeight
            val rightLimit = actionsArea.right - pad
            var left = actionsArea.left + if (split) dp(4f) else pad
            canvas.inArea(actionsArea) {
                notification.actions.take(2).forEachIndexed { index, action ->
                    actionPaint.textSize = sp(9.5f)
                    val textWidth = actionPaint.measureText(action.title)
                    val pillWidth = textWidth + dp(18f)
                    val pillRight = (left + pillWidth).coerceAtMost(rightLimit)
                    if (pillRight - left < dp(20f)) return@forEachIndexed
                    val rect = RectF(left, pillTop, pillRight, pillTop + pillHeight)
                    canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, buttonPaint)
                    val baseline = rect.centerY() - (actionPaint.descent() + actionPaint.ascent()) / 2f
                    val label = TextUtils.ellipsize(
                        action.title,
                        actionPaint,
                        rect.width() - dp(12f),
                        TextUtils.TruncateAt.END,
                    )
                    canvas.drawText(label, 0, label.length, left + dp(9f), baseline, actionPaint)
                    targets += Target(TargetKind.Action, index, rect)
                    left = pillRight + dp(6f)
                }
            }
        } else if (split) {
            // Only the physically split right half gets the app name; on a single area the title
            // and body already span the full width and a right-aligned label would collide.
            val labelMax = (actionsArea.width() - pad * 2f).coerceAtLeast(dp(24f))
            val label = TextUtils.ellipsize(
                notification.appLabel,
                subtitlePaint,
                labelMax,
                TextUtils.TruncateAt.END,
            )
            val labelBaseline = (heightPx - (subtitlePaint.descent() + subtitlePaint.ascent())) / 2f
            canvas.inArea(actionsArea) {
                canvas.drawText(
                    label,
                    0,
                    label.length,
                    actionsArea.right - pad - subtitlePaint.measureText(label.toString()),
                    labelBaseline,
                    subtitlePaint,
                )
            }
        }
    }

    private fun drawCharging(
        canvas: Canvas,
        charging: IslandContent.Charging,
        areas: Areas,
        heightPx: Float,
    ) {
        val area = areas.widest
        canvas.inArea(area) {
            val text = if (charging.level >= 0) "${charging.level}%" else "CHARGING"
            chargingPaint.textSize = sp(13f)
            val textWidth = chargingPaint.measureText(text)
            val boltWidth = dp(11f)
            val boltHeight = dp(16f)
            val gap = dp(7f)
            val totalWidth = boltWidth + gap + textWidth
            val available = (area.width() - dp(8f)).coerceAtLeast(dp(1f))
            // A centred hole can leave a narrow side; shrink the group rather than cross it.
            val scale = (available / totalWidth).coerceIn(0.55f, 1f)

            canvas.save()
            canvas.translate(area.centerX(), heightPx / 2f)
            canvas.scale(scale, scale)

            val bx = -totalWidth / 2f
            val by = -boltHeight / 2f
            boltPath.reset()
            boltPath.moveTo(bx + boltWidth * 0.58f, by)
            boltPath.lineTo(bx + boltWidth * 0.12f, by + boltHeight * 0.56f)
            boltPath.lineTo(bx + boltWidth * 0.44f, by + boltHeight * 0.56f)
            boltPath.lineTo(bx + boltWidth * 0.34f, by + boltHeight)
            boltPath.lineTo(bx + boltWidth * 0.88f, by + boltHeight * 0.42f)
            boltPath.lineTo(bx + boltWidth * 0.54f, by + boltHeight * 0.42f)
            boltPath.close()
            canvas.drawPath(boltPath, whitePaint)

            val baseline = -(chargingPaint.descent() + chargingPaint.ascent()) / 2f
            canvas.drawText(text, bx + boltWidth + gap, baseline, chargingPaint)
            canvas.restore()
        }
    }

    /**
     * Compact pill: always the countdown (mm:ss), centred in the wider side of the hole, with a
     * small pause glyph while frozen. The label is not shown here — digits are the useful
     * information at this size.
     */
    private fun drawTimerCompact(
        canvas: Canvas,
        timer: IslandContent.Timer,
        areas: Areas,
        heightPx: Float,
    ) {
        val area = areas.widest
        canvas.inArea(area) {
            val timeText = timer.displayTime
            val timePaint = TextPaint(chargingPaint).apply { textSize = sp(13f) }
            val timeWidth = timePaint.measureText(timeText)

            // Pause glyph only while paused mid-countdown; finished 00:00 has nothing to freeze.
            val showPause = !timer.isRunning && timer.remainingMs > 0L
            val barWidth = dp(1.8f)
            val barGap = dp(1.8f)
            val glyphWidth = barWidth * 2f + barGap
            val glyphHeight = heightPx * 0.34f
            val glyphGap = if (showPause) dp(6f) else 0f

            val totalWidth = glyphWidth + glyphGap + timeWidth
            val available = (area.width() - dp(6f)).coerceAtLeast(dp(1f))
            val scale = (available / totalWidth).coerceIn(0.6f, 1f)

            canvas.save()
            canvas.translate(area.centerX(), heightPx / 2f)
            canvas.scale(scale, scale)

            val left = -totalWidth / 2f
            if (showPause) {
                val cx = left + barWidth + barGap / 2f
                val top = -glyphHeight / 2f
                canvas.drawRoundRect(
                    RectF(cx - barGap / 2f - barWidth, top, cx - barGap / 2f, top + glyphHeight),
                    barWidth / 2f, barWidth / 2f, whitePaint,
                )
                canvas.drawRoundRect(
                    RectF(cx + barGap / 2f, top, cx + barGap / 2f + barWidth, top + glyphHeight),
                    barWidth / 2f, barWidth / 2f, whitePaint,
                )
            }

            val baseline = -(timePaint.descent() + timePaint.ascent()) / 2f
            canvas.drawText(
                timeText,
                0,
                timeText.length,
                left + glyphWidth + glyphGap,
                baseline,
                timePaint,
            )
            canvas.restore()
        }
    }

    /**
     * Expanded pill: label + large countdown on the left, pause/resume and stop buttons on the
     * right. When the hole splits the body, text and buttons live in their own areas; with a
     * single area (one-side mode) the original full-width layout is used inside it.
     */
    private fun drawTimerExpanded(
        canvas: Canvas,
        timer: IslandContent.Timer,
        areas: Areas,
        heightPx: Float,
    ) {
        val split = areas.split
        val textArea = if (split) areas.left!! else areas.single
        val controlsArea = if (split) areas.right!! else areas.single
        val pad = dp(12f)
        val buttonHeight = dp(30f)
        val buttonGap = dp(7f)
        val finished = timer.remainingMs <= 0L

        val toggleLabel = if (timer.isRunning) "PAUSE" else "RESUME"
        actionPaint.textSize = sp(9.5f)
        val toggleTextWidth = actionPaint.measureText(toggleLabel)
        val stopTextWidth = actionPaint.measureText("STOP")
        val naturalWidth = maxOf(toggleTextWidth, stopTextWidth) + dp(33f)
        val availableButtons = (controlsArea.width() - pad * 2f).coerceAtLeast(dp(1f))
        val maxButtonWidth = if (finished) availableButtons else (availableButtons - buttonGap) / 2f
        val buttonWidth = minOf(naturalWidth, maxButtonWidth).coerceAtLeast(dp(30f))

        val stopRect = RectF(
            controlsArea.right - pad - buttonWidth,
            (heightPx - buttonHeight) / 2f,
            controlsArea.right - pad,
            (heightPx + buttonHeight) / 2f,
        )
        val toggleRect = RectF(
            stopRect.left - buttonGap - buttonWidth,
            stopRect.top,
            stopRect.left - buttonGap,
            stopRect.bottom,
        )

        val textLeft = textArea.left + pad
        val textRight = if (split) {
            textArea.right - dp(10f)
        } else {
            (if (finished) stopRect.left else toggleRect.left) - dp(10f)
        }
        val textMax = (textRight - textLeft).coerceAtLeast(dp(24f))

        canvas.inArea(textArea) {
            val labelPaint = TextPaint(titlePaint).apply {
                textSize = sp(12.5f)
                color = if (finished) Color.rgb(215, 25, 33) else Color.WHITE
            }
            val timePaint = TextPaint(chargingPaint).apply { textSize = sp(26f) }

            val label = TextUtils.ellipsize(
                timer.label.ifBlank { "TIMER" },
                labelPaint,
                textMax,
                TextUtils.TruncateAt.END,
            )
            val labelAscender = -labelPaint.ascent()
            val timeAscender = -timePaint.ascent()
            val groupHeight = labelAscender + dp(4f) + timeAscender
            val top = (heightPx - groupHeight) / 2f

            canvas.drawText(label, 0, label.length, textLeft, top + labelAscender, labelPaint)

            // A split body leaves a narrow text column; scale the countdown rather than clip it.
            val timeWidth = timePaint.measureText(timer.displayTime)
            val timeScale = (textMax / timeWidth.coerceAtLeast(1f)).coerceIn(0.55f, 1f)
            canvas.save()
            canvas.translate(textLeft, top + labelAscender + dp(4f) + timeAscender)
            canvas.scale(timeScale, timeScale)
            canvas.drawText(timer.displayTime, 0f, 0f, timePaint)
            canvas.restore()
        }

        canvas.inArea(controlsArea) {
            if (!finished) {
                drawTimerButton(canvas, toggleRect, toggleLabel, TimerGlyph.Toggle(timer.isRunning))
                targets += Target(TargetKind.TimerToggle, -1, toggleRect)
            }
            drawTimerButton(canvas, stopRect, "STOP", TimerGlyph.Stop)
            targets += Target(TargetKind.TimerStop, -1, stopRect)
        }
    }

    /** Rounded button with a small glyph and a monospace-ish label, matching the media controls. */
    private fun drawTimerButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        glyph: TimerGlyph,
    ) {
        canvas.drawRoundRect(rect, dp(10f), dp(10f), buttonPaint)

        val glyphSize = dp(9f)
        val gap = dp(6f)
        actionPaint.textSize = sp(9.5f)
        val labelWidth = actionPaint.measureText(label)
        val contentLeft = rect.centerX() - (glyphSize + gap + labelWidth) / 2f
        val cx = contentLeft + glyphSize / 2f
        val cy = rect.centerY()
        val baseline = cy - (actionPaint.descent() + actionPaint.ascent()) / 2f

        when (glyph) {
            is TimerGlyph.Toggle -> if (glyph.pause) {
                val barWidth = glyphSize * 0.28f
                val barGap = glyphSize * 0.22f
                canvas.drawRoundRect(
                    RectF(cx - barGap / 2f - barWidth, cy - glyphSize / 2f, cx - barGap / 2f, cy + glyphSize / 2f),
                    barWidth / 2f, barWidth / 2f, whitePaint,
                )
                canvas.drawRoundRect(
                    RectF(cx + barGap / 2f, cy - glyphSize / 2f, cx + barGap / 2f + barWidth, cy + glyphSize / 2f),
                    barWidth / 2f, barWidth / 2f, whitePaint,
                )
            } else {
                iconPath.reset()
                iconPath.moveTo(cx - glyphSize * 0.32f, cy - glyphSize / 2f)
                iconPath.lineTo(cx + glyphSize * 0.5f, cy)
                iconPath.lineTo(cx - glyphSize * 0.32f, cy + glyphSize / 2f)
                iconPath.close()
                canvas.drawPath(iconPath, whitePaint)
            }

            TimerGlyph.Stop -> canvas.drawRoundRect(
                RectF(cx - glyphSize * 0.4f, cy - glyphSize * 0.4f, cx + glyphSize * 0.4f, cy + glyphSize * 0.4f),
                dp(1.5f), dp(1.5f), whitePaint,
            )
        }

        canvas.drawText(label, contentLeft + glyphSize + gap, baseline, actionPaint)
    }

    private sealed interface TimerGlyph {
        data class Toggle(val pause: Boolean) : TimerGlyph
        data object Stop : TimerGlyph
    }


    private fun drawControlButton(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        transport: TransportCommand,
        isPlaying: Boolean,
        clip: RectF? = null,
    ) {
        val rect = RectF(left, top, left + size, top + size)
        canvas.drawRoundRect(rect, dp(9f), dp(9f), buttonPaint)
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
                        0.6f, 0.6f, whitePaint,
                    )
                    canvas.drawRoundRect(
                        RectF(cx + unit * 0.75f - barWidth, cy - halfHeight, cx + unit * 0.75f, cy + halfHeight),
                        0.6f, 0.6f, whitePaint,
                    )
                } else {
                    iconPath.reset()
                    iconPath.moveTo(cx - unit * 0.55f, cy - unit)
                    iconPath.lineTo(cx + unit * 0.95f, cy)
                    iconPath.lineTo(cx - unit * 0.55f, cy + unit)
                    iconPath.close()
                    canvas.drawPath(iconPath, whitePaint)
                }
            }

            TransportCommand.Previous, TransportCommand.Next -> {
                val direction = if (transport == TransportCommand.Previous) -1f else 1f
                val barX = cx + direction * unit * 0.95f
                canvas.drawRoundRect(
                    RectF(barX - unit * 0.14f, cy - unit, barX + unit * 0.14f, cy + unit),
                    0.6f, 0.6f, whitePaint,
                )
                iconPath.reset()
                // Bar sits on the far side, triangle points toward it: |◀ for previous, ▶| for next.
                iconPath.moveTo(cx - direction * unit * 0.62f, cy - unit)
                iconPath.lineTo(cx + direction * unit * 0.62f, cy)
                iconPath.lineTo(cx - direction * unit * 0.62f, cy + unit)
                iconPath.close()
                canvas.drawPath(iconPath, whitePaint)
            }
        }

        val kind = when (transport) {
            TransportCommand.Previous -> TargetKind.Previous
            TransportCommand.PlayPause -> TargetKind.PlayPause
            TransportCommand.Next -> TargetKind.Next
        }
        // The drawing is clipped to the controls area, so the hit target must be too; otherwise a
        // cut-off button could still be tapped over the camera hole.
        val target = RectF(rect)
        if (clip == null || target.intersect(clip)) {
            targets += Target(kind, -1, target)
        }
    }

    private fun drawPlayingBars(canvas: Canvas, left: Float, right: Float, heightPx: Float) {
        val available = (right - left).coerceAtLeast(dp(10f))
        val barWidth = dp(2.6f)
        val gap = dp(2.4f)
        val count = 3
        val total = barWidth * count + gap * (count - 1)
        var x = left + ((available - total) / 2f).coerceAtLeast(0f)
        val maxHeight = heightPx * 0.44f
        val minHeight = heightPx * 0.16f
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
                whitePaint,
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
                pressed = targetAt(event.x, event.y)
                if (pressed == null) return false
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val down = pressed
                pressed = null
                val up = targetAt(event.x, event.y)
                if (down != null && up != null &&
                    down.kind == up.kind && down.index == up.index
                ) {
                    dispatch(up)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
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
        }
    }

    private enum class TargetKind { Body, Previous, PlayPause, Next, Action, TimerToggle, TimerStop }

    private data class Target(val kind: TargetKind, val index: Int, val rect: RectF)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private companion object {
        /**
         * Morph springs. The width leads (fast, almost no bounce); the height trails it by roughly
         * a tenth of a second so the shell stretches before it settles.
         */
        const val WIDTH_OMEGA = 24f
        const val WIDTH_ZETA = 0.88f
        const val HEIGHT_OMEGA = 17f
        const val HEIGHT_ZETA = 0.9f

        /** Curling into / out of the camera hole: a touch softer than the expand springs. */
        const val DORMANT_OMEGA = 21f
        const val DORMANT_ZETA = 0.92f

        /** Always-on display outline: at most 1 dp and never more than 2 physical px. */
        const val AOD_STROKE_PX = 2f

        /** Never let a lost frame callback strand the island in its oversized morph window. */
        const val MORPH_WATCHDOG_MS = 900L

        /** Crossfade when the island switches activities while settled. */
        const val SWAP_DURATION_MS = 160L

        /** Reveal timing for incoming notification text. */
        const val REVEAL_DURATION_MS = 260f
        const val REVEAL_STAGGER_MS = 26f
        const val REVEAL_MAX_STAGGER_MS = 240f
        const val REVEAL_TOTAL_MS = REVEAL_MAX_STAGGER_MS + REVEAL_DURATION_MS
    }
}
