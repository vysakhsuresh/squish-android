import com.squish.app.av.AvCodecMap
import com.squish.app.av.AvRouteRules
import com.squish.app.av.AvRouteRules.Stream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Squish's own FFmpeg, the parts that are arithmetic: which reader opens a
 * file (AvRouteRules) and how a track Media3 found is handed to FFmpeg
 * (AvCodecMap), executed.
 *
 *   sh tools/jvm/jc.sh AvChecks app/src/main/java/com/squish/app/av/AvCodecMap.kt \
 *      app/src/main/java/com/squish/app/av/AvRouteRules.kt tools/jvm/AvChecks.kt
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

private const val MP4 = "mov,mp4,m4a,3gp,3g2,mj2"
private const val MKV = "matroska,webm"

private fun v(codec: String, tag: Long = 0L) = Stream(video = true, codec = codec, tag = tag)
private fun a(codec: String, tag: Long = 0L) = Stream(video = false, codec = codec, tag = tag)

fun main() {
    // ---- Whose reader. Every file that played before stays with Media3. ------
    check(AvRouteRules.media3Reads(MP4, listOf(v("h264"), a("aac"))), "a phone's own MP4 goes to FFmpeg")
    check(AvRouteRules.media3Reads(MP4, listOf(v("hevc"), a("aac"))), "a phone's HEVC MP4 goes to FFmpeg")
    check(AvRouteRules.media3Reads(MP4, listOf(v("hevc"), a("eac3"))), "an HEVC MP4 with Dolby Digital Plus goes to FFmpeg")
    check(AvRouteRules.media3Reads(MKV, listOf(v("hevc"), a("dts"))), "a film rip's HEVC and DTS MKV goes to FFmpeg; Media3 reads both tracks")
    check(AvRouteRules.media3Reads(MKV, listOf(v("vp9"), a("opus"))), "a WebM goes to FFmpeg")
    check(AvRouteRules.media3Reads("mp3", listOf(a("mp3"))), "an MP3 goes to FFmpeg")
    check(AvRouteRules.media3Reads("flac", listOf(a("flac"))), "a FLAC goes to FFmpeg")
    check(AvRouteRules.media3Reads("ogg", listOf(a("vorbis"))), "an Ogg Vorbis goes to FFmpeg")
    check(AvRouteRules.media3Reads("wav", listOf(a("pcm_s16le"))), "a WAV goes to FFmpeg")
    check(AvRouteRules.media3Reads("mpegts", listOf(v("h264"), a("aac")), 188), "a 188-byte transport stream goes to FFmpeg")
    check(AvRouteRules.media3Reads("avi", listOf(v("mpeg4", AvRouteRules.fourcc("XVID")), a("mp3", 0x55))), "an Xvid AVI goes to FFmpeg")
    check(AvRouteRules.media3Reads("flv", listOf(v("h264"), a("aac"))), "an H.264 FLV goes to FFmpeg")

    // ---- And everything Media3 drops a track of, or cannot open, to FFmpeg. --
    check(!AvRouteRules.media3Reads(MP4, listOf(v("prores"), a("pcm_s24le"))), "an iPhone ProRes MOV stays with Media3, which drops the picture")
    check(!AvRouteRules.media3Reads(MP4, listOf(v("h264"), a("pcm_s24le"))), "24-bit PCM in a MOV stays with Media3, which drops the sound")
    check(!AvRouteRules.media3Reads(MP4, listOf(v("mjpeg"), a("pcm_u8"))), "an old camera's Motion JPEG MOV stays with Media3")
    check(!AvRouteRules.media3Reads("asf", listOf(v("wmv3"), a("wmav2"))), "a WMV stays with Media3, which has no ASF reader")
    check(!AvRouteRules.media3Reads("rm", listOf(v("rv40"), a("cook"))), "a RealMedia file stays with Media3")
    check(!AvRouteRules.media3Reads(MKV, listOf(v("msmpeg4v3", AvRouteRules.fourcc("DIV3")), a("mp3"))), "a DivX 3 picture behind V_MS/VFW stays with Media3")
    check(!AvRouteRules.media3Reads(MKV, listOf(v("mpeg4", AvRouteRules.fourcc("XVID")), a("aac"))), "an Xvid picture behind V_MS/VFW stays with Media3, which names it unknown")
    check(!AvRouteRules.media3Reads(MKV, listOf(v("h264"), a("wmav2", 0x161))), "a WMA sound behind A_MS/ACM stays with Media3")
    check(AvRouteRules.media3Reads(MKV, listOf(v("h264"), a("pcm_s16le", 0x1))), "PCM behind A_MS/ACM, which Media3 reads, goes to FFmpeg")
    check(!AvRouteRules.media3Reads(MKV, listOf(v("theora"), a("vorbis"))), "a Theora MKV stays with Media3")
    check(!AvRouteRules.media3Reads("avi", listOf(v("msmpeg4v3", AvRouteRules.fourcc("DIV3")), a("mp3", 0x55))), "a DIV3 AVI (a fourcc AviExtractor does not list) stays with Media3")
    check(!AvRouteRules.media3Reads("avi", listOf(v("mpeg4", AvRouteRules.fourcc("XVID")), a("adpcm_ms", 0x2))), "an AVI with MS ADPCM sound stays with Media3")
    check(!AvRouteRules.media3Reads("avi", listOf(v("cinepak", AvRouteRules.fourcc("cvid")))), "a Cinepak AVI stays with Media3")
    check(!AvRouteRules.media3Reads("mpegts", listOf(v("h264"), a("ac3")), 192), "a camcorder's M2TS (192-byte packets) stays with Media3")
    check(!AvRouteRules.media3Reads("mpegts", listOf(v("vc1"), a("ac3")), 188), "a VC-1 transport stream stays with Media3")
    check(!AvRouteRules.media3Reads("mpeg", listOf(v("mpeg2video"), a("pcm_dvd"))), "a DVD's LPCM sound in a VOB stays with Media3")
    check(!AvRouteRules.media3Reads("flv", listOf(v("flv1"), a("mp3"))), "a Sorenson Spark FLV stays with Media3")
    check(!AvRouteRules.media3Reads("ape", listOf(a("ape"))), "a Monkey's Audio file stays with Media3")
    check(!AvRouteRules.media3Reads("mxf", listOf(v("dnxhd"), a("pcm_s24le"))), "an MXF stays with Media3")
    check(!AvRouteRules.media3Reads("dv", listOf(v("dvvideo"), a("pcm_s16le"))), "a DV tape capture stays with Media3")
    check(!AvRouteRules.media3Reads(MP4, emptyList()), "a file with no picture or sound is said to be read by Media3")

    // ---- The transport stream's packet size, from its first bytes. ----------
    fun ts(start: Int, step: Int): ByteArray = ByteArray(1024).also { b -> var at = start; while (at < b.size) { b[at] = 0x47; at += step } }
    check(AvRouteRules.tsPacketSize(ts(0, 188)) == 188, "a 188-byte stream is not found")
    check(AvRouteRules.tsPacketSize(ts(4, 192)) == 192, "an M2TS is not found")
    check(AvRouteRules.tsPacketSize(ByteArray(1024)) == 0, "a stream of zeros is a transport stream")
    check(AvRouteRules.fourcc("XVID") == 0x44495658L, "a fourcc is not stored low byte first, as FFmpeg's codec_tag is")

    // ---- Media3's names to FFmpeg's. ----------------------------------------
    val names = mapOf(
        "video/avc" to "h264", "video/hevc" to "hevc", "video/av01" to "av1", "video/x-vnd.on2.vp9" to "vp9",
        "video/mp4v-es" to "mpeg4", "video/mpeg2" to "mpeg2video", "video/mjpeg" to "mjpeg", "video/wvc1" to "vc1",
        "video/mp43" to "msmpeg4v3", "audio/mp4a-latm" to "aac", "audio/vnd.dts" to "dts", "audio/vnd.dts.hd" to "dts",
        "audio/true-hd" to "truehd", "audio/eac3-joc" to "eac3", "audio/3gpp" to "amr_nb", "audio/amr-wb" to "amr_wb",
        "audio/vorbis" to "vorbis", "audio/alac" to "alac", "audio/flac" to "flac", "audio/opus" to "opus"
    )
    for ((mime, codec) in names) check(AvCodecMap.ffmpegName(mime, null) == codec, "$mime is not FFmpeg's $codec: ${AvCodecMap.ffmpegName(mime, null)}")
    check(AvCodecMap.ffmpegName("video/dolby-vision", "dvhe.08.06") == "hevc", "Dolby Vision 8 is not decoded as its HEVC base")
    check(AvCodecMap.ffmpegName("video/dolby-vision", "dvav.09.05") == "h264", "Dolby Vision 9 is not decoded as its H.264 base")
    check(AvCodecMap.ffmpegName("audio/raw", null) == null, "raw PCM is handed to a decoder")
    check(AvCodecMap.ffmpegName("video/x-unknown", null) == null, "an unknown picture is guessed at")

    // ---- Extradata. --------------------------------------------------------
    val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2)
    val pps = byteArrayOf(0, 0, 0, 1, 0x68, 3)
    check(AvCodecMap.extradata("h264", listOf(sps, pps))?.contentEquals(sps + pps) == true, "H.264 parameter sets are not joined as Annex B")
    val id = ByteArray(30) { 1 }
    val setup = ByteArray(300) { 2 }
    val vorbis = AvCodecMap.extradata("vorbis", listOf(id, setup))!!
    check(vorbis.size == 30 + 300 + 6, "the Vorbis extradata is ${vorbis.size} bytes")
    check(((vorbis[0].toInt() and 255) shl 8 or (vorbis[1].toInt() and 255)) == 30, "the Vorbis identification header's size is not first")
    check(vorbis[32] == 0.toByte() && vorbis[33] == 0.toByte(), "the empty comment header is missing")
    check(((vorbis[34].toInt() and 255) shl 8 or (vorbis[35].toInt() and 255)) == 300, "the setup header's size is wrong")
    check(vorbis[36] == 2.toByte() && vorbis[2] == 1.toByte(), "the Vorbis headers are not where their sizes say")
    val cookie = ByteArray(24) { 7 }
    val alac = AvCodecMap.extradata("alac", listOf(cookie))!!
    check(alac.size == 36 && String(alac, 4, 4) == "alac" && alac[3] == 36.toByte() && alac[12] == 7.toByte(), "the ALAC cookie is not inside its atom")
    check(AvCodecMap.alacAtom(alac).contentEquals(alac), "an ALAC atom is wrapped twice")
    check(AvCodecMap.extradata("aac", emptyList()) == null, "AAC with no configuration gets extradata")

    // ---- The parameter blob, as squishav.c's read_params reads it. -----------
    val extra = byteArrayOf(9, 8, 7)
    val blob = AvCodecMap.blob(video = false, codec = "dts", extradata = extra, sampleRate = 48000, channels = 6, bitRate = 1_509_000L)
    val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
    check(String(blob, 0, 4) == "SQP1", "the blob does not start SQP1")
    b.position(4)
    val ints = IntArray(30) { b.int }
    check(ints[0] == 1, "a sound blob is not AVMEDIA_TYPE_AUDIO")
    check(ints[18] == 48000, "the sample rate is not the 19th number: ${ints[18]}")
    check(ints[20] == 6, "the channel count is not the 21st number: ${ints[20]}")
    check(ints[19] == 0, "the channel order is not unspecified")
    check(ints[28] == 1 && ints[29] == 1_000_000, "the time base is not microseconds")
    check(b.long == 1_509_000L, "the bitrate is not first of the 64-bit numbers")
    check(b.long == 0L, "a channel mask was invented")
    val nameLength = b.int
    check(nameLength == 3 && String(blob, b.position(), 3) == "dts", "the codec name is not after the numbers")
    b.position(b.position() + nameLength)
    check(b.int == 3, "the extradata's length is not before it")
    check(blob.size == 4 + 120 + 16 + 4 + 3 + 4 + 3, "the blob is ${blob.size} bytes")
    check(blob.copyOfRange(blob.size - 3, blob.size).contentEquals(extra), "the extradata is not last")
    val video = ByteBuffer.wrap(AvCodecMap.blob(video = true, codec = "hevc", extradata = null, width = 1920, height = 1080)).order(ByteOrder.LITTLE_ENDIAN)
    video.position(4)
    val vi = IntArray(30) { video.int }
    check(vi[0] == 0 && vi[7] == 1920 && vi[8] == 1080, "a picture blob does not carry its type and size")
    check(vi[5] == -99 && vi[6] == -99, "the profile and level are not FFmpeg's unknown")

    // ---- Native handles: a tagged arm64 pointer is a handle, not an error. --
    check(!com.squish.app.av.AvHandles.failed(0xb4000072c7d869e0uL.toLong()), "a tagged heap pointer (0xb4...) is taken for an error")
    check(!com.squish.app.av.AvHandles.failed(0x00000072c7d869e0L), "an untagged pointer is taken for an error")
    check(com.squish.app.av.AvHandles.failed(0L), "a null handle is taken for a handle")
    check(com.squish.app.av.AvHandles.failed(-1094995529L), "AVERROR_INVALIDDATA is taken for a handle")
    check(com.squish.app.av.AvHandles.failed(-12L), "AVERROR(ENOMEM) is taken for a handle")

    if (problems.isEmpty()) println("AvChecks: all passed") else {
        problems.forEach { println("FAIL: $it") }
        System.exit(1)
    }
}
