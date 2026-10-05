package com.squish.app.media.gif

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/**
 * A video as a looping GIF in the gallery (Pictures/Squish): its first
 * [MAX_SECONDS] at [FPS] frames a second, [WIDTH] pixels wide - the size a GIF
 * is shared at, and small enough to send. Frames come from the platform's
 * retriever, exact ones, so the motion is even.
 */
object GifMaker {

    const val FPS = 12
    const val WIDTH = 480
    const val MAX_SECONDS = 8

    /** The GIF's gallery URI, or null when the video could not be read or the gallery refused it. [progress] runs 0..1. */
    suspend fun make(context: Context, video: Uri, name: String, progress: (Float) -> Unit = {}): Uri? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val temp = File(context.cacheDir, "gif-${System.currentTimeMillis()}.gif")
        try {
            retriever.setDataSource(context, video)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext null
            val probe = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@withContext null
            val w = WIDTH.coerceAtMost(probe.width).let { it - it % 2 }
            val h = (w.toLong() * probe.height / probe.width).toInt().coerceAtLeast(2).let { it - it % 2 }
            probe.recycle()
            val spanMs = minOf(durationMs, MAX_SECONDS * 1000L)
            val frames = (spanMs * FPS / 1000).toInt().coerceAtLeast(1)
            val pixels = IntArray(w * h)
            temp.outputStream().buffered().use { out ->
                val encoder = GifEncoder(out, w, h)
                for (i in 0 until frames) {
                    ensureActive()
                    val atUs = i * 1_000_000L / FPS
                    val frame = retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h) ?: continue
                    val scaled = if (frame.width == w && frame.height == h) frame
                    else android.graphics.Bitmap.createScaledBitmap(frame, w, h, true).also { frame.recycle() }
                    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                    scaled.recycle()
                    // In hundredths spread so they add up: 12 fps is 8, 8, 9, ... not 8 every time (4% fast).
                    encoder.addFrame(pixels, (Math.round((i + 1) * 100.0 / FPS) - Math.round(i * 100.0 / FPS)).toInt() * 10)
                    progress((i + 1f) / frames)
                }
                encoder.finish()
            }
            publish(context, temp, name)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        } finally {
            retriever.release()
            temp.delete()
        }
    }

    private fun publish(context: Context, file: File, name: String): Uri? {
        val resolver = context.contentResolver
        // No version test: the app's minimum is 29, which *is* Q, so the three
        // checks that were here were always true and the branch under the last
        // of them - inserting into EXTERNAL_CONTENT_URI with no relative path -
        // could never run. Dead code that reads as a fallback is worse than no
        // fallback: it says a path exists that does not, and that one would
        // have written the GIF to the wrong place.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Squish")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }
}

/**
 * GIFs being made, kept going when the screen that started one is left: they
 * run in the app's own scope and say when they are done with a toast. The
 * screen reads [progress] to show how far along a file's is.
 */
object GifJobs {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    private val running = kotlinx.coroutines.flow.MutableStateFlow<Map<Uri, Float>>(emptyMap())
    val progress: kotlinx.coroutines.flow.StateFlow<Map<Uri, Float>> = running

    /** How each finished one went: true when the GIF reached the gallery. */
    val finished: kotlinx.coroutines.flow.StateFlow<Map<Uri, Boolean>> get() = done
    private val done = kotlinx.coroutines.flow.MutableStateFlow<Map<Uri, Boolean>>(emptyMap())

    fun start(context: Context, video: Uri, name: String) {
        if (video in running.value) return
        val app = context.applicationContext
        running.value = running.value + (video to 0f)
        scope.launch {
            val gif = GifMaker.make(app, video, name) { p -> scope.launch { if (video in running.value) running.value = running.value + (video to p) } }
            done.value = done.value + (video to (gif != null))
            running.value = running.value - video
            android.widget.Toast.makeText(
                app,
                if (gif != null) "GIF saved to Pictures › Squish" else "Couldn't make a GIF of this video",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
