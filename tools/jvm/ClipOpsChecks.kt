import android.net.Uri
import com.squish.app.media.ExportPlan
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transform
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.VoiceEffect
import com.squish.app.timeline.mirrored
import com.squish.app.timeline.replacementNeedsMs
import com.squish.app.timeline.turnedAspect
import com.squish.app.timeline.withAttributesPasted
import com.squish.app.timeline.withClipReplaced
import com.squish.app.timeline.withClipReversed
import com.squish.app.timeline.withClipUnreversed
import com.squish.app.timeline.withClipsMoved
import com.squish.app.timeline.withClipsRemoved
import com.squish.app.timeline.withClipsReordered
import com.squish.app.timeline.withFrozenFrame
import com.squish.app.timeline.withSelectionToggled
import kotlin.math.abs
import kotlin.system.exitProcess

// The clip operations of docs/ROADMAP.md batch B11, executed: a freeze frame
// cut into a shot, a file replaced under a clip, a clip reversed and put back,
// a mirror and a turn agreeing between the preview and the file, attributes
// copied and pasted, and several clips selected, deleted and carried at once.

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun video(
    id: String, span: Long, start: Long = 0, srcIn: Long = 0, layer: Int = 0,
    ramp: SpeedRamp = SpeedRamp(), keys: List<Keyframe> = emptyList(), uri: String = "content://$id"
) = Clip(
    id = id, kind = ClipKind.Video, uri = Uri.parse(uri), label = id, sourceInMs = srcIn, sourceOutMs = srcIn + span,
    timelineStartMs = start, sourceDurationMs = 60_000, speedRamp = ramp, layer = layer, keyframes = keys
)

fun audio(id: String, span: Long, start: Long = 0) = Clip(
    id = id, kind = ClipKind.Audio, uri = Uri.parse("content://$id"), label = id, sourceInMs = 0, sourceOutMs = span,
    timelineStartMs = start, sourceDurationMs = 60_000
)

fun TimelineState.byId(id: String) = clips.first { it.id == id }
fun TimelineState.main() = baseVideoClips.map { it.id }

fun butted(state: TimelineState, what: String) {
    var cursor = 0L
    for (clip in state.baseVideoClips) {
        check(clip.timelineStartMs == cursor, "$what: ${clip.id} starts at ${clip.timelineStartMs}, not $cursor")
        cursor = clip.timelineEndMs
    }
}

fun main() {
    val still = Clip(kind = ClipKind.Video, uri = Uri.parse("file:///data/stills/freeze_1.mp4"), label = "Freeze",
        sourceInMs = 0, sourceOutMs = 3_000, timelineStartMs = 0, sourceDurationMs = 10_000)

    // --- Freeze frame: the shot cut round the still, the track closing up. ----------
    run {
        val a = video("a", 10_000).copy(mirrored = true, quarterTurns = 1, volume = 0.5f)
        val b = video("b", 5_000, start = 10_000)
        val state = TimelineState(clips = listOf(a, b)).withFrozenFrame("a", 4_000, still)
        check(state.baseVideoClips.size == 4, "freeze: ${state.baseVideoClips.size} shots on the track")
        val order = state.main()
        check(order[0] == "a" && order[1] == still.id && order[3] == "b", "freeze: order $order")
        val head = state.byId("a")
        val frozen = state.byId(still.id)
        val tail = state.baseVideoClips[2]
        check(head.sourceOutMs == 4_000L && head.durationMs == 4_000L, "freeze: head is ${head.sourceOutMs}")
        check(frozen.timelineStartMs == 4_000L && frozen.durationMs == 3_000L, "freeze: still at ${frozen.timelineStartMs} for ${frozen.durationMs}")
        check(tail.sourceInMs == 4_000L && tail.timelineStartMs == 7_000L, "freeze: tail at ${tail.timelineStartMs} from ${tail.sourceInMs}")
        // The halves add up to the shot, so b moves along by the still alone.
        check(state.byId("b").timelineStartMs == 13_000L, "freeze: b moved to ${state.byId("b").timelineStartMs}, not 13000")
        butted(state, "freeze")
        check(frozen.mirrored && frozen.quarterTurns == 1 && frozen.volume == 0.5f, "freeze: the still did not take the shot's mirror, turn and level")
        check(state.selectedClipId == still.id, "freeze: the still is not selected")
        check(frozen.speedRamp == SpeedRamp() && frozen.keyframes.isEmpty(), "freeze: the still carries a curve or keys")

        // Within the margin of an end: no sliver, the still goes beside the whole shot.
        val nearStart = TimelineState(clips = listOf(a, b)).withFrozenFrame("a", 100, still)
        check(nearStart.main() == listOf(still.id, "a", "b"), "freeze near the start: ${nearStart.main()}")
        check(nearStart.byId("a").sourceSpanMs == 10_000L, "freeze near the start cut the shot")
        val nearEnd = TimelineState(clips = listOf(a, b)).withFrozenFrame("a", 9_950, still)
        check(nearEnd.main() == listOf("a", still.id, "b"), "freeze near the end: ${nearEnd.main()}")
        butted(nearEnd, "freeze near the end")

        // The placement at that moment, still: a push-in frozen half-way is held at 1.09.
        val push = listOf(Keyframe(0, Transform(scale = 1f), KeyframeEasing.Linear), Keyframe(10_000, Transform(scale = 1.18f), KeyframeEasing.Linear))
        val moving = TimelineState(clips = listOf(a.copy(keyframes = push), b)).withFrozenFrame("a", 5_000, still)
        val held = moving.byId(still.id)
        check(abs(held.scale - 1.09f) < 0.01f && held.keyframes.isEmpty(), "freeze of a push-in: scale ${held.scale}")

        // A retimed shot: the frame under the playhead is the one frozen, and the halves add up.
        val slow = video("s", 10_000, ramp = SpeedRamp.flat(0.5f))
        val retimed = TimelineState(clips = listOf(slow)).withFrozenFrame("s", 10_000, still)
        check(retimed.baseVideoClips.size == 3, "freeze on a slowed shot: ${retimed.baseVideoClips.size} pieces")
        check(retimed.byId("s").sourceOutMs == 5_000L, "freeze on a slowed shot: head to ${retimed.byId("s").sourceOutMs}")
        butted(retimed, "freeze on a slowed shot")

        // Off the shot: nothing.
        check(TimelineState(clips = listOf(a, b)).withFrozenFrame("a", 12_000, still).clips.size == 2, "a freeze off the shot did something")
    }

    // --- Freeze on an overlay row: the tail moves along, or there is no room. ------------
    run {
        val base = video("base", 30_000)
        val o = video("o", 6_000, start = 2_000, layer = 1)
        val free = TimelineState(clips = listOf(base, o)).withFrozenFrame("o", 5_000, still)
        val row = free.clips.filter { it.layer == 1 }.sortedBy { it.timelineStartMs }
        check(row.size == 3, "overlay freeze: ${row.size} on the row")
        check(row[0].timelineEndMs == 5_000L && row[1].timelineStartMs == 5_000L && row[2].timelineStartMs == 8_000L,
            "overlay freeze: ${row.map { it.timelineStartMs to it.timelineEndMs }}")
        check(row.all { it.layer == 1 }, "overlay freeze changed rows")
        check(free.byId("base").durationMs == 30_000L, "overlay freeze touched the main track")

        // Something in the way on the row: unchanged.
        val wall = video("w", 2_000, start = 8_500, layer = 1)
        val blocked = TimelineState(clips = listOf(base, o, wall)).withFrozenFrame("o", 5_000, still)
        check(blocked.clips.size == 3 && blocked.clips.none { it.id == still.id }, "overlay freeze went through a wall")
    }

    // --- Replace: the window, place, speed and keys stay; the footage changes. ---------
    run {
        val keys = listOf(Keyframe(0, Transform(scale = 1f)), Keyframe(4_000, Transform(scale = 1.2f)))
        val a = video("a", 8_000, srcIn = 2_000, ramp = SpeedRamp.flat(2f), keys = keys)
            .copy(mirrored = true, opacity = 0.8f, stabilizer = listOf(Keyframe(0, Transform(scale = 1.05f))))
        val b = video("b", 5_000, start = a.durationMs)
        val before = TimelineState(clips = listOf(a, b))
        check(a.replacementNeedsMs() == 8_000L, "a 2x clip of 8 s needs ${a.replacementNeedsMs()} of file")
        val after = before.withClipReplaced("a", Uri.parse("content://new"), fileMs = 20_000, sourceInMs = 5_000, label = "new")
        val r = after.byId("a")
        check(r.uri == Uri.parse("content://new") && r.label == "new", "replace: file not changed")
        check(r.sourceInMs == 5_000L && r.sourceOutMs == 13_000L && r.sourceDurationMs == 20_000L, "replace: window ${r.sourceInMs}..${r.sourceOutMs}")
        check(r.durationMs == a.durationMs && r.timelineStartMs == 0L, "replace: length or place changed")
        check(r.speedRamp == a.speedRamp && r.keyframes == keys && r.mirrored && r.opacity == 0.8f, "replace: settings lost")
        check(r.stabilizer.isEmpty(), "replace: the old footage's stabilizer was kept")
        check(after.byId("b").timelineStartMs == b.timelineStartMs, "replace moved the next shot")
        // Too short: unchanged. And an in-point past the end is pulled back.
        check(before.withClipReplaced("a", Uri.parse("content://short"), 6_000, 0, "short") == before, "replace with a short file went in")
        val late = before.withClipReplaced("a", Uri.parse("content://new"), 20_000, 19_000, "new").byId("a")
        check(late.sourceInMs == 12_000L && late.sourceOutMs == 20_000L, "replace: an in-point past the end gave ${late.sourceInMs}..${late.sourceOutMs}")
    }

    // --- Reverse: the render under the clip, and the original back. -------------------
    run {
        val ramp = SpeedRamp(listOf(SpeedPoint(0, 1f), SpeedPoint(6_000, 1f), SpeedPoint(8_000, 0.35f)))
        val a = video("a", 8_000, srcIn = 2_000, ramp = ramp).copy(fadeInMs = 500, fadeOutMs = 1_500)
        val rendered = Uri.parse("file:///data/reversed/r.mp4")
        val rev = a.reversed(rendered, renderedMs = 8_000)
        check(rev.uri == rendered && rev.isReversed, "reverse: not on the render")
        check(rev.sourceInMs == 0L && rev.sourceOutMs == 8_000L && rev.sourceDurationMs == 8_000L, "reverse: window ${rev.sourceInMs}..${rev.sourceOutMs}")
        // A mirrored curve is stepped from the other end: within a couple of
        // steps of the length it had, and exactly that length once put back.
        check(abs(rev.durationMs - a.durationMs) <= 2 * SpeedRamp.STEP_MS, "reverse changed the length: ${rev.durationMs} vs ${a.durationMs}")
        check(rev.unreversed().durationMs == a.durationMs, "reverse and back changed the length")
        check(video("flat", 8_000, srcIn = 2_000).reversed(rendered, 8_000).durationMs == 8_000L, "reversing an unramped clip changed its length")
        // On the main track the shots after move with the new length.
        val follower = video("f", 4_000, start = a.durationMs)
        val track = TimelineState(clips = listOf(a, follower)).withClipReversed("a", rendered, 8_000, 2_000, 10_000)
        check(track.byId("a").isReversed && track.byId("f").timelineStartMs == track.byId("a").timelineEndMs, "reverse on the track: f at ${track.byId("f").timelineStartMs}")
        butted(track, "reverse on the track")
        val put = track.withClipUnreversed("a")
        check(!put.byId("a").isReversed && put.byId("f").timelineStartMs == a.durationMs, "unreverse on the track: f at ${put.byId("f").timelineStartMs}")
        butted(put, "unreverse on the track")
        check(rev.fadeInMs == 1_500L && rev.fadeOutMs == 500L, "reverse did not swap the fades")
        // The slow-out is a slow-in now.
        check(rev.speedRamp.speedAt(0) < 0.4f && rev.speedRamp.speedAt(8_000) == 1f, "reverse: the curve was not mirrored: ${rev.speedRamp.ordered}")
        check(rev.reversedFrom?.uri == a.uri && rev.reversedFrom?.sourceInMs == 2_000L && rev.reversedFrom?.sourceOutMs == 10_000L,
            "reverse: the origin ${rev.reversedFrom}")
        val back = rev.unreversed()
        check(back.uri == a.uri && back.sourceInMs == 2_000L && back.sourceOutMs == 10_000L && !back.isReversed, "unreverse: ${back.sourceInMs}..${back.sourceOutMs}")
        check(back.speedRamp.speedAt(8_000) == ramp.speedAt(8_000) && back.fadeInMs == 500L, "unreverse: curve or fades not put back")
        check(back.sourceDurationMs == a.sourceDurationMs, "unreverse: file length ${back.sourceDurationMs}")

        // Trimmed while reversed: the original window is the frames still shown.
        val trimmed = rev.copy(sourceInMs = 1_000, sourceOutMs = 5_000)
        val backTrimmed = trimmed.unreversed()
        check(backTrimmed.sourceInMs == 5_000L && backTrimmed.sourceOutMs == 9_000L, "unreverse after a trim: ${backTrimmed.sourceInMs}..${backTrimmed.sourceOutMs}")

        // Trimmed while the render ran: the render covers the old window, the clip keeps its frames.
        val meanwhile = a.copy(sourceInMs = 3_000, sourceOutMs = 7_000)
        val landed = meanwhile.reversed(rendered, 8_000, renderedInMs = 2_000, renderedOutMs = 10_000)
        check(landed.sourceInMs == 3_000L && landed.sourceOutMs == 7_000L, "reverse after a trim: ${landed.sourceInMs}..${landed.sourceOutMs}")
        check(landed.unreversed().sourceInMs == 3_000L && landed.unreversed().sourceOutMs == 7_000L, "reverse after a trim does not round-trip")

        // A flat curve is its own mirror; a render a frame short still fits.
        check(SpeedRamp.flat(2f).mirrored(5_000) == SpeedRamp.flat(2f), "a flat curve changed when mirrored")
        val short = a.reversed(rendered, renderedMs = 7_980)
        check(short.sourceOutMs == 7_980L && short.sourceInMs == 0L, "a render a frame short: ${short.sourceInMs}..${short.sourceOutMs}")
    }

    // --- Mirror and turn: what the preview turns and what the file turns agree. ---------
    run {
        val a = video("a", 1_000)
        check(a.withQuarterTurn().quarterTurns == 1 && a.withQuarterTurn().withQuarterTurn().withQuarterTurn().withQuarterTurn().quarterTurns == 0, "four turns are not none")
        check(a.withMirrorToggled().mirrored && a.withMirrorToggled().withMirrorToggled() == a, "mirror twice is not none")
        check(!a.isQuarterTurned && a.withQuarterTurn().isQuarterTurned && !a.withQuarterTurn().withQuarterTurn().isQuarterTurned, "on its side is wrong")
        // The preview turns the view clockwise; Media3 turns counterclockwise; the two are the same turn.
        for (q in 0..3) {
            val screen = ExportPlan.screenTurnDegrees(q)
            val file = ExportPlan.turnDegrees(q)
            check(((screen + file) % 360f + 360f) % 360f == 0f, "turn $q: screen $screen and file $file are not the same turn")
        }
        check(ExportPlan.screenTurnDegrees(1) == 90f && ExportPlan.turnDegrees(1) == 270f, "one turn: ${ExportPlan.screenTurnDegrees(1)} / ${ExportPlan.turnDegrees(1)}")
        check(ExportPlan.turnDegrees(0) == 0f && ExportPlan.turnDegrees(2) == 180f, "no turn or a half turn is wrong")
        // The shape the picture is seen in.
        check(turnedAspect(16f / 9f, 1) == 9f / 16f && turnedAspect(16f / 9f, 2) == 16f / 9f, "turned aspect")
        check(turnedAspect(null, 1) == null && turnedAspect(16f / 9f, 0) == 16f / 9f, "turned aspect of nothing, or no turn")
    }

    // --- Copy and paste attributes: settings travel, footage and place do not. --------
    run {
        val keys = listOf(Keyframe(0, Transform(scale = 1f)), Keyframe(10_000, Transform(scale = 1.2f)))
        val src = video("src", 10_000, ramp = SpeedRamp(listOf(SpeedPoint(0, 1f), SpeedPoint(10_000, 0.5f))), keys = keys)
            .copy(volume = 0.4f, fadeInMs = 300, fadeOutMs = 600, voice = VoiceEffect.Robot, opacity = 0.7f,
                mirrored = true, quarterTurns = 2, offsetXFraction = 0.2f)
        val attrs = src.attributes
        val dst = video("dst", 4_000, start = src.durationMs, srcIn = 1_000)
        val after = TimelineState(clips = listOf(src, dst)).withAttributesPasted("dst", attrs)
        val d = after.byId("dst")
        check(d.uri == dst.uri && d.sourceInMs == 1_000L && d.sourceSpanMs == 4_000L, "paste changed the footage or window")
        check(d.volume == 0.4f && d.fadeInMs == 300L && d.voice == VoiceEffect.Robot && d.opacity == 0.7f, "paste: sound or opacity not carried")
        check(d.mirrored && d.quarterTurns == 2 && d.offsetXFraction == 0.2f, "paste: mirror, turn or placement not carried")
        // Shapes, refitted: the curve's end point at the new span, the keys at the
        // same share of the new played length (the source's second key sat at
        // 10 s of its played 13.8 s).
        check(d.speedRamp.ordered.last().atMs == 4_000L && abs(d.speedRamp.speedAt(4_000) - 0.5f) < 0.01f, "paste: the curve was not refitted: ${d.speedRamp.ordered}")
        val expectedKey = Math.round(10_000.0 * d.durationMs / src.durationMs)
        check(d.keyframes.last().atMs == expectedKey, "paste: the keys were not refitted: ${d.keyframes.map { it.atMs }} for ${d.durationMs}, wanted $expectedKey")
        check(d.durationMs > 4_000L, "paste: the curve did not retime the clip")
        // The retime kept the shot where it was and the track butted.
        check(after.byId("dst").timelineStartMs == src.durationMs, "paste moved the shot")
        butted(after, "paste")
        // A sound takes only what a sound has.
        val song = audio("song", 8_000, start = 1_000)
        val s = TimelineState(clips = listOf(src, song)).withAttributesPasted("song", attrs).byId("song")
        check(s.volume == 0.4f && s.voice == VoiceEffect.Robot && s.fadeOutMs == 600L, "paste onto a sound: level, voice or fade missing")
        check(s.timelineStartMs == 1_000L && s.uri == song.uri, "paste onto a sound moved or replaced it")
        check(s.speedRamp.ordered.last().atMs == 8_000L, "paste onto a sound: curve not refitted")
        // Nothing to change: nothing changes.
        check(TimelineState(clips = listOf(src)).withAttributesPasted("src", attrs) == TimelineState(clips = listOf(src)), "pasting a clip's own attributes changed it")
    }

    // --- Several selected: toggled, deleted as one, carried as one. ---------------------
    run {
        val a = video("a", 3_000)
        val b = video("b", 3_000, start = 3_000)
        val c = video("c", 3_000, start = 6_000)
        val d = video("d", 3_000, start = 9_000)
        val one = TimelineState(clips = listOf(a, b, c, d), selectedClipId = "b")
        val two = one.withSelectionToggled("d")
        check(two.selectedClipId == "b" && two.selectedIds == setOf("d") && two.allSelectedIds == setOf("b", "d"), "toggle: ${two.selectedClipId} + ${two.selectedIds}")
        check(two.isSelected("b") && two.isSelected("d") && !two.isSelected("a"), "toggle: isSelected")
        val leadGone = two.withSelectionToggled("b")
        check(leadGone.selectedClipId == "d" && leadGone.selectedIds.isEmpty(), "toggle off the lead: ${leadGone.selectedClipId} + ${leadGone.selectedIds}")
        check(leadGone.withSelectionToggled("d").selectedClipId == null, "toggling the last off left a selection")

        // Delete: the track closes up once over both.
        val cut = two.withClipsRemoved(setOf("b", "d"))
        check(cut.main() == listOf("a", "c") && cut.byId("c").timelineStartMs == 3_000L, "delete two: ${cut.main()} c at ${cut.byId("c").timelineStartMs}")
        check(cut.selectedClipId == null && cut.selectedIds.isEmpty(), "delete two left a selection")
        butted(cut, "delete two")

        // Carry: b and d go together to the front, in their order, b where it is dropped.
        val front = two.withClipsReordered(setOf("b", "d"), anchorId = "b", index = 0)
        check(front.main() == listOf("b", "d", "a", "c"), "carry two to the front: ${front.main()}")
        butted(front, "carry two to the front")
        val toEnd = two.withClipsReordered(setOf("b", "d"), anchorId = "b", index = 2)
        check(toEnd.main() == listOf("a", "c", "b", "d"), "carry two to the end: ${toEnd.main()}")
        // The anchor lands where it is dropped: d dropped at index 1 among {a, c} puts b before it.
        val byD = two.withClipsReordered(setOf("b", "d"), anchorId = "d", index = 1)
        check(byD.main() == listOf("b", "d", "a", "c") || byD.main() == listOf("a", "b", "d", "c"), "carry by d: ${byD.main()}")
        check(byD.main().indexOf("d") == byD.main().indexOf("b") + 1, "the group came apart: ${byD.main()}")
        // One alone is an ordinary reorder.
        check(one.withClipsReordered(setOf("b"), "b", 3).main() == listOf("a", "c", "d", "b"), "one alone: ${one.withClipsReordered(setOf("b"), "b", 3).main()}")

        // Sounds and overlays slide together, keeping their spacing, never before zero.
        val base = video("base", 30_000)
        val s1 = audio("s1", 2_000, start = 1_000)
        val s2 = audio("s2", 2_000, start = 5_000)
        val o1 = video("o1", 2_000, start = 2_000, layer = 1)
        val wall = video("wall", 2_000, start = 8_000, layer = 1)
        val group = setOf("s1", "s2", "o1")
        val state = TimelineState(clips = listOf(base, s1, s2, o1, wall))
        val slid = state.withClipsMoved(group, 1_500)
        check(slid.byId("s1").timelineStartMs == 2_500L && slid.byId("s2").timelineStartMs == 6_500L && slid.byId("o1").timelineStartMs == 3_500L,
            "slide: ${group.map { slid.byId(it).timelineStartMs }}")
        check(slid.byId("base").timelineStartMs == 0L && slid.byId("wall").timelineStartMs == 8_000L, "slide moved what was not selected")
        val floor = state.withClipsMoved(group, -5_000)
        check(floor.byId("s1").timelineStartMs == 0L && floor.byId("s2").timelineStartMs == 4_000L && floor.byId("o1").timelineStartMs == 1_000L,
            "slide to the floor: ${group.map { floor.byId(it).timelineStartMs }}")
        // The overlay would land on the wall at 8000: the whole group stops
        // against it, spacing kept - the sounds do not go on without it.
        val stopped = state.withClipsMoved(group, 5_000)
        check(stopped.byId("o1").timelineEndMs == 8_000L, "slide into a wall: o1 ends at ${stopped.byId("o1").timelineEndMs}")
        check(stopped.byId("s1").timelineStartMs == 5_000L && stopped.byId("s2").timelineStartMs == 9_000L, "slide into a wall: the sounds went on without the overlay")
        // Far enough that the overlay fits past the wall, the group jumps past it, as one drag does.
        val past = state.withClipsMoved(group, 10_000)
        check(past.byId("o1").timelineStartMs == 12_000L && past.byId("s1").timelineStartMs == 11_000L, "slide past a wall: ${past.byId("o1").timelineStartMs}")
        // Main-track shots are not slid; words are the editor's.
        check(state.withClipsMoved(setOf("base"), 1_000) == state, "a main-track shot was slid")
    }

    println("clip ops: freeze, replace, reverse, mirror and turn, attributes, several at once")
    if (problems.isEmpty()) println("PASS - the clip operations do what B11 says")
    else { println("FAIL (${problems.size})"); problems.take(40).forEach { println("  - $it") }; exitProcess(1) }
}
