import com.squish.app.media.ConversionRules
import com.squish.app.media.YuvDataSpace
import kotlin.math.abs

/**
 * The import conversion's arithmetic (ConversionRules) and the colour space a
 * software-decoded frame is handed over in (YuvDataSpace), executed.
 *
 *   sh tools/jvm/jc.sh ConversionChecks app/src/main/java/com/squish/app/media/ConversionRules.kt \
 *      app/src/main/java/com/squish/app/media/YuvDataSpace.kt tools/jvm/ConversionChecks.kt
 */
private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // ---- What is converted. -------------------------------------------------
    check(!ConversionRules.plan(false, false).needed, "a file the phone decodes is converted")
    check(ConversionRules.plan(true, false).let { it.video && !it.audio }, "software-only video re-encodes the picture and copies the sound")
    check(ConversionRules.plan(false, true).let { !it.video && it.audio }, "software-only sound re-encodes the sound and copies the picture")

    // ---- The frame: the source's own unless the encoder cannot write it. ----
    check(ConversionRules.frame(1920, 1080, 1080) == null, "a 1080p frame under a 1080 ceiling is resampled")
    check(ConversionRules.frame(3840, 2160, 2160) == null, "a 4K frame under a 4K ceiling is resampled")
    check(ConversionRules.frame(3840, 2160, 0) == null, "an unknown ceiling resamples")
    check(ConversionRules.frame(0, 0, 1080) == null, "an unknown frame is scaled")
    val scaled = ConversionRules.frame(3840, 2160, 1080)
    check(scaled == ConversionRules.Frame(1920, 1080), "4K over a 1080 ceiling is not 1920x1080: $scaled")
    val portrait = ConversionRules.frame(2160, 3840, 1080)
    check(portrait == ConversionRules.Frame(1080, 1920), "portrait 4K over a 1080 ceiling is not 1080x1920: $portrait")
    val odd = ConversionRules.frame(4096, 2160, 1080)
    check(odd != null && odd.width % 2 == 0 && odd.height % 2 == 0 && odd.height == 1080, "a DCI frame scales to odd edges: $odd")

    // ---- The bitrate: a mezzanine, never under the source. ------------------
    val clean1080 = ConversionRules.bitrate(1920, 1080, 30f, 0L)
    check(clean1080 == (1920L * 1080 * 30 * 0.2).toInt(), "1080p30 with no source rate is not twice a clean encode: $clean1080")
    check(ConversionRules.bitrate(1920, 1080, 30f, 60_000_000L) == 60_000_000, "a 60 Mbps source is written under its own rate")
    check(ConversionRules.bitrate(7680, 4320, 60f, 0L) == ConversionRules.MAX_BPS, "8K60 is not capped")
    check(ConversionRules.bitrate(320, 240, 15f, 0L) == ConversionRules.MIN_BPS, "a tiny frame goes under the floor")
    check(ConversionRules.bitrate(1920, 1080, Float.NaN, 0L) == ConversionRules.bitrate(1920, 1080, 30f, 0L), "an unknown rate is not read as 30")
    check(ConversionRules.bitrate(1920, 1080, 120f, 0L) == ConversionRules.bitrate(1920, 1080, 60f, 0L), "120 fps is budgeted past 60")
    check(ConversionRules.sourceBps(100_000_000L, 10_000L) == 80_000_000L, "100 MB over ten seconds is not 80 Mbps")
    check(ConversionRules.sourceBps(0L, 10_000L) == 0L && ConversionRules.sourceBps(5L, 0L) == 0L, "an unknown size or length gives a rate")
    check(abs(ConversionRules.BITS_PER_PIXEL - 0.2) < 1e-9, "the mezzanine is no longer twice a clean encode's bits per pixel")

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
        println("PASS: conversion plan, frame and bitrate; the handed-over colour space follows the decoder's matrix, the size, the HDR transfer and the range")
    } else {
        println("FAIL (${problems.size}):")
        problems.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
