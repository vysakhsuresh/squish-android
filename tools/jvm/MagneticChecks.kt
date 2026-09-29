import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.RampShape
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transform
import com.squish.app.timeline.TransformLimits
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.rippleVideo
import com.squish.app.timeline.shiftedBy
import com.squish.app.timeline.within
import com.squish.app.timeline.transformAt
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.withClipDuplicated
import com.squish.app.timeline.withClipMoved
import com.squish.app.timeline.withClipRemoved
import com.squish.app.timeline.withClipReordered
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withLayerChanged
import com.squish.app.timeline.withOverlayGeometry
import com.squish.app.timeline.withPlacementReset
import com.squish.app.timeline.OVERLAY_LANDING
import com.squish.app.timeline.withSplitAllTracks
import com.squish.app.timeline.withSplitAtPlayhead
import com.squish.app.timeline.withTransition
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.exitProcess

// The timeline model's edits, executed: the magnetic main track, cuts, trims,
// keyframes carried through both, and overlay rows that never share a moment.
// Each block is one claim from docs/ROADMAP.md batch B2 or the audit item it
// fixes (T1-T5, T11, V3, V10, O6, O7).

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun video(
    id: String, span: Long, start: Long = 0, srcIn: Long = 0, layer: Int = 0,
    ramp: SpeedRamp = SpeedRamp(), keys: List<Keyframe> = emptyList(), transition: Transition = Transition()
) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = srcIn, sourceOutMs = srcIn + span,
    timelineStartMs = start, sourceDurationMs = 60_000, speedRamp = ramp, layer = layer,
    keyframes = keys, transitionIn = transition
)

fun audio(id: String, span: Long, start: Long = 0, srcIn: Long = 0, ramp: SpeedRamp = SpeedRamp()) = Clip(
    id = id, kind = ClipKind.Audio, label = id, sourceInMs = srcIn, sourceOutMs = srcIn + span,
    timelineStartMs = start, sourceDurationMs = 60_000, speedRamp = ramp
)

fun TimelineState.byId(id: String) = clips.first { it.id == id }

/** A push-in: scale 1 to 1.18 across [length], the way MotionPreset lays it. */
fun pushIn(length: Long, easing: KeyframeEasing = KeyframeEasing.Smooth) = listOf(
    Keyframe(0L, Transform(scale = 1f), easing),
    Keyframe(length, Transform(scale = 1.18f), easing)
)

/** Every rule the main track promises, checked on any state. */
fun mainIsMagnetic(state: TimelineState, what: String) {
    val base = state.baseVideoClips
    if (base.isEmpty()) return
    check(base[0].timelineStartMs == 0L, "$what: main track starts at ${base[0].timelineStartMs}, not 0")
    check(!base[0].transitionIn.isActive, "$what: the first clip carries a transition into nothing")
    for (i in 1 until base.size) {
        val prev = base[i - 1]
        val clip = base[i]
        val overlap = if (clip.transitionIn.isActive) {
            clip.transitionIn.durationMs.coerceAtMost(minOf(clip.durationMs, prev.durationMs) / 2)
        } else 0L
        check(
            clip.timelineStartMs == prev.timelineEndMs - overlap,
            "$what: clip $i starts at ${clip.timelineStartMs}, previous ends ${prev.timelineEndMs}, overlap $overlap"
        )
        if (i >= 2) {
            check(clip.timelineStartMs >= base[i - 2].timelineEndMs, "$what: clip $i overlaps two back")
        }
    }
}

fun overlayRowsClear(state: TimelineState, what: String) {
    val overlays = state.overlayClips
    for (a in overlays) for (b in overlays) {
        if (a.id >= b.id || a.layer != b.layer) continue
        val clash = a.timelineStartMs < b.timelineEndMs && b.timelineStartMs < a.timelineEndMs
        check(!clash, "$what: ${a.id} and ${b.id} overlap on row ${a.layer}")
    }
    check(overlays.all { it.layer in 1..MAX_LAYER }, "$what: an overlay left the rows")
}

fun near(a: Float, b: Float, eps: Float = 0.004f) = abs(a - b) <= eps

fun main() {
    // --- The ramp: footage revealed before the window plays at the opening rate.
    run {
        check(SpeedRamp.flat(0.5f).outputOffsetAt(-100L, 4000L) == -200L, "0.5x: -100 source is not -200 played")
        check(SpeedRamp().outputOffsetAt(-300L, 4000L) == -300L, "1x: -300 source is not -300 played")
        val slow = SpeedRamp.preset(RampShape.SlowStart, 4000L) // opens at 0.35x
        check(abs(slow.outputOffsetAt(-350L, 4000L) + 1000L) <= 1L, "ramp: revealed stretch not at the opening rate")
        val widened = slow.sliced(-350L, 4000L)
        val revealedPlays = widened.outputOffsetAt(350L, 4350L)
        check(abs(revealedPlays - 1000L) <= 2L, "ramp: sliced() plays the revealed 350ms in $revealedPlays, not ~1000")
    }

    // --- Keyframes shifted, never dropped. ------------------------------------------
    run {
        val keys = pushIn(10_000L, KeyframeEasing.Smooth)
        val back = keys.shiftedBy(-5_000L)
        check(back.map { it.atMs } == listOf(-5_000L, 5_000L), "shifted back half: $back")
        // The shifted curve is the same curve: every moment reads what it read.
        for (t in 0L..5_000L step 250) {
            check(near(back.transformAt(t, Transform()).scale, keys.transformAt(t + 5_000L, Transform()).scale, 0.0005f),
                "shifted curve differs at $t")
        }
        check(keys.shiftedBy(0L) === keys, "a zero shift copied the list")
        check(emptyList<Keyframe>().shiftedBy(100).isEmpty(), "shifting nothing made something")
        check(back.within(5_000L).map { it.atMs } == listOf(5_000L), "within() kept a key off the clip: ${back.within(5_000L)}")
    }

    // --- Split: T3/V10 - keyframes carry on, the second half has no transition. -
    for (easing in KeyframeEasing.entries) {
        val z = video("z", 5_000)
        val a = video("a", 20_000, start = 5_000, keys = pushIn(20_000, easing),
            transition = Transition(TransitionType.CrossFade, 500))
        val b = video("b", 10_000, start = 25_000, transition = Transition(TransitionType.CrossFade, 500))
        val cut = TimelineState(clips = listOf(z, a, b), playheadMs = 15_000).rippleVideo().withSplitAtPlayhead()
        val halves = cut.baseVideoClips
        check(halves.size == 4, "$easing: cut made ${halves.size} main clips")
        if (halves.size != 4) continue
        val first = halves[1]
        val second = halves[2]
        check(first.id == "a" && first.transitionIn.isActive, "$easing: the first half lost the transition into the shot")
        check(!second.transitionIn.isActive, "$easing: second half inherited the transition into the shot")
        // No snap: the frame either side of the seam has the same pose.
        val before = first.transformAt(first.timelineEndMs - 1)
        val after = second.transformAt(second.timelineStartMs)
        val whole = a.copy(timelineStartMs = 0).transformAt(first.durationMs)
        check(near(after.scale, whole.scale, 0.005f), "$easing: second half starts at ${after.scale}, uncut was ${whole.scale}")
        if (easing != KeyframeEasing.Hold) {
            check(near(before.scale, after.scale, 0.005f), "$easing: pose jumps at the cut ${before.scale} -> ${after.scale}")
        }
        val end = second.transformAt(second.timelineEndMs)
        check(near(end.scale, 1.18f), "$easing: second half ends at ${end.scale}, not 1.18")
        // Every frame of both halves plays exactly the pose it had uncut - Smooth
        // included, whose ease used to restart at the cut.
        val uncut = a.copy(timelineStartMs = first.timelineStartMs)
        for (t in first.timelineStartMs until second.timelineEndMs step 97) {
            val half = if (t < first.timelineEndMs) first else second
            check(near(half.transformAt(t).scale, uncut.transformAt(t).scale, 0.0005f),
                "$easing: at $t the cut plays ${half.transformAt(t).scale}, uncut ${uncut.transformAt(t).scale}")
        }
        check(first.keyframes == a.keyframes, "$easing: the first half's keys were rewritten")
        mainIsMagnetic(cut, "split $easing")
    }

    // --- Split: the halves add up, so nothing after a main-track cut moves. ------
    run {
        val ramps = listOf(SpeedRamp(), SpeedRamp.flat(0.5f), SpeedRamp.flat(3f), SpeedRamp.flat(0.7f)) +
            RampShape.entries.map { SpeedRamp.preset(it, 7_000L) }
        for (ramp in ramps) {
            val c = video("c", 7_000, ramp = ramp)
            val next = video("n", 2_000, start = c.durationMs)
            var s = TimelineState(clips = listOf(c, next))
            // A beat cutter's run: fifty cuts across the one clip.
            for (i in 1..50) s = s.copy(playheadMs = c.durationMs * i / 51).withSplitAllTracks { it.kind == ClipKind.Video }
            // Exact where the ramp's staircase allows it. Where a half-speed or
            // quarter-speed tread keeps the fraction fixed, no nearby cut adds up
            // and a millisecond is left over; before the search it was up to 4ms
            // a cut, in either direction, adding up over a run.
            val drift = abs(s.byId("n").timelineStartMs - c.durationMs)
            check(drift <= 2L,
                "ramp ${ramp.points}: after 50 cuts the next clip moved ${c.durationMs} -> ${s.byId("n").timelineStartMs}")
            if (ramp.points.size <= 1) check(drift == 0L, "flat ${ramp.points}: 50 cuts moved the next clip by $drift")
            mainIsMagnetic(s, "fifty cuts")
        }
    }

    // --- Split: T4 - refused inside the margin, and the button agrees. ---------
    run {
        val a = video("a", 1_000)
        fun at(ms: Long) = TimelineState(clips = listOf(a), playheadMs = ms)
        check(!at(50).canSplit(), "a cut 50ms into a clip is offered")
        check(at(50).withSplitAtPlayhead().clips.size == 1, "a cut 50ms into a clip happened")
        check(!at(950).canSplit(), "a cut 50ms from the end is offered")
        check(at(300).canSplit(), "a cut 300ms into a 1s clip is refused")
        val cut = at(300).withSplitAtPlayhead().baseVideoClips
        check(cut.size == 2 && cut[1].timelineStartMs == cut[0].timelineEndMs && cut[0].durationMs == 300L,
            "a cut at 300ms: ${cut.map { it.timelineStartMs to it.timelineEndMs }}")
        val tiny = video("t", 2 * MIN_CLIP_MS)
        check(!TimelineState(clips = listOf(tiny), playheadMs = MIN_CLIP_MS).canSplit(), "a 400ms clip is offered a cut")

        // Over every playhead position and ramp: the offer and the cut agree, and
        // a cut never leaves an overlap or a gap.
        val ramps = listOf(SpeedRamp(), SpeedRamp.flat(0.5f), SpeedRamp.flat(3f)) +
            RampShape.entries.map { SpeedRamp.preset(it, 3_000L) }
        for (ramp in ramps) {
            val c = video("c", 3_000, ramp = ramp)
            val next = video("n", 2_000, start = c.durationMs)
            var t = 0L
            while (t <= c.durationMs + 50) {
                val s = TimelineState(clips = listOf(c, next), playheadMs = t)
                val done = s.withSplitAtPlayhead()
                val didCut = done.clips.size == 3
                check(didCut == s.canSplit(), "ramp ${ramp.points}: at $t offer=${s.canSplit()} but cut=$didCut")
                if (didCut) {
                    mainIsMagnetic(done, "cut at $t")
                    val halves = done.baseVideoClips
                    check(halves.all { it.sourceSpanMs >= MIN_CLIP_MS }, "cut at $t left a half under the minimum")
                    check(halves[0].sourceOutMs == halves[1].sourceInMs, "cut at $t lost or doubled footage")
                    // T4's own wording: the second half sits where the cut frame lands.
                    val lands = c.timelineAtSource(halves[1].sourceInMs)
                    check(abs(halves[1].timelineStartMs - lands) <= 2 * SpeedRamp.STEP_MS,
                        "cut at $t: second half at ${halves[1].timelineStartMs}, cut frame lands at $lands")
                }
                t += 37
            }
        }
    }

    // --- Split: what it acts on (A9 model half). ---------------------------------
    run {
        val a = video("a", 10_000)
        val song = audio("song", 30_000)
        val over = video("pip", 5_000, start = 3_000, layer = 1)
        val s = TimelineState(clips = listOf(a, song, over), playheadMs = 4_000)
        val plain = s.withSplitAtPlayhead()
        check(plain.clips.count { it.kind == ClipKind.Audio } == 1, "a cut with nothing selected cut the music")
        check(plain.overlayClips.size == 1, "a cut with nothing selected cut the overlay")
        check(plain.baseVideoClips.size == 2, "a cut with nothing selected did not cut the main clip")
        val onSong = s.copy(selectedClipId = "song").withSplitAtPlayhead()
        check(onSong.audioClips.size == 2 && onSong.baseVideoClips.size == 1, "a cut with the song selected cut the picture")
        val halves = onSong.audioClips
        check(halves[1].timelineStartMs == halves[0].timelineEndMs, "the song's halves are not butted")
        val stale = s.copy(selectedClipId = "gone").withSplitAtPlayhead()
        check(stale.baseVideoClips.size == 2, "a selection the state does not hold blocked the cut")
        val all = s.withSplitAllTracks { it.kind == ClipKind.Video }
        check(all.audioClips.size == 1 && all.baseVideoClips.size == 2 && all.overlayClips.size == 2,
            "a video-only all-tracks cut: ${all.clips.map { it.id }}")
        overlayRowsClear(all, "all-tracks cut")
        val atJoin = TimelineState(clips = listOf(a, video("b", 5_000, start = 10_000)), playheadMs = 10_000)
        check(!atJoin.canSplit(), "a cut exactly on a join is offered")
    }

    // --- Ripple on delete (script step 4). ----------------------------------------
    run {
        val a = video("a", 30_000)
        val b = video("b", 15_000, start = 30_000)
        val cut = TimelineState(clips = listOf(a, b), playheadMs = 10_000).withSplitAtPlayhead()
        val second = cut.baseVideoClips[1]
        val gone = cut.withClipRemoved("a")
        mainIsMagnetic(gone, "delete")
        check(gone.byId(second.id).timelineStartMs == 0L, "after deleting the first half the second is at ${gone.byId(second.id).timelineStartMs}")
        check(gone.byId("b").timelineStartMs == 20_000L, "b is at ${gone.byId("b").timelineStartMs}, not butted at 20000")

        // Script step 5: its head dragged left reveals the deleted footage, stops
        // at the start of the file, and b moves along instead of being overrun.
        var s = gone
        repeat(40) { s = s.withClipTrimmed(second.id, -500L, 0L) }
        val grown = s.byId(second.id)
        check(grown.sourceInMs == 0L, "head drag did not stop at the file's start: in ${grown.sourceInMs}")
        check(grown.timelineStartMs == 0L, "head drag moved the clip off zero")
        mainIsMagnetic(s, "head drag")
        check(s.byId("b").timelineStartMs == 30_000L, "b not pushed along to 30000: ${s.byId("b").timelineStartMs}")
    }

    // --- T5 on a lane that stays put: a clip at zero stalls. ----------------------
    for (ramp in listOf(SpeedRamp(), SpeedRamp.flat(0.5f), SpeedRamp.flat(2f), SpeedRamp.preset(RampShape.SlowStart, 4_000L))) {
        val parked = audio("s", 4_000, start = 0, srcIn = 3_000, ramp = ramp)
        val tried = TimelineState(clips = listOf(parked)).withClipTrimmed("s", -1_000L, 0L).byId("s")
        check(tried.sourceInMs == 3_000L && tried.timelineEndMs == parked.timelineEndMs,
            "${ramp.points}: a sound at 0 grew by head drag (in ${tried.sourceInMs}, end ${tried.timelineEndMs})")

        val placed = audio("s", 4_000, start = 1_000, srcIn = 3_000, ramp = ramp)
        var st = TimelineState(clips = listOf(placed))
        repeat(30) { st = st.withClipTrimmed("s", -100L, 0L) }
        val moved = st.byId("s")
        check(moved.timelineStartMs >= 0L, "${ramp.points}: head drag took a sound before zero")
        check(abs(moved.timelineEndMs - placed.timelineEndMs) <= 2, "${ramp.points}: head drag moved the tail ${placed.timelineEndMs} -> ${moved.timelineEndMs}")
        check(moved.sourceInMs < placed.sourceInMs, "${ramp.points}: head drag revealed nothing")
        // The frame that was first is still where it was.
        val wasAt = placed.timelineStartMs
        val nowAt = moved.timelineAtSource(placed.sourceInMs)
        check(abs(nowAt - wasAt) <= SpeedRamp.STEP_MS / 4 + 3, "${ramp.points}: revealed head moved the kept frames $wasAt -> $nowAt")
    }

    // --- T11: trims keep keyframes on their frames. -----------------------------------
    run {
        val keys = pushIn(10_000L, KeyframeEasing.Linear)
        val pip = video("p", 10_000, start = 2_000, layer = 1, keys = keys)
        val s = TimelineState(clips = listOf(pip))
        val head = s.withClipTrimmed("p", 3_000L, 0L).byId("p")
        for (src in 3_000L..10_000L step 500) {
            val before = pip.transformAt(pip.timelineAtSource(src)).scale
            val after = head.transformAt(head.timelineAtSource(src)).scale
            check(near(before, after), "head trim: frame $src had scale $before, now $after")
        }
        val tail = s.withClipTrimmed("p", 0L, -4_000L).byId("p")
        check(near(tail.transformAt(tail.timelineEndMs).scale, pip.transformAt(pip.timelineStartMs + 6_000).scale),
            "tail trim: end pose ${tail.transformAt(tail.timelineEndMs).scale} is not the pose of that frame")
        check(tail.keyframes == keys, "a tail trim rewrote the keys: ${tail.keyframes}")

        // One gesture, in and back out, in 1ms steps and in one step: the keys
        // come back exactly, and fast and slow agree (the finding: a push-in whose
        // tail went in 1s and back out lost its end pose).
        for (easing in KeyframeEasing.entries) {
            val moving = video("k", 3_000, start = 1_000, layer = 1, keys = pushIn(3_000L, easing))
            val start = TimelineState(clips = listOf(moving))
            var slow = start
            repeat(1_000) { slow = slow.withClipTrimmed("k", 0L, -1L) }
            val fast = start.withClipTrimmed("k", 0L, -1_000L)
            check(near(slow.byId("k").transformAt(slow.byId("k").timelineEndMs).scale,
                fast.byId("k").transformAt(fast.byId("k").timelineEndMs).scale, 0.0005f),
                "$easing: a slow tail trim ends on a different pose from a fast one")
            repeat(1_000) { slow = slow.withClipTrimmed("k", 0L, 1L) }
            check(slow.byId("k").keyframes == moving.keyframes, "$easing: tail in and back out lost keys: ${slow.byId("k").keyframes}")
            check(near(slow.byId("k").transformAt(slow.byId("k").timelineEndMs).scale, 1.18f), "$easing: end pose gone after tail in/out")
            var headed = start
            repeat(700) { headed = headed.withClipTrimmed("k", 1L, 0L) }
            repeat(700) { headed = headed.withClipTrimmed("k", -1L, 0L) }
            check(headed.byId("k").keyframes == moving.keyframes, "$easing: head in and back out moved keys: ${headed.byId("k").keyframes}")
        }
        // A push-in at half speed, trimmed: still reaches 1.18 on its last frame.
        val slowKeys = pushIn(20_000L)
        val slow = video("m", 10_000, ramp = SpeedRamp.flat(0.5f), keys = slowKeys)
        val trimmed = TimelineState(clips = listOf(slow)).withClipTrimmed("m", 2_000L, 0L).byId("m")
        check(near(trimmed.transformAt(trimmed.timelineEndMs).scale, 1.18f), "trimmed slow push-in ends at ${trimmed.transformAt(trimmed.timelineEndMs).scale}")
        check(near(trimmed.transformAt(0).scale, slow.transformAt(4_000).scale, 0.005f), "trimmed slow push-in starts off its frame")
    }

    // --- V3: a transition never opens a gap (script step 6). ---------------------
    run {
        val a = video("a", 5_000)
        val b = video("b", 5_000, start = 5_000)
        val c = video("c", 5_000, start = 10_000)
        val s = TimelineState(clips = listOf(a, b, c)).withTransition("b", Transition(TransitionType.CrossFade, 500))
        mainIsMagnetic(s, "transition")
        check(s.byId("c").timelineStartMs == s.byId("b").timelineEndMs, "a gap opened after the transition")
        check(s.durationMs == 14_500L, "a 500ms dissolve made the edit ${s.durationMs}, not 14500")
        val long = s.withTransition("b", Transition(TransitionType.CrossFade, 60_000))
        mainIsMagnetic(long, "long transition")
        val none = s.withTransition("b", Transition())
        check(none.durationMs == 15_000L, "removing the dissolve left ${none.durationMs}")
        val first = s.withTransition("a", Transition(TransitionType.CrossFade, 500))
        check(!first.byId("a").transitionIn.isActive, "the first clip took a transition from nothing")
        val reordered = s.withClipReordered("b", 0)
        mainIsMagnetic(reordered, "reorder a transitioned clip to the front")
    }

    // --- Reorder and drag on the main track. ---------------------------------------
    run {
        val s = TimelineState(clips = listOf(video("a", 3_000), video("b", 4_000, start = 3_000), video("c", 5_000, start = 7_000)))
        val r = s.withClipReordered("a", 2)
        check(r.baseVideoClips.map { it.id } == listOf("b", "c", "a"), "reorder a to the end: ${r.baseVideoClips.map { it.id }}")
        mainIsMagnetic(r, "reorder")
        check(s.withClipReordered("b", 1) == s, "a reorder to where it is changed something")
        check(s.withClipMoved("b", 300) == s, "a small drag on the main track changed something")
        val far = s.withClipMoved("a", 9_000)
        check(far.baseVideoClips.map { it.id } == listOf("b", "c", "a"), "a long drag did not reorder: ${far.baseVideoClips.map { it.id }}")
        mainIsMagnetic(far, "drag")

        // What the strip sends: one gesture measured from where it began, each
        // event asking for the clip to start at anchor + finger travel (see
        // ClipView). Drag b left past a, 30ms at a time.
        var dragged = s
        val anchor = s.byId("b").timelineStartMs
        var travel = 0L
        var steps = 0
        while (travel > -4_000L) {
            travel -= 30L
            val clip = dragged.byId("b")
            dragged = dragged.withClipMoved("b", anchor + travel - clip.timelineStartMs)
            steps++
        }
        check(dragged.baseVideoClips.map { it.id } == listOf("b", "a", "c"), "a strip drag of b past a: ${dragged.baseVideoClips.map { it.id }}")
        mainIsMagnetic(dragged, "strip drag")
        // Resting the finger either side of the swap point does not flip it back.
        val swapped = dragged
        for (wobble in listOf(10L, -10L, 25L, -25L)) {
            val clip = swapped.byId("b")
            val nudged = swapped.withClipMoved("b", anchor + travel + wobble - clip.timelineStartMs)
            check(nudged.baseVideoClips.map { it.id } == listOf("b", "a", "c"), "a ${wobble}ms wobble flipped the order back")
        }

        // A draft from before the track was magnetic, with a clip parked 2s after
        // the one before it and another laid over it: an edit keeps that spacing
        // and only closes what it opens itself. Close gaps takes it away.
        val legacy = TimelineState(clips = listOf(video("x", 3_000), video("y", 3_000, start = 5_000), video("z", 3_000, start = 6_000)))
        val trimmed = legacy.withClipTrimmed("x", 0L, -500L)
        check(trimmed.byId("y").timelineStartMs == 4_500L && trimmed.byId("z").timelineStartMs == 5_500L,
            "legacy trim: y at ${trimmed.byId("y").timelineStartMs}, z at ${trimmed.byId("z").timelineStartMs}")
        val cutFirst = legacy.copy(playheadMs = 1_000L).withSplitAtPlayhead()
        check(cutFirst.byId("y").timelineStartMs == 5_000L && cutFirst.byId("z").timelineStartMs == 6_000L,
            "legacy cut moved later clips: ${cutFirst.baseVideoClips.map { it.timelineStartMs }}")
        val dissolve = legacy.withTransition("z", Transition(TransitionType.CrossFade, 500))
        check(dissolve.byId("y").timelineStartMs == 5_000L, "a transition on z moved y")
        val closed = legacy.rippleVideo()
        mainIsMagnetic(closed, "legacy closed up")
    }

    // --- O7: overlays never share a row at the same moment (script step 7). ---------
    run {
        val base = video("base", 30_000)
        val first = video("o1", 8_000, start = 2_000, layer = 1)
        val second = video("o2", 8_000, start = 2_000, layer = 1)
        val s = TimelineState(clips = listOf(base)).withClipAdded(first).withClipAdded(second)
        check(s.byId("o2").layer == 2, "the second overlay landed on row ${s.byId("o2").layer}")
        overlayRowsClear(s, "add two")
        var full = s
        for (i in 3..MAX_LAYER) full = full.withClipAdded(video("o$i", 8_000, start = 2_000, layer = 1))
        val refused = full.withClipAdded(video("extra", 8_000, start = 2_000, layer = 1))
        check(refused == full, "an overlay with no free row was added anyway")
        val later = full.withClipAdded(video("later", 2_000, start = 20_000, layer = 1))
        check(later.byId("later").layer == 1, "an overlay where row 1 is free went to ${later.byId("later").layer}")

        // Dragging onto a neighbour stops against it, on its own row; dragged far
        // enough to fit past it, it goes there - still on its own row.
        val two = TimelineState(clips = listOf(base, video("p", 3_000, start = 0, layer = 1), video("q", 3_000, start = 5_000, layer = 1)))
        val blocked = two.withClipMoved("p", 3_000)
        check(blocked.byId("p").layer == 1 && blocked.byId("p").timelineEndMs == 5_000L,
            "an overlay dragged onto another: row ${blocked.byId("p").layer}, ends ${blocked.byId("p").timelineEndMs}")
        overlayRowsClear(blocked, "drag onto a neighbour")
        val past = blocked.withClipMoved("p", 9_000 - blocked.byId("p").timelineStartMs)
        check(past.byId("p").layer == 1 && past.byId("p").timelineStartMs == 9_000L,
            "an overlay dragged past its neighbour: row ${past.byId("p").layer}, at ${past.byId("p").timelineStartMs}")
        val back = past.withClipMoved("p", -3_000)
        check(back.byId("p").timelineStartMs == 8_000L, "dragged back, it did not stop against q's end: ${back.byId("p").timelineStartMs}")
        // A logo on row 3 dragged into another row-3 overlay while row 1 is free
        // does not drop under the row-2 picture.
        val stack = TimelineState(clips = listOf(base, video("logo", 2_000, start = 0, layer = 3),
            video("other", 2_000, start = 3_000, layer = 3), video("mid", 20_000, start = 0, layer = 2)))
        check(stack.withClipMoved("logo", 2_000).byId("logo").layer == 3, "a drag changed an overlay's row")
        var crowded = two
        for (l in 2..MAX_LAYER) crowded = crowded.withClipAdded(video("w$l", 30_000, start = 0, layer = l))
        val stopped = crowded.withClipMoved("p", 3_000)
        check(stopped.byId("p").layer == 1 && stopped.byId("p").timelineEndMs == 5_000L,
            "an overlay with nowhere to go ended at ${stopped.byId("p").timelineEndMs} on row ${stopped.byId("p").layer}")
        val grown = crowded.withClipTrimmed("p", 0L, 9_000L)
        check(grown.byId("p").timelineEndMs == 5_000L, "a tail trim ran an overlay into its neighbour: ${grown.byId("p").timelineEndMs}")
        val q = crowded.withClipTrimmed("q", -9_000L, 0L).byId("q")
        check(q.timelineStartMs >= 3_000L, "a head trim ran an overlay back into its neighbour: ${q.timelineStartMs}")

        // Layer changes skip taken rows, and the main track closes up or opens.
        val lift = TimelineState(clips = listOf(video("a", 3_000), video("b", 3_000, start = 3_000), video("c", 3_000, start = 6_000)))
            .withLayerChanged("b", 1)
        mainIsMagnetic(lift, "lift to overlay")
        check(lift.byId("c").timelineStartMs == 3_000L, "lifting b left a hole: c at ${lift.byId("c").timelineStartMs}")
        val drop = lift.withLayerChanged("b", -1)
        mainIsMagnetic(drop, "drop to main")
        check(drop.baseVideoClips.size == 3, "dropping b to the main track lost it")

        // Lower never falls through busy rows onto the main track.
        val rows = TimelineState(clips = listOf(video("m", 20_000), video("r1", 5_000, start = 0, layer = 1),
            video("r2", 5_000, start = 0, layer = 2), video("r3", 5_000, start = 1_000, layer = 3)))
        check(rows.withLayerChanged("r3", -1) == rows, "Lower on row 3 over busy rows 2 and 1 changed something")
        check(rows.withLayerChanged("r2", -1) == rows, "Lower on row 2 over a busy row 1 changed something")
        val r1Down = rows.withLayerChanged("r1", -1)
        check(r1Down.byId("r1").layer == 0 && r1Down.baseVideoClips.size == 2, "Lower on row 1 did not join the main track")
        val freeBelow = rows.withClipRemoved("r2").withLayerChanged("r3", -1)
        check(freeBelow.byId("r3").layer == 2, "Lower on row 3 over a free row 2 went to ${freeBelow.byId("r3").layer}")
    }

    // --- Fuzz: any sequence of edits keeps both promises. -----------------------------
    run {
        val rnd = Random(7)
        repeat(300) { round ->
            var s = TimelineState(
                clips = listOf(video("a", 6_000), video("b", 4_000, start = 6_000), video("c", 8_000, start = 10_000),
                    audio("m", 20_000), video("o", 3_000, start = 1_000, layer = 1))
            )
            repeat(25) {
                val ids = s.clips.map { it.id }
                val id = ids[rnd.nextInt(ids.size)]
                s = when (rnd.nextInt(9)) {
                    0 -> s.copy(playheadMs = rnd.nextLong(0, s.durationMs + 1)).withSplitAtPlayhead()
                    1 -> s.copy(playheadMs = rnd.nextLong(0, s.durationMs + 1), selectedClipId = id).withSplitAtPlayhead()
                    2 -> if (s.baseVideoClips.size > 1 || s.byId(id).kind != ClipKind.Video) s.withClipRemoved(id) else s
                    3 -> s.withClipTrimmed(id, rnd.nextLong(-3_000, 3_000), 0L)
                    4 -> s.withClipTrimmed(id, 0L, rnd.nextLong(-3_000, 3_000))
                    5 -> s.withClipMoved(id, rnd.nextLong(-8_000, 8_000))
                    6 -> s.withTransition(id, Transition(TransitionType.entries[rnd.nextInt(TransitionType.entries.size)], rnd.nextLong(0, 3_000)))
                    7 -> s.withLayerChanged(id, if (rnd.nextBoolean()) 1 else -1)
                    else -> s.withClipAdded(video("n${rnd.nextInt()}", rnd.nextLong(500, 5_000), start = rnd.nextLong(0, 20_000), layer = rnd.nextInt(0, 3)))
                }
                mainIsMagnetic(s, "fuzz $round")
                overlayRowsClear(s, "fuzz $round")
                check(s.clips.all { it.sourceSpanMs >= MIN_CLIP_MS && it.timelineStartMs >= 0 }, "fuzz $round: a clip shrank past the minimum or before zero")
            }
        }
    }

    // --- O6: one set of limits, and edits that move an animated picture. -------------
    run {
        val pip = video("p", 5_000, layer = 1)
        val s = TimelineState(clips = listOf(pip), playheadMs = 1_000)
        val big = s.withOverlayGeometry("p", scale = 9f, offsetX = 3f, offsetY = -3f, rotation = 400f).byId("p")
        check(big.scale == TransformLimits.SCALE_MAX && big.offsetXFraction == TransformLimits.OFFSET_MAX &&
            big.offsetYFraction == -TransformLimits.OFFSET_MAX && big.rotation == TransformLimits.ROTATION_MAX,
            "limits: ${big.staticTransform}")
        check(s.withOverlayGeometry("p", scale = 1.5f).byId("p").scale == 1.5f, "a scale of 1.5 was cut down")
        val fade = s.withOverlayGeometry("p", opacity = 0.4f).byId("p")
        check(fade.opacity == 0.4f && fade.scale == pip.scale, "an opacity change touched the placement")

        val animated = TimelineState(clips = listOf(pip.copy(keyframes = pushIn(5_000L))), playheadMs = 2_500)
        val moved = animated.withOverlayGeometry("p", offsetX = 0.5f).byId("p")
        check(near(moved.transformAt(2_500).offsetXFraction, 0.5f), "an animated overlay did not move: ${moved.transformAt(2_500)}")
        check(moved.keyframes.size == 3, "an animated overlay's edit wrote ${moved.keyframes.size} keys, not 3")
        check(moved.scale == pip.scale && moved.offsetXFraction == pip.offsetXFraction, "an animated edit wrote the dead static fields")
        // What a slider bound to placementAt reads back is what it just set -
        // the static field it used to read never changed, so the thumb jumped back.
        check(near(moved.placementAt(2_500).offsetXFraction, 0.5f), "the placement read back is ${moved.placementAt(2_500)}")

        // Playhead after the clip: the whole move shifts, the end key is not
        // rewritten on its own, and the value reads back.
        val after = animated.copy(playheadMs = 9_000)
        val slid = after.withOverlayGeometry("p", offsetX = 0.3f).byId("p")
        check(slid.keyframes.size == 2, "an off-clip edit added a key: ${slid.keyframes}")
        check(slid.keyframes.all { near(it.transform.offsetXFraction, 0.3f) }, "an off-clip edit did not move every key: ${slid.keyframes}")
        check(near(slid.placementAt(9_000).offsetXFraction, 0.3f), "off-clip placement reads ${slid.placementAt(9_000)}")
        val grown = after.withOverlayGeometry("p", scale = 2.36f).byId("p")
        check(near(grown.keyframes[0].transform.scale, 2f, 0.01f) && near(grown.keyframes[1].transform.scale, 2.36f, 0.01f),
            "an off-clip size change did not scale the whole move: ${grown.keyframes.map { it.transform.scale }}")

        // Rotation goes the same way: the Placement sheet's fourth slider used to
        // key the nearer end off the clip while the other three shifted the move.
        val turned = after.withOverlayGeometry("p", rotation = 30f).byId("p")
        check(turned.keyframes.size == 2 && turned.keyframes.all { near(it.transform.rotationDegrees, 30f) },
            "an off-clip turn did not turn the whole move: ${turned.keyframes.map { it.transform.rotationDegrees }}")

        // A main-track shot is placed by the same rule.
        val shot = video("m", 5_000, keys = pushIn(5_000L))
        val shotAfter = TimelineState(clips = listOf(shot, video("n", 3_000, start = 5_000)), playheadMs = 6_000)
        val shotSlid = shotAfter.withOverlayGeometry("m", offsetY = 0.2f).byId("m")
        check(shotSlid.keyframes.size == 2 && shotSlid.keyframes.all { near(it.transform.offsetYFraction, 0.2f) },
            "an off-clip shot edit rewrote one key: ${shotSlid.keyframes}")
    }

    // --- Placement's Reset (B6): still again, where the kind of clip lands. --------
    run {
        val keyed = video("p", 5_000, layer = 1, keys = pushIn(5_000L))
            .copy(scale = 0.7f, offsetXFraction = -0.2f, rotation = 12f, opacity = 0.5f, stabilizer = pushIn(5_000L))
        // Off the clip, as in the review: no key at the playhead, no key anywhere.
        val s = TimelineState(clips = listOf(video("m", 10_000), keyed), playheadMs = 9_000)
        val reset = s.withPlacementReset("p").byId("p")
        check(reset.keyframes.isEmpty(), "an overlay's reset left ${reset.keyframes.size} keys")
        check(reset.staticTransform == OVERLAY_LANDING, "an overlay reset to ${reset.staticTransform}, not where overlays land")
        check(reset.opacity == 0.5f && reset.stabilizer.size == 2, "a placement reset touched opacity or the stabilizer")
        check(s.withPlacementReset("p").byId("m") == s.byId("m"), "a placement reset touched another clip")

        val shotReset = TimelineState(clips = listOf(video("m", 5_000, keys = pushIn(5_000L)).copy(scale = 2f)), playheadMs = 2_000)
            .withPlacementReset("m").byId("m")
        check(shotReset.keyframes.isEmpty() && shotReset.staticTransform == Transform.Identity,
            "a shot's reset left ${shotReset.keyframes.size} keys at ${shotReset.staticTransform}")
    }

    // --- Duplicate (B6): the copy lands straight after, the track makes room. -----
    run {
        val a = video("a", 3_000, keys = pushIn(3_000))
        val b = video("b", 2_000, start = 3_000 - 500, transition = Transition(TransitionType.CrossFade, 500))
        val c = video("c", 4_000, start = 4_000)
        val s = TimelineState(clips = listOf(a, b, c).let { TimelineState(clips = it).rippleVideo().clips })
        val d = s.withClipDuplicated("a", "a2")
        mainIsMagnetic(d, "duplicate a")
        val order = d.baseVideoClips.map { it.id }
        check(order == listOf("a", "a2", "b", "c"), "duplicate: order is $order")
        val copy = d.byId("a2")
        check(copy.timelineStartMs == d.byId("a").timelineEndMs, "duplicate: the copy starts at ${copy.timelineStartMs}")
        check(copy.keyframes == a.keyframes && copy.sourceInMs == a.sourceInMs && copy.sourceOutMs == a.sourceOutMs,
            "duplicate: the copy lost its trim or keys")
        check(!copy.transitionIn.isActive, "duplicate: the copy dissolves into itself")
        check(d.byId("b").transitionIn == b.transitionIn, "duplicate: the next join lost its transition")
        check(d.byId("c").timelineStartMs - s.byId("c").timelineStartMs == copy.durationMs,
            "duplicate: the shots after moved by ${d.byId("c").timelineStartMs - s.byId("c").timelineStartMs}, not ${copy.durationMs}")
        check(d.selectedClipId == "a2", "duplicate: the copy is not selected")
        check(s.withClipDuplicated("missing", "x") == s, "duplicate of nothing changed the state")

        // The last shot, and a ramped one: the copy keeps the curve and the length.
        val ramped = TimelineState(clips = listOf(video("r", 4_000, ramp = SpeedRamp.flat(2f))))
        val rd = ramped.withClipDuplicated("r", "r2")
        mainIsMagnetic(rd, "duplicate ramped")
        check(rd.byId("r2").durationMs == 2_000L && rd.byId("r2").timelineStartMs == 2_000L,
            "duplicate ramped: ${rd.byId("r2").timelineStartMs}+${rd.byId("r2").durationMs}")

        // Overlays: its own row where free, the next free row where not, nothing when full.
        val pip = video("p", 2_000, start = 1_000, layer = 1)
        val free = TimelineState(clips = listOf(video("m", 10_000), pip)).withClipDuplicated("p", "p2")
        overlayRowsClear(free, "duplicate overlay")
        check(free.byId("p2").layer == 1 && free.byId("p2").timelineStartMs == 3_000L,
            "duplicate overlay: row ${free.byId("p2").layer} at ${free.byId("p2").timelineStartMs}")
        val blocked = TimelineState(clips = listOf(video("m", 10_000), pip, video("q", 2_000, start = 3_500, layer = 1)))
            .withClipDuplicated("p", "p2")
        overlayRowsClear(blocked, "duplicate overlay blocked")
        check(blocked.byId("p2").layer == 2, "duplicate overlay: a taken row put the copy on ${blocked.byId("p2").layer}")
        val full = TimelineState(clips = listOf(video("m", 10_000), pip) + (1..MAX_LAYER).map { video("w$it", 3_000, start = 3_000, layer = it) })
        check(full.withClipDuplicated("p", "p2") == full, "duplicate overlay with every row taken still added a clip")

        // Sound goes where the original ends; the main track is left alone.
        val song = audio("s", 5_000, start = 2_000)
        val sd = TimelineState(clips = listOf(video("m", 10_000), song)).withClipDuplicated("s", "s2")
        check(sd.byId("s2").timelineStartMs == 7_000L && sd.byId("m").timelineStartMs == 0L, "duplicate sound misplaced")
    }

    println("magnetic checks: main track, cuts, trims, keys, overlay rows")
    if (problems.isEmpty()) println("PASS - every edit leaves the main track butted, the rows clear and the keys on their frames")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
