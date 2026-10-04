package com.squish.app.editor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Shapes and arrows on the picture: the thing every reviewer of a product video
 * reaches for and the one annotation all three competitors have.
 *
 * Nothing here touches Android. A shape is described once, as points in the box
 * it occupies, and CaptionRenderer paints those points - which is the same
 * arrangement the curve and the blend ended up with, and for the same reason:
 * a shape drawn one way for the preview and another for the file is two things
 * that can disagree. Because the preview already paints captions with
 * CaptionRenderer, one description here is one drawing everywhere.
 *
 * tools/jvm/ShapeChecks.kt executes it.
 */

/** A point in the box a shape occupies, in that box's own pixels. */
data class ShapePoint(val x: Float, val y: Float)

/** What a run of points is for when it is drawn. */
enum class ShapeRole {
    /** The shape itself: filled when the shape is solid, outlined when it is not. */
    Body,

    /** An arrow's head: always solid, in the shape's own colour, whatever the body is. */
    Head
}

/**
 * One run of points. [closed] joins the last point back to the first.
 *
 * An open run has no inside, so a Solid line is still a line: the thickness
 * slider is what gives it body.
 */
data class ShapePoly(val points: List<ShapePoint>, val closed: Boolean, val role: ShapeRole = ShapeRole.Body)

/**
 * The shapes on offer. [defaultAspect] is the width over the height a new one
 * lands at - an arrow wants to be long, a star wants to be square.
 */
enum class AnnotationShape(val label: String, val defaultAspect: Float) {
    /** Not a shape: an ordinary line of words or a sticker. */
    None("None", 1f),

    Rectangle("Rectangle", 1.6f),
    Ellipse("Ellipse", 1.4f),
    Triangle("Triangle", 1.15f),
    Diamond("Diamond", 1f),
    Star("Star", 1f),
    Line("Line", 2.6f),
    Arrow("Arrow", 2.6f),
    DoubleArrow("Double arrow", 2.8f);

    val isShape: Boolean get() = this != None

    /** Whether the shape has an inside for a fill to go in: a line and an arrow do not. */
    val hasInside: Boolean
        get() = when (this) {
            None, Line, Arrow, DoubleArrow -> false
            else -> true
        }

    companion object {
        /** The shapes a picker offers, in the order it offers them. */
        val offered: List<AnnotationShape> get() = entries.filter { it.isShape }

        fun named(name: String?): AnnotationShape =
            entries.firstOrNull { it.name == name } ?: None
    }
}

/**
 * How a shape is laid out and how thick its line is.
 *
 * Sizes are shares rather than pixels so a shape placed on a phone-sized
 * preview lands in the same place and at the same weight in a 4K export - the
 * rule the caption renderer already works to.
 */
object ShapeGeometry {

    /** How many points an ellipse is drawn with. Far more than the eye can see at any size a phone shows. */
    const val ELLIPSE_STEPS = 72

    /** A star's inner radius as a share of its outer: the five-point star everyone draws. */
    const val STAR_INNER = 0.382f

    /** The longest an arrow head may be, as a share of the shape's width. */
    const val HEAD_SHARE = 0.34f

    /** A line's thickness as a share of the shape's short edge, when nothing has been set. */
    const val DEFAULT_THICKNESS = 0.12f

    const val MIN_THICKNESS = 0.02f
    const val MAX_THICKNESS = 0.4f

    /** The narrowest and widest a shape may be made against its height. */
    const val MIN_ASPECT = 0.2f
    const val MAX_ASPECT = 6f

    /** What a new shape lands at: a third of the frame's short edge tall. */
    const val DEFAULT_SIZE_SP = 110

    /**
     * [shape] laid out in a box [width] by [height] pixels, with its origin at
     * the box's top left.
     *
     * The points stay inside the box; the thickness of the line drawn through
     * them is the caller's business, and the renderer pads the bitmap for it.
     */
    fun polys(shape: AnnotationShape, width: Float, height: Float): List<ShapePoly> {
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        return when (shape) {
            AnnotationShape.None -> emptyList()

            AnnotationShape.Rectangle -> listOf(
                ShapePoly(listOf(p(0f, 0f), p(w, 0f), p(w, h), p(0f, h)), closed = true)
            )

            AnnotationShape.Ellipse -> listOf(
                ShapePoly(
                    (0 until ELLIPSE_STEPS).map { i ->
                        val a = 2.0 * PI * i / ELLIPSE_STEPS
                        p(w / 2f + (w / 2f) * cos(a).toFloat(), h / 2f + (h / 2f) * sin(a).toFloat())
                    },
                    closed = true
                )
            )

            AnnotationShape.Triangle -> listOf(
                ShapePoly(listOf(p(w / 2f, 0f), p(w, h), p(0f, h)), closed = true)
            )

            AnnotationShape.Diamond -> listOf(
                ShapePoly(listOf(p(w / 2f, 0f), p(w, h / 2f), p(w / 2f, h), p(0f, h / 2f)), closed = true)
            )

            AnnotationShape.Star -> listOf(
                ShapePoly(
                    // Point up: the first spike at twelve o'clock, then
                    // alternating out and in every thirty-six degrees. Stretched
                    // afterwards to its own edges - a five-point star on a
                    // circle leaves a gap at the sides and the bottom, so drawn
                    // raw it sat small and high inside the box a finger had
                    // sized, and its own box outlined empty room.
                    stretched(
                        (0 until 10).map { i ->
                            val a = -PI / 2.0 + PI * i / 5.0
                            val r = if (i % 2 == 0) 1f else STAR_INNER
                            p(cos(a).toFloat() * r, sin(a).toFloat() * r)
                        },
                        w,
                        h
                    ),
                    closed = true
                )
            )

            AnnotationShape.Line -> listOf(
                ShapePoly(listOf(p(0f, h / 2f), p(w, h / 2f)), closed = false)
            )

            AnnotationShape.Arrow -> {
                val head = headLength(w, h)
                listOf(
                    // The shaft stops a little inside the head so a thick line
                    // does not poke out of the point.
                    ShapePoly(listOf(p(0f, h / 2f), p(w - head * 0.6f, h / 2f)), closed = false),
                    headAt(w, h, head, pointingRight = true)
                )
            }

            AnnotationShape.DoubleArrow -> {
                val head = headLength(w, h)
                listOf(
                    ShapePoly(listOf(p(head * 0.6f, h / 2f), p(w - head * 0.6f, h / 2f)), closed = false),
                    headAt(w, h, head, pointingRight = true),
                    headAt(w, h, head, pointingRight = false)
                )
            }
        }
    }

    /**
     * The thickness of the line a shape is drawn with, in pixels, for a box
     * [width] by [height] and a [share] of its short edge.
     *
     * Measured on the short edge so a long thin arrow keeps a sane line: taken
     * on the width, a wide arrow's shaft grew until it swallowed its own head.
     */
    fun thicknessPx(width: Float, height: Float, share: Float): Float =
        (min(width, height).coerceAtLeast(1f) * share.coerceIn(MIN_THICKNESS, MAX_THICKNESS))

    /**
     * How far past the points the drawing reaches: half the line, plus a
     * whisker for the join on a sharp corner. The renderer pads the bitmap by
     * this so a star's spikes are not shaved off.
     */
    fun padPx(width: Float, height: Float, share: Float): Float = thicknessPx(width, height, share) * 0.9f + 1f

    /** [points] moved and scaled so their own edges are the box's: 0..[w] by 0..[h]. */
    private fun stretched(points: List<ShapePoint>, w: Float, h: Float): List<ShapePoint> {
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val spanX = (points.maxOf { it.x } - minX).takeIf { it > 1e-6f } ?: 1f
        val spanY = (points.maxOf { it.y } - minY).takeIf { it > 1e-6f } ?: 1f
        return points.map { p((it.x - minX) / spanX * w, (it.y - minY) / spanY * h) }
    }

    private fun headLength(w: Float, h: Float): Float = min(w * HEAD_SHARE, h)

    private fun headAt(w: Float, h: Float, head: Float, pointingRight: Boolean): ShapePoly {
        val half = min(h / 2f, head * 0.78f)
        val tipX = if (pointingRight) w else 0f
        val backX = if (pointingRight) w - head else head
        return ShapePoly(
            listOf(p(backX, h / 2f - half), p(tipX, h / 2f), p(backX, h / 2f + half)),
            closed = true,
            role = ShapeRole.Head
        )
    }

    private fun p(x: Float, y: Float) = ShapePoint(x, y)
}
