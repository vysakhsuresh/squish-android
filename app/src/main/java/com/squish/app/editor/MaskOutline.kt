package com.squish.app.editor

import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The edge of a mask, as the shader cuts it, for drawing over the picture.
 *
 * The shapes are signed distance fields in squish_mask_es2.glsl, and the edge
 * is where the distance is zero. Rather than keep a second drawing of each
 * shape here - which would stop matching the moment a shape was retuned - the
 * same distance is worked out in Kotlin ([distance]) and its zero found by
 * bisection along rays from the shape's centre. Every shape here is
 * star-shaped about its centre, so each ray crosses the edge once. A linear
 * mask is a line, a mirror mask two, both drawn well past the frame.
 *
 * Points are in the frame's own pixels, top-down, from a frame [w] x [h]; the
 * shader works in texture space with y up, and the flip is done at the end.
 * tools/jvm/MaskOutlineChecks.kt executes the distance against the shapes'
 * known extents.
 */
object MaskOutline {

    /** How many points round a closed shape. */
    private const val STEPS = 96

    /**
     * The mask's edge on a [w] x [h] frame, centred at [centre] (the shader's
     * -1..1 fractions, y up): one closed polyline for a box shape, one or two
     * open ones for a line.
     */
    fun outline(mask: Mask, centre: Pair<Float, Float>, w: Float, h: Float): List<List<Pair<Float, Float>>> {
        if (w <= 0f || h <= 0f) return emptyList()
        val aspect = w / h
        val rx = mask.widthFraction * aspect
        val ry = mask.heightFraction
        val far = 4f * max(aspect, 1f)
        return when (mask.shape) {
            MaskShape.Linear -> listOf(listOf(-far to 0f, far to 0f).map { (x, y) -> toFrame(x, y, mask, centre, aspect, w, h) })
            MaskShape.Mirror -> listOf(
                listOf(-far to ry, far to ry).map { (x, y) -> toFrame(x, y, mask, centre, aspect, w, h) },
                listOf(-far to -ry, far to -ry).map { (x, y) -> toFrame(x, y, mask, centre, aspect, w, h) }
            )
            else -> listOf(
                (0 until STEPS).map { i ->
                    val angle = i.toFloat() / STEPS * 2.0 * Math.PI
                    val dx = cos(angle).toFloat()
                    val dy = sin(angle).toFloat()
                    val t = edgeAlong(mask, dx, dy, rx, ry, far)
                    toFrame(dx * t, dy * t, mask, centre, aspect, w, h)
                }
            )
        }
    }

    /** How far along ([dx], [dy]) from the centre the edge lies, by bisection on the distance's sign. */
    private fun edgeAlong(mask: Mask, dx: Float, dy: Float, rx: Float, ry: Float, far: Float): Float {
        var inside = 0f
        var outside = far
        if (distance(mask.shape, dx * far, dy * far, rx, ry, mask.safeCornerRadius) < 0f) return far
        repeat(24) {
            val mid = (inside + outside) / 2f
            if (distance(mask.shape, dx * mid, dy * mid, rx, ry, mask.safeCornerRadius) < 0f) inside = mid else outside = mid
        }
        return (inside + outside) / 2f
    }

    /**
     * The shader's signed distance for a box shape, in its isotropic units:
     * negative inside. [rx] and [ry] are the half sizes with x already
     * stretched by the aspect, as the shader's `r` is.
     */
    fun distance(shape: MaskShape, qx: Float, qy: Float, rx: Float, ry: Float, cornerRadius: Float): Float = when (shape) {
        MaskShape.Rectangle -> {
            val dx = abs(qx) - rx + cornerRadius
            val dy = abs(qy) - ry + cornerRadius
            sqrt(max(dx, 0f) * max(dx, 0f) + max(dy, 0f) * max(dy, 0f)) + min(max(dx, dy), 0f) - cornerRadius
        }
        MaskShape.Ellipse -> {
            val nx = qx / max(rx, 0.0001f)
            val ny = qy / max(ry, 0.0001f)
            (sqrt(nx * nx + ny * ny) - 1f) * min(rx, ry)
        }
        MaskShape.Linear -> qy
        MaskShape.Mirror -> abs(qy) - ry
        MaskShape.Heart -> {
            val rrx = max(rx, 0.0001f)
            val rry = max(ry, 0.0001f)
            val hx = abs(qx) / rrx * 0.6f
            val hy = (qy + rry) / (2f * rry) * 1.1f
            val d = if (hy + hx > 1f) {
                val ex = hx - 0.25f
                val ey = hy - 0.75f
                sqrt(ex * ex + ey * ey) - 0.35355f
            } else {
                val ax = hx
                val ay = hy - 1f
                val m = 0.5f * max(hx + hy, 0f)
                val bx = hx - m
                val by = hy - m
                sqrt(min(ax * ax + ay * ay, bx * bx + by * by)) * Math.signum(hx - hy)
            }
            d * min(rrx / 0.6f, rry / 0.55f)
        }
        MaskShape.Star -> {
            val rrx = max(rx, 0.0001f)
            val rry = max(ry, 0.0001f)
            var px = abs(qx / rrx)
            var py = qy / rry
            val k1x = 0.809016994f
            val k1y = -0.587785252f
            val k2x = -k1x
            val k2y = k1y
            var dot = max(k1x * px + k1y * py, 0f)
            px -= 2f * dot * k1x
            py -= 2f * dot * k1y
            dot = max(k2x * px + k2y * py, 0f)
            px -= 2f * dot * k2x
            py -= 2f * dot * k2y
            px = abs(px)
            py -= 1f
            val bax = 0.45f * -k1y
            val bay = 0.45f * k1x - 1f
            val hh = ((px * bax + py * bay) / (bax * bax + bay * bay)).coerceIn(0f, 1f)
            val ex = px - bax * hh
            val ey = py - bay * hh
            sqrt(ex * ex + ey * ey) * Math.signum(py * bax - px * bay) * min(rrx, rry)
        }
    }

    /** A point in the shape's own (isotropic, turned, centred, y up) space to the frame's pixels, top-down. */
    private fun toFrame(qx: Float, qy: Float, mask: Mask, centre: Pair<Float, Float>, aspect: Float, w: Float, h: Float): Pair<Float, Float> {
        // The shader turned q by -rotation to get here; back the other way.
        val radians = Math.toRadians(mask.rotationDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val ux = qx * c - qy * s
        val uy = qx * s + qy * c
        val px = ux / aspect + centre.first
        val py = uy + centre.second
        return ((px + 1f) / 2f * w) to ((1f - (py + 1f) / 2f) * h)
    }

    /**
     * A finger moved by ([dx], [dy]) pixels on a [w] x [h] frame: how far the
     * shape's centre moves, in the shader's -1..1 fractions, y up.
     */
    fun dragged(dx: Float, dy: Float, w: Float, h: Float): Pair<Float, Float> =
        (2f * dx / w.coerceAtLeast(1f)) to (-2f * dy / h.coerceAtLeast(1f))
}

