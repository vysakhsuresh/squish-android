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
                    encoder.addFrame(pixels, 1000 / FPS)
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
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Squish")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }
}
