package com.squish.app.media

/**
 * How much video the phone's decoder can play at once, and what that means for
 * the edit (EDGE_PLAN C4).
 *
 * A codec's own limit on *instances* says little: the moto g84's H.264 decoder
 * allows sixteen, but its throughput is 489,600 macroblocks a second, which is
 * one 1080p stream at 60 fps or two at 30. Eight players of 1080p30 footage ask
 * for four times that, and the preview stutters or goes black however many
 * instances are allowed. So the budget is counted in blocks a second, the unit
 * the codec states (`VideoCapabilities.getBlocksPerSecondRange`), and a 540p
 * light copy is a quarter of a 1080p stream.
 *
 * Free of Android so it is executed on the JVM (tools/jvm/DecoderBudgetChecks.kt).
 */
object DecoderBudget {

    /** A 16 x 16 block, what a codec counts. */
    private const val BLOCK = 16

    /** What one stream of [width] x [height] at [fps] costs a second, in blocks. */
    fun load(width: Int, height: Int, fps: Float): Long {
        if (width <= 0 || height <= 0) return 0L
        val blocks = ((width + BLOCK - 1) / BLOCK).toLong() * ((height + BLOCK - 1) / BLOCK)
        return (blocks * fps.coerceAtLeast(1f).toDouble()).toLong()
    }

    /** A light copy's cost: ProxyEngine writes 540 on the short edge, at the source's rate. */
    fun proxyLoad(width: Int, height: Int, fps: Float): Long {
        if (width <= 0 || height <= 0) return 0L
        val short = minOf(width, height)
        if (short <= PROXY_SHORT_EDGE) return load(width, height, fps)
        val scale = PROXY_SHORT_EDGE.toDouble() / short
        return load((width * scale).toInt(), (height * scale).toInt(), fps)
    }

    const val PROXY_SHORT_EDGE = 540

    /** The fewest footage rows offered, whatever the phone: what every phone has had. */
    const val MIN_FOOTAGE_ROWS = 3

    /** The most: every overlay row. */
    const val MAX_FOOTAGE_ROWS = 6

    /**
     * Rows of footage the phone can play beside the main track, from its decoder's
     * [capacity] in blocks a second: the two main rolls (both decode through a
     * transition) and each row as a 540p30 copy, inside the stated figure.
     * Unknown (0) is the old three. The moto's 489,600 hold eight 540p30
     * copies of 61,200: two rolls and six rows.
     */
    fun footageRows(capacity: Long): Int {
        if (capacity <= 0L) return MIN_FOOTAGE_ROWS
        val each = proxyLoad(1920, 1080, 30f)
        val usable = capacity - 2 * each
        return (usable / each).toInt().coerceIn(MIN_FOOTAGE_ROWS, MAX_FOOTAGE_ROWS)
    }

    /** One piece of footage on the edit: where it plays, its file, its full and its light cost, and whether it can have a copy. */
    data class Stream(
        val key: String,
        val startMs: Long,
        val endMs: Long,
        val load: Long,
        val lightLoad: Long
    )

    /**
     * The files that need their light copy so that no moment of the edit asks
     * the decoder for more than [capacity]: walked moment by moment, at each one
     * over the budget the heaviest file still at full cost there is given its
     * copy, until the moment fits or every file there has one. A file that never
     * plays in an overloaded moment keeps its original - a copy is a whole
     * re-encode, and is not made for nothing.
     */
    fun needingCopies(streams: List<Stream>, capacity: Long): Set<String> {
        if (capacity <= 0L || streams.isEmpty()) return emptySet()
        val light = mutableSetOf<String>()
        val edges = streams.flatMap { listOf(it.startMs, it.endMs) }.distinct().sorted()
        for (i in 0 until edges.size - 1) {
            val mid = (edges[i] + edges[i + 1]) / 2
            if (edges[i] == edges[i + 1]) continue
            val here = streams.filter { it.startMs <= mid && mid < it.endMs }
            fun total() = here.sumOf { if (it.key in light) it.lightLoad else it.load }
            while (total() > capacity) {
                val next = here.filter { it.key !in light && it.lightLoad < it.load }.maxByOrNull { it.load - it.lightLoad } ?: break
                light += next.key
            }
        }
        return light
    }
}
