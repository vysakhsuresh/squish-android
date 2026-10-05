import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineLanes
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.withSpanRemoved
import com.squish.app.timeline.withClipRetimed

private fun shot(id: String, span: Long, start: Long = 0, srcIn: Long = 0, layer: Int = 0, ramp: SpeedRamp = SpeedRamp(), file: Long = 600_000) =
    Clip(id = id, kind = ClipKind.Video, label = id, sourceInMs = srcIn, sourceOutMs = srcIn + span,
        timelineStartMs = start, sourceDurationMs = file, speedRamp = ramp, layer = layer)

private fun sound(id: String, span: Long, start: Long, row: Int = 0) = Clip(
    id = id, kind = ClipKind.Audio, label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 600_000, layer = row)

private fun state(vararg clips: Clip) = clips.fold(TimelineState()) { s, c -> s.withClipAdded(c) }
private val TimelineState.mainLength: Long get() = baseVideoClips.maxOfOrNull { it.timelineEndMs } ?: 0L

fun main() {
    // --- 1. span removal with a transition on the join inside the span -------
    run {
        val a = shot("a", 4_000)
        val b = shot("b", 4_000, start = 4_000).copy(transitionIn = Transition(TransitionType.Dissolve, 1_000))
        val st = state(a, b)
        println("with a dissolve: main = ${st.baseVideoClips.map { it.id to (it.timelineStartMs to it.timelineEndMs) }} length ${st.mainLength}")
        val song = sound("song", 20_000, 0)
        val st2 = st.withClipAdded(song)
        val after = st2.withSpanRemoved(5_000, 6_000)
        println("after removing 5000..6000 (1000 ms):")
        println("  main   ${after.baseVideoClips.map { it.id to (it.timelineStartMs to it.timelineEndMs) }} length ${after.mainLength}")
        println("  sounds ${after.audioClips.map { it.id to (it.timelineStartMs to it.timelineEndMs) }}")
        println("  picture shortened by ${st2.mainLength - after.mainLength} (span was 1000)")
    }

    // --- 2. span removal on a retimed (2x) shot -----------------------------
    run {
        val a = shot("a", 20_000, ramp = SpeedRamp.flat(2f))
        val st = state(a, sound("song", 20_000, 0))
        println("2x shot plays ${st.byIdOrNull("a")?.durationMs}")
        val after = st.withSpanRemoved(2_000, 4_000)
        println("after 2000..4000 on a 2x shot:")
        println("  main   ${after.baseVideoClips.map { it.id to (it.timelineStartMs to it.timelineEndMs) }} length ${after.mainLength}")
        println("  sounds ${after.audioClips.map { it.id to (it.timelineStartMs to it.timelineEndMs) }}")
    }

    // --- 3. span removal where the sound is on two rows ---------------------
    run {
        val st = state(shot("a", 10_000), sound("s1", 10_000, 0), sound("s2", 2_000, 3_000, row = 1))
        val after = st.withSpanRemoved(6_000, 8_000)
        println("two sound rows, span 6000..8000:")
        println("  main   ${after.baseVideoClips.map { it.id to (it.timelineStartMs to it.timelineEndMs) }}")
        println("  sounds ${after.audioClips.map { Triple(it.id, it.timelineStartMs, it.layer) }}")
    }

    // --- 4. placedRow vs the row rows() actually draws after the drop -------
    run {
        val items = listOf(
            com.squish.app.timeline.LaneItem("s1", 0, 5_000, 0),
            com.squish.app.timeline.LaneItem("s2", 0, 5_000, 1)
        )
        val shown = TimelineLanes.rows(items)
        val placed = TimelineLanes.placedRow(items, "s2", 2, 10_000)
        val prefs = TimelineLanes.preferencesAfterMove(items, "s2", 2, 10_000)
        val moved = items.map {
            if (it.id == "s2") it.copy(startMs = 10_000, endMs = 15_000, preferredRow = prefs.getValue(it.id))
            else it.copy(preferredRow = prefs.getValue(it.id))
        }
        println("shown $shown placedRow $placed prefs $prefs drawn-after ${TimelineLanes.rows(moved)}")
    }

    // --- 5. ruler tick budget -----------------------------------------------
    run {
        var worst = 0
        var worstAt = 0L
        for (v in 1_000L..3_000_000L step 137L) {
            val step = com.squish.app.timeline.TimelineSpan.rulerStepMs(v, 1_000)
            val t = com.squish.app.timeline.TimelineSpan.tickCount(v, step)
            if (t > worst) { worst = t; worstAt = v }
        }
        println("worst tick count $worst at ${worstAt}ms visible (cap ${com.squish.app.timeline.TimelineSpan.MAX_TICKS})")
    }

    // --- 6. rippleAfterRetime: a follower butted on the SAME clip twice -----
    run {
        val o1 = shot("o1", 2_000, start = 0, layer = 1)
        val s1 = sound("x1", 2_000, 2_000)
        val before = sound("b0", 2_000, 0)
        val clips = listOf(before, s1)
        val retimed = clips.map { if (it.id == "b0") it.copy(speedRamp = SpeedRamp.flat(0.5f)) else it }
        val out = TimelineLanes.rippleAfterRetime(retimed, before, 40)
        println("sound slowed 0.5x: ${out.map { it.id to (it.timelineStartMs to it.timelineEndMs) }}")
    }
}

private fun TimelineState.byIdOrNull(id: String) = clips.firstOrNull { it.id == id }
