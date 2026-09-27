package com.squish.app.media

import com.squish.app.editor.Quality

object ExportPresets {
    data class Resolution(val width: Int, val height: Int)

    const val AUDIO_BITRATE_BPS = 128_000

    /**
     * The frame to encode into, for footage of this shape at this quality.
     *
     * Always even in both directions. Hardware encoders want even dimensions and
     * a good many simply refuse odd ones, which is the sort of failure that
     * happens on one phone and not another and is miserable to chase. Two paths
     * through here used to return the source's own numbers untouched - the "it
     * already fits" shortcut and Original - so a clip that happened to be an odd
     * number of pixels tall went to the encoder that way.
     *
     * The size passed in must be the shape the frame will *be* when it arrives,
     * which after a user rotation is not the shape it was shot at.
     */
    fun resolutionFor(quality: Quality, sourceWidth: Int, sourceHeight: Int): Resolution {
        if (sourceWidth <= 0 || sourceHeight <= 0) return Resolution(sourceWidth, sourceHeight)
        val targetLongEdge = when (quality) {
            Quality.Small -> 640
            Quality.Medium -> 1280
            Quality.High -> 1920
            Quality.Original -> maxOf(sourceWidth, sourceHeight)
        }
        val longEdge = maxOf(sourceWidth, sourceHeight)
        if (longEdge <= targetLongEdge) return even(sourceWidth, sourceHeight)
        val scale = targetLongEdge.toFloat() / longEdge
        return even((sourceWidth * scale).toInt(), (sourceHeight * scale).toInt())
    }

    /** Rounded down to even, and never to nothing. */
    private fun even(width: Int, height: Int) = Resolution(
        width = (width.coerceAtLeast(2) / 2) * 2,
        height = (height.coerceAtLeast(2) / 2) * 2
    )

    fun bitrateFor(quality: Quality): Int = when (quality) {
        Quality.Small -> 1_500_000
        Quality.Medium -> 4_000_000
        Quality.High -> 9_000_000
        Quality.Original -> 16_000_000
    }

    fun bitrateForTargetSize(targetSizeBytes: Long, durationMs: Long, includeAudio: Boolean): Int {
        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val audioBits = if (includeAudio) AUDIO_BITRATE_BPS * durationSec else 0.0
        val totalBits = targetSizeBytes * 8.0
        val videoBits = (totalBits - audioBits).coerceAtLeast(200_000.0)
        return (videoBits / durationSec).toInt().coerceIn(300_000, 20_000_000)
    }
}
