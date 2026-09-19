package com.squish.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes a finished export into the system gallery (Movies/Squish) via MediaStore.
 *
 * Without this, exports live in app-private storage: invisible in Photos/Gallery and
 * deleted when the app is uninstalled. Users reasonably expect a finished video to
 * just be in their gallery.
 */
object GallerySaver {

    suspend fun publish(context: Context, source: File, displayName: String = source.name): Uri? =
        insert(
            context = context,
            source = source,
            displayName = displayName,
            mimeType = "video/mp4",
            relativePath = Environment.DIRECTORY_MOVIES + File.separator + "Squish",
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )

    /** Extracted soundtracks belong in Music, not Movies. */
    suspend fun publishAudio(context: Context, source: File, displayName: String = source.name): Uri? =
        insert(
            context = context,
            source = source,
            displayName = displayName,
            mimeType = "audio/mp4",
            relativePath = Environment.DIRECTORY_MUSIC + File.separator + "Squish",
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )

    private suspend fun insert(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
        relativePath: String,
        collection: Uri
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val target = resolver.insert(collection, values) ?: return@withContext null

        try {
            resolver.openOutputStream(target)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return@withContext null

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            target
        } catch (t: Throwable) {
            runCatching { resolver.delete(target, null, null) }
            null
        }
    }
}
