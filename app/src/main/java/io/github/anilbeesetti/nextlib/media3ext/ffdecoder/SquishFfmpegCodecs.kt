@file:androidx.annotation.OptIn(UnstableApi::class)

// This file is Squish's, and it sits in nextlib's package on purpose.
//
// nextlib's two decoders - FfmpegVideoDecoder and FfmpegAudioDecoder, the
// FFmpeg-backed SimpleDecoders its ExoPlayer renderers are built on - are
// package-private, as the Media3 extension they descend from made them. The
// renderers are the only public door, and a renderer is a *player's* decoder:
// it runs at wall-clock speed and drops what it cannot show in time. The
// export wants the decoder itself, fed and drained by Media3's Transformer at
// whatever speed the phone manages. Declaring this file in the same package
// is what Kotlin and ART both accept as "same package" for package-private
// access (one APK, one class loader). Nothing else of the app lives here.
package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.media.MediaCodec
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.transformer.Codec
import androidx.media3.transformer.ExportException
import android.os.Build
import androidx.annotation.RequiresApi
import com.squish.app.media.YuvDataSpace
import com.squish.app.media.YuvImagePainter
import java.nio.ByteBuffer

/**
 * nextlib's FFmpeg decoders wrapped as Transformer [Codec]s, so an export, a
 * proxy or an import conversion can read a file no decoder on the phone
 * takes (SoftwareDecoders.decoderFactory hands these out for exactly those
 * formats, and Media3's own MediaCodec path for everything else).
 *
 * The contract, read in the 1.11.1 bytecode of ExoAssetLoaderBaseRenderer
 * and its two subclasses rather than assumed:
 *
 * - `maybeDequeueInputBuffer(buffer)` sets `buffer.data` to a ByteBuffer the
 *   renderer then *writes the sample into* and flips. The renderer's buffer
 *   is in BUFFER_REPLACEMENT_MODE_DISABLED, so the buffer handed out must be
 *   big enough for any sample or `readSource` throws "Buffer too small".
 *   MediaCodec's input buffers are sized by the format's maxInputSize; so
 *   are these, with a generous floor for containers that do not say.
 * - `queueInputBuffer(buffer)` is the same object back, filled; a flag of
 *   end-of-stream on it is the end.
 * - For audio, `getOutputFormat()` is asked before the first output exists
 *   and must answer null until the decoder knows its channel count and
 *   rate (FFmpeg only does after the first frame); `getOutputBuffer()` and
 *   `getOutputBufferInfo()` describe the current output until
 *   `releaseOutputBuffer(false)` lets it go.
 * - For video, the consumer is made from the *input* format before this
 *   decoder exists, so `getOutputFormat()` is never asked for the frame's
 *   size; `getOutputBufferInfo()` is polled for the next frame's time and
 *   `releaseOutputBuffer(renderTimeUs)` must put that frame on the Surface
 *   at the picture's size and stamped with that time (YuvImagePainter, which
 *   says why that takes API 33), while `releaseOutputBuffer(false)` drops it.
 *
 * **What this path cannot do: hand back the last frames of a stream.**
 * SimpleDecoder never calls `decode` for the end-of-stream buffer and
 * nextlib's JNI has no way to send FFmpeg a flush packet (its
 * ffmpegSendPacket refuses a NULL buffer and FFmpeg refuses an empty one), so
 * the frames FFmpeg is still holding for reordering and for its decoding
 * threads - a few at one thread, more with several - are never drained.
 * nextlib's own playback loses the same frames. ConversionRules says why the
 * app therefore converts such a file whole on import rather than decoding
 * trimmed clips through here at export time: the loss then sits at the very
 * end of the whole file. The thread count below is the trade between
 * decoding speed and how many of those frames go.
 */
object SquishFfmpegCodecs {

    fun audio(format: Format): Codec = FfmpegAudioCodec(format)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun video(format: Format, surface: Surface): Codec = FfmpegVideoCodec(format, surface)

    /**
     * Decoding threads. FFmpeg's frame threading holds about one frame per
     * thread, and every held frame is lost at the end of the stream (see
     * above); four is fast enough for HEVC 1080p on a mid-range phone and
     * costs about a tenth of a second at the end of a file.
     */
    private const val MAX_THREADS = 4

    /** Input and output buffers each: what nextlib's own video renderer uses. */
    private const val VIDEO_BUFFERS = 4

    /** Audio buffers, as nextlib's audio renderer has them. */
    private const val AUDIO_BUFFERS = 16

    /** Frames the painter may have on the Surface before the renderer waits. */
    private const val PENDING_FRAMES = 3

    /** For an audio container that gives no maxInputSize: well over any DTS-HD or FLAC frame. */
    private const val AUDIO_INPUT_FLOOR = 256 * 1024

    /** nextlib's own default for an audio input buffer. */
    private const val AUDIO_INPUT_DEFAULT = 5760

    /** For a video container that gives no maxInputSize: a pixel per byte, at least two megabytes. */
    private const val VIDEO_INPUT_FLOOR = 2 * 1024 * 1024

    private fun failure(cause: Throwable, format: Format, video: Boolean, name: String, init: Boolean): ExportException =
        ExportException.createForCodec(
            cause,
            if (init) ExportException.ERROR_CODE_DECODER_INIT_FAILED else ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.CodecInfo(format.toString(), video, /* isDecoder = */ true, name)
        )

    /**
     * Moves what the renderer wrote into the transformer's buffer onto the
     * decoder's own. Normally they share one ByteBuffer (handed out in
     * maybeDequeueInputBuffer) and the renderer has already flipped it; the
     * copy is for the case where they do not, and the end-of-stream buffer,
     * which carries no data and must reach the decoder empty.
     */
    private fun carryOver(from: DecoderInputBuffer, to: DecoderInputBuffer, format: Format) {
        val data = from.data
        val own = to.data
        when {
            data == null -> own?.let { it.clear(); it.flip() }
            own == null || data !== own -> {
                to.ensureSpaceForWrite(data.remaining())
                to.data!!.put(data)
                to.data!!.flip()
            }
        }
        to.timeUs = from.timeUs
        to.setFlags(0)
        if (from.isEndOfStream) to.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        if (from.isKeyFrame) to.addFlag(C.BUFFER_FLAG_KEY_FRAME)
        to.format = from.format ?: format
    }

    private class FfmpegAudioCodec(private val format: Format) : Codec {
        private val decoder: FfmpegAudioDecoder
        private val inputCapacity: Int
        private var pendingInput: DecoderInputBuffer? = null
        private var current: SimpleDecoderOutputBuffer? = null
        private val info = MediaCodec.BufferInfo()
        private var inputEnded = false
        private var ended = false
        private var outputFormat: Format? = null

        init {
            val initial = if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else AUDIO_INPUT_DEFAULT
            inputCapacity = maxOf(initial, AUDIO_INPUT_FLOOR)
            decoder = try {
                FfmpegAudioDecoder(format, AUDIO_BUFFERS, AUDIO_BUFFERS, initial, /* outputFloat = */ false)
            } catch (e: Exception) {
                throw failure(e, format, video = false, name = "ffmpeg", init = true)
            }
        }

        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, format, video = false, name = decoder.name, init = false)
        }

        override fun getConfigurationFormat(): Format = format
        override fun getName(): String = decoder.name
        // A decoder takes samples, not a surface; DefaultCodec throws here too.
        override fun getInputSurface(): Surface = throw IllegalStateException("a decoder has no input surface")
        override fun getInputFormat(): Format = format

        override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer): Boolean {
            if (inputEnded) return false
            val own = pendingInput ?: guarded { decoder.dequeueInputBuffer() } ?: return false
            pendingInput = own
            own.ensureSpaceForWrite(inputCapacity)
            inputBuffer.data = own.data
            return true
        }

        override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
            val own = checkNotNull(pendingInput) { "queueInputBuffer before maybeDequeueInputBuffer" }
            pendingInput = null
            carryOver(inputBuffer, own, format)
            guarded { decoder.queueInputBuffer(own) }
            if (inputBuffer.isEndOfStream) inputEnded = true
        }

        override fun signalEndOfInputStream() {
            if (inputEnded) return
            val own = pendingInput ?: guarded { decoder.dequeueInputBuffer() } ?: return
            pendingInput = null
            own.data?.let { it.clear(); it.flip() }
            own.setFlags(0)
            own.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            guarded { decoder.queueInputBuffer(own) }
            inputEnded = true
        }

        /** Makes the next decoded output current; false when there is none yet, or ever. */
        private fun advance(): Boolean {
            if (current != null) return true
            if (ended) return false
            while (true) {
                val out = guarded { decoder.dequeueOutputBuffer() } ?: return false
                if (out.isEndOfStream) {
                    out.release()
                    ended = true
                    return false
                }
                val data = out.data
                if (out.shouldBeSkipped || data == null || data.remaining() == 0) {
                    out.release()
                    continue
                }
                current = out
                info.set(0, data.remaining(), out.timeUs, 0)
                return true
            }
        }

        override fun getOutputFormat(): Format? {
            outputFormat?.let { return it }
            advance()
            val channels = decoder.channelCount
            val rate = decoder.sampleRate
            if (channels <= 0 || rate <= 0) return null
            return Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(decoder.encoding)
                .setChannelCount(channels)
                .setSampleRate(rate)
                .build()
                .also { outputFormat = it }
        }

        override fun getOutputBuffer(): ByteBuffer? = if (advance()) current?.data else null

        override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = if (advance()) info else null

        override fun releaseOutputBuffer(render: Boolean) {
            current?.release()
            current = null
        }

        override fun releaseOutputBuffer(renderPresentationTimeUs: Long) = releaseOutputBuffer(true)

        override fun isEnded(): Boolean = ended

        override fun release() {
            current?.release()
            current = null
            decoder.release()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class FfmpegVideoCodec(private val format: Format, surface: Surface) : Codec {
        private val decoder: FfmpegVideoDecoder
        private val painter = YuvImagePainter(surface)
        private val inputCapacity: Int
        private val fullRange = format.colorInfo?.colorRange == C.COLOR_RANGE_FULL
        private val transfer = when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_HLG -> YuvDataSpace.Transfer.HLG
            C.COLOR_TRANSFER_ST2084 -> YuvDataSpace.Transfer.PQ
            else -> YuvDataSpace.Transfer.SDR
        }
        private var pendingInput: DecoderInputBuffer? = null
        private var current: VideoDecoderOutputBuffer? = null
        private val info = MediaCodec.BufferInfo()
        private var inputEnded = false
        private var ended = false

        init {
            val initial = if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize
            else maxOf(VIDEO_INPUT_FLOOR, format.width.coerceAtLeast(0) * format.height.coerceAtLeast(0))
            inputCapacity = initial
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
            // The container's rotation is kept *off* the decoder. Transformer
            // expects decoded frames the way they are stored - MediaCodec
            // hands them over unturned - and turns them itself from the
            // format's rotationDegrees. nextlib's decoder reads the rotation
            // for its own Surface path; given the real value here it would
            // be applied twice, or once in the wrong place.
            val stored = format.buildUpon().setRotationDegrees(0).build()
            decoder = try {
                FfmpegVideoDecoder(VIDEO_BUFFERS, VIDEO_BUFFERS, initial, threads, stored)
            } catch (e: Exception) {
                throw failure(e, format, video = true, name = "ffmpeg", init = true)
            }
            decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV)
        }

        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, format, video = true, name = decoder.name, init = false)
        }

        override fun getConfigurationFormat(): Format = format
        override fun getName(): String = decoder.name
        // A decoder takes samples, not a surface; DefaultCodec throws here too.
        override fun getInputSurface(): Surface = throw IllegalStateException("a decoder has no input surface")
        override fun getInputFormat(): Format = format
        override fun getOutputFormat(): Format = format
        override fun getMaxPendingFrameCount(): Int = PENDING_FRAMES

        override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer): Boolean {
            if (inputEnded) return false
            val own = pendingInput ?: guarded { decoder.dequeueInputBuffer() } ?: return false
            pendingInput = own
            own.ensureSpaceForWrite(inputCapacity)
            inputBuffer.data = own.data
            return true
        }

        override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
            val own = checkNotNull(pendingInput) { "queueInputBuffer before maybeDequeueInputBuffer" }
            pendingInput = null
            carryOver(inputBuffer, own, format)
            guarded { decoder.queueInputBuffer(own) }
            if (inputBuffer.isEndOfStream) inputEnded = true
        }

        override fun signalEndOfInputStream() {
            if (inputEnded) return
            val own = pendingInput ?: guarded { decoder.dequeueInputBuffer() } ?: return
            pendingInput = null
            own.data?.let { it.clear(); it.flip() }
            own.setFlags(0)
            own.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            guarded { decoder.queueInputBuffer(own) }
            inputEnded = true
        }

        private fun advance(): Boolean {
            if (current != null) return true
            if (ended) return false
            while (true) {
                val out = guarded { decoder.dequeueOutputBuffer() } ?: return false
                if (out.isEndOfStream) {
                    out.release()
                    ended = true
                    return false
                }
                if (out.shouldBeSkipped || out.mode != C.VIDEO_OUTPUT_MODE_YUV || out.yuvPlanes == null || out.yuvStrides == null) {
                    out.release()
                    continue
                }
                current = out
                info.set(0, 0, out.timeUs, 0)
                return true
            }
        }

        override fun getOutputBuffer(): ByteBuffer? = null

        override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = if (advance()) info else null

        override fun releaseOutputBuffer(render: Boolean) {
            val out = current ?: return
            if (render) paint(out, out.timeUs)
            out.release()
            current = null
        }

        override fun releaseOutputBuffer(renderPresentationTimeUs: Long) {
            val out = current ?: return
            paint(out, renderPresentationTimeUs)
            out.release()
            current = null
        }

        private fun paint(out: VideoDecoderOutputBuffer, timeUs: Long) {
            try {
                painter.paint(
                    out.yuvPlanes!!, out.yuvStrides!!, out.width, out.height,
                    YuvDataSpace.choose(out.colorspace, out.height, transfer, fullRange), timeUs
                )
            } catch (e: Exception) {
                throw failure(e, format, video = true, name = decoder.name, init = false)
            }
        }

        override fun isEnded(): Boolean = ended

        override fun release() {
            current?.release()
            current = null
            runCatching { painter.release() }
            decoder.release()
        }
    }
}
