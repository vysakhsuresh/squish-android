package com.squish.app.timeline

import com.squish.app.media.video.MotionTrack

/**
 * The shapes a mask can take. Each is a signed distance function in the shader,
 * which is what lets one uniform feather them all identically.
 */
enum class MaskShape(val label: String) {
    Rectangle("Rectangle"),
    Ellipse("Ellipse"),
    /** A half-plane: everything on one side of a line. The classic reveal. */
    Linear("Linear"),
    /** A band between two parallel lines - a letterbox slot you can turn. */
    Mirror("Mirror")
}

/**
 * What the shape does to what is inside it.
 *
 * [Cutout] is a compositing tool; the other two are privacy tools, and they behave
 * differently on purpose - obscuring leaves the frame intact and destroys only what
 * is inside the shape, because hiding a face must not also punch a hole in the
 * picture.
 */
enum class MaskMode(val label: String) {
    Cutout("Cut out"),
    Pixelate("Pixelate"),
    Blur("Blur")
}

/**
 * Restricts a clip to a shape.
 *
 * Sizes and the center are fractions of the **frame**, so width 1.0 spans the
 * whole picture and the center runs -1 to 1 from edge to edge. Rotation and the
 * corner radius are applied in pixel-isotropic space, so a turned rectangle stays
 * a rectangle on a 16:9 frame rather than shearing into a rhombus.
 *
 * [feather] is the width of the soft edge, in the same isotropic units, and
 * [inverted] punches the shape out instead of keeping it.
 */
data class Mask(
    val shape: MaskShape = MaskShape.Ellipse,
    val centerXFraction: Float = 0f,
    val centerYFraction: Float = 0f,
    val widthFraction: Float = 0.6f,
    val heightFraction: Float = 0.6f,
    val rotationDegrees: Float = 0f,
    val feather: Float = 0.04f,
    val cornerRadius: Float = 0f,
    val inverted: Boolean = false,
    val mode: MaskMode = MaskMode.Cutout,
    /** How coarse the pixelation, or how wide the blur. */
    val strength: Float = 0.5f,

    /**
     * Pins the shape to something moving, in **source** time. A face does not hold
     * still, so a privacy mask that cannot follow one is a mask you have to keyframe
     * by hand for every frame of the shot.
     */
    val track: MotionTrack? = null
) {
    /** Never zero: it is the width of a smoothstep band in the shader. */
    val safeFeather: Float get() = feather.coerceAtLeast(0.001f)

    /**
     * The corner radius cannot exceed half the shorter side, or the rounding
     * inverts and the rectangle turns inside out.
     */
    val safeCornerRadius: Float
        get() = cornerRadius.coerceIn(0f, minOf(widthFraction, heightFraction) / 2f)

    val shapeIndex: Float get() = shape.ordinal.toFloat()
    val modeIndex: Float get() = mode.ordinal.toFloat()

    /**
     * Texture-coordinate units, so both read as a fraction of the frame.
     *
     * The ranges are set by what it takes to actually hide a face, measured rather
     * than guessed: the first draft topped out at a 3% blur radius, through which a
     * face was still perfectly recognisable. A privacy blur has to approach the size
     * of the feature it is destroying, which is why this reaches 14%.
     */
    val pixelSize: Float get() = 0.015f + 0.075f * strength.coerceIn(0f, 1f)
    val blurRadius: Float get() = 0.015f + 0.125f * strength.coerceIn(0f, 1f)

    /**
     * The shape's center at a moment of the source, following its track if it has
     * one. Track fractions run 0 to 1 across the frame; the shader's center runs
     * -1 to 1 from the middle.
     */
    fun centerAt(sourceMs: Long): Pair<Float, Float> {
        val sample = track?.sampleAt(sourceMs)
            ?: return centerXFraction to centerYFraction
        return (sample.xFraction - 0.5f) * 2f to (sample.yFraction - 0.5f) * 2f
    }
}
