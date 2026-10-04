import com.squish.app.editor.AnnotationShape
import com.squish.app.editor.ShapeGeometry
import com.squish.app.editor.ShapePoint
import com.squish.app.editor.ShapeRole
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.system.exitProcess

// Shapes and arrows: the points a rectangle, a circle, a star and an arrow are
// drawn from, executed. One description is painted by the preview and by the
// file, so what holds here holds in both - which is the whole reason the
// geometry is a pure object and not two Canvas calls.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun ShapePoint.inside(w: Float, h: Float) =
    x >= -0.01f && x <= w + 0.01f && y >= -0.01f && y <= h + 0.01f

/** The area a closed run encloses, by the shoelace formula - sign and all. */
private fun area(points: List<ShapePoint>): Float {
    var sum = 0f
    points.indices.forEach { i ->
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a.x * b.y - b.x * a.y
    }
    return sum / 2f
}

fun main() {
    val boxes = listOf(
        100f to 100f, 320f to 180f, 180f to 320f, 1920f to 60f, 7f to 7f, 1080f to 1080f
    )

    // --- Every shape, in every box: inside it, and made of real runs. --------
    AnnotationShape.offered.forEach { shape ->
        boxes.forEach { (w, h) ->
            val polys = ShapeGeometry.polys(shape, w, h)
            check(polys.isNotEmpty(), "$shape at ${w}x$h drew nothing")
            polys.forEach { poly ->
                check(poly.points.size >= 2, "$shape at ${w}x$h has a run of ${poly.points.size} points")
                poly.points.forEach { p ->
                    check(p.inside(w, h), "$shape at ${w}x$h put a point at (${p.x}, ${p.y}), outside its box")
                    check(p.x.isFinite() && p.y.isFinite(), "$shape at ${w}x$h made a point that is not a number")
                }
                if (poly.closed) {
                    check(abs(area(poly.points)) > 0f, "$shape at ${w}x$h closed a run with no area")
                    check(
                        poly.points.first() != poly.points.last(),
                        "$shape at ${w}x$h repeated its first point in a closed run - the close does that"
                    )
                }
            }
        }
    }

    // --- None draws nothing. -------------------------------------------------
    check(ShapeGeometry.polys(AnnotationShape.None, 100f, 100f).isEmpty(), "None drew something")

    // --- A shape fills the box it is given. ----------------------------------
    AnnotationShape.offered.forEach { shape ->
        val polys = ShapeGeometry.polys(shape, 200f, 100f)
        val xs = polys.flatMap { it.points }.map { it.x }
        val ys = polys.flatMap { it.points }.map { it.y }
        check(xs.min() <= 0.5f && xs.max() >= 199.5f, "$shape does not reach across its box: ${xs.min()}..${xs.max()}")
        // A line and an arrow are drawn along the middle, so only the shapes
        // with an inside are expected to reach top and bottom.
        if (shape.hasInside) {
            check(ys.min() <= 0.5f && ys.max() >= 99.5f, "$shape does not fill its box vertically: ${ys.min()}..${ys.max()}")
        }
    }

    // --- The rectangle is the box. -------------------------------------------
    run {
        val r = ShapeGeometry.polys(AnnotationShape.Rectangle, 60f, 40f).single()
        check(r.closed && r.points.size == 4, "a rectangle is not four closed corners")
        check(abs(abs(area(r.points)) - 2400f) < 0.01f, "a 60x40 rectangle encloses ${abs(area(r.points))}")
    }

    // --- The ellipse is an ellipse. -------------------------------------------
    run {
        val e = ShapeGeometry.polys(AnnotationShape.Ellipse, 200f, 100f).single()
        check(e.closed && e.points.size == ShapeGeometry.ELLIPSE_STEPS, "an ellipse has ${e.points.size} points")
        // Every point on the ellipse: (x/a)^2 + (y/b)^2 = 1 about its centre.
        e.points.forEach { p ->
            val u = (p.x - 100f) / 100f
            val v = (p.y - 50f) / 50f
            check(abs(u * u + v * v - 1f) < 1e-3f, "an ellipse point is off its curve by ${abs(u * u + v * v - 1f)}")
        }
        // Close to pi*a*b: 72 points understate it by less than a fifth of a percent.
        val want = (Math.PI * 100f * 50f).toFloat()
        check(abs(abs(area(e.points)) - want) / want < 0.005f, "an ellipse's area is ${abs(area(e.points))}, want $want")
    }

    // --- The star has five spikes, and points up. ----------------------------
    run {
        val s = ShapeGeometry.polys(AnnotationShape.Star, 100f, 100f).single()
        check(s.points.size == 10, "a star has ${s.points.size} points")
        // Stretched to its own edges, so the radii are no longer one circle -
        // what holds is that every spike is further out than both valleys
        // beside it, and that the first spike is at the top, in the middle.
        val radii = s.points.map { hypot((it.x - 50f).toDouble(), (it.y - 50f).toDouble()).toFloat() }
        radii.indices.filter { it % 2 == 0 }.forEach { i ->
            val before = radii[(i + 9) % 10]
            val after = radii[(i + 1) % 10]
            check(radii[i] > before && radii[i] > after, "spike $i is no further out than its valleys: $radii")
        }
        check(abs(s.points.first().x - 50f) < 0.01f && s.points.first().y < 1f, "the first spike is not at the top")
        check(s.points.count { it.y < 1f } == 1, "more than one point is on the top edge")
    }

    // --- The arrow: a shaft and a solid head, pointing right. ----------------
    run {
        val a = ShapeGeometry.polys(AnnotationShape.Arrow, 260f, 100f)
        check(a.size == 2, "an arrow is ${a.size} runs")
        val shaft = a.first { it.role == ShapeRole.Body }
        val head = a.first { it.role == ShapeRole.Head }
        check(!shaft.closed && shaft.points.size == 2, "an arrow's shaft is not a two-point line")
        check(shaft.points.all { abs(it.y - 50f) < 0.01f }, "an arrow's shaft is not level")
        check(head.closed && head.points.size == 3, "an arrow's head is not a closed triangle")
        check(head.points.any { abs(it.x - 260f) < 0.01f && abs(it.y - 50f) < 0.01f }, "the tip is not at the right-hand middle")
        // The shaft stops inside the head, so a thick line cannot poke out of the point.
        val shaftEnd = shaft.points.maxOf { it.x }
        val headBack = head.points.minOf { it.x }
        check(shaftEnd > headBack && shaftEnd < 260f, "the shaft ends at $shaftEnd; the head runs $headBack..260")
    }

    // --- The double arrow has two heads, facing opposite ways. ---------------
    run {
        val d = ShapeGeometry.polys(AnnotationShape.DoubleArrow, 300f, 80f)
        val heads = d.filter { it.role == ShapeRole.Head }
        check(heads.size == 2, "a double arrow has ${heads.size} heads")
        check(heads.any { h -> h.points.any { abs(it.x - 300f) < 0.01f } }, "no head points right")
        check(heads.any { h -> h.points.any { abs(it.x) < 0.01f } }, "no head points left")
        val shaft = d.first { it.role == ShapeRole.Body }
        check(shaft.points.minOf { it.x } > 0f, "the shaft runs past the left head's back")
    }

    // --- A very wide arrow keeps a sane head and a sane line. ----------------
    run {
        // The failure this is here for: taken as a share of the width, a shaft
        // on a 1920x60 arrow was thicker than the arrow was tall.
        val thick = ShapeGeometry.thicknessPx(1920f, 60f, ShapeGeometry.DEFAULT_THICKNESS)
        check(thick <= 60f * ShapeGeometry.MAX_THICKNESS + 0.01f, "a wide arrow's line is $thick on a 60-tall box")
        val head = ShapeGeometry.polys(AnnotationShape.Arrow, 1920f, 60f).first { it.role == ShapeRole.Head }
        val length = 1920f - head.points.minOf { it.x }
        check(length <= 60f + 0.01f, "a wide arrow's head is $length long on a 60-tall box")
    }

    // --- Thickness and padding behave. ---------------------------------------
    run {
        listOf(-1f, 0f, 0.001f, 0.5f, 10f).forEach { share ->
            val t = ShapeGeometry.thicknessPx(100f, 100f, share)
            check(
                t >= 100f * ShapeGeometry.MIN_THICKNESS - 1e-3f && t <= 100f * ShapeGeometry.MAX_THICKNESS + 1e-3f,
                "a share of $share gave a thickness of $t"
            )
            check(ShapeGeometry.padPx(100f, 100f, share) > t / 2f, "the padding does not cover half the line at $share")
        }
        check(
            ShapeGeometry.thicknessPx(400f, 100f, 0.2f) == ShapeGeometry.thicknessPx(100f, 400f, 0.2f),
            "the line's thickness changed when the box was turned on its side"
        )
    }

    // --- A box of nothing does not divide by it. -----------------------------
    AnnotationShape.offered.forEach { shape ->
        val polys = ShapeGeometry.polys(shape, 0f, 0f)
        check(polys.flatMap { it.points }.all { it.x.isFinite() && it.y.isFinite() }, "$shape at 0x0 made a point that is not a number")
    }

    // --- Which shapes have an inside. ----------------------------------------
    check(!AnnotationShape.Line.hasInside && !AnnotationShape.Arrow.hasInside && !AnnotationShape.DoubleArrow.hasInside,
        "a line or an arrow was given an inside to fill")
    check(AnnotationShape.offered.filter { it.hasInside }.size == 5, "the shapes with an inside are not the five closed ones")
    check(AnnotationShape.offered.none { it == AnnotationShape.None }, "None is on offer")
    check(AnnotationShape.named("Star") == AnnotationShape.Star, "a shape could not be read back by name")
    check(AnnotationShape.named("nonsense") == AnnotationShape.None, "a name that is not a shape was not refused")
    check(AnnotationShape.named(null) == AnnotationShape.None, "no name at all was not refused")
    check(AnnotationShape.offered.all { it.defaultAspect in ShapeGeometry.MIN_ASPECT..ShapeGeometry.MAX_ASPECT },
        "a shape lands at a width its own slider could not reach")

    println("shapes: rectangles, circles, stars and arrows, drawn from points")
    if (problems.isEmpty()) println("PASS - every shape fills its box, and an arrow's head is where its point is")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
