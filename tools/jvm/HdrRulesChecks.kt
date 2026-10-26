import com.squish.app.media.HdrRules
import com.squish.app.media.HdrRules.Clip
import com.squish.app.media.HdrRules.Mode
import kotlin.system.exitProcess

/*
 * EDGE_PLAN B4: an HDR10 (PQ) file decoded by Squish's FFmpeg - on the moto
 * every 10-bit HEVC is - reached Media3 labelled PQ and came out of its
 * OpenGL tone map with the red, magenta and blue bars black and the rest blown,
 * in the light copy, so in the preview, and in the file (27 Oct, read off the
 * copy). The FFmpeg decoder has its own tone map, the one the filmstrip's
 * pictures are right through, so such a file is handed on already SDR: the
 * decoder is asked to tone-map (Transformer's MediaCodec mode) and says so.
 * HLG through the same path is left as it was - seen right through Media3's
 * tone map and the lift fitted to it (25 Oct) - and so is anything the
 * phone's own decoders read.
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    val sdr = Clip(hdr = false, hlg = false, software = false)
    val sdrSoftware = Clip(hdr = false, hlg = false, software = true)
    val pqSoftware = Clip(hdr = true, hlg = false, software = true)
    val pqPhone = Clip(hdr = true, hlg = false, software = false)
    val hlgSoftware = Clip(hdr = true, hlg = true, software = true)
    val hlgPhone = Clip(hdr = true, hlg = true, software = false)

    check(HdrRules.decoderToneMaps(pqSoftware), "a PQ file FFmpeg decodes is not tone-mapped in the decoder")
    check(!HdrRules.decoderToneMaps(hlgSoftware), "an HLG file FFmpeg decodes is tone-mapped in the decoder - it was right through Media3's")
    check(!HdrRules.decoderToneMaps(pqPhone), "a PQ file the phone decodes is taken off its own decoder")
    check(!HdrRules.decoderToneMaps(sdrSoftware) && !HdrRules.decoderToneMaps(sdr), "an SDR file is tone-mapped")

    check(HdrRules.mode(listOf(pqSoftware), keepHdr = false) == Mode.DECODER, "a PQ file FFmpeg decodes went through Media3's tone map")
    check(HdrRules.mode(listOf(pqSoftware, sdr, sdrSoftware), keepHdr = false) == Mode.DECODER, "SDR shots beside it changed its mode")
    check(HdrRules.mode(listOf(pqSoftware), keepHdr = true) == Mode.KEEP, "Keep HDR was overridden")
    check(HdrRules.mode(listOf(pqSoftware, hlgSoftware), keepHdr = false) == Mode.OPEN_GL, "an HLG shot beside it was moved off Media3's tone map")
    check(HdrRules.mode(listOf(pqSoftware, pqPhone), keepHdr = false) == Mode.OPEN_GL, "a phone-decoded HDR shot beside it was forced onto FFmpeg")
    check(HdrRules.mode(listOf(hlgPhone), keepHdr = false) == Mode.OPEN_GL, "HLG off the phone changed mode")
    check(HdrRules.mode(listOf(sdr, sdrSoftware), keepHdr = false) == Mode.OPEN_GL, "an SDR edit changed mode")
    check(HdrRules.mode(emptyList(), keepHdr = false) == Mode.OPEN_GL, "nothing at all changed mode")

    if (problems.isEmpty()) println("HdrRulesChecks: all checks passed")
    else { problems.forEach { println("FAIL: $it") }; exitProcess(1) }
}
