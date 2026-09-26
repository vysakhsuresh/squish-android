package com.squish.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri

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
 */
fun Context.keepReadAccess(uri: Uri) {
    runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Whether a video can still be opened - for a draft, before trying to resume it. */
fun Context.canReadMedia(uri: Uri): Boolean =
    runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
