import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import kotlin.random.Random
import kotlin.system.exitProcess

// TimelineState's sorted lists and length, kept per clip list (28 Oct). On the
// moto, scrubbing a 200-shot edit spent a median 26 ms a frame in the app: the
// strip and the preview read baseVideoClips, overlayClips, audioClips and
// durationMs many times a frame, and each read filtered and sorted every clip.
// The playhead is in the state, so a scrub makes a new state each frame with
// the same clips - the answers are kept against the clip list itself.

private val failures = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) failures += msg }

private fun clip(r: Random, i: Int): Clip {
    val kind = listOf(ClipKind.Video, ClipKind.Video, ClipKind.Audio, ClipKind.Text)[r.nextInt(4)]
    val span = 200L + r.nextInt(4000)
    val ramp = if (r.nextInt(5) == 0) SpeedRamp(listOf(SpeedPoint(0L, 0.5f), SpeedPoint(span, 2f))) else SpeedRamp()
    return Clip(
        id = "c$i", kind = kind, label = "c$i", sourceInMs = 0, sourceOutMs = span,
        timelineStartMs = r.nextLong(0, 60_000), sourceDurationMs = 60_000,
        layer = if (kind == ClipKind.Text) 0 else r.nextInt(4), speedRamp = ramp
    )
}

fun main() {
    val r = Random(7)
    repeat(20) { round ->
        val clips = (0 until 1 + r.nextInt(200)).map { clip(r, it) }
        val s = TimelineState(clips = clips, playheadMs = r.nextLong(0, 60_000))
        // The same answers the getters always gave.
        val video = clips.filter { it.kind == ClipKind.Video }.sortedBy { it.timelineStartMs }
        check(s.videoClips == video, "round $round: videoClips changed")
        check(s.baseVideoClips == clips.filter { it.kind == ClipKind.Video && it.layer == 0 }.sortedBy { it.timelineStartMs }, "round $round: baseVideoClips changed")
        check(s.overlayClips == clips.filter { it.kind == ClipKind.Video && it.layer > 0 }.sortedBy { it.timelineStartMs }, "round $round: overlayClips changed")
        check(s.layerCount == (video.maxOfOrNull { it.layer } ?: 0), "round $round: layerCount changed")
        check(s.audioClips == clips.filter { it.kind == ClipKind.Audio }.sortedBy { it.timelineStartMs }, "round $round: audioClips changed")
        check(s.textClips == clips.filter { it.kind == ClipKind.Text }.sortedBy { it.timelineStartMs }, "round $round: textClips changed")
        check(s.durationMs == (clips.maxOfOrNull { it.timelineEndMs } ?: 0L), "round $round: durationMs changed")

        // Kept: read twice, and from a state that only moved its playhead, the
        // same list - not a new filter and sort each time.
        val scrubbed = s.copy(playheadMs = s.playheadMs + 33)
        check(s.baseVideoClips === s.baseVideoClips && scrubbed.baseVideoClips === s.baseVideoClips,
            "round $round: baseVideoClips is worked out again on every read")
        check(scrubbed.audioClips === s.audioClips && scrubbed.overlayClips === s.overlayClips,
            "round $round: the sorted lists are worked out again for a state with the same clips")

        // A new clip list is new answers, never the old one's.
        val moved = s.copy(clips = clips.map { if (it.kind == ClipKind.Video) it.copy(timelineStartMs = it.timelineStartMs + 1000) else it })
        check(moved.durationMs != s.durationMs || video.isEmpty() || clips.maxByOrNull { it.timelineEndMs }!!.kind != ClipKind.Video,
            "round $round: a moved edit kept the old length")
        check(moved.baseVideoClips.map { it.timelineStartMs } == s.baseVideoClips.map { it.timelineStartMs + 1000 },
            "round $round: a moved edit kept the old main track")
        // And going back to the first list answers for it, not for the second.
        check(s.baseVideoClips.map { it.timelineStartMs } == clips.filter { it.kind == ClipKind.Video && it.layer == 0 }.sortedBy { it.timelineStartMs }.map { it.timelineStartMs },
            "round $round: the first edit answered with the second's clips")
    }
    if (failures.isEmpty()) println("TimelineDerivedChecks: all checks passed")
    else { failures.distinct().take(12).forEach { println("FAIL: $it") }; exitProcess(1) }
}
