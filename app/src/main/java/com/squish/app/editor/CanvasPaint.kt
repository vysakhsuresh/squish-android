package com.squish.app.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A gradient or a pattern canvas (EDGE_PLAN F5, INSHOT_PARITY P5) as pixels.
 *
 * The one drawing: CanvasBackdrop writes what this answers to the still the
 * preview shows and the export lays under the picture, so the two are the same
 * file. Free of Android, so tools/jvm/CanvasPaintChecks.kt runs it.
 *
 * A gradient's two ends land on the canvas's own far corners at any angle (the
 * colour is measured along the angle, over the canvas's whole reach that way),
 * so nothing of either colour is lost off the edge and nothing is flat. A
 * pattern has a whole number of cells each way - checks an even number - so it
 * meets itself at every edge for every frame shape, with cells only as far
 * from square as that takes.
 */
object CanvasPaint {

    /** [w] x [h] ARGB pixels of [b], row by row. */
    fun pixels(b: CanvasBackground, w: Int, h: Int): IntArray {
        val cells = cells(b, w, h)
        return IntArray(w * h) { i -> pixelAt(b, (i % w) + 0.5, (i / w) + 0.5, w, h, cells) }
    }

    /**
     * The colour at ([x], [y]) - a pixel's middle - on a [w] x [h] canvas; any
     * x and y, past the edge included, which is how the checks see that a
     * pattern meets itself there.
     */
    fun pixelAt(b: CanvasBackground, x: Double, y: Double, w: Int, h: Int, cells: Pair<Int, Int> = cells(b, w, h)): Int = when (b.fill) {
        CanvasFill.Gradient -> {
            val a = b.angleDegrees * PI / 180.0
            val dx = cos(a); val dy = sin(a)
            val reach = abs(w / 2.0 * dx) + abs(h / 2.0 * dy)
            val t = if (reach <= 0.0) 0.5 else ((x - w / 2.0) * dx + (y - h / 2.0) * dy) / reach * 0.5 + 0.5
            mix(b.colorArgb, b.color2Argb, t.toFloat())
        }
        CanvasFill.Pattern -> {
            val (cx, cy) = cells
            // Its coverage from four taps round the point, so edges are soft.
            var cover = 0f
            for (s in 0 until 4) {
                val px = x + (s % 2) * 0.5 - 0.25
                val py = y + (s / 2) * 0.5 - 0.25
                cover += ink(b.pattern, px * cx / w, py * cy / h, cx.toDouble() / w, cy.toDouble() / h)
            }
            mix(b.colorArgb, b.color2Argb, cover / 4f)
        }
        else -> b.colorArgb or (0xFF shl 24)
    }

    /** How many cells across and down: [CanvasBackground.patternCells] on the short edge, whole numbers each way, even for checks. */
    fun cells(b: CanvasBackground, w: Int, h: Int): Pair<Int, Int> {
        val n = b.patternCells.coerceIn(CanvasBackground.MIN_CELLS, CanvasBackground.MAX_CELLS)
        val short = minOf(w, h).coerceAtLeast(1)
        val size = short.toDouble() / n
        var cx = max(1, (w / size).roundToInt())
        var cy = max(1, (h / size).roundToInt())
        if (b.pattern == CanvasPattern.Checks) {
            if (cx % 2 == 1) cx += 1
            if (cy % 2 == 1) cy += 1
        }
        return cx to cy
    }

    /**
     * How much of the second colour at (u, v), in cells; [pu] and [pv] are a
     * pixel's size in cells, so an edge is soft over one pixel.
     */
    private fun ink(p: CanvasPattern, u: Double, v: Double, pu: Double, pv: Double): Float {
        val fu = u - floor(u); val fv = v - floor(v)
        val pix = max(pu, pv)
        return when (p) {
            CanvasPattern.Dots -> {
                val d = sqrt((fu - 0.5) * (fu - 0.5) + (fv - 0.5) * (fv - 0.5))
                edge(0.28 - d, pix)
            }
            // Two stripes a cell on the diagonal: whole across a cell each way, so they meet themselves.
            CanvasPattern.Stripes -> {
                val s = (fu + fv) % 1.0
                edge(0.25 - abs(s - 0.5), pix)
            }
            CanvasPattern.Grid -> {
                val line = minOf(minOf(fu, 1 - fu), minOf(fv, 1 - fv))
                // Never under two pixels wide, or a fine grid is a grey haze rather than lines.
                edge(max(0.06, 1.25 * pix) - line, pix)
            }
            CanvasPattern.Checks -> {
                val odd = ((floor(u) + floor(v)).toLong() and 1L) == 1L
                if (odd) 1f else 0f
            }
            // A wave a cell long and a band a third of a cell deep, a row a cell.
            CanvasPattern.Waves -> {
                val centre = 0.5 + 0.22 * sin(2 * PI * fu)
                edge(0.17 - abs(fv - centre), pix)
            }
        }
    }

    /** 1 inside (distance > 0), 0 outside, soft across one pixel. */
    private fun edge(distance: Double, pixel: Double): Float = ((distance / pixel) + 0.5).coerceIn(0.0, 1.0).toFloat()

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
