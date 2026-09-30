import com.squish.app.media.video.FrameGrid
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** The frames kept from [times] (microseconds) at [fps]. */
fun kept(fps: Float, times: List<Long>): List<Long> { val g = FrameGrid(fps); return times.filter { g.keep(it) } }

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
    // A new stream starts again; the first frame is always kept.
    val g = FrameGrid(30f)
    check(g.keep(0L) && !g.keep(10_000L), "the grid did not start on the first frame")
    g.reset()
    check(g.keep(5_000_000L), "the first frame after a reset was dropped")
    if (problems.isEmpty()) println("FrameGridChecks: all checks passed") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
