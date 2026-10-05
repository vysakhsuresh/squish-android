package com.squish.app.media

import com.squish.app.editor.OutputSize

object ExportPresets {
    data class Resolution(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height.toLong()
    }

    /**
     * What the AAC track is written at: Media3's own `DefaultEncoderFactory
     * .DEFAULT_AUDIO_BITRATE`, which is 128 * 1024 and not 128,000. Nothing here
     * asks for an audio bitrate, so that is the number the encoder gets, and a
     * probe of an export measured 131 kbps in the file. The estimate said
     * 128,000 and was 2.4% light on every track.
     */
    const val AUDIO_BITRATE_BPS = 128 * 1024

    /**
     * What the phone's encoder said it will write for the frame it was asked
     * for (EncoderCeiling). Kept with the question, so an answer to a size no
     * longer chosen is never read as the answer to the one that is.
     */
    data class EncoderAnswer(val asked: Resolution, val written: Resolution) {
        val shrunk: Boolean get() = written != asked
    }

    /** The frame that will be written for [asked]: the encoder's answer when it is to this question, else [asked]. */
    fun writtenFrame(asked: Resolution, answer: EncoderAnswer?): Resolution =
        if (answer != null && answer.asked == asked) answer.written else asked

    /**
     * The frame an export at [outputP] comes out at, the source's shape kept.
     *
     * The short edge is set to [outputP] - "1080p" is 1080 across a portrait
     * screen recording and 1080 tall on a landscape clip, which is how every
     * phone and upload page uses the word. Scaling up is allowed: asking for more
     * than the source is a choice, and it gets what it asked for.
     */
    fun resolutionFor(outputP: Int, sourceWidth: Int, sourceHeight: Int): Resolution {
        if (sourceWidth <= 0 || sourceHeight <= 0) return Resolution(sourceWidth, sourceHeight)
        if (outputP == OutputSize.ORIGINAL) return even(sourceWidth, sourceHeight)
        val shortEdge = minOf(sourceWidth, sourceHeight)
        if (outputP == shortEdge) return even(sourceWidth, sourceHeight)
        val scale = outputP.toFloat() / shortEdge
        return even((sourceWidth * scale).toInt(), (sourceHeight * scale).toInt())
    }

    /**
     * The part of the frame a crop keeps, in pixels: [cropWidth] and [cropHeight]
     * are the kept rectangle's sides as fractions of the rotated frame.
     *
     * This is what every size downstream starts from. The size used to be worked
     * out from the whole frame and the crop applied first, so a 9:16 cut of a
     * landscape clip - a 608 pixel wide picture - was then fitted into a
     * 1920x1080 box and written with black pillars down both sides, while the
     * sheet promised a size nobody got.
     */
    fun croppedFrame(framedWidth: Int, framedHeight: Int, cropWidth: Float, cropHeight: Float): Resolution {
        if (framedWidth <= 0 || framedHeight <= 0) return Resolution(framedWidth, framedHeight)
        val w = (framedWidth * cropWidth.coerceIn(0f, 1f)).toInt()
        val h = (framedHeight * cropHeight.coerceIn(0f, 1f)).toInt()
        return even(w, h)
    }

    /**
     * The frame an export is written at: the kept part of the picture, at the
     * chosen size. The canvas every layer is drawn on, the encoder's frame, the
     * size the sheet shows and the pixels the bitrate pays for - one number.
     */
    fun canvasFor(outputP: Int, framedWidth: Int, framedHeight: Int, cropWidth: Float, cropHeight: Float): Resolution {
        val uncropped = cropWidth >= 1f - CROP_EPSILON && cropHeight >= 1f - CROP_EPSILON
        if (uncropped || framedWidth <= 0 || framedHeight <= 0) return resolutionFor(outputP, framedWidth, framedHeight)
        if (outputP == OutputSize.ORIGINAL) return croppedFrame(framedWidth, framedHeight, cropWidth, cropHeight)
        // Scaled from the exact cut, not from its pixels rounded down to even:
        // 9:16 of 1920x1080 is 607.5 pixels wide, and scaling the rounded 606 to
        // 720 made the file 720x1282 - two rows of black under the picture.
        val keptW = framedWidth * cropWidth.coerceIn(0f, 1f)
        val keptH = framedHeight * cropHeight.coerceIn(0f, 1f)
        val shortEdge = minOf(keptW, keptH)
        if (shortEdge <= 0f) return croppedFrame(framedWidth, framedHeight, cropWidth, cropHeight)
        val scale = outputP / shortEdge
        return Resolution(nearestEven(keptW * scale), nearestEven(keptH * scale))
    }

    private fun nearestEven(v: Float): Int = (Math.round(v / 2f) * 2).coerceAtLeast(2)

    /** A crop within this of the whole frame is no crop - CropRect's own tolerance. */
    private const val CROP_EPSILON = 0.002f

    /**
     * Rounded down to even, and never to nothing.
     *
     * Hardware encoders want even dimensions and a good many simply refuse odd
     * ones - a configuration failure on some phones and not others, which is the
     * worst kind to chase. Two paths through the sizing above returned the
     * source's own numbers untouched, so a clip that happened to be an odd number
     * of pixels tall reached the encoder that way.
     */
    private fun even(width: Int, height: Int) = Resolution(
        width = (width.coerceAtLeast(2) / 2) * 2,
        height = (height.coerceAtLeast(2) / 2) * 2
    )

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
    fun bitrateFor(
        outputP: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        fps: Float,
        sourceVideoBps: Long,
        sourceFps: Float = fps
    ): Int =
        bitrateForFrame(resolutionFor(outputP, sourceWidth, sourceHeight), sourceWidth, sourceHeight, fps, sourceVideoBps, sourceFps)

    /**
     * The same, for an output frame already worked out - a cropped one, whose
     * pixels are fewer than the source's at the same size. Budgeting a 9:16 cut
     * of a landscape clip as if it were the whole frame spent three times the bits
     * the picture needed.
     *
     * [fps] is the rate the file is written at and [sourceFps] the footage's
     * own: fewer frames a second are fewer frames to pay for, so the source's
     * rate is scaled down with them. Without that, 30 chosen on 60 fps footage
     * at Original size kept the source's whole bitrate - the same weight of
     * file with half the frames, under a hint that promised a smaller one. A
     * rate above the footage's changes nothing: no frames are made.
     */
    fun bitrateForFrame(
        out: Resolution,
        sourceWidth: Int,
        sourceHeight: Int,
        fps: Float,
        sourceVideoBps: Long,
        sourceFps: Float = fps
    ): Int {
        val nominal = nominalBitrate(out, fps)
        if (sourceVideoBps <= 0L || sourceWidth <= 0 || sourceHeight <= 0) return nominal

        val ratio = out.pixels.toDouble() / (sourceWidth.toLong() * sourceHeight)
        val frames = frameShare(fps, sourceFps)
        val scaled = sourceVideoBps * ratio * frames
        val chosen = when {
            ratio > 1.001 -> maxOf(scaled, nominal.toDouble())
            ratio < 0.999 -> minOf(scaled, nominal.toDouble())
            else -> sourceVideoBps * frames
        }
        return chosen.toLong().coerceIn(MIN_VIDEO_BPS.toLong(), MAX_VIDEO_BPS.toLong()).toInt()
    }

    /** The share of the footage's frames the file keeps: one when either rate is unknown or the file's is higher. */
    private fun frameShare(fps: Float, sourceFps: Float): Double {
        if (!fps.isFinite() || !sourceFps.isFinite() || fps <= 1f || sourceFps <= 1f) return 1.0
        return (fps / sourceFps).toDouble().coerceIn(0.0, 1.0)
    }

    /** About a tenth of a bit per pixel per frame: clean H.264 at phone sizes. */
    private fun nominalBitrate(resolution: Resolution, fps: Float): Int {
        if (resolution.width <= 0 || resolution.height <= 0) return DEFAULT_VIDEO_BPS
        val frames = fps.takeIf { it.isFinite() && it > 1f }?.coerceAtMost(60f) ?: 30f
        return (resolution.pixels * frames * BITS_PER_PIXEL)
            .toLong().coerceIn(MIN_VIDEO_BPS.toLong(), MAX_VIDEO_BPS.toLong()).toInt()
    }

    /**
     * The video bitrate a target size asks for, *before* the floor below which
     * nothing is written - so a caller can tell whether the fit is reachable at
     * all. It is not when this comes out under [MIN_VIDEO_BPS]: the file lands
     * over the limit however the budget is scaled, because the scaled number
     * is clamped straight back up to the floor. At 16 MB that is every edit
     * longer than about five minutes, and at the Email preset's 10 MB every
     * edit longer than three.
     *
     * May be zero or negative: a target too small even for the sound track
     * cannot be met by any picture at all.
     */
    fun solvedBitrateForTargetSize(targetSizeBytes: Long, durationMs: Long, includeAudio: Boolean): Int {
        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val audioBits = if (includeAudio) AUDIO_BITRATE_BPS * durationSec else 0.0
        val videoBits = targetSizeBytes * 8.0 - audioBits
        return (videoBits / durationSec).coerceIn(-1e9, MAX_FIT_VIDEO_BPS.toDouble()).toInt()
    }

    /** Whether a fit to [targetSizeBytes] over [durationMs] can be met at all. */
    fun fitReachable(targetSizeBytes: Long, durationMs: Long, includeAudio: Boolean): Boolean =
        solvedBitrateForTargetSize(targetSizeBytes, durationMs, includeAudio) >= MIN_VIDEO_BPS

    /**
     * The smallest a fit can come out at over [durationMs]: the floor's own
     * bitrate plus the sound track, which is what the file lands at when the
     * target cannot be met. For telling the user the number rather than
     * letting them discover it after the render.
     */
    fun smallestFittedBytes(durationMs: Long, includeAudio: Boolean): Long {
        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val audio = if (includeAudio) AUDIO_BITRATE_BPS.toDouble() else 0.0
        return ((MIN_VIDEO_BPS + audio) * durationSec / 8.0).toLong()
    }

    fun bitrateForTargetSize(targetSizeBytes: Long, durationMs: Long, includeAudio: Boolean): Int =
        solvedBitrateForTargetSize(targetSizeBytes, durationMs, includeAudio)
            .coerceIn(MIN_VIDEO_BPS, MAX_FIT_VIDEO_BPS)

    /**
     * The most a fit will ask for. Lower than [MAX_VIDEO_BPS], which is the
     * encoder's ceiling: a fit is for an upload limit, and spending eighty
     * megabits on a three-second clip to use up a 100 MB budget is not what
     * anybody means by it.
     */
    const val MAX_FIT_VIDEO_BPS = 20_000_000

    /**
     * The size a fitted export is written at: the largest named size, no bigger
     * than the frame itself, that the target's bitrate can still cover at
     * [FIT_MIN_BITS_PER_PIXEL] a frame.
     *
     * Fitting used to keep the source's own size and spend the whole budget on
     * bitrate: 16 MB over a minute of 4K is two megabits a second across eight
     * million pixels, which is a wall of blocks, while the same bits over 720p
     * are a clean picture. The size is solved from the budget instead - the
     * short edge steps down until each pixel has enough - and [OutputSize.ORIGINAL]
     * comes back when the frame as it is already qualifies, so a small clip is
     * not resized for nothing.
     */
    fun fitOutputP(
        targetSizeBytes: Long,
        durationMs: Long,
        fps: Float,
        frameWidth: Int,
        frameHeight: Int,
        includeAudio: Boolean
    ): Int = fitOutputPForBitrate(bitrateForTargetSize(targetSizeBytes, durationMs, includeAudio), fps, frameWidth, frameHeight)

    /**
     * The same, from the video bitrate the fit will actually spend - the
     * budget after a missed run has pulled it down (EditorUiState.fitScale).
     * Solved from the plain budget, a second run aimed a fifth lower kept the
     * frame the first run had and starved it instead of stepping down.
     */
    fun fitOutputPForBitrate(bitrate: Int, fps: Float, frameWidth: Int, frameHeight: Int): Int {
        if (frameWidth <= 0 || frameHeight <= 0) return OutputSize.ORIGINAL
        val frames = fps.takeIf { it.isFinite() && it > 1f }?.coerceAtMost(60f) ?: 30f
        val shortEdge = minOf(frameWidth, frameHeight)
        // Original first, then every named size below the frame, largest first.
        val candidates = listOf(OutputSize.ORIGINAL) + OutputSize.PRESETS.filter { it < shortEdge }.sortedDescending()
        for (p in candidates) {
            val pixels = resolutionFor(p, frameWidth, frameHeight).pixels
            if (pixels <= 0L) continue
            if (bitrate / (pixels * frames) >= FIT_MIN_BITS_PER_PIXEL) return p
        }
        return candidates.last()
    }

    /**
     * Half the clean rate ([BITS_PER_PIXEL]): the point below which a fitted
     * export is better off with fewer pixels than with starved ones.
     */
    private const val FIT_MIN_BITS_PER_PIXEL = 0.05

    private const val BITS_PER_PIXEL = 0.1
    const val MIN_VIDEO_BPS = 300_000
    const val MAX_VIDEO_BPS = 80_000_000
    private const val DEFAULT_VIDEO_BPS = 8_000_000
}
