package com.squish.app.media

import com.squish.app.editor.OutputSize

object ExportPresets {
    data class Resolution(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height.toLong()
    }

    const val AUDIO_BITRATE_BPS = 128_000

    /**
     * The frame an export at [outputP] comes out at, the source's shape kept.
     *
     * The short edge is set to [outputP] - "1080p" is 1080 across a portrait
     * screen recording and 1080 tall on a landscape clip, which is how every
     * phone and upload page uses the word. Scaling up is allowed: asking for more
     * than the source is a choice, and it gets what it asked for.
     */
    fun resolutionFor(outputP: Int, sourceWidth: Int, sourceHeight: Int): Resolution {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputP == OutputSize.ORIGINAL) {
            return Resolution(sourceWidth, sourceHeight)
        }
        val shortEdge = minOf(sourceWidth, sourceHeight)
        if (outputP == shortEdge) return Resolution(sourceWidth, sourceHeight)
        val scale = outputP.toFloat() / shortEdge
        val w = ((sourceWidth * scale).toInt().coerceAtLeast(2) / 2) * 2
        val h = ((sourceHeight * scale).toInt().coerceAtLeast(2) / 2) * 2
        return Resolution(w, h)
    }

    /**
     * How many bits a second the source's picture was recorded at, or 0 when that
     * cannot be worked out. The file's weight over its length, less the sound.
     */
    fun sourceVideoBitrate(sizeBytes: Long, durationMs: Long, hasAudio: Boolean): Long {
        if (sizeBytes <= 0L || durationMs <= 0L) return 0L
        val total = sizeBytes * 8.0 / (durationMs / 1000.0)
        val video = total - if (hasAudio) AUDIO_BITRATE_BPS else 0
        return video.toLong().coerceAtLeast(0L)
    }

    /**
     * The video bitrate for an export at [outputP].
     *
     * Taken from the source rather than from a table. The old table gave "High"
     * 9 Mbps, and a phone records at 15-50, so High came out smaller than the
     * original it was supposedly better than. Now the bitrate follows the pixels:
     *
     * - the source's own size keeps the source's own bitrate, so Original weighs
     *   about what the original did;
     * - scaling up spends the source's bits-per-pixel on every extra pixel, so a
     *   bigger size is always a bigger file;
     * - scaling down never spends more than a clean encode of that size needs, so
     *   a smaller size is always a smaller file.
     *
     * With no source bitrate to go on, a nominal rate for the frame size is used.
     */
    fun bitrateFor(outputP: Int, sourceWidth: Int, sourceHeight: Int, fps: Float, sourceVideoBps: Long): Int {
        val out = resolutionFor(outputP, sourceWidth, sourceHeight)
        val nominal = nominalBitrate(out, fps)
        if (sourceVideoBps <= 0L || sourceWidth <= 0 || sourceHeight <= 0) return nominal

        val ratio = out.pixels.toDouble() / (sourceWidth.toLong() * sourceHeight)
        val scaled = sourceVideoBps * ratio
        val chosen = when {
            ratio > 1.001 -> maxOf(scaled, nominal.toDouble())
            ratio < 0.999 -> minOf(scaled, nominal.toDouble())
            else -> sourceVideoBps.toDouble()
        }
        return chosen.toLong().coerceIn(MIN_VIDEO_BPS.toLong(), MAX_VIDEO_BPS.toLong()).toInt()
    }

    /** About a tenth of a bit per pixel per frame: clean H.264 at phone sizes. */
    private fun nominalBitrate(resolution: Resolution, fps: Float): Int {
        if (resolution.width <= 0 || resolution.height <= 0) return DEFAULT_VIDEO_BPS
        val frames = fps.takeIf { it.isFinite() && it > 1f }?.coerceAtMost(60f) ?: 30f
        return (resolution.pixels * frames * BITS_PER_PIXEL)
            .toLong().coerceIn(MIN_VIDEO_BPS.toLong(), MAX_VIDEO_BPS.toLong()).toInt()
    }

    fun bitrateForTargetSize(targetSizeBytes: Long, durationMs: Long, includeAudio: Boolean): Int {
        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val audioBits = if (includeAudio) AUDIO_BITRATE_BPS * durationSec else 0.0
        val totalBits = targetSizeBytes * 8.0
        val videoBits = (totalBits - audioBits).coerceAtLeast(200_000.0)
        return (videoBits / durationSec).toInt().coerceIn(300_000, 20_000_000)
    }

    private const val BITS_PER_PIXEL = 0.1
    private const val MIN_VIDEO_BPS = 300_000
    private const val MAX_VIDEO_BPS = 80_000_000
    private const val DEFAULT_VIDEO_BPS = 8_000_000
}
