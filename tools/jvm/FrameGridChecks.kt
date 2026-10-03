import com.squish.app.media.video.FrameGrid
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** The frames kept from [times] (microseconds) at [fps]. */
fun kept(fps: Float, times: List<Long>): List<Long> { val g = FrameGrid(fps); return times.filter { g.keep(it) } }

/** The times the kept frames go into the file at. [onGrid] as the export decides it. */
fun slots(fps: Float, times: List<Long>, onGrid: Boolean = true): List<Long> {
    val g = FrameGrid(fps, onGrid)
    return times.mapNotNull { if (g.keep(it)) g.slotUs else null }
}

/** A steady input: [seconds] of frames at [inFps]. */
fun steady(inFps: Double, seconds: Double, startUs: Long = 0L): List<Long> =
    (0 until (inFps * seconds).toInt()).map { startUs + Math.round(it * 1_000_000.0 / inFps) }

fun rate(frames: List<Long>): Double = (frames.size - 1) * 1_000_000.0 / (frames.last() - frames.first())

fun main() {
    // The files found: 30 fps footage at 1.5x and 2.72x, asked for 30.
    for (speed in listOf(1.25, 1.5, 2.0, 2.72, 3.3, 8.0)) {
        val k = kept(30f, steady(30.0 * speed, 10.0))
        check(Math.abs(rate(k) - 30.0) < 0.3, "${speed}x wrote ${"%.2f".format(rate(k))} fps, not 30")
        val gaps = k.zipWithNext { a, b -> b - a }
        check(gaps.max() <= 1_000_000.0 / 30 + 1_000_000.0 / (30 * speed) + 1, "${speed}x left a gap of ${gaps.max()} us")
    }
    // Footage at the rate, or below it, keeps every frame - with a wobble too.
    check(kept(30f, steady(30.0, 5.0)).size == 150, "30 fps at 30 lost frames")
    check(kept(30f, steady(24.0, 5.0)).size == 120, "24 fps at 30 lost frames")
    check(kept(30f, steady(29.97, 5.0)).size == steady(29.97, 5.0).size, "29.97 fps at 30 lost frames")
    val wobbly = (0 until 300).map { it * 33_333L + (if (it % 2 == 0) 4_000L else -4_000L) }
    check(kept(30f, wobbly).size == 300, "30 fps with a 4 ms wobble lost ${300 - kept(30f, wobbly).size} frames")
    // 60 to 30 is every other frame, exactly.
    val sixty = steady(60.0, 5.0)
    check(kept(30f, sixty) == sixty.filterIndexed { i, _ -> i % 2 == 0 }, "60 to 30 is not every other frame")
    // A slow stretch then a fast one: no burst while the grid catches up.
    val mixed = steady(10.0, 5.0) + steady(90.0, 5.0, 5_000_000L)
    val fast = kept(30f, mixed).filter { it >= 5_000_000L }
    check(Math.abs(rate(fast) - 30.0) < 0.5, "the fast stretch after a slow one wrote ${"%.2f".format(rate(fast))} fps")
    check(fast.zipWithNext { a, b -> b - a }.min() >= 1_000_000 / 90, "frames closer than the input's own gap")
    // The file is evenly spaced, not merely the right rate on average: 30 fps
    // footage written at 24 alternated 33 ms and 67 ms until the kept frames
    // were stamped with their slots (found by probing an export's frame table).
    for (out in listOf(24f, 25f, 30f)) {
        val s = slots(out, steady(30.0, 10.0))
        val gaps = s.zipWithNext { a, b -> b - a }.distinct()
        check(gaps.size == 1, "30 fps at ${out.toInt()} wrote gaps $gaps, not one even step")
        check(s.first() == 0L, "30 fps at ${out.toInt()} moved the first frame to ${s.first()}")
        // The grid never drifts: every slot is counted from the first frame.
        val period = (1_000_000.0 / out).toLong()
        check(s.withIndex().all { (i, t) -> t == i * period }, "30 fps at ${out.toInt()} drifted off its grid")
    }
    // Nothing is moved by as much as half a period, so sound stays with picture.
    for (out in listOf(24f, 25f)) {
        val times = steady(30.0, 10.0)
        val gg = FrameGrid(out, true)
        val shift = times.mapNotNull { if (gg.keep(it)) gg.slotUs - it else null }
        check(shift.all { Math.abs(it) <= 1_000_000L / out.toLong() / 2 + 1 }, "${out.toInt()} moved a frame by ${shift.maxOf { Math.abs(it) }} us")
    }
    // Footage no faster than the rate asked for is left alone (the export passes
    // onGrid false): every frame is kept there, and pulling them onto a faster
    // grid would run the shot quicker than it was cut. 60 asked of 30 fps
    // footage is the case the sheet offers.
    check(slots(60f, steady(30.0, 5.0), onGrid = false) == steady(30.0, 5.0), "30 fps footage at 60 was pulled onto the grid")
    check(slots(30f, steady(24.0, 5.0), onGrid = false) == steady(24.0, 5.0), "24 fps footage at 30 was pulled onto the grid")
    // A slow stretch then a fast one: still in order, and the slow part untouched.
    val mix = steady(10.0, 5.0) + steady(90.0, 5.0, 5_000_000L)
    val mixSlots = slots(30f, mix)
    check(mixSlots == mixSlots.sorted() && mixSlots.distinct() == mixSlots, "slots went backwards or repeated")
    check(mixSlots.filter { it < 5_000_000L } == steady(10.0, 5.0), "the slow stretch was moved")
    // A clip slower than the project's own rate, in a project whose rate says
    // the grid is safe (the export reads one rate for the edit, not one per
    // file): the grid must not run it fast. The catch-up branch is what holds
    // it - every frame is kept, and a slot more than half a period off the
    // frame re-anchors the grid to the frame. So a frame moves by less than one
    // period and the clip ends where it would have ended.
    for (inFps in listOf(24.0, 25.0, 15.0)) {
        val times = steady(inFps, 8.0)
        val s = slots(30f, times, onGrid = true)
        check(s.size == times.size, "a ${inFps.toInt()} fps clip lost frames at 30")
        val drift = s.zip(times) { out, own -> out - own }
        check(drift.all { Math.abs(it) < 1_000_000L / 30 }, "a ${inFps.toInt()} fps clip moved a frame by ${drift.maxOf { Math.abs(it) }} us")
        check(s == s.sorted() && s.distinct() == s, "a ${inFps.toInt()} fps clip got slots out of order")
        // No creep: the end is where the footage puts it, not where a 30 fps grid would.
        check(Math.abs(s.last() - times.last()) < 1_000_000L / 30, "a ${inFps.toInt()} fps clip drifted by its end")
    }

    // Which frames are kept is unchanged by the stamping.
    for (out in listOf(24f, 25f, 30f, 60f)) {
        val times = steady(30.0, 10.0)
        check(slots(out, times, onGrid = false) == kept(out, times), "stamping changed which frames ${out.toInt()} keeps")
    }

    // A new stream starts again; the first frame is always kept.
    val g = FrameGrid(30f)
    check(g.keep(0L) && !g.keep(10_000L), "the grid did not start on the first frame")
    g.reset()
    check(g.keep(5_000_000L), "the first frame after a reset was dropped")
    if (problems.isEmpty()) println("FrameGridChecks: all checks passed") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
