package com.squish.app.media.effects

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Lift, gamma and gain - the three wheels every colourist reaches for first,
 * and the other half of the Curves tool.
 *
 * A wheel is three numbers, one per channel, around neutral. What the pad on
 * screen shows - a dot in a disc, red up - and what the shader gets are the
 * same three numbers: [Wheel.of] turns a dot into them and [Wheel.pad] turns
 * them back, exactly, so dragging the dot and reopening the sheet put it in the
 * same place.
 *
 * Nothing here touches Android, and tools/jvm/WheelChecks.kt executes it.
 */

/**
 * One wheel's offsets, per channel, around neutral. Each runs -1 to 1; what
 * that *does* depends on which wheel it is, and [ColorWheels] says.
 */
data class Wheel(val r: Float = 0f, val g: Float = 0f, val b: Float = 0f) {

    val isIdentity: Boolean get() = abs(r) < EPS && abs(g) < EPS && abs(b) < EPS

    /** The level the whole wheel is at: what the slider beside the pad shows. */
    val master: Float get() = (r + g + b) / 3f

    /** This wheel at a different level, keeping its colour. */
    fun withMaster(level: Float): Wheel {
        val (x, y) = pad()
        return of(x, y, level)
    }

    /**
     * Where the dot sits in the disc, -1 to 1 each way: the exact inverse of
     * [of]. The level is taken out first, because the three axes sum to nothing
     * and the colour part is what is left.
     */
    fun pad(): Pair<Float, Float> {
        val m = master
        val cr = r - m
        val cg = g - m
        val cb = b - m
        val x = (cr * COS_R + cg * COS_G + cb * COS_B) * 2f / 3f
        val y = (cr * SIN_R + cg * SIN_G + cb * SIN_B) * 2f / 3f
        return x to y
    }

    /** How far out the dot is, 0 at neutral and 1 at the rim. */
    val radius: Float get() = pad().let { hypot(it.first.toDouble(), it.second.toDouble()).toFloat() }

    companion object {
        val NONE = Wheel()
        private const val EPS = 1e-4f

        /**
         * The largest a component can be, either way.
         *
         * A component is `master + the tint`: the Level slider runs to ±1 and
         * the dot's own contribution is a unit vector's projection, so [of]
         * produces values to ±2 and the shader uses every one of them. Named
         * here because a reader elsewhere has to clamp to the same number -
         * ProjectAutosave's decode clamped to 1 and made a strong wheel come
         * back weaker on every reopen, while the draft on disk held the right
         * value all along.
         */
        const val COMPONENT_REACH = 2f

        // Red up, green at seven o'clock, blue at five: the wheel every
        // grading tool draws, so a dot dragged towards orange warms the picture.
        private val COS_R = cos(Math.toRadians(90.0)).toFloat()
        private val SIN_R = sin(Math.toRadians(90.0)).toFloat()
        private val COS_G = cos(Math.toRadians(210.0)).toFloat()
        private val SIN_G = sin(Math.toRadians(210.0)).toFloat()
        private val COS_B = cos(Math.toRadians(330.0)).toFloat()
        private val SIN_B = sin(Math.toRadians(330.0)).toFloat()

        /**
         * The wheel for a dot at ([x], [y]) in the disc with the level slider at
         * [master].
         *
         * The three axes are 120 degrees apart, so whatever the dot does the
         * three offsets sum to the level and nothing else: the pad changes the
         * colour, the slider changes the brightness, and neither touches the
         * other. Outside the rim the dot is pulled back to it rather than
         * clamped per axis, which would bend the hue.
         */
        fun of(x: Float, y: Float, master: Float): Wheel {
            val r = hypot(x.toDouble(), y.toDouble()).toFloat()
            val scale = if (r > 1f) 1f / r else 1f
            val px = x * scale
            val py = y * scale
            return Wheel(
                r = master + px * COS_R + py * SIN_R,
                g = master + px * COS_G + py * SIN_G,
                b = master + px * COS_B + py * SIN_B
            )
        }
    }
}

/**
 * The three wheels together.
 *
 * [lift] moves the shadows without touching white, [gamma] bends the midtones,
 * [gain] scales towards white. Applied in that order, which is the order a
 * primary correction is made in and the order the shader applies them.
 */
data class ColorWheels(
    val lift: Wheel = Wheel.NONE,
    val gamma: Wheel = Wheel.NONE,
    val gain: Wheel = Wheel.NONE
) {
    val isIdentity: Boolean get() = lift.isIdentity && gamma.isIdentity && gain.isIdentity

    /** The lift as the shader takes it: an offset per channel. */
    fun liftRgb(): FloatArray = floatArrayOf(lift.r * LIFT_REACH, lift.g * LIFT_REACH, lift.b * LIFT_REACH)

    /** The gamma as the shader takes it: the exponent's *denominator*, never zero. */
    fun gammaRgb(): FloatArray = floatArrayOf(gammaOf(gamma.r), gammaOf(gamma.g), gammaOf(gamma.b))

    /** The gain as the shader takes it: a multiplier per channel. */
    fun gainRgb(): FloatArray = floatArrayOf(1f + gain.r * GAIN_REACH, 1f + gain.g * GAIN_REACH, 1f + gain.b * GAIN_REACH)

    /**
     * One channel through all three, in the shader's order. The shader does
     * exactly this, and so does [Grade.applyTo] - one description, three places
     * that read it.
     */
    fun applyChannel(value: Float, lift: Float, gamma: Float, gain: Float): Float {
        // Lift: black is raised to the lift and white stays where it is, so
        // the shadows move and the highlights do not.
        var c = value + lift * (1f - value)
        // Gamma on the lifted value. Negatives would come back as NaN from pow,
        // so the floor is where the shader's own floor is.
        c = Math.pow(c.coerceAtLeast(0f).toDouble(), (1.0 / gamma)).toFloat()
        return c * gain
    }

    companion object {
        val NONE = ColorWheels()

        /** Lift at full: a third of the way from black to white. */
        const val LIFT_REACH = 0.33f

        /** Gamma at full: 1.8, which is as far as a midtone bend reads before it posterises. */
        const val GAMMA_REACH = 0.8f

        /** Gain at full: 1.8 - the same reach the other way as gamma. */
        const val GAIN_REACH = 0.8f

        /** The gamma a wheel value of [value] means; never zero, so the shader never divides by it. */
        fun gammaOf(value: Float): Float = (1f + value * GAMMA_REACH).coerceAtLeast(0.05f)
    }
}
