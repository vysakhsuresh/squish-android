package com.squish.app.editor

import com.squish.app.media.ExportPresets
import com.squish.app.timeline.Clip

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
 * run them: how big a padded canvas is, where the picture sits on it, and
 * where auto-reframe has the crop centred at a moment.
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
     * Where auto-reframe has the frame-shape crop centred at [timelineMs]: the
     * subject's place in the shot under the playhead, read from that shot's own
     * track in its own file time. Null with no shot there, or one not analysed
     * - the crop stays centred. Each shot carries its own track now; one track
     * for the edit, read off the first file, chased where a subject had been
     * in different footage (V11).
     */
    fun reframeFocus(clips: List<Clip>, timelineMs: Long): Pair<Float, Float>? {
        val base = clips.filter { it.layer == 0 }
        val shot = base.firstOrNull { timelineMs >= it.timelineStartMs && timelineMs < it.timelineEndMs }
            ?: base.maxByOrNull { it.timelineEndMs }?.takeIf { it.timelineEndMs == timelineMs }
            ?: return null
        val track = shot.reframe ?: return null
        val sample = track.sampleAt(shot.sourceAt(timelineMs)) ?: return null
        return sample.xFraction to sample.yFraction
    }
}
