package com.squish.app.export

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * The done screen's tick as one stroke of a loaded brush: down into the dip,
 * then a long flick up and out, thick where the brush lands and presses, thin
 * where it lifts away. The owner asked for it to look painted rather than
 * stamped (28 Oct). Kept free of the framework so tools/jvm/BrushTickChecks.kt
 * runs it; ExportScreen's BrushTick fills what it answers.
 *
 * Everything is in a unit box, 0..1 both ways, y down.
 */
object BrushTickShape {

    /** The stroke's middle: a short, slightly curved drop, then a longer rising flick. */
    val spine: List<Pair<Float, Float>> = run {
        val out = ArrayList<Pair<Float, Float>>()
        // Down stroke: (0.20, 0.50) to the dip at (0.42, 0.72), bowing a little.
        for (i in 0..12) {
            val t = i / 12f
            val x = 0.20f + 0.22f * t
            val y = 0.50f + 0.22f * t + 0.03f * sin(PI * t).toFloat()
            out += x to y
        }
        // Up stroke: from the dip to (0.84, 0.26), its middle bowed down and right.
        for (i in 1..24) {
            val t = i / 24f
            val x = 0.42f + 0.42f * t + 0.02f * sin(PI * t).toFloat()
            val y = 0.72f - 0.46f * t + 0.05f * sin(PI * t).toFloat()
            out += x to y
        }
        out
    }

    /** How far along the spine each point is, 0 at the brush's landing, 1 at the lift. */
    val along: List<Float> = run {
        val lengths = ArrayList<Float>()
        var total = 0f
        lengths += 0f
        for (i in 1 until spine.size) {
            total += hypot(spine[i].first - spine[i - 1].first, spine[i].second - spine[i - 1].second)
            lengths += total
        }
        lengths.map { it / total }
    }

    /**
     * The stroke's width at [s] along it, as a share of the box: it lands
     * pressed down, swells into the dip where the brush turns, and runs out to
     * a point as it lifts.
     */
    fun widthAt(s: Float): Float {
        // Lighter where it lands and heaviest at the dip (a third of the way):
        // pressed hard over the whole short stroke, it read as a blob on the
        // phone (28 Oct). The rising arm holds its weight and then runs out.
        val press = 0.075f + 0.055f * (s / 0.3f).coerceIn(0f, 1f)
        val t = ((s - 0.35f) / 0.65f).coerceIn(0f, 1f)
        return press * (0.12f + 0.88f * (1f - t.pow(1.6f)))
    }

    /**
     * The outline of the stroke painted as far as [progress] (0..1), as a closed
     * polygon: down one side of the spine and back up the other. Empty before
     * the brush lands.
     */
    fun outline(progress: Float): List<Pair<Float, Float>> {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) return emptyList()
        val left = ArrayList<Pair<Float, Float>>()
        val right = ArrayList<Pair<Float, Float>>()
        for (i in spine.indices) {
            if (along[i] > p) break
            val (x, y) = spine[i]
            val a = spine[maxOf(0, i - 1)]
            val b = spine[minOf(spine.size - 1, i + 1)]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len = hypot(dx, dy).coerceAtLeast(1e-6f)
            val half = widthAt(along[i]) / 2f
            val nx = -dy / len * half
            val ny = dx / len * half
            left += (x + nx) to (y + ny)
            right += (x - nx) to (y - ny)
        }
        return left + right.asReversed()
    }

    /**
     * Thin dry streaks inside the stroke - the brush's bristles running out of
     * paint towards the lift - as [count] lines, each a list of points, offset
     * across the stroke. Their ends come short of the tip, as a dry brush's do.
     */
    fun bristles(progress: Float, count: Int = 3): List<List<Pair<Float, Float>>> {
        val p = progress.coerceIn(0f, 1f)
        return (0 until count).map { k ->
            val across = -0.3f + 0.6f * k / (count - 1).coerceAtLeast(1)
            val stop = minOf(p, 0.78f + 0.06f * k)
            spine.indices.filter { along[it] in 0.35f..stop }.map { i ->
                val (x, y) = spine[i]
                val a = spine[maxOf(0, i - 1)]
                val b = spine[minOf(spine.size - 1, i + 1)]
                val dx = b.first - a.first
                val dy = b.second - a.second
                val len = hypot(dx, dy).coerceAtLeast(1e-6f)
                val off = widthAt(along[i]) * across
                (x - dy / len * off) to (y + dx / len * off)
            }
        }
    }
}
