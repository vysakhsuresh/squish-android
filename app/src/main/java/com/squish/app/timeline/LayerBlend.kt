package com.squish.app.timeline

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How a layer's colour meets the picture under it.
 *
 * The separable blend functions of the W3C compositing spec, which is what
 * Skia implements behind Compose's `BlendMode` and what every grading tool
 * means by these names. Written here once so the preview (which hands Compose
 * the matching mode) and the file (whose shader mirrors these lines) are
 * saying the same thing, and so the formulas can be executed.
 *
 * Only offered on a photo or a sticker. A *video* overlay cannot be blended in
 * the file at all on Media3 1.11.1 - its layers only meet inside a compositor
 * with no blend knob and no way to replace it - and a blend that worked on
 * screen and not in the export would be worse than none. See
 * docs/COMPETITORS.md, G1.
 */
enum class LayerBlend(val label: String) {
    Normal("Normal"),
    Multiply("Multiply"),
    Screen("Screen"),
    Overlay("Overlay"),
    Darken("Darken"),
    Lighten("Lighten"),
    HardLight("Hard light"),
    SoftLight("Soft light"),
    Difference("Difference"),
    Add("Add");

    /**
     * One channel of the layer ([over]) against one channel of what is under it
     * ([base]), both 0..1. The spec's own definitions, in its own order.
     */
    fun blend(base: Float, over: Float): Float {
        val b = base.coerceIn(0f, 1f)
        val s = over.coerceIn(0f, 1f)
        return when (this) {
            Normal -> s
            Multiply -> b * s
            Screen -> b + s - b * s
            // Overlay is Hard light with the two swapped - the spec says so, and
            // saying it here rather than writing the branches out twice is how
            // they cannot drift apart.
            Overlay -> HardLight.blend(s, b)
            Darken -> minOf(b, s)
            Lighten -> maxOf(b, s)
            HardLight -> if (s <= 0.5f) Multiply.blend(b, 2f * s) else Screen.blend(b, 2f * s - 1f)
            SoftLight -> {
                val d = if (b <= 0.25f) ((16f * b - 12f) * b + 4f) * b else sqrt(b)
                if (s <= 0.5f) b - (1f - 2f * s) * b * (1f - b)
                else b + (2f * s - 1f) * (d - b)
            }
            Difference -> abs(b - s)
            Add -> minOf(1f, b + s)
        }.coerceIn(0f, 1f)
    }

    /** Whether this leaves the picture under it exactly as it was. */
    val isPlain: Boolean get() = this == Normal
}
