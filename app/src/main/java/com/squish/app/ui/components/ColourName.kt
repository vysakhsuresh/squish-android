package com.squish.app.ui.components

/**
 * A colour said out loud.
 *
 * Every colour swatch in the app was a bare circle whose only content was its
 * own fill - no name, no selected state - so with a screen reader on, the Style
 * tab offered nine identical nameless targets and there was no non-visual route
 * to setting a colour anywhere: text, outline, shadow, bubble, shape, the
 * canvas background or a cut-out's fill. The eyedropper beside them has always
 * carried "Pick a colour from the picture", which is how you can tell this was
 * a gap and not the house style.
 *
 * Named from the colour itself rather than from a table beside each palette, so
 * a swatch, the eyedropper's answer and the picker's own readout all say the
 * same thing, and a palette gains names the moment it gains a colour.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/ColourNameChecks.kt).
 */
object ColourName {

    /**
     * [argb] as someone would say it: "white", "warm yellow", "deep blue",
     * "light grey". The alpha is ignored - a swatch is opaque.
     */
    fun of(argb: Int): String {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val value = max
        val saturation = if (max <= 0f) 0f else (max - min) / max

        // Grey, black and white have no hue worth saying.
        if (saturation < 0.10f) return when {
            value < 0.08f -> "black"
            value < 0.30f -> "dark grey"
            value < 0.65f -> "grey"
            value < 0.92f -> "light grey"
            else -> "white"
        }

        val hue = hueOf(r, g, b, max, min)
        val name = HUES.first { hue < it.first }.second
        // The qualifier says what the hue cannot: how light and how strong.
        val qualifier = when {
            value < 0.35f -> "dark "
            saturation < 0.35f -> "pale "
            value > 0.85f && saturation > 0.75f -> "bright "
            saturation > 0.85f && value < 0.70f -> "deep "
            else -> ""
        }
        return qualifier + name
    }

    /** Degrees, 0 at red and going round through green and blue. */
    private fun hueOf(r: Float, g: Float, b: Float, max: Float, min: Float): Float {
        val span = max - min
        if (span <= 0f) return 0f
        val h = when (max) {
            r -> ((g - b) / span) % 6f
            g -> (b - r) / span + 2f
            else -> (r - g) / span + 4f
        } * 60f
        return if (h < 0f) h + 360f else h
    }

    /** Upper bound of each hue sector, in degrees, and what it is called. */
    private val HUES = listOf(
        14f to "red",
        40f to "orange",
        70f to "yellow",
        95f to "lime",
        150f to "green",
        175f to "teal",
        195f to "cyan",
        225f to "sky blue",
        260f to "blue",
        290f to "violet",
        330f to "magenta",
        360.1f to "pink"
    )
}
