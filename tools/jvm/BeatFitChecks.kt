import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withShotsFittedToBeats
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun shot(id: String, ms: Long) = Clip(id = id, kind = ClipKind.Video, uri = null, label = id, sourceInMs = 0, sourceOutMs = ms, timelineStartMs = 0, sourceDurationMs = ms)

fun main() {
    val laid = TimelineState(clips = listOf(shot("a", 3_300), shot("b", 3_300), shot("c", 3_300)))
        .let { s -> s.copy(clips = s.clips.mapIndexed { i, c -> c.copy(timelineStartMs = i * 3_300L) }) }
    val beats = (1..40).map { it * 500L }
    val fitted = laid.withShotsFittedToBeats(beats).baseVideoClips
    val ends = fitted.map { it.timelineEndMs }
    check(ends == listOf(3_000L, 6_000L, 9_000L), "the cuts did not land on beats: $ends")
    check(fitted.zipWithNext().all { (x, y) -> y.timelineStartMs == x.timelineEndMs }, "the shots were not butted")
    check(fitted.all { it.sourceInMs == 0L }, "a shot's head moved")

    // A shot shorter than any beat inside it is left.
    val short = TimelineState(clips = listOf(shot("a", 300))).withShotsFittedToBeats(beats).baseVideoClips
    check(short.first().durationMs == 300L, "a shot with no beat in it was changed")

    // A shot at 2x: its footage is taken from its own clock, so the cut still lands on a beat.
    val fast = shot("f", 6_600).copy(speedRamp = com.squish.app.timeline.SpeedRamp.flat(2f))
    val fastFit = TimelineState(clips = listOf(fast)).withShotsFittedToBeats(beats).baseVideoClips.first()
    check(fastFit.timelineEndMs == 3_000L, "a 2x shot did not end on the beat: ${fastFit.timelineEndMs}")

    // No beats, nothing changes.
    check(laid.withShotsFittedToBeats(emptyList()) == laid, "no beats changed the edit")

    transitionChecks()
    if (problems.isEmpty()) println("BeatFitChecks: all checks passed") else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}

fun transitionChecks() {
    // A (4 s) then B (4 s) with a 1 s dissolve into B, laid as the track lays them: B starts at 3 s.
    val a = shot("a", 4_000)
    val b = shot("b", 4_000).copy(timelineStartMs = 3_000, transitionIn = com.squish.app.timeline.Transition(com.squish.app.timeline.TransitionType.CrossFade, 1_000))
    val c = shot("c", 4_000).copy(timelineStartMs = 7_000)
    val beats = (1..40).map { it * 500L }
    val fitted = TimelineState(clips = listOf(a, b, c)).withShotsFittedToBeats(beats).baseVideoClips
    fitted.forEach { check(it.timelineEndMs % 500L == 0L, "a cut after a dissolve landed off the beat: ${it.id} ends ${it.timelineEndMs}") }
    // A gap an old draft kept stays, and the cut after it is still on a beat.
    val gapped = TimelineState(clips = listOf(shot("x", 3_300), shot("y", 3_300).copy(timelineStartMs = 3_600)))
    val g = gapped.withShotsFittedToBeats(beats).baseVideoClips
    check(g.all { it.timelineEndMs % 500L == 0L }, "a cut after a gap landed off the beat: ${g.map { it.timelineEndMs }}")
}
