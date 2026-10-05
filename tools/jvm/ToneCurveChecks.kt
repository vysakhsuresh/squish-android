import com.squish.app.media.effects.Curve
import com.squish.app.media.effects.CurvePoint
import com.squish.app.media.effects.ToneCurve
import kotlin.math.abs
import kotlin.system.exitProcess

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, slack: Float = 1e-3f) = abs(a - b) <= slack

private fun curve(vararg p: Pair<Float, Float>) = Curve(p.map { CurvePoint(it.first, it.second) })

fun main() {
    // --- A straight curve is nothing at all. --------------------------------
    val straight = Curve()
    check(straight.isIdentity, "the default curve is not identity")
    check(ToneCurve.NONE.isIdentity, "an untouched ToneCurve is not identity")
    for (i in 0..255) {
        val x = i / 255f
        check(near(straight.valueAt(x), x), "the straight curve moved $x to ${straight.valueAt(x)}")
    }
    // A point placed on the diagonal is still nothing - the editor lets you add
    // one before you drag it, and the sheet must not light up for that.
    check(curve(0f to 0f, 0.5f to 0.5f, 1f to 1f).isIdentity, "a point on the diagonal counted as an edit")

    // --- The ends are the ends. ---------------------------------------------
    val lifted = curve(0f to 0.2f, 1f to 1f)
    check(near(lifted.valueAt(0f), 0.2f), "a lifted black point did not lift")
    check(near(lifted.valueAt(1f), 1f), "white moved when only black was lifted")
    // Outside the points it holds rather than running on.
    val inner = curve(0.25f to 0.25f, 0.75f to 0.75f)
    check(near(inner.valueAt(0f), 0.25f) && near(inner.valueAt(1f), 0.75f), "the curve ran past its end points")

    // --- Monotone: this is why it is not a Catmull-Rom. ---------------------
    // The classic overshoot case - a flat run then a jump. A plain spline dips
    // below the flat part before the rise, which in a picture is a band that
    // gets darker as the footage gets brighter.
    val step = curve(0f to 0f, 0.4f to 0.1f, 0.6f to 0.1f, 1f to 1f)
    var last = -1f
    for (i in 0..255) {
        val v = step.valueAt(i / 255f)
        check(v >= last - 1e-5f, "the curve turned back on itself at ${i / 255f}: $v after $last")
        check(v in 0f..1f, "the curve left 0..1 at ${i / 255f}: $v")
        last = v
    }
    // The flat stretch really is flat.
    check(near(step.valueAt(0.45f), 0.1f, 2e-3f) && near(step.valueAt(0.55f), 0.1f, 2e-3f), "a flat run between two equal points was not flat")

    // Every curve a person can draw stays in range and never falls.
    val shapes = listOf(
        curve(0f to 0f, 0.25f to 0.1f, 0.75f to 0.9f, 1f to 1f),   // an S
        curve(0f to 0.1f, 0.5f to 0.4f, 1f to 0.9f),               // a faded film look
        curve(0f to 0f, 0.1f to 0.5f, 1f to 1f),                   // a hard lift
        curve(0f to 0f, 0.9f to 0.5f, 1f to 1f),                   // a hard crush
        curve(0f to 1f, 1f to 0f)                                  // inverted, which is allowed
    )
    for ((n, c) in shapes.withIndex()) {
        var prev = c.valueAt(0f)
        val rising = c.valueAt(1f) >= c.valueAt(0f)
        for (i in 1..255) {
            val v = c.valueAt(i / 255f)
            check(v in 0f..1f, "shape $n left 0..1 at ${i / 255f}: $v")
            check(if (rising) v >= prev - 1e-5f else v <= prev + 1e-5f, "shape $n is not monotone at ${i / 255f}")
            prev = v
        }
    }

    // --- Points out of order, doubled, or missing. --------------------------
    check(near(curve(1f to 1f, 0f to 0f).valueAt(0.5f), 0.5f), "points given out of order were not sorted")

    // `ordered` is what everything that reads a curve reads, the editor
    // included. It held an index into the raw list while calling it "an index
    // into the sorted points" and clamped a dragged point between its
    // neighbours there - so an out-of-order list (a draft from another build, a
    // file edited by hand) gave a floor above its ceiling, and coerceIn on an
    // inverted range throws rather than returning anything.
    run {
        val jumbled = curve(0.8f to 0.9f, 0.1f to 0.2f, 0.5f to 0.3f, 0.3f to 0.7f)
        val inOrder = jumbled.ordered
        check(inOrder.size == 4, "ordering a jumbled curve lost a point: ${inOrder.size}")
        check(
            inOrder.map { it.x } == inOrder.map { it.x }.sorted(),
            "ordered is not sorted: ${inOrder.map { it.x }}"
        )
        // Which is the property the editor's clamp needs: every point's
        // neighbours are on the right sides of it.
        for (i in 1 until inOrder.lastIndex) {
            check(
                inOrder[i - 1].x <= inOrder[i + 1].x,
                "ordered gave point $i a floor above its ceiling: ${inOrder[i - 1].x} to ${inOrder[i + 1].x}"
            )
        }
        // The y values travel with their x, not with their place in the list.
        check(inOrder[0] == CurvePoint(0.1f, 0.2f), "ordering moved a point's value: ${inOrder[0]}")
        check(inOrder[3] == CurvePoint(0.8f, 0.9f), "ordering moved a point's value: ${inOrder[3]}")
        // Two points on one x are one point, so an index into this list is an
        // index into what is drawn.
        val stacked = curve(0f to 0f, 0.5f to 0.3f, 0.5f to 0.8f, 1f to 1f)
        check(stacked.ordered.size == 3, "two points on one x came back as ${stacked.ordered.size} points")
        check(stacked.ordered.map { it.x } == listOf(0f, 0.5f, 1f), "the kept points are ${stacked.ordered}")
        // And an out-of-order curve evaluates exactly as the sorted one does.
        val sortedSame = Curve(jumbled.points.sortedBy { it.x })
        for (i in 0..255) {
            val x = i / 255f
            check(
                near(jumbled.valueAt(x), sortedSame.valueAt(x), 1e-6f),
                "a jumbled curve and a sorted one disagree at $x"
            )
        }
    }
    val doubled = curve(0f to 0f, 0.5f to 0.3f, 0.5f to 0.8f, 1f to 1f)
    for (i in 0..255) check(doubled.valueAt(i / 255f) in 0f..1f, "two points on one x produced ${doubled.valueAt(i / 255f)}")
    check(near(Curve(emptyList()).valueAt(0.4f), 0.4f), "an empty curve is not a straight one")
    check(near(Curve(listOf(CurvePoint(0.5f, 0.2f))).valueAt(0.9f), 0.2f), "a one-point curve is not flat")

    // --- The table the shader samples, and the CPU reading of it. -----------
    val tone = ToneCurve(
        master = curve(0f to 0f, 0.5f to 0.6f, 1f to 1f),
        red = curve(0f to 0.05f, 1f to 1f)
    )
    val lut = tone.lut()
    check(lut.size == ToneCurve.LUT_SIZE * 3, "the table is the wrong size")
    // Per channel, then master: red's lift goes through the master's lift too.
    check(near(ToneCurve.sample(lut, 0, 0f), tone.master.valueAt(0.05f), 2e-3f), "red's black point did not go through the master curve")
    check(near(ToneCurve.sample(lut, 1, 0.5f), tone.master.valueAt(0.5f), 2e-3f), "green took something other than the master curve")
    check(near(ToneCurve.sample(lut, 1, 0.5f), 0.6f, 2e-3f), "the master curve's middle is not where it was put")
    // Sampling the table lands on the curve, which is what lets the swatch, the
    // preview and the file agree: all three read this table, nothing re-evaluates.
    for (i in 0..255) {
        val x = i / 255f
        check(near(ToneCurve.sample(lut, 2, x), tone.master.valueAt(tone.blue.valueAt(x)), 2e-3f),
            "the table and the curve disagree at $x on blue")
    }
    // An identity curve's table is the ramp, so a clip with a curve that does
    // nothing is not quietly changed by passing through the lookup.
    val flat = ToneCurve.NONE.lut()
    for (i in 0 until ToneCurve.LUT_SIZE) {
        val x = i.toFloat() / (ToneCurve.LUT_SIZE - 1)
        check(near(flat[i * 3], x) && near(flat[i * 3 + 1], x) && near(flat[i * 3 + 2], x), "an identity table is not a ramp at $i")
    }
    // The table's ends are the curve's ends: the shader clamps its texture reads,
    // so black and white have to be right in the table itself.
    check(near(ToneCurve.sample(lut, 0, -1f), lut[0]), "reading below black left the table")
    check(near(ToneCurve.sample(lut, 0, 2f), lut[(ToneCurve.LUT_SIZE - 1) * 3]), "reading above white left the table")

    // --- Each channel is its own. -------------------------------------------
    val onlyBlue = ToneCurve(blue = curve(0f to 0f, 0.5f to 0.8f, 1f to 1f))
    val blueLut = onlyBlue.lut()
    for (i in 0 until ToneCurve.LUT_SIZE) {
        val x = i.toFloat() / (ToneCurve.LUT_SIZE - 1)
        check(near(blueLut[i * 3], x) && near(blueLut[i * 3 + 1], x), "a blue curve moved red or green at $i")
    }
    check(ToneCurve.sample(blueLut, 2, 0.5f) > 0.7f, "the blue curve did not lift blue")

    // --- A curve that turns stays inside the two points it is between. -------
    //
    // The file promises "between two points it never leaves the range they
    // set", and that is only true with Fritsch-Carlson's sign step: where the
    // secants either side of a point run opposite ways, the averaged slope
    // points against one of them, and the circle constraint that follows
    // scales a tangent without changing its sign. So the overshoot survived,
    // and in the Curves tool a point placed *below* the one before it made the
    // picture brighter than either of them before coming down - a bright rim
    // just past a highlight, which is exactly the artefact a monotone fit is
    // chosen to avoid.
    val turning = listOf(
        curve(0f to 0f, 0.5f to 0.9f, 1f to 0.85f),   // a highlight rolled off
        curve(0f to 0f, 0.5f to 0.8f, 1f to 0.2f),    // up then hard down
        curve(0f to 0.9f, 0.5f to 0.1f, 1f to 0.3f),  // down then up
        curve(0f to 0.2f, 0.3f to 0.9f, 0.6f to 0.1f, 1f to 0.8f), // a zigzag
        curve(0f to 0f, 0.2f to 0.6f, 0.4f to 0.55f, 0.6f to 0.95f, 1f to 1f)
    )
    for ((n, c) in turning.withIndex()) {
        val p = c.points.sortedBy { it.x }
        for (i in 0 until p.size - 1) {
            val lo = minOf(p[i].y, p[i + 1].y)
            val hi = maxOf(p[i].y, p[i + 1].y)
            for (k in 0..64) {
                val x = p[i].x + (p[i + 1].x - p[i].x) * k / 64f
                val v = c.valueAt(x)
                check(
                    v >= lo - 1e-4f && v <= hi + 1e-4f,
                    "turning curve $n overshot between (${p[i].x}, ${p[i].y}) and (${p[i + 1].x}, ${p[i + 1].y}): $v at $x"
                )
            }
        }
        // And it still goes through its own points, which a flattened tangent
        // does not change: only the slope between them moved.
        p.forEach { check(near(c.valueAt(it.x), it.y, 2e-3f), "turning curve $n missed its own point at ${it.x}") }
    }

    println("tone curve: ${ToneCurve.LUT_SIZE} entries, 4 curves, ${shapes.size} drawn shapes, ${turning.size} that turn")
    if (problems.isEmpty()) println("PASS - monotone, in range, and the table is the curve")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
