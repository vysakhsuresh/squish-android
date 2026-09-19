package com.squish.app.media

import com.squish.app.editor.Quality

object ExportPresets {
    data class Resolution(val width: Int, val height: Int)

    const val AUDIO_BITRATE_BPS = 128_000

    fun resolutionFor(quality: Quality, sourceWidth: Int, sourceHeight: Int): Resolution {
        if (sourceWidth <= 0 || sourceHeight <= 0) return Resolution(sourceWidth, sourceHeight)
        val targetLongEdge = when (quality) {
            Quality.Small -> 640
            Quality.Medium -> 1280
            Quality.High -> 1920
            Quality.Original -> maxOf(sourceWidth, sourceHeight)
        }
        val longEdge = maxOf(sourceWidth, sourceHeight)
        if (longEdge <= targetLongEdge) return Resolution(sourceWidth, sourceHeight)
        val scale = targetLongEdge.toFloat() / longEdge
        val w = ((sourceWidth * scale).toInt().coerceAtLeast(2) / 2) * 2
        val h = ((sourceHeight * scale).toInt().coerceAtLeast(2) / 2) * 2
        return Resolution(w, h)
    }

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
