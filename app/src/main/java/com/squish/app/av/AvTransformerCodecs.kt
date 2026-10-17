@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.av

import android.media.MediaCodec
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import androidx.media3.transformer.ExportException
import com.squish.app.media.YuvImagePainter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Squish's FFmpeg decoders as Transformer [Codec]s: the export, the proxy,
 * the stills and every other Transformer read a file no decoder on the phone
 * takes through these (SoftwareDecoders.decoderFactory hands them out).
 *
 * The contract, read in the 1.11.1 bytecode of ExoAssetLoaderBaseRenderer
 * (the notes nextlib's version of this file kept, which still hold):
 * `maybeDequeueInputBuffer` hands the renderer a ByteBuffer it writes the
 * sample into, big enough for any sample; `queueInputBuffer` gives it back;
 * for sound, `getOutputFormat` answers null until the first frame says the
 * rate and channels; for pictures, `releaseOutputBuffer(renderTimeUs)` puts
 * the frame on the surface at the picture's own size, stamped with that
 * time (YuvImagePainter, API 33).
 *
 * Decoding happens inline, on Transformer's own thread - nothing here races
 * a clock. And unlike nextlib's, the end of the stream drains FFmpeg, so the
 * last frames of a file - and of every trimmed clip of it - come out.
 */
object AvTransformerCodecs {

    fun audio(format: Format): Codec = AudioCodec(format)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun video(format: Format, surface: Surface): Codec = VideoCodec(format, surface)

    /** Pictures the painter may have on the surface before the renderer waits. */
    private const val PENDING_FRAMES = 3

    /** Frames held back from the renderer at most; FFmpeg's threads hold a few more. */
    private const val HELD_FRAMES = 4

    private fun failure(cause: Throwable, format: Format, video: Boolean, name: String, init: Boolean): ExportException =
        ExportException.createForCodec(
            cause,
            if (init) ExportException.ERROR_CODE_DECODER_INIT_FAILED else ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.CodecInfo(format.toString(), video, /* isDecoder = */ true, name)
        )

    private fun open(format: Format, video: Boolean): AvDecoder {
        val params = AvCodecs.paramsFor(format)
            ?: throw failure(IllegalArgumentException("FFmpeg has no decoder for $format"), format, video, "ffmpeg", init = true)
        return AvDecoder.open(params, if (video) AvDecoder.defaultThreads() else 1)
            ?: throw failure(IllegalStateException("FFmpeg did not open a decoder"), format, video, "ffmpeg", init = true)
    }

    /** What both codecs share: the one input buffer, fed to FFmpeg as it comes back. */
    private abstract class Base(protected val format: Format, protected val video: Boolean) : Codec {
        protected val decoder: AvDecoder = open(format, video)
        private val input = ByteBuffer.allocateDirect(AvCodecs.inputCapacity(format)).order(ByteOrder.nativeOrder())
        private var inputOut = false
        protected var inputEnded = false
        protected var ended = false
        protected val frame = AvDecoder.Frame()

        protected fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw failure(e, format, video, decoder.name, init = false)
        }

        override fun getConfigurationFormat(): Format = format
        override fun getName(): String = decoder.name
        override fun getInputSurface(): Surface = throw IllegalStateException("a decoder has no input surface")
        override fun getInputFormat(): Format = format

        /** Whether the decoder can take another packet now; collects what it has when it cannot. */
        protected abstract fun hasRoom(): Boolean

        /** Takes every frame FFmpeg has ready (into the codec's own queue). */
        protected abstract fun collect()

        override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer): Boolean {
            if (inputEnded || inputOut) return inputOut.also { if (it) inputBuffer.data = input }
            if (!hasRoom()) return false
            input.clear()
            inputBuffer.data = input
            inputOut = true
            return true
        }

        override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
            inputOut = false
            val data = inputBuffer.data
            val packet: ByteBuffer
            val size: Int
            if (data == null) {
                packet = input
                size = 0
            } else if (data === input) {
                packet = input
                size = input.remaining()
            } else {
                // A renderer that wrote somewhere else: copy it over.
                input.clear()
                input.put(data)
                input.flip()
                packet = input
                size = input.remaining()
            }
            val start = packet.position()
            guarded {
                if (size > 0) {
                    var r = decoder.send(packet, start, size, inputBuffer.timeUs, inputBuffer.isKeyFrame)
                    while (r == AvNative.AGAIN) {
                        collect()
                        r = decoder.send(packet, start, size, inputBuffer.timeUs, inputBuffer.isKeyFrame)
                    }
                }
                if (inputBuffer.isEndOfStream) {
                    decoder.drain()
                    inputEnded = true
                }
                collect()
            }
        }

        override fun signalEndOfInputStream() {
            if (inputEnded) return
            guarded {
                decoder.drain()
                collect()
            }
            inputEnded = true
        }

        override fun isEnded(): Boolean = ended
    }

    private class AudioCodec(format: Format) : Base(format, video = false) {
        private class Pcm(val data: ByteBuffer, val timeUs: Long)

        private val queue = ArrayDeque<Pcm>()
        private val info = MediaCodec.BufferInfo()
        private var outputFormat: Format? = null
        private var sawEnd = false
        private var channels = 0
        private var rate = 0

        override fun hasRoom(): Boolean = queue.size < 32

        override fun collect() {
            while (true) {
                val r = decoder.receive(frame)
                if (r == AvNative.EOF) {
                    sawEnd = true
                    return
                }
                if (r != AvNative.OK) return
                if (frame.samples <= 0) continue
                if (channels == 0) {
                    channels = frame.outChannels.coerceAtLeast(1)
                    rate = frame.sampleRate
                }
                val need = frame.samples * channels * 2 + 4096
                val buffer = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
                val bytes = decoder.toPcm16(buffer, 0, need)
                if (bytes <= 0) continue
                buffer.limit(bytes)
                queue.addLast(Pcm(buffer, if (frame.ptsUs == Long.MIN_VALUE) 0L else frame.ptsUs))
            }
        }

        override fun getOutputFormat(): Format? {
            outputFormat?.let { return it }
            if (channels <= 0 || rate <= 0) {
                if (queue.isEmpty() && !inputEnded) return null
                if (channels <= 0) return null
            }
            return Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(channels)
                .setSampleRate(rate)
                .build()
                .also { outputFormat = it }
        }

        override fun getOutputBuffer(): ByteBuffer? = queue.firstOrNull()?.data.also { refreshEnd() }

        override fun getOutputBufferInfo(): MediaCodec.BufferInfo? {
            val head = queue.firstOrNull() ?: return null.also { refreshEnd() }
            info.set(0, head.data.remaining(), head.timeUs, 0)
            return info
        }

        private fun refreshEnd() {
            if (queue.isEmpty() && sawEnd && inputEnded) ended = true
        }

        override fun releaseOutputBuffer(render: Boolean) {
            queue.removeFirstOrNull()
            refreshEnd()
        }

        override fun releaseOutputBuffer(renderPresentationTimeUs: Long) = releaseOutputBuffer(true)

        override fun release() {
            queue.clear()
            decoder.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class VideoCodec(format: Format, surface: Surface) : Base(format, video = true) {
        private class Held(val handle: Long, val meta: AvDecoder.Frame)

        private val painter = YuvImagePainter(surface)
        private val queue = ArrayDeque<Held>()
        private val info = MediaCodec.BufferInfo()
        private var sawEnd = false

        override fun getOutputFormat(): Format = format
        override fun getMaxPendingFrameCount(): Int = PENDING_FRAMES
        override fun getOutputBuffer(): ByteBuffer? = null

        override fun hasRoom(): Boolean = queue.size < HELD_FRAMES

        override fun collect() {
            while (true) {
                val r = decoder.receive(frame)
                if (r == AvNative.EOF) {
                    sawEnd = true
                    return
                }
                if (r != AvNative.OK) return
                if (frame.width <= 0 || frame.height <= 0) continue
                val copy = AvDecoder.Frame()
                System.arraycopy(frame.meta, 0, copy.meta, 0, frame.meta.size)
                val handle = decoder.takeFrame()
                if (handle != 0L) queue.addLast(Held(handle, copy))
            }
        }

        override fun getOutputBufferInfo(): MediaCodec.BufferInfo? {
            val head = queue.firstOrNull()
            if (head == null) {
                if (sawEnd && inputEnded) ended = true
                return null
            }
            val pts = if (head.meta.ptsUs == Long.MIN_VALUE) 0L else head.meta.ptsUs
            info.set(0, 0, pts, 0)
            return info
        }

        override fun releaseOutputBuffer(render: Boolean) {
            val head = queue.removeFirstOrNull() ?: return
            try {
                if (render) paint(head, head.meta.ptsUs)
            } finally {
                AvDecoder.freeFrame(head.handle)
            }
            if (queue.isEmpty() && sawEnd && inputEnded) ended = true
        }

        override fun releaseOutputBuffer(renderPresentationTimeUs: Long) {
            val head = queue.removeFirstOrNull() ?: return
            try {
                paint(head, renderPresentationTimeUs)
            } finally {
                AvDecoder.freeFrame(head.handle)
            }
            if (queue.isEmpty() && sawEnd && inputEnded) ended = true
        }

        private fun paint(held: Held, timeUs: Long) = guarded {
            AvCodecs.paint(painter, decoder, held.handle, held.meta, format, timeUs * 1000L)
        }

        override fun release() {
            while (queue.isNotEmpty()) AvDecoder.freeFrame(queue.removeFirst().handle)
            runCatching { painter.release() }
            decoder.close()
        }
    }
}
