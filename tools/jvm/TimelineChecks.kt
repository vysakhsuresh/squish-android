import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withSplitAtPlayhead
import kotlin.math.abs
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun clip(id: String, ramp: SpeedRamp, srcIn: Long = 0, srcOut: Long = 4000, start: Long = 0) =
    Clip(
        id = id, kind = ClipKind.Video, label = id,
        sourceInMs = srcIn, sourceOutMs = srcOut,
        timelineStartMs = start, sourceDurationMs = 20_000, speedRamp = ramp
    )

fun main() {
    val ramps = buildList {
        add("flat 1x" to SpeedRamp())
        add("flat 0.5x" to SpeedRamp.flat(0.5f))
        add("flat 2x" to SpeedRamp.flat(2f))
        for (shape in RampShape.entries) add(shape.name to SpeedRamp.preset(shape, 4000L))
    }

    // --- Clip length is played length, and it follows the ramp. ---------------
    check(clip("a", SpeedRamp()).durationMs == 4000L, "1x clip is not 4000ms")
    check(clip("a", SpeedRamp.flat(0.5f)).durationMs == 8000L, "0.5x clip is not 8000ms")
    check(clip("a", SpeedRamp.flat(2f)).durationMs == 2000L, "2x clip is not 2000ms")
    check(clip("a", SpeedRamp.flat(2f)).sourceSpanMs == 4000L, "speed changed the source span")

    // --- sourceAt / timelineAtSource round trip over the timeline. ------------
    for ((name, ramp) in ramps) {
        val c = clip("a", ramp, start = 1500)
        for (i in 0..20) {
            val t = c.timelineStartMs + c.durationMs * i / 20
            val src = c.sourceAt(t)
            check(src in c.sourceInMs..c.sourceOutMs, "$name: sourceAt($t) = $src outside the window")
            val back = c.timelineAtSource(src)
            val slack = (SpeedRamp.STEP_MS / ramp.speedAt(src - c.sourceInMs)).toLong() + 4
            check(abs(back - t) <= slack, "$name: timeline $t -> source $src -> $back (slack $slack)")
        }
        check(c.sourceAt(c.timelineStartMs) == c.sourceInMs, "$name: clip start is not the source in point")
    }

    // --- A razor cut must not change the total played length or the frames. ---
    for ((name, ramp) in ramps) {
        val c = clip("a", ramp)
        val whole = c.durationMs
        for (frac in listOf(0.1, 0.25, 0.5, 0.75, 0.9)) {
            val cut = (whole * frac).toLong()
            val after = TimelineState(clips = listOf(c), playheadMs = cut).withSplitAtPlayhead()
            check(after.clips.size == 2, "$name cut@$frac: split produced ${after.clips.size} clips")
            if (after.clips.size != 2) continue
            val (first, second) = after.clips.sortedBy { it.timelineStartMs }

            val total = first.durationMs + second.durationMs
            val slowest = (0..4000L step 40).minOf { ramp.speedAt(it) }
            val slack = (SpeedRamp.STEP_MS / slowest).toLong() + 6
            check(
                abs(total - whole) <= slack,
                "$name cut@$frac: halves total ${total}ms, whole was ${whole}ms (slack $slack)"
            )
            // No footage lost or duplicated at the seam.
            check(
                first.sourceOutMs == second.sourceInMs,
                "$name cut@$frac: seam gap, ${first.sourceOutMs} vs ${second.sourceInMs}"
            )
            check(
                first.sourceInMs == c.sourceInMs && second.sourceOutMs == c.sourceOutMs,
                "$name cut@$frac: the halves do not span the original window"
            )
            check(second.timelineStartMs == cut, "$name cut@$frac: second half starts at ${second.timelineStartMs}, not $cut")
            check(first.durationMs > 0 && second.durationMs > 0, "$name cut@$frac: a half has no length")
        }
    }

    // --- Trimming the head must leave the kept frames where they were. --------
    for ((name, ramp) in ramps) {
        val c = clip("a", ramp, start = 2000)
        for (headMs in listOf(200L, 800L, 2000L)) {
            val after = TimelineState(clips = listOf(c)).withClipTrimmed("a", headMs, 0L)
            val t = after.clips.first()
            check(t.sourceInMs == c.sourceInMs + headMs, "$name head@$headMs: source in is ${t.sourceInMs}")

            // The frame that is now first used to sit somewhere on the timeline.
            // It must still be there.
            val wasAt = c.timelineAtSource(t.sourceInMs)
            val slowest = (0..4000L step 40).minOf { ramp.speedAt(it) }
            val slack = (SpeedRamp.STEP_MS / slowest).toLong() + 6
            check(
                abs(t.timelineStartMs - wasAt) <= slack,
                "$name head@$headMs: kept frames moved from $wasAt to ${t.timelineStartMs} (slack $slack)"
            )
            // And they must still play at the rates they played at.
            for (probe in 0..(t.sourceSpanMs) step 311) {
                val now = t.speedRamp.speedAt(probe)
                val before = ramp.speedAt(headMs + probe)
                check(abs(now - before) < 0.02f, "$name head@$headMs: rate at $probe is $now, was $before")
            }
        }
    }

    // --- Trimming the tail must not move the clip at all. ---------------------
    for ((name, ramp) in ramps) {
        val c = clip("a", ramp, start = 2000)
        val after = TimelineState(clips = listOf(c)).withClipTrimmed("a", 0L, -1000L)
        val t = after.clips.first()
        check(t.timelineStartMs == c.timelineStartMs, "$name: a tail trim moved the clip")
        check(t.sourceOutMs == c.sourceOutMs - 1000L, "$name: tail trim left source out at ${t.sourceOutMs}")
        check(t.durationMs < c.durationMs, "$name: a tail trim did not shorten the clip")
    }

    // --- An unramped clip must behave exactly as it did before ramps existed. -
    run {
        val c = clip("a", SpeedRamp(), start = 500)
        val after = TimelineState(clips = listOf(c), playheadMs = 2500).withSplitAtPlayhead()
        val (first, second) = after.clips.sortedBy { it.timelineStartMs }
        check(first.sourceOutMs == 2000L, "1x split cut at source ${first.sourceOutMs}, expected 2000")
        check(second.timelineStartMs == 2500L, "1x split placed the second half at ${second.timelineStartMs}")
        check(first.durationMs + second.durationMs == c.durationMs, "1x split changed the total length")
        val trimmed = TimelineState(clips = listOf(c)).withClipTrimmed("a", 300L, 0L).clips.first()
        check(trimmed.timelineStartMs == 800L, "1x head trim put the clip at ${trimmed.timelineStartMs}, expected 800")
    }

    println("timeline checks over ${ramps.size} ramps")
    if (problems.isEmpty()) println("PASS - cuts and trims survive a ramp")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
