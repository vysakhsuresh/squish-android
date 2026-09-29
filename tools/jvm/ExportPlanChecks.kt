import com.squish.app.media.ExportPlan
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.Transform
import com.squish.app.timeline.Transition
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.transformAt
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.system.exitProcess

/**
 * The export's decisions, executed: which clip lands on which layer, what the
 * stacked layers look like through every transition, which clock a keyframe
 * runs on, how much of a song is read and how 5.1 folds down.
 *
 * The transition checks composite the layers the way Media3's compositor does -
 * first input on top, straight alpha over black - and compare the result pixel
 * by pixel with what the preview draws for the same moment. That is the claim
 * the export makes, and it is checked on both stacking orders, because which
 * roll a shot lands on is an accident of the edit.
 */

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun video(
    id: String,
    start: Long,
    lengthMs: Long,
    layer: Int = 0,
    transition: Transition = Transition(),
    ramp: SpeedRamp = SpeedRamp(),
    srcIn: Long = 0L
) = Clip(
    id = id, kind = ClipKind.Video, label = id,
    sourceInMs = srcIn, sourceOutMs = srcIn + lengthMs, timelineStartMs = start,
    sourceDurationMs = srcIn + lengthMs + 10_000, layer = layer, transitionIn = transition, speedRamp = ramp
)

fun audio(id: String, start: Long, spanMs: Long, ramp: SpeedRamp = SpeedRamp(), srcIn: Long = 0L, fileMs: Long = 600_000L) =
    Clip(
        id = id, kind = ClipKind.Audio, label = id,
        sourceInMs = srcIn, sourceOutMs = srcIn + spanMs, timelineStartMs = start,
        sourceDurationMs = fileMs, speedRamp = ramp
    )

// ---- A two-layer compositor, the way Media3's draws ---------------------------

/** One shot's colour: a flat grey, so every pixel of it is the same number. */
data class Shot(val clip: Clip, val value: Float)

/**
 * The pixel at [x] (0..1 across the frame) with [rolls] stacked top first, each
 * drawn with its ExportPlan draw, straight alpha over black.
 */
fun composite(rolls: List<List<Clip>>, colours: Map<String, Float>, timeUs: Long, x: Float): Float {
    var dst = 0f
    for (r in rolls.indices.reversed()) {
        for (clip in rolls[r]) {
            val covers = timeUs >= clip.timelineStartMs * 1000 && timeUs < clip.timelineEndMs * 1000
            if (!covers) continue
            val d = ExportPlan.drawAt(rolls, clip, timeUs)
            val sx = x - d.shiftX
            val inside = sx >= 0f && sx <= 1f && x >= d.keepFrom && x <= d.keepTo
            val a = if (inside) d.alpha.coerceIn(0f, 1f) else 0f
            val src = colours.getValue(clip.id)
            dst = src * a + dst * (1 - a)
        }
    }
    return dst
}

/** What PreviewEngine.blend shows for outgoing [o] into incoming [i] at progress [p], pixel [x]. */
fun preview(type: TransitionType, p: Float, x: Float, o: Float, i: Float): Float = when (type) {
    TransitionType.None -> i
    TransitionType.CrossFade -> o * (1 - p) + i * p
    // Outgoing visible under a veil rising to black, then incoming under a veil falling away.
    TransitionType.DipToBlack -> if (p < 0.5f) o * (1 - 2 * p) else i * (2 * p - 1)
    // Incoming translated right by (1 - p) of the frame, over the outgoing.
    TransitionType.SlideLeft -> if (x >= 1 - p) i else o
    // Incoming revealed from the left edge to p.
    TransitionType.WipeRight -> if (x <= p) i else o
}

fun main() {
    // --- Layers: the clock first, overlays above the base, higher layers higher.
    run {
        val a = video("a", 0, 4000)
        val b = video("b", 3500, 4000, transition = Transition(TransitionType.CrossFade, 500))
        val pip1 = video("p1", 1000, 2000, layer = 1)
        val pip2 = video("p2", 1500, 2000, layer = 2)
        val pip1b = video("p1b", 2000, 1000, layer = 1) // overlaps p1 on the same layer
        val layers = ExportPlan.layers(listOf(a, b, pip1, pip2, pip1b))
        val roles = layers.layers.map { it.role }
        check(roles.first() == ExportPlan.Role.Clock, "the clock is not the first sequence: $roles")
        check(roles.count { it == ExportPlan.Role.Clock } == 1, "more than one clock")
        val firstBase = roles.indexOf(ExportPlan.Role.Base)
        check(roles.drop(firstBase).all { it == ExportPlan.Role.Base }, "an overlay sits below a base roll: $roles")
        val overlayIds = layers.layers.filter { it.role == ExportPlan.Role.Overlay }.map { l -> l.clips.map { it.id } }
        check(overlayIds.first() == listOf("p2"), "layer 2 is not on top of layer 1: $overlayIds")
        check(overlayIds.size == 3, "two overlapping clips on one layer did not spill onto two tracks: $overlayIds")
        check(overlayIds[1] == listOf("p1b"), "the later of two overlapping overlays is not drawn on top: $overlayIds")
        check(layers.baseRolls.size == 2, "a dissolve did not deal onto two rolls: ${layers.baseRolls}")
        check(layers.endMs == 7500L, "edit end ${layers.endMs}")
    }

    // --- Dealing the base track. ------------------------------------------------
    run {
        val butted = listOf(video("a", 0, 1000), video("b", 1000, 1000), video("c", 2000, 1000))
        check(ExportPlan.dealRolls(butted).size == 1, "butted clips took more than one roll")

        val gapped = listOf(video("a", 0, 1000), video("b", 3000, 1000))
        check(ExportPlan.dealRolls(gapped).size == 1, "a gap opened a second roll")

        val chain = listOf(
            video("a", 0, 3000), video("b", 2500, 3000, transition = Transition(TransitionType.CrossFade, 500)),
            video("c", 5000, 3000, transition = Transition(TransitionType.CrossFade, 500))
        )
        val rolls = ExportPlan.dealRolls(chain)
        check(rolls.size == 2, "a chain of dissolves needed ${rolls.size} rolls")
        check(rolls[0].map { it.id } == listOf("a", "c"), "chain did not alternate: ${rolls.map { r -> r.map { it.id } }}")

        val triple = listOf(video("a", 0, 5000), video("b", 1000, 5000), video("c", 2000, 5000))
        check(ExportPlan.dealRolls(triple).size == 3, "three stacked clips did not get three rolls")
        for (roll in ExportPlan.dealRolls(triple + chain)) {
            roll.zipWithNext().forEach { (x, y) -> check(y.timelineStartMs >= x.timelineEndMs, "a roll overlaps itself: ${x.id} ${y.id}") }
        }
    }

    // --- Pieces: every layer runs the length of the edit, clips where they belong.
    run {
        val clips = listOf(
            video("a", 0, 2000), video("b", 5000, 1000), video("c", 6010, 1000), // 10 ms gap: rounding
            video("d", 9000, 1000)
        )
        val layer = ExportPlan.Layer(ExportPlan.Role.Base, clips)
        val end = 12_000L
        val pieces = ExportPlan.pieces(layer, end)
        check(pieces.sumOf { it.durationMs } in (end - ExportPlan.MIN_GAP_MS)..end, "pieces run ${pieces.sumOf { it.durationMs }} of $end")
        var at = 0L
        for (piece in pieces) {
            if (piece is ExportPlan.Piece.Item) {
                check(abs(at - piece.clip.timelineStartMs) < ExportPlan.MIN_GAP_MS, "${piece.clip.id} lands at $at, not ${piece.clip.timelineStartMs}")
            }
            at += piece.durationMs
        }
        check(pieces.last() is ExportPlan.Piece.Gap, "no trailing blank: a layer that stops freezes its last frame")

        val clock = ExportPlan.pieces(ExportPlan.Layer(ExportPlan.Role.Clock, emptyList()), end)
        check(clock == listOf(ExportPlan.Piece.Gap(end)), "the clock is not one stretch the length of the edit: $clock")

        // Many small slips must not add up: each is made good at the next real gap.
        val drifty = (0 until 50).map { video("s$it", it * 1015L, 1000) }
        val dp = ExportPlan.pieces(ExportPlan.Layer(ExportPlan.Role.Base, drifty), 60_000)
        var t = 0L
        for (piece in dp) {
            if (piece is ExportPlan.Piece.Item) check(abs(t - piece.clip.timelineStartMs) < ExportPlan.MIN_GAP_MS, "slip carried to ${piece.clip.id}: $t vs ${piece.clip.timelineStartMs}")
            t += piece.durationMs
        }
    }

    // --- Transitions composite to what the preview shows, on either stacking. ---
    run {
        val types = TransitionType.entries
        var compared = 0
        for (type in types) for (incomingOnTop in listOf(true, false)) {
            val out = video("out", 0, 4000)
            val inc = video("in", 3000, 4000, transition = Transition(type, 1000))
            val rolls = if (incomingOnTop) listOf(listOf(inc), listOf(out)) else listOf(listOf(out), listOf(inc))
            val colours = mapOf("out" to 0.2f, "in" to 0.9f)
            for (step in 0..20) {
                val tUs = 3_000_000L + step * 50_000L - if (step == 20) 1 else 0
                val p = ((tUs - 3_000_000L) / 1_000_000.0).toFloat()
                for (xi in 0..40) {
                    val x = xi / 40f
                    // Exactly on a slide or wipe edge the two sides are one pixel apart; skip it.
                    if ((type == TransitionType.SlideLeft && abs(x - (1 - p)) < 0.02f) ||
                        (type == TransitionType.WipeRight && abs(x - p) < 0.02f)) continue
                    val got = composite(rolls, colours, tUs, x)
                    val want = preview(type, p, x, 0.2f, 0.9f)
                    compared++
                    check(abs(got - want) < 0.01f, "$type incomingOnTop=$incomingOnTop p=$p x=$x: file $got, preview $want")
                }
            }
            // Outside the overlap each shot is simply itself.
            check(abs(composite(rolls, colours, 1_000_000L, 0.5f) - 0.2f) < 1e-4f, "$type: outgoing not plain before the overlap")
            check(abs(composite(rolls, colours, 6_000_000L, 0.5f) - 0.9f) < 1e-4f, "$type: incoming not plain after the overlap")
        }
        check(compared > 1000, "only $compared pixels compared")

        // A third shot still running under a transition is hidden, as in the preview.
        val a = video("a", 0, 10_000)
        val b = video("b", 2000, 5000)
        val c = video("c", 4000, 5000, transition = Transition(TransitionType.CrossFade, 1000))
        val rolls = ExportPlan.dealRolls(listOf(a, b, c))
        val draws = ExportPlan.baseDrawsAt(rolls, 5_000_000L)
        check(draws["a"]?.alpha == 0f, "the third shot under a transition shows: ${draws["a"]}")

        // Neighbourhood: nothing that cannot share the screen, and nothing that can left out.
        val far = video("far", 20_000, 1000)
        val hood = ExportPlan.neighbourhood(rolls + listOf(listOf(far)), c).flatten().map { it.id }.toSet()
        check(hood == setOf("a", "b", "c"), "neighbourhood of c is $hood")
        check(!ExportPlan.takesPartInBlend(listOf(listOf(far)), far), "a lone clip takes part in a blend")
    }

    // --- Keyframes run on played time; the stabilizer on source time. -----------
    run {
        val push = listOf(
            Keyframe(0L, Transform(scale = 1f), KeyframeEasing.Linear),
            Keyframe(4000L, Transform(scale = 1.18f), KeyframeEasing.Linear)
        )
        for (speed in listOf(0.25f, 0.5f, 1f, 2f)) {
            val span = (4000 * speed).toLong() // played length 4000 ms whatever the speed
            val clip = video("k", 0, span, ramp = SpeedRamp.flat(speed)).copy(keyframes = push)
            check(abs(clip.durationMs - 4000) <= 2, "test clip at $speed plays ${clip.durationMs}")
            val end = ExportPlan.motionAt(clip, ExportPlan.MotionPart.User, span)
            check(abs(end.scale - 1.18f) < 0.005f, "at ${speed}x the push-in ends at ${end.scale}, not 1.18")
            val mid = ExportPlan.motionAt(clip, ExportPlan.MotionPart.User, span / 2)
            check(abs(mid.scale - 1.09f) < 0.005f, "at ${speed}x the push-in is at ${mid.scale} half way, not 1.09")
        }
        val shake = listOf(Keyframe(5000L, Transform(offsetXFraction = 0.1f)), Keyframe(6000L, Transform(offsetXFraction = -0.1f)))
        val stab = video("s", 0, 2000, ramp = SpeedRamp.flat(0.5f), srcIn = 5000).copy(stabilizer = shake)
        val s = ExportPlan.motionAt(stab, ExportPlan.MotionPart.Stabilizer, 1000)
        val want = shake.transformAt(6000L, Transform.Identity)
        check(abs(s.offsetXFraction - want.offsetXFraction) < 1e-4f, "stabilizer not on source time: ${s.offsetXFraction}")
        check(ExportPlan.motionAt(stab, ExportPlan.MotionPart.User, 1000).isIdentity, "a clip with no keyframes moved")
        check(!ExportPlan.hasMotion(video("still", 0, 1000), ExportPlan.MotionPart.User), "a still clip has user motion")
    }

    // --- Placement matrix: square pixels, clockwise degrees, screen-down Y. ------
    run {
        fun apply(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[1] * y + m[4]) to (m[2] * x + m[3] * y + m[5])
        for (aspect in listOf(16f / 9f, 9f / 16f, 1f, 4f / 3f)) {
            for (deg in listOf(0f, 15f, 30f, 90f, 180f, -45f)) {
                for (scale in listOf(1f, 0.4f, 1.5f)) {
                    val m = ExportPlan.placementMatrix(Transform(scale = scale, rotationDegrees = deg), aspect)
                    // Pixel lengths (x in half-widths, y in half-heights) scale by exactly [scale].
                    for ((vx, vy) in listOf(1f to 0f, 0f to 1f, 0.6f to -0.3f)) {
                        val (tx, ty) = apply(m, vx, vy)
                        val before = hypot(vx * aspect, vy)
                        val after = hypot(tx * aspect, ty)
                        check(abs(after - before * scale) < 1e-3f, "aspect $aspect $deg deg x$scale sheared ($vx,$vy): $before -> $after")
                    }
                }
            }
            // 90 degrees clockwise on screen: right goes to down (negative NDC y).
            val (rx, ry) = apply(ExportPlan.placementMatrix(Transform(rotationDegrees = 90f), aspect), 1f, 0f)
            check(abs(rx) < 1e-4f && ry < 0f, "aspect $aspect: 90 degrees is not clockwise ($rx, $ry)")
        }
        val (ox, oy) = apply(ExportPlan.placementMatrix(Transform(offsetXFraction = 0.45f, offsetYFraction = 0.2f), 16f / 9f), 0f, 0f)
        check(abs(ox - 0.45f) < 1e-5f && abs(oy + 0.2f) < 1e-5f, "offsets not in half-canvas units with screen-down Y: ($ox, $oy)")
    }

    // --- Sound: placed where it was put, read in full through its ramp. ----------
    run {
        // A song at 20 s on a 38 s edit: silence until 20 s, then the song.
        val s = ExportPlan.audioSlice(audio("song", 20_000, 60_000), 38_000)!!
        check(s.leadMs == 20_000L, "song lead ${s.leadMs}")
        check(s.sourceOutMs - s.sourceInMs == 18_000L, "song read ${s.sourceOutMs - s.sourceInMs} ms with 18 s of edit left")

        // 60 s of song at 2x plays 30 s and must be read in full.
        val fast = ExportPlan.audioSlice(audio("fast", 0, 60_000, SpeedRamp.flat(2f)), 60_000)!!
        check(fast.sourceOutMs - fast.sourceInMs == 60_000L, "2x song read ${fast.sourceOutMs - fast.sourceInMs} ms, not all 60 s")

        // At 0.5x the room runs out first; the read stops where the edit does, in source time.
        val slow = ExportPlan.audioSlice(audio("slow", 0, 60_000, SpeedRamp.flat(0.5f)), 60_000)!!
        check(slow.sourceOutMs - slow.sourceInMs == 30_000L, "0.5x song read ${slow.sourceOutMs - slow.sourceInMs} ms, not 30 s")

        // Trimmed: never past the trim-out point, whatever the speed.
        val trimmed = ExportPlan.audioSlice(audio("t", 1000, 10_000, SpeedRamp.flat(0.5f), srcIn = 5000), 600_000)!!
        check(trimmed.sourceOutMs == 15_000L, "trimmed song read to ${trimmed.sourceOutMs}, past its out point")

        // Dragged to start before zero: heard from where zero falls in it.
        val early = ExportPlan.audioSlice(audio("e", -4000, 20_000), 30_000)!!
        check(early.leadMs == 0L && early.sourceInMs == 4000L && early.skippedSourceMs == 4000L, "early song: $early")

        check(ExportPlan.audioSlice(audio("late", 40_000, 10_000), 38_000) == null, "a song after the end was kept")
        check(ExportPlan.audioSlice(audio("file", 0, 60_000, fileMs = 20_000), 60_000)!!.sourceOutMs == 20_000L, "read past the end of the file")
    }

    // --- Fold-down: mono and stereo untouched, wider never clips, no LFE. --------
    run {
        for (n in 1..ExportPlan.MAX_INPUT_CHANNELS) {
            val out = ExportPlan.downmixOutputChannels(n)
            val m = ExportPlan.downmixCoefficients(n)
            check(m.size == n * out, "$n channels: ${m.size} coefficients for $out outputs")
            if (n <= 2) {
                check(out == n, "$n channels changed shape")
                for (i in 0 until n) for (o in 0 until n) check(m[i * n + o] == if (i == o) 1f else 0f, "$n channels is not identity")
                continue
            }
            check(out == 2, "$n channels became $out")
            for (o in 0 until 2) {
                val sum = (0 until n).sumOf { m[it * 2 + o].toDouble() }
                check(sum <= 1.0001, "$n channels: output $o can clip (sum $sum)")
                check(sum > 0.5, "$n channels: output $o nearly silent (sum $sum)")
            }
            check(m[0] > 0f && m[1] == 0f && m[2] == 0f && m[3] > 0f, "$n channels: front left/right not kept apart")
            if (n >= 6) check(m[3 * 2] == 0f && m[3 * 2 + 1] == 0f, "$n channels: the LFE is mixed in")
            // Left and right weigh the same.
            val l = (0 until n).sumOf { m[it * 2].toDouble() }
            val r = (0 until n).sumOf { m[it * 2 + 1].toDouble() }
            check(abs(l - r) < 1e-4, "$n channels: left $l and right $r differ")
        }
    }

    // --- What each sequence declares: the clock is primary for every track. -----
    run {
        val dissolve = Transition(TransitionType.CrossFade, 500)
        val heard: (Clip) -> Boolean = { it.id.startsWith("h") }
        // The device's failing edit: a shot, a photo dissolved onto it, and a
        // heard overlay over both - so the second roll opens on a still.
        val edit = listOf(video("a", 0, 5200), video("b", 4700, 3000, transition = dissolve), video("h1", 0, 8363, layer = 1))
        val layers = ExportPlan.layers(edit)
        val roles = layers.layers.map { it.role }
        check(roles == listOf(ExportPlan.Role.Clock, ExportPlan.Role.Overlay, ExportPlan.Role.Base, ExportPlan.Role.Base), "layers $roles")
        val tracks = ExportPlan.sequenceTracks(layers, videoOut = true, baseAudio = true, heard = heard)
        check(tracks.size == layers.layers.size, "one answer per layer: ${tracks.size} for ${layers.layers.size}")
        check(tracks[0] == ExportPlan.Tracks(video = true, sound = true), "the clock does not carry sound while a layer does: ${tracks[0]}")
        check(tracks.drop(1).all { it == ExportPlan.Tracks(video = true, sound = true) }, "a heard row or a base roll without sound: $tracks")
        // The invariant the export leans on: any track a layer declares, the
        // clock declares too, so the clock is the sequence that makes its exporter.
        fun primaryForAll(answer: List<ExportPlan.Tracks?>) {
            val clock = answer[0] ?: return
            answer.drop(1).filterNotNull().forEach { t ->
                check(!t.sound || clock.sound, "a layer declares sound the clock does not: $answer")
                check(!t.video || clock.video, "a layer declares picture the clock does not: $answer")
            }
        }
        primaryForAll(tracks)
        // Camera muted and nothing heard: picture only everywhere, and no silent
        // track written for nothing.
        val quiet = ExportPlan.sequenceTracks(layers, videoOut = true, baseAudio = false, heard = { false })
        check(quiet.all { it == ExportPlan.Tracks(video = true, sound = false) }, "a muted edit declares sound: $quiet")
        // A photo row over a camera that is on: the row is picture only, the
        // clock still carries sound for the rolls.
        val photoRow = ExportPlan.sequenceTracks(layers, videoOut = true, baseAudio = true, heard = { false })
        check(photoRow[1] == ExportPlan.Tracks(video = true, sound = false), "an unheard row declares sound: ${photoRow[1]}")
        check(photoRow[0]?.sound == true, "the clock dropped sound with the rolls still carrying it")
        primaryForAll(photoRow)
        // A heard row over a muted camera: the clock follows the row.
        val rowOnly = ExportPlan.sequenceTracks(layers, videoOut = true, baseAudio = false, heard = heard)
        check(rowOnly[0]?.sound == true && rowOnly[2]?.sound == false, "clock/roll sound with only the row heard: $rowOnly")
        primaryForAll(rowOnly)
        // Sound only: no clock, the rolls and the heard row as sound, a silent row left out.
        val soundOnly = ExportPlan.sequenceTracks(layers, videoOut = false, baseAudio = true, heard = heard)
        check(soundOnly[0] == null, "a sound-only export built the clock")
        check(soundOnly.drop(1).all { it == ExportPlan.Tracks(video = false, sound = true) }, "sound-only layers: $soundOnly")
        val silentRow = ExportPlan.sequenceTracks(layers, videoOut = false, baseAudio = true, heard = { false })
        check(silentRow[1] == null && silentRow.count { it != null } == layers.baseLayers.size, "sound-only with a silent row: $silentRow")
    }

    // --- When the compositor is needed. -----------------------------------------
    run {
        check(!ExportPlan.needsCompositing(listOf(video("a", 0, 1000), video("b", 1000, 1000))), "butted clips composited")
        check(ExportPlan.needsCompositing(listOf(video("a", 500, 1000))), "a late first clip did not composite")
        check(ExportPlan.needsCompositing(listOf(video("a", 0, 1000), video("b", 1500, 1000))), "a gap did not composite")
        check(ExportPlan.needsCompositing(listOf(video("a", 0, 1000), video("p", 0, 500, layer = 1))), "an overlay did not composite")
        check(
            ExportPlan.needsCompositing(listOf(video("a", 0, 1000), video("b", 900, 1000, transition = Transition(TransitionType.CrossFade, 100)))),
            "a dissolve did not composite"
        )
        // Rounding is not a gap - but only while what it adds up to stays under
        // the bound the composited path holds every clip to.
        check(!ExportPlan.needsCompositing(listOf(video("a", 0, 1000), video("b", 1010, 1000))), "a 10 ms rounding gap composited")
        check(!ExportPlan.needsCompositing(listOf(video("a", 12, 1000), video("b", 1012, 1000))), "a 12 ms late start composited")
        check(
            ExportPlan.needsCompositing(listOf(video("a", 12, 1000), video("b", 1022, 1000))),
            "12 ms late plus a 10 ms gap played end to end: the second clip 22 ms early"
        )
        check(
            ExportPlan.needsCompositing(listOf(video("a", 0, 1000), video("b", 1010, 1000), video("c", 2020, 1000))),
            "two 10 ms gaps played end to end: the third clip 20 ms early"
        )
        check(ExportPlan.needsCompositing(listOf(video("a", 0, 1000), video("b", 990, 1000))), "an overlap with no transition played end to end")
        check(!ExportPlan.needsCompositing(listOf(video("a", -300, 1000), video("b", 700, 1000))), "a clip dragged before zero composited")

        // Whatever the cuts-only path accepts is within the bound it is allowed.
        for (gaps in listOf(listOf(0L, 5L, 5L, 9L), listOf(19L), listOf(3L, 3L, 3L, 3L, 3L, 3L))) {
            var at = gaps.first()
            val clips = mutableListOf(video("g0", at, 1000))
            gaps.drop(1).forEachIndexed { i, g -> at += 1000 + g; clips += video("g${i + 1}", at, 1000) }
            if (!ExportPlan.needsCompositing(clips)) {
                var played = 0L
                for (c in clips) {
                    check(abs(played - c.timelineStartMs) < ExportPlan.MIN_GAP_MS, "end to end, ${c.id} is ${c.timelineStartMs - played} ms early")
                    played += c.durationMs
                }
            }
        }
    }

    // --- The clock is always there, first, however short the edit. ----------------
    run {
        for (end in listOf(0L, 1L, 10L, 19L, 20L, 5_000L)) {
            val clock = ExportPlan.pieces(ExportPlan.Layer(ExportPlan.Role.Clock, emptyList()), end)
            check(clock.size == 1 && clock[0] is ExportPlan.Piece.Gap && clock[0].durationMs >= 1L, "no clock for a $end ms edit: $clock")
        }
        val tiny = ExportPlan.layers(listOf(video("a", 0, 10), video("p", 0, 10, layer = 1)))
        check(tiny.layers.first().role == ExportPlan.Role.Clock, "a 10 ms edit's first layer is not the clock")
        check(ExportPlan.pieces(tiny.layers.first(), tiny.endMs).isNotEmpty(), "a 10 ms edit's clock has nothing in it")
    }

    if (problems.isEmpty()) {
        println("PASS - layers, transitions, clocks, placement, sound slices and fold-downs all hold")
    } else {
        problems.take(15).forEach { println("FAIL - $it") }
        println("${problems.size} problems")
        exitProcess(1)
    }
}
