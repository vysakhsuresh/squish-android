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
fun composite(rolls: List<List<Clip>>, colours: Map<String, Float>, timeUs: Long, x: Float, y: Float = 0.5f): Float {
    var dst = 0f
    for (r in rolls.indices.reversed()) {
        for (clip in rolls[r]) {
            val covers = timeUs >= clip.timelineStartMs * 1000 && timeUs < clip.timelineEndMs * 1000
            if (!covers) continue
            val d = ExportPlan.drawAt(rolls, clip, timeUs)
            dst = drawPixel(d, colours.getValue(clip.id), dst, x, y)
        }
    }
    return dst
}

/**
 * One draw's pixel at ([x], [y]) over [dst], the way the transition shader
 * samples it: shifted, scaled about the centre, cut to the kept rectangle,
 * whitened, then straight alpha over what is there.
 */
fun drawPixel(d: ExportPlan.Draw, src: Float, dst: Float, x: Float, y: Float): Float {
    val sx = (x - d.shiftX - 0.5f) / d.scale + 0.5f
    val sy = (y - d.shiftY - 0.5f) / d.scale + 0.5f
    val inside = sx >= 0f && sx <= 1f && sy >= 0f && sy <= 1f &&
        x >= d.keepFrom && x <= d.keepTo && y >= d.keepFromY && y <= d.keepToY
    val a = if (inside) d.alpha.coerceIn(0f, 1f) else 0f
    val lit = src + (1f - src) * d.white.coerceIn(0f, 1f)
    return lit * a + dst * (1 - a)
}

/**
 * The same pixel the way the shader itself computes it, from the uniforms the
 * program is handed (ExportPlan.Draw.shaderUniforms), in the texture's own
 * space: [y] is the screen's, down the picture, and the texture's row is
 * 1 - y. squish_transition_es2.glsl line for line, so a draw that reads right
 * on the preview and upside down in the file fails here rather than on the
 * phone - which is where the first vertical transitions failed.
 */
fun shaderPixel(u: ExportPlan.ShaderUniforms, src: Float, dst: Float, x: Float, y: Float): Float {
    val uvx = x
    val uvy = 1f - y
    val sx = (uvx - u.shiftX - 0.5f) / maxOf(u.scale, 0.001f) + 0.5f
    val sy = (uvy - u.shiftY - 0.5f) / maxOf(u.scale, 0.001f) + 0.5f
    val inside = sx >= 0f && sx <= 1f && sy >= 0f && sy <= 1f &&
        uvx >= u.keepFromX && uvx <= u.keepToX && uvy >= u.keepFromY && uvy <= u.keepToY
    val lit = src + (1f - src) * u.white
    val a = if (inside) u.alpha else 0f
    return lit * a + dst * (1 - a)
}

/**
 * What the four original transitions looked like on screen before the preview
 * drew from ExportPlan.blend itself: outgoing [o] into incoming [i] at progress
 * [p], pixel [x]. Kept as the record of what the phone was shown, so the shared
 * function cannot drift from it.
 */
fun preview(type: TransitionType, p: Float, x: Float, o: Float, i: Float): Float? = when (type) {
    TransitionType.None -> i
    TransitionType.CrossFade -> o * (1 - p) + i * p
    // Outgoing visible under a veil rising to black, then incoming under a veil falling away.
    TransitionType.DipToBlack -> if (p < 0.5f) o * (1 - 2 * p) else i * (2 * p - 1)
    // Incoming translated right by (1 - p) of the frame, over the outgoing.
    TransitionType.SlideLeft -> if (x >= 1 - p) i else o
    // Incoming revealed from the left edge to p.
    TransitionType.WipeRight -> if (x <= p) i else o
    else -> null
}

/** The pixel the preview draws: the two shots stacked incoming over outgoing, from the shared blend. */
fun previewPixel(type: TransitionType, p: Float, x: Float, y: Float, o: Float, i: Float): Float {
    val (inDraw, outDraw) = ExportPlan.blend(type, p, incomingOnTop = true)
    return drawPixel(inDraw, i, drawPixel(outDraw, o, 0f, x, y), x, y)
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
        check(pieces.last() is ExportPlan.Piece.Clear, "no trailing blank: a layer that stops freezes its last frame")
        check(pieces.none { it is ExportPlan.Piece.Gap }, "a layer's empty stretch is Media3's black gap, not the still: $pieces")

        // The clock: one frame of gap, then a still to the end.
        val lead = ExportPlan.clockLeadMs(60)
        val clock = ExportPlan.pieces(ExportPlan.Layer(ExportPlan.Role.Clock, emptyList()), end, lead)
        check(clock == listOf(ExportPlan.Piece.Gap(lead), ExportPlan.Piece.Clear(end - lead)), "the clock is not a frame of gap and a still to the end: $clock")

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
        var kept = 0
        for (type in types) for (incomingOnTop in listOf(true, false)) {
            val out = video("out", 0, 4000)
            val inc = video("in", 3000, 4000, transition = Transition(type, 1000))
            val rolls = if (incomingOnTop) listOf(listOf(inc), listOf(out)) else listOf(listOf(out), listOf(inc))
            val colours = mapOf("out" to 0.2f, "in" to 0.9f)
            for (step in 0..20) {
                val tUs = 3_000_000L + step * 50_000L - if (step == 20) 1 else 0
                val p = ((tUs - 3_000_000L) / 1_000_000.0).toFloat()
                for (xi in 0..40) for (yi in 0..8) {
                    val x = xi / 40f
                    val y = yi / 8f
                    // Exactly on a moving edge the two sides are one pixel apart; skip it.
                    val (inDraw, outDraw) = ExportPlan.blend(type, p, incomingOnTop = true)
                    val edges = listOf(inDraw, outDraw).flatMap { d ->
                        listOf(d.keepFrom to x, d.keepTo to x, d.shiftX to x, 1f + d.shiftX to x, d.keepFromY to y, d.keepToY to y, d.shiftY to y, 1f + d.shiftY to y)
                    }
                    if (edges.any { (edge, at) -> edge >= 0f && edge <= 1f && abs(at - edge) < 0.02f }) continue
                    val got = composite(rolls, colours, tUs, x, y)
                    // The four original kinds against the record of what the phone
                    // showed; every kind against the preview's own stacking.
                    preview(type, p, x, 0.2f, 0.9f)?.let { want ->
                        kept++
                        check(abs(got - want) < 0.01f, "$type incomingOnTop=$incomingOnTop p=$p x=$x: file $got, preview $want")
                    }
                    val shown = previewPixel(type, p, x, y, 0.2f, 0.9f)
                    compared++
                    check(abs(got - shown) < 0.01f, "$type incomingOnTop=$incomingOnTop p=$p x=$x y=$y: file $got, preview $shown")
                    // And the shader itself, fed the uniforms the program sets,
                    // in the texture's upside-down space: the same pixel again.
                    val shaded = shaderPixel(inDraw.shaderUniforms(), 0.9f, shaderPixel(outDraw.shaderUniforms(), 0.2f, 0f, x, y), x, y)
                    check(abs(shaded - shown) < 0.01f, "$type p=$p x=$x y=$y: the shader draws $shaded, the preview $shown")
                }
            }
            // Outside the overlap each shot is simply itself.
            check(abs(composite(rolls, colours, 1_000_000L, 0.5f) - 0.2f) < 1e-4f, "$type: outgoing not plain before the overlap")
            check(abs(composite(rolls, colours, 6_000_000L, 0.5f) - 0.9f) < 1e-4f, "$type: incoming not plain after the overlap")
            // At the overlap's start the old shot is whole (a plain cut over an
            // overlap is the new shot from its first frame); by its end the new one is.
            if (type != TransitionType.None) {
                check(abs(composite(rolls, colours, 3_000_000L, 0.5f) - 0.2f) < 1e-4f, "$type incomingOnTop=$incomingOnTop: the overlap does not open on the old shot")
            }
            check(abs(composite(rolls, colours, 3_999_999L, 0.5f) - 0.9f) < 0.02f, "$type incomingOnTop=$incomingOnTop: the overlap does not close on the new shot")
        }
        check(compared > 5000 && kept > 1000, "only $compared pixels compared, $kept against the record")
        // Every kind draws something a hard cut does not, somewhere in its run.
        val samples = listOf(0.01f, 0.25f, 0.5f, 0.75f, 0.99f)
        for (type in types.filter { it != TransitionType.None }) {
            var differs = false
            for (pi in 1..19) for (x in samples) for (y in samples) {
                val p = pi / 20f
                val cut = if (p >= 0.5f) 0.9f else 0.2f
                if (abs(previewPixel(type, p, x, y, 0.2f, 0.9f) - cut) > 0.01f) differs = true
            }
            check(differs, "$type is a plain cut")
        }
        check(TransitionType.entries.map { it.category }.distinct().size == 4, "a tab has no transitions")

        // --- The softness of a Defocus join, which no pixel above can see. ---
        //
        // drawPixel and shaderPixel are fed one flat colour per shot, and a box
        // of nine taps of one colour is that colour - so the blur is invisible
        // to every check above and needs its own. It is the only part of a draw
        // that is not a function of the pixel.
        run {
            var peak = 0f
            for (step in 0..20) {
                val p = step / 20f
                for (onTop in listOf(true, false)) {
                    val (inD, outD) = ExportPlan.blend(TransitionType.Defocus, p, onTop)
                    check(inD.blur == outD.blur, "defocus p=$p: the two shots are softened differently, ${inD.blur} and ${outD.blur}")
                    check(inD.blur >= 0f && inD.blur <= ExportPlan.MAX_BLUR, "defocus p=$p: a softness of ${inD.blur}, past the ring's reach")
                    val other = ExportPlan.blend(TransitionType.Defocus, p, !onTop)
                    check(other.first.blur == inD.blur, "defocus p=$p: the softness depends on which roll the shot fell on")
                    peak = maxOf(peak, inD.blur)
                }
            }
            check(peak > 0.005f, "a defocus never softens more than $peak")
            // Sharp at both ends, so the shots either side of the join are
            // themselves and the overlap opens and closes on a plain picture.
            check(ExportPlan.blend(TransitionType.Defocus, 0f, true).let { it.first.blur == 0f && it.second.blur == 0f }, "a defocus opens soft")
            check(ExportPlan.blend(TransitionType.Defocus, 1f, true).let { it.first.blur == 0f && it.second.blur == 0f }, "a defocus closes soft")
            // Softest at the cut itself.
            val mid = ExportPlan.blend(TransitionType.Defocus, 0.5f, true).first.blur
            listOf(0.1f, 0.25f, 0.75f, 0.9f).forEach { p ->
                check(ExportPlan.blend(TransitionType.Defocus, p, true).first.blur < mid, "a defocus is as soft at $p as at its middle")
            }
            // And it is still a dissolve underneath: the picture crosses over.
            check(ExportPlan.blend(TransitionType.Defocus, 0.5f, true).first.alpha in 0.4f..0.6f, "a defocus does not cross over")

            // No other kind softens anything. A blur that leaked into a slide
            // would cost every frame of it nine taps for nothing.
            for (type in TransitionType.entries.filter { it != TransitionType.Defocus }) {
                for (step in 0..10) {
                    val p = step / 10f
                    val (inD, outD) = ExportPlan.blend(type, p, true)
                    check(inD.blur == 0f && outD.blur == 0f, "$type softens at p=$p")
                }
            }

            // The pass is not skipped for a draw whose only move is the blur.
            check(!ExportPlan.Draw(blur = 0.01f).isPlain, "a softened draw reads as plain and is skipped")
            check(ExportPlan.Draw().isPlain, "an untouched draw does not read as plain")
            // Two softenings of one picture are one softening, the wider.
            check(ExportPlan.Draw(blur = 0.004f).over(ExportPlan.Draw(blur = 0.01f)).blur == 0.01f, "two blurs were added rather than the wider taken")
            check(ExportPlan.Draw(blur = 0.012f).over(ExportPlan.Draw(blur = 0.003f)).blur == 0.012f, "the wider blur was lost")
            // Symmetric about the pixel, so it is not turned over on the way in
            // the way the shift and the kept band are, and it is clamped.
            check(ExportPlan.Draw(blur = 0.008f).shaderUniforms().blur == 0.008f, "the softness was changed on its way to the shader")
            check(ExportPlan.Draw(blur = 1f).shaderUniforms().blur == ExportPlan.MAX_BLUR, "an absurd softness reached the shader whole")
            check(ExportPlan.Draw(blur = -1f).shaderUniforms().blur == 0f, "a negative softness reached the shader")
        }

        // --- A Burn out leaves the new shot whole and hangs the old one over it. ---
        run {
            // The old shot is itself at the start - the overlap opens on it, as
            // every kind must - and white before the join is half over.
            check(ExportPlan.blend(TransitionType.BurnOut, 0f, true).second.white == 0f, "a burn out starts already white")
            check(ExportPlan.blend(TransitionType.BurnOut, 0.5f, true).second.white > 0.99f, "a burn out is not white by its middle")
            check(ExportPlan.blend(TransitionType.BurnOut, 1f, true).second.white > 0.99f, "a burn out comes off white")
            // Rising, never falling: what thins is the ghost's alpha, not its white.
            var last = -1f
            for (step in 0..20) {
                val w = ExportPlan.blend(TransitionType.BurnOut, step / 20f, true).second.white
                check(w >= last - 1e-6f, "a burn out dims again at ${step / 20f}")
                last = w
            }
            // The new shot is up from the first frame underneath, which is what
            // makes it a burn out rather than a flash: with the incoming on the
            // lower roll it is drawn plain and the old one fades off it.
            check(ExportPlan.blend(TransitionType.BurnOut, 0.5f, incomingOnTop = false).first.isPlain, "a burn out hides the new shot")
            // And with it on top the mix is a straight dissolve, so the two
            // stackings land on the same picture (the loop above holds that).
            check(ExportPlan.blend(TransitionType.BurnOut, 0.5f, incomingOnTop = true).first.alpha in 0.49f..0.51f, "a burn out does not cross over evenly")
            // Neither of the two new kinds moves the picture: a defocus and a
            // burn out are both cuts you do not feel as a camera move.
            for (type in listOf(TransitionType.Defocus, TransitionType.BurnOut)) {
                for (step in 0..10) {
                    val (inD, outD) = ExportPlan.blend(type, step / 10f, true)
                    listOf(inD, outD).forEach {
                        check(it.shiftX == 0f && it.shiftY == 0f && it.scale == 1f, "$type moves the picture at ${step / 10f}")
                        check(it.keepFrom == 0f && it.keepTo == 1f && it.keepFromY == 0f && it.keepToY == 1f, "$type cuts the picture at ${step / 10f}")
                    }
                }
            }
        }
        // A flicker ends on the new shot however it is sampled.
        check(ExportPlan.flickerShowsIncoming(1f) && !ExportPlan.flickerShowsIncoming(0f), "flicker ends")

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

        // An arrival runs on played time too, laid over the keys.
        val arriving = video("a", 0, 2000, ramp = SpeedRamp.flat(0.5f))
            .copy(keyframes = push, arrival = com.squish.app.timeline.ClipArrival.SlideLeft, arrivalMs = 1000L)
        check(ExportPlan.hasMotion(arriving, ExportPlan.MotionPart.User), "an arrival is not motion")
        val opening = ExportPlan.motionAt(arriving, ExportPlan.MotionPart.User, 0L)
        check(opening.offsetXFraction >= 2f && abs(opening.scale - 1f) < 1e-3f, "the arrival's first frame: $opening")
        // 500 ms of source at half speed is 1000 ms played: the arrival has landed, the push is a quarter in.
        val landed = ExportPlan.motionAt(arriving, ExportPlan.MotionPart.User, 500L)
        check(landed.offsetXFraction == 0f && abs(landed.scale - 1.045f) < 0.01f, "the arrival on the source clock: $landed")
    }

    // --- A clip's own draw: its fade, and a transition on an overlay as its arrival.
    run {
        val plain = video("p", 0, 2000, layer = 1)
        check(ExportPlan.ownDrawAt(plain, 500L).isPlain && !ExportPlan.drawsOwn(plain), "a plain overlay draws itself")
        val dim = plain.copy(opacity = 0.4f)
        check(ExportPlan.drawsOwn(dim) && abs(ExportPlan.ownDrawAt(dim, 500L).alpha - 0.4f) < 1e-4f, "a 40% overlay is not drawn at 40%")
        val sliding = plain.copy(transitionIn = Transition(TransitionType.SlideLeft, 500))
        check(ExportPlan.drawsOwn(sliding), "an overlay's transition is not drawn")
        check(abs(ExportPlan.ownDrawAt(sliding, 0L).shiftX - 1f) < 1e-4f, "the overlay's slide does not start off screen")
        check(abs(ExportPlan.ownDrawAt(sliding, 250L).shiftX - 0.5f) < 1e-4f, "the overlay's slide is not half way at half its length")
        check(ExportPlan.ownDrawAt(sliding, 500L).isPlain, "the overlay's slide has not landed at its length")
        val faded = sliding.copy(opacity = 0.5f, transitionIn = Transition(TransitionType.CrossFade, 500))
        check(abs(ExportPlan.ownDrawAt(faded, 250L).alpha - 0.25f) < 1e-4f, "a dissolve onto an overlay ignores its opacity")
        // A transition longer than the overlay runs its length and no more.
        val long = plain.copy(transitionIn = Transition(TransitionType.CrossFade, 9000))
        check(ExportPlan.overlayTransitionMs(long) == 2000L, "an overlay's transition outruns it")
        // On the main track a transition is the rolls' business, not the clip's own draw.
        val main = video("m", 0, 2000, transition = Transition(TransitionType.CrossFade, 500))
        check(ExportPlan.ownDrawAt(main, 100L).isPlain && !ExportPlan.drawsOwn(main), "a main-track transition is drawn twice")

        // Every kind is a whole arrival on its own: the overlay is never gone for
        // the first half (the dips, the jitter and the flash hide the incoming
        // shot until the cut when there are two shots), and it has landed by the end.
        for (type in TransitionType.entries.filter { it != TransitionType.None }) {
            val early = (1..9).map { ExportPlan.arrival(type, it / 20f) }
            check(early.any { it.alpha > 0f }, "$type: an overlay arriving by it is invisible for the whole first half")
            check(ExportPlan.arrival(type, 1f).isPlain, "$type: the arrival has not landed at its end: ${ExportPlan.arrival(type, 1f)}")
            val dipping = plain.copy(transitionIn = Transition(type, 1000))
            check(ExportPlan.ownDrawAt(dipping, 100L).alpha > 0f || ExportPlan.ownDrawAt(dipping, 400L).alpha > 0f, "$type: the overlay's head is a hole")
        }
        check(abs(ExportPlan.arrival(TransitionType.DipToBlack, 0.5f).alpha - 0.5f) < 1e-4f, "a dip to black as an arrival is not a fade up")
        check(ExportPlan.arrival(TransitionType.DipToWhite, 0f).white >= 0.99f, "a dip to white as an arrival does not open on white")
        // A burn out has nothing to burn when there is no shot leaving, so as an
        // arrival it is the overlay coming out of the white rather than the
        // dissolve the blend would have given.
        check(ExportPlan.arrival(TransitionType.BurnOut, 0.2f).white > 0.5f, "a burn out as an arrival is a plain dissolve")
        check(ExportPlan.arrival(TransitionType.BurnOut, 0f).white >= 0.99f, "a burn out as an arrival does not open on white")
        // And a defocus arrival sharpens as it lands, rather than landing soft.
        check(ExportPlan.arrival(TransitionType.Defocus, 0.2f).blur > 0f, "a defocus as an arrival never softens")
        check(ExportPlan.arrival(TransitionType.Defocus, 1f).blur == 0f, "a defocus arrival lands soft")
        // The transition's draw with the clip's own laid on it.
        val stacked = ExportPlan.Draw(alpha = 0.5f, shiftX = 0.2f).over(ExportPlan.Draw(alpha = 0.5f, keepTo = 0.6f, white = 0.3f))
        check(abs(stacked.alpha - 0.25f) < 1e-5f && stacked.shiftX == 0.2f && stacked.keepTo == 0.6f && stacked.white == 0.3f, "over: $stacked")
        // The shader's space: down is minus, and the kept band is mirrored.
        val u = ExportPlan.Draw(shiftY = 0.25f, keepFromY = 0.1f, keepToY = 0.4f).shaderUniforms()
        check(u.shiftY == -0.25f && abs(u.keepFromY - 0.6f) < 1e-5f && abs(u.keepToY - 0.9f) < 1e-5f, "shader uniforms: $u")

        // A join on an overlay row: another overlay ending where this one starts.
        val first = video("f", 0, 2000, layer = 1)
        val butted = video("g", 2000, 1000, layer = 1)
        check(ExportPlan.hasOverlayJoin(butted, listOf(first, butted)), "a butted overlay has no join")
        check(!ExportPlan.hasOverlayJoin(butted.copy(timelineStartMs = 2500), listOf(first, butted)), "a gap is a join")
        check(!ExportPlan.hasOverlayJoin(butted.copy(layer = 2), listOf(first, butted)), "a join across rows")
        check(!ExportPlan.hasOverlayJoin(video("h", 2000, 1000), listOf(first)), "a main-track shot has an overlay join")
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
        for (rate in listOf(24, 30, 60)) for (end in listOf(0L, 1L, 10L, 19L, 20L, 40L, 60L, 5_000L)) {
            val lead = ExportPlan.clockLeadMs(rate)
            val clock = ExportPlan.pieces(ExportPlan.Layer(ExportPlan.Role.Clock, emptyList()), end, lead)
            check(clock.isNotEmpty() && clock[0] is ExportPlan.Piece.Gap && clock[0].durationMs >= 1L, "no gap to open a $end ms edit's clock: $clock")
            check(clock.sumOf { it.durationMs } == maxOf(end, 1L), "a $end ms edit's clock runs ${clock.sumOf { it.durationMs }}")
            check(clock.size <= 2 && (clock.size == 1 || clock[1] is ExportPlan.Piece.Clear), "a $end ms edit's clock: $clock")
            // The still is there whenever there is a frame's worth of edit after the gap.
            check((clock.size == 2) == (end - lead >= ExportPlan.MIN_GAP_MS), "a $end ms edit at $rate fps has the wrong clock: $clock")
        }
        val tiny = ExportPlan.layers(listOf(video("a", 0, 10), video("p", 0, 10, layer = 1)))
        check(tiny.layers.first().role == ExportPlan.Role.Clock, "a 10 ms edit's first layer is not the clock")
        check(ExportPlan.pieces(tiny.layers.first(), tiny.endMs).isNotEmpty(), "a 10 ms edit's clock has nothing in it")
    }

    // --- The clock's opening gap and the file's cadence. -------------------------
    run {
        check(ExportPlan.clockLeadMs(60) == 17L, "60 fps lead ${ExportPlan.clockLeadMs(60)}")
        check(ExportPlan.clockLeadMs(30) == 33L, "30 fps lead ${ExportPlan.clockLeadMs(30)}")
        check(ExportPlan.clockLeadMs(120) == 8L, "120 fps lead ${ExportPlan.clockLeadMs(120)}")
        // Slower than the gap's own rate, the lead is one gap frame, not one
        // source frame: a longer gap would draw a second blank frame at 33 ms.
        check(ExportPlan.clockLeadMs(24) == 33L, "24 fps lead ${ExportPlan.clockLeadMs(24)}")
        check(ExportPlan.clockLeadMs(0) == 33L, "an unknown rate's lead ${ExportPlan.clockLeadMs(0)}")
        val gapFrameUs = 1_000_000L / ExportPlan.GAP_FPS
        for (rate in listOf(24, 25, 30, 50, 60, 120)) {
            val lead = ExportPlan.clockLeadMs(rate)
            // Replayed: the gap draws frames at its fixed rate for its length,
            // then the still draws at the edit's from where the gap ended. The
            // compositor writes one output frame per frame of this.
            val stamps = mutableListOf<Long>()
            var t = 0L
            while (t < lead * 1_000L) { stamps += t; t += gapFrameUs }
            val step = 1_000_000.0 / rate
            var k = 0
            while (lead * 1_000L + (k * step).toLong() < 2_000_000L) { stamps += lead * 1_000L + (k * step).toLong(); k++ }
            check(stamps.size == 1 + k, "$rate fps: the gap drew ${stamps.size - k} frames")
            check(abs(stamps.size - rate * 2) <= 1, "$rate fps: ${stamps.size} frames in two seconds, not ${rate * 2}")
            val intervals = stamps.zipWithNext { a, b -> b - a }
            check(intervals.drop(1).all { abs(it - step) <= 1_000L }, "$rate fps: uneven cadence after the first frame: ${intervals.take(4)}")
            check(intervals.first() <= step.toLong() + 1_000L, "$rate fps: the first interval is long: ${intervals.first()}")
            // The old clock, for the record: every frame at the gap's rate.
            val old = (2_000_000L / gapFrameUs).toInt()
            if (rate != ExportPlan.GAP_FPS) check(abs(old - rate * 2) > 1, "the whole-gap clock would have been right at $rate fps")
        }
    }

    // --- The mixer's rate: the highest any sound has, never stepped down. --------
    run {
        check(ExportPlan.mixerSampleRate(listOf(48_000, 44_100)) == 48_000, "48 k under a 44.1 k song was stepped down")
        check(ExportPlan.mixerSampleRate(listOf(44_100)) == 44_100, "a 44.1 k edit was resampled for nothing")
        check(ExportPlan.mixerSampleRate(emptyList()) == ExportPlan.DEFAULT_SAMPLE_RATE_HZ, "nothing known: not the default")
        check(ExportPlan.mixerSampleRate(listOf(0, -1)) == ExportPlan.DEFAULT_SAMPLE_RATE_HZ, "unknown rates counted")
        check(ExportPlan.mixerSampleRate(listOf(192_000)) == ExportPlan.MAX_SAMPLE_RATE_HZ, "a rate past what AAC takes was kept")
        check(ExportPlan.mixerSampleRate(listOf(4_000)) == ExportPlan.MIN_SAMPLE_RATE_HZ, "a rate under what AAC takes was kept")
    }

    // --- The file runs to a sound dragged out past the picture. ------------------
    run {
        val shot = listOf(video("a", 0, 8_000))
        check(!ExportPlan.needsCompositing(shot), "one shot composited")
        check(!ExportPlan.needsCompositing(shot, 8_010), "a rounding tail composited")
        check(ExportPlan.needsCompositing(shot, 20_000), "a sound past the picture was not composited: the file would stop at the shot")
        val long = ExportPlan.layers(shot, 20_000)
        check(long.endMs == 20_000L, "the edit ends at ${long.endMs}, not with the sound")
        val roll = ExportPlan.pieces(long.baseLayers.single(), long.endMs)
        check(roll == listOf(ExportPlan.Piece.Item(shot[0]), ExportPlan.Piece.Clear(12_000)), "the roll does not run black to the sound's end: $roll")
        val clock = ExportPlan.pieces(long.layers.first(), long.endMs, ExportPlan.clockLeadMs(30))
        check(clock.sumOf { it.durationMs } == 20_000L, "the clock stops short of the sound: $clock")
        // Never shorter than the picture, whatever it is told.
        check(ExportPlan.layers(shot, 5_000).endMs == 8_000L, "an end before the picture's was taken")
        check(ExportPlan.pictureEnd(shot) == 8_000L, "picture end ${ExportPlan.pictureEnd(shot)}")
    }

    if (problems.isEmpty()) {
        println("PASS - layers, transitions, clocks, placement, sound slices and fold-downs all hold")
    } else {
        problems.take(15).forEach { println("FAIL - $it") }
        println("${problems.size} problems")
        exitProcess(1)
    }
}
