import com.squish.app.media.video.FrameBlendPlan
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Where the frame blender puts its frames, executed: frames only where the
 * footage has none, at the file's own cadence, each mixed by where it sits,
 * never one on top of a real frame, and never more than the effect has
 * textures for.
 */

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val at30 = FrameBlendPlan.intervalUs(30)
    check(at30 == 33_333L, "30 fps interval is $at30")

    // Footage at 30 fps played at normal speed: nothing to add.
    check(FrameBlendPlan.between(0L, 33_333L, at30, 8).isEmpty(), "a normal gap sprouted frames")
    check(FrameBlendPlan.between(0L, 33_400L, at30, 8).isEmpty(), "a gap a rounding error long sprouted frames")
    check(FrameBlendPlan.between(0L, 40_000L, at30, 8).isEmpty(), "a gap under a quarter frame over sprouted frames")

    // A quarter speed: one real frame every 133 ms, three blends between.
    val quarter = FrameBlendPlan.between(0L, 133_333L, at30, 8)
    check(quarter.size == 3, "quarter speed made ${quarter.size} frames, not 3: $quarter")
    check(quarter.map { it.timeUs } == listOf(33_333L, 66_666L, 99_999L), "blends are not on the file's cadence: $quarter")
    check(abs(quarter[0].mix - 0.25f) < 0.01f && abs(quarter[2].mix - 0.75f) < 0.01f, "mixes are not where the frames sit: $quarter")
    check(quarter.all { it.timeUs > 0L && it.timeUs < 133_333L }, "a blend landed on a real frame")

    // Half speed: one between.
    val half = FrameBlendPlan.between(1_000_000L, 1_066_666L, at30, 8)
    check(half.size == 1 && half[0].timeUs == 1_033_333L && abs(half[0].mix - 0.5f) < 0.01f, "half speed: $half")

    // A tenth: nine wanted, capped to what the effect can hold, nearest first.
    val tenth = FrameBlendPlan.between(0L, 333_333L, at30, 6)
    check(tenth.size == 6, "the cap did not hold: ${tenth.size}")
    check(tenth.first().timeUs == 33_333L, "the cap kept the wrong frames: ${tenth.first()}")

    // A gap ending a sliver after a cadence point gets no blend on top of the real frame.
    val sliver = FrameBlendPlan.between(0L, 66_666L + 2_000L, at30, 8)
    check(sliver.size == 1, "a blend was put a sliver before the real frame: $sliver")

    // Every blend is strictly between, increasing, and mixes climb with time.
    for (gap in listOf(50_000L, 100_000L, 250_000L, 1_000_000L)) {
        val blends = FrameBlendPlan.between(500_000L, 500_000L + gap, at30, 8)
        check(blends.zipWithNext().all { (x, y) -> x.timeUs < y.timeUs && x.mix < y.mix }, "gap $gap: not increasing: $blends")
        check(blends.all { it.timeUs > 500_000L && it.timeUs < 500_000L + gap }, "gap $gap: a blend outside the gap")
    }
    check(FrameBlendPlan.between(0L, 200_000L, 0L, 8).isEmpty(), "a zero interval did not refuse")
    check(FrameBlendPlan.between(0L, 200_000L, at30, 0).isEmpty(), "no textures did not refuse")

    println()
    if (problems.isEmpty()) println("PASS - blended frames land only where the footage has none, at the file's cadence")
    else { println("FAIL (${problems.size})"); problems.take(25).forEach { println("  - $it") }; exitProcess(1) }
}
