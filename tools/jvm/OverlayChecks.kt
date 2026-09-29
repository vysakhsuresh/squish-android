import com.squish.app.editor.OverlayRules
import com.squish.app.editor.OverlayRules.withMainOnOverlay
import com.squish.app.editor.OverlayRules.withOverlayCopiedInPlace
import com.squish.app.editor.OverlayRules.withOverlayOnMain
import com.squish.app.media.ExportPlan
import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.timeline.OVERLAY_LANDING
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transform
import com.squish.app.timeline.transformAt
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.withClipTrimmed
import com.squish.app.timeline.withOverlayGeometry
import kotlin.math.abs
import kotlin.system.exitProcess

// Overlays, executed: how one lands, how it moves between the main track and the
// rows, how loud it is, and - the part a finger touches - the box on the picture,
// which has to be exactly where the export draws the layer. Each block is a claim
// from docs/ROADMAP.md batch B8 or the audit item it fixes (O1, O6, O7, O9).

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }
fun near(a: Float, b: Float, eps: Float = 0.01f) = abs(a - b) <= eps

fun video(
    id: String, span: Long, start: Long = 0, layer: Int = 0, srcIn: Long = 0, fileMs: Long = 60_000,
    keys: List<Keyframe> = emptyList(), volume: Float = 1f
) = Clip(
    id = id, kind = ClipKind.Video, label = id, sourceInMs = srcIn, sourceOutMs = srcIn + span,
    timelineStartMs = start, sourceDurationMs = fileMs, layer = layer, keyframes = keys, volume = volume,
    scale = if (layer > 0) OVERLAY_LANDING.scale else 1f,
    offsetXFraction = if (layer > 0) OVERLAY_LANDING.offsetXFraction else 0f,
    offsetYFraction = if (layer > 0) OVERLAY_LANDING.offsetYFraction else 0f
)

fun TimelineState.byId(id: String) = clips.first { it.id == id }

fun butted(state: TimelineState, what: String) {
    val base = state.baseVideoClips
    if (base.isEmpty()) return
    check(base[0].timelineStartMs == 0L, "$what: main track starts at ${base[0].timelineStartMs}")
    for (i in 1 until base.size) {
        check(base[i].timelineStartMs == base[i - 1].timelineEndMs, "$what: clip $i at ${base[i].timelineStartMs}, previous ends ${base[i - 1].timelineEndMs}")
    }
}

fun rowsClear(state: TimelineState, what: String) {
    val overlays = state.overlayClips
    for (a in overlays) for (b in overlays) {
        if (a.id >= b.id || a.layer != b.layer) continue
        check(!(a.timelineStartMs < b.timelineEndMs && b.timelineStartMs < a.timelineEndMs), "$what: ${a.id} and ${b.id} share row ${a.layer}")
    }
    check(overlays.all { it.layer in 1..MAX_LAYER }, "$what: an overlay left the rows")
}

fun main() {
    // --- Landing: no longer than the main track runs past the playhead. ---------------
    run {
        check(OverlayRules.landingLengthMs(60_000, 2_000, 10_000) == 8_000L, "a long overlay did not stop at the main track's end")
        check(OverlayRules.landingLengthMs(5_000, 2_000, 10_000) == 5_000L, "a short overlay was cut")
        check(OverlayRules.landingLengthMs(60_000, 10_000, 10_000) == 60_000L, "at the end, an overlay was cut to nothing")
        check(OverlayRules.landingLengthMs(60_000, 9_950, 10_000) == 60_000L, "a sliver of room cut it below the shortest clip")
        check(OverlayRules.landingLengthMs(OverlayRules.STILL_LANDING_MS, 1_000, 2_500) == 1_500L, "a photo past the end")
    }

    // --- A photo: trimmed like footage, either way; exported for as long as it plays. --
    run {
        val (inMs, outMs, fileMs) = OverlayRules.stillWindow(3_000)
        val photo = video("ph", outMs - inMs, start = 4_000, layer = 1, srcIn = inMs, fileMs = fileMs)
        check(photo.durationMs == 3_000L, "photo landed ${photo.durationMs} long")
        val state = TimelineState(clips = listOf(video("m", 20_000), photo))
        val longer = state.withClipTrimmed("ph", 0, 9_000).byId("ph")
        check(longer.durationMs == 12_000L && longer.timelineStartMs == 4_000L, "photo tail drag: ${longer.durationMs} at ${longer.timelineStartMs}")
        val earlier = state.withClipTrimmed("ph", -3_000, 0).byId("ph")
        check(earlier.timelineStartMs == 1_000L && earlier.durationMs == 6_000L, "photo head drag: ${earlier.durationMs} at ${earlier.timelineStartMs}")
        // Stops at the start of the edit, like footage placed there.
        val stopped = state.withClipTrimmed("ph", -9_000, 0).byId("ph")
        check(stopped.timelineStartMs == 0L && stopped.durationMs == 7_000L, "photo head drag past zero: ${stopped.durationMs} at ${stopped.timelineStartMs}")

        val keyed = longer.copy(
            speedRamp = SpeedRamp.flat(2f),
            keyframes = listOf(Keyframe(0, Transform(scale = 0.4f), KeyframeEasing.Linear), Keyframe(6_000, Transform(scale = 0.8f), KeyframeEasing.Linear))
        )
        val still = OverlayRules.asStill(keyed)
        check(still.durationMs == keyed.durationMs, "a photo exports ${still.durationMs}, plays ${keyed.durationMs}")
        check(still.sourceInMs == 0L && still.speedRamp.isIdentity, "a photo exports with a window or a speed")
        // The export's clock for the keys is the played clock, unchanged.
        for (t in listOf(0L, 1_500L, 3_000L, 5_999L)) {
            check(near(ExportPlan.motionAt(still, ExportPlan.MotionPart.User, t).scale, keyed.keyframes.transformAt(t, keyed.staticTransform).scale),
                "photo keys at $t ms differ between export and edit")
        }
    }

    // --- Onto the main track: at the playhead's nearer cut, full frame, rippled (O9). ---
    run {
        val o = video("o", 2_000, start = 1_000, layer = 1).copy(opacity = 0.5f, rotation = 20f,
            keyframes = listOf(Keyframe(0, Transform(0.3f), KeyframeEasing.Smooth), Keyframe(2_000, Transform(0.6f), KeyframeEasing.Smooth)))
        val state = TimelineState(clips = listOf(video("a", 5_000), video("b", 5_000, start = 5_000), o))
        val nearEnd = state.withOverlayOnMain("o", 4_000)
        butted(nearEnd, "to main near a's end")
        check(nearEnd.baseVideoClips.map { it.id } == listOf("a", "o", "b"), "to main at 4 s: ${nearEnd.baseVideoClips.map { it.id }}")
        val dropped = nearEnd.byId("o")
        check(dropped.layer == 0 && dropped.staticTransform.isIdentity && dropped.keyframes.isEmpty() && dropped.opacity == 1f,
            "to main kept its corner placement: $dropped")
        check(dropped.timelineStartMs == 5_000L && nearEnd.byId("b").timelineStartMs == 7_000L, "to main did not make room")
        check(nearEnd.selectedClipId == "o", "to main let go of the clip")
        val nearStart = state.withOverlayOnMain("o", 1_000)
        check(nearStart.baseVideoClips.map { it.id } == listOf("o", "a", "b"), "to main at 1 s: ${nearStart.baseVideoClips.map { it.id }}")
        butted(nearStart, "to main near the start")
        val pastEnd = state.withOverlayOnMain("o", 60_000)
        check(pastEnd.baseVideoClips.last().id == "o", "to main past the end")
        check(state.withOverlayOnMain("a", 0) == state, "a main shot was 'dropped' onto the main track")
    }

    // --- Off the main track: the corner, the track closed up behind it. ---------------
    run {
        val state = TimelineState(clips = listOf(video("a", 5_000), video("b", 5_000, start = 5_000), video("c", 5_000, start = 10_000)))
        val lifted = state.withMainOnOverlay("b")
        butted(lifted, "to overlay")
        val b = lifted.byId("b")
        check(b.layer == 1 && b.staticTransform == OVERLAY_LANDING, "to overlay landed ${b.staticTransform} on row ${b.layer}")
        check(lifted.byId("c").timelineStartMs == 5_000L, "to overlay left a hole")
        val full = TimelineState(clips = state.clips + (1..MAX_LAYER).map { video("w$it", 20_000, layer = it) })
        check(full.withMainOnOverlay("b") == full, "to overlay with every row taken changed something")
    }

    // --- The box's Duplicate: same moment, a row up, nudged. ----------------------------
    run {
        val o = video("o", 3_000, start = 2_000, layer = 1)
        val state = TimelineState(clips = listOf(video("m", 10_000), o))
        val copied = state.withOverlayCopiedInPlace("o", "o2")
        rowsClear(copied, "copy in place")
        val c = copied.byId("o2")
        check(c.layer == 2 && c.timelineStartMs == 2_000L && c.durationMs == 3_000L, "copy in place: row ${c.layer} at ${c.timelineStartMs}")
        check(near(c.offsetXFraction, o.offsetXFraction + OverlayRules.COPY_NUDGE) && near(c.offsetYFraction, o.offsetYFraction + OverlayRules.COPY_NUDGE),
            "copy in place was not nudged")
        check(copied.selectedClipId == "o2", "copy in place is not selected")
        // A row free only below: it goes there rather than nowhere.
        val top = TimelineState(clips = listOf(video("m", 10_000), video("t", 3_000, start = 2_000, layer = MAX_LAYER)))
        check(top.withOverlayCopiedInPlace("t", "t2").byId("t2").layer == 1, "copy from the top row found no row")
        val full = TimelineState(clips = listOf(video("m", 10_000)) + (1..MAX_LAYER).map { video("w$it", 5_000, layer = it) })
        check(full.withOverlayCopiedInPlace("w1", "x") == full, "copy in place with every row taken added a clip")
        // Adding lands on the lowest free row, never on top of another (O7).
        var added = TimelineState(clips = listOf(video("m", 10_000)))
        repeat(MAX_LAYER) { i -> added = added.withClipAdded(video("n$i", 4_000, start = 1_000, layer = 1)) }
        rowsClear(added, "adding at one moment")
        check(added.overlayClips.map { it.layer }.sorted() == (1..MAX_LAYER).toList(), "adding at one moment: ${added.overlayClips.map { it.layer }}")
        check(added.withClipAdded(video("late", 4_000, start = 1_000, layer = 1)) == added, "an overlay past the last row was added")
    }

    // --- Sound: a shot under the camera level, an overlay on its own. --------------------
    run {
        val shot = video("s", 1_000, volume = 0.5f)
        val pip = video("p", 1_000, layer = 1, volume = 0.7f)
        check(near(OverlayRules.effectiveVolume(shot, false, 0.5f), 0.25f), "a shot is not under the camera level")
        check(OverlayRules.effectiveVolume(shot, true, 1f) == 0f, "a shot is heard with the camera sound off")
        check(near(OverlayRules.effectiveVolume(pip, true, 0.2f), 0.7f), "the camera switch silenced an overlay")
        val old = OverlayRules.withPerClipVolume(listOf(video("a", 1_000), pip, video("b", 1_000, volume = 1f)), 0.4f)
        check(near(old[0].volume, 0.4f) && near(old[2].volume, 0.4f), "an old draft's camera level did not move onto its shots")
        check(old[1].volume == 0f, "an old draft's overlay starts talking")
        // What is heard after the move is what was heard before it.
        check(near(OverlayRules.effectiveVolume(old[0], false, 1f), OverlayRules.effectiveVolume(video("a", 1_000), false, 0.4f)),
            "moving the level changed what is heard")
    }

    // --- The box on the picture is where the export draws the layer (O1). ---------------
    run {
        val frames = listOf(1080f to 1920f, 1920f to 1080f, 720f to 720f, 607.5f to 1080f)
        val aspects = listOf(16f / 9f, 9f / 16f, 1f, 4f / 3f)
        val placements = listOf(
            Transform(), OVERLAY_LANDING, Transform(0.7f, -0.3f, 0.2f, 30f), Transform(1.4f, 0.1f, -0.6f, -135f), Transform(0.25f, 0.9f, 0.9f, 90f)
        )
        for ((w, h) in frames) for (a in aspects) for (t in placements) {
            val box = OverlayRules.box(t, a, w, h)
            // The export: the layer fitted to the canvas, then placementMatrix in NDC.
            val (fw, fh) = OverlayRules.fitted(a, w, h)
            val m = ExportPlan.placementMatrix(t, w / h)
            val ndcCorners = listOf(-fw / w to fh / h, fw / w to fh / h, fw / w to -fh / h, -fw / w to -fh / h) // TL, TR, BR, BL, +y up
            val drawn = ndcCorners.map { (x, y) ->
                val nx = m[0] * x + m[1] * y + m[4]
                val ny = m[2] * x + m[3] * y + m[5]
                ((nx + 1f) / 2f * w) to ((1f - ny) / 2f * h)
            }
            box.corners.zip(drawn).forEachIndexed { i, (b, e) ->
                check(near(b.first, e.first, 0.5f) && near(b.second, e.second, 0.5f),
                    "frame ${w}x$h aspect $a $t corner $i: box $b, export $e")
            }
            // Inside is inside: the centre, and just within each corner.
            check(box.contains(box.cx, box.cy), "centre not inside for $t")
            box.corners.forEach { (cx, cy) ->
                val px = box.cx + (cx - box.cx) * 0.95f
                val py = box.cy + (cy - box.cy) * 0.95f
                check(box.contains(px, py), "a point near a corner is outside for $t")
                val ox = box.cx + (cx - box.cx) * 1.1f
                val oy = box.cy + (cy - box.cy) * 1.1f
                check(!box.contains(ox, oy), "a point past a corner is inside for $t")
            }
        }
        // An unknown shape is the whole frame, as the surface is laid out until the decoder says.
        check(OverlayRules.fitted(null, 100f, 50f) == (100f to 50f), "unknown aspect is not the whole frame")
    }

    // --- Moving it: the finger's distance, whatever the frame's shape. -------------------
    run {
        val w = 1080f
        val h = 1920f
        val t = OVERLAY_LANDING
        val moved = OverlayRules.dragged(t, 54f, -96f, w, h)
        val before = OverlayRules.box(t, 1f, w, h)
        val after = OverlayRules.box(moved, 1f, w, h)
        check(near(after.cx - before.cx, 54f) && near(after.cy - before.cy, -96f), "a drag moved the box by ${after.cx - before.cx}, ${after.cy - before.cy}")
        val pinched = OverlayRules.pinched(t, 1.5f, 200f)
        check(near(pinched.scale, t.scale * 1.5f) && near(pinched.rotationDegrees, -160f), "pinch: $pinched")
        check(OverlayRules.pinched(t, Float.NaN, 0f).scale == t.scale, "a pinch with no distance changed the size")

        // The corner handle: twice as far out is twice the size; a quarter turn around is 90°.
        val box = OverlayRules.box(t, 1f, w, h)
        val (bx, by) = box.corners[2]
        val doubled = OverlayRules.handled(t, box.cx, box.cy, bx, by, box.cx + 2 * (bx - box.cx), box.cy + 2 * (by - box.cy))
        check(near(doubled.scale, t.scale * 2f, 0.001f) && near(doubled.rotationDegrees, 0f), "handle outwards: $doubled")
        val dx = bx - box.cx
        val dy = by - box.cy
        val turned = OverlayRules.handled(t, box.cx, box.cy, bx, by, box.cx - dy, box.cy + dx)
        check(near(turned.rotationDegrees, 90f, 0.05f) && near(turned.scale, t.scale, 0.001f), "handle a quarter turn: $turned")
        check(OverlayRules.handled(t, 10f, 10f, 10f, 10f, 500f, 500f) == t, "a handle on the centre did something")

        check(OverlayRules.normalized(190f) == -170f && OverlayRules.normalized(-180f) == 180f && OverlayRules.normalized(540f) == 180f,
            "angles are not kept in (-180, 180]")
        check(OverlayRules.limited(Transform(scale = 99f, offsetXFraction = -9f, rotationDegrees = 370f)) ==
            Transform(scale = 4f, offsetXFraction = -1.5f, rotationDegrees = 10f), "the one set of limits is not applied")
    }

    // --- Snapping: onto a line within reach, off it once the finger carries on. --------
    run {
        val w = 1000f
        val h = 1000f
        val t = Transform(scale = 0.4f, offsetXFraction = 0.012f, offsetYFraction = 0.3f)
        val s = OverlayRules.snapped(t, 1f, w, h, emptyList(), threshold = 8f)
        check(s.transform.offsetXFraction == 0f, "not pulled onto the centre line: ${s.transform.offsetXFraction}")
        check(s.xLines == listOf(500f), "centre line not reported: ${s.xLines}")
        check(s.yLines.isEmpty() && near(s.transform.offsetYFraction, 0.3f), "snapped across when it should not have")
        val away = OverlayRules.snapped(t.copy(offsetXFraction = 0.05f), 1f, w, h, emptyList(), threshold = 8f)
        check(away.transform.offsetXFraction == 0.05f && away.xLines.isEmpty(), "held on a line 25 px away")
        // An edge onto the frame's edge: box half-width 200 at 0.4 of 1000.
        val edge = OverlayRules.snapped(Transform(0.4f, -0.595f, 0f), 1f, w, h, emptyList(), threshold = 8f)
        check(near(edge.transform.offsetXFraction, -0.6f, 0.0005f) && edge.xLines.contains(0f), "left edge not onto the frame edge: $edge")
        // Onto another layer's edge.
        val other = OverlayRules.box(Transform(0.2f, 0.5f, 0.5f), 1f, w, h) // 100 half, centre 750: edges 650, 850
        val nextTo = OverlayRules.snapped(Transform(0.2f, 0.105f, -0.5f), 1f, w, h, listOf(other), threshold = 8f)
        // Its right edge was at 552.5+100 = 652.5: pulled to 650.
        check(near(OverlayRules.box(nextTo.transform, 1f, w, h).cx + 100f, 650f, 0.5f), "not onto the other layer's edge: $nextTo")
        // A turned box snaps by the rectangle it fits in.
        val turned = OverlayRules.snapped(Transform(0.4f, 0.004f, 0f, 45f), 1f, w, h, emptyList(), threshold = 8f)
        check(turned.transform.offsetXFraction == 0f, "a turned box did not snap")

        // The angle: squared up within reach, and only when asked.
        val tilt = OverlayRules.snapped(Transform(rotationDegrees = 88f), 1f, w, h, emptyList(), 8f, snapPosition = false, snapAngle = true)
        check(tilt.transform.rotationDegrees == 90f && tilt.angleSnapped, "88° not squared to 90°")
        val back = OverlayRules.snapped(Transform(rotationDegrees = -178.5f), 1f, w, h, emptyList(), 8f, snapPosition = false, snapAngle = true)
        check(back.transform.rotationDegrees == 180f, "-178.5° not squared to 180°: ${back.transform.rotationDegrees}")
        val free = OverlayRules.snapped(Transform(rotationDegrees = 80f), 1f, w, h, emptyList(), 8f, snapPosition = false, snapAngle = true)
        check(free.transform.rotationDegrees == 80f && !free.angleSnapped, "80° was pulled")
        val notAsked = OverlayRules.snapped(Transform(rotationDegrees = 89f), 1f, w, h, emptyList(), 8f, snapPosition = false)
        check(notAsked.transform.rotationDegrees == 89f, "a drag squared the angle")
    }

    // --- The readout says where, in words a person can map to the picture (O6). -------
    run {
        check(OverlayRules.readout(Transform(offsetXFraction = 0.45f, offsetYFraction = -0.45f), moving = true) == "Across 73% · Down 28%",
            "readout: ${OverlayRules.readout(Transform(offsetXFraction = 0.45f, offsetYFraction = -0.45f), true)}")
        check(OverlayRules.readout(Transform(scale = 1.4f, rotationDegrees = -15.4f), moving = false) == "Size 140% · -15°",
            "readout: ${OverlayRules.readout(Transform(scale = 1.4f, rotationDegrees = -15.4f), false)}")
    }

    // --- A gesture on an animated overlay keys the playhead, as the sliders do. -------
    run {
        val keys = listOf(Keyframe(0, Transform(0.4f), KeyframeEasing.Linear), Keyframe(4_000, Transform(0.8f), KeyframeEasing.Linear))
        val o = video("o", 4_000, start = 1_000, layer = 1, keys = keys)
        val state = TimelineState(clips = listOf(video("m", 10_000), o), playheadMs = 3_000)
        val placed = OverlayRules.dragged(o.placementAt(3_000), 100f, 0f, 1000f, 1000f)
        val moved = state.withOverlayGeometry("o", scale = placed.scale, offsetX = placed.offsetXFraction, offsetY = placed.offsetYFraction, rotation = placed.rotationDegrees)
            .byId("o")
        check(moved.keyframes.size == 3 && moved.keyframes.any { it.atMs == 2_000L }, "a drag on an animated overlay did not key the playhead: ${moved.keyframes}")
        check(near(moved.placementAt(3_000).offsetXFraction, placed.offsetXFraction), "the key is not where the finger left it")
        check(near(moved.placementAt(1_000).scale, 0.4f) && near(moved.placementAt(5_000).scale, 0.8f), "the drag rewrote the ends of the move")
    }

    println("overlay checks: landing, rows, main track, sound, the box and its snapping")
    if (problems.isEmpty()) println("PASS - an overlay lands, moves and sounds as B8 decided, and its box is where the export draws it")
    else { println("FAIL (${problems.size})"); problems.take(30).forEach { println("  - $it") }; exitProcess(1) }
}
