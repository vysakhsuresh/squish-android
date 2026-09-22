package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Runs [ObjectTracker] over a clip and returns the path it found, in source time.
 *
 * Analyzed at 320 pixels wide rather than the stabilizer's 96. Stabilization only
 * needs to know how the whole frame drifted; tracking has to tell one object from
 * the things around it, and at 96 across, a face is about four pixels.
 */
object TrackRunner {

    private const val ANALYSIS_WIDTH = 320
    private const val MAX_FRAMES = 1_200
    private const val BATCH = 10

    /** How far the object may move between frames, in analysis pixels. */
    private const val SEARCH_RADIUS = 16

    suspend fun track(
        context: Context,
        uri: Uri,
        fps: Float,
        fromMs: Long,
        toMs: Long,
        startXFraction: Float,
        startYFraction: Float,
        boxFraction: Float,
        onProgress: (Int, Int) -> Unit
    ): MotionTrack? = withContext(Dispatchers.IO) {
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
            val span = lastIndex - firstIndex + 1
            val stride = ((span + MAX_FRAMES - 1) / MAX_FRAMES).coerceAtLeast(1)

            var analysisWidth = 0
            var analysisHeight = 0
            var tracker: ObjectTracker? = null
            var x = 0f
            var y = 0f
            val samples = mutableListOf<TrackSample>()
            var pixelBuffer: IntArray? = null

            var index = firstIndex
            while (index <= lastIndex) {
                coroutineContext.ensureActive()

                val count = minOf(BATCH * stride, lastIndex - index + 1)
                val frames: List<Bitmap>? =
                    runCatching { retriever.getFramesAtIndex(index, count) }.getOrNull()
                if (frames.isNullOrEmpty()) break

                frames.forEachIndexed { offset, bitmap ->
                    if ((offset % stride) == 0) {
                        if (analysisWidth == 0) {
                            analysisWidth = ANALYSIS_WIDTH
                            analysisHeight = (ANALYSIS_WIDTH.toFloat() * bitmap.height / bitmap.width)
                                .roundToInt().coerceAtLeast(64)
                        }

                        val buffer = pixelBuffer.let {
                            val needed = bitmap.width * bitmap.height
                            if (it != null && it.size >= needed) it
                            else IntArray(needed).also { made -> pixelBuffer = made }
                        }
                        bitmap.getPixels(buffer, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val luma = MotionEstimator.toLuma(
                            buffer, bitmap.width, bitmap.height, analysisWidth, analysisHeight
                        )

                        val atMs = ((index + offset) / frameRate * 1000.0).toLong()

                        if (tracker == null) {
                            // Odd sizes only: a centered patch needs a middle pixel.
                            val size = (boxFraction * analysisWidth).roundToInt()
                                .coerceIn(12, 96).let { if (it % 2 == 0) it + 1 else it }
                            x = startXFraction * analysisWidth
                            y = startYFraction * analysisHeight
                            val template = cut(luma, x.roundToInt(), y.roundToInt(), size)
                            if (template != null) {
                                tracker = ObjectTracker(template, size, size)
                                samples.add(
                                    TrackSample(atMs, startXFraction, startYFraction, 1f, 1f)
                                )
                            }
                        } else {
                            val step = tracker!!.step(luma, x, y, SEARCH_RADIUS)
                            // A lost object is not chased. Holding the last good
                            // position lets the tracker pick the object back up when
                            // it reappears, where following a bad match walks the
                            // template onto the background and never recovers.
                            if (step.confidence >= MotionTrack.LOST_BELOW) {
                                x = step.x
                                y = step.y
                            }
                            samples.add(
                                TrackSample(
                                    atMs = atMs,
                                    xFraction = x / analysisWidth,
                                    yFraction = y / analysisHeight,
                                    scale = step.scale,
                                    confidence = step.confidence
                                )
                            )
                        }
                    }
                    bitmap.recycle()
                }

                index += count
                onProgress(samples.size, span / stride)
            }

            if (samples.size < 2) null else MotionTrack(samples)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun cut(frame: LumaFrame, cx: Int, cy: Int, size: Int): FloatArray? {
        val half = size / 2
        if (cx - half < 0 || cy - half < 0 || cx + half >= frame.width || cy + half >= frame.height) {
            return null
        }
        val out = FloatArray(size * size)
        for (ty in 0 until size) {
            val sy = cy - half + ty
            for (tx in 0 until size) {
                out[ty * size + tx] = frame.pixels[sy * frame.width + (cx - half + tx)]
            }
        }
        return out
    }
}
