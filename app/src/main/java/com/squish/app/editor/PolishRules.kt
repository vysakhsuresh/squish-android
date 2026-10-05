package com.squish.app.editor

import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transform
import kotlin.math.abs

/**
 * The small decisions behind the sheets' chips and notices, kept pure so they
 * can be executed on the JVM (tools/jvm/PolishRulesChecks.kt).
 *
 * A preset is not stored by name - a curve or a pair of keys is what the clip
 * carries - so which chip is "on" has to be read back off the clip. That is
 * what these do: recognise a shape that a tap laid down, and stop recognising
 * it the moment a point is dragged or a key is added, so a chip never claims
 * a curve it did not make.
 */
object PolishRules {

    /**
     * How far off a ramp point may be, as a fraction of the clip, and still be
     * the preset's point: a frame or two of rounding, not a dragged point.
     */
    private const val POINT_TOLERANCE = 0.02

    /** A rate within this of the preset's is the preset's. */
    private const val SPEED_TOLERANCE = 0.011f

    /**
     * The ramp shape [ramp] is, or null when it is no preset - points added,
     * dragged, or the clip cut so its curve is a piece of one.
     *
     * Normal is one rate, and that rate 1x: the chip lays [SpeedRamp.Normal],
     * which is 1x, so it may only light where a tap on it would change
     * nothing. It used to light for any flat rate, so a shot at 2x read as
     * "Normal" and a tap on the lit chip dropped it to 1x, rippling the track.
     * A flat 2x lights no chip. The other shapes are matched on their points
     * as fractions of [spanMs], which is how [SpeedRamp.preset] lays them, so a
     * preset stays recognised whatever the clip's length.
     */
    fun activeRampShape(ramp: SpeedRamp, spanMs: Long): RampShape? {
        if (!ramp.isRamped) return if (abs(ramp.flatSpeed - 1f) <= SPEED_TOLERANCE) RampShape.Normal else null
        val span = spanMs.coerceAtLeast(1L).toDouble()
        val points = ramp.ordered
        return RampShape.entries.firstOrNull { shape ->
            if (shape == RampShape.Normal) return@firstOrNull false
            val laid = SpeedRamp.preset(shape, spanMs).ordered
            laid.size == points.size && laid.zip(points).all { (a, b) ->
                abs(a.atMs - b.atMs) / span <= POINT_TOLERANCE && abs(a.speed - b.speed) <= SPEED_TOLERANCE
            }
        }
    }

    /**
     * The line under the curve chips: the chosen shape's own hint, or what a
     * tap does - with the rate, on a flat shot that is not at 1x, since no
     * chip is lit to say what it is on.
     */
    fun rampHint(active: RampShape?, ramp: SpeedRamp? = null): String = when {
        active != null -> active.hint
        ramp != null && !ramp.isRamped -> "One rate, ${rateLabel(ramp.flatSpeed)}. Tap a curve to lay it across the whole shot, or Normal for 1x"
        else -> "Tap a curve to lay it across the whole shot"
    }

    /**
     * A number with its trailing zeros gone: 2.00 reads "2", 0.50 reads "0.5".
     *
     * Formatted against `Locale.ROOT`, so the separator is always a point.
     * `"%.2f".format(v)` uses the phone's *own* locale: set to German or French
     * it gives "2,00", and the trim then takes the zeros and cannot take the
     * comma - so every speed chip read "2,x", every effect knob "1,/s", and the
     * Settings transition list "0,5 s". Seven labels were built that way.
     */
    fun number(value: Float, decimals: Int = 2): String =
        String.format(java.util.Locale.ROOT, "%.${decimals}f", value).trimEnd('0').trimEnd('.')

    /** A rate as the Speed sheet prints it everywhere: "2x", "0.5x", "0.25x", "1.5x". */
    fun rateLabel(speed: Float): String =
        if (speed < 1f) "${number(speed)}x"
        else if (abs(speed - speed.toInt()) < 0.005f) "${speed.toInt()}x"
        else "${number(speed, 1)}x"

    /**
     * How far a key may sit from the clip's start or end, as a fraction of the
     * clip, and still be the preset's endpoint. A preset's second key is laid
     * at the clip's end; a trim of a frame or two should not lose the chip.
     */
    private const val KEY_TOLERANCE = 0.02

    /** A placement value within this of the preset's endpoint is the endpoint's. */
    private const val TRANSFORM_TOLERANCE = 0.005f

    /**
     * The move [keyframes] are, or null when they are no preset: none, one,
     * three or more keys, an easing other than the smooth one a preset lays,
     * or endpoints that have been moved.
     */
    fun activeMotionPreset(keyframes: List<Keyframe>, durationMs: Long): MotionPreset? {
        if (keyframes.size != 2) return null
        val (first, last) = keyframes.sortedBy { it.atMs }
        val length = durationMs.coerceAtLeast(1L).toDouble()
        if (abs(first.atMs) / length > KEY_TOLERANCE) return null
        if (abs(last.atMs - durationMs) / length > KEY_TOLERANCE) return null
        // The last key's easing describes no segment, so only the first's counts.
        if (first.easing != KeyframeEasing.Smooth) return null
        return MotionPreset.entries.firstOrNull { preset ->
            val (from, to) = preset.endpoints()
            near(first.transform, from) && near(last.transform, to)
        }
    }

    /** The line under the move chips: the chosen move's own hint, or what a tap does. */
    fun motionHint(active: MotionPreset?): String =
        active?.hint ?: "A preset lays two keys across the whole clip. Adjust them below, or add your own."

    private fun near(a: Transform, b: Transform): Boolean =
        abs(a.scale - b.scale) <= TRANSFORM_TOLERANCE &&
            abs(a.offsetXFraction - b.offsetXFraction) <= TRANSFORM_TOLERANCE &&
            abs(a.offsetYFraction - b.offsetYFraction) <= TRANSFORM_TOLERANCE &&
            abs(a.rotationDegrees - b.rotationDegrees) <= TRANSFORM_TOLERANCE * 100

    /**
     * The proxy notice while a copy is being built. The percentage is the
     * encoder's own (Transformer.getProgress), shown once it has said anything;
     * before that the spinner alone says work is under way. With more than one
     * heavy file, how many are done.
     */
    fun proxyBuildingLine(percent: Int?, ready: Int, total: Int): String {
        val pct = percent?.takeIf { it in 1..100 }?.let { " · $it%" } ?: ""
        val count = if (total > 1) " ($ready of $total ready)" else ""
        return "Building a light preview copy$pct$count — editing stays responsive while it works"
    }
}
