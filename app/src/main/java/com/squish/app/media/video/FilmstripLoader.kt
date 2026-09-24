package com.squish.app.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Decodes the thumbnails a filmstrip is made of, and remembers them.
 *
 * Three things are being defended here at once. Memory, by decoding scaled and
 * capping what is kept. Responsiveness, by never letting more than a couple of
 * decoders run at once - a dozen clips coming on screen together would otherwise
 * start a dozen `MediaMetadataRetriever`s against the same file and make the
 * scroll that revealed them stutter. And wasted work, by checking the cache per
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

    /** Concurrent decoders. Two keeps a core free for the preview that is playing. */
    private val decoders = Semaphore(2)

    private val cache = BoundedCache<Bitmap>(BUDGET_BYTES) { it.byteCount.toLong() }

    fun cached(uri: Uri, timeMs: Long): Bitmap? =
        cache.get(FilmstripPlan.key(uri.toString(), timeMs))

    /**
     * Fills in whichever of [times] is not already cached, reporting each tile as
     * it arrives so the strip paints left to right instead of appearing whole.
     *
     * Cancellation is checked between tiles and the retriever is released on the
     * way out however the call ends, which is what makes this safe to hang off a
     * `LaunchedEffect` that restarts on every trim and every zoom.
     */
    suspend fun load(
        context: Context,
        uri: Uri,
        times: List<Long>,
        onTile: (index: Int, bitmap: Bitmap) -> Unit
    ) {
        val wanted = times.withIndex().filter { cached(uri, it.value) == null }
        if (wanted.isEmpty()) return

        decoders.withPermit {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    for ((index, timeMs) in wanted) {
                        if (!currentCoroutineContext().isActive) return@withContext
                        // Re-check: a neighbouring clip on the same source may have
                        // decoded this exact tile while this call waited for a permit.
                        val hit = cached(uri, timeMs)
                        if (hit != null) {
                            withContext(Dispatchers.Main) { onTile(index, hit) }
                            continue
                        }
                        val frame = runCatching {
                            retriever.getScaledFrameAtTime(
                                timeMs * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                TILE_PX,
                                TILE_PX
                            )
                        }.getOrNull() ?: continue
                        cache.put(FilmstripPlan.key(uri.toString(), timeMs), frame)
                        withContext(Dispatchers.Main) { onTile(index, frame) }
                    }
                } catch (t: Throwable) {
                    // A source that cannot be opened has no filmstrip, and that is
                    // all it means - the clip still draws, edits, trims and exports.
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }

    /** Drops every thumbnail. Called when the editor lets go of its project. */
    fun evictAll() = cache.clear()
}
