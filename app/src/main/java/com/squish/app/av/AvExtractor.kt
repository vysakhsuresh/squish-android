@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.av

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Media3's extractors for a file they read whole, Squish's FFmpeg demuxer
 * for one they do not (AvRoute decides, AvRouteRules says how). Every player
 * and every Transformer in the app is built over this, so a WMV, a RealMedia
 * file, a ProRes MOV or a DivX 3 AVI plays and exports like an MP4 does.
 */
class AvExtractorsFactory(
    context: Context,
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory()
) : ExtractorsFactory {

    private val context = context.applicationContext

    override fun createExtractors(): Array<Extractor> = delegate.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        if (AvRoute.ffmpeg(context, uri)) arrayOf(AvExtractor(context, uri))
        else delegate.createExtractors(uri, responseHeaders)

    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }
}

/**
 * A Media3 [Extractor] over Squish's FFmpeg.
 *
 * FFmpeg reads the file itself, through its own descriptor, because a
 * demuxer pulls and seeks as it likes and Media3's [ExtractorInput] only
 * reads forward. The input is still moved along behind it - skipped to each
 * packet's byte position - because that position is what Media3's loader
 * measures to know when it has buffered enough; an extractor that never
 * moved its input would have the whole file read into memory at once.
 *
 * Every picture and sound stream FFmpeg can decode becomes a track, typed
 * with Squish's own MIME types and carrying FFmpeg's parameters for it as
 * its initialization data, so the decoders (AvCodecs) open it exactly as
 * FFmpeg described it - no translation through Media3's names.
 */
class AvExtractor(context: Context, private val uri: Uri) : Extractor {

    private val context = context.applicationContext
    private var file: AvFile? = null
    private lateinit var output: ExtractorOutput
    private val tracks = HashMap<Int, TrackOutput>()
    private val audioStreams = HashSet<Int>()
    private var startUs = 0L
    private var buffer = ByteArray(1 shl 20)
    private var pendingSeekUs = C.TIME_UNSET
    private var packetsRead = 0L
    private val lastTimeUs = HashMap<Int, Long>()

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    private fun open(): AvFile {
        val opened = AvFile.open(context, uri)
            ?: throw ParserException.createForMalformedContainer("FFmpeg could not open $uri", null)
        val info = opened.info
        startUs = info.startUs.coerceAtLeast(0L)
        for (s in info.streams) {
            if (!s.decodable || !(s.isVideo || s.isAudio)) continue
            val params = runCatching { opened.streamParams(s.index) }.getOrNull() ?: continue
            val track = output.track(s.index, if (s.isVideo) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO)
            track.format(formatOf(s, info, params))
            tracks[s.index] = track
            if (s.isAudio) audioStreams += s.index
        }
        output.endTracks()
        output.seekMap(seekMapOf(info))
        file = opened
        return opened
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val f = file ?: open()
        if (pendingSeekUs != C.TIME_UNSET) {
            f.seek(pendingSeekUs + startUs)
            pendingSeekUs = C.TIME_UNSET
            lastTimeUs.clear()
        }
        val more = try {
            f.readPacket()
        } catch (e: AvException) {
            // A read error past the start is a truncated or damaged tail: what
            // came before it plays, as a player would have it.
            if (packetsRead == 0L) throw ParserException.createForMalformedContainer(e.message, e)
            android.util.Log.w("SquishAv", "stopping at a read error in $uri", e)
            false
        }
        if (!more) return Extractor.RESULT_END_OF_INPUT
        packetsRead++
        val stream = f.packetStream
        val track = tracks[stream]
        val size = f.packetSize
        if (track != null && size > 0 && !f.packetDiscard) {
            if (buffer.size < size) buffer = ByteArray(size + size / 4)
            f.packetData(buffer, 0)
            val raw = if (f.packetPtsUs != Long.MIN_VALUE) f.packetPtsUs else f.packetDtsUs
            // A packet with no time at all follows the one before it.
            val timeUs = if (raw != Long.MIN_VALUE) raw - startUs
            else (lastTimeUs[stream] ?: 0L) + f.packetDurationUs.coerceAtLeast(0L)
            lastTimeUs[stream] = timeUs
            track.sampleData(ParsableByteArray(buffer, size), size)
            // A sound packet is always somewhere a decoder can start; FFmpeg
            // does not always say so.
            val key = f.packetKey || stream in audioStreams
            track.sampleMetadata(timeUs, if (key) C.BUFFER_FLAG_KEY_FRAME else 0, size, 0, null)
        }
        follow(input, f.packetPosition, size)
        return Extractor.RESULT_CONTINUE
    }

    /** Moves Media3's input up to where FFmpeg is reading, so its buffering measure is true. */
    private fun follow(input: ExtractorInput, packetPosition: Long, size: Int) {
        val target = if (packetPosition >= 0) packetPosition + size else input.position + size
        var behind = target - input.position
        while (behind > 0) {
            val skipped = input.skip(minOf(behind, SKIP_STEP).toInt())
            if (skipped == C.RESULT_END_OF_INPUT) return
            behind -= skipped
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        // The first seek, to the start before anything is read, needs nothing done.
        if (file == null && timeUs == 0L) return
        pendingSeekUs = timeUs
    }

    override fun release() {
        file?.close()
        file = null
    }

    private fun seekMapOf(info: AvInfo): SeekMap {
        val durationUs = if (info.durationUs > 0) info.durationUs else C.TIME_UNSET
        val size = info.size
        if (!info.seekable || durationUs == C.TIME_UNSET) return SeekMap.Unseekable(durationUs)
        return object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = durationUs

            // The byte position only places Media3's input, which FFmpeg's
            // reads then pull forward (see follow); so it errs early - a
            // position past FFmpeg's would have nothing skipped, one before it
            // is skipped up to it - and costs nothing to compute, since this
            // is asked on the player's thread while the loader owns the file.
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val t = timeUs.coerceIn(0L, durationUs)
                val position = if (size > 0) (size.toDouble() * t / durationUs * 0.9).toLong() else 0L
                return SeekMap.SeekPoints(SeekPoint(t, position))
            }
        }
    }

    private fun formatOf(s: AvStream, info: AvInfo, params: ByteArray): Format {
        val b = Format.Builder()
            .setId(s.index.toString())
            .setSampleMimeType(if (s.isVideo) AvCodecMap.MIME_VIDEO else AvCodecMap.MIME_AUDIO)
            .setCodecs(s.codec)
            .setInitializationData(listOf(params))
            .setLanguage(s.language.takeIf { it.isNotEmpty() && it != "und" })
            .setLabel(s.title.takeIf { it.isNotEmpty() })
            .setSelectionFlags(if (s.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
        if (s.bitRate > 0) b.setAverageBitrate(s.bitRate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (s.isVideo) {
            b.setWidth(s.width).setHeight(s.height).setRotationDegrees(s.rotation)
            if (s.fps > 0f) b.setFrameRate(s.fps)
            if (s.sarNum > 0 && s.sarDen > 0) b.setPixelWidthHeightRatio(s.sarNum.toFloat() / s.sarDen)
            colorInfoOf(s)?.let { b.setColorInfo(it) }
            b.setMaxInputSize(videoInputSize(s, info))
        } else {
            val channels = if (s.channels > 8) 2 else s.channels
            b.setSampleRate(s.sampleRate).setChannelCount(channels.coerceAtLeast(1))
            b.setMaxInputSize(AUDIO_INPUT)
        }
        return b.build()
    }

    companion object {
        /** At most this much skipped behind FFmpeg per call, so a read returns quickly. */
        private const val SKIP_STEP = 4L shl 20

        private const val AUDIO_INPUT = 512 * 1024

        /**
         * Room for the largest packet the stream will send: three frames'
         * worth of its bitrate, never under 2 MB, never over 64 MB - ProRes
         * 4444 XQ at 4K runs to about 9 MB a frame, and uncompressed video
         * is its own frame size (which the bitrate says).
         */
        fun videoInputSize(s: AvStream, info: AvInfo): Int {
            val rate = if (s.bitRate > 0) s.bitRate else info.bitRate
            val fps = if (s.fps > 1f) s.fps else 25f
            val perFrame = if (rate > 0) (rate / 8.0 / fps).toLong() else 0L
            val pixels = s.width.toLong() * s.height
            return maxOf(2L shl 20, perFrame * 3, pixels).coerceAtMost(64L shl 20).toInt()
        }

        /** FFmpeg's colour enums are ISO/IEC 23091-2's numbers, which Media3 reads directly. */
        fun colorInfoOf(s: AvStream): ColorInfo? {
            val space = ColorInfo.isoColorPrimariesToColorSpace(s.colorPrimaries)
            val transfer = ColorInfo.isoTransferCharacteristicsToColorTransfer(s.colorTrc)
            val range = when (s.colorRange) {
                1 -> C.COLOR_RANGE_LIMITED
                2 -> C.COLOR_RANGE_FULL
                else -> Format.NO_VALUE
            }
            if (space == Format.NO_VALUE && transfer == Format.NO_VALUE && range == Format.NO_VALUE) return null
            return ColorInfo.Builder()
                .setColorSpace(space)
                .setColorTransfer(transfer)
                .setColorRange(range)
                .setLumaBitdepth(s.bits.takeIf { it > 0 } ?: Format.NO_VALUE)
                .setChromaBitdepth(s.bits.takeIf { it > 0 } ?: Format.NO_VALUE)
                .build()
        }
    }
}
