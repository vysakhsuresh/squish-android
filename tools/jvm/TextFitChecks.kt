import com.squish.app.ui.components.TextFit
import kotlin.math.abs
import kotlin.system.exitProcess

/*
 * FitText's loop, run to a fixed point.
 *
 * A line shrinks until it fits. What it never did was grow back: the scale was
 * reset only by a change of words or style, so a tile's name that had shrunk at
 * three across stayed small at two across, and a sheet's label that shrank
 * upright stayed small on its side. The simulation here is the layout loop -
 * lay out, see whether it overflowed, take the next scale, lay out again - so
 * the fixed point it settles on is the size the eye would see.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val MIN = 0.6f

/** The widest a line of [intrinsic] px at scale 1 can be and still fit [room]. */
private fun settle(intrinsic: Int, room: Int, from: Float = 1f, fittedFrom: Int = 0): Pair<Float, Int> {
    var scale = from
    var fittedAt = fittedFrom
    // The real loop runs one layout per frame and stops when nothing changes.
    // Sixty passes is far more than the eleven steps between 1 and the floor.
    repeat(60) {
        val overflowing = intrinsic * scale > room
        val next = TextFit.nextScale(scale, overflowing, room, fittedAt, MIN)
        fittedAt = room
        if (next == scale) return scale to fittedAt
        scale = next
    }
    problems += "the loop did not settle: $intrinsic px in $room px"
    return scale to fittedAt
}

fun main() {
    // --- It shrinks until it fits, and no further. ---------------------------
    run {
        val (scale, _) = settle(intrinsic = 200, room = 150)
        check(200 * scale <= 150, "a 200px line in 150px settled at $scale, still ${200 * scale}px")
        check(200 * (scale + TextFit.STEP) > 150, "it came down further than it had to: $scale")
    }
    // A line that already fits is left alone - no step, no recomposition.
    check(
        TextFit.nextScale(1f, overflowing = false, room = 300, fittedAt = 300, minScale = MIN) == 1f,
        "a line that fits was shrunk"
    )
    // Past the floor it is cut after all, rather than becoming unreadable.
    run {
        val (scale, _) = settle(intrinsic = 4_000, room = 100)
        check(abs(scale - MIN) < 1e-6f, "an impossible line went to $scale, past the floor $MIN")
    }

    // --- And it grows back when the box does. This is the fix. --------------
    //
    // Three across, then two across: the same words in a box half again as
    // wide. Before, the scale was only ever reset by a change of words, so the
    // name stayed at the size it reached in the narrow tile.
    run {
        val (narrow, fittedAt) = settle(intrinsic = 200, room = 150)
        check(narrow < 1f, "the fixture is wrong: 200px fitted 150px without shrinking")
        val (wide, _) = settle(intrinsic = 200, room = 260, from = narrow, fittedFrom = fittedAt)
        check(abs(wide - 1f) < 1e-6f, "the box grew to 260px and the line stayed at $wide")
        // And to a width where it still does not quite fit, it settles at the
        // largest step that does rather than at the narrow box's answer.
        val (middling, _) = settle(intrinsic = 200, room = 190, from = narrow, fittedFrom = fittedAt)
        check(middling > narrow, "the box grew from 150px to 190px and the line stayed at $narrow")
        check(200 * middling <= 190, "at 190px it settled at $middling, which is ${200 * middling}px")
    }

    // --- A box that narrows keeps shrinking; it does not read as a grow. ----
    run {
        val (wide, fittedAt) = settle(intrinsic = 200, room = 190)
        val (narrow, _) = settle(intrinsic = 200, room = 130, from = wide, fittedFrom = fittedAt)
        check(narrow < wide, "the box narrowed to 130px and the line stayed at $wide")
        check(200 * narrow <= 130, "at 130px it settled at $narrow, which is ${200 * narrow}px")
    }

    // --- A wobble is not a growth. ------------------------------------------
    //
    // Without the slack, a box that measures a pixel or two wider on the second
    // layout pass resets the scale, overflows, shrinks, and does it again on
    // the next pass: a flicker, and a layout that never settles.
    run {
        val (scale, fittedAt) = settle(intrinsic = 200, room = 150)
        for (wobble in 1..TextFit.GROWTH_SLACK) {
            check(
                TextFit.nextScale(scale, overflowing = false, room = 150 + wobble, fittedAt = fittedAt, minScale = MIN) == scale,
                "a wobble of $wobble px reset the scale from $scale"
            )
        }
        check(
            TextFit.nextScale(scale, overflowing = false, room = 150 + TextFit.GROWTH_SLACK + 1, fittedAt = fittedAt, minScale = MIN) == 1f,
            "a real growth past the slack did not reset the scale"
        )
    }
    // At full size there is nothing to grow back to, so a growth changes
    // nothing - the reset must not fire on every widening of a line that fits.
    check(
        TextFit.nextScale(1f, overflowing = false, room = 1_000, fittedAt = 100, minScale = MIN) == 1f,
        "a line already at full size was reset by the box growing"
    )
    // The first layout of all: fittedAt is 0, so every box is a growth. A line
    // at full size must not be disturbed, and one that overflows must shrink.
    check(
        TextFit.nextScale(1f, overflowing = true, room = 150, fittedAt = 0, minScale = MIN) < 1f,
        "the very first layout did not shrink an overflowing line"
    )

    // --- Every box, every line: it settles on the largest step that fits. ---
    for (intrinsic in listOf(60, 120, 200, 340, 900)) {
        for (room in listOf(40, 80, 150, 210, 400)) {
            val (scale, _) = settle(intrinsic, room)
            val fits = intrinsic * scale <= room
            check(
                fits || abs(scale - MIN) < 1e-6f,
                "${intrinsic}px in ${room}px settled at $scale, which neither fits nor is the floor"
            )
            if (fits) check(
                scale >= 1f - 1e-6f || intrinsic * (scale + TextFit.STEP) > room,
                "${intrinsic}px in ${room}px stopped at $scale with room for a bigger step"
            )
            // And arriving from any smaller scale lands on the same answer, so
            // the size a line ends up at does not depend on its history.
            val (again, _) = settle(intrinsic, room, from = MIN, fittedFrom = 1)
            check(
                abs(again - scale) < 1e-6f,
                "${intrinsic}px in ${room}px settled at $scale from full size and $again from the floor"
            )
        }
    }

    println("text fit: step ${TextFit.STEP}, slack ${TextFit.GROWTH_SLACK}px, 25 box-and-line pairs")
    if (problems.isEmpty()) println("PASS - a line shrinks until it fits and grows back when the box does")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
