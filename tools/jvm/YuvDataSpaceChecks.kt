import com.squish.app.media.YuvDataSpace

/**
 * The colour space a software-decoded frame is handed over in (YuvDataSpace),
 * executed: every picture Squish's FFmpeg decodes reaches a player's or
 * Transformer's surface as a YUV image carrying this DataSpace.
 *
 *   sh tools/jvm/jc.sh YuvDataSpaceChecks app/src/main/java/com/squish/app/media/YuvDataSpace.kt \
 *      tools/jvm/YuvDataSpaceChecks.kt
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // ---- The colour space a frame is handed over in. -------------------------
    val sdr = YuvDataSpace.Transfer.SDR
    fun pick(cs: Int, h: Int, t: YuvDataSpace.Transfer = sdr, full: Boolean = false) = YuvDataSpace.choose(cs, h, t, full)
    // The decoder's word wins, whatever the size.
    check(pick(1, 1080).standard == YuvDataSpace.Standard.BT601, "colorspace 1 at 1080 lines is not 601")
    check(pick(2, 480).standard == YuvDataSpace.Standard.BT709, "colorspace 2 at 480 lines is not 709")
    check(pick(3, 480).standard == YuvDataSpace.Standard.BT2020, "colorspace 3 at 480 lines is not 2020")
    // No word: HD is 709, less is 601, HDR is 2020.
    check(pick(0, 720).standard == YuvDataSpace.Standard.BT709, "unknown at 720 lines is not 709")
    check(pick(0, 576).standard == YuvDataSpace.Standard.BT601, "unknown at 576 lines is not 601")
    check(pick(0, 2160, YuvDataSpace.Transfer.HLG).standard == YuvDataSpace.Standard.BT2020, "unknown HLG is not 2020")
    check(pick(0, 1080, YuvDataSpace.Transfer.PQ).standard == YuvDataSpace.Standard.BT2020, "unknown PQ is not 2020")
    // The transfer and the range pass straight through: an HLG frame said
    // to be SDR would reach the tone-mapper with nothing to map, and a
    // full-range frame said to be limited has grey for black.
    check(pick(3, 2160, YuvDataSpace.Transfer.HLG).transfer == YuvDataSpace.Transfer.HLG, "HLG is not carried")
    check(pick(3, 2160, YuvDataSpace.Transfer.PQ).transfer == YuvDataSpace.Transfer.PQ, "PQ is not carried")
    check(pick(2, 1080).transfer == sdr, "an SDR frame is not SDR")
    check(pick(2, 1080, full = true).range == YuvDataSpace.Range.FULL, "full range is not carried")
    check(pick(2, 1080, full = false).range == YuvDataSpace.Range.LIMITED, "limited range is not carried")
    // A decoder that names BT.709 for an HLG stream is believed about the
    // matrix and the transfer still travels: the two are independent.
    check(pick(2, 1080, YuvDataSpace.Transfer.HLG) == YuvDataSpace.Choice(YuvDataSpace.Standard.BT709, YuvDataSpace.Transfer.HLG, YuvDataSpace.Range.LIMITED), "matrix and transfer are not independent")

    if (problems.isEmpty()) {
        println("PASS: the handed-over colour space follows the decoder's matrix, the size, the HDR transfer and the range")
    } else {
        println("FAIL (${problems.size}):")
        problems.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
