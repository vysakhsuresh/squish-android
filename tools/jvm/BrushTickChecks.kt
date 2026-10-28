import com.squish.app.export.BrushTickShape
import kotlin.system.exitProcess

/*
 * The done screen's tick, painted with a brush (28 Oct, the owner's ask): one
 * stroke, inside its box, that lands pressed, swells into the dip and runs out
 * to a point; painted in order from the landing to the lift; and a tick, not a
 * line - the dip lower than both ends.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val full = BrushTickShape.outline(1f)
    check(full.isNotEmpty(), "the painted tick has no outline")
    check(full.all { (x, y) -> x in 0f..1f && y in 0f..1f }, "the tick is painted outside its box: ${full.filter { (x, y) -> x !in 0f..1f || y !in 0f..1f }.take(3)}")
    check(BrushTickShape.outline(0f).isEmpty(), "something is painted before the brush lands")

    // Painted in order: a part of the way is a part of the stroke, from its start.
    val half = BrushTickShape.outline(0.5f)
    check(half.size in 2 until full.size, "half way the stroke has ${half.size} points of ${full.size}")
    check(half.first() == full.first(), "the stroke half painted does not start where the whole one does")

    // Pressed where it lands and the brush turns, a point where it lifts.
    val w = { s: Float -> BrushTickShape.widthAt(s) }
    check(w(0.4f) > w(0.95f) * 2.5f, "the stroke does not run out to a point: ${w(0.4f)} in the dip, ${w(0.95f)} at the lift")
    check(w(0f) < w(0.3f), "the stroke does not land lighter than it presses: ${w(0f)} against ${w(0.3f)}")
    check((0..100).all { w(it / 100f) > 0f }, "the stroke vanishes along the way")
    // Seen on the moto (28 Oct): pressed to 0.17 of the box over the short
    // stroke, the drop read as a blob, not a brush mark.
    val widest = (0..100).maxOf { w(it / 100f) }
    check(widest <= 0.14f, "the stroke is too heavy for a tick: $widest of the box at its widest")
    check(w(0.6f) >= w(0.3f) * 0.7f, "the rising arm thins too soon: ${w(0.6f)} against ${w(0.3f)} at the dip")

    // A tick: the dip below both ends, and the rising arm the longer.
    val spine = BrushTickShape.spine
    val dip = spine.maxByOrNull { it.second }!!
    check(dip.second > spine.first().second && dip.second > spine.last().second, "the tick's dip is not below its ends")
    check(spine.last().first - dip.first > dip.first - spine.first().first, "the rising arm is not the longer one")
    check(BrushTickShape.along.zipWithNext().all { (a, b) -> b >= a } && BrushTickShape.along.last() == 1f, "the way along the stroke does not run 0 to 1 in order")

    // The dry streaks stay inside the stroke and stop short of its tip.
    val streaks = BrushTickShape.bristles(1f)
    check(streaks.size == 3 && streaks.all { it.isNotEmpty() }, "the bristle streaks are missing")
    check(streaks.all { s -> s.all { (x, y) -> x in 0f..1f && y in 0f..1f } }, "a bristle streak leaves the box")

    if (problems.isEmpty()) println("BrushTickChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
