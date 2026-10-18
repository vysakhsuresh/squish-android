package com.squish.app.media.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A 16-bit PCM WAV written again as AAC in an MP4 (.m4a), with the phone's
 * own encoder. For the tracks Squish composes (MusicLibrary): a WAV of a
 * fifty-second track is 6.5 MB, the same track at 192 kbps AAC under 1.3,
 * which is what lets every original be made ahead of time rather than on
 * the tap that wants it.
 */
internal object AacWriter {

    private const val BITRATE = 192_000
    private const val TIMEOUT_US = 10_000L

    /** Encodes [wav] into [out]; false (and no [out]) when it could not. */
    fun encode(wav: File, out: File): Boolean {
        val header = readHeader(wav) ?: return false
        val part = File(out.absolutePath + ".part")
        part.delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, header.rate, header.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull() ?: return false
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(part.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(header.dataOffset)
                val frameBytes = 2 * header.channels
                var remaining = header.dataBytes
                var framesFed = 0L
                val info = MediaCodec.BufferInfo()
                var track = -1
                var inputDone = false
                var outputDone = false
                val chunk = ByteArray(32 * 1024)
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            buf.clear()
                            val room = (minOf(buf.remaining(), chunk.size) / frameBytes) * frameBytes
                            val n = minOf(room.toLong(), remaining).toInt()
                            val ptsUs = framesFed * 1_000_000L / header.rate
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                raf.readFully(chunk, 0, n)
                                buf.put(chunk, 0, n)
                                codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                                remaining -= n
                                framesFed += n / frameBytes
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)
                            if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                ok = track >= 0
            }
        } catch (e: Exception) {
            android.util.Log.w("SquishMusic", "could not write ${out.name}", e)
            ok = false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (ok) muxer?.stop() }
            runCatching { muxer?.release() }
        }
        if (ok && part.length() > 0L && part.renameTo(out)) return true
        part.delete()
        return false
    }

    private class Header(val rate: Int, val channels: Int, val dataOffset: Long, val dataBytes: Long)

    /** The fmt and data chunks of a 16-bit PCM WAV; null for anything else. */
    private fun readHeader(wav: File): Header? = runCatching {
        DataInputStream(wav.inputStream().buffered()).use { input ->
            val riff = ByteArray(12)
            input.readFully(riff)
            if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") return null
            var offset = 12L
            var rate = 0
            var channels = 0
            var bits = 0
            while (true) {
                val id = ByteArray(4)
                input.readFully(id)
                val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                offset += 8
                when (String(id)) {
                    "fmt " -> {
                        val body = ByteArray(size.toInt())
                        input.readFully(body)
                        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                        if (b.short.toInt() != 1) return null
                        channels = b.short.toInt()
                        rate = b.int
                        b.int
                        b.short
                        bits = b.short.toInt()
                    }
                    "data" -> {
                        if (rate <= 0 || channels <= 0 || bits != 16) return null
                        return Header(rate, channels, offset, size)
                    }
                    else -> input.skipBytes(size.toInt())
                }
                offset += size
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }.getOrNull()
}
