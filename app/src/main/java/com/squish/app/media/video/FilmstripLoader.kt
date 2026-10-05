package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import com.squish.app.media.StillClips
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Decodes the thumbnails a filmstrip is made of, and remembers them.
 *
 * Three things are being defended here at once. Memory, by decoding scaled and
 * capping what is kept. Responsiveness, by decoding on one queue - a dozen clips
 * coming on screen together would otherwise start a dozen
 * `MediaMetadataRetriever`s against the same file and make the scroll that
 * revealed them stutter. And wasted work, by checking the cache per
 * tile rather than per clip, so a trim that shifts a clip by half a second
 * re-decodes the tiles that moved and none of the ones that did not.
 */
object FilmstripLoader {

    /**
     * How much of the heap the whole strip may hold.
     *
     * A tile is at most [TILE_PX] square at four bytes a pixel, so this is on the
     * order of a thousand tiles - far more than any timeline shows at once, and
     * still a small enough number that a long session cannot creep past it.
     */
    private const val BUDGET_BYTES = 12L * 1024 * 1024

    /**
     * The box a thumbnail is decoded into.
     *
     * A lane is 54dp tall, so even on a three-times-density screen 128 pixels is
     * more than the tile can show. Decoding at source size instead would be the
     * filmstrip's own version of the large-video crash: a single 4K frame is 33MB
     * and a strip holds dozens.
     */
    private const val TILE_PX = 128

    private val cache = BoundedCache<Bitmap>(BUDGET_BYTES) { it.byteCount.toLong() }

    fun cached(uri: Uri, timeMs: Long): Bitmap? =
        cache.get(FilmstripPlan.key(uri.toString(), tileTime(uri, timeMs)))

    /**
     * The frame of [uri] decoded nearest [timeMs], for a tile whose own frame is
     * still on its way. Drawn empty, a tile showed the lane's colour, and zoomed
     * in - where every quarter screen of scrolling asks for a fresh set of
     * times - a clip read as a bare purple block until the decoder caught up.
     */
    fun nearest(uri: Uri, timeMs: Long): Bitmap? {
        val times = synchronized(decoded) { decoded[uri.toString()]?.let { java.util.TreeSet(it) } } ?: return null
        val t = tileTime(uri, timeMs)
        val below = times.headSet(t, true).descendingIterator()
        val above = times.tailSet(t, false).iterator()
        var lo = if (below.hasNext()) below.next() else null
        var hi = if (above.hasNext()) above.next() else null
        while (lo != null || hi != null) {
            val pick = when {
                lo == null -> hi!!
                hi == null -> lo
                t - lo <= hi - t -> lo
                else -> hi
            }
            cache.get(FilmstripPlan.key(uri.toString(), pick))?.let { return it }
            // Evicted since: the next nearest.
            if (pick == lo) lo = if (below.hasNext()) below.next() else null
            else hi = if (above.hasNext()) above.next() else null
        }
        return null
    }

    /** The times decoded for each file, so [nearest] can find a neighbour. */
    private val decoded = HashMap<String, java.util.TreeSet<Long>>()

    private fun noteDecoded(uri: Uri, timeMs: Long) {
        synchronized(decoded) { decoded.getOrPut(uri.toString()) { java.util.TreeSet() }.add(timeMs) }
    }

    /**
     * A photo on an overlay row is one picture however long it runs, so every
     * tile of it is the same one, decoded once.
     */
    // A photo on the main track too: it is rendered to a video file whose every
    // frame is the same picture, and a tile per moment decoded it again and again.
    private fun tileTime(uri: Uri, timeMs: Long): Long =
        if (StillClips.isStill(uri) || com.squish.app.timeline.isRenderedStill(uri.toString())) 0L else timeMs

    /** Bumped on the main thread each time a thumbnail lands, so strips waiting on one redraw. */
    val arrivals = mutableIntStateOf(0)

    /**
     * How many tiles may wait at once. Past it the oldest asks are dropped: they
     * were for a part of the strip that has most likely scrolled away.
     *
     * Which is true of a scroll and false of a first layout, where the "oldest"
     * ask is a row that is on screen right now. Four rows of footage - the
     * budget B8 sets - compose in one main-thread pass and ask for up to
     * MAX_TILES each before the worker has drained a single tile, so at 48 the
     * top overlay row's asks were all thrown away; and a dropped ask left no
     * cache entry, nothing in `decoded` and no record that it was ever wanted,
     * so that row drew as bare lane colour until a scroll or a pinch changed
     * its times. Four rows at MAX_TILES is the number here, and the asker
     * re-asks as tiles land, so a drop under a real scroll heals itself.
     */
    private const val MAX_PENDING = 4 * FilmstripPlan.MAX_TILES

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tiles still to decode, oldest first. Guarded by itself, as is [worker]. */
    private val pending = LinkedHashMap<String, Pair<Uri, Long>>()
    private var worker: Job? = null

    /**
     * Asks for [times] to be decoded, and returns at once.
     *
     * This used to be a suspend call hung off each strip's `LaunchedEffect`, keyed
     * on the tile times - so it restarted whenever they changed. Zoomed in with
     * playback scrolling the strip, they change every frame: each restart paid
     * for opening the file, was cancelled before decoding a single tile, and the
     * strip stayed blank for as long as it moved. Now the asks go on one queue
     * that outlives them, newest first so what just scrolled into view comes
     * before what has left, with the file kept open from one tile to the next.
     * One decoder, which also leaves the cores to the preview that is playing.
     */
    fun request(context: Context, uri: Uri, times: List<Long>) {
        val app = context.applicationContext
        synchronized(pending) {
            times.map { tileTime(uri, it) }.distinct().forEach { t ->
                val key = FilmstripPlan.key(uri.toString(), t)
                if (cache.get(key) != null) return@forEach
                pending.remove(key)
                pending[key] = uri to t
            }
            while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
            if (worker == null && pending.isNotEmpty()) worker = scope.launch { drain(app) }
        }
    }

    private suspend fun drain(context: Context) {
        var retriever: MediaMetadataRetriever? = null
        var openUri: Uri? = null
        try {
            while (true) {
                val next = synchronized(pending) {
                    val newest = pending.entries.lastOrNull()
                    if (newest == null) {
                        worker = null
                        null
                    } else {
                        pending.remove(newest.key)
                        newest.key to newest.value
                    }
                } ?: break
                val (key, ask) = next
                val (uri, timeMs) = ask
                if (cache.get(key) != null) continue
                // A picture, not footage: no retriever reads a PNG.
                if (StillClips.isStill(uri)) {
                    StillClips.previewBitmap(context, uri, TILE_PX)?.let { picture ->
                        cache.put(key, picture)
                        noteDecoded(uri, timeMs)
                        withContext(Dispatchers.Main) { arrivals.intValue++ }
                    }
                    continue
                }
                if (uri != openUri) {
                    runCatching { retriever?.release() }
                    openUri = uri
                    // A source that cannot be opened has no filmstrip, and that is
                    // all it means - the clip still draws, edits, trims and exports.
                    retriever = runCatching { MediaMetadataRetriever().apply { setDataSource(context, uri) } }.getOrNull()
                }
                val frame = runCatching {
                    retriever?.getScaledFrameAtTime(
                        timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        TILE_PX,
                        TILE_PX
                    )
                }.getOrNull() ?: continue
                cache.put(key, frame)
                noteDecoded(uri, timeMs)
                withContext(Dispatchers.Main) { arrivals.intValue++ }
            }
        } finally {
            runCatching { retriever?.release() }
            // Normally the loop has already handed the queue back. If it ended any
            // other way, let the next request start a fresh worker - but only if
            // no newer one has taken over in the meantime.
            val self = currentCoroutineContext()[Job]
            synchronized(pending) { if (worker === self) worker = null }
        }
    }

    /**
     * Drops every thumbnail. Called when the editor lets go of its project.
     *
     * The queue and the worker go with it. They used not to: the scope is the
     * process's, so the worker carried on pulling the closed project's asks -
     * up to MAX_PENDING of them, a retriever per distinct file - and put every
     * result back into the cache it had just been told to empty, competing for
     * the IO pool with the dashboard the user had navigated to and bumping
     * [arrivals] on the main looper for a strip that was gone. The tiles then
     * sat in the cache until some *later* editor session's onCleared.
     *
     * The worker clears `worker` itself on the way out only if it is still the
     * current one, so nulling it here is safe: a request arriving after this
     * starts a fresh one.
     */
    fun evictAll() {
        val stopping = synchronized(pending) {
            pending.clear()
            val job = worker
            worker = null
            job
        }
        stopping?.cancel()
        cache.clear()
        synchronized(decoded) { decoded.clear() }
    }
}
