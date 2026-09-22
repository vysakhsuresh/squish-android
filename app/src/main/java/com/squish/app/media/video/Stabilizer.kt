package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.timeline.Transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

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
        val framesAnalysed: Int
    )

    /** Beyond this the analysis strides frames rather than running forever. */
    private const val MAX_FRAMES = 1_800

    /** Frames decoded per call. Small: these arrive as full-size bitmaps. */
    private const val BATCH = 12

    suspend fun analyze(
        context: Context,
        uri: Uri,
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

            var index = firstIndex
            var pixelBuffer: IntArray? = null

            while (index <= lastIndex) {
                coroutineContext.ensureActive()

                val count = minOf(BATCH * stride, lastIndex - index + 1)
                // Typed explicitly: getFramesAtIndex is a Java call, so its result is a
                // platform type and an inferred val would carry that ambiguity onward.
                val frames: List<Bitmap>? =
                    runCatching { retriever.getFramesAtIndex(index, count) }.getOrNull()
                if (frames.isNullOrEmpty()) break

                frames.forEachIndexed { offset, bitmap ->
                    if ((offset % stride) == 0) {
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
                        if (previous != null) {
                            times.add(((index + offset) / frameRate * 1000.0).toLong())
                        }
                        previous = luma
                    }
                    bitmap.recycle()
                }

                index += count
                onProgress(motions.size, span / stride)
            }

            if (motions.size < 4 || analysisWidth == 0) return@withContext null

            val window = (10 + (35 * strength)).roundToInt().coerceAtLeast(4)
            val cropBudget = 0.03f + 0.12f * strength
            // A correction of d analysis pixels is 2d/size in the half-frame units the
            // transform speaks, so each axis's pixel budget is the crop budget halved
            // against that axis's own length.
            val corrections = TrajectorySmoother.smooth(
                motions = motions,
                windowFrames = window,
                maxShiftX = cropBudget / 2f * analysisWidth,
                maxShiftY = cropBudget / 2f * analysisHeight,
                maxRotationDegrees = 1.5f
            )

            val crop = TrajectorySmoother.requiredCrop(
                corrections, analysisWidth.toFloat(), analysisHeight.toFloat()
            )
            val scale = 1f + crop

            val keys = corrections.mapIndexed { i, correction ->
                Keyframe(
                    atMs = times.getOrElse(i) { 0L },
                    transform = Transform(
                        scale = scale,
                        offsetXFraction = 2f * correction.dx / analysisWidth,
                        offsetYFraction = 2f * correction.dy / analysisHeight,
                        rotationDegrees = correction.rotationDegrees
                    ),
                    // Linear between densely sampled keys. Smoothing already shaped
                    // the path; easing each tiny segment again would fight it.
                    easing = KeyframeEasing.Linear
                )
            }.sortedBy { it.atMs }

            Result(keyframes = keys, crop = crop, framesAnalysed = motions.size + 1)
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
