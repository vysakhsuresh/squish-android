package com.squish.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/**
 * Keeps the right to read a picked video after the app is closed.
 *
 * A video chosen in the photo picker can be read only for as long as the process
 * that chose it lives. Drafts outlive that - they exist precisely for the time
 * the app was killed - so every draft pointed at a file the app could no longer
 * open: blank thumbnails in the drafts list, and a crash on reopening one. Taking
 * the grant as persistable keeps it until the app is uninstalled.
 *
 * Harmless to call on anything: a link that cannot be persisted just is not.
 * Returns whether it was, so a caller that must keep the file can copy it in
 * instead (see [importCopy]).
 */
fun Context.keepReadAccess(uri: Uri): Boolean =
    runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)

/**
 * Lets a kept grant go, once no project names the file (ProjectRules.releasable).
 * The phone caps how many an app may hold, and a grant held for a project that
 * was purged is one fewer for the next.
 */
fun Context.releaseReadAccess(uri: Uri) {
    runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

/** Whether a video can still be opened - for a draft, before trying to resume it. */
fun Context.canReadMedia(uri: Uri): Boolean =
    runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

/**
 * A file handed over by "Share" with a grant that ends when this process does
 * - most apps share that way - is copied under the app's own storage
 * (files/imports) and the copy is what the project keeps, so the draft still
 * opens tomorrow. Returns the copy, or the original when it could not be made:
 * the project then opens on the grant for as long as it lasts, which is what
 * it always did. Called off the main thread; a share can be a gigabyte.
 */
fun Context.importCopy(uri: Uri): Uri {
    if (uri.scheme == "file") return uri
    val dir = File(filesDir, IMPORTS_DIR).apply { mkdirs() }
    val name = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    val extension = name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        ?: contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?: "mp4"
    val target = File(dir, "${UUID.randomUUID()}.$extension")
    val copied = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("no stream")
        target.length() > 0L
    }.getOrDefault(false)
    if (!copied) {
        target.delete()
        return uri
    }
    return Uri.fromFile(target)
}

/** Where shared files that could not be kept by grant are copied to. */
const val IMPORTS_DIR = "imports"
