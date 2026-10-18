package com.squish.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.squish.app.data.ProjectRules
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
    // A gallery file the app may read without a grant is not copied: its own
    // address outlives the grant, so the copy bought nothing and cost the
    // whole file before the editor opened - a 1.4 GB film shared from the
    // gallery was a minute of spinner.
    if (readableWithoutGrant(uri)) return uri
    val dir = File(filesDir, IMPORTS_DIR).apply { mkdirs() }
    var name: String? = null
    var size = -1L
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
    }
    val extension = name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        ?: contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?: "mp4"

    // The same file opened twice is copied once. Three gigabytes of imports on
    // the owner's phone were mostly the same few videos over and over - see
    // ProjectRules.importCopyName, which has the count. The length is checked
    // rather than trusted: a copy left half-written by a kill is shorter than
    // the source, and must be made again rather than opened.
    val kept = ProjectRules.importCopyName(name, size, extension)?.let { File(dir, it) }
    if (kept != null && kept.length() == size) return Uri.fromFile(kept)

    // Written beside the name it will take, and moved onto it only when the
    // whole file is there - so a kill mid-copy leaves something that is plainly
    // not the copy, rather than a short file the next open would trust.
    val target = kept ?: File(dir, "${UUID.randomUUID()}.$extension")
    val partial = File(dir, "${target.name}.part")
    val copied = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            // A megabyte at a time: the default 8 KB was one system call on
            // each side for every 8 KB of a file that can be a gigabyte.
            partial.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
        } ?: error("no stream")
        partial.length() > 0L && (size <= 0L || partial.length() == size)
    }.getOrDefault(false)
    if (!copied || !partial.renameTo(target)) {
        partial.delete()
        return uri
    }
    return Uri.fromFile(target)
}

private const val COPY_BUFFER = 1 shl 20

/**
 * Whether [uri] is a MediaStore item of a kind the app holds the read
 * permission for: the media provider then lets the app open it by its own
 * address in any process, grant or none. Partial access (Android 14's
 * "selected photos") is not enough - only what was selected stays readable -
 * so that case is copied as before.
 */
private fun Context.readableWithoutGrant(uri: Uri): Boolean {
    if (uri.authority != android.provider.MediaStore.AUTHORITY) return false
    val path = uri.path.orEmpty()
    val permissions = when {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ->
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        "/video/" in path -> listOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        "/images/" in path -> listOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        "/audio/" in path -> listOf(android.Manifest.permission.READ_MEDIA_AUDIO)
        else -> return false
    }
    return permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/** Where shared files that could not be kept by grant are copied to. */
const val IMPORTS_DIR = "imports"
