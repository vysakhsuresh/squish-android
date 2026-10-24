package com.squish.app.media

import com.squish.app.editor.OutputSize

/**
 * How much the encoder is allowed to spend, against the rate the source's own
 * pixels would get (ExportPresets.bitrateForFrame). "Standard" is that rate -
 * the recommended one, which its hint says; the chip is a third of a 360 dp
 * sheet, where "Recommended" lost its last letter. The other two are a clear
 * step either side of it, far enough apart to see and to weigh, close enough
 * that neither is a mistake. The names are in drafts and the defaults; the
 * labels are free to change.
 */
enum class ExportQuality(val label: String, val bitrateScale: Float) {
    Lower("Lower", 0.6f),
    Recommended("Standard", 1f),
    Higher("Higher", 1.6f);

    companion object {
        fun fromName(name: String?): ExportQuality = entries.firstOrNull { it.name == name } ?: Recommended
    }
}

/**
 * The export sheet's decisions that are arithmetic: the frame rate the file
 * gets, what a codec or a quality step does to the bitrate, which sizes a phone
 * can write, where a fitted export lands and how a missed target is chased.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/ExportSettingsChecks.kt).
 */
object ExportSettings {

    /** The frame rates the sheet offers, besides the footage's own. */
    val FPS_CHOICES = listOf(24, 25, 30, 50, 60)

    /** "Keep the footage's rate", which is the default and what every draft before the row had. */
    const val SOURCE_FPS = 0

    /**
     * HEVC at about two thirds of H.264's bits looks the same, which is the
     * whole reason to offer it. The estimate and the encoder both read this, so
     * "smaller file" on the sheet is a promise the file keeps.
     */
    const val HEVC_BITRATE_SCALE = 0.65f

    /**
     * What "Auto" keeps at most. A phone's slow-motion mode writes 120 or 240
     * frames a second; kept whole, the cuts-only export asked the encoder for
     * a file at that rate (EDGE_PLAN B5) - refused by most and enormous where
     * not. The frames past 60 are there to be slowed into, not played.
     */
    const val AUTO_MAX_FPS = 60f

    /**
     * The frame rate an encoder is configured with: the footage's own, unless
     * frames are dropped to write [writtenFps], and never more than what is
     * written. Media3 configures the encoder from the decoded footage, so 240
     * fps slow-motion footage written at 60 asked the phone's H.264 encoder
     * for 240 frames a second, and it failed the export before the first
     * (seen on the moto, 25 October).
     */
    fun encoderFrameRate(sourceFps: Float, writtenFps: Int): Float = when {
        !sourceFps.isFinite() || sourceFps <= 0f -> writtenFps.toFloat()
        sourceFps > writtenFps + 0.5f -> writtenFps.toFloat()
        else -> sourceFps
    }

    /** The rate the file is written at: the choice, or the footage's own (up to [AUTO_MAX_FPS]) when none was made. */
    fun effectiveFps(outputFps: Int, sourceFps: Float): Float = when {
        outputFps > 0 -> outputFps.toFloat()
        sourceFps > AUTO_MAX_FPS + 0.5f -> AUTO_MAX_FPS
        else -> sourceFps
    }

    /**
     * Whether a chosen rate asks for frames the footage does not have. Dropping
     * frames is arithmetic; making them is not, so 60 on a 30 fps shot writes 30
     * fps frames under a 60 fps label, and the sheet should say so.
     */
    fun exceedsSource(outputFps: Int, sourceFps: Float): Boolean =
        outputFps > 0 && sourceFps > 1f && outputFps > sourceFps + 0.5f

    /** [bitrate] after the quality step and the codec's saving, within what an encoder takes. */
    fun scaledBitrate(bitrate: Int, quality: ExportQuality, hevc: Boolean): Int {
        val scale = quality.bitrateScale * (if (hevc) HEVC_BITRATE_SCALE else 1f)
        return (bitrate * scale).toLong().coerceIn(ExportPresets.MIN_VIDEO_BPS.toLong(), ExportPresets.MAX_VIDEO_BPS.toLong()).toInt()
    }

    /**
     * Whether a named size is past what this phone's encoder writes. [ceilingP]
     * is the short edge the encoder gave for a 4K frame of the edit's shape
     * (EncoderCeiling.ceilingShortEdge), or 0 while unknown - then nothing is
     * greyed, and the encoder's own answer for the size chosen still stands.
     * Original is never greyed: the encoder's answer for it is shown instead.
     */
    fun aboveCeiling(p: Int, ceilingP: Int): Boolean =
        ceilingP > 0 && p != OutputSize.ORIGINAL && p > ceilingP

    /** The largest short edge a hand-typed size may take: the sheet's limit, or the encoder's when it is lower. */
    fun customCeiling(ceilingP: Int): Int =
        if (ceilingP in 1 until OutputSize.MAX_P) ceilingP else OutputSize.MAX_P

    /**
     * The size a remembered default lands at on a new project: the size kept,
     * unless it is bigger than the footage. A 4K remembered from the last
     * project would otherwise upscale every 720p clip opened afterwards by
     * default, which nobody chose for this one.
     */
    fun defaultOutputP(remembered: Int, sourceShortEdge: Int): Int = when {
        remembered == OutputSize.ORIGINAL -> OutputSize.ORIGINAL
        sourceShortEdge > 0 && remembered > sourceShortEdge -> OutputSize.ORIGINAL
        else -> remembered
    }

    /**
     * The rate a remembered default lands at on a new project, by the same rule
     * as the size: the rate kept, unless the footage does not run that fast.
     *
     * A 60 chosen once for 60 fps footage was carried onto every project after
     * it, and on a 30 fps shot that writes the same thirty frames under a 60
     * fps label at twice the bitrate - the sheet said so in a line under the
     * row ("a higher rate can't add frames") while the row itself stayed on 60.
     * Nobody chose 60 for this footage, so it falls back to the footage's own.
     */
    fun defaultOutputFps(remembered: Int, sourceFps: Float): Int =
        if (exceedsSource(remembered, sourceFps)) SOURCE_FPS else remembered

    /**
     * Whether a fitted export missed its target. Encoders overshoot a requested
     * rate a little on a busy scene; a file within [OVERSHOOT_TOLERANCE] of the
     * limit is under it for every upload page that rounds, and asking for
     * another run over that would be fussing.
     */
    fun overshoots(actualBytes: Long, targetBytes: Long): Boolean =
        targetBytes > 0L && actualBytes > (targetBytes * (1.0 + OVERSHOOT_TOLERANCE)).toLong()

    /**
     * The bitrate scale for the next fitted run after one that came out at
     * [actualBytes]: the previous scale pulled down by the amount it missed
     * by, and a little further, since the encoder that overshot once will
     * overshoot again by about the same share.
     */
    fun retryScale(previousScale: Float, actualBytes: Long, targetBytes: Long): Float {
        if (actualBytes <= 0L || targetBytes <= 0L) return previousScale
        val ratio = targetBytes.toDouble() / actualBytes
        return (previousScale * ratio * RETRY_MARGIN).toFloat().coerceIn(MIN_RETRY_SCALE, 1f)
    }

    /** Two per cent: what a VBR encoder is allowed to miss by before it is a miss. */
    const val OVERSHOOT_TOLERANCE = 0.02

    /** A run that missed is aimed four per cent under the target the next time. */
    private const val RETRY_MARGIN = 0.96

    /** However far off the first run was, the next is never starved past a fifth of the target rate. */
    private const val MIN_RETRY_SCALE = 0.2f

    /** The rates a file is made at; one measured near none of them is a mix of several. */
    private val STANDARD_RATES = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 90f, 120f)

    /**
     * A file's rate as the done screen says it: "30 fps", "29.97 fps" - or
     * "Varies" for one measured between the standard rates, which is a file
     * whose clips ran at different rates, kept each at its own. The average
     * of a reel of 60 fps and 30 fps clips read "43.35 fps": a rate nothing
     * was shot or written at, which reads as a fault.
     */
    fun rateLabel(fps: Float): String {
        if (fps <= 0f || !fps.isFinite()) return "…"
        val near = STANDARD_RATES.minByOrNull { kotlin.math.abs(it - fps) }?.takeIf { kotlin.math.abs(it - fps) < 0.05f * maxOf(1f, it / 30f) } ?: return "Varies"
        return if (near % 1f > 0.01f) String.format(java.util.Locale.ROOT, "%.2f fps", near) else String.format(java.util.Locale.ROOT, "%.0f fps", near)
    }
}
