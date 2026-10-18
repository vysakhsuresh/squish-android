package com.squish.app.media.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class MonoPcm(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate <= 0) 0L else samples.size * 1000L / sampleRate
}

/**
 * Decodes the audio track of any media file to mono float PCM, decimated to a low
 * analysis rate. Pure Android framework (MediaExtractor + MediaCodec) - no Media3,
 * no native libs, nothing to go wrong at build time.
 *
 * This is what powers both the waveform lanes and automatic A/V sync detection.
 */
object PcmDecoder {

    suspend fun decodeMono(
        context: Context,
        uri: Uri,
        targetSampleRate: Int = 8_000,
        maxDurationMs: Long = 60_000L
    ): MonoPcm? = withContext(Dispatchers.IO) {
        var out = FloatArray(0)
        var written = 0
        var stride = 1
        var analysisRate = 0
        var limit = 0
        var framesSeen = 0L
        val ok = decodeFrames(context, uri, onFormat = { sourceRate ->
            stride = max(1, sourceRate / targetSampleRate)
            analysisRate = sourceRate / stride
            limit = ((maxDurationMs / 1000.0) * analysisRate).toInt().coerceAtLeast(1024)
            out = FloatArray(minOf(limit, 1 shl 20))
        }) { sample ->
            if (framesSeen % stride == 0L) {
                if (written == out.size) out = out.copyOf(minOf(out.size * 2, limit))
                if (written < out.size) out[written++] = sample
            }
            framesSeen++
            written < limit
        }
        // The final copy is the largest single allocation here; on a long file it
        // can be the one the heap refuses. No sound to work with, not a crash.
        if (!ok || written == 0) null else runCatching { MonoPcm(out.copyOf(written), analysisRate) }.getOrNull()
    }

    /**
     * The loudest sample in each [bucketMs] of the file, 0..1 after
     * normalising, for as much of it as [maxDurationMs] covers - the whole of
     * any song. Read straight off the decoder into one float per bucket, so an
     * hour of audio costs a few hundred kilobytes rather than the samples
     * themselves: the waveform used to be built from a ten-minute decode held
     * in memory, and a longer file's strip went flat past the tenth minute.
     */
    suspend fun decodePeaks(
        context: Context,
        uri: Uri,
        bucketMs: Int = WaveformBuilder.BUCKET_MS,
        maxDurationMs: Long = 60 * 60_000L
    ): Waveform? = withContext(Dispatchers.IO) {
        // From disk when this file was drawn before: every project open used
        // to decode every one of its songs again, one after another, an
        // hour's song taking the time an hour's decode takes.
        val key = WaveformCache.key(context, uri, bucketMs, maxDurationMs)
        key?.let { WaveformCache.read(context, it) }?.let { return@withContext it }
        decodePeaksUncached(context, uri, bucketMs, maxDurationMs)?.also { wave ->
            key?.let { WaveformCache.write(context, it, wave) }
        }
    }

    private suspend fun decodePeaksUncached(
        context: Context,
        uri: Uri,
        bucketMs: Int,
        maxDurationMs: Long
    ): Waveform? = withContext(Dispatchers.IO) {
        var rate = 1
        var perBucket = 1L
        var peaks = FloatArray(0)
        var buckets = 0
        var limitFrames = 0L
        var frames = 0L
        var peak = 0f
        val ok = decodeFrames(context, uri, onFormat = { sourceRate ->
            rate = sourceRate.coerceAtLeast(1)
            perBucket = (rate.toLong() * bucketMs / 1000L).coerceAtLeast(1L)
            limitFrames = rate.toLong() * maxDurationMs / 1000L
            peaks = FloatArray(1024)
        }) { sample ->
            val v = abs(sample)
            if (v > peak) peak = v
            frames++
            if (frames % perBucket == 0L) {
                if (buckets == peaks.size) peaks = peaks.copyOf(peaks.size * 2)
                peaks[buckets++] = peak
                peak = 0f
            }
            frames < limitFrames
        }
        if (!ok || frames == 0L) return@withContext null
        // The last, partial bucket counts: a sound that ends on a hit should show it.
        if (frames % perBucket != 0L) {
            if (buckets == peaks.size) peaks = peaks.copyOf(peaks.size + 1)
            peaks[buckets++] = peak
        }
        WaveformBuilder.fromPeaks(peaks.copyOf(buckets), frames * 1000L / rate)
    }

    /**
     * Runs the decoder over the file's first audio track, handing every frame -
     * the channels averaged to one float - to [onFrame] until it returns false
     * or the file ends. [onFormat] gets the *decoder's* sample rate, once,
     * before the first frame - not the container's, which is a different number
     * on HE-AAC. False when there is no readable audio track at all.
     *
     * Suspend, and it checks for cancellation on every pass of the codec loop:
     * a waveform read is up to an hour of audio, and a sound selected and
     * deselected again used to leave that decode running to the end of the
     * file on the IO pool with nobody waiting for it. Several of them at once
     * (a strip of sounds scrolled past) starved everything else there.
     */
    private suspend fun decodeFrames(
        context: Context,
        uri: Uri,
        onFormat: (sampleRate: Int) -> Unit,
        onFrame: (Float) -> Boolean
    ): Boolean {
        // Squish's FFmpeg first for a file only it reads or whose sound only it
        // decodes - a WMA, a DTS track, an APE - and after the phone's decoder
        // for anything the phone's path gives no sound from. Never after sound
        // has been handed on: the callers would hear the start twice.
        //
        // And FFmpeg first for every file when it is here at all: this walk
        // wants PCM as fast as it comes, not a picture on time, and MediaCodec
        // hands it over a buffer at a time through the media server - 49
        // seconds of WAV took 614 ms to draw on the moto that way.
        if (com.squish.app.av.AvNative.loaded) {
            var deliveredByFfmpeg = false
            val ok = decodeWithFfmpeg(context, uri, onFormat) { sample -> deliveredByFfmpeg = true; onFrame(sample) }
            if (ok || deliveredByFfmpeg) return true
        }
        val report = com.squish.app.media.MediaCompat.cached(uri)
        if (com.squish.app.av.AvRoute.decide(context, uri).ffmpeg || report?.audioSoftware == true || report?.audioProblem != null) {
            return false
        }
        // The phone's decoder for what FFmpeg gave nothing from (or when the
        // library is not on this phone).
        return decodeWithPlatform(context, uri, onFormat, onFrame)
    }

    /** The same walk through Squish's FFmpeg: 16-bit PCM off the decoder, the channels averaged. */
    private suspend fun decodeWithFfmpeg(
        context: Context,
        uri: Uri,
        onFormat: (sampleRate: Int) -> Unit,
        onFrame: (Float) -> Boolean
    ): Boolean {
        val file = com.squish.app.av.AvFile.open(context, uri) ?: return false
        try {
            val stream = file.info.audio ?: return false
            val decoder = com.squish.app.av.AvDecoder.forStream(file, stream.index, threads = 1) ?: return false
            decoder.use {
                val frame = com.squish.app.av.AvDecoder.Frame()
                var pcm = java.nio.ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.nativeOrder())
                var announced = false
                var delivered = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val r = com.squish.app.av.AvNative.fileDecodeNext(file.nativeHandle, decoder.nativeHandle, stream.index, frame.meta)
                    if (r != com.squish.app.av.AvNative.OK) break
                    val channels = frame.outChannels.coerceAtLeast(1)
                    val need = frame.samples * channels * 2 + 4096
                    if (pcm.capacity() < need) pcm = java.nio.ByteBuffer.allocateDirect(need * 2).order(ByteOrder.nativeOrder())
                    val bytes = decoder.toPcm16(pcm, 0, pcm.capacity())
                    if (bytes <= 0) continue
                    if (!announced) {
                        announced = true
                        onFormat(frame.sampleRate.coerceAtLeast(1))
                    }
                    pcm.position(0)
                    pcm.limit(bytes)
                    val shorts = pcm.asShortBuffer()
                    while (shorts.remaining() >= channels) {
                        var sum = 0f
                        for (c in 0 until channels) sum += shorts.get() / 32768f
                        delivered = true
                        if (!onFrame(sum / channels)) return true
                    }
                }
                return delivered
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("SquishAv", "reading the sound of $uri", t)
            return false
        } finally {
            file.close()
        }
    }

    private suspend fun decodeWithPlatform(
        context: Context,
        uri: Uri,
        onFormat: (sampleRate: Int) -> Unit,
        onFrame: (Float) -> Boolean
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            // Seeded from the container, corrected by the decoder below. These
            // are not the same number for every file: HE-AAC's SBR doubles the
            // output rate over the one the esds signals, and HE-AACv2's
            // parametric stereo decodes a mono-signalled stream to two
            // channels. Taking the container's word for it made every
            // millisecond this layer reports wrong by that factor - the strip's
            // waveform drawn against a length twice or four times the file's,
            // the beat detector handed audio that slow, the sync offset scaled -
            // and read interleaved channels as consecutive mono frames.
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1
            var floatPcm = false
            // So onFormat waits for the first output buffer rather than firing
            // here: the callers fix their stride, their bucket and their output
            // array inside it, and the decoder's own format has not been said
            // yet. MediaCodec delivers INFO_OUTPUT_FORMAT_CHANGED before the
            // first buffer, so by the time this fires the numbers are the real
            // ones. (ReverseRenderer has always done this; the two readers of
            // one file disagreed about what a frame is.)
            var announced = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var delivered = false

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                // Every free input buffer filled without waiting, then the
                // output asked for without waiting if anything went in. One
                // buffer each way with a 10 ms wait on both was up to 20 ms a
                // pass of nothing but waiting, which on a long song was most
                // of the time the waveform took to appear.
                var fed = false
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val inBuf = codec.getInputBuffer(inIndex)
                    val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    fed = true
                }

                val outIndex = codec.dequeueOutputBuffer(info, if (fed) 0L else 10_000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                    }
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            if (!announced) {
                                announced = true
                                onFormat(rate)
                            }
                            // 16-bit unless the decoder said otherwise. Nothing
                            // asks for float here (configure gets the
                            // container's own format, which carries no
                            // KEY_PCM_ENCODING), so this leg is insurance - but
                            // reading floats as pairs of shorts is noise, not a
                            // wrong number, so it is cheap insurance.
                            val floats = if (floatPcm) buf.asFloatBuffer() else null
                            val shorts = if (floatPcm) null else buf.asShortBuffer()
                            while (!outputDone && (floats?.hasRemaining() ?: shorts!!.hasRemaining())) {
                                var sum = 0f
                                var read = 0
                                while (read < channels && (floats?.hasRemaining() ?: shorts!!.hasRemaining())) {
                                    sum += floats?.get() ?: (shorts!!.get() / 32768f)
                                    read++
                                }
                                if (read == 0) break
                                delivered = true
                                if (!onFrame(sum / read)) outputDone = true
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return delivered
        } catch (c: CancellationException) {
            // Past the blanket catch below, or a cancelled read would read as
            // "this file has no sound" and the caller would cache that.
            throw c
        } catch (t: Throwable) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
