import com.squish.app.editor.CanvasBackground
import com.squish.app.editor.CanvasFill
import com.squish.app.editor.CanvasPaint
import com.squish.app.editor.CanvasPattern
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.system.exitProcess

/*
 * EDGE_PLAN F5 / INSHOT_PARITY P5: gradient and pattern canvases. A gradient's
 * two colours land on the canvas's far corners at every angle, and it never
 * runs backwards along its own direction; every pattern meets itself at every
 * edge for every frame shape (a column past the right edge is the left edge's,
 * a row past the bottom the top's), checks have an even count, and both
 * colours are in every pattern.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun close(a: Int, b: Int, by: Int = 3) =
    (0..2).all { abs(((a shr (it * 8)) and 0xFF) - ((b shr (it * 8)) and 0xFF)) <= by }

fun main() {
    val one = 0xFF102030.toInt()
    val two = 0xFFF0C040.toInt()
    // Near the size the backdrop is written at (CanvasBackdrop), and one odd shape.
    val shapes = listOf(360 to 640, 640 to 360, 480 to 480, 505 to 385, 256 to 800)
    for ((w, h) in shapes) {
        for (deg in 0 until 360 step 15) {
            val b = CanvasBackground(fill = CanvasFill.Gradient, colorArgb = one, color2Argb = two, angleDegrees = deg.toFloat())
            val a = Math.toRadians(deg.toDouble())
            val dx = cos(a); val dy = sin(a)
            // The two corners furthest each way along the angle.
            val corners = listOf(0.0 to 0.0, w.toDouble() to 0.0, 0.0 to h.toDouble(), w.toDouble() to h.toDouble())
            val proj = corners.map { (x, y) -> (x - w / 2.0) * dx + (y - h / 2.0) * dy }
            val lo = corners[proj.indexOf(proj.min())]; val hi = corners[proj.indexOf(proj.max())]
            check(close(CanvasPaint.pixelAt(b, lo.first, lo.second, w, h), one), "a ${w}x$h gradient at $deg degrees does not start on its first colour")
            check(close(CanvasPaint.pixelAt(b, hi.first, hi.second, w, h), two), "a ${w}x$h gradient at $deg degrees does not end on its second colour")
            // Along its own direction it only goes one way.
            var last = -1
            for (k in 0..20) {
                val t = k / 20.0 - 0.5
                val r = abs(w / 2.0 * dx) + abs(h / 2.0 * dy)
                val c = CanvasPaint.pixelAt(b, w / 2.0 + dx * t * 2 * r, h / 2.0 + dy * t * 2 * r, w, h)
                val red = (c shr 16) and 0xFF
                check(red >= last, "a ${w}x$h gradient at $deg degrees runs backwards at $t")
                last = red
            }
        }
        for (p in CanvasPattern.entries) for (n in listOf(3, 7, 10, 23, 40)) {
            val b = CanvasBackground(fill = CanvasFill.Pattern, colorArgb = one, color2Argb = two, pattern = p, patternCells = n)
            val cells = CanvasPaint.cells(b, w, h)
            if (p == CanvasPattern.Checks) check(cells.first % 2 == 0 && cells.second % 2 == 0, "checks on ${w}x$h with $n cells are ${cells.first}x${cells.second}, not even")
            for (y in 0 until h) {
                val left = CanvasPaint.pixelAt(b, 0.5, y + 0.5, w, h)
                val past = CanvasPaint.pixelAt(b, w + 0.5, y + 0.5, w, h)
                check(close(left, past, 1), "$p with $n cells on ${w}x$h has a seam at its right edge, row $y")
            }
            for (x in 0 until w) {
                val top = CanvasPaint.pixelAt(b, x + 0.5, 0.5, w, h)
                val past = CanvasPaint.pixelAt(b, x + 0.5, h + 0.5, w, h)
                check(close(top, past, 1), "$p with $n cells on ${w}x$h has a seam at its bottom edge, column $x")
            }
            val px = CanvasPaint.pixels(b, w, h)
            check(px.any { close(it, one, 8) } && px.any { close(it, two, 8) }, "$p with $n cells on ${w}x$h is missing one of its colours")
        }
    }
    if (problems.isEmpty()) println("CanvasPaintChecks: all checks passed")
    else { problems.take(30).forEach { println("FAIL: $it") }; println("(${problems.size} in all)"); exitProcess(1) }
}
