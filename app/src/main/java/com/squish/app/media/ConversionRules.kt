package com.squish.app.media

/**
 * When a file is converted on its way into a project, and into what.
 *
 * Some files only decode here in software - the FFmpeg decoders the app
 * carries (SoftwareDecoders) - because no decoder on the phone takes them:
 * HEVC 10-bit on a phone with no Main 10 decoder, an old DivX, DTS or TrueHD
 * sound off a film rip. The preview can play those straight from the software
 * decoder. Almost nothing else can: the filmstrip's frames, the waveform, a
 * freeze, Reverse, Stabilize and the export all read a clip's file through the
 * platform's own decoders, and each of them would have to be taught the
 * software path separately - and the software path cannot hand back the last
 * few frames of a stream (nextlib cannot drain FFmpeg; see SquishFfmpegCodecs),
 * which on a trimmed clip is a visible freeze at every cut.
 *
 * So such a file is converted **once, whole, on import**, into a file every
 * path already reads - H.264 and AAC - and the project keeps the copy
 * (MediaConversion). The missing frames are then at the very end of the whole
 * file, where nothing looks. This object is the arithmetic of that decision,
 * kept pure so it can be executed (tools/jvm/ConversionChecks.kt).
 */
object ConversionRules {

    /** Which tracks the copy re-encodes; the other is copied as it is. */
    data class Plan(val video: Boolean, val audio: Boolean) {
        val needed: Boolean get() = video || audio
    }

    /**
     * A track is re-encoded when only the software decoder takes it. One that
     * nothing takes is left alone: the failure card already names it, and
     * a track the platform decodes is copied without a decode at all, so a
     * film rip with a fine H.264 picture and a DTS track keeps its picture
     * byte for byte and gets AAC beside it.
     */
    fun plan(videoSoftwareOnly: Boolean, audioSoftwareOnly: Boolean): Plan =
        Plan(video = videoSoftwareOnly, audio = audioSoftwareOnly)

    /** A frame, both edges even as every encoder wants. */
    data class Frame(val width: Int, val height: Int)

    /**
     * The copy's frame: the source's own shape, written as it is when the
     * encoder takes it, else scaled to the largest short edge the encoder
     * writes (EncoderCeiling.ceilingShortEdge). Null means "as it is" - no
     * Presentation on the item, so nothing is resampled.
     */
    fun frame(displayWidth: Int, displayHeight: Int, ceilingShortEdge: Int): Frame? {
        if (displayWidth <= 0 || displayHeight <= 0) return null
        val short = minOf(displayWidth, displayHeight)
        if (ceilingShortEdge <= 0 || short <= ceilingShortEdge) return null
        val scale = ceilingShortEdge.toDouble() / short
        return Frame(even(displayWidth * scale), even(displayHeight * scale))
    }

    private fun even(value: Double): Int = (Math.round(value / 2.0) * 2).toInt().coerceAtLeast(2)

    /**
     * The copy's bitrate. It is a mezzanine, not a delivery file: twice the
     * bits per pixel a clean phone encode spends, so it does not add a
     * second generation's worth of softness on top of the original's, and
     * never under the source's own rate when that is known - a file already
     * at 60 Mbps is not written back at 20. Capped where phone encoders stop.
     */
    fun bitrate(width: Int, height: Int, fps: Float, sourceBps: Long): Int {
        val frames = if (fps.isFinite() && fps > 1f) fps.coerceAtMost(60f) else 30f
        val clean = width.toLong() * height * frames * BITS_PER_PIXEL
        return maxOf(clean, sourceBps.toDouble()).toLong().coerceIn(MIN_BPS.toLong(), MAX_BPS.toLong()).toInt()
    }

    /** The source's overall rate from what a file reader can see: its size over its length. */
    fun sourceBps(sizeBytes: Long, durationMs: Long): Long =
        if (sizeBytes <= 0L || durationMs <= 0L) 0L else sizeBytes * 8_000L / durationMs

    /** Twice ExportPresets' tenth of a bit per pixel per frame. */
    const val BITS_PER_PIXEL = 0.2
    const val MIN_BPS = 2_000_000
    const val MAX_BPS = 80_000_000
}
