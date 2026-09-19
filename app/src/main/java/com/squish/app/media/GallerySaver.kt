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
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + File.separator + "Squish"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val target = resolver.insert(collection, values) ?: return@withContext null

            try {
                resolver.openOutputStream(target)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: return@withContext null

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(target, values, null, null)
                target
            } catch (t: Throwable) {
                runCatching { resolver.delete(target, null, null) }
                null
            }
        }
}
