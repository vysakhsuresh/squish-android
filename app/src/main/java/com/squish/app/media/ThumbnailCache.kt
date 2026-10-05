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
     * The frame of [uri] at [timeMs], from memory, from disk, or decoded and
     * kept; null when the file cannot be read.
     *
     * A null is deliberately *not* remembered. It would save a retriever open
     * per composition on a file that has gone - the callers all ask from a
     * `LaunchedEffect` keyed on the uri, so it is one open per entry and not
     * one per frame - and it would cost correctness: nothing calls [evict] when
     * a missing file is relinked, so a remembered miss would outlive the thing
     * that caused it and the picture would stay blank on a project that works
     * again. If a negative cache is ever wanted, the relink has to clear it
     * first.
     */
    suspend fun frame(context: Context, uri: Uri, timeMs: Long): Bitmap? {
        val key = key(uri, timeMs)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val file = diskFile(app, key)
            val onDisk = if (file.exists()) runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() else null
            if (onDisk != null) {
                memory.put(key, onDisk)
                return@withContext onDisk
            }
            val decoded = decoding.withLock {
                memory.get(key) ?: ThumbnailExtractor.cover(app, uri, timeMs)
            } ?: return@withContext null
            memory.put(key, decoded)
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            }
            decoded
        }
    }

    /** Forgets what is kept for [uri], memory and disk - for a file that was deleted or replaced. */
    fun evict(context: Context, uri: Uri, timeMs: Long) {
        val key = key(uri, timeMs)
        memory.remove(key)
        runCatching { diskFile(context.applicationContext, key).delete() }
    }

    /** Everything on disk, for Settings' storage card. */
    fun diskBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearDisk(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
        memory.evictAll()
    }

    private fun key(uri: Uri, timeMs: Long): String = "$uri@$timeMs"

    private fun dir(context: Context): File = File(context.cacheDir, "thumbs")

    /** Named by a hash of the key: a content URI is not a file name. */
    private fun diskFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir(context), digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }
}
