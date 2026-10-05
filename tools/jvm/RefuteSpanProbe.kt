import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.withSpanRemoved

private fun video(id: String, span: Long, start: Long = 0, layer: Int = 0) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000, layer = layer
)

private fun audio(id: String, span: Long, start: Long = 0, layer: Int = 0) = Clip(
    id = id, kind = ClipKind.Audio, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000, layer = layer
)

private fun dump(title: String, s: TimelineState) {
    println("-- $title")
    s.clips.sortedWith(compareBy({ it.kind.name }, { it.layer }, { it.timelineStartMs })).forEach {
        println("   ${it.kind} L${it.layer} ${it.label.padEnd(6)} ${it.timelineStartMs}..${it.timelineEndMs}  src ${it.sourceInMs}..${it.sourceOutMs}")
    }
}

fun main() {
    // Claim A: a sound starting 100 ms before the stretch - the head cut is refused.
    run {
        val st = TimelineState(clips = listOf(
            video("m", 20_000),
            audio("song", 20_000),
            audio("fx", 4_000, start = 1_900, layer = 1)
        ))
        dump("A before", st)
        dump("A after withSpanRemoved(2000,5000)", st.withSpanRemoved(2_000, 5_000))
    }

    // Claim B: a sound ending 100 ms after the stretch - the tail cut is refused.
    run {
        val st = TimelineState(clips = listOf(
            video("m", 20_000),
            audio("vo", 5_000, start = 1_000, layer = 1),
            audio("late", 2_000, start = 6_000, layer = 1)
        ))
        dump("B before", st)
        dump("B after withSpanRemoved(2000,5100)", st.withSpanRemoved(2_000, 5_100))
    }

    // Claim C: the overlay variant they say is "the same".
    run {
        val st = TimelineState(clips = listOf(
            video("m", 20_000),
            video("pip1", 4_100, start = 1_000, layer = 1),
            video("pip2", 2_000, start = 6_000, layer = 1)
        ))
        dump("C before", st)
        dump("C after withSpanRemoved(2000,5000)", st.withSpanRemoved(2_000, 5_000))
    }

    // Claim D: a sound shorter than MIN_CLIP_MS*2 cannot be cut at all.
    run {
        val st = TimelineState(clips = listOf(
            video("m", 20_000),
            audio("tick", 300, start = 1_900, layer = 1),
            audio("late", 2_000, start = 6_000, layer = 1)
        ))
        dump("D before", st)
        dump("D after withSpanRemoved(2000,5000)", st.withSpanRemoved(2_000, 5_000))
    }

    // What the strip would draw for A: rows are by overlap, not by layer.
    run {
        val after = TimelineState(clips = listOf(
            video("m", 20_000),
            audio("song", 20_000),
            audio("fx", 4_000, start = 1_900, layer = 1)
        )).withSpanRemoved(2_000, 5_000)
        val sounds = after.clips.filter { it.kind == ClipKind.Audio }
        val items = sounds.map { com.squish.app.timeline.LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) }
        val rows = com.squish.app.timeline.TimelineLanes.rows(items)
        println("-- A sound rows as the strip lays them: " + sounds.map { "${it.label}@row${rows[it.id]} ${it.timelineStartMs}..${it.timelineEndMs}" })
    }
}
