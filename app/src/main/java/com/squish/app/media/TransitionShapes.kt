package com.squish.app.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The reveal masks of the shape transitions (EDGE_PLAN F3, INSHOT_PARITY P3):
 * how much of the new shot shows at a pixel, a fraction [at] of the way through.
 *
 * Each shape is one number per pixel - [field], the moment in 0..1 that pixel
 * turns over - and [coverage] ramps across it with a soft edge [SOFT] wide. So
 * every shape is 0 everywhere at the start, 1 everywhere at the end, never
 * goes back as it runs, and its edge is never wider than [SOFT]: what
 * TransitionShapeChecks executes.
 *
 * This is the reference. squish_transition_es2.glsl is a transliteration of
 * it (the shader cannot be executed here), and the preview draws this very
 * function into a small mask (TransitionMask), so the screen and the checks
 * share one definition and the file shares its arithmetic.
 *
 * Coordinates are the frame's, 0..1, y running *down*, as ExportPlan.Draw's
 * are; [aspect] is the frame's width over its height, so a circle is round.
 */
object TransitionShapes {
    const val NONE = 0
    const val IRIS = 1
    const val IRIS_CLOSE = 2
    const val DIAMOND = 3
    const val STAR = 4
    const val HEART = 5
    const val CLOCK = 6
    const val BLINDS = 7
    const val BLINDS_SIDE = 8
    const val DOORS = 9
    const val SPLIT = 10
    const val DIAGONAL = 11
    const val CHECKER = 12
    const val PIXELS = 13
    const val STRIPES = 14
    const val LAST = STRIPES

    /** The soft edge, in the field's own units: a twenty-fifth of the run. */
    const val SOFT = 0.04f

    /** How much of the new shot shows at ([x], [y]) when [at] of the shape has run. */
    fun coverage(kind: Int, at: Float, x: Float, y: Float, aspect: Float): Float {
        if (kind == NONE || at >= 1f) return 1f
        if (at <= 0f) return 0f
        val f = field(kind, x, y, aspect)
        return ((at * (1f + SOFT) - f) / SOFT).coerceIn(0f, 1f)
    }

    /** When the pixel at ([x], [y]) turns over, 0..1. */
    fun field(kind: Int, x: Float, y: Float, aspect: Float): Float {
        val dx = (x - 0.5f) * aspect
        val dy = y - 0.5f
        val reach = sqrt(0.25f * aspect * aspect + 0.25f)
        val r = sqrt(dx * dx + dy * dy)
        val f = when (kind) {
            IRIS -> r / reach
            IRIS_CLOSE -> 1f - r / reach
            DIAMOND -> (abs(dx) + abs(dy)) / (0.5f * aspect + 0.5f)
            STAR -> {
                // Five points, one straight up: the radius swings between half and whole
                // with the angle from the top, which is the same either side of it.
                val a = atan2(dx, -dy)
                val c = abs(cos(2.5f * a))
                r / ((0.5f + 0.5f * c * c * c) * 2f * reach)
            }
            HEART -> heartScale(dx, dy) / reachFor(aspect)
            CLOCK -> {
                // Clockwise from twelve: y runs down, so right of the top is clockwise.
                val a = atan2(dx, -dy)
                ((a + 2f * PI.toFloat()) % (2f * PI.toFloat())) / (2f * PI.toFloat())
            }
            BLINDS -> fract(y * SLATS)
            BLINDS_SIDE -> fract(x * SLATS)
            DOORS -> abs(x - 0.5f) * 2f
            SPLIT -> abs(y - 0.5f) * 2f
            DIAGONAL -> (x + y) / 2f
            CHECKER -> {
                val cx = floor(x * CHECKS * aspect); val cy = floor(y * CHECKS)
                val odd = ((cx + cy).toInt() and 1).toFloat()
                odd * 0.5f + 0.5f * fract(x * CHECKS * aspect)
            }
            PIXELS -> hash(floor(x * PIXEL_ROWS * aspect), floor(y * PIXEL_ROWS))
            STRIPES -> {
                val odd = (floor(y * STRIPE_COUNT).toInt() and 1) == 1
                if (odd) 1f - x else x
            }
            else -> 0f
        }
        return f.coerceIn(0f, 1f)
    }

    // The heart's reach on the last frame shape asked about: four halvings a
    // pixel otherwise, for a number that only changes with the frame.
    private var reachAspect = Float.NaN
    private var reachValue = 1f
    private fun reachFor(aspect: Float): Float {
        if (aspect != reachAspect) { reachValue = heartReach(aspect); reachAspect = aspect }
        return reachValue
    }

    private const val SLATS = 8f
    private const val CHECKS = 6f
    private const val PIXEL_ROWS = 24f
    private const val STRIPE_COUNT = 10f

    private fun fract(v: Float) = v - floor(v)

    /** The same hash as the shaders' (squish_fx_es2.glsl), on a cell. */
    fun hash(px: Float, py: Float): Float {
        var x = fract(px * 0.1031f); var y = fract(py * 0.1031f); var z = fract(px * 0.1031f)
        val d = x * (y + 33.33f) + y * (z + 33.33f) + z * (x + 33.33f)
        x += d; y += d; z += d
        return fract((x + y) * z)
    }

    /**
     * The smallest size of heart that holds the point ([dx], [dy]) - y down -
     * found by halving, since the heart's curve (x² + y² − 1)³ = x²y³ has no
     * radius to read off. Its lobes are up; it is lifted so its middle, not
     * its own origin, is the frame's.
     */
    fun heartScale(dx: Float, dy: Float): Float {
        var lo = 0f
        var hi = 4f
        repeat(HEART_STEPS) {
            val s = 0.5f * (lo + hi)
            if (insideHeart(dx / s, -dy / s + HEART_LIFT)) hi = s else lo = s
        }
        return hi
    }

    /** The heart size that covers every corner of a frame [aspect] wide. */
    fun heartReach(aspect: Float): Float {
        val hx = 0.5f * aspect
        return max(max(heartScale(hx, 0.5f), heartScale(hx, -0.5f)), max(heartScale(0f, 0.5f), heartScale(0f, -0.5f))) * 1.02f
    }

    private fun insideHeart(x: Float, y: Float): Boolean {
        val a = x * x + y * y - 1f
        return a * a * a - x * x * y * y * y <= 0f
    }

    const val HEART_STEPS = 14
    const val HEART_LIFT = 0.15f
}
