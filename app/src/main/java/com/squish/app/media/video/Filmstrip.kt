package com.squish.app.media.video

/**
 * Where to sample a clip's thumbnails, and how many are worth having.
 *
 * Kept free of the framework so the arithmetic can be executed rather than
 * argued about. Everything that touches a decoder lives in `FilmstripLoader`;
 * everything that decides *what* to decode lives here.
 */
object FilmstripPlan {

    /** A tile's nominal width. Narrower than the lane is tall, so it reads as film. */
    const val TILE_DP = 34f

    /**
     * The most tiles one clip may ask for, however wide it is drawn.
     *
     * Without a ceiling a clip zoomed to four thousand dp would ask for a hundred
     * and twenty decodes and hold a hundred and twenty bitmaps, for a strip that
     * nobody can see more than a screenful of. Past the cap the tiles simply
     * stretch: the strip still reads, and the cost stops growing.
     */
    const val MAX_TILES = 20

    /** Below this a clip is all label and handles; a thumbnail would be a smear. */
    const val MIN_WIDTH_DP = 56f

    /**
     * Cache buckets, in milliseconds.
     *
     * Thumbnails are addressed by a rounded time so that trimming a clip by a
     * frame, or zooming a notch, asks for the times it already has instead of a
     * fresh set that happen to be two milliseconds away. Nothing is lost by it:
     * `OPTION_CLOSEST_SYNC` snaps to the nearest keyframe regardless, which on
     * most footage is a coarser grid than this one.
     */
    const val QUANTUM_MS = 200L

    /** How many tiles fit across [widthDp], or zero if the clip is too narrow to bother. */
    fun tileCount(widthDp: Float, maxTiles: Int = MAX_TILES): Int {
        if (widthDp < MIN_WIDTH_DP) return 0
        return (widthDp / TILE_DP).toInt().coerceIn(1, maxTiles)
    }

    /**
     * The source times to decode, one per tile, each at its tile's centre.
     *
     * Centres rather than edges because a tile shows the moment it covers: a
     * frame taken at the left edge of the last tile is a frame from before the
     * span it is sitting over, and on a clip with a hard cut at the end that
     * reads as the strip being a tile behind.
     */
    fun tileTimes(sourceInMs: Long, sourceOutMs: Long, tiles: Int): List<Long> {
        if (tiles <= 0) return emptyList()
        val start = sourceInMs.coerceAtLeast(0L)
        val span = (sourceOutMs - start).coerceAtLeast(0L)
        if (span == 0L) return List(tiles) { quantize(start) }
        return (0 until tiles).map { i ->
            quantize(start + span * (2 * i + 1) / (2L * tiles))
        }
    }

    /** Rounds a source time onto the cache grid. */
    fun quantize(timeMs: Long): Long =
        (timeMs.coerceAtLeast(0L) + QUANTUM_MS / 2) / QUANTUM_MS * QUANTUM_MS

    fun key(uri: String, timeMs: Long): String = "$uri@$timeMs"
}

/**
 * A least-recently-used store with a hard budget in bytes.
 *
 * Deliberately generic over its contents and its measure, so the eviction can be
 * run on the JVM with counters standing in for bitmaps. A filmstrip cache that
 * quietly grows is the same crash as the one that batched frames - a slow one.
 *
 * Nothing is recycled on the way out. An evicted bitmap may still be on screen:
 * Compose holds it until the next frame, and recycling underneath it draws a
 * "trying to use a recycled bitmap" crash rather than a stale thumbnail. Dropping
 * the reference is enough - the collector is better placed to judge the moment
 * than this class is.
 */
class BoundedCache<T>(
    private val budgetBytes: Long,
    private val sizeOf: (T) -> Long
) {
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)
    private var used = 0L

    val bytes: Long get() = synchronized(this) { used }
    val size: Int get() = synchronized(this) { entries.size }

    fun get(key: String): T? = synchronized(this) { entries[key] }

    fun put(key: String, value: T) = synchronized(this) {
        val cost = sizeOf(value).coerceAtLeast(0L)
        // Bigger than the whole budget: keeping it would evict everything else and
        // then evict itself. Refusing is the honest answer.
        if (cost > budgetBytes) return@synchronized
        entries.remove(key)?.let { used -= sizeOf(it) }
        entries[key] = value
        used += cost
        val iterator = entries.entries.iterator()
        while (used > budgetBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            used -= sizeOf(oldest.value)
            iterator.remove()
        }
    }

    fun clear() = synchronized(this) {
        entries.clear()
        used = 0L
    }
}
