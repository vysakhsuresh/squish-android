import com.squish.app.media.ReverseRuns
import kotlin.system.exitProcess

/**
 * How far each run of a reversed render is fed.
 *
 * The one that mattered: the last run of the window had no bound at all - it
 * was given the end of the track - so reversing the first few seconds of a long
 * recording decoded the whole remainder of the file and threw every frame of it
 * away. Nothing executed covered it, which is why it stood. These are the
 * invariants: every run's bound is finite when the file has keyframes past the
 * window, every bound is past the run's own start, and the last run's bound is
 * a bounded distance past the window rather than a function of the file's
 * length.
 */

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    println("reverse: how far each run is fed")

    // A 20-minute file at one keyframe a second, a window of the first 3 s.
    val outUs = 3_000_000L
    val inWindow = listOf(0L, 1_000_000L, 2_000_000L)
    val past = (3..1_200).map { it * 1_000_000L }
    val tail = ReverseRuns.tailBoundUs(past)

    check(tail == 4_000_000L, "the last run is fed to $tail, not the second keyframe past the window")
    check(tail < Long.MAX_VALUE, "the last run is still fed to the end of the track")
    // The whole point: what it costs does not grow with the file.
    val longer = ReverseRuns.tailBoundUs((3..100_000).map { it * 1_000_000L })
    check(longer == tail, "a longer file moved the bound to $longer: the cost grows with the file again")

    inWindow.indices.forEach { gop ->
        val end = ReverseRuns.runEndUs(inWindow, gop, tail)
        check(end > inWindow[gop], "run $gop is fed from ${inWindow[gop]} to $end")
        check(end < Long.MAX_VALUE, "run $gop has no bound")
        check(end <= tail, "run $gop is fed to $end, past the last run's own bound of $tail")
    }
    // Every run but the last stops where the next begins, so no sample is fed twice.
    check(ReverseRuns.runEndUs(inWindow, 0, tail) == 1_000_000L, "the first run does not stop where the second begins")
    check(ReverseRuns.runEndUs(inWindow, 1, tail) == 2_000_000L, "the second run does not stop where the third begins")
    check(ReverseRuns.runEndUs(inWindow, 2, tail) == tail, "the last run is not fed to the tail bound")

    // The margin is a whole run, not the window's edge: the samples come out in
    // decode order, so a frame displaying just inside the window can be stored
    // after the keyframe that follows it. One keyframe of bound would drop it.
    check(ReverseRuns.TAIL_SYNCS >= 2, "the tail margin is ${ReverseRuns.TAIL_SYNCS} keyframes, less than a whole run")
    check(tail > outUs, "the last run is fed only to the window's own end, with no room for an open GOP")

    // A window that reaches the end of the file: fewer keyframes past it than
    // the margin wants, so the extractor's own end of stream is the bound, and
    // that is cheap because there is nothing after it.
    check(ReverseRuns.tailBoundUs(emptyList()) == Long.MAX_VALUE, "a window at the end of the file was given a bound inside it")
    check(ReverseRuns.tailBoundUs(listOf(3_000_000L)) == Long.MAX_VALUE, "one keyframe past the window was taken as the bound")

    // A window inside a single run - no keyframe of its own but the one it
    // starts on - is still bounded.
    val single = listOf(0L)
    check(ReverseRuns.runEndUs(single, 0, 4_000_000L) == 4_000_000L, "a one-run window was not bounded")

    println()
    if (problems.isEmpty()) println("PASS - every run of a reverse is fed to its own end, and the last to a fixed margin past the window")
    else { println("FAIL (${problems.size})"); problems.take(25).forEach { println("  - $it") }; exitProcess(1) }
}
