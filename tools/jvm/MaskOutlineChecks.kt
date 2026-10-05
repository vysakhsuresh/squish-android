import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.TrackSample
import com.squish.app.editor.MaskOutline
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskShape
import kotlin.math.abs
import kotlin.system.exitProcess

// The mask's edge on the preview (B12), executed: the distance field agrees
// with the shapes' known extents, every outline point sits on the zero of
// the field, and the outline lands on the frame where the shader cuts.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
private fun near(a: Float, b: Float, slack: Float = 1e-3f) = abs(a - b) <= slack

fun main() {
    // --- The field: inside is negative, the known edges are zero. --------------
    for (shape in listOf(MaskShape.Rectangle, MaskShape.Ellipse, MaskShape.Heart, MaskShape.Star)) {
        check(MaskOutline.distance(shape, 0f, 0f, 1f, 0.5f, 0f) < 0f, "$shape: the centre is outside")
        check(MaskOutline.distance(shape, 3f, 3f, 1f, 0.5f, 0f) > 0f, "$shape: far away is inside")
    }
    check(near(MaskOutline.distance(MaskShape.Rectangle, 1f, 0f, 1f, 0.5f, 0f), 0f), "a rectangle's right edge is not at its half width")
    check(near(MaskOutline.distance(MaskShape.Ellipse, 0f, 0.5f, 1f, 0.5f, 0f), 0f), "an ellipse's top is not at its half height")
    check(near(MaskOutline.distance(MaskShape.Star, 0f, 0.5f, 1f, 0.5f, 0f), 0f, 1e-2f), "the star's top point is not at the box's top")
    check(near(MaskOutline.distance(MaskShape.Heart, 0f, -0.5f, 1f, 0.5f, 0f), 0f, 2e-2f), "the heart's tip is not at the box's bottom")
    check(MaskOutline.distance(MaskShape.Heart, 0f, 0.5f, 1f, 0.5f, 0f) > 0f, "the heart's cleft is filled")
    check(near(MaskOutline.distance(MaskShape.Linear, 0f, 0.3f, 1f, 1f, 0f), 0.3f), "a linear mask is not the distance above the line")
    check(near(MaskOutline.distance(MaskShape.Mirror, 0f, 0.7f, 1f, 0.5f, 0f), 0.2f), "a mirror band's edge is not its half height")
    val rounded = MaskOutline.distance(MaskShape.Rectangle, 1f, 0.5f, 1f, 0.5f, 0.2f)
    check(rounded > 0f, "a rounded rectangle still reaches its corner")

    // --- Every outline point is on the edge and inside the box. -------------------
    val w = 400f
    val h = 225f
    val aspect = w / h
    for (shape in listOf(MaskShape.Rectangle, MaskShape.Ellipse, MaskShape.Heart, MaskShape.Star)) {
        val mask = Mask(shape = shape, widthFraction = 0.5f, heightFraction = 0.6f, rotationDegrees = 0f, cornerRadius = 0.1f)
        val lines = MaskOutline.outline(mask, 0f to 0f, w, h)
        check(lines.size == 1 && lines[0].size >= 48, "$shape: not one closed outline")
        val rx = mask.widthFraction * aspect
        val ry = mask.heightFraction
        var worst = 0f
        lines[0].forEach { (x, y) ->
            // Back into the shader's units: frame pixels -> -1..1 -> isotropic.
            val px = x / w * 2f - 1f
            val py = 1f - y / h * 2f
            val qx = px * aspect
            val qy = py
            worst = maxOf(worst, abs(MaskOutline.distance(shape, qx, qy, rx, ry, mask.safeCornerRadius)))
            check(abs(qx) <= rx + 1e-2f && abs(qy) <= ry + 1e-2f, "$shape: an outline point is outside the box: ($qx,$qy)")
        }
        check(worst < 5e-3f, "$shape: an outline point is off the edge by $worst")
    }

    // --- On the frame: an ellipse half the width and height, centred, spans the middle. ---
    run {
        val mask = Mask(shape = MaskShape.Ellipse, widthFraction = 0.5f, heightFraction = 0.5f)
        val points = MaskOutline.outline(mask, 0f to 0f, 200f, 100f)[0]
        check(near(points.minOf { it.first }, 50f, 1f) && near(points.maxOf { it.first }, 150f, 1f), "the ellipse does not span 50..150 across")
        check(near(points.minOf { it.second }, 25f, 1f) && near(points.maxOf { it.second }, 75f, 1f), "the ellipse does not span 25..75 down")
        // Moved up (the shader's y is up) it sits higher on the frame.
        val up = MaskOutline.outline(mask, 0f to 0.5f, 200f, 100f)[0]
        check(up.maxOf { it.second } < points.maxOf { it.second }, "a centre above the middle drew lower")
        // Turned a quarter, the box's sides swap in isotropic units.
        val turned = MaskOutline.outline(mask.copy(widthFraction = 0.25f, rotationDegrees = 90f), 0f to 0f, 200f, 100f)[0]
        check(near(turned.maxOf { it.first } - turned.minOf { it.first }, 50f, 2f), "a quarter turn did not swap the sides: ${turned.maxOf { it.first } - turned.minOf { it.first }}")
    }

    // --- Lines. ---------------------------------------------------------------------
    run {
        val linear = MaskOutline.outline(Mask(shape = MaskShape.Linear), 0f to 0f, 200f, 100f)
        check(linear.size == 1 && linear[0].size == 2 && near(linear[0][0].second, 50f, 1f), "a linear mask is not one line through the middle")
        val mirror = MaskOutline.outline(Mask(shape = MaskShape.Mirror, heightFraction = 0.5f), 0f to 0f, 200f, 100f)
        check(mirror.size == 2 && near(mirror[0][0].second, 25f, 1f) && near(mirror[1][0].second, 75f, 1f), "a mirror mask is not two lines a band apart")
        check(MaskOutline.outline(Mask(), 0f to 0f, 0f, 100f).isEmpty(), "an unmeasured frame drew something")
    }

    // --- A finger on the frame moves the centre in the shader's units. -------------
    run {
        val (dx, dy) = MaskOutline.dragged(10f, 10f, 200f, 100f)
        check(near(dx, 0.1f) && near(dy, -0.2f), "a drag of 10px on 200x100 is not (0.1, -0.2): ($dx,$dy)")
    }

    // ---- A track and a drag move the shape the same way -------------------
    //
    // The tracker's y is a bitmap row over the frame's height, so 0 is the top;
    // the shader's is a GL texture coordinate, so +1 is the top, which is the
    // convention centerYFraction and MaskOutline.dragged are both in. The
    // conversion did not negate, so a pinned track sat mirrored about the
    // middle of the picture and walked up while its subject walked down.
    run {
        val near = { a: Float, b: Float -> kotlin.math.abs(a - b) < 1e-4f }
        val top = Mask(track = MotionTrack(listOf(TrackSample(0L, 0.5f, 0.1f, 0.2f))))
        val (_, y) = top.centerAt(0L)
        check(y > 0f, "a track in the top of the frame gives a centre of $y, and the shader's +y is the top")
        val bottom = Mask(track = MotionTrack(listOf(TrackSample(0L, 0.5f, 0.9f, 0.2f))))
        check(bottom.centerAt(0L).second < 0f, "a track in the bottom of the frame does not give a negative centre")
        check(near(Mask(track = MotionTrack(listOf(TrackSample(0L, 0.5f, 0.5f, 0.2f)))).centerAt(0L).second, 0f),
            "a track in the middle is not at 0")
        // And the same way as a finger: dragging down and tracking down agree.
        val draggedDown = MaskOutline.dragged(0f, 10f, 200f, 100f).second
        val trackedDown = Mask(track = MotionTrack(listOf(TrackSample(0L, 0.5f, 0.6f, 0.2f)))).centerAt(0L).second
        check(draggedDown < 0f && trackedDown < 0f, "a drag down gives $draggedDown and a track down $trackedDown")
        // x runs the same way in both, so it is not negated.
        check(Mask(track = MotionTrack(listOf(TrackSample(0L, 0.9f, 0.5f, 0.2f)))).centerAt(0L).first > 0f,
            "a track on the right of the frame is not on the right")
    }

    println("mask outline: ${MaskShape.entries.size} shapes on the shader's own field")
    if (problems.isEmpty()) println("PASS - the edge drawn is the edge cut, for every shape")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
