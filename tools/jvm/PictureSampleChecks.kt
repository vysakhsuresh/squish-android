import com.squish.app.media.PictureSample
import kotlin.system.exitProcess

/*
 * How far a picture may be sampled on the way into memory, executed.
 *
 * The fault: overlayFromImage solved its sample size against the picture's
 * SHORT side while the size it keeps is bounded on the long side too. For
 * anything wider than 3840/1080 - a panorama, a long screenshot - the short
 * side is already under 1080, so the halving never happened and the whole
 * picture was decoded as ARGB_8888. A 12000x1200 panorama took 57 MB for a
 * picture kept at 5.9 MB, and the OutOfMemoryError is swallowed by the
 * runCatching round the decode, so the photo was silently refused after
 * taking the heap down with it. ThumbnailExtractor.cover records the same
 * mistake, the same way round, being fixed there - which is why the check
 * below is the invariant and not the two numbers.
 */

private val problems = mutableListOf<String>()
private fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

/** What StillClips keeps an overlay picture at. */
private const val SHORT = 1080
private const val LONG = 3840

private val SIZES = listOf(
    // Ordinary photos.
    4000 to 3000, 3000 to 4000, 4032 to 3024, 8192 to 6144, 1080 to 1080, 640 to 480,
    // Panoramas and long screenshots - the shapes that were decoded whole.
    12000 to 1200, 15000 to 1500, 10944 to 2016, 1080 to 19200, 2016 to 10944,
    // Smaller than the bounds: nothing to do.
    800 to 600, 100 to 4000, 1 to 1,
    // Right on the bounds.
    3840 to 1080, 1080 to 3840, 3841 to 1081
)

fun main() {
    SIZES.forEach { (w, h) ->
        val sample = PictureSample.forFit(w, h, SHORT, LONG)
        val (keptW, keptH) = PictureSample.fit(w, h, SHORT, LONG)
        val decodedW = (w / sample).coerceAtLeast(1)
        val decodedH = (h / sample).coerceAtLeast(1)

        // 1. A power of two, and never zero.
        check(sample >= 1 && (sample and (sample - 1)) == 0, "${w}x$h gave a sample of $sample")

        // 2. The decode never lands under what is kept - the picture would be
        //    scaled back up and come out soft.
        check(
            decodedW >= keptW && decodedH >= keptH,
            "${w}x$h decodes to ${decodedW}x$decodedH, under the ${keptW}x$keptH it keeps"
        )

        // 3. And never far over it. The sample is the largest power of two at
        //    or under the reciprocal of the kept fraction, so the decode is
        //    within a doubling of the kept size on each side: at most four
        //    times its pixels. This is the one the old loop broke - a
        //    12000x1200 panorama decoded 14.4 megapixels for the 1.5 it keeps,
        //    ten times over, and 15000x1500 was ninety megabytes.
        val decodedPixels = decodedW.toLong() * decodedH
        val keptPixels = keptW.toLong() * keptH
        check(
            decodedPixels <= keptPixels * 4,
            "${w}x$h decodes ${decodedPixels / 1000}k pixels for the ${keptPixels / 1000}k it keeps " +
                "(${decodedPixels.toFloat() / keptPixels} times over)"
        )

        // 4. The kept size honours both bounds.
        check(minOf(keptW, keptH) <= SHORT, "${w}x$h keeps a short side of ${minOf(keptW, keptH)}")
        check(maxOf(keptW, keptH) <= LONG, "${w}x$h keeps a long side of ${maxOf(keptW, keptH)}")

        // 5. A picture inside both bounds is kept whole.
        if (maxOf(w, h) <= LONG && minOf(w, h) <= SHORT) {
            check(keptW == w && keptH == h, "${w}x$h was shrunk to ${keptW}x$keptH although it fits")
            check(sample == 1, "${w}x$h was sampled by $sample although it fits")
        }
    }

    // The panoramas, by the numbers, so the arithmetic is on the record.
    check(PictureSample.forFit(12000, 1200, SHORT, LONG) == 2, "a 12000x1200 panorama samples by ${PictureSample.forFit(12000, 1200, SHORT, LONG)}")
    check(PictureSample.fit(12000, 1200, SHORT, LONG) == (3840 to 384), "a 12000x1200 panorama keeps ${PictureSample.fit(12000, 1200, SHORT, LONG)}")
    check(PictureSample.forFit(15000, 1500, SHORT, LONG) == 2, "a 15000x1500 panorama samples by ${PictureSample.forFit(15000, 1500, SHORT, LONG)}")
    // And an ordinary photo samples exactly as it did before, since the short
    // side is the binding one there: nothing that worked has moved.
    check(PictureSample.forFit(4032, 3024, SHORT, LONG) == 2, "a 12 MP photo samples by ${PictureSample.forFit(4032, 3024, SHORT, LONG)}")
    check(PictureSample.forFit(8192, 6144, SHORT, LONG) == 4, "a 50 MP photo samples by ${PictureSample.forFit(8192, 6144, SHORT, LONG)}")

    // ---- The long-side-only decode: a thumbnail, a tile, the preview. -------
    //
    // Bounded on one side, so the rule is simply that it is at least maxSide
    // across its long side - nothing is drawn from fewer pixels than it shows -
    // and within a doubling of it.
    run {
        listOf(480, 120, 1920).forEach { maxSide ->
            SIZES.forEach { (w, h) ->
                val sample = PictureSample.forLongSide(w, h, maxSide)
                val long = maxOf(w, h)
                val decoded = (long / sample).coerceAtLeast(1)
                check(sample >= 1 && (sample and (sample - 1)) == 0, "${w}x$h at $maxSide gave a sample of $sample")
                if (long >= maxSide) {
                    check(decoded >= maxSide, "${w}x$h at $maxSide decodes a long side of $decoded, under $maxSide")
                    check(decoded < maxSide * 2, "${w}x$h at $maxSide decodes a long side of $decoded, over twice $maxSide")
                } else {
                    check(sample == 1, "${w}x$h is already under $maxSide and was sampled by $sample")
                }
            }
        }
        // The panorama the thumbnail loop used to hold at fourteen megabytes.
        check(PictureSample.forLongSide(12000, 1200, 480) == 16, "a panorama's thumbnail samples by ${PictureSample.forLongSide(12000, 1200, 480)}")
        check(12000 / 16 == 750, "the panorama's thumbnail is not 750 across")
    }

    if (problems.isEmpty()) {
        println("PictureSampleChecks: a decode lands at or just over what it keeps, on every shape")
    } else {
        println("FAIL (${problems.size})")
        problems.take(20).forEach { println("  - $it") }
        exitProcess(1)
    }
}
