package com.squish.app.media

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Writes a window of a video file backwards, into a file of its own.
 *
 * Media3's Transformer plays a file forwards and nothing else, so a reversed
 * clip is a second file: the clip then points at it, and Reverse again points
 * it back (see Clip.reversed). Made with the platform's own codecs, frame by
 * frame, because there is no way to ask a decoder for the frame *before* this
 * one: footage is stored as runs of frames that each depend on the one before,
 * from a keyframe. So each run - a GOP - is decoded forwards, kept, and handed
 * to the encoder last frame first, and the runs are taken from the end of the
 * window to its start. A run of 1080p frames is a few megabytes each; a
 * [FrameSpool] keeps as many as fit a budget in memory and puts the rest of a
 * long run on disk, so a two-second GOP of 4K does not become a 700 MB
 * allocation.
 *
 * The frames never leave YUV: the decoder's picture is packed into a plain
 * I420 buffer and unpacked into the encoder's, whatever strides either side
 * uses. Turning each frame into a Bitmap and back would be a colour conversion
 * both ways for nothing, on every frame.
 *
 * The sound is reversed too - a whole window of PCM held in memory, which is
 * why [MAX_MS] is what it is - and encoded to AAC first, so both tracks are
 * known before the muxer starts. The file keeps the source's rotation tag
 * rather than turning pixels: the frames are written as they were stored, and
 * the tag says which way up, as the camera wrote it.
 *
 * Only 8-bit footage: the frames are packed as bytes, and a 10-bit picture
 * (HLG or HDR10 from a recent phone) has two per sample. Such a file is
 * refused before anything is decoded, from its transfer and profile tags,
 * and again from the decoder's first picture where the tags are missing -
 * read as bytes, its planes came out as green stripes.
 */
object ReverseRenderer {

    /** The longest window reversed: its sound is held whole, and three minutes of 48 kHz stereo is 35 MB. */
    const val MAX_MS = 3 * 60_000L

    /**
     * How much decoded picture is kept in memory before a run spills to disk:
     * a quarter of the heap the app is allowed, at most 96 MB. A fixed 96 MB
     * on a 256 MB heap that already holds the editor's filmstrip, waveforms
     * and preview state - plus the sound held whole - was an OutOfMemoryError
     * minutes into a long clip, or the process gone.
     */
    private val spoolBudgetBytes: Long
        get() = (Runtime.getRuntime().maxMemory() / 4).coerceIn(16L * 1024 * 1024, 96L * 1024 * 1024)

    /** A 10-bit transfer or profile, by MediaFormat's numbers: HLG and PQ transfers; HEVC Main 10, HDR10 and HDR10+; VP9 and AV1 profile 2. */
    private const val COLOR_TRANSFER_ST2084 = 6
    private const val COLOR_TRANSFER_HLG = 7
    private val TEN_BIT_HEVC_PROFILES = setOf(2, 4096, 8192)
    private const val VP9_AV1_PROFILE_2 = 4
    private const val TEN_BIT = "10-bit or HDR footage cannot be reversed here"

    /**
     * How far past the window [keyframesWithin] goes looking for the margin the
     * last run is fed to. A minute is far more than any ordinary file's spacing
     * - a second is usual - and past it there is nothing to find that would
     * save anything.
     */
    private const val TAIL_SCAN_US = 60_000_000L

    private const val TIMEOUT_US = 10_000L
    /** Dequeue attempts, at [TIMEOUT_US] each, before a draining encoder is given up on: five seconds. */
    private const val DRAIN_PATIENCE = 500
    private const val AUDIO_BITRATE = 128_000
    private const val TAG = "SquishReverse"

    /** The file written and how long it runs. */
    data class Rendered(val uri: Uri, val durationMs: Long)

    /**
     * Reverses [inMs]..[outMs] of [source] into [output]. [onProgress] is called
     * from the render's thread with 0..1. A cancelled or failed render leaves no
     * file behind; cancellation is thrown, as a coroutine's is, rather than
     * returned as a failure - a Cancel reported as "Couldn't reverse" would be
     * the phone contradicting the finger.
     */
    suspend fun render(
        context: Context,
        source: Uri,
        inMs: Long,
        outMs: Long,
        output: File,
        onProgress: (Float) -> Unit = {}
    ): Result<Rendered> = withContext(Dispatchers.Default) {
        val spill = File(context.cacheDir, "reverse_${System.nanoTime()}.yuv")
        val partial = File(output.absolutePath + ".part")
        val result = runCatching {
            val inUs = inMs.coerceAtLeast(0L) * 1_000L
            val outUs = outMs * 1_000L
            require(outUs > inUs) { "nothing to reverse" }
            require(outMs - inMs <= MAX_MS) { "too long to reverse" }
            runCatching { partial.delete() }
            val muxer = MediaMuxer(partial.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val sound = reverseSound(context, source, inUs, outUs) { onProgress(it * AUDIO_SHARE) }
                val length = reversePicture(context, source, inUs, outUs, muxer, sound, spill) {
                    onProgress(AUDIO_SHARE + it * (1f - AUDIO_SHARE))
                }
                muxer.stop()
                length
            } finally {
                runCatching { muxer.release() }
            }
        }.mapCatching { lengthMs ->
            if (partial.length() <= 0L || !partial.renameTo(output)) error("could not keep ${output.name}")
            Rendered(Uri.fromFile(output), lengthMs)
        }
        if (result.isFailure) {
            android.util.Log.w(TAG, "reverse of $source failed", result.exceptionOrNull())
            runCatching { partial.delete() }
        }
        runCatching { spill.delete() }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        result
    }

    private const val AUDIO_SHARE = 0.1f

    // ---- Sound -----------------------------------------------------------------

    /** The reversed sound as AAC samples, with the format the muxer's track takes; null when the file has none. */
    private class EncodedSound(val format: MediaFormat, val samples: List<Pair<ByteBuffer, MediaCodec.BufferInfo>>)

    private suspend fun reverseSound(
        context: Context,
        source: Uri,
        inUs: Long,
        outUs: Long,
        onProgress: (Float) -> Unit
    ): EncodedSound? {
        val pcm = decodeSound(context, source, inUs, outUs, onProgress) ?: return null
        val (samples, count, rate, channels) = pcm
        // Frame by frame - a frame being one sample per channel - so the
        // channels stay with each other. In place: the window is the one
        // large thing the sound stage holds, and a reversed copy beside it
        // doubled the 35 MB that MAX_MS budgets for.
        val frames = count / channels
        var lo = 0
        var hi = frames - 1
        while (lo < hi) {
            val a = lo * channels
            val b = hi * channels
            for (c in 0 until channels) {
                val t = samples[a + c]
                samples[a + c] = samples[b + c]
                samples[b + c] = t
            }
            lo++
            hi--
        }
        return encodeSound(samples, frames * channels, rate, channels)
    }

    /** [count] samples of [samples] are the sound; the array is allotted ahead and may run longer. */
    private data class Pcm(val samples: ShortArray, val count: Int, val sampleRate: Int, val channels: Int)

    /** The sound between [inUs] and [outUs] as 16-bit PCM, interleaved. Null without a sound track. */
    private suspend fun decodeSound(context: Context, source: Uri, inUs: Long, outUs: Long, onProgress: (Float) -> Unit): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            extractor.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floatPcm = false
            var out = ShortArray(((outUs - inUs) / 1_000_000.0 * rate * channels).toInt().coerceAtLeast(1024) + 4096)
            var written = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        // Past the window there is nothing more to read; the
                        // decoder still drains what it holds.
                        if (size < 0 || extractor.sampleTime > outUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val bytesPerSample = if (floatPcm) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            // Which of this buffer's frames fall inside the window.
                            val usPerFrame = 1_000_000.0 / rate
                            val firstUs = info.presentationTimeUs
                            val skip = if (firstUs >= inUs) 0 else ((inUs - firstUs) / usPerFrame).toInt().coerceIn(0, frames)
                            val keep = (((outUs - firstUs) / usPerFrame).toLong().coerceIn(0L, frames.toLong())).toInt() - skip
                            if (keep > 0) {
                                val need = written + keep * channels
                                if (need > out.size) out = out.copyOf(maxOf(need, out.size * 2))
                                if (floatPcm) {
                                    val fb = buf.asFloatBuffer()
                                    for (n in 0 until keep * channels) {
                                        val v = fb.get((skip * channels) + n)
                                        out[written++] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                    }
                                } else {
                                    val sb = buf.asShortBuffer()
                                    sb.position(skip * channels)
                                    sb.get(out, written, keep * channels)
                                    written += keep * channels
                                }
                            }
                            onProgress(((firstUs - inUs).toFloat() / (outUs - inUs)).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (written == 0) return null
            // Not trimmed to size: a copy of a three-minute window is a
            // third of it again in memory for nothing.
            return Pcm(out, written, rate, channels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun encodeSound(samples: ShortArray, count: Int, rate: Int, channels: Int): EncodedSound? {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val out = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
            var outFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        buf.order(ByteOrder.nativeOrder())
                        val room = (buf.capacity() / 2 / channels) * channels
                        val n = minOf(room, count - fed)
                        val ptsUs = (fed / channels) * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.asShortBuffer().put(samples, fed, n)
                            codec.queueInputBuffer(i, 0, n * 2, ptsUs, 0)
                            fed += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        // The codec's own header travels in the format, not as a sample.
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val copy = ByteBuffer.allocate(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            copy.put(buf)
                            copy.flip()
                            val meta = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                            out.add(copy to meta)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val f = outFormat ?: return null
            return EncodedSound(f, out)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    // ---- Picture ------------------------------------------------------------------

    /** Reverses the picture into [muxer], the sound's samples written first. Returns the length written, in ms. */
    private suspend fun reversePicture(
        context: Context,
        source: Uri,
        inUs: Long,
        outUs: Long,
        muxer: MediaMuxer,
        sound: EncodedSound?,
        spill: File,
        onProgress: (Float) -> Unit
    ): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        val spool = FrameSpool(spoolBudgetBytes, spill)
        try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: error("no video track")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("no video mime")
            if (isTenBit(format, mime)) error(TEN_BIT)
            extractor.selectTrack(track)

            val rotation = if (format.containsKey(KEY_ROTATION)) runCatching { format.getInteger(KEY_ROTATION) }.getOrDefault(0) else 0
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrElse {
                    runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }.getOrDefault(30)
                }
            } else 30
            val sourceBitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0) else 0

            // Where each run of frames starts, within the window: the keyframes.
            val runs = keyframesWithin(extractor, inUs, outUs)
            val syncs = runs.syncs
            if (syncs.isEmpty()) error("no keyframe before the window")

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder = MediaCodec.createDecoderByType(mime).started { configure(format, null, null, 0) }

            var videoTrack = -1
            var audioTrack = -1
            var started = false
            var frameW = 0
            var frameH = 0
            var packed: ByteArray? = null
            var lastPtsUs = -1L
            var firstPtsUs = -1L
            val outInfo = MediaCodec.BufferInfo()

            fun drainEncoder(enc: MediaCodec, untilEos: Boolean) {
                var idle = 0
                while (true) {
                    val o = enc.dequeueOutputBuffer(outInfo, if (untilEos) TIMEOUT_US else 0L)
                    // An encoder that never ends its stream would hold this forever.
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER && untilEos && ++idle > DRAIN_PATIENCE) error("the encoder did not finish")
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!started) { "the encoder changed its format mid-file" }
                            videoTrack = muxer.addTrack(enc.outputFormat)
                            if (sound != null) audioTrack = muxer.addTrack(sound.format)
                            muxer.setOrientationHint(rotation)
                            muxer.start()
                            started = true
                            // The sound goes in whole, first: its samples are
                            // already in order and the muxer interleaves by time.
                            if (sound != null) for ((buf, meta) in sound.samples) muxer.writeSampleData(audioTrack, buf, meta)
                        }
                        o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                        o >= 0 -> {
                            val buf = enc.getOutputBuffer(o)
                            if (buf != null && outInfo.size > 0 && outInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(started) { "a frame arrived before the encoder's format" }
                                buf.position(outInfo.offset)
                                buf.limit(outInfo.offset + outInfo.size)
                                muxer.writeSampleData(videoTrack, buf, outInfo)
                            }
                            enc.releaseOutputBuffer(o, false)
                            if (outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            // Last run first, so the frames come out newest first.
            for ((n, gop) in syncs.indices.reversed().withIndex()) {
                coroutineContext.ensureActive()
                val gopStartUs = syncs[gop]
                val gopEndUs = ReverseRuns.runEndUs(syncs, gop, runs.tailBoundUs)
                spool.clear()
                decodeRun(extractor, decoder, gopStartUs, gopEndUs, inUs, outUs) { image, ptsUs ->
                    if (frameW == 0) {
                        // Two bytes a sample on the luma plane is a 10-bit picture
                        // the tags did not announce.
                        if (image.planes[0].pixelStride != 1) error(TEN_BIT)
                        // Even sides, as encoders require: an odd crop loses its last line.
                        frameW = image.cropRect.width() / 2 * 2
                        frameH = image.cropRect.height() / 2 * 2
                        packed = ByteArray(frameW * frameH * 3 / 2)
                        encoder = openEncoder(frameW, frameH, fps, sourceBitrate)
                    }
                    packI420(image, packed!!, frameW, frameH)
                    spool.add(ptsUs, packed!!)
                }
                val enc = encoder ?: continue
                for (i in spool.count - 1 downTo 0) {
                    coroutineContext.ensureActive()
                    val ptsUs = spool.ptsAt(i)
                    val frame = spool.frameAt(i)
                    // Time runs the other way: the last frame of the window is the first of the file.
                    var newPts = outUs - ptsUs
                    if (newPts <= lastPtsUs) newPts = lastPtsUs + 1L
                    if (firstPtsUs < 0L) firstPtsUs = newPts
                    var queued = false
                    while (!queued) {
                        val idx = enc.dequeueInputBuffer(TIMEOUT_US)
                        if (idx >= 0) {
                            val image = enc.getInputImage(idx) ?: error("the encoder gave no image to fill")
                            unpackI420(frame, image, frameW, frameH)
                            enc.queueInputBuffer(idx, 0, frameW * frameH * 3 / 2, newPts - firstPtsUs, 0)
                            queued = true
                        }
                        drainEncoder(enc, untilEos = false)
                    }
                    lastPtsUs = newPts
                }
                onProgress((n + 1).toFloat() / syncs.size)
            }
            val enc = encoder ?: error("the window held no frames")
            while (true) {
                val idx = enc.dequeueInputBuffer(TIMEOUT_US)
                if (idx >= 0) {
                    enc.queueInputBuffer(idx, 0, 0, lastPtsUs - firstPtsUs + 1L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
            }
            drainEncoder(enc, untilEos = true)
            check(started) { "nothing was written" }
            return (lastPtsUs - firstPtsUs) / 1_000L + 1_000L / fps.coerceAtLeast(1)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
            spool.close()
        }
    }

    /** Whether the track's tags say its samples are more than a byte each. */
    private fun isTenBit(format: MediaFormat, mime: String): Boolean {
        val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) runCatching { format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrDefault(0) else 0
        if (transfer == COLOR_TRANSFER_ST2084 || transfer == COLOR_TRANSFER_HLG) return true
        val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) runCatching { format.getInteger(MediaFormat.KEY_PROFILE) }.getOrDefault(0) else 0
        return when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> profile in TEN_BIT_HEVC_PROFILES
            MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_AV1 -> profile == VP9_AV1_PROFILE_2
            else -> false
        }
    }

    /** The runs of a window: where each starts, and where the last one's feed stops. */
    private class Runs(val syncs: List<Long>, val tailBoundUs: Long)

    /**
     * The keyframes that start each run of the window - the one at or before
     * [inUs], then every one before [outUs] - and where the last of those runs
     * must stop being fed. Read off the sample flags, which costs a pass over
     * the container and no decoding.
     *
     * The walk carries on a little past [outUs] for that bound alone: see
     * [ReverseRuns], where the arithmetic is, and why the last run used to be
     * fed to the end of the file.
     */
    private fun keyframesWithin(extractor: MediaExtractor, inUs: Long, outUs: Long): Runs {
        val syncs = ArrayList<Long>()
        val past = ArrayList<Long>()
        extractor.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            val t = extractor.sampleTime
            if (t < 0L) break
            val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            if (t >= outUs) {
                // Only the margin is looked for past the window, and only so
                // far: a file with no keyframe for the next minute has one run
                // covering all of it anyway, so walking the rest of its samples
                // to say so would be a pass over the container for nothing.
                if (t - outUs > TAIL_SCAN_US) break
                if (sync && (past.isEmpty() || t > past.last())) {
                    past.add(t)
                    if (past.size >= ReverseRuns.TAIL_SYNCS) break
                }
            } else if (sync && (syncs.isEmpty() || t > syncs.last())) syncs.add(t)
            if (!extractor.advance()) break
        }
        return Runs(syncs, ReverseRuns.tailBoundUs(past))
    }

    /**
     * Decodes the run from [gopStartUs] up to [gopEndUs], handing over each
     * frame inside [inUs]..[outUs] as it comes. The decoder is drained to the
     * end of the run and flushed, so the next run starts clean on its keyframe.
     */
    private suspend fun decodeRun(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        gopStartUs: Long,
        gopEndUs: Long,
        inUs: Long,
        outUs: Long,
        onFrame: (Image, Long) -> Unit
    ) {
        extractor.seekTo(gopStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // Some containers land a hair before; walk up to the keyframe itself.
        while (extractor.sampleTime in 0L until gopStartUs) if (!extractor.advance()) break
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            coroutineContext.ensureActive()
            if (!inputDone) {
                val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val t = extractor.sampleTime
                    val buf = decoder.getInputBuffer(i)
                    val size = if (buf != null && t >= 0L && t < gopEndUs) extractor.readSampleData(buf, 0) else -1
                    if (size < 0) {
                        decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(i, 0, size, t, 0)
                        extractor.advance()
                    }
                }
            }
            val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (o >= 0) {
                val pts = info.presentationTimeUs
                if (info.size > 0 && pts >= inUs && pts < outUs) {
                    val image = decoder.getOutputImage(o)
                    if (image != null) {
                        try { onFrame(image, pts) } finally { image.close() }
                    }
                }
                decoder.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        // Drained to its end, the decoder takes no more until it is flushed.
        decoder.flush()
    }

    private fun openEncoder(width: Int, height: Int, fps: Int, sourceBitrate: Int): MediaCodec {
        val bitrate = if (sourceBitrate > 0) sourceBitrate else (width.toLong() * height * fps * 0.08).toInt()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceIn(500_000, 80_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 120))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .started { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
    }

    /**
     * Configured and started, or nothing left behind.
     *
     * `createCodec(...).also { configure(); start() }` loses the codec when
     * either call throws: the assignment never happens, so the caller's
     * `finally` sees null while a configured hardware session is held until the
     * process dies - and the next Reverse then cannot get one at all, so the
     * failure compounds rather than repeating. configure really does throw on
     * this path: a frame size the AVC encoder will not take (a 4K reverse on a
     * phone whose encoder stops at 1080), or no free session because of exactly
     * this leak. The audio encoder in this file has always created first and
     * configured inside a try; the two video codecs had not.
     */
    private fun MediaCodec.started(configure: MediaCodec.() -> Unit): MediaCodec {
        try {
            configure()
            start()
        } catch (t: Throwable) {
            runCatching { release() }
            throw t
        }
        return this
    }

    /** The decoder's picture into plain planar I420 - Y, then U, then V, each tightly packed. */
    private fun packI420(image: Image, out: ByteArray, width: Int, height: Int) {
        val crop = image.cropRect
        val planes = image.planes
        var offset = 0
        for (p in 0 until 3) {
            val plane = planes[p]
            val sub = if (p == 0) 1 else 2
            val w = width / sub
            val h = height / sub
            val left = crop.left / sub
            val top = crop.top / sub
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (pixelStride == 1) {
                for (row in 0 until h) {
                    buffer.position((top + row) * rowStride + left)
                    buffer.get(out, offset, w)
                    offset += w
                }
            } else {
                val line = ByteArray((w - 1) * pixelStride + 1)
                for (row in 0 until h) {
                    buffer.position((top + row) * rowStride + left * pixelStride)
                    buffer.get(line, 0, line.size)
                    var x = 0
                    while (x < w) {
                        out[offset++] = line[x * pixelStride]
                        x++
                    }
                }
            }
        }
    }

    /** Planar I420 into the encoder's picture, whatever its strides. */
    private fun unpackI420(frame: ByteArray, image: Image, width: Int, height: Int) {
        val planes = image.planes
        var offset = 0
        for (p in 0 until 3) {
            val plane = planes[p]
            val sub = if (p == 0) 1 else 2
            val w = width / sub
            val h = height / sub
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (pixelStride == 1) {
                for (row in 0 until h) {
                    buffer.position(row * rowStride)
                    buffer.put(frame, offset, w)
                    offset += w
                }
            } else {
                // One byte at a time: the U and V planes of a semi-planar
                // picture are the same bytes interleaved, and a whole line
                // written to one would wipe the other's.
                for (row in 0 until h) {
                    val base = row * rowStride
                    var x = 0
                    while (x < w) {
                        buffer.put(base + x * pixelStride, frame[offset++])
                        x++
                    }
                }
            }
        }
    }

    /** MediaFormat.KEY_ROTATION, spelled out as ThumbnailExtractor does. */
    private const val KEY_ROTATION = "rotation-degrees"

    /**
     * One run's decoded frames, in the order they came: in memory up to
     * [budgetBytes], and on disk in [spill] past that. Read back by index, any
     * order, so a run is handed over last frame first.
     */
    private class FrameSpool(private val budgetBytes: Long, private val spill: File) {
        private val pts = ArrayList<Long>()
        private val inMemory = ArrayList<ByteArray>()
        private var spilled = 0
        private var frameBytes = 0
        private var file: RandomAccessFile? = null
        private var memoryBytes = 0L
        private var scratch: ByteArray? = null

        val count: Int get() = pts.size

        fun add(ptsUs: Long, frame: ByteArray) {
            frameBytes = frame.size
            if (memoryBytes + frame.size <= budgetBytes) {
                inMemory.add(frame.copyOf())
                memoryBytes += frame.size
            } else {
                val f = file ?: RandomAccessFile(spill, "rw").also { file = it }
                f.seek(spilled.toLong() * frameBytes)
                f.write(frame)
                spilled++
            }
            pts.add(ptsUs)
        }

        fun ptsAt(i: Int): Long = pts[i]

        fun frameAt(i: Int): ByteArray {
            if (i < inMemory.size) return inMemory[i]
            val f = checkNotNull(file)
            val buf = scratch?.takeIf { it.size == frameBytes } ?: ByteArray(frameBytes).also { scratch = it }
            f.seek((i - inMemory.size).toLong() * frameBytes)
            f.readFully(buf)
            return buf
        }

        fun clear() {
            pts.clear()
            inMemory.clear()
            memoryBytes = 0L
            spilled = 0
            runCatching { file?.setLength(0L) }
        }

        fun close() {
            runCatching { file?.close() }
            file = null
            runCatching { spill.delete() }
        }
    }
}
