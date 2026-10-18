package com.squish.app.av

/**
 * Whose reader opens a file: Media3's own extractors, which every file the
 * app played before Squish had its own FFmpeg went through and which stay
 * first, or Squish's FFmpeg demuxer (AvExtractor) for the rest.
 *
 * The question is not "can Media3 open it" - Media3 opens a great deal and
 * then quietly drops the tracks it has no name for: a ProRes picture in a
 * MOV, a WMA sound in an MKV, a DivX 3 picture in an AVI by a fourcc it does
 * not list. The file plays silent, or black, and nothing says why. So the
 * rule is a list of what Media3 reads *whole*, container by container, read
 * off the 1.11.1 sources (Mp4Extractor's sample entries, MatroskaExtractor's
 * codec ids, AviExtractor's fourccs and wave tags, and the rest), and a file
 * goes to Media3 only when every picture and sound stream in it is on the
 * list for its container. Everything else - every container FFmpeg has and
 * Media3 does not, every codec Media3's readers drop - goes to FFmpeg.
 *
 * Pure, so tools/jvm/AvChecks.kt executes it.
 */
object AvRouteRules {

    /** A stream as the rule sees it: picture or sound, FFmpeg's codec name, its container tag. */
    data class Stream(val video: Boolean, val codec: String, val tag: Long = 0L)

    private val MP4_VIDEO = setOf("h264", "hevc", "av1", "vp9", "vp8", "mpeg4", "h263", "apv")
    private val MP4_AUDIO = setOf(
        "aac", "mp3", "ac3", "eac3", "opus", "flac", "alac", "amr_nb", "amr_wb",
        "pcm_s16le", "pcm_s16be", "dts", "mp2"
    )

    private val MKV_VIDEO = setOf("h264", "hevc", "vp8", "vp9", "av1", "mpeg2video", "mpeg4")
    private val MKV_AUDIO = setOf(
        "aac", "mp3", "mp2", "ac3", "eac3", "truehd", "dts", "vorbis", "opus", "flac", "alac",
        "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_s16be", "pcm_s24be", "pcm_s32be", "pcm_f32le"
    )

    /** AviExtractor's fourccs (StreamFormatChunk.getMimeTypeFromCompression), little-endian as stored. */
    private val AVI_VIDEO_TAGS = setOf(
        fourcc("MP42"), fourcc("MP43"), fourcc("H264"), fourcc("avc1"), fourcc("AVC1"),
        fourcc("3VID"), fourcc("divx"), fourcc("DIVX"), fourcc("DX50"), fourcc("FMP4"),
        fourcc("xvid"), fourcc("XVID"), fourcc("MJPG"), fourcc("mjpg")
    )

    /** AviExtractor's wave format tags: PCM, MP3, AAC, AC-3, DTS. */
    private val AVI_AUDIO_TAGS = setOf(0x1L, 0x55L, 0xffL, 0x2000L, 0x2001L)

    private val TS_VIDEO = setOf("h264", "hevc", "mpeg2video", "mpeg1video")
    private val TS_AUDIO = setOf("aac", "aac_latm", "mp3", "mp2", "ac3", "eac3", "dts")

    private val PS_VIDEO = setOf("mpeg2video", "mpeg1video", "h264")
    private val PS_AUDIO = setOf("mp2", "mp3", "ac3")

    private val WAV_AUDIO = setOf("pcm_s16le", "pcm_u8", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_alaw", "pcm_mulaw", "adpcm_ima_wav")

    /**
     * Whether Media3's extractors read every picture and sound stream of a
     * file FFmpeg calls [format]. [tsPacketSize] is the transport stream's
     * packet size where [format] is "mpegts" (188, or 192 for a camcorder's
     * M2TS - which Media3's TsExtractor does not read).
     */
    fun media3Reads(format: String, streams: List<Stream>, tsPacketSize: Int = 188): Boolean {
        val av = streams
        if (av.isEmpty()) return false
        val ok: (Stream) -> Boolean = when (format) {
            "mov,mp4,m4a,3gp,3g2,mj2" -> { s -> if (s.video) s.codec in MP4_VIDEO else s.codec in MP4_AUDIO }
            // A tag in Matroska is a Video for Windows or ACM codec behind
            // V_MS/VFW/FOURCC or A_MS/ACM, which Media3 reads only as PCM.
            "matroska,webm" -> { s ->
                if (s.video) s.tag == 0L && s.codec in MKV_VIDEO
                else if (s.tag != 0L) s.codec.startsWith("pcm_") else s.codec in MKV_AUDIO
            }
            "avi" -> { s -> if (s.video) s.tag in AVI_VIDEO_TAGS else s.tag in AVI_AUDIO_TAGS && s.codec != "adpcm_ms" }
            "mpegts" -> { s -> tsPacketSize == 188 && if (s.video) s.codec in TS_VIDEO else s.codec in TS_AUDIO }
            "mpeg" -> { s -> if (s.video) s.codec in PS_VIDEO else s.codec in PS_AUDIO }
            "flv" -> { s -> if (s.video) s.codec == "h264" else s.codec == "aac" || s.codec == "mp3" }
            "mp3" -> { s -> !s.video && s.codec == "mp3" }
            "aac" -> { s -> !s.video && s.codec == "aac" }
            "flac" -> { s -> !s.video && s.codec == "flac" }
            "ogg" -> { s -> !s.video && (s.codec == "vorbis" || s.codec == "opus" || s.codec == "flac") }
            "wav" -> { s -> !s.video && s.codec in WAV_AUDIO }
            "amr" -> { s -> !s.video && (s.codec == "amr_nb" || s.codec == "amr_wb") }
            "ac3", "eac3" -> { s -> !s.video && (s.codec == "ac3" || s.codec == "eac3") }
            else -> { _ -> false }
        }
        return av.all(ok)
    }

    /** A fourcc as FFmpeg's codec_tag holds it: the first letter in the low byte. */
    fun fourcc(s: String): Long {
        require(s.length == 4)
        return (s[0].code.toLong()) or (s[1].code.toLong() shl 8) or (s[2].code.toLong() shl 16) or (s[3].code.toLong() shl 24)
    }

    /**
     * The packet size of a transport stream from its first bytes: 188 when the
     * sync byte 0x47 repeats every 188 bytes from the start, 192 when it
     * repeats every 192 from byte 4 (the four-byte timecode an M2TS puts in
     * front of each packet), else 0.
     */
    fun tsPacketSize(head: ByteArray): Int {
        fun syncs(start: Int, step: Int): Boolean {
            var at = start
            var n = 0
            while (at < head.size && n < 4) {
                if (head[at] != 0x47.toByte()) return false
                at += step
                n++
            }
            return n >= 3
        }
        return when {
            syncs(0, 188) -> 188
            syncs(4, 192) -> 192
            else -> 0
        }
    }
}

/**
 * Whether a handle the native side returns is a failure: zero, or one of
 * FFmpeg's error numbers, which are negative ints. Not "<= 0": a heap
 * pointer on an arm64 phone carries a tag in its top byte (the moto's are
 * 0xb4...), which as a signed long is hugely negative - and read as an
 * error, every file FFmpeg opened was thrown away and every reader quietly
 * fell back to the platform's. Pure, so AvChecks holds it.
 */
object AvHandles {
    fun failed(handle: Long): Boolean = handle == 0L || (handle < 0L && handle >= Int.MIN_VALUE.toLong())
}
