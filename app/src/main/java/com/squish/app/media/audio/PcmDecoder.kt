package com.squish.app.media.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
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
            if (trackIndex < 0 || format == null) return@withContext null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 1

            val stride = max(1, sourceRate / targetSampleRate)
            val analysisRate = sourceRate / stride
            val limit = ((maxDurationMs / 1000.0) * analysisRate).toInt().coerceAtLeast(1024)

            var out = FloatArray(minOf(limit, 1 shl 20))
            var written = 0
            var framesSeen = 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            while (shorts.hasRemaining() && written < limit) {
                                var sum = 0f
                                var read = 0
                                while (read < channels && shorts.hasRemaining()) {
                                    sum += shorts.get() / 32768f
                                    read++
                                }
                                if (read == 0) break
                                if (framesSeen % stride == 0L) {
                                    if (written == out.size) out = out.copyOf(minOf(out.size * 2, limit))
                                    if (written < out.size) out[written++] = sum / read
                                }
                                framesSeen++
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (written >= limit) outputDone = true
                }
            }

            if (written == 0) null else MonoPcm(out.copyOf(written), analysisRate)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
