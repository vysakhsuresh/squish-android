import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.LaneItem
import com.squish.app.timeline.TimelineLanes
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.withSpanRemoved

private fun video(id: String, span: Long, start: Long = 0, layer: Int = 0) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000, layer = layer
)

private fun audio(id: String, span: Long, start: Long = 0, layer: Int = 0) = Clip(
    id = id, kind = ClipKind.Audio, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000, layer = layer
)

private fun dump(tag: String, s: TimelineState) {
    println("--- $tag")
    s.clips.sortedWith(compareBy({ it.kind.name }, { it.layer }, { it.timelineStartMs })).forEach {
        println("  ${it.kind} L${it.layer} ${it.label.padEnd(5)} ${it.timelineStartMs}..${it.timelineEndMs}  src ${it.sourceInMs}..${it.sourceOutMs}")
    }
    val auds = s.clips.filter { it.kind == ClipKind.Audio }
    val rows = TimelineLanes.rows(auds.map { LaneItem(it.id, it.timelineStartMs, it.timelineEndMs, it.layer) })
    println("  sound rows drawn: " + auds.map { "${it.label}->r${rows[it.id]}" })
    val clash = auds.any { x -> auds.any { y -> x.id != y.id && rows[x.id] == rows[y.id] && x.timelineStartMs < y.timelineEndMs && y.timelineStartMs < x.timelineEndMs } }
    println("  two sounds drawn on one row? $clash")
}

fun main() {
    // Claim's audio scenario: fx starts 100 ms before the stretch.
    run {
        val st = TimelineState(clips = listOf(
            video("main", 20_000),
            audio("song", 20_000),
            audio("fx", 4_000, start = 1_900, layer = 1)
        ))
        dump("A before: fx 1900..5900, delete 2000..5000", st)
        dump("A after", st.withSpanRemoved(2_000, 5_000))
    }
    // Tail-edge refusal: fx ends 100 ms after the stretch.
    run {
        val st = TimelineState(clips = listOf(
            video("main", 20_000),
            audio("song", 20_000),
            audio("fx", 4_100, start = 1_000, layer = 1)
        ))
        dump("B before: fx 1000..5100, delete 2000..5000", st)
        dump("B after", st.withSpanRemoved(2_000, 5_000))
    }
    // Claim's overlay variant.
    run {
        val st = TimelineState(clips = listOf(
            video("main", 20_000),
            video("pip1", 4_100, start = 1_000, layer = 1),
            video("pip2", 2_000, start = 6_000, layer = 1)
        ))
        dump("C before: pip1 1000..5100, pip2 6000..8000 both L1, delete 2000..5000", st)
        val after = st.withSpanRemoved(2_000, 5_000)
        dump("C after", after)
        val ov = after.clips.filter { it.kind == ClipKind.Video && it.layer > 0 }
        val clash = ov.any { x -> ov.any { y -> x.id != y.id && x.layer == y.layer && x.timelineStartMs < y.timelineEndMs && y.timelineStartMs < x.timelineEndMs } }
        println("  two overlays on one layer? $clash")
    }
    // Head-edge refusal on the MAIN track: the stretch starts 100 ms into a shot.
    run {
        val st = TimelineState(clips = listOf(
            video("a", 5_000),
            video("b", 5_000, start = 5_000),
            video("c", 5_000, start = 10_000),
            audio("song", 15_000)
        ))
        dump("D before: shots 0..5k, 5k..10k, 10k..15k; delete a word at 5100..6000", st)
        dump("D after", st.withSpanRemoved(5_100, 6_000))
        // Control: the same word 300 ms in instead of 100 ms in.
        dump("D control after (5300..6000)", st.withSpanRemoved(5_300, 6_000))
    }
}
