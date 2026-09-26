package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads a clip's frames as small brightness grids, straight off the hardware decoder.
 *
 * Stabilization only needs brightness on a ~96-wide grid. The old path asked
 * MediaMetadataRetriever for every frame as a full-size ARGB bitmap - a colour
 * conversion and a multi-megabyte copy per frame - and then read every pixel back
 * to throw nearly all of them away. Here the decoder writes YUV into its own
 * buffer and only the grid's points of the Y plane are ever read, so the cost per
 * frame is the decode itself, which the phone's video block does in hardware.
 *
 * Frames come out in the order they are shown and rotated to display orientation,
 * exactly as the retriever handed them over, so the analysis downstream sees no
 * difference except in how long it waited.
 */
object LumaDecoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * Calls [onFrame] with each frame from [fromMs] to [toMs] (every [stride]th),
     * as a [gridWidth]-wide brightness grid. Returns false if the file could not be
     * decoded this way at all, so the caller can fall back to the slow path.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        stride: Int,
        onFrame: suspend (timeMs: Long, frame: LumaFrame) -> Unit
    ): Boolean = decodeFrames(
        context, uri, fromMs, toMs,
        wanted = { index, _ -> index % stride == 0 },
        convert = ::toGrid,
        onFrame = onFrame
    )

    /**
     * Calls [onFrame] with a small colour picture of a frame roughly every
     * [everyMs], the longer side [maxSide] pixels. Same decoder, same order and
     * orientation as [decode] - for the person segmenter, which needs colour and
     * the exact frame, not the nearest keyframe the retriever would hand back fast.
     */
    suspend fun decodeColour(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        everyMs: Long,
        maxSide: Int,
        onFrame: suspend (timeMs: Long, frame: Bitmap) -> Unit
    ): Boolean {
        var next = fromMs
        return decodeFrames(
            context, uri, fromMs, toMs,
            wanted = { _, timeMs -> (timeMs >= next).also { if (it) next = timeMs + everyMs } },
            convert = { image, rotation -> toBitmap(image, rotation, maxSide) },
            onFrame = onFrame
        )
    }

    private suspend fun <T> decodeFrames(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        wanted: (index: Int, timeMs: Long) -> Boolean,
        convert: (android.media.Image, Int) -> T,
        onFrame: suspend (timeMs: Long, frame: T) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var index = 0

            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: return false
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > toMs * 1000L + 200_000L) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex < 0) continue
                val timeMs = info.presentationTimeUs / 1000L
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && timeMs in fromMs..toMs) {
                    if (wanted(index, timeMs)) {
                        val image = codec.getOutputImage(outIndex)
                        if (image != null) {
                            val frame = image.use { convert(it, rotation) }
                            codec.releaseOutputBuffer(outIndex, false)
                            onFrame(timeMs, frame)
                        } else {
                            codec.releaseOutputBuffer(outIndex, false)
                            return false
                        }
                    } else {
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    index++
                } else {
                    codec.releaseOutputBuffer(outIndex, false)
                }
                if (ended || timeMs > toMs) break
            }
            return index > 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * The Y plane point-sampled onto the analysis grid, in display orientation -
     * each grid point is mapped back through the rotation to the stored pixel.
     */
    private fun toGrid(image: android.media.Image, rotation: Int): LumaFrame {
        val codedW = image.width
        val codedH = image.height
        val quarter = rotation % 180 != 0
        val displayW = if (quarter) codedH else codedW
        val displayH = if (quarter) codedW else codedH
        val (gw, gh) = MotionEstimator.analysisSize(displayW, displayH)

        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = FloatArray(gw * gh)
        for (gy in 0 until gh) {
            val dy = (gy.toLong() * displayH / gh).toInt()
            for (gx in 0 until gw) {
                val dx = (gx.toLong() * displayW / gw).toInt()
                val (cx, cy) = when (((rotation % 360) + 360) % 360) {
                    90 -> dy to (codedH - 1 - dx)
                    180 -> (codedW - 1 - dx) to (codedH - 1 - dy)
                    270 -> (codedW - 1 - dy) to dx
                    else -> dx to dy
                }
                val y = buffer.get(cy.coerceIn(0, codedH - 1) * rowStride + cx.coerceIn(0, codedW - 1) * pixelStride).toInt() and 0xFF
                out[gy * gw + gx] = y / 255f
            }
        }
        return LumaFrame(gw, gh, out)
    }

    /**
     * The frame scaled so its longer side is [maxSide], converted from the
     * decoder's YUV (BT.601, video range) point by point, in display orientation.
     */
    private fun toBitmap(image: android.media.Image, rotation: Int, maxSide: Int): Bitmap {
        val codedW = image.width
        val codedH = image.height
        val quarter = rotation % 180 != 0
        val displayW = if (quarter) codedH else codedW
        val displayH = if (quarter) codedW else codedH
        val scale = maxSide.toFloat() / maxOf(displayW, displayH)
        val w = (displayW * scale).toInt().coerceAtLeast(1)
        val h = (displayH * scale).toInt().coerceAtLeast(1)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val pixels = IntArray(w * h)
        val turn = ((rotation % 360) + 360) % 360
        for (oy in 0 until h) {
            val dy = (oy.toLong() * displayH / h).toInt()
            for (ox in 0 until w) {
                val dx = (ox.toLong() * displayW / w).toInt()
                val (cx0, cy0) = when (turn) {
                    90 -> dy to (codedH - 1 - dx)
                    180 -> (codedW - 1 - dx) to (codedH - 1 - dy)
                    270 -> (codedW - 1 - dy) to dx
                    else -> dx to dy
                }
                val cx = cx0.coerceIn(0, codedW - 1)
                val cy = cy0.coerceIn(0, codedH - 1)
                val y = yPlane.buffer.get(cy * yPlane.rowStride + cx * yPlane.pixelStride).toInt() and 0xFF
                val u = (uPlane.buffer.get((cy / 2) * uPlane.rowStride + (cx / 2) * uPlane.pixelStride).toInt() and 0xFF) - 128
                val v = (vPlane.buffer.get((cy / 2) * vPlane.rowStride + (cx / 2) * vPlane.pixelStride).toInt() and 0xFF) - 128
                val c = 1.164f * (y - 16)
                val r = (c + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (c - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (c + 2.017f * u).toInt().coerceIn(0, 255)
                pixels[oy * w + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
