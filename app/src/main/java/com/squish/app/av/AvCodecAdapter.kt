@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.av

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PersistableBundle
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import com.squish.app.media.YuvImagePainter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Squish's FFmpeg decoder dressed as a MediaCodec, for ExoPlayer's own
 * MediaCodec renderers.
 *
 * Why not a renderer of its own, as nextlib had: a decoder renderer
 * (DecoderVideoRenderer) draws straight to the player's surface and ignores
 * the player's video effects, so a clip decoded in software showed with no
 * grade, no mask, no key, no transition in the preview - which is why such
 * files used to be converted on import. Behind MediaCodecVideoRenderer the
 * frames go wherever a hardware decoder's would: into the effects pipeline's
 * input surface when there is one, onto the view when there is not. Sound
 * goes through MediaCodecAudioRenderer and so through every audio processor
 * the app puts in the sink.
 *
 * Behaves like a MediaCodec in synchronous mode: input buffers are handed
 * out by index and come back filled; a worker thread feeds FFmpeg and
 * collects what it gives back; output buffers are handed out by index in
 * order, with INFO_OUTPUT_FORMAT_CHANGED ahead of the first and of any
 * change. A picture is held as a reference to FFmpeg's own frame until the
 * renderer releases it, and is written into the surface's image only then -
 * one conversion, no copy of our own. A flush throws everything in flight
 * away, as MediaCodec's does.
 */
class AvCodecAdapter(configuration: MediaCodecAdapter.Configuration) : MediaCodecAdapter {

    private val format: Format = configuration.format
    private val video = MimeTypes.isVideo(format.sampleMimeType)
    private val decoder: AvDecoder
    private var surface: Surface? = configuration.surface
    private var painter: YuvImagePainter? = null
    private var frameListener: MediaCodecAdapter.OnFrameRenderedListener? = null
    private var frameListenerHandler: Handler? = null

    private class Input(val buffer: ByteBuffer) {
        var size = 0
        var timeUs = 0L
        var flags = 0
    }

    private class Output {
        var held = 0L                    // a taken FFmpeg frame, for a picture
        var pcm: ByteBuffer? = null      // the samples, for sound
        val info = MediaCodec.BufferInfo()
        val frame = AvDecoder.Frame()    // the picture's own description
    }

    /** A decision in the output queue: a buffer by index, or a change of format before the next. */
    private sealed class Ready {
        class Buffer(val index: Int) : Ready()
        class FormatChange(val mediaFormat: MediaFormat) : Ready()
    }

    private val lock = ReentrantLock()
    private val inputFree = lock.newCondition()
    private val inputQueued = lock.newCondition()
    private val outputFree = lock.newCondition()

    private val inputs: Array<Input>
    private val freeInputs = ArrayDeque<Int>()
    private val queuedInputs = ArrayDeque<Int>()
    private val outputs = Array(if (video) VIDEO_OUTPUTS else AUDIO_OUTPUTS) { Output() }
    private val freeOutputs = ArrayDeque<Int>()
    // The last slot is kept back for the end of the stream: MediaCodecRenderer
    // processes an empty end-of-stream buffer without ever releasing it.
    private val eosIndex = outputs.size - 1
    private val paintLock = Any()
    private val ready = ArrayDeque<Ready>()
    private var currentFormat: MediaFormat = initialFormat()
    private var announced: String? = null
    /** Bumped by every flush; work begun under an older one is thrown away. */
    @Volatile private var generation = 0
    @Volatile private var released = false
    @Volatile private var failure: Exception? = null
    /** Held by the worker for each packet it feeds and drains; a flush takes it to know FFmpeg is idle. */
    private val decoderLock = ReentrantLock()
    private val worker: Thread
    private val workFrame = AvDecoder.Frame()

    init {
        val params = AvCodecs.paramsFor(format) ?: throw IOException("FFmpeg has no decoder for ${format.sampleMimeType} ${format.codecs}")
        decoder = AvDecoder.open(params, if (video) AvDecoder.defaultThreads() else 1)
            ?: throw IOException("FFmpeg did not open a decoder for ${format.codecs ?: format.sampleMimeType}")
        val capacity = maxOf(
            AvCodecs.inputCapacity(format),
            if (configuration.mediaFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                configuration.mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        )
        inputs = Array(if (video) VIDEO_INPUTS else AUDIO_INPUTS) {
            Input(ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder()))
        }
        inputs.indices.forEach { freeInputs.add(it) }
        (0 until eosIndex).forEach { freeOutputs.add(it) }
        worker = Thread({ work() }, "SquishAv-" + (if (video) "video" else "audio"))
        worker.start()
        android.util.Log.i("SquishAv", "decoding ${format.codecs ?: format.sampleMimeType} with FFmpeg ${decoder.name}")
    }

    private fun initialFormat(): MediaFormat = if (video) {
        MediaFormat.createVideoFormat(format.sampleMimeType!!, format.width.coerceAtLeast(1), format.height.coerceAtLeast(1))
    } else {
        MediaFormat.createAudioFormat(MimeTypes.AUDIO_RAW, format.sampleRate.coerceAtLeast(8000), format.channelCount.coerceAtLeast(1)).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        }
    }

    private fun rethrow() {
        failure?.let { throw IllegalStateException("FFmpeg decoding failed", it) }
    }

    override fun dequeueInputBufferIndex(): Int = lock.withLock {
        rethrow()
        freeInputs.pollFirst() ?: MediaCodec.INFO_TRY_AGAIN_LATER
    }

    override fun getInputBuffer(index: Int): ByteBuffer = inputs[index].buffer.also { it.clear() }

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        lock.withLock {
            val input = inputs[index]
            if (offset != 0) {
                // MediaCodecRenderer always writes from zero; this is for a caller that does not.
                val b = input.buffer.duplicate()
                b.position(offset)
                b.limit(offset + size)
                input.buffer.clear()
                input.buffer.put(b)
            }
            input.size = size
            input.timeUs = presentationTimeUs
            input.flags = flags
            queuedInputs.addLast(index)
            inputQueued.signalAll()
        }
    }

    override fun queueSecureInputBuffer(index: Int, offset: Int, info: CryptoInfo, presentationTimeUs: Long, flags: Int) {
        throw UnsupportedOperationException("FFmpeg does not decode protected content")
    }

    override fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int = lock.withLock {
        rethrow()
        when (val next = ready.pollFirst()) {
            null -> MediaCodec.INFO_TRY_AGAIN_LATER
            is Ready.FormatChange -> {
                currentFormat = next.mediaFormat
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            is Ready.Buffer -> {
                val info = outputs[next.index].info
                bufferInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
                next.index
            }
        }
    }

    override fun getOutputFormat(): MediaFormat = lock.withLock { currentFormat }

    override fun getOutputBuffer(index: Int): ByteBuffer? = lock.withLock { outputs[index].pcm }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        val out = outputs[index]
        if (render && video) paint(out, out.info.presentationTimeUs * 1000L)
        free(index)
    }

    override fun releaseOutputBuffer(index: Int, renderTimeStampNs: Long) {
        val out = outputs[index]
        if (video) paint(out, renderTimeStampNs)
        free(index)
    }

    private fun paint(out: Output, timestampNs: Long) {
        if (out.held == 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val target = surface ?: return
        paintOn(target, out, timestampNs)
        val listener = frameListener ?: return
        val pts = out.info.presentationTimeUs
        val now = System.nanoTime()
        val handler = frameListenerHandler
        if (handler != null) handler.post { listener.onFrameRendered(this, pts, now) }
        else listener.onFrameRendered(this, pts, now)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun paintOn(target: Surface, out: Output, timestampNs: Long) {
        try {
            synchronized(paintLock) {
                // As many images as a hardware decoder gives this surface (the
                // moto's "max dequeue count 11"). Media3's effects input holds
                // several frames before it shows the first; with the three a
                // Transformer is content with, the writer waited for a free image
                // that the pipeline was waiting on frames to give back, and the
                // preview sat black with four frames painted - every file FFmpeg
                // decoded, on the phone, 18 October.
                val p = painter ?: YuvImagePainter(target, PLAYER_IMAGES).also { painter = it }
                AvCodecs.paint(p, decoder, out.held, out.frame, format, timestampNs)
            }
        } catch (e: Exception) {
            // A surface that has gone away under the frame - the view torn
            // down mid-render - loses that frame, not the playback.
            android.util.Log.w("SquishAv", "a frame did not reach the surface", e)
        }
    }

    private fun free(index: Int) = lock.withLock {
        val out = outputs[index]
        AvDecoder.freeFrame(out.held)
        out.held = 0L
        if (index != eosIndex && !freeOutputs.contains(index)) freeOutputs.addLast(index)
        outputFree.signalAll()
    }

    override fun flush() {
        lock.withLock {
            generation++
            inputFree.signalAll()
            inputQueued.signalAll()
            outputFree.signalAll()
        }
        decoderLock.withLock {
            lock.withLock {
                runCatching { decoder.flush() }
                queuedInputs.clear()
                freeInputs.clear()
                inputs.indices.forEach { freeInputs.add(it) }
                ready.clear()
                for (i in outputs.indices) {
                    AvDecoder.freeFrame(outputs[i].held)
                    outputs[i].held = 0L
                }
                freeOutputs.clear()
                (0 until eosIndex).forEach { freeOutputs.add(it) }
                // The format is said again after a flush, as a fresh decoder would.
                announced = null
            }
        }
    }

    override fun release() {
        lock.withLock {
            released = true
            generation++
            inputQueued.signalAll()
            outputFree.signalAll()
        }
        worker.interrupt()
        runCatching { worker.join(2_000) }
        decoderLock.withLock {
            lock.withLock {
                for (o in outputs) {
                    AvDecoder.freeFrame(o.held)
                    o.held = 0L
                }
            }
            decoder.close()
        }
        painter?.release()
        painter = null
    }

    override fun setOnFrameRenderedListener(listener: MediaCodecAdapter.OnFrameRenderedListener, handler: Handler) {
        frameListener = listener
        frameListenerHandler = handler
    }

    override fun setOutputSurface(surface: Surface) {
        if (surface === this.surface) return
        painter?.release()
        painter = null
        this.surface = surface
    }

    override fun detachOutputSurface() {
        painter?.release()
        painter = null
        surface = null
    }

    override fun setParameters(params: Bundle) = Unit
    override fun setVideoScalingMode(scalingMode: Int) = Unit
    override fun needsReconfiguration(): Boolean = false
    override fun getMetrics(): PersistableBundle = PersistableBundle()
    override fun subscribeToVendorParameters(names: MutableList<String>) = Unit
    override fun unsubscribeFromVendorParameters(names: MutableList<String>) = Unit

    // ---- the worker -------------------------------------------------------

    private fun work() {
        try {
            while (true) {
                val index: Int
                val gen: Int
                lock.withLock {
                    while (!released && queuedInputs.isEmpty()) inputQueued.await()
                    if (released) return
                    index = queuedInputs.pollFirst()!!
                    gen = generation
                }
                val input = inputs[index]
                decoderLock.withLock {
                    if (gen == generation) feed(input, gen)
                }
                lock.withLock {
                    if (gen == generation && !freeInputs.contains(index)) freeInputs.addLast(index)
                    inputFree.signalAll()
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!released) {
                android.util.Log.w("SquishAv", "FFmpeg ${decoder.name} failed", e)
                failure = e
            }
        }
    }

    private fun feed(input: Input, gen: Int) {
        if (input.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            // The sample an end-of-stream buffer may carry goes in first.
            if (input.size > 0) send(input, gen)
            decoder.drain()
            collect(gen)
            emitEndOfStream(gen)
            return
        }
        if (input.size <= 0) return
        send(input, gen)
        collect(gen)
    }

    private fun send(input: Input, gen: Int) {
        val key = input.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        var r = decoder.send(input.buffer, 0, input.size, input.timeUs, key)
        while (r == AvNative.AGAIN && gen == generation) {
            collect(gen)
            r = decoder.send(input.buffer, 0, input.size, input.timeUs, key)
        }
    }

    /** Takes every frame FFmpeg has ready and queues it as an output. */
    private fun collect(gen: Int) {
        while (gen == generation) {
            val r = decoder.receive(workFrame)
            if (r != AvNative.OK) return
            val slot = takeOutput(gen) ?: return
            val out = outputs[slot]
            if (!fill(out)) {
                lock.withLock { freeOutputs.addLast(slot) }
                continue
            }
            lock.withLock {
                if (gen != generation) {
                    AvDecoder.freeFrame(out.held)
                    out.held = 0L
                    freeOutputs.addLast(slot)
                    return
                }
                announce(out)
                ready.addLast(Ready.Buffer(slot))
            }
        }
    }

    /** A free output slot, waiting for the renderer to give one back; null when a flush came first. */
    private fun takeOutput(gen: Int): Int? = lock.withLock {
        while (freeOutputs.isEmpty() && gen == generation && !released) outputFree.await()
        if (gen != generation || released) null else freeOutputs.pollFirst()
    }

    /** The frame just received into [out]; false for one with nothing in it. */
    private fun fill(out: Output): Boolean {
        val pts = if (workFrame.ptsUs == Long.MIN_VALUE) 0L else workFrame.ptsUs
        System.arraycopy(workFrame.meta, 0, out.frame.meta, 0, workFrame.meta.size)
        if (video) {
            if (workFrame.width <= 0 || workFrame.height <= 0) return false
            out.held = decoder.takeFrame()
            if (out.held == 0L) return false
            out.info.set(0, workFrame.width * workFrame.height, pts, 0)
            return true
        }
        val need = workFrame.samples * workFrame.outChannels.coerceAtLeast(1) * 2 + 4096
        val pcm = out.pcm?.takeIf { it.capacity() >= need }
            ?: ByteBuffer.allocateDirect(need * 2).order(ByteOrder.nativeOrder()).also { out.pcm = it }
        pcm.clear()
        val bytes = decoder.toPcm16(pcm, 0, pcm.capacity())
        if (bytes <= 0) return false
        pcm.position(0)
        pcm.limit(bytes)
        out.info.set(0, bytes, pts, 0)
        return true
    }

    /** Queues a change of output format ahead of [out] when it differs from the last said. */
    private fun announce(out: Output) {
        val f = out.frame
        val key = if (video) "${f.width}x${f.height}" else "${f.sampleRate}/${f.outChannels}"
        if (key == announced) return
        announced = key
        val mediaFormat = if (video) {
            MediaFormat.createVideoFormat(format.sampleMimeType!!, f.width, f.height)
        } else {
            MediaFormat.createAudioFormat(MimeTypes.AUDIO_RAW, f.sampleRate, f.outChannels).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            }
        }
        ready.addLast(Ready.FormatChange(mediaFormat))
    }

    /** The end of the stream as MediaCodec says it: an empty buffer with the flag, never released by the renderer. */
    private fun emitEndOfStream(gen: Int) {
        lock.withLock {
            if (gen != generation) return
            outputs[eosIndex].info.set(0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            ready.addLast(Ready.Buffer(eosIndex))
        }
    }

    /** Hands out [AvCodecAdapter]s for our decoders and the delegate's for every other. */
    class Factory(private val delegate: MediaCodecAdapter.Factory) : MediaCodecAdapter.Factory {
        override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter =
            if (AvCodecs.isOurs(configuration.codecInfo)) AvCodecAdapter(configuration)
            else delegate.createAdapter(configuration)
    }

    private companion object {
        /** Images the writer may have out at once on a player's surface (see paintOn). */
        const val PLAYER_IMAGES = 10
        const val VIDEO_INPUTS = 4
        const val AUDIO_INPUTS = 8
        /** Frames held for the renderer; one more is kept back for the end of stream. */
        const val VIDEO_OUTPUTS = 6
        const val AUDIO_OUTPUTS = 12
    }
}
