package com.squish.app.media

/**
 * How far down a picture may be sampled on the way into memory.
 *
 * ImageDecoder and BitmapFactory both sample by powers of two, and the number
 * decides how much heap a decode takes: a sample of 2 is a quarter of the
 * pixels, a sample of 4 a sixteenth. Two things have to hold at once, and
 * every loop in the app that got this wrong got it wrong by only checking one
 * of them:
 *
 *  - the decode must not land *under* the size that will be kept, or the
 *    picture is scaled back up and comes out soft;
 *  - it must not land far *over* it either, or a panorama is held whole.
 *
 * The mistake both times was solving against the short side when the kept size
 * is bounded on the long side too. A 12000x1200 panorama has a short side of
 * 1200, so a loop that halves "while the short side is still over 1080" never
 * halves at all, and 57 MB of ARGB_8888 is allocated for a picture that will
 * be kept at 5.9 MB - with the OutOfMemoryError swallowed by the runCatching
 * round the decode, so the photo is silently refused after taking the heap
 * down with it.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/PictureSampleChecks.kt).
 */
object PictureSample {

    /**
     * The fraction of [width] x [height] that will be kept, given a bound on
     * each side. Never above 1: a small picture is not blown up.
     */
    fun fitScale(width: Int, height: Int, maxShortSide: Int, maxLongSide: Int): Float = minOf(
        1f,
        maxShortSide.toFloat() / minOf(width, height).coerceAtLeast(1),
        maxLongSide.toFloat() / maxOf(width, height).coerceAtLeast(1)
    )

    /** The kept size itself, at [fitScale]. */
    fun fit(width: Int, height: Int, maxShortSide: Int, maxLongSide: Int): Pair<Int, Int> {
        val scale = fitScale(width, height, maxShortSide, maxLongSide)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /**
     * The sample size for a decode that will then be scaled to
     * [fit]: the largest power of two that does not take the picture below
     * what is kept. The same fraction applies to both sides, so there is one
     * number to stay at or under - the reciprocal of [fitScale].
     */
    fun forFit(width: Int, height: Int, maxShortSide: Int, maxLongSide: Int): Int {
        val room = maxOf(
            1f,
            minOf(width, height).coerceAtLeast(1).toFloat() / maxShortSide,
            maxOf(width, height).coerceAtLeast(1).toFloat() / maxLongSide
        )
        var sample = 1
        while (sample * 2 <= room) sample *= 2
        return sample
    }

    /**
     * The sample size for a decode bounded on the long side alone - a
     * thumbnail, a filmstrip tile, a picture handed to the preview to draw.
     * The result is at least [maxSide] on its long side, so nothing is drawn
     * from fewer pixels than it shows.
     */
    fun forLongSide(width: Int, height: Int, maxSide: Int): Int {
        val long = maxOf(width, height).coerceAtLeast(1)
        var sample = 1
        while (long / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
