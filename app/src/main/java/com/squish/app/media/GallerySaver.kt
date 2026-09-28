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
 *
 * Returns where the copy landed, or null when it did not. Callers keep that
 * answer (ExportRecord.galleryUri): the export screen used to say "Saved to your
 * gallery" whatever happened here, so a failed copy left people looking in
 * Photos for a video that was only inside the app.
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

        // Inside the catch too: a provider that throws from insert rather than
        // returning null (a full volume, a revoked write) escaped this function
        // and took the export's hand-over with it, after the file was written.
        val target = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return@withContext null

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
