import android.net.Uri
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.rippleVideo
import com.squish.app.timeline.withFrozenFrame

fun video(id: String, span: Long, start: Long = 0) = Clip(
    id = id, kind = ClipKind.Video, uri = Uri.parse("content://$id"), label = id,
    sourceInMs = 0, sourceOutMs = span, timelineStartMs = start, sourceDurationMs = 60_000,
    speedRamp = SpeedRamp()
)

fun main() {
    val still = Clip(
        kind = ClipKind.Video, uri = Uri.parse("file:///data/stills/freeze_1.mp4"), label = "Freeze",
        sourceInMs = 0, sourceOutMs = 3_000, timelineStartMs = 0, sourceDurationMs = 10_000
    )
    val x = video("x", 5_000)
    val a = video("a", 10_000, start = 5_000).copy(transitionIn = Transition(TransitionType.Dissolve, 600))
    val b = video("b", 5_000, start = 15_000)
    val before = TimelineState(clips = listOf(x, a, b)).rippleVideo()
    println("before: " + before.baseVideoClips.joinToString { "${it.id}@${it.timelineStartMs}..${it.timelineEndMs} tin=${it.transitionIn.type}/${it.transitionIn.durationMs}" })

    val aStart = before.clips.first { it.id == "a" }.timelineStartMs
    val after = before.withFrozenFrame("a", aStart + 100, still)
    println("after : " + after.baseVideoClips.joinToString { "${if (it.id == still.id) "FREEZE" else it.id}@${it.timelineStartMs}..${it.timelineEndMs} tin=${it.transitionIn.type}/${it.transitionIn.durationMs}" })

    val nearEnd = before.withFrozenFrame("a", before.clips.first { it.id == "a" }.timelineEndMs - 50, still)
    println("nearEnd: " + nearEnd.baseVideoClips.joinToString { "${if (it.id == still.id) "FREEZE" else it.id}@${it.timelineStartMs}..${it.timelineEndMs} tin=${it.transitionIn.type}/${it.transitionIn.durationMs}" })

    val mid = before.withFrozenFrame("a", before.clips.first { it.id == "a" }.timelineStartMs + 4_000, still)
    println("mid    : " + mid.baseVideoClips.joinToString { "${if (it.id == still.id) "FREEZE" else it.id}@${it.timelineStartMs}..${it.timelineEndMs} tin=${it.transitionIn.type}/${it.transitionIn.durationMs}" })
}
