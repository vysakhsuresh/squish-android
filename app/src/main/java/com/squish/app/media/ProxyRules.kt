package com.squish.app.media

/**
 * When a clip gets a light preview copy (EDGE_PLAN C5). It used to be only
 * above 1920 px on the long edge; the frames that make a phone's preview stutter
 * are as often 1080p at 60, 10-bit or HEVC 1080p (a heavier decode for the same
 * size), and camera files at very high bitrates. A proxy is a whole re-encode of
 * the file, so it is still only worth it where the original is the problem.
 */
object ProxyRules {

    /** Settings' Preview quality: the rule below, a proxy for anything sizeable, or never. */
    enum class Quality(val label: String, val blurb: String) {
        Auto("Auto", "A light copy where the original would stutter: above 1080p, 1080p at 60, 10-bit or HEVC, very high bitrates."),
        Smooth("Smooth", "A light copy of anything 720p or larger - the smoothest preview, at some battery."),
        Full("Full", "Always the original - the sharpest preview, and heavy footage may stutter.")
    }

    data class Footage(
        val width: Int,
        val height: Int,
        val fps: Float,
        val durationMs: Long,
        /** FFmpeg's codec name ("h264", "hevc", "vp9"...), or "" when unknown. */
        val codec: String = "",
        /** Bits per sample, 0 when unknown. */
        val bits: Int = 0,
        /** Bits per second, 0 when unknown. */
        val bitRate: Long = 0L
    ) {
        val longEdge: Int get() = maxOf(width, height)
    }

    const val MAX_DURATION_MS = 15L * 60_000
    const val BIG_LONG_EDGE = 1920
    const val SMOOTH_LONG_EDGE = 1280
    const val HIGH_RATE_FPS = 50f
    const val HIGH_BITRATE = 40_000_000L

    /** Whether [footage] gets a proxy at [quality], and why - the reason is what the notice says. */
    fun reason(footage: Footage, quality: Quality = Quality.Auto): String? {
        if (footage.durationMs !in 1..MAX_DURATION_MS) return null
        val big = footage.longEdge > BIG_LONG_EDGE
        val fullHd = footage.longEdge >= BIG_LONG_EDGE
        return when (quality) {
            Quality.Full -> null
            Quality.Smooth -> if (footage.longEdge >= SMOOTH_LONG_EDGE) "Smooth preview" else null
            Quality.Auto -> when {
                big -> "above 1080p"
                fullHd && footage.fps > HIGH_RATE_FPS -> "1080p at ${footage.fps.toInt()} fps"
                fullHd && footage.bits > 8 -> "10-bit"
                fullHd && footage.codec == "hevc" -> "HEVC"
                footage.bitRate > HIGH_BITRATE -> "a very high bitrate"
                else -> null
            }
        }
    }

    fun worth(footage: Footage, quality: Quality = Quality.Auto): Boolean = reason(footage, quality) != null
}
