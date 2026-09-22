package com.squish.app.timeline

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
 * Restricts a clip to a shape.
 *
 * Sizes and the centre are fractions of the **frame**, so width 1.0 spans the
 * whole picture and the centre runs -1 to 1 from edge to edge. Rotation and the
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
    val inverted: Boolean = false
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
}
