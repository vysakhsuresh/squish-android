package com.squish.app.av

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import com.squish.app.media.video.LumaFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A stretch of a file's frames in order through Squish's FFmpeg, for the
 * analyses that walk a clip (the stabilizer, the person masks): the same
 * frames, the same order and the same upright orientation as LumaDecoder's
 * hardware path gives them.
 */
object AvFrames {

    /**
     * Seeks [file]'s [stream] so that [frame] holds the first key frame at or
     * before [targetUs] (container time), decoded by [decoder]; false when
     * nothing could be decoded at all.
     *
     * A seek lands on a key frame at or before the time only in a file with
     * an index. One without - a transport stream, an MPEG program stream, an
     * FLV - is searched by its timestamps and lands wherever its bytes happen
     * to give, half a second past the key frame on the phone's test VOB; every
     * run of a Reverse was then skipped as "after the window" and the render
     * held no frames. So when the first key frame decoded is later than
     * asked, it seeks further back - a second, four, sixteen, then the start
     * of the file - and decodes forward again. A stream that never marks a
     * key frame gives up waiting after [AvFrameSource.MAX_KEY_WAIT] frames and
     * keeps what it has.
     */
    fun seekToKeyFrame(file: AvFile, decoder: AvDecoder, stream: Int, frame: AvDecoder.Frame, targetUs: Long): Boolean {
        val start = file.info.startUs
        var back = 0L
        while (true) {
            val at = (targetUs - back).coerceAtLeast(start)
            AvNative.fileSeekDecoder(file.nativeHandle, decoder.nativeHandle, if (at <= start) 0L else at)
            var waited = 0
            var found = false
            while (true) {
                if (AvNative.fileDecodeNext(file.nativeHandle, decoder.nativeHandle, stream, frame.meta) != AvNative.OK) break
                // From the start of the file the first frame is taken as it is: it is
                // whole, and a decoder that never marks key frames (FFmpeg's
                // Sorenson Spark) would otherwise be waited on to the end - and a
                // rewind to byte zero does not work in every container (FLV).
                if (frame.key || at <= start || ++waited >= AvFrameSource.MAX_KEY_WAIT) {
                    found = true
                    break
                }
            }
            val late = found && frame.ptsUs != Long.MIN_VALUE && frame.ptsUs > targetUs
            if (found && !late) return true
            if (at <= start) return found
            back = if (back == 0L) 1_000_000L else back * 4
        }
    }

    /** The frame in hand, converted on request. */
    class View internal constructor(
        private val decoder: AvDecoder,
        private val stream: AvStream,
        private val frame: AvDecoder.Frame
    ) {
        /** The luma on the analysis grid, upright; [analysisSize] gives the grid for the upright frame. */
        fun grid(analysisSize: (Int, Int) -> Pair<Int, Int>): LumaFrame {
            val turn = ((stream.rotation % 360) + 360) % 360
            val quarter = turn % 180 != 0
            val storedW = shownWidth()
            val storedH = frame.height
            val displayW = if (quarter) storedH else storedW
            val displayH = if (quarter) storedW else storedH
            val (gw, gh) = analysisSize(displayW, displayH)
            // The grid is read unturned at the turned grid's transposed size,
            // then each upright point is mapped back to it, as toGrid maps
            // each grid point back to the stored pixel.
            val uw = if (quarter) gh else gw
            val uh = if (quarter) gw else gh
            val stored = ByteArray(uw * uh)
            decoder.toGray(stored, uw, uh)
            val out = FloatArray(gw * gh)
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val (cx, cy) = when (turn) {
                        90 -> gy to (uh - 1 - gx)
                        180 -> (uw - 1 - gx) to (uh - 1 - gy)
                        270 -> (uw - 1 - gy) to gx
                        else -> gx to gy
                    }
                    out[gy * gw + gx] = (stored[cy.coerceIn(0, uh - 1) * uw + cx.coerceIn(0, uw - 1)].toInt() and 0xFF) / 255f
                }
            }
            return LumaFrame(gw, gh, out)
        }

        /** The frame upright, its longer side [maxSide]. */
        fun bitmap(maxSide: Int): Bitmap? {
            val w0 = shownWidth()
            val h0 = frame.height
            val scale = maxSide.toFloat() / maxOf(w0, h0)
            val w = (w0 * scale).toInt().coerceAtLeast(1)
            val h = (h0 * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (!decoder.toBitmap(bitmap)) {
                bitmap.recycle()
                return null
            }
            if (stream.rotation % 360 == 0) return bitmap
            val turned = Bitmap.createBitmap(bitmap, 0, 0, w, h, Matrix().apply { postRotate(stream.rotation.toFloat()) }, true)
            if (turned !== bitmap) bitmap.recycle()
            return turned
        }

        private fun shownWidth(): Int =
            if (stream.sarNum > 0 && stream.sarDen > 0 && stream.sarNum != stream.sarDen)
                Math.round(frame.width * stream.sarNum.toDouble() / stream.sarDen).toInt()
            else frame.width
    }

    /**
     * Calls [onFrame] with each [wanted] frame from [fromMs] to [toMs], as
     * [convert] makes it; false when FFmpeg could not read the file's picture
     * at all, or no frame fell in the range.
     */
    suspend fun <T> forEach(
        context: Context,
        uri: Uri,
        fromMs: Long,
        toMs: Long,
        wanted: (index: Int, timeMs: Long) -> Boolean,
        convert: (View) -> T?,
        onFrame: suspend (timeMs: Long, frame: T) -> Unit
    ): Boolean {
        val file = AvFile.open(context, uri) ?: return false
        try {
            val stream = file.info.video ?: return false
            val decoder = AvDecoder.forStream(file, stream.index) ?: return false
            decoder.use {
                val start = file.info.startUs
                val frame = AvDecoder.Frame()
                val view = View(decoder, stream, frame)
                var index = 0
                // From the key frame at or before the start (seekToKeyFrame says why that takes care).
                if (!seekToKeyFrame(file, decoder, stream.index, frame, fromMs * 1000L + start)) return false
                var first = true
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!first) {
                        val r = AvNative.fileDecodeNext(file.nativeHandle, decoder.nativeHandle, stream.index, frame.meta)
                        if (r != AvNative.OK) break
                    }
                    first = false
                    if (frame.ptsUs == Long.MIN_VALUE) continue
                    val timeMs = (frame.ptsUs - start) / 1000L
                    if (timeMs > toMs) break
                    if (timeMs < fromMs) continue
                    if (wanted(index, timeMs)) convert(view)?.let { onFrame(timeMs, it) }
                    index++
                }
                return index > 0
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("SquishAv", "reading frames of $uri", t)
            return false
        } finally {
            file.close()
        }
    }
}
