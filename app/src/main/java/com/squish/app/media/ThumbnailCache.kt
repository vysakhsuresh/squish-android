package com.squish.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * One frame per file, remembered: in memory for the list on screen, on disk
 * for the next time the app opens.
 *
 * The library, the dashboard's project grid and the drafts list each drew a
 * thumbnail by opening a fresh MediaMetadataRetriever in the row's own effect,
 * and a LazyColumn disposes rows off screen, so scrolling an eighty-item
 * library decoded a keyframe for every row each time it came back into view
 * - visible as rows popping in and the scroll stuttering. Here a frame is
 * decoded once per file and moment, kept under a memory budget, and written
 * as a small JPEG under the cache directory so a cold start draws the grid
 * from disk rather than from the footage.
 *
 * Decodes go one at a time. A screenful of rows arriving together used to
 * open a dozen retrievers against a dozen files at once, which is the very
 * contention the queue in FilmstripLoader exists to avoid.
 */
object ThumbnailCache {

    /** An eighth of the heap, as the platform's own examples size a bitmap cache. */
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(4 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val decoding = Mutex()

    /**
     * Files a frame could not be got out of, and when the attempt was made.
     *
     * A miss used not to be remembered at all, on the grounds that a
     * remembered one would outlive its cause: nothing calls [evict] when a
     * missing file is relinked, so a project that works again would have stayed
     * blank. But not remembering it costs more than that is worth. A file the
     * phone cannot decode - a 10-bit HEVC film rip, which this phone cannot
     * decode at all - was probed again on every pass of the list, and the probe
     * runs inside [decoding], the one process-wide lane, so every readable
     * row's thumbnail queued behind the failure; and the dashboard pays twice,
     * because its fallback on a null re-reads and re-parses the whole project
     * JSON and opens a file descriptor per clip.
     *
     * So it is remembered, and it expires. A relink puts a different uri under
     * the clip, which is a different key and no stale miss at all; what the
     * expiry is for is the same file becoming readable - a picker grant
     * restored, a share still being copied in - and [MISS_TTL_MS] is short
     * enough that the picture appears on the next look and long enough that a
     * scroll back and forth does not re-probe.
     */
    private val misses = LinkedHashMap<String, Long>()

    /** Half a minute: longer than a scroll, shorter than a look away. */
    private const val MISS_TTL_MS = 30_000L

    /** Bounded, so a library of unreadable files cannot grow it without end. */
    private const val MAX_MISSES = 256

    /**
     * The frame of [uri] at [timeMs], from memory, from disk, or decoded and
     * kept; null when the file cannot be read.
     */
    suspend fun frame(context: Context, uri: Uri, timeMs: Long): Bitmap? {
        val key = key(uri, timeMs)
        memory.get(key)?.let { return it }
        if (missedRecently(key)) return null
        return withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val file = diskFile(app, key)
            val onDisk = if (file.exists()) runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() else null
            if (onDisk != null) {
                memory.put(key, onDisk)
                return@withContext onDisk
            }
            val decoded = decoding.withLock {
                // Another row may have answered this while we waited for the
                // lane - including answering that there is nothing here.
                memory.get(key) ?: if (missedRecently(key)) null else ThumbnailExtractor.cover(app, uri, timeMs)
            }
            if (decoded == null) {
                noteMiss(key)
                return@withContext null
            }
            memory.put(key, decoded)
            // Through a .part and renamed, like every other picture this app
            // writes. BitmapFactory.decodeFile hands back a *partial* bitmap
            // for a truncated JPEG rather than null - AOSP accepts
            // kIncompleteInput - so a write that died part way (the process
            // going, a full disk) left a half-drawn thumbnail that `exists()`
            // was happy with and that was served for ever, with nothing but
            // Settings' "Clear preview thumbnails" to get rid of it. The same
            // lesson StillClips.blank records in so many words.
            runCatching {
                file.parentFile?.mkdirs()
                val partial = File(file.absolutePath + ".part")
                partial.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (partial.length() <= 0L || !partial.renameTo(file)) partial.delete()
            }
            decoded
        }
    }

    private fun missedRecently(key: String): Boolean = synchronized(misses) {
        val at = misses[key] ?: return false
        if (System.currentTimeMillis() - at <= MISS_TTL_MS) return true
        misses.remove(key)
        false
    }

    private fun noteMiss(key: String) = synchronized(misses) {
        misses.remove(key)
        misses[key] = System.currentTimeMillis()
        while (misses.size > MAX_MISSES) misses.remove(misses.keys.first())
    }

    /** Forgets what is kept for [uri], memory and disk - for a file that was deleted or replaced. */
    fun evict(context: Context, uri: Uri, timeMs: Long) {
        val key = key(uri, timeMs)
        memory.remove(key)
        synchronized(misses) { misses.remove(key) }
        runCatching { diskFile(context.applicationContext, key).delete() }
    }

    /** Everything on disk, for Settings' storage card. */
    fun diskBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearDisk(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
        memory.evictAll()
        // Asked for by somebody who wants the pictures made again - the files a
        // frame could not be got out of included.
        synchronized(misses) { misses.clear() }
    }

    private fun key(uri: Uri, timeMs: Long): String = "$uri@$timeMs"

    private fun dir(context: Context): File = File(context.cacheDir, "thumbs")

    /** Named by a hash of the key: a content URI is not a file name. */
    private fun diskFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir(context), digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }
}
