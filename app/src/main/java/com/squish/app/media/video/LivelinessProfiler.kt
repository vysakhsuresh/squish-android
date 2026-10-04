package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.audio.PcmDecoder
import com.squish.app.timeline.Liveliness
import com.squish.app.timeline.LivelinessProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * How much is happening, over a file, measured.
 *
 * Two cheap signals, because the third - optical flow - costs more than the
 * whole feature is worth: how much the picture changes from one sampled frame
 * to the next, and how loud the sound is. Both are already decodable here;
 * neither needs a model.
 *
 * Choosing *from* the measurement is [Liveliness], which is pure and executed.
 * This half is the Android part: it decodes, and hands over numbers.
 */
object LivelinessProfiler {

    /**
     * How many frames are sampled from a file. Forty is a sample every three
     * seconds of a two-minute clip and every quarter second of a ten-second
     * one, which is as fine as a window of a second or two can use - and it is
     * forty seeks, which is what sets how long a whole timeline takes.
     */
    const val FRAMES = 40

    /** The frames are compared at this size: a thumbnail of a thumbnail, so a pan is movement and grain is not. */
    private const val COMPARE_SIDE = 24

    /** The sound is read in buckets this long, then folded onto the frame steps. */
    private const val SOUND_BUCKET_MS = 100

    /**
     * A file's liveliness. Null when it has neither a readable picture nor
     * readable sound - a shot with no profile is left alone rather than guessed
     * at, which is what makes running this over a whole timeline safe.
     */
    suspend fun profile(context: Context, uri: Uri, durationMs: Long): LivelinessProfile? =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0L) return@withContext null
            val stepMs = (durationMs / FRAMES).coerceAtLeast(1L)
            val motion = motionOf(context, uri, durationMs)
            currentCoroutineContext().ensureActive()
            val loudness = loudnessOf(context, uri, stepMs, FRAMES)
            val scores = Liveliness.combined(motion, loudness)
            if (scores.isEmpty() || scores.all { it <= 0f }) null else LivelinessProfile(stepMs, scores)
        }

    /**
     * How much the picture changes, step by step.
     *
     * The first step is given the second's difference rather than zero: there
     * is no frame before the first, and a nought there made the head of every
     * clip look still, which is the opposite of the thing the discounted ends
     * are there to catch.
     */
    private suspend fun motionOf(context: Context, uri: Uri, durationMs: Long): List<Float> {
        val frames = runCatching { ThumbnailExtractor.extractFrames(context, uri, FRAMES, durationMs) }.getOrNull()
            ?: return emptyList()
        if (frames.isEmpty()) return emptyList()
        val greys = frames.map { it?.let(::greyOf) }
        val out = MutableList(greys.size) { 0f }
        var previous: IntArray? = null
        greys.forEachIndexed { i, grey ->
            val before = previous
            if (grey != null && before != null && grey.size == before.size) {
                var sum = 0L
                grey.indices.forEach { p -> sum += abs(grey[p] - before[p]) }
                out[i] = sum.toFloat() / grey.size / 255f
            }
            if (grey != null) previous = grey
        }
        if (out.size > 1) out[0] = out[1]
        frames.forEach { it?.recycle() }
        return out
    }

    /** A frame as [COMPARE_SIDE] squared grey levels, so two frames of different sizes still compare. */
    private fun greyOf(bitmap: Bitmap): IntArray? = runCatching {
        val small = Bitmap.createScaledBitmap(bitmap, COMPARE_SIDE, COMPARE_SIDE, true)
        val pixels = IntArray(COMPARE_SIDE * COMPARE_SIDE)
        small.getPixels(pixels, 0, COMPARE_SIDE, 0, 0, COMPARE_SIDE, COMPARE_SIDE)
        if (small !== bitmap) small.recycle()
        IntArray(pixels.size) { i ->
            val p = pixels[i]
            // The same weights every shader here uses for luma.
            ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
    }.getOrNull()

    /** The sound's peaks, folded onto [steps] steps of [stepMs] - a silent file gives nothing. */
    private suspend fun loudnessOf(context: Context, uri: Uri, stepMs: Long, steps: Int): List<Float> {
        val wave = runCatching { PcmDecoder.decodePeaks(context, uri, SOUND_BUCKET_MS) }.getOrNull() ?: return emptyList()
        if (wave.peaks.isEmpty()) return emptyList()
        return (0 until steps).map { i ->
            val from = (i * stepMs / SOUND_BUCKET_MS).toInt()
            val to = ((i + 1) * stepMs / SOUND_BUCKET_MS).toInt().coerceAtLeast(from + 1)
            var top = 0f
            for (b in from until to) {
                if (b >= wave.peaks.size) break
                top = max(top, wave.peaks[b])
            }
            top
        }
    }
}
