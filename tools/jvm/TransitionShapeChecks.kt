import com.squish.app.media.TransitionShapes
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * EDGE_PLAN F3 / INSHOT_PARITY P3: the shape transitions' reveal masks.
 *
 * Per shape, over a grid of pixels and at two frame shapes: nothing of the new
 * shot at the start, all of it at the end; no pixel ever goes back as the
 * transition runs; the soft edge never wider than its setting; the heart and
 * the star the same either side of the middle; the iris round on a wide frame;
 * and each shape opening the way its name says - the iris from the middle, the
 * close from the edge, the clock from twelve.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val kinds = 1..TransitionShapes.LAST
    for (aspect in listOf(16f / 9f, 9f / 16f)) for (kind in kinds) {
        var widest = 0f
        for (xi in 0..32) for (yi in 0..32) {
            val x = xi / 32f; val y = yi / 32f
            check(TransitionShapes.coverage(kind, 0f, x, y, aspect) == 0f, "shape $kind shows the new shot at the start at ($x, $y)")
            check(TransitionShapes.coverage(kind, 1f, x, y, aspect) == 1f, "shape $kind leaves the old shot at the end at ($x, $y)")
            var last = 0f
            var from = -1f; var to = -1f
            for (pi in 0..400) {
                val p = pi / 400f
                val c = TransitionShapes.coverage(kind, p, x, y, aspect)
                check(c + 1e-6f >= last, "shape $kind goes back at ($x, $y) at $p: $last then $c")
                if (c > 0f && from < 0f) from = p
                if (c >= 1f && to < 0f) to = p
                last = c
            }
            widest = maxOf(widest, to - from)
        }
        // The ramp at one pixel lasts SOFT of the field, which is SOFT / (1 + SOFT) of the run.
        check(widest <= TransitionShapes.SOFT / (1f + TransitionShapes.SOFT) + 0.006f, "shape $kind's edge takes $widest of the run, wider than its setting")
    }
    // The heart and the star mirror left to right; so do the iris and the diamond.
    for (kind in listOf(TransitionShapes.HEART, TransitionShapes.STAR, TransitionShapes.IRIS, TransitionShapes.DIAMOND)) {
        for (xi in 0..40) for (yi in 0..40) {
            val x = xi / 40f; val y = yi / 40f
            val a = TransitionShapes.field(kind, x, y, 16f / 9f)
            val b = TransitionShapes.field(kind, 1f - x, y, 16f / 9f)
            check(abs(a - b) < 1e-3f, "shape $kind is not the same either side at ($x, $y): $a against $b")
        }
    }
    // The star's points: one straight up, and it is a star - further out up than between points.
    check(TransitionShapes.field(TransitionShapes.STAR, 0.5f, 0.3f, 1f) < TransitionShapes.field(TransitionShapes.STAR, 0.5f + 0.2f * 0.5878f, 0.5f - 0.2f * 0.809f, 1f), "the star has no point at the top")
    // The heart's lobes are up and its point down: the point is reached later than a lobe at the same distance.
    check(TransitionShapes.field(TransitionShapes.HEART, 0.5f, 0.85f, 1f) > TransitionShapes.field(TransitionShapes.HEART, 0.5f, 0.15f, 1f) ||
        TransitionShapes.field(TransitionShapes.HEART, 0.5f, 0.15f, 1f) > TransitionShapes.field(TransitionShapes.HEART, 0.3f, 0.25f, 1f), "the heart is upside down")
    // The dip: straight up, its edge is nearer the middle than towards a lobe at the same distance.
    check(TransitionShapes.field(TransitionShapes.HEART, 0.5f, 0.3f, 1f) > TransitionShapes.field(TransitionShapes.HEART, 0.6f, 0.3268f, 1f), "the heart has no dip between its lobes")
    // The iris is round on a wide frame: the same distance across and up opens together.
    val aspect = 16f / 9f
    val across = TransitionShapes.field(TransitionShapes.IRIS, 0.5f + 0.3f / aspect, 0.5f, aspect)
    val up = TransitionShapes.field(TransitionShapes.IRIS, 0.5f, 0.2f, aspect)
    check(abs(across - up) < 1e-3f, "the iris is an ellipse: $across across against $up up")
    // Each opens the way it is named.
    check(TransitionShapes.field(TransitionShapes.IRIS, 0.5f, 0.5f, aspect) < TransitionShapes.field(TransitionShapes.IRIS, 0.05f, 0.05f, aspect), "the iris does not open from the middle")
    check(TransitionShapes.field(TransitionShapes.IRIS_CLOSE, 0.5f, 0.5f, aspect) > TransitionShapes.field(TransitionShapes.IRIS_CLOSE, 0.05f, 0.05f, aspect), "the iris close does not close to the middle")
    check(TransitionShapes.field(TransitionShapes.CLOCK, 0.6f, 0.2f, aspect) < TransitionShapes.field(TransitionShapes.CLOCK, 0.8f, 0.5f, aspect) &&
        TransitionShapes.field(TransitionShapes.CLOCK, 0.8f, 0.5f, aspect) < TransitionShapes.field(TransitionShapes.CLOCK, 0.5f, 0.8f, aspect), "the clock does not run clockwise from twelve")
    check(TransitionShapes.field(TransitionShapes.DOORS, 0.5f, 0.5f, aspect) < TransitionShapes.field(TransitionShapes.DOORS, 0.05f, 0.5f, aspect), "the doors do not open from the middle")
    // The pixel dissolve is spread over the run, not bunched.
    val pixels = (0 until 400).map { TransitionShapes.field(TransitionShapes.PIXELS, (it % 20) / 20f + 0.01f, (it / 20) / 20f + 0.01f, 1f) }
    check(pixels.count { it < 0.5f } in 120..280, "the pixel dissolve is bunched: ${pixels.count { it < 0.5f }} of 400 in its first half")

    if (problems.isEmpty()) println("TransitionShapeChecks: all checks passed (${TransitionShapes.LAST} shapes)")
    else { problems.take(30).forEach { println("FAIL: $it") }; println("(${problems.size} in all)"); exitProcess(1) }
}
