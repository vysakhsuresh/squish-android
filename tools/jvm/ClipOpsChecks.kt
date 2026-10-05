import android.net.Uri
import com.squish.app.editor.ClipCrop
import com.squish.app.editor.CropRatio
import com.squish.app.editor.CropRect
import com.squish.app.media.ExportPlan
import com.squish.app.media.effects.Adjust
import com.squish.app.media.video.FrameMotion
import com.squish.app.media.video.MotionTrack
import com.squish.app.media.video.StabilizerMeasurement
import com.squish.app.media.video.StabilizerSolve
import com.squish.app.media.video.TrackSample
import com.squish.app.timeline.BackgroundRemoval
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipArrival
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.ClipLeaving
import com.squish.app.timeline.ClipLoop
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskKey
import com.squish.app.timeline.MaskShape
import com.squish.app.timeline.SpeedPoint
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.ValueKey
import com.squish.app.timeline.Transform
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.VoiceEffect
import com.squish.app.timeline.carriesSelection
import com.squish.app.timeline.groupMoveDelta
import com.squish.app.timeline.isRenderedStill
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
import com.squish.app.timeline.withSelectionJoined
import com.squish.app.timeline.withSelectionToggled
import com.squish.app.timeline.selectionAfterRestore
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
        // The shot's look, sliders and crop come along too: the frame is grabbed
        // from the file plain, so without them the still is not the frame seen.
        val graded = video("g", 10_000).copy(
            lookId = "cool", lookIntensity = 0.8f, adjust = Adjust(exposure = -0.2f), crop = ClipCrop(rect = CropRect(0.2f, 0f, 0.8f, 1f))
        )
        val g = TimelineState(clips = listOf(graded)).withFrozenFrame("g", 5_000, still).byId(still.id)
        check(g.lookId == "cool" && g.lookIntensity == 0.8f && g.adjust.exposure == -0.2f && g.crop == graded.crop, "freeze: the still did not take the shot's look, sliders and crop")
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
        // The stabilizer's measurement and a mask's track are written in the
        // *file's* clock, and the render has reversed that clock, so both turn
        // round with it. The curve and the fades above were asserted and these
        // two were not, which is the half of a reverse that only shows as a
        // shake correction fighting the picture or a cut-out walking away.
        run {
            // A window of 2,000..12,000 in the file, so the pivot a reverse
            // mirrors about is 12,000. Keys at 2,000 and 6,000 therefore land
            // at 10,000 and 6,000 - picked asymmetric on purpose, since a
            // symmetric pair looks identical whether it was mirrored or not.
            val steady = video("s", 10_000, srcIn = 2_000).copy(
                stabilizer = listOf(
                    Keyframe(2_000, Transform(offsetXFraction = 0.1f)),
                    Keyframe(6_000, Transform(offsetXFraction = 0.4f))
                ),
                mask = Mask(
                    keys = listOf(
                        MaskKey(2_000, Mask(centerXFraction = -0.5f)),
                        MaskKey(6_000, Mask(centerXFraction = 0.5f))
                    ),
                    track = MotionTrack(listOf(TrackSample(2_000, 0.1f, 0.5f), TrackSample(6_000, 0.9f, 0.5f)))
                )
            )
            val r = steady.reversed(rendered, renderedMs = 10_000)
            check(
                r.stabilizer.map { it.atMs } == listOf(6_000L, 10_000L),
                "reverse: the stabilizer's keys are at ${r.stabilizer.map { it.atMs }}, want 6000 and 10000"
            )
            check(
                r.stabilizer.last().transform.offsetXFraction == 0.1f,
                "reverse: the stabilizer's keys kept their order rather than their moments"
            )
            check(
                r.mask?.keys?.map { it.atMs } == listOf(6_000L, 10_000L),
                "reverse: the mask's keys are at ${r.mask?.keys?.map { it.atMs }}"
            )
            check(
                r.mask?.track?.samples?.map { it.atMs } == listOf(6_000L, 10_000L),
                "reverse: the mask's track is at ${r.mask?.track?.samples?.map { it.atMs }}"
            )
            // The thing it was following was on the left at the window's start,
            // so on the render's last frame it is still on the left.
            check(
                r.mask?.track?.samples?.last()?.xFraction == 0.1f,
                "reverse: the track's samples moved as well as their moments"
            )
            // And back again is where it began.
            val again = r.unreversed()
            check(again.stabilizer.map { it.atMs } == listOf(2_000L, 6_000L), "unreverse: the stabilizer is at ${again.stabilizer.map { it.atMs }}")
            check(again.mask?.track?.samples?.map { it.atMs } == listOf(2_000L, 6_000L), "unreverse: the mask's track is at ${again.mask?.track?.samples?.map { it.atMs }}")
        }

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
        val window = ClipCrop(rect = CropRect(0.1f, 0.1f, 0.9f, 0.9f), ratio = CropRatio.Square)
        val src = video("src", 10_000, ramp = SpeedRamp(listOf(SpeedPoint(0, 1f), SpeedPoint(10_000, 0.5f))), keys = keys)
            .copy(volume = 0.4f, fadeInMs = 300, fadeOutMs = 600, voice = VoiceEffect.Robot, opacity = 0.7f,
                mirrored = true, quarterTurns = 2, offsetXFraction = 0.2f,
                lookId = "warm", lookIntensity = 0.6f, adjust = Adjust(exposure = 0.3f), crop = window,
                arrival = ClipArrival.Zoom, arrivalMs = 800, leaving = ClipLeaving.Fade, leavingMs = 400, loop = ClipLoop.None,
                frameBlend = true, pitchFollowsSpeed = true)
        val attrs = src.attributes
        val dst = video("dst", 4_000, start = src.durationMs, srcIn = 1_000)
        val after = TimelineState(clips = listOf(src, dst)).withAttributesPasted("dst", attrs)
        val d = after.byId("dst")
        check(d.uri == dst.uri && d.sourceInMs == 1_000L && d.sourceSpanMs == 4_000L, "paste changed the footage or window")
        check(d.volume == 0.4f && d.fadeInMs == 300L && d.voice == VoiceEffect.Robot && d.opacity == 0.7f, "paste: sound or opacity not carried")
        check(d.mirrored && d.quarterTurns == 2 && d.offsetXFraction == 0.2f, "paste: mirror, turn or placement not carried")
        // The look, the sliders and the crop window (B12) are settings too.
        check(d.lookId == "warm" && d.lookIntensity == 0.6f && d.adjust.exposure == 0.3f && d.crop == window, "paste: look, sliders or crop not carried")
        // So are the arrival, the leaving and their lengths, the frame blend and the pitch switch (B13).
        check(d.arrival == ClipArrival.Zoom && d.arrivalMs == 800L && d.leaving == ClipLeaving.Fade && d.leavingMs == 400L, "paste: arrival or leaving not carried")
        check(d.frameBlend && d.pitchFollowsSpeed, "paste: frame blend or pitch switch not carried")
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
        check(s.lookId == null && s.adjust == Adjust.NONE && s.crop == null, "paste onto a sound gave it a picture's look or crop")
        check(s.pitchFollowsSpeed && s.arrival == ClipArrival.None && !s.frameBlend, "paste onto a sound: the pitch switch travels with the curve, the picture's arrival does not")
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
        // The anchor lands where it is dropped. The slot is counted among the
        // track as drawn with d lifted - a, b, c - so slot 1 is between a and
        // b: the group goes in after a, b ahead of d as it was. It used to
        // subtract b from the slot and land the group at the front.
        val byD = two.withClipsReordered(setOf("b", "d"), anchorId = "d", index = 1)
        check(byD.main() == listOf("a", "b", "d", "c"), "carry by d to slot 1: ${byD.main()}")
        butted(byD, "carry by d")
        // Slot 2 (between b and c) is the same place among the shots not moving; slot 3 is the end.
        check(two.withClipsReordered(setOf("b", "d"), "d", 2).main() == listOf("a", "b", "d", "c"), "carry by d to slot 2: ${two.withClipsReordered(setOf("b", "d"), "d", 2).main()}")
        check(two.withClipsReordered(setOf("b", "d"), "d", 3).main() == listOf("a", "c", "b", "d"), "carry by d to the end: ${two.withClipsReordered(setOf("b", "d"), "d", 3).main()}")
        check(two.withClipsReordered(setOf("b", "d"), "d", 0).main() == listOf("b", "d", "a", "c"), "carry by d to the front: ${two.withClipsReordered(setOf("b", "d"), "d", 0).main()}")
        // Dropped back in its own slot, the group still gathers round the
        // anchor: b and d were apart, and a carry of the two makes them one.
        check(two.withClipsReordered(setOf("b", "d"), "b", 1).main() == listOf("a", "b", "d", "c"), "a group dropped in its own slot: ${two.withClipsReordered(setOf("b", "d"), "b", 1).main()}")
        // Three of four by the middle one to the end (slot 3 of a, c, d drawn): d first, then the three as they were.
        val three = TimelineState(clips = listOf(a, b, c, d), selectedClipId = "a", selectedIds = setOf("b", "c"))
        check(three.withClipsReordered(setOf("a", "b", "c"), "b", 3).main() == listOf("d", "a", "b", "c"), "three by b past d: ${three.withClipsReordered(setOf("a", "b", "c"), "b", 3).main()}")
        // And the same three dropped in b's own slot (1): already gathered, nothing moves.
        check(three.withClipsReordered(setOf("a", "b", "c"), "b", 1) == three, "a gathered group dropped in its own slot moved")
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
        // The editor asks how far the group went, to move the lines of words by
        // the same: the whole way, the floor, the wall, and the drag itself when
        // nothing in the set is a sound or an overlay (words alone).
        check(state.groupMoveDelta(group, 1_500) == 1_500L, "delta of a free slide")
        check(state.groupMoveDelta(group, -5_000) == -1_000L, "delta to the floor: ${state.groupMoveDelta(group, -5_000)}")
        check(state.groupMoveDelta(group, 5_000) == 4_000L, "delta into a wall: ${state.groupMoveDelta(group, 5_000)}")
        check(state.groupMoveDelta(setOf("line-1", "line-2"), 7_000) == 7_000L, "delta of words alone")
        check(state.withClipsMoved(group, 5_000).byId("o1").timelineStartMs == 2_000L + state.groupMoveDelta(group, 5_000), "the move and its delta disagree")
    }

    // --- Who carries the set: a lift on one of several selected, and only then. -----
    run {
        val a = video("a", 3_000)
        val b = video("b", 3_000, start = 3_000)
        val c = video("c", 3_000, start = 6_000)
        val two = TimelineState(clips = listOf(a, b, c), selectedClipId = "a", selectedIds = setOf("b"))
        check(two.carriesSelection("a") && two.carriesSelection("b"), "a lift on one of the set does not carry it")
        check(!two.carriesSelection("c"), "a lift on a clip outside the set carries the set")
        check(!TimelineState(clips = listOf(a, b, c), selectedClipId = "a").carriesSelection("a"), "one selected alone carries a group")
        // Lifted or tapped on the picture: joins and leads, never leaves.
        val joined = two.withSelectionJoined("c")
        check(joined.allSelectedIds == setOf("a", "b", "c") && joined.selectedClipId == "c", "join from outside: ${joined.selectedClipId} + ${joined.selectedIds}")
        val led = two.withSelectionJoined("b")
        check(led.allSelectedIds == setOf("a", "b") && led.selectedClipId == "b", "join from inside: ${led.selectedClipId} + ${led.selectedIds}")
        check(led.withSelectionJoined("b") == led, "joining twice changed something")
        // The tap on the strip still toggles out.
        check(two.withSelectionToggled("b").allSelectedIds == setOf("a"), "a strip tap no longer leaves the set")
    }

    // --- A set carried by a member butted against another member: they are no walls. ---
    run {
        val o1 = video("o1", 2_000, start = 0, layer = 1)
        val o2 = video("o2", 2_000, start = 2_000, layer = 1)
        val o3 = video("o3", 1_000, start = 7_000, layer = 1)
        val state = TimelineState(clips = listOf(video("a", 10_000), o1, o2, o3), selectedClipId = "o1", selectedIds = setOf("o2"))
        check(state.carriesSelection("o1"), "the butted pair is not carried together")
        // What the strip's landing now asks of the set, not of o1 alone.
        check(state.groupMoveDelta(setOf("o1", "o2"), 500) == 500L, "the set is walled by its own member: ${state.groupMoveDelta(setOf("o1", "o2"), 500)}")
        check(state.groupMoveDelta(setOf("o1", "o2"), 5_000) == 3_000L, "the set runs through o3: ${state.groupMoveDelta(setOf("o1", "o2"), 5_000)}")
        // o1 resolved alone is stopped by o2 - the fault the set's landing avoids.
        check(state.groupMoveDelta(setOf("o1"), 500) == 0L, "o1 alone is not walled by o2")
        val moved = state.withClipsMoved(setOf("o1", "o2"), state.groupMoveDelta(setOf("o1", "o2"), 500))
        check(moved.byId("o1").timelineStartMs == 500L && moved.byId("o2").timelineStartMs == 2_500L, "the set did not move together")
    }

    // --- An undo reconciles the rest of a selection with the lead it puts back. ---
    run {
        val all = setOf("a", "b", "o")
        // Add overlay O (lead O), Select more, tap A, Undo: the lead goes back to nothing.
        check(selectionAfterRestore(null, setOf("o", "a"), all - "o").isEmpty(), "a set with no lead survived an undo")
        // The step before had X selected: the set was never built around X.
        check(selectionAfterRestore("x", setOf("o", "a"), all + "x").isEmpty(), "an undo joined a set to a lead that was never in it")
        // Lead A, B added then in the set, Undo the add: B is gone from the set.
        check(selectionAfterRestore("a", setOf("a", "b"), all - "b").isEmpty(), "a clip the undo took away is still in the set")
        // An undo that touches none of it leaves the set as it was.
        check(selectionAfterRestore("a", setOf("a", "b", "o"), all) == setOf("b", "o"), "an unrelated undo let the set go")
        check("a" !in selectionAfterRestore("a", setOf("a", "b"), all), "the lead is in its own set")
    }

    // --- What was measured on the footage, through Reverse, Replace, Freeze and Copy. ---
    run {
        val shake = listOf(Keyframe(2_000, Transform(offsetXFraction = 0.01f), KeyframeEasing.Linear), Keyframe(9_000, Transform(offsetXFraction = -0.02f), KeyframeEasing.Linear))
        val path = MotionTrack(listOf(TrackSample(2_000, 0.2f, 0.3f), TrackSample(10_000, 0.8f, 0.3f)))
        val masked = Mask(shape = MaskShape.Ellipse, track = path)
        val bg = BackgroundRemoval(maskFile = "/data/segments/a.bin")
        val a = video("a", 8_000, srcIn = 2_000).copy(stabilizer = shake, mask = masked, background = bg)
        val rendered = Uri.parse("file:///data/reversed/r.mp4")
        val rev = a.reversed(rendered, 8_000)
        // The correction measured on the original's frame at 9 s is at the
        // render's 1 s, since the render's t is the original's 10 s - t, and
        // the render's own clock is what transformAt samples it with.
        check(rev.stabilizer.map { it.atMs } == listOf(1_000L, 8_000L), "reverse: the stabilizer's keys are at ${rev.stabilizer.map { it.atMs }}")
        check(abs(rev.transformAt(0).offsetXFraction - (-0.02f)) < 1e-4f, "reverse: the first frame's correction is ${rev.transformAt(0).offsetXFraction}, not the original's last")
        check(abs(a.transformAt(a.durationMs - 1).offsetXFraction - rev.transformAt(0).offsetXFraction) < 1e-3f, "reverse: the last frame's correction did not become the first's")
        // The track follows the same subject: at the render's 0 (the original's 10 s) it is at x 0.8.
        val sample = rev.mask?.track?.sampleAt(0L)
        check(sample != null && abs(sample.xFraction - 0.8f) < 1e-4f, "reverse: the track at the render's start is ${sample?.xFraction}")
        check(rev.mask?.track?.sampleAt(8_000L)?.xFraction?.let { abs(it - 0.2f) < 1e-4f } == true, "reverse: the track at the render's end")
        // The person masks cannot be re-keyed: off, and kept for the way back.
        check(rev.background == null && rev.reversedFrom?.background == bg, "reverse: the person masks were kept on the render, or lost")
        val back = rev.unreversed()
        check(back.stabilizer == shake && back.mask == masked && back.background == bg, "unreverse did not put the analyses back: ${back.stabilizer.map { it.atMs }}")
        // Trimmed while reversed, then put back: the keys are still on the original's clock.
        check(rev.copy(sourceInMs = 1_000, sourceOutMs = 5_000).unreversed().stabilizer == shake, "unreverse after a trim moved the stabilizer")
        // A clip with none of them reverses to none of them.
        check(video("plain", 8_000).reversed(rendered, 8_000).let { it.stabilizer.isEmpty() && it.mask == null && it.background == null }, "reverse invented an analysis")

        // The reframe path and the kept measurement are measured on the footage
        // too, and both used to be carried through Reverse untouched - so the
        // auto-reframe crop panned against the picture, and one nudge of the
        // Strength slider re-solved from the old clock and wrote un-mirrored
        // keys over the mirrored ones above.
        val measured = StabilizerMeasurement(
            analysisWidth = 96, analysisHeight = 54,
            timesMs = listOf(2_000L, 4_000L, 6_000L, 9_000L),
            motions = listOf(
                FrameMotion(1f, 2f, 0.1f, 0.9f), FrameMotion(-3f, 1f, -0.2f, 0.9f),
                FrameMotion(2f, -1f, 0.3f, 0.9f), FrameMotion(4f, 4f, 0.4f, 0.9f)
            )
        )
        val looked = MotionTrack(listOf(TrackSample(2_000, 0.25f, 0.4f), TrackSample(9_000, 0.75f, 0.6f)))
        val m = a.copy(stabilizerMeasurement = measured, reframe = looked)
        val mRev = m.reversed(rendered, 8_000)
        check(mRev.reframe?.sampleAt(1_000L)?.xFraction?.let { abs(it - 0.75f) < 1e-4f } == true,
            "reverse: the reframe path at the render's 1 s is ${mRev.reframe?.sampleAt(1_000L)?.xFraction}, not the original's 9 s")
        check(mRev.reframe?.samples?.map { it.atMs } == listOf(1_000L, 8_000L),
            "reverse: the reframe samples are at ${mRev.reframe?.samples?.map { it.atMs }}")
        check(mRev.stabilizerMeasurement?.timesMs == listOf(1_000L, 4_000L, 6_000L, 8_000L),
            "reverse: the measurement's times are at ${mRev.stabilizerMeasurement?.timesMs}")
        // Each motion is the move from one frame to the next, so backwards it is
        // the same move the other way: the list reversed and every motion negated.
        check(mRev.stabilizerMeasurement?.motions?.map { it.dx } == listOf(-4f, -2f, 3f, -1f),
            "reverse: the measurement's motions are ${mRev.stabilizerMeasurement?.motions?.map { it.dx }}")
        check(mRev.stabilizerMeasurement?.motions?.map { it.rotationDegrees } == listOf(-0.4f, -0.3f, 0.2f, -0.1f),
            "reverse: the measurement's turns are ${mRev.stabilizerMeasurement?.motions?.map { it.rotationDegrees }}")
        // And back again is the measurement that was taken, exactly.
        check(mRev.unreversed().stabilizerMeasurement == measured, "unreverse did not put the measurement back")
        check(mRev.unreversed().reframe == looked, "unreverse did not put the reframe path back")
        // The solve from the mirrored measurement lands keys on the render's own
        // clock, which is what the Strength slider needs it for.
        val resolved = StabilizerSolve.solve(mRev.stabilizerMeasurement!!, 0.5f)
        check(resolved == null || resolved.keyframes.map { it.atMs } == listOf(1_000L, 4_000L, 6_000L, 8_000L),
            "reverse: a re-solve from the mirrored measurement keys at ${resolved?.keyframes?.map { it.atMs }}")

        // Replace keeps the mask's shape and drops its path; Copy carries the shape alone.
        val replaced = TimelineState(clips = listOf(m)).withClipReplaced("a", Uri.parse("content://new"), 20_000, 0, "new").byId("a")
        check(replaced.mask?.track == null && replaced.mask?.shape == MaskShape.Ellipse, "replace kept the old footage's track")
        // Both of those are of footage no longer under the clip.
        check(replaced.reframe == null, "replace kept the old footage's reframe path")
        check(replaced.stabilizerMeasurement == null, "replace kept the old footage's stabilizer measurement")
        check(a.attributes.mask?.track == null && a.attributes.mask?.shape == MaskShape.Ellipse, "copied attributes carry a track")
        // A freeze at the original's 6 s holds the mask where the track had it then (x 0.5 -> 0 in the shader's -1..1).
        val frozen = TimelineState(clips = listOf(a)).withFrozenFrame("a", 4_000, still).byId(still.id)
        check(frozen.mask?.track == null && abs((frozen.mask?.centerXFraction ?: 9f) - 0f) < 1e-3f, "freeze: the still's mask is ${frozen.mask}")

        // Every number the still keeps is the one that was on screen, which is
        // what the function promises - the placement and the mask were read at
        // the moment and the two keyed levels were not, so a shot halfway
        // through a keyed fade froze at the static field the keys had replaced.
        run {
            val keyed = video("k", 8_000).copy(
                opacity = 1f,
                opacityKeys = listOf(ValueKey(0L, 0.2f), ValueKey(8_000L, 1f)),
                lookId = "noir",
                lookIntensity = 1f,
                lookKeys = listOf(ValueKey(0L, 0f), ValueKey(8_000L, 1f))
            )
            val half = TimelineState(clips = listOf(keyed)).withFrozenFrame("k", 4_000, still).byId(still.id)
            check(abs(half.opacity - 0.6f) < 1e-3f, "freeze: the still's opacity is ${half.opacity}, not the 0.6 on screen")
            check(abs(half.lookIntensity - 0.5f) < 1e-3f, "freeze: the still's filter strength is ${half.lookIntensity}, not the 0.5 on screen")
            // And the still carries no track of its own: it is one frame.
            check(half.opacityKeys.isEmpty() && half.lookKeys.isEmpty() && half.keyframes.isEmpty(),
                "freeze: the still carries keys of its own")
            // A clip with no keys still freezes at its own levels.
            val plain = video("p", 8_000).copy(opacity = 0.4f, lookId = "noir", lookIntensity = 0.7f)
            val still2 = TimelineState(clips = listOf(plain)).withFrozenFrame("p", 4_000, still).byId(still.id)
            check(abs(still2.opacity - 0.4f) < 1e-3f && abs(still2.lookIntensity - 0.7f) < 1e-3f,
                "freeze: an unkeyed clip's levels came out ${still2.opacity} and ${still2.lookIntensity}")
        }

        // Copied from a sound, pasted on a picture: the sound of it and nothing else.
        val song = audio("song", 8_000).copy(volume = 0.3f, fadeInMs = 400, voice = VoiceEffect.Robot)
        val pip = video("pip", 4_000, layer = 1, keys = listOf(Keyframe(0, Transform(scale = 0.4f)), Keyframe(4_000, Transform(scale = 0.5f))))
            .copy(scale = 0.4f, offsetXFraction = 0.5f, mask = Mask(), mirrored = true, quarterTurns = 1)
        val p = TimelineState(clips = listOf(video("base", 10_000), song, pip)).withAttributesPasted("pip", song.attributes).byId("pip")
        check(p.volume == 0.3f && p.fadeInMs == 400L && p.voice == VoiceEffect.Robot, "paste from a sound: the sound was not carried")
        check(p.keyframes == pip.keyframes && p.offsetXFraction == 0.5f && p.mask == Mask() && p.mirrored && p.quarterTurns == 1,
            "paste from a sound reset the picture: keys ${p.keyframes.size}, x ${p.offsetXFraction}, mask ${p.mask}, mirror ${p.mirrored}, turns ${p.quarterTurns}")
    }

    // --- The stabilizer apart from the placement, as the file applies them. -----------
    run {
        val shake = listOf(Keyframe(2_000, Transform(scale = 1.1f, offsetXFraction = 0.02f), KeyframeEasing.Linear),
            Keyframe(8_000, Transform(scale = 1.1f, offsetXFraction = -0.04f), KeyframeEasing.Linear))
        val a = video("a", 6_000, srcIn = 2_000).copy(stabilizer = shake, scale = 0.5f, offsetXFraction = 0.3f)
        for (at in listOf(0L, 1_500L, 3_000L, 5_999L)) {
            val whole = a.transformAt(at)
            val placed = a.placedAt(at)
            val fix = a.stabilizerAt(at)
            // The split adds back up to what the rest of the editor reads.
            check(abs(whole.scale - placed.scale * fix.scale) < 1e-5f && abs(whole.offsetXFraction - (placed.offsetXFraction + fix.offsetXFraction)) < 1e-5f,
                "at $at: $whole is not $placed with $fix")
            check(placed == a.placementAt(at), "at $at the placement carries the correction: $placed")
            // And the correction is the export's, on the source clock.
            check(fix == ExportPlan.motionAt(a, ExportPlan.MotionPart.Stabilizer, a.sourceAt(at) - a.sourceInMs), "at $at the correction is not the file's")
        }
        check(video("b", 1_000).stabilizerAt(500).isIdentity, "an unstabilized clip is corrected")
    }

    // --- A frozen frame lands where each state shows it. ------------------------------
    run {
        val shot = video("a", 6_000, srcIn = 2_000)
        val file = shot.uri
        // The frame 5 s into the file is 3 s into the shot.
        check(shot.timelineOfFrame(file, 5_000) == 3_000L, "frame at ${shot.timelineOfFrame(file, 5_000)}")
        // A main-track head trim keeps the start and moves the source: the same
        // frame is a second earlier on the strip - not the same moment.
        val trimmed = shot.copy(sourceInMs = 3_000)
        check(trimmed.timelineOfFrame(file, 5_000) == 2_000L, "after a head trim the frame is at ${trimmed.timelineOfFrame(file, 5_000)}")
        check(shot.timelineOfFrame(file, 1_000) == null, "a frame before the window is shown")
        check(shot.timelineOfFrame(file, 8_000) == null, "the out-point itself is shown")
        check(shot.timelineOfFrame(Uri.parse("content://other"), 5_000) == null, "another file's frame is shown")
    }

    // --- A photo overlay takes no key and no mask from a paste. ------------------------
    run {
        val keyed = video("pip", 3_000, layer = 1).copy(chromaKey = com.squish.app.timeline.ChromaKey(), mask = Mask())
        val logo = video("logo", 3_000, start = 4_000, layer = 1, uri = "file:///data/user/0/com.squish.app/files/stills/overlay_1.png")
        check(logo.isStillPicture, "the logo is not a photo overlay")
        val after = TimelineState(clips = listOf(video("base", 10_000), keyed, logo)).withAttributesPasted("logo", keyed.attributes).byId("logo")
        check(after.chromaKey == null && after.mask == null, "a photo overlay took a key or mask its preview never draws: ${after.chromaKey}, ${after.mask}")
        val pip2 = video("pip2", 3_000, start = 4_000, layer = 2)
        val onFootage = TimelineState(clips = listOf(video("base", 10_000), keyed, pip2)).withAttributesPasted("pip2", keyed.attributes).byId("pip2")
        check(onFootage.chromaKey != null && onFootage.mask != null, "footage no longer takes a pasted key and mask")
    }

    // --- A pasted curve on an overlay ripples its row, as the speed sheet does. --------
    run {
        val slow = video("slow", 2_000, ramp = SpeedRamp.flat(0.5f))
        val o1 = video("o1", 2_000, start = 0, layer = 1)
        val o2 = video("o2", 2_000, start = 2_000, layer = 1)
        val o3 = video("o3", 1_000, start = 4_500, layer = 1)
        val after = TimelineState(clips = listOf(video("base", 20_000), slow, o1, o2, o3)).withAttributesPasted("o1", slow.attributes)
        check(after.byId("o1").durationMs == 4_000L, "the pasted curve did not slow o1: ${after.byId("o1").durationMs}")
        val row = after.clips.filter { it.layer == 1 }.sortedBy { it.timelineStartMs }
        check(row.zipWithNext().none { (x, y) -> y.timelineStartMs < x.timelineEndMs },
            "a pasted curve left overlays overlapping: ${row.map { it.id to (it.timelineStartMs to it.timelineEndMs) }}")
        check(after.byId("o2").timelineStartMs == 4_000L, "the butted follower was not carried: ${after.byId("o2").timelineStartMs}")
    }

    // --- A still rendered into a file is not footage. ------------------------------------
    run {
        check(isRenderedStill("file:///data/user/0/com.squish.app/files/stills/freeze_1_ab.mp4"), "a freeze file is not a rendered still")
        check(isRenderedStill("file:///data/user/0/com.squish.app/files/stills/photo_1_ab.mp4"), "a photo file is not a rendered still")
        check(!isRenderedStill("file:///data/user/0/com.squish.app/files/stills/overlay_1.png"), "a photo overlay is a rendered still")
        check(!isRenderedStill("file:///data/user/0/com.squish.app/files/reversed/reverse_1.mp4"), "a reversed render is a rendered still")
        check(!isRenderedStill("content://media/external/video/media/12"), "a gallery file is a rendered still")
        // Animate every photo reads this: main-track photos, never a blank or a freeze or a photo overlay.
        check(com.squish.app.timeline.isRenderedPhoto("file:///data/user/0/com.squish.app/files/stills/photo_1790792006247_67c2aefc.mp4"), "a main-track photo is not a photo")
        check(!com.squish.app.timeline.isRenderedPhoto("file:///data/user/0/com.squish.app/files/stills/blank_1_ab.mp4") && !com.squish.app.timeline.isRenderedPhoto("file:///data/user/0/com.squish.app/files/stills/freeze_1_ab.mp4"), "a blank or a freeze read as a photo")
        check(!com.squish.app.timeline.isRenderedPhoto("file:///data/user/0/com.squish.app/files/stills/overlay_1.png") && !com.squish.app.timeline.isRenderedPhoto(null), "a photo overlay or nothing read as a main-track photo")

        // Taking the filter off takes its strength track with it: a look-
        // strength key belongs to the look, and left behind it drew diamonds on
        // the strip and lit the keyframe button for a filter that was not
        // there, took the file's animated path per frame for nothing, and
        // animated the *next* look picked by the last one's keys.
        run {
            val keys = listOf(ValueKey(0L, 0.2f), ValueKey(3_000L, 1f))
            val graded = video("g", 6_000).copy(lookId = "noir", lookIntensity = 0.6f, lookKeys = keys)
            check(graded.lookAnimated, "the keyed clip is not animated to begin with")
            val cleared = graded.withLook(null)
            check(cleared.lookId == null && cleared.lookKeys.isEmpty() && !cleared.lookAnimated,
                "clearing the look left ${cleared.lookKeys.size} strength keys on it")
            check(cleared.lookIntensity == 1f, "clearing the look left the strength at ${cleared.lookIntensity}")
            // A different look keeps the track - the strength is of whichever
            // filter is on, which is the point of keying it.
            val swapped = graded.withLook("warm")
            check(swapped.lookId == "warm" && swapped.lookKeys == keys && swapped.lookIntensity == 0.6f,
                "swapping the look lost the strength track or the strength")
            check(graded.withLook("warm", 0.3f).lookIntensity == 0.3f, "an explicit strength was not taken")
            // And a clip with no track is unchanged either way.
            val plain = video("p", 6_000)
            check(plain.withLook(null) == plain.copy(lookIntensity = 1f), "clearing nothing changed something")
        }

        // A blank, which "Move every shot" leaves alone: there is nothing in a
        // flat colour to move. Named off the address like the rest of these.
        fun blank(a: String?) = com.squish.app.timeline.isBlankStill(a)
        check(blank("file:///data/user/0/com.squish.app/files/stills/blank_1790792006247_67c2aefc.mp4"), "a blank is not a blank")
        check(!blank("file:///data/user/0/com.squish.app/files/stills/photo_1_ab.mp4"), "a photo read as a blank")
        check(!blank("file:///data/user/0/com.squish.app/files/stills/freeze_1_ab.mp4"), "a freeze read as a blank")
        // The PNG the blank is rendered *from* lives in the same folder and is
        // not a clip; nor is a gallery file whose own name happens to start the
        // same way.
        check(!blank("file:///data/user/0/com.squish.app/files/stills/blank_1080x1920.png"), "the blank's own frame read as a clip")
        check(!blank("content://media/external/video/media/12") && !blank(null), "a gallery file or nothing read as a blank")
        check(!blank("file:///data/user/0/com.squish.app/files/imports/blank_canvas.mp4"), "a file called blank_ somewhere else read as a blank")
        check(video("a", 1_000).isFootage && !still.isFootage && still.isRenderedStill, "footage and a still are confused")
        check(!audio("s", 1_000).isFootage, "a sound is footage")
    }

    println("clip ops: freeze, replace, reverse, mirror and turn, attributes, several at once")
    if (problems.isEmpty()) println("PASS - the clip operations do what B11 says")
    else { println("FAIL (${problems.size})"); problems.take(40).forEach { println("  - $it") }; exitProcess(1) }
}
