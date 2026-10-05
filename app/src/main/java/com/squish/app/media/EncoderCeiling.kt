@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil
import kotlin.math.abs

/**
 * What this phone's encoder will actually write for a frame it is asked
 * for.
 *
 * Media3's DefaultEncoderFactory falls back by itself when an encoder cannot
 * take the requested size - keeping the encoders whose nearest supported size
 * is nearest by area and writing at that size - and says nothing. On the
 * phone, 4K of a 4:3 source was promised as 2880 x 2160 and written at
 * 1440 x 1080, half the size and a third of the weight the sheet had quoted.
 * This asks the same question the factory asks, the same way, so the sheet can
 * show the size that will be written and the export can be built at it.
 *
 * Every question names the codec: an HEVC encoder and an H.264 one on the
 * same phone can stop at different sizes.
 */
object EncoderCeiling {

    /** The codec an export is written in, as Media3 names it. */
    fun mimeFor(hevc: Boolean): String = if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264

    /** Whether this phone has any encoder for [mime]. Blocking: opens the codec list. */
    fun hasEncoder(mime: String): Boolean =
        runCatching { EncoderUtil.getSupportedEncoders(mime).isNotEmpty() }.getOrDefault(false)

    /**
     * The encoders Media3 will choose from for [mime]: the hardware ones if
     * this phone has any, else the software ones.
     *
     * That is EncoderSelector.DEFAULT, which DefaultEncoderFactory uses because
     * VideoProcessor builds it with no selector of its own - "the selection
     * result contains only hardware encoders if they exist, or only software
     * encoders otherwise". Asking the unfiltered list instead was the very
     * failure this file exists to prevent: on a phone whose hardware AVC
     * encoder stops at 1920x1088 while AOSP's size-flexible software encoder
     * advertises 4080x4080, the software one won by area, so the sheet left 4K
     * tappable with no note, budgeted a 4K bitrate for it, and recorded
     * 3840x2160 in the library - while the render, going through the selector,
     * wrote 1920x1088. VideoProcessor.cbrSupported already asks the question
     * this way; the two places that model "what Media3 will pick" disagreed.
     *
     * Media3 narrows the set again by HDR editing support before it filters by
     * resolution, which this does not model - so a "Keep HDR" ceiling is still
     * measured against encoders the render may drop.
     */
    private fun candidates(mime: String): List<android.media.MediaCodecInfo> {
        val all = EncoderUtil.getSupportedEncoders(mime)
        val hardware = all.filter { it.isHardwareAccelerated }
        return if (hardware.isEmpty()) all else hardware
    }

    /** The frame the encoder writes for [asked]; [asked] itself when nothing can be said. Blocking: opens the codec list. */
    fun written(asked: ExportPresets.Resolution, mime: String = MimeTypes.VIDEO_H264): ExportPresets.Resolution {
        if (asked.width <= 0 || asked.height <= 0) return asked
        // The encoder is handed landscape frames: a portrait output is turned a
        // quarter turn for it and the turn written into the file (Transformer's
        // default, portrait encoding off), so it is asked about that way up.
        val portrait = asked.height > asked.width
        val w = if (portrait) asked.height else asked.width
        val h = if (portrait) asked.width else asked.height
        val fitted = runCatching {
            candidates(mime)
                .mapNotNull { info -> runCatching { EncoderUtil.getSupportedResolution(info, mime, w, h) }.getOrNull() }
                // The factory keeps the encoders nearest by area and takes the
                // first's size; the first of the nearest is the same encoder.
                .minByOrNull { abs(it.width.toLong() * it.height - w.toLong() * h) }
        }.getOrNull() ?: return asked
        return if (portrait) ExportPresets.Resolution(fitted.height, fitted.width)
        else ExportPresets.Resolution(fitted.width, fitted.height)
    }

    /**
     * The largest short edge the encoder writes for the edit's shape:
     * what it gives for [asked], the edit's 4K frame (EditorUiState.resolutionAt). The sheet greys every named
     * size above it (ExportSettings.aboveCeiling) rather than offering a size
     * that fails at the start of a render, or quietly halves. Blocking.
     */
    fun ceilingShortEdge(asked: ExportPresets.Resolution, mime: String): Int {
        if (asked.width <= 0 || asked.height <= 0) return 0
        val got = written(asked, mime)
        return minOf(got.width, got.height).coerceAtLeast(0)
    }
}
