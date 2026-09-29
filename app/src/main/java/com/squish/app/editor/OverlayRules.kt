package com.squish.app.editor

import com.squish.app.timeline.Clip
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.timeline.MIN_CLIP_MS
import com.squish.app.timeline.OVERLAY_LANDING
import com.squish.app.timeline.SpeedRamp
import com.squish.app.timeline.TimelineState
import com.squish.app.timeline.Transform
import com.squish.app.timeline.Transition
import com.squish.app.timeline.clamped
import com.squish.app.timeline.firstFreeLayer
import com.squish.app.timeline.layerIsFree
import com.squish.app.timeline.withClipAdded
import com.squish.app.timeline.withClipReordered
import com.squish.app.timeline.withLayerChanged
import com.squish.app.timeline.withPlacementReset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An overlay's decisions, kept free of Android so tools/jvm/OverlayChecks.kt can
 * run them: how long one lands, where a dropped one goes on the main track, what
 * a finger on its box does to its placement, where it snaps, and how loud it is.
 *
 * The placement arithmetic here is the preview's and the export's own: the box a
 * finger drags is the rectangle the overlay's fitted frame covers after its
 * scale, turn and offset (ExportPlan.placementMatrix, the preview's
 * graphicsLayer), so what is grabbed is what is drawn, and what is drawn is what
 * the file gets.
 */
object OverlayRules {

    // ---- Landing ---------------------------------------------------------------------

    /** How long a photo lands on an overlay row. */
    const val STILL_LANDING_MS = 3_000L

    /**
     * How far a photo on an overlay row can be dragged out either way. A photo
     * has no length of its own, so it is given a window to trim within that no
     * edit will reach, centred, so the head handle has as much room as the tail.
     */
    const val STILL_ROOM_MS = 30L * 60_000

    /**
     * How long an overlay lands: the whole of it, but no further than the main
     * track goes - a reaction clip longer than the video under it used to run on
     * over black. With the playhead at (or past) the end of the main track there
     * is nothing to fit it to, and it lands whole.
     */
    fun landingLengthMs(sourceMs: Long, startMs: Long, mainEndMs: Long): Long {
        val room = mainEndMs - startMs
        return if (room >= MIN_CLIP_MS) minOf(sourceMs, room) else sourceMs
    }

    /**
     * The window a photo overlay's clip covers: [STILL_ROOM_MS] in, [lengthMs]
     * long, in a "file" twice [STILL_ROOM_MS] long. Only the length is ever read
     * for a photo; the numbers exist so trimming works as it does for footage.
     */
    fun stillWindow(lengthMs: Long): Triple<Long, Long, Long> =
        Triple(STILL_ROOM_MS, STILL_ROOM_MS + lengthMs, STILL_ROOM_MS * 2)

    /**
     * A photo overlay as the export writes it: a picture shown for as long as
     * the clip plays, with no window and no speed. Its keys were drawn on the
     * played clip and stay where they are; with no ramp, played time is the time
     * the frames carry.
     */
    fun asStill(clip: Clip): Clip {
        val played = clip.durationMs
        return clip.copy(sourceInMs = 0L, sourceOutMs = played, sourceDurationMs = played, speedRamp = SpeedRamp())
    }

    // ---- Between the main track and the rows ------------------------------------------

    /**
     * An overlay dropped onto the main track: at the cut nearest [atMs] (never
     * inside a shot - see EditRules.insertion), full frame, still and opaque, with
     * the shots after it moved along to make room.
     *
     * Its placement goes: a corner picture-in-picture kept as a main-track shot
     * was a small picture over black in the middle of the edit (O9). Its opacity
     * goes too - the main track draws every shot opaque, so a faded one would
     * say one thing on the Opacity sheet and show another.
     */
    fun TimelineState.withOverlayOnMain(clipId: String, atMs: Long): TimelineState {
        val clip = clips.firstOrNull { it.id == clipId && it.kind == ClipKind.Video && it.isOverlay } ?: return this
        val base = baseVideoClips
        val index = EditRules.insertion(base.map { Span(it.timelineStartMs, it.timelineEndMs) }, atMs, listOf(clip.durationMs)).index
        val main = clip.copy(
            layer = 0,
            opacity = 1f,
            keyframes = emptyList(),
            scale = 1f,
            offsetXFraction = 0f,
            offsetYFraction = 0f,
            rotation = 0f,
            transitionIn = Transition(),
            // Somewhere on the track, for withClipAdded; the reorder puts it where
            // it belongs.
            timelineStartMs = base.lastOrNull()?.timelineEndMs ?: 0L
        )
        return copy(clips = clips.filterNot { it.id == clipId })
            .withClipAdded(main)
            .withClipReordered(clipId, index)
            .copy(selectedClipId = clipId)
    }

    /**
     * A shot lifted off the main track onto the lowest row free over it, the
     * track closing up behind it, landing where an added overlay does - in the
     * corner, small. Full frame it covered the whole picture, and it looked as if
     * the button had done nothing but shorten the edit. Unchanged when every row
     * is taken there.
     */
    fun TimelineState.withMainOnOverlay(clipId: String): TimelineState {
        val lifted = withLayerChanged(clipId, +1)
        val clip = lifted.clips.firstOrNull { it.id == clipId } ?: return this
        if (!clip.isOverlay) return this
        return lifted.withPlacementReset(clipId).copy(selectedClipId = clipId)
    }

    /** How far a copy made from the box's corner sits from the original, in half-canvases. */
    const val COPY_NUDGE = 0.1f

    /**
     * The box's Duplicate: a copy at the same moment, on the first row free above
     * the original (or any free row), nudged down and to the right so both are
     * seen, and selected. The toolbar's Duplicate puts the copy after the
     * original in time; on the picture, a copy you cannot see is no copy.
     * Unchanged when every row is taken at that moment.
     */
    fun TimelineState.withOverlayCopiedInPlace(clipId: String, copyId: String): TimelineState {
        val clip = clips.firstOrNull { it.id == clipId && it.kind == ClipKind.Video && it.isOverlay } ?: return this
        val start = clip.timelineStartMs
        val end = clip.timelineEndMs
        val row = ((clip.layer + 1)..MAX_LAYER).firstOrNull { layerIsFree(it, start, end) }
            ?: firstFreeLayer(start, end)
            ?: return this
        fun nudged(t: Transform) = t.copy(
            offsetXFraction = t.offsetXFraction + COPY_NUDGE,
            offsetYFraction = t.offsetYFraction + COPY_NUDGE
        ).clamped()
        val placed = nudged(clip.staticTransform)
        val copy = clip.copy(
            id = copyId,
            layer = row,
            scale = placed.scale,
            offsetXFraction = placed.offsetXFraction,
            offsetYFraction = placed.offsetYFraction,
            rotation = placed.rotationDegrees,
            keyframes = clip.keyframes.map { it.copy(transform = nudged(it.transform)) }
        )
        return copy(clips = clips + copy, selectedClipId = copyId)
    }

    // ---- Sound -------------------------------------------------------------------------

    /**
     * How loud a picture's own sound is played and written. A shot's level is
     * its own times the camera sound for the whole edit (Sound, Voice & FX),
     * which is also where it is switched off; an overlay is at its own level
     * only - that switch is for the main track, and turning the camera off to
     * put music under a video must not silence the reaction clip over it.
     */
    fun effectiveVolume(clip: Clip, muteOriginal: Boolean, originalVolume: Float): Float = when {
        clip.isOverlay -> clip.volume.coerceIn(0f, 1f)
        muteOriginal -> 0f
        else -> (clip.volume * originalVolume).coerceIn(0f, 1f)
    }

    /**
     * Clips from a draft saved before each clip had its own level: the edit-wide
     * camera level moved onto every shot (and put back to full, by the caller),
     * so each shot's Volume shows what is heard. Overlays were always silent
     * then - in the preview and the file - and stay so rather than start
     * talking over an edit that was finished without them.
     */
    fun withPerClipVolume(clips: List<Clip>, originalVolume: Float): List<Clip> = clips.map { clip ->
        when {
            clip.kind != ClipKind.Video -> clip
            clip.isOverlay -> clip.copy(volume = 0f)
            else -> clip.copy(volume = (clip.volume * originalVolume).coerceIn(0f, 1f))
        }
    }

    // ---- The box on the picture ----------------------------------------------------------

    /**
     * An overlay's rectangle on the picture, in pixels of the frame the export
     * keeps: its centre, half its width and height, and its turn in degrees
     * clockwise.
     */
    data class Box(val cx: Float, val cy: Float, val halfW: Float, val halfH: Float, val degrees: Float) {
        private val radians: Double get() = Math.toRadians(degrees.toDouble())

        /** A point given in the box's own axes, from its centre, on the picture. */
        fun toPicture(lx: Float, ly: Float): Pair<Float, Float> {
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            return (cx + lx * c - ly * s) to (cy + lx * s + ly * c)
        }

        /** Whether a point is inside, with [slop] pixels of grace on every side. */
        fun contains(x: Float, y: Float, slop: Float = 0f): Boolean {
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            val dx = x - cx
            val dy = y - cy
            val lx = dx * c + dy * s
            val ly = -dx * s + dy * c
            return abs(lx) <= halfW + slop && abs(ly) <= halfH + slop
        }

        /** Top-left, top-right, bottom-right, bottom-left, as seen before the turn. */
        val corners: List<Pair<Float, Float>>
            get() = listOf(
                toPicture(-halfW, -halfH), toPicture(halfW, -halfH),
                toPicture(halfW, halfH), toPicture(-halfW, halfH)
            )

        /** Half the width and height of the upright rectangle the turned box fits in. */
        val extent: Pair<Float, Float>
            get() {
                val c = abs(cos(radians).toFloat())
                val s = abs(sin(radians).toFloat())
                return (halfW * c + halfH * s) to (halfW * s + halfH * c)
            }
    }

    /**
     * The overlay's frame fitted into a [frameW] x [frameH] picture - the fit the
     * export's Presentation gives it - before its own scale. Null aspect (the
     * decoder has not said yet) is the whole frame.
     */
    fun fitted(contentAspect: Float?, frameW: Float, frameH: Float): Pair<Float, Float> {
        if (contentAspect == null || !contentAspect.isFinite() || contentAspect <= 0f || frameW <= 0f || frameH <= 0f) {
            return frameW to frameH
        }
        return if (contentAspect > frameW / frameH) frameW to frameW / contentAspect
        else frameH * contentAspect to frameH
    }

    /** Where [t] puts an overlay of [contentAspect] on a [frameW] x [frameH] picture. */
    fun box(t: Transform, contentAspect: Float?, frameW: Float, frameH: Float): Box {
        val (fw, fh) = fitted(contentAspect, frameW, frameH)
        return Box(
            cx = frameW / 2f * (1f + t.offsetXFraction),
            cy = frameH / 2f * (1f + t.offsetYFraction),
            halfW = fw * t.scale / 2f,
            halfH = fh * t.scale / 2f,
            degrees = t.rotationDegrees
        )
    }

    /** A finger moved by ([dx], [dy]) pixels: the same distance on the picture, whatever the frame's shape. */
    fun dragged(t: Transform, dx: Float, dy: Float, frameW: Float, frameH: Float): Transform {
        if (frameW <= 0f || frameH <= 0f) return t
        return t.copy(
            offsetXFraction = t.offsetXFraction + 2f * dx / frameW,
            offsetYFraction = t.offsetYFraction + 2f * dy / frameH
        )
    }

    /** Two fingers: [zoom] times the size, turned [degrees] more, about the overlay's own centre. */
    fun pinched(t: Transform, zoom: Float, degrees: Float): Transform {
        val z = if (zoom.isFinite() && zoom > 0f) zoom else 1f
        return t.copy(scale = t.scale * z, rotationDegrees = normalized(t.rotationDegrees + degrees))
    }

    /**
     * The corner handle dragged from ([fromX], [fromY]) to ([toX], [toY]) with the
     * box centred on ([cx], [cy]): as much bigger as the finger is further from
     * the centre, turned by the angle it swept. Measured from where the drag
     * began, so a slow drag and a fast one end in the same place.
     */
    fun handled(start: Transform, cx: Float, cy: Float, fromX: Float, fromY: Float, toX: Float, toY: Float): Transform {
        val r0 = hypot(fromX - cx, fromY - cy)
        val r1 = hypot(toX - cx, toY - cy)
        if (r0 < 1f) return start
        val a0 = Math.toDegrees(atan2((fromY - cy).toDouble(), (fromX - cx).toDouble())).toFloat()
        val a1 = Math.toDegrees(atan2((toY - cy).toDouble(), (toX - cx).toDouble())).toFloat()
        return start.copy(
            scale = start.scale * (r1 / r0),
            rotationDegrees = normalized(start.rotationDegrees + (a1 - a0))
        )
    }

    /** An angle in (-180, 180]. */
    fun normalized(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** A placement that snapped, and the lines it snapped to - x positions and y positions on the picture. */
    data class Snapped(val transform: Transform, val xLines: List<Float>, val yLines: List<Float>, val angleSnapped: Boolean) {
        val snappedAny: Boolean get() = xLines.isNotEmpty() || yLines.isNotEmpty() || angleSnapped
    }

    /** How close to a straight angle a turn is pulled onto it. */
    const val ANGLE_SNAP_DEGREES = 3f

    /**
     * [raw] - where the finger alone would put it - pulled onto whatever it is
     * within [threshold] pixels of: the picture's centre lines and edges, and
     * the edges and centres of [others] (the other layers on screen). An edge
     * or the centre of the overlay's upright bounds meets a line; per axis the
     * nearest one wins. With [snapAngle], a turn within [ANGLE_SNAP_DEGREES] of
     * upright or a quarter turn is squared up.
     *
     * Always worked out from the raw placement, never from the last snapped one,
     * so a finger that keeps going past a line leaves it again.
     */
    fun snapped(
        raw: Transform,
        contentAspect: Float?,
        frameW: Float,
        frameH: Float,
        others: List<Box>,
        threshold: Float,
        snapPosition: Boolean = true,
        snapAngle: Boolean = false
    ): Snapped {
        var t = raw
        var angleSnapped = false
        if (snapAngle) {
            val nearest = normalized(Math.round(t.rotationDegrees / 90f) * 90f)
            if (abs(normalized(t.rotationDegrees - nearest)) <= ANGLE_SNAP_DEGREES) {
                t = t.copy(rotationDegrees = nearest)
                angleSnapped = true
            }
        }
        if (!snapPosition || frameW <= 0f || frameH <= 0f) return Snapped(t, emptyList(), emptyList(), angleSnapped)
        val b = box(t, contentAspect, frameW, frameH)
        val (ex, ey) = b.extent
        val xTargets = listOf(0f, frameW / 2f, frameW) + others.flatMap { o ->
            val (oex, _) = o.extent
            listOf(o.cx - oex, o.cx, o.cx + oex)
        }
        val yTargets = listOf(0f, frameH / 2f, frameH) + others.flatMap { o ->
            val (_, oey) = o.extent
            listOf(o.cy - oey, o.cy, o.cy + oey)
        }
        val dx = nearestPull(listOf(b.cx - ex, b.cx, b.cx + ex), xTargets, threshold)
        val dy = nearestPull(listOf(b.cy - ey, b.cy, b.cy + ey), yTargets, threshold)
        val cx = b.cx + (dx ?: 0f)
        val cy = b.cy + (dy ?: 0f)
        // Only an axis that snapped is worked out again: round-tripping the other
        // through pixels would nudge it by a rounding error on every event.
        val placed = t.copy(
            offsetXFraction = if (dx == null) t.offsetXFraction else 2f * cx / frameW - 1f,
            offsetYFraction = if (dy == null) t.offsetYFraction else 2f * cy / frameH - 1f
        )
        // Every line the snapped box now sits on, so two that line up at once both show.
        val xLines = if (dx == null) emptyList() else linesTouched(listOf(cx - ex, cx, cx + ex), xTargets)
        val yLines = if (dy == null) emptyList() else linesTouched(listOf(cy - ey, cy, cy + ey), yTargets)
        return Snapped(placed, xLines, yLines, angleSnapped)
    }

    /** The smallest move that puts one of [features] on one of [targets], if any is within [threshold]. */
    private fun nearestPull(features: List<Float>, targets: List<Float>, threshold: Float): Float? {
        var best: Float? = null
        for (f in features) for (g in targets) {
            val d = g - f
            if (abs(d) <= threshold && (best == null || abs(d) < abs(best))) best = d
        }
        return best
    }

    private fun linesTouched(features: List<Float>, targets: List<Float>): List<Float> =
        targets.filter { g -> features.any { abs(it - g) < 0.5f } }.distinct()

    /**
     * What the picture says while a finger is on an overlay: where its centre is,
     * as a share of the way across and down the frame - "+45%" of half a canvas
     * meant nothing to anyone (O6) - or its size and turn.
     */
    fun readout(t: Transform, moving: Boolean): String =
        if (moving) {
            val across = ((1f + t.offsetXFraction) / 2f * 100f).roundToInt()
            val down = ((1f + t.offsetYFraction) / 2f * 100f).roundToInt()
            "Across $across% · Down $down%"
        } else {
            "Size ${(t.scale * 100f).roundToInt()}% · ${normalized(t.rotationDegrees).roundToInt()}°"
        }

    /** A placement inside the editor's one set of limits (TransformLimits), with its angle in range. */
    fun limited(t: Transform): Transform = t.copy(rotationDegrees = normalized(t.rotationDegrees)).clamped()
}
