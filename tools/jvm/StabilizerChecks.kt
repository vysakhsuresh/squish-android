import com.squish.app.media.video.Correction
import com.squish.app.media.video.FrameMotion
import com.squish.app.media.video.StabilizerMeasurement
import com.squish.app.media.video.StabilizerSolve
import com.squish.app.media.video.TrajectorySmoother
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * The stabilizer's solve, executed on a synthetic shake: the same measurement
 * solved twice gives the same correction (so Strength can re-solve from the
 * kept measurement and land where the analysis would have), a stronger
 * strength spends more frame, and a measurement too thin to trust is refused.
 */

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // A slow pan with a 6 Hz shake on top, measured at 30 fps.
    val n = 240
    val motions = (0 until n).map { i ->
        val t = i / 30f
        FrameMotion(
            dx = 0.4f + 2.5f * sin(t * 6f * 2f * Math.PI.toFloat()),
            dy = 1.8f * sin(t * 5f * 2f * Math.PI.toFloat() + 1f),
            rotationDegrees = 0.15f * sin(t * 4f * 2f * Math.PI.toFloat()),
            confidence = 0.9f
        )
    }
    val times = (0 until n).map { i -> 1000L + (i + 1) * 33L }
    val measurement = StabilizerMeasurement(96, 54, times, motions)

    val a = StabilizerSolve.solve(measurement, 0.5f)
    val b = StabilizerSolve.solve(measurement, 0.5f)
    check(a != null, "a plain measurement was refused")
    check(a == b, "the same measurement at the same strength solved differently")
    check(a!!.keyframes.size == n, "one key per motion expected, got ${a.keyframes.size}")
    check(a.keyframes.map { it.atMs } == times, "keys are not on the measured frames' source times")
    check(a.keyframes.zipWithNext().all { (x, y) -> x.atMs <= y.atMs }, "keys are not sorted")
    check(a.crop > 0f, "a shaking shot cost no frame at all")
    check(a.keyframes.all { abs(it.transform.scale - (1f + a.crop)) < 1e-5f }, "the zoom does not match the crop")
    // The correction pushes against the shake: the two are anti-correlated.
    var dot = 0f
    a.keyframes.forEachIndexed { i, k -> dot += k.transform.offsetXFraction * motions[i].dx }
    check(dot < 0f, "the correction runs with the shake rather than against it")

    val weak = StabilizerSolve.solve(measurement, 0f)!!
    val strong = StabilizerSolve.solve(measurement, 1f)!!
    check(StabilizerSolve.windowFrames(1f) > StabilizerSolve.windowFrames(0f), "strength does not widen the window")
    check(StabilizerSolve.cropBudget(1f) > StabilizerSolve.cropBudget(0f), "strength does not raise the crop budget")
    check(strong.crop >= weak.crop, "full strength spends less frame (${strong.crop}) than none (${weak.crop})")
    check(weak != strong, "strength changed nothing")

    check(StabilizerSolve.solve(StabilizerMeasurement(96, 54, times.take(3), motions.take(3)), 0.5f) == null, "three motions were solved")
    check(StabilizerSolve.solve(StabilizerMeasurement(0, 0, times, motions), 0.5f) == null, "a measurement with no frame size was solved")

    // ---- The zoom covers the turn as well as the shift ----------------------
    //
    // The solve turns the frame by up to 1.5 degrees and the crop used to be
    // measured off the translation alone, so the picture was rotated by more
    // than the zoom covered and black wedges ran along the edges. Worst on
    // gimbal footage, where the roll is all that is left: no shift at all gave
    // a crop of exactly zero with half a degree still being applied.
    run {
        val w = 1920f
        val h = 1080f
        // A pure turn, no shift.
        listOf(0.25f, 0.6f, 1.5f).forEach { degrees ->
            val only = listOf(Correction(0f, 0f, degrees))
            val crop = TrajectorySmoother.requiredCrop(only, w, h)
            // What a turn of that size needs to keep a 16:9 frame covered.
            val radians = degrees * kotlin.math.PI / 180.0
            val needed = (kotlin.math.cos(radians) + kotlin.math.sin(radians) * (w / h) - 1.0).toFloat()
            check(crop >= needed - 1e-4f, "a turn of $degrees deg asks for $crop and needs $needed")
            check(crop <= needed * 1.2f + 1e-4f, "a turn of $degrees deg asks for $crop, far more than the $needed it needs")
        }
        check(TrajectorySmoother.requiredCrop(listOf(Correction(0f, 0f, 0f)), w, h) == 0f,
            "a correction that does nothing still asked for a crop")
        // A shift and a turn together ask for more than either alone.
        val shiftOnly = TrajectorySmoother.requiredCrop(listOf(Correction(10f, 0f, 0f)), w, h)
        val turnOnly = TrajectorySmoother.requiredCrop(listOf(Correction(0f, 0f, 0.6f)), w, h)
        val both = TrajectorySmoother.requiredCrop(listOf(Correction(10f, 0f, 0.6f)), w, h)
        check(both > shiftOnly && both > turnOnly, "a shift and a turn together ask for $both, no more than $shiftOnly or $turnOnly")
        // And a portrait frame needs more for the same turn than a square one.
        val portrait = TrajectorySmoother.requiredCrop(listOf(Correction(0f, 0f, 1f)), 1080f, 1920f)
        val square = TrajectorySmoother.requiredCrop(listOf(Correction(0f, 0f, 1f)), 1080f, 1080f)
        check(portrait > square, "a portrait frame asks $portrait for a turn a square one asks $square for")
    }

    // ---- The measurement on a reversed render's clock ----------------------
    //
    // A time stamps the motion's *later* frame - the frame the move arrives at,
    // and so the frame the correction belongs to. Played backwards, the move
    // that arrived at frame k now arrives at frame k-1, so mirroring the times
    // without re-pairing them stamped every correction on the frame it moved
    // *from*: every re-solved key on a reversed clip landed one frame early,
    // the first at the pivot where no measurement reaches, and the reversed
    // clip's last frame had no key at all.
    run {
        val step = 40L
        val frames = 10
        // Frames at 0, 40, ..., 400; a motion into each of the nine after the
        // first, so the times are 40..400.
        val times = (1..frames - 1).map { it * step }
        val motions = times.indices.map { FrameMotion(dx = 1f + it, dy = 2f, rotationDegrees = 0.1f, confidence = 1f) }
        val measured = StabilizerMeasurement(
            analysisWidth = 320,
            analysisHeight = 180,
            timesMs = times,
            motions = motions
        )
        val pivot = (frames - 1) * step   // the render's out point: the last frame
        val mirrored = measured.mirroredAt(pivot)

        check(mirrored.timesMs.size == times.size, "mirroring changed the number of times")
        check(mirrored.timesMs == mirrored.timesMs.sorted(), "the mirrored times are out of order: ${mirrored.timesMs}")
        // The move that arrived at the *second* frame, reversed, arrives at the
        // first - which on the mirrored clock is the pivot. That key is the one
        // that used to be missing entirely.
        check(
            mirrored.timesMs.last() == pivot - (times.first() - step),
            "the last mirrored time is ${mirrored.timesMs.last()}, and the first frame mirrors to ${pivot - (times.first() - step)}"
        )
        // And the first mirrored key is one frame in from the start, not at it:
        // nothing arrives at the reversed clip's own first frame.
        check(
            mirrored.timesMs.first() == pivot - times[times.size - 2],
            "the first mirrored time is ${mirrored.timesMs.first()}, want ${pivot - times[times.size - 2]}"
        )
        check(mirrored.timesMs.first() > 0L, "a mirrored key sits at the very start, where no motion arrives")
        // Its own inverse, which is what lets a clip be reversed and reversed
        // again: the step is added once and taken off once.
        val back = mirrored.mirroredAt(pivot)
        check(back.timesMs == measured.timesMs, "mirroring twice gave ${back.timesMs}, not ${measured.timesMs}")
        check(
            back.motions.map { it.dx } == measured.motions.map { it.dx },
            "mirroring twice did not put the motions back"
        )
        // The motions are reversed and negated - the same move the other way.
        check(mirrored.motions.map { it.dx } == motions.reversed().map { -it.dx }, "the mirrored motions are not the negated reverse")

        // Solved, every key lands inside the reversed clip and on a frame.
        val solved = StabilizerSolve.solve(mirrored, 0.5f)
        check(solved != null, "a mirrored measurement did not solve")
        solved?.keyframes?.forEach { key ->
            check(key.atMs in 0L..pivot, "a mirrored key is at ${key.atMs}, outside 0..$pivot")
            check(key.atMs % step == 0L, "a mirrored key is at ${key.atMs}, which is not a frame")
        }
        // One measured frame short of a full clip, and a measurement with one
        // motion in it: neither may throw or invent a time.
        check(
            StabilizerMeasurement(320, 180, listOf(step), listOf(motions.first())).mirroredAt(pivot).timesMs == listOf(pivot - step),
            "a single motion mirrored wrongly"
        )
        check(
            StabilizerMeasurement(320, 180, emptyList(), emptyList()).mirroredAt(pivot).timesMs.isEmpty(),
            "an empty measurement gained a time"
        )
    }

    println()
    if (problems.isEmpty()) println("PASS - the stabilizer solves the same from its measurement, and strength re-solves")
    else { println("FAIL (${problems.size})"); problems.take(25).forEach { println("  - $it") }; exitProcess(1) }
}
