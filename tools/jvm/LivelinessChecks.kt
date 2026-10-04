import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Liveliness
import com.squish.app.timeline.LivelinessProfile
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withBestBitsKept
import kotlin.system.exitProcess

// Which part of a shot is worth keeping. The rule that makes this safe to run
// over a whole timeline is that a flat profile keeps the head, exactly as the
// old behaviour did - so everything here leans on that holding at every size.

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private fun profile(stepMs: Long, vararg scores: Float) = LivelinessProfile(stepMs, scores.toList())

private fun shot(id: String, inMs: Long, outMs: Long, fileMs: Long, start: Long = 0) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = inMs, sourceOutMs = outMs,
    timelineStartMs = start, sourceDurationMs = fileMs
)

fun main() {
    // --- A flat profile: deterministic, in range, clear of the ends. ---------
    // Nothing to choose between the parts, so it lands just past the discounted
    // start - the hand settling, which reads as movement and must not win.
    for (steps in 1..40) {
        val flat = LivelinessProfile(100L, List(steps) { 0.5f })
        val fileMs = steps * 100L
        for (want in listOf(100L, 300L, 1_000L, 2_500L)) {
            val at = Liveliness.bestWindowStart(flat, fileMs, want)
            check(at >= 0L, "a flat profile of $steps steps started a window at $at")
            check(at + want <= fileMs || want >= fileMs, "a ${want}ms window at $at runs past a file of $fileMs")
            check(
                at == Liveliness.bestWindowStart(flat, fileMs, want),
                "the same question twice gave two answers"
            )
            // Only where there is room to clear both discounted ends; a window
            // nearly as long as the file has to overlap one of them.
            val edge = (steps * Liveliness.EDGE_SHARE).toInt() * 100L
            if (want <= fileMs - edge * 2) {
                check(at >= edge, "a flat window sat inside the discounted start: at=$at steps=$steps want=$want edge=$edge")
            }
        }
    }
    // Nothing measured at all: the head.
    check(Liveliness.bestWindowStart(LivelinessProfile(0L, emptyList()), 5_000, 1_000) == 0L, "an empty profile moved the window")
    check(Liveliness.bestWindowStart(profile(100L, 1f, 9f, 1f), 5_000, 0) == 0L, "a window of nothing moved")

    // --- The window lands on the busy part. ----------------------------------
    run {
        // Ten seconds at 1 s a step, everything happening in the eighth second.
        val p = LivelinessProfile(1_000L, listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 9f, 0f, 0f))
        val at = Liveliness.bestWindowStart(p, 10_000, 2_000)
        check(at in 6_000..7_000, "the busy second at 7 s was not kept: the window starts at $at")
        check(at + 2_000 <= 10_000, "the window runs past the end of the file")
    }

    // --- The window never runs past the end. ---------------------------------
    run {
        val p = LivelinessProfile(500L, List(20) { i -> if (i >= 18) 9f else 0f })
        val at = Liveliness.bestWindowStart(p, 10_000, 3_000)
        check(at + 3_000 <= 10_000, "a window chasing the end ran past it: $at + 3000")
        check(at >= 6_500, "the busy end was not kept: $at")
    }

    // --- Nothing fits: the head, and never a negative start. ------------------
    listOf(1_000L to 5_000L, 5_000L to 5_000L, 5_000L to 9_000L).forEach { (duration, want) ->
        val p = LivelinessProfile(100L, List(10) { it.toFloat() })
        val at = Liveliness.bestWindowStart(p, duration, want)
        check(at == 0L, "a window of $want in a file of $duration started at $at")
    }

    // --- The ends are discounted, but a very busy end still wins. -------------
    run {
        // The same score at the very start as in the middle: the middle wins.
        val steps = 50
        val scores = MutableList(steps) { 0f }
        (0 until 4).forEach { scores[it] = 1f }
        (24 until 28).forEach { scores[it] = 1f }
        val at = Liveliness.bestWindowStart(LivelinessProfile(100L, scores), 5_000, 400)
        check(at >= 2_000, "a discounted start beat an equal middle: $at")
        // Twice as busy at the start: it wins anyway, since the ends are
        // discounted rather than ruled out.
        (0 until 4).forEach { scores[it] = 3f }
        val now = Liveliness.bestWindowStart(LivelinessProfile(100L, scores), 5_000, 400)
        check(now < 1_000, "a far busier start was ruled out rather than discounted: $now")
    }

    // --- Combining the two measurements. -------------------------------------
    run {
        val motion = listOf(0f, 1f, 0f, 0f)
        val loud = listOf(0f, 0f, 0f, 2_000f)
        val mixed = Liveliness.combined(motion, loud)
        check(mixed.size == 4, "combining gave ${mixed.size} steps")
        check(mixed[1] > mixed[0] && mixed[3] > mixed[0], "neither peak survived the mix")
        // Scaled by their own peaks first, so a loud number does not swamp a
        // small one: movement alone and sound alone score the same.
        check(kotlin.math.abs(mixed[1] - mixed[3]) < 1e-5f, "the two peaks came out unequal: ${mixed[1]} vs ${mixed[3]}")
        check(Liveliness.combined(emptyList(), emptyList()).isEmpty(), "combining nothing gave something")
        check(Liveliness.combined(listOf(0f, 0f), listOf(0f, 0f)).all { it == 0f }, "silence and stillness scored")
        // One measurement missing entirely - a silent clip - still works.
        val motionOnly = Liveliness.combined(listOf(0f, 1f, 0f), emptyList())
        check(motionOnly.size == 3 && motionOnly[1] > motionOnly[0], "a silent clip's movement was lost")
    }

    check(Liveliness.normalized(listOf(2f, 4f)) == listOf(0.5f, 1f), "normalising went wrong")
    check(Liveliness.normalized(listOf(0f, 0f)) == listOf(0f, 0f), "normalising nothing divided by it")
    check(Liveliness.normalized(listOf(-3f, -1f)) == listOf(0f, 0f), "a profile of negatives was not refused")

    // --- Over a timeline. -----------------------------------------------------
    run {
        val before = TimelineState(
            clips = listOf(
                shot("a", 0, 2_000, 10_000, 0),
                shot("b", 0, 3_000, 10_000, 2_000),
                shot("c", 0, 4_000, 4_000, 5_000)
            )
        )
        val busyLate = LivelinessProfile(1_000L, List(10) { i -> if (i >= 7) 9f else 0f })
        val after = before.withBestBitsKept(mapOf("a" to busyLate, "c" to busyLate))
        val a = after.clips.first { it.id == "a" }
        val b = after.clips.first { it.id == "b" }
        val c = after.clips.first { it.id == "c" }
        check(a.sourceInMs >= 6_000, "shot a did not move to its busy end: ${a.sourceInMs}")
        check(a.sourceSpanMs == 2_000L, "shot a changed length to ${a.sourceSpanMs}")
        check(b.sourceInMs == 0L && b.sourceSpanMs == 3_000L, "a shot with no profile was moved")
        check(c.sourceInMs == 0L, "a shot using its whole file was moved")
        // Nothing on the timeline moved: the lengths are what the strip reads.
        check(after.clips.map { it.timelineStartMs } == before.clips.map { it.timelineStartMs }, "the track shifted")
        check(after.clips.map { it.durationMs } == before.clips.map { it.durationMs }, "a shot's length changed")
    }

    // --- Nothing to do. --------------------------------------------------------
    run {
        val before = TimelineState(clips = listOf(shot("a", 0, 2_000, 10_000)))
        check(before.withBestBitsKept(emptyMap()) == before, "no profiles changed the edit")
        check(TimelineState().withBestBitsKept(mapOf("a" to profile(100L, 1f))).clips.isEmpty(), "an empty edit grew a shot")
    }

    println("liveliness: the part of a shot worth keeping, chosen")
    if (problems.isEmpty()) println("PASS - a busy shot keeps its best bit, a flat one lands clear of its ends, and one with nothing measured is untouched")
    else { println("FAIL (${problems.size})"); problems.take(20).forEach { println("  - $it") }; exitProcess(1) }
}
