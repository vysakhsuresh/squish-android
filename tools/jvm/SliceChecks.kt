import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * The property that matters for a razor cut: slicing a clip in two must not change
 * what plays. The two halves together have to run the same frames at the same
 * rates, in the same total time, as the one clip did.
 */
fun main() {
    val problems = mutableListOf<String>()
    fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

    val span = 4321L
    val ramps = buildList {
        for (shape in RampShape.entries) add(shape.name to SpeedRamp.preset(shape, span))
        add("flat 0.5x" to SpeedRamp.flat(0.5f))
        add("flat 1x" to SpeedRamp.flat(1f))
        add("two point" to SpeedRamp(listOf(SpeedPoint(0, 0.3f), SpeedPoint(span, 3f))))
    }

    for ((name, ramp) in ramps) {
        val whole = ramp.outputDurationMs(span)

        for (cutSource in listOf(1L, 40L, 500L, span / 2, span - 500, span - 40, span - 1)) {
            val a = ramp.sliced(0L, cutSource)
            val b = ramp.sliced(cutSource, span)

            val aOut = a.outputDurationMs(cutSource)
            val bOut = b.outputDurationMs(span - cutSource)

            // Halves must add up. Slack is one staircase tread at the slowest rate
            // present, because the cut lands mid-tread.
            val slowest = (0..span step 40).minOf { ramp.speedAt(it) }
            val slack = (SpeedRamp.STEP_MS / slowest).toLong() + 4
            check(
                abs((aOut + bOut) - whole) <= slack,
                "$name cut@$cutSource: halves $aOut + $bOut = ${aOut + bOut}, whole $whole (slack $slack)"
            )

            // The rate must be continuous across the cut: the end of the first
            // half and the start of the second are the same frame of the file.
            val before = a.speedAt(cutSource)
            val after = b.speedAt(0L)
            check(
                abs(before - after) < 0.01f,
                "$name cut@$cutSource: rate jumps ${before} -> ${after} across the cut"
            )

            // And every frame in each half must play at the rate it played at in
            // the whole clip.
            for (probe in 0..cutSource step 97) {
                check(
                    abs(a.speedAt(probe) - ramp.speedAt(probe)) < 0.02f,
                    "$name cut@$cutSource: first half rate at $probe is ${a.speedAt(probe)}, was ${ramp.speedAt(probe)}"
                )
            }
            for (probe in 0..(span - cutSource) step 97) {
                check(
                    abs(b.speedAt(probe) - ramp.speedAt(cutSource + probe)) < 0.02f,
                    "$name cut@$cutSource: second half rate at $probe is ${b.speedAt(probe)}, was ${ramp.speedAt(cutSource + probe)}"
                )
            }
        }

        // Trimming the head: the frames that survive must play exactly as before.
        for (head in listOf(0L, 200L, span / 3)) {
            val trimmed = ramp.sliced(head, span)
            for (probe in 0..(span - head) step 131) {
                check(
                    abs(trimmed.speedAt(probe) - ramp.speedAt(head + probe)) < 0.02f,
                    "$name head@$head: rate at $probe is ${trimmed.speedAt(probe)}, was ${ramp.speedAt(head + probe)}"
                )
            }
        }
    }

    // A flat clip is unchanged by any slice - no boundary points, nothing to drift.
    check(SpeedRamp.flat(2f).sliced(100, 900).flatSpeed == 2f, "slicing a flat ramp changed its rate")
    check(!SpeedRamp.flat(2f).sliced(100, 900).isRamped, "slicing a flat ramp made it ramped")
    check(SpeedRamp().sliced(0, 500).isIdentity, "slicing identity stopped being identity")
    check(SpeedRamp.preset(RampShape.BulletTime, span).sliced(500, 500).isRamped.not(),
        "a zero-length slice is not flat")

    println("slice check over ${ramps.size} ramps x 7 cut points x 3 head trims")
    if (problems.isEmpty()) println("PASS - a cut does not change what plays")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
