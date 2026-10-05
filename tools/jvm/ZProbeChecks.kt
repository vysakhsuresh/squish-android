import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.withTransition
import com.squish.app.timeline.withPlayhead
import com.squish.app.timeline.withSplitAllTracks
import com.squish.app.timeline.withSpanRemoved

private fun video(id: String, span: Long, start: Long = 0, layer: Int = 0) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000, layer = layer
)

private fun audio(id: String, span: Long, start: Long = 0) = Clip(
    id = id, kind = ClipKind.Audio, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000
)

private fun state(vararg clips: Clip) = clips.fold(TimelineState()) { s, c -> s.withClipAdded(c) }

private fun dump(tag: String, s: TimelineState) {
    println("$tag:")
    s.clips.sortedWith(compareBy({ it.kind.name }, { it.timelineStartMs })).forEach {
        println("   ${it.kind} ${it.id} layer=${it.layer} ${it.timelineStartMs}..${it.timelineEndMs} dur=${it.durationMs} tin=${it.transitionIn.type}/${it.transitionIn.durationMs}")
    }
    println("   picture end = ${s.baseVideoClips.maxOfOrNull { it.timelineEndMs } ?: 0L}")
}

fun main() {
    // --- The reviewer's dissolve case -------------------------------------
    run {
        var s = state(video("a", 4_000), video("b", 4_000, start = 4_000), audio("song", 6_500))
        s = s.withTransition("b", Transition(TransitionType.CrossFade, 1_500))
        dump("dissolve, before", s)
        val after = s.withSpanRemoved(3_000, 5_800)
        dump("dissolve, after withSpanRemoved(3000,5800)", after)
    }

    // --- The reviewer's gap case ------------------------------------------
    run {
        val s = state(video("a", 2_000), video("b", 4_000, start = 5_000), audio("song", 9_000))
        dump("gap, before", s)
        val after = s.withSpanRemoved(3_000, 4_000)
        dump("gap, after withSpanRemoved(3000,4000)", after)
    }

    // --- A split alone across a transition --------------------------------
    run {
        var s = state(video("a", 4_000), video("b", 4_000, start = 4_000), audio("song", 6_500))
        s = s.withTransition("b", Transition(TransitionType.CrossFade, 1_500))
        val cut = s.withPlayhead(3_000).withSplitAllTracks()
        dump("split at 3000 alone", cut)
        val cut2 = cut.withPlayhead(5_800).withSplitAllTracks()
        dump("then split at 5800", cut2)
    }
}
