package com.squish.app.media.gif

/**
 * The size a GIF is written at, from the shape of the footage.
 *
 * At most [MAX_WIDTH] across and never wider than the source, both sides
 * even (the encoder's pixel buffer is indexed by width, and an odd side is the
 * kind of thing that shows as a skewed last row), and never under 2.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/GifChecks.kt). The
 * shape it is given comes from the file's header rather than a decoded frame:
 * reading it off frame zero meant allocating a full-resolution bitmap - 33 MB
 * for a 4K edit - for two numbers.
 */
object GifSize {

    /** At most this across: a GIF is a preview, and a 480-wide one is already megabytes. */
    const val MAX_WIDTH = 480

    fun of(sourceWidth: Int, sourceHeight: Int, maxWidth: Int = MAX_WIDTH): Pair<Int, Int>? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val w = even(maxWidth.coerceAtMost(sourceWidth))
        val h = even((w.toLong() * sourceHeight / sourceWidth).toInt())
        return w to h
    }

    private fun even(side: Int): Int = (side - side % 2).coerceAtLeast(2)
}
