package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.squish.app.timeline.Keyframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Measures camera shake and writes the correction that cancels it.
 *
 * The result is an ordinary keyframe track, which is the whole trick: the app
 * already applies a time-varying transform per frame, in both the preview and the
 * export, so stabilization needs no new rendering at all. It is an analysis that
 * writes numbers into a system that was already there.
 */
object Stabilizer {

    data class Result(
        val keyframes: List<Keyframe>,
        /** How much of the frame the correction costs, 0..1. */
        val crop: Float,
        val framesAnalysed: Int,
        /** The raw motion the keys were solved from, for solving again at another strength. */
        val measurement: StabilizerMeasurement
    )

    /** Beyond this the analysis strides frames rather than running forever. */
    private const val MAX_FRAMES = 1_800


    suspend fun analyze(
        context: Context,
        uri: Uri,
        /** Frame size, so the decode batch can be budgeted against it. */
        sourceWidth: Int,
        sourceHeight: Int,
        fps: Float,
        fromMs: Long,
        toMs: Long,
        strength: Float,
        onProgress: (Int, Int) -> Unit
    ): Result? = withContext(Dispatchers.IO) {
        val frameRate = if (fps > 1f) fps else 30f
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, uri)

            val totalFrames = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: 0
            if (totalFrames <= 2) return@withContext null

            val firstIndex = (fromMs / 1000.0 * frameRate).toInt().coerceIn(0, totalFrames - 1)
            val lastIndex = (toMs / 1000.0 * frameRate).toInt().coerceIn(firstIndex + 1, totalFrames - 1)

            // Shake lives around 2-10 Hz, so frames cannot be skipped far before the
            // measurement stops describing it. Striding is a last resort for very
            // long clips, and the UI says when it happened.
            val span = lastIndex - firstIndex + 1
            val stride = ((span + MAX_FRAMES - 1) / MAX_FRAMES).coerceAtLeast(1)

            var analysisWidth = 0
            var analysisHeight = 0
            var previous: LumaFrame? = null
            val motions = mutableListOf<FrameMotion>()
            val times = mutableListOf<Long>()

            // The fast path: brightness grids straight off the hardware decoder. Several
            // times quicker than the retriever below, which is kept for any file the
            // decoder will not hand over as readable YUV.
            val expected = (span / stride).coerceAtLeast(1)
            val fast = LumaDecoder.decode(context, uri, fromMs, toMs, stride) { timeMs, luma ->
                if (analysisWidth == 0) {
                    analysisWidth = luma.width
                    analysisHeight = luma.height
                }
                previous?.let {
                    motions.add(MotionEstimator.estimate(it, luma))
                    times.add(timeMs)
                }
                previous = luma
                if (motions.size % 15 == 0) onProgress(motions.size, expected)
            }
            if (!fast) {
                motions.clear()
                times.clear()
                previous = null
                analysisWidth = 0
                analysisHeight = 0
            }

            var pixelBuffer: IntArray? = null
            val perCall = FrameBatch.framesPerCall(sourceWidth, sourceHeight)

            if (!fast) FrameBatch.forEachFrame(
                retriever = retriever,
                firstIndex = firstIndex,
                lastIndex = lastIndex,
                stride = stride,
                perCall = perCall,
                onBatch = {
                    coroutineContext.ensureActive()
                    onProgress(motions.size, span / stride)
                }
            ) { frameIndex, bitmap ->
                if (analysisWidth == 0) {
                    val size = MotionEstimator.analysisSize(bitmap.width, bitmap.height)
                    analysisWidth = size.first
                    analysisHeight = size.second
                }
                val luma = toLuma(bitmap, analysisWidth, analysisHeight) { needed ->
                    val existing = pixelBuffer
                    if (existing != null && existing.size >= needed) existing
                    else IntArray(needed).also { pixelBuffer = it }
                }
                previous?.let { motions.add(MotionEstimator.estimate(it, luma)) }
                if (previous != null) times.add((frameIndex / frameRate * 1000.0).toLong())
                previous = luma
            }

            if (motions.size < 4 || analysisWidth == 0) return@withContext null

            // The decode is done; the rest is arithmetic on what it measured,
            // shared with the Strength slider so a new strength never decodes.
            val measurement = StabilizerMeasurement(analysisWidth, analysisHeight, times.toList(), motions.toList())
            val solved = StabilizerSolve.solve(measurement, strength) ?: return@withContext null
            Result(
                keyframes = solved.keyframes,
                crop = solved.crop,
                framesAnalysed = motions.size + 1,
                measurement = measurement
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Downsamples a decoded frame to the analysis grid.
     *
     * The pixel buffer is reused across the whole run: a 4K frame is eight million
     * ints, and allocating that per frame would spend more time in the collector
     * than in the analysis.
     */
    private inline fun toLuma(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        buffer: (Int) -> IntArray
    ): LumaFrame {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = buffer(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return MotionEstimator.toLuma(pixels, w, h, width, height)
    }
}
