package com.squish.app.editor

import com.squish.app.media.ExportPresets
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Transform
import com.squish.app.timeline.transformAt
import kotlin.math.cos
import kotlin.math.sin

/** What fills the canvas behind the picture when the picture does not fill it. */
enum class CanvasFill(val label: String) {
    /** The picture fills the canvas and is cut to its shape: no background at all. */
    Crop("Fill"),
    Colour("Colour"),
    /** A blurred copy of the shot itself, the way reels are made from landscape footage. */
    Blur("Blur"),
    Image("Image")
}

/**
 * The edit's background: what a ratio's canvas is filled with around the
 * footage. With [CanvasFill.Crop] there is no canvas to fill - the ratio cuts
 * the picture, as it always did. With anything else the picture is fitted
 * whole into a canvas of that ratio and this shows round it.
 */
data class CanvasBackground(
    val fill: CanvasFill = CanvasFill.Crop,
    val colorArgb: Int = DEFAULT_COLOUR,
    /** The picture behind the footage, for [CanvasFill.Image]; a file this app keeps under files/stills/. */
    val imageUri: String? = null
) {
    /** Whether the footage is fitted inside the canvas over a backdrop rather than filling it. */
    val pads: Boolean get() = fill != CanvasFill.Crop && (fill != CanvasFill.Image || imageUri != null)

    companion object {
        val NONE = CanvasBackground()
        const val DEFAULT_COLOUR: Int = 0xFF101828.toInt()
    }
}

/**
 * The decisions about the edit's frame that the preview, the export and the
 * sheet all read, kept free of Android so tools/jvm/FrameRulesChecks.kt can
 * run them: how big a padded canvas is, where the picture sits on it, where
 * a shot's subject lands on the canvas once the shot is cropped, turned and
 * placed, which shot a blurred backdrop is taken from at a moment, and where
 * auto-reframe has the crop centred.
 */
object FrameRules {

    /**
     * The canvas of a padded frame: [ratio] wide per unit tall, with the short
     * edge the source's own (or [outputP], when a size is chosen). A landscape
     * 1920x1080 shot on a 9:16 canvas is 1080x1920 - the same pixels tall as
     * it was wide, which is what a phone would have recorded.
     */
    fun paddedCanvas(outputP: Int, framedWidth: Int, framedHeight: Int, ratio: Float): ExportPresets.Resolution {
        if (framedWidth <= 0 || framedHeight <= 0 || ratio <= 0f || !ratio.isFinite()) {
            return ExportPresets.Resolution(framedWidth, framedHeight)
        }
        val short = if (outputP == OutputSize.ORIGINAL) minOf(framedWidth, framedHeight) else outputP
        val (w, h) = if (ratio >= 1f) (short * ratio) to short.toFloat() else short.toFloat() to (short / ratio)
        return ExportPresets.Resolution(even(w), even(h))
    }

    private fun even(v: Float): Int = (Math.round(v / 2f) * 2).coerceAtLeast(2)

    /**
     * Where a picture of [pictureAspect] sits, fitted whole and centred, on a
     * canvas of [canvasAspect]: the frame the base surfaces are laid out in
     * when the canvas is padded, and where the export's Presentation puts it.
     */
    fun fittedFrame(canvasAspect: Float, pictureAspect: Float): PreviewBox.Frame {
        if (canvasAspect <= 0f || pictureAspect <= 0f || !canvasAspect.isFinite() || !pictureAspect.isFinite()) {
            return PreviewBox.Frame()
        }
        return if (pictureAspect >= canvasAspect) {
            val h = canvasAspect / pictureAspect
            PreviewBox.Frame(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        } else {
            val w = pictureAspect / canvasAspect
            PreviewBox.Frame((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        }
    }

    /**
     * Where a point of a shot's own picture lands on the canvas the frame's
     * crop is cut from - the walk the shot makes to the screen and to the file
     * (TimelinePreview's layers, VideoProcessor.editedClip): its own crop
     * window (mirrored, turned, cut), the edit's quarter turn, the window
     * fitted whole into the canvas, then its placement on the canvas.
     *
     * Auto-reframe's track is measured on the source frame, and the crop it
     * steers is cut from the canvas, so the subject has to be carried across
     * or the window follows where the face *was* before the shot was cropped
     * or moved. Every point is a top-down fraction of its frame.
     *
     * @param sourceAspect the shot's own picture, width over height, before
     *   the edit's turn; null when nothing has measured it, which reads as the
     *   canvas's own shape turned back - right for the file the edit was
     *   opened on.
     * @param rotationDegrees the edit's turn, counterclockwise as Media3 turns it.
     * @param placement the clip's own placement at that moment, without the stabilizer.
     */
    fun subjectOnCanvas(
        sx: Float,
        sy: Float,
        crop: ClipCrop?,
        sourceAspect: Float?,
        rotationDegrees: Int,
        placement: Transform,
        canvasAspect: Float
    ): Pair<Float, Float> {
        val quarter = PreviewBox.isQuarterTurn(rotationDegrees)
        val canvasKnown = canvasAspect > 0f && canvasAspect.isFinite()
        val source = sourceAspect?.takeIf { it > 0f && it.isFinite() }
            ?: if (canvasKnown) (if (quarter) 1f / canvasAspect else canvasAspect) else 1f

        // The clip's own window, as CropRules cuts it.
        val cropped = crop != null && !crop.isIdentity
        val (u, v) = if (cropped) CropRules.windowPoint(sx, sy, crop!!, source) else sx to sy
        val windowAspect = CropRules.croppedAspect(source, crop)

        // The edit's turn, in square units so a turn is a turn.
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val px = (u - 0.5f) * windowAspect
        val py = v - 0.5f
        val rx = px * c + py * s
        val ry = -px * s + py * c
        // The turned frame's own sides, in the same units: a quarter turn
        // swaps them, so its fractions are read against the swapped pair.
        val turned = if (quarter) 1f / windowAspect else windowAspect
        val (tw, th) = if (quarter) 1f to windowAspect else windowAspect to 1f
        val tu = rx / tw + 0.5f
        val tv = ry / th + 0.5f

        // Fitted whole into the canvas, as the export's Presentation fits it.
        val canvas = if (canvasKnown) canvasAspect else turned
        val frame = fittedFrame(canvas, turned)
        val cx = frame.left + tu * frame.width
        val cy = frame.top + tv * frame.height

        // The placement, on the canvas: scaled about its middle, turned
        // clockwise as seen, moved by fractions of half the canvas - what
        // ExportPlan.placementMatrix writes and the preview's layer draws.
        val pr = Math.toRadians(placement.rotationDegrees.toDouble())
        val pc = cos(pr).toFloat()
        val ps = sin(pr).toFloat()
        val qx = (cx - 0.5f) * canvas * placement.scale
        val qy = (cy - 0.5f) * placement.scale
        val ox = qx * pc - qy * ps
        val oy = qx * ps + qy * pc
        return (ox / canvas + 0.5f + placement.offsetXFraction / 2f) to (oy + 0.5f + placement.offsetYFraction / 2f)
    }

    /**
     * Where auto-reframe has the frame-shape crop centred at [timelineMs], on
     * the canvas: the subject's place in the shot under the playhead, read
     * from that shot's own track in its own file time and carried through the
     * shot's crop, the edit's turn and the shot's placement ([subjectOnCanvas]).
     * Null with no shot there, or one not analysed - the crop stays centred.
     * Each shot carries its own track now; one track for the edit, read off
     * the first file, chased where a subject had been in different footage
     * (V11).
     *
     * @param sourceAspectOf the shot's own picture shape, width over height
     *   before the edit's turn, or null when unknown.
     */
    fun reframeFocus(
        clips: List<Clip>,
        timelineMs: Long,
        rotationDegrees: Int = 0,
        canvasAspect: Float = 0f,
        sourceAspectOf: (Clip) -> Float? = { null }
    ): Pair<Float, Float>? {
        val base = clips.filter { it.layer == 0 }
        val shot = base.firstOrNull { timelineMs >= it.timelineStartMs && timelineMs < it.timelineEndMs }
            ?: base.maxByOrNull { it.timelineEndMs }?.takeIf { it.timelineEndMs == timelineMs }
            ?: return null
        val track = shot.reframe ?: return null
        val sample = track.sampleAt(shot.sourceAt(timelineMs)) ?: return null
        // The user's placement alone: the stabilizer's correction is a frame's
        // shake, not where the shot was put. With its arrival, leaving and loop,
        // as the export's ReframeEffect reads it (ExportPlan.motionAt User):
        // left out, the window stayed on the resting subject while the file's
        // chased it sliding in.
        val placement = shot.placedAt(timelineMs)
        return subjectOnCanvas(
            sample.xFraction, sample.yFraction, shot.crop, sourceAspectOf(shot), rotationDegrees, placement, canvasAspect
        )
    }

    // ---- The blurred backdrop ---------------------------------------------------

    /**
     * How coarsely the moment a blurred backdrop is taken from is chosen. A
     * trim handle moves a shot's middle by half a pixel of drag, and a still
     * per pixel was a 4K decode and a file for each - so the middle is read
     * on this grid, and a drag across a few seconds asks for a few stills.
     */
    const val BACKDROP_GRID_MS = 1_000L

    /** The moment of a shot its blurred backdrop is taken from: its middle, in the file's own time, to the nearest [BACKDROP_GRID_MS]. */
    fun backdropMomentMs(clip: Clip): Long {
        val middle = clip.sourceAt(clip.timelineStartMs + clip.durationMs / 2)
        return ((middle + BACKDROP_GRID_MS / 2) / BACKDROP_GRID_MS * BACKDROP_GRID_MS).coerceAtLeast(0L)
    }

    /** One stretch of the blurred backdrop: the shot it is taken from, and where on the timeline it shows. */
    data class BackdropStretch(val clip: Clip, val startMs: Long, val endMs: Long)

    /**
     * Where each main-track shot's blurred still shows: from the shot's start
     * to the next shot's - across a gap, so the canvas never goes to nothing
     * between two shots - the first from the very start, and the last on to
     * [endMs], under a song that runs past the picture. The preview and the
     * file both read this, so a gap looks the same in both.
     */
    fun backdropStretches(clips: List<Clip>, endMs: Long): List<BackdropStretch> {
        val shots = clips.filter { it.isMain && it.durationMs > 0 }.sortedBy { it.timelineStartMs }
        return shots.mapIndexed { i, shot ->
            val from = if (i == 0) 0L else shot.timelineStartMs
            val to = shots.getOrNull(i + 1)?.timelineStartMs ?: maxOf(endMs, shot.timelineEndMs)
            BackdropStretch(shot, from, to)
        }.filter { it.endMs > it.startMs }
    }

    /** The shot whose blurred still is behind the canvas at [timelineMs]; null with no shot at all. */
    fun backdropShot(clips: List<Clip>, timelineMs: Long, endMs: Long): Clip? =
        backdropStretches(clips, endMs).lastOrNull { timelineMs >= it.startMs }?.clip
}
