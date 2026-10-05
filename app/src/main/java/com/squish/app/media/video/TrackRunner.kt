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

    /** How far the object may move between frames, in analysis pixels. */
    private const val SEARCH_RADIUS = 16

    suspend fun track(
        context: Context,
        uri: Uri,
        /** Frame size, so the decode batch can be budgeted against it. */
        sourceWidth: Int,
        sourceHeight: Int,
        fps: Float,
        fromMs: Long,
        toMs: Long,
        startXFraction: Float,
        startYFraction: Float,
        boxFraction: Float,
        onProgress: (Int, Int) -> Unit
    ): MotionTrack? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, uri)
            val totalFrames = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toIntOrNull() ?: 0
            if (totalFrames <= 2) return@withContext null

            // This file's own rate, not the edit's.
            //
            // [fps] is EditorUiState.fps, which is the *lead* file's rate - the
            // one the project was opened on. Frame indices and the frame count
            // below come from this clip's file, so on a 60 fps clip in a 30 fps
            // project the window read was half as long as it should be and
            // every sample was stamped at twice its real moment: a mask pinned
            // to the track followed its subject at half speed and then held.
            // The frame count and the duration are both this file's, so their
            // ratio is its rate; the edit's is only the fallback.
            val lengthMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frameRate = when {
                lengthMs > 0L && totalFrames > 1 -> (totalFrames * 1000.0 / lengthMs).toFloat()
                fps > 1f -> fps
                else -> 30f
            }.coerceIn(1f, 480f)

            val firstIndex = (fromMs / 1000.0 * frameRate).toInt().coerceIn(0, totalFrames - 2)
            // The low bound first, so the range is never inverted. It was
            // firstIndex + 1 against totalFrames - 1, and Kotlin's coerceIn
            // throws on an empty range rather than taking a bound - so aiming at
            // the clip's very last frame failed with "tracking failed" and no
            // reason. Reachable now that the run starts at the playhead.
            val lastIndex = (toMs / 1000.0 * frameRate).toInt()
                .coerceAtMost(totalFrames - 1)
                .coerceAtLeast(firstIndex + 1)
            val span = lastIndex - firstIndex + 1
            val stride = ((span + MAX_FRAMES - 1) / MAX_FRAMES).coerceAtLeast(1)

            var analysisWidth = 0
            var analysisHeight = 0
            var tracker: ObjectTracker? = null
            var x = 0f
            var y = 0f
            val samples = mutableListOf<TrackSample>()
            var pixelBuffer: IntArray? = null

            val perCall = FrameBatch.framesPerCall(sourceWidth, sourceHeight)

            FrameBatch.forEachFrame(
                retriever = retriever,
                firstIndex = firstIndex,
                lastIndex = lastIndex,
                stride = stride,
                perCall = perCall,
                onBatch = {
                    coroutineContext.ensureActive()
                    onProgress(samples.size, span / stride)
                }
            ) { frameIndex, bitmap ->
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

                val atMs = (frameIndex / frameRate * 1000.0).toLong()

                val current = tracker
                if (current == null) {
                    // Odd sizes only: a centered patch needs a middle pixel.
                    val size = (boxFraction * analysisWidth).roundToInt()
                        .coerceIn(12, 96).let { if (it % 2 == 0) it + 1 else it }
                    x = startXFraction * analysisWidth
                    y = startYFraction * analysisHeight
                    val template = cut(luma, x.roundToInt(), y.roundToInt(), size)
                    // Given up on at once rather than tried again every frame.
                    // `cut` fails on bounds and not on content - the point
                    // chosen is nearer an edge than half the box - and neither
                    // the point nor the size changes from one frame to the
                    // next, so no later frame could have succeeded. The whole
                    // clip was decoded to fail the same way up to twelve
                    // hundred times, with the progress bar at nothing the
                    // entire time (it is fed samples.size, which stays empty),
                    // and only then did the panel say it could not follow it.
                    if (template == null) return@withContext null
                    tracker = ObjectTracker(template, size, size)
                    samples.add(TrackSample(atMs, startXFraction, startYFraction, 1f, 1f))
                } else {
                    val step = current.step(luma, x, y, SEARCH_RADIUS)
                    // A lost object is not chased. Holding the last good position
                    // lets the tracker pick the object back up when it reappears,
                    // where following a bad match walks the template onto the
                    // background and never recovers.
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
