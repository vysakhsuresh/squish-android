package com.squish.app.av

/**
 * How a track Media3's own readers found is handed to Squish's FFmpeg: the
 * decoder FFmpeg knows it by, and the extradata built from the track's
 * initialization data. Pure, so tools/jvm/AvChecks.kt executes it.
 *
 * Media3's readers write H.264 and H.265 as Annex B (start codes before each
 * NAL unit, the parameter sets likewise in the initialization data), which is
 * what FFmpeg's decoders take when the extradata is Annex B too; so the sets
 * are joined as they are. The three that need repacking are the ones the
 * Media3 FFmpeg extension repacks, for the same reasons: Vorbis (FFmpeg wants
 * all three headers with sizes; Media3 keeps two), ALAC (FFmpeg reads the
 * cookie inside its atom) and nothing else.
 */
object AvCodecMap {

    /** The sample MIME types Squish's own reader (AvExtractor) gives its tracks. */
    const val MIME_VIDEO = "video/x-squish-av"
    const val MIME_AUDIO = "audio/x-squish-av"

    /** FFmpeg's name for the decoder of [mime] (with [codecs] for the ambiguous ones), or null. */
    fun ffmpegName(mime: String?, codecs: String?): String? = when (mime) {
        "video/avc" -> "h264"
        "video/hevc" -> "hevc"
        "video/dolby-vision" -> {
            val c = codecs.orEmpty()
            when {
                c.startsWith("dvav") || c.startsWith("dva1") -> "h264"
                c.startsWith("dav1") -> "av1"
                else -> "hevc"
            }
        }
        "video/av01" -> "av1"
        "video/x-vnd.on2.vp8" -> "vp8"
        "video/x-vnd.on2.vp9" -> "vp9"
        "video/mp4v-es", "video/divx" -> "mpeg4"
        "video/3gpp" -> "h263"
        "video/mpeg2" -> "mpeg2video"
        "video/mpeg" -> "mpeg1video"
        "video/mjpeg" -> "mjpeg"
        "video/wvc1" -> "vc1"
        "video/mp42" -> "msmpeg4v2"
        "video/mp43" -> "msmpeg4v3"
        "video/apv" -> "apv"
        "audio/mp4a-latm" -> "aac"
        "audio/mpeg" -> "mp3"
        "audio/mpeg-L1" -> "mp1"
        "audio/mpeg-L2" -> "mp2"
        "audio/ac3" -> "ac3"
        "audio/eac3", "audio/eac3-joc" -> "eac3"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr", "audio/vnd.dts.uhd;profile=p2" -> "dts"
        "audio/true-hd" -> "truehd"
        "audio/vorbis" -> "vorbis"
        "audio/opus" -> "opus"
        "audio/flac" -> "flac"
        "audio/alac" -> "alac"
        "audio/3gpp" -> "amr_nb"
        "audio/amr-wb" -> "amr_wb"
        "audio/g711-alaw" -> "pcm_alaw"
        "audio/g711-mlaw" -> "pcm_mulaw"
        else -> null
    }

    /** The extradata FFmpeg's [codec] decoder wants from Media3's [initialization] data, or null for none. */
    fun extradata(codec: String, initialization: List<ByteArray>): ByteArray? {
        if (initialization.isEmpty()) return null
        return when (codec) {
            "h264", "hevc" -> join(initialization)
            "vorbis" -> if (initialization.size >= 2) vorbis(initialization[0], initialization[1]) else null
            "alac" -> alacAtom(initialization[0])
            // A Matroska or MP4 Opus track carries the OpusHead first, then
            // Media3's own codec delay and seek pre-roll as 8-byte numbers;
            // only the head is FFmpeg's.
            "opus" -> initialization[0]
            else -> initialization[0]
        }
    }

    private fun join(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    /**
     * The identification and setup headers, each behind a 16-bit size, with
     * an empty comment header between them - the layout FFmpeg's Xiph
     * splitter recognises by the first size being the identification
     * header's 30 bytes.
     */
    fun vorbis(identification: ByteArray, setup: ByteArray): ByteArray {
        val out = ByteArray(identification.size + setup.size + 6)
        out[0] = (identification.size shr 8).toByte()
        out[1] = identification.size.toByte()
        System.arraycopy(identification, 0, out, 2, identification.size)
        val at = identification.size + 2
        out[at] = 0
        out[at + 1] = 0
        out[at + 2] = (setup.size shr 8).toByte()
        out[at + 3] = setup.size.toByte()
        System.arraycopy(setup, 0, out, at + 4, setup.size)
        return out
    }

    /** The ALAC magic cookie inside its atom: size, "alac", version and flags, then the cookie. */
    fun alacAtom(cookie: ByteArray): ByteArray {
        // A cookie that already carries its atom header is passed as it is.
        if (cookie.size >= 12 && cookie[4] == 'a'.code.toByte() && cookie[5] == 'l'.code.toByte() &&
            cookie[6] == 'a'.code.toByte() && cookie[7] == 'c'.code.toByte()
        ) return cookie
        val size = 12 + cookie.size
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte(); out[1] = (size ushr 16).toByte(); out[2] = (size ushr 8).toByte(); out[3] = size.toByte()
        out[4] = 'a'.code.toByte(); out[5] = 'l'.code.toByte(); out[6] = 'a'.code.toByte(); out[7] = 'c'.code.toByte()
        System.arraycopy(cookie, 0, out, 12, cookie.size)
        return out
    }

    /**
     * The parameter blob libsquishav reads (read_params in squishav.c):
     * "SQP1", thirty int32, two int64, the codec name, the extradata, all
     * little-endian. Only what a Media3 format can say is filled; the rest is
     * FFmpeg's "unknown".
     */
    fun blob(
        video: Boolean,
        codec: String,
        extradata: ByteArray?,
        width: Int = 0,
        height: Int = 0,
        sampleRate: Int = 0,
        channels: Int = 0,
        bitsPerSample: Int = 0,
        bitRate: Long = 0L
    ): ByteArray {
        val name = codec.toByteArray(Charsets.US_ASCII)
        val extra = extradata ?: ByteArray(0)
        val out = java.nio.ByteBuffer.allocate(4 + 30 * 4 + 16 + 4 + name.size + 4 + extra.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("SQP1".toByteArray(Charsets.US_ASCII))
        val unknownProfile = -99
        val ints = intArrayOf(
            if (video) 0 else 1,           // codec_type: AVMEDIA_TYPE_VIDEO, AUDIO
            0,                             // codec_tag
            -1,                            // format
            bitsPerSample.coerceAtLeast(0), // bits_per_coded_sample
            0,                             // bits_per_raw_sample
            unknownProfile, unknownProfile, // profile, level
            width.coerceAtLeast(0), height.coerceAtLeast(0),
            0, 1,                          // sample aspect ratio
            0,                             // field order
            0, 2, 2, 2,                    // range, primaries, transfer, matrix: unspecified
            0, 0,                          // chroma location, video delay
            sampleRate.coerceAtLeast(0),
            0, channels.coerceAtLeast(0),  // channel order unspecified: FFmpeg's default for the count
            0, 0, 0, 0, 0,                 // block align, frame size, padding, trailing, pre-roll
            0, 1,                          // frame rate
            1, 1_000_000                   // time base: microseconds
        )
        check(ints.size == 30)
        for (v in ints) out.putInt(v)
        out.putLong(bitRate.coerceAtLeast(0L))
        out.putLong(0L)
        out.putInt(name.size)
        out.put(name)
        out.putInt(extra.size)
        out.put(extra)
        return out.array()
    }
}
