package com.zenith.nothingisland.overlay

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frame-rate-independent motion math for the canvas shell.
 *
 * The previous semi-implicit Euler step changed its trajectory with the display refresh rate.
 * This is the closed-form solution of the same damped spring, so a 60 Hz and a 120 Hz panel land
 * on the same position at the same wall-clock time. Interrupting a morph keeps velocity intact.
 */
internal object IslandMotion {
    const val WIDTH_OMEGA = 24f
    const val WIDTH_ZETA = 0.88f
    const val HEIGHT_OMEGA = 17f
    const val HEIGHT_ZETA = 0.9f
    const val DORMANT_OMEGA = 16f
    const val DORMANT_ZETA = 0.9f
    const val DORMANT_WIDTH_LEAD = 1.6f
    const val DORMANT_HEIGHT_START = 0.45f
    const val SWAP_DURATION_MS = 160L
    const val REVEAL_DURATION_MS = 220f

    /** Writes position to out[0] and velocity to out[1]. All shell springs are underdamped. */
    fun stepSpring(
        position: Float,
        velocity: Float,
        target: Float,
        omega: Float,
        zeta: Float,
        dt: Float,
        out: FloatArray,
    ) {
        require(out.size >= 2)
        if (dt <= 0f) {
            out[0] = position
            out[1] = velocity
            return
        }
        val damping = zeta * omega
        val dampedOmega = omega * sqrt(1f - zeta * zeta)
        val decay = exp(-damping * dt)
        val phase = dampedOmega * dt
        val cosine = cos(phase)
        val sine = sin(phase)
        val offset = position - target
        val sineVelocity = (damping * velocity + omega * omega * offset) / dampedOmega

        out[0] = target + decay * (offset * cosine + (velocity + damping * offset) / dampedOmega * sine)
        out[1] = decay * (velocity * cosine - sineVelocity * sine)
    }

    fun smoothStep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    fun easeOutCubic(value: Float): Float {
        val inv = 1f - value.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    /** Width closes first, so the shell slides into the lens rather than shrinking in place. */
    fun dormantWidthShare(progress: Float): Float =
        1f - (1f - progress.coerceIn(0f, 1f)).pow(DORMANT_WIDTH_LEAD)

    fun dormantHeightShare(progress: Float): Float =
        smoothStep((progress.coerceIn(0f, 1f) - DORMANT_HEIGHT_START) / (1f - DORMANT_HEIGHT_START))
}
