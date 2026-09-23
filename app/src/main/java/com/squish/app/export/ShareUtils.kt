package com.squish.app.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing a finished file to another app.
 *
 * Every path here starts the intent and catches the failure rather than asking
 * whether an app exists first: package visibility on Android 11 and up hides other
 * apps from a query, so a resolveActivity check reports "not installed" for apps
 * that are sitting right there on the home screen.
 */
object ShareUtils {

    /** True when the share was handed off; false when nothing could take it. */
    fun share(context: Context, path: String, targetPackage: String? = null): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        }.getOrNull() ?: return false

        val mime = mimeOf(path)

        fun send(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pkg?.let { setPackage(it) }
        }

        if (targetPackage != null) {
            try {
                context.startActivity(send(targetPackage))
                return true
            } catch (_: ActivityNotFoundException) {
                // The named app is not here. Fall through to the chooser rather
                // than dead-ending on a tap that looked like it would work.
            }
        }

        return try {
            context.startActivity(Intent.createChooser(send(null), "Share"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    @Deprecated("Use share", ReplaceWith("share(context, path, targetPackage)"))
    fun shareVideo(context: Context, path: String, targetPackage: String? = null) {
        share(context, path, targetPackage)
    }

    private fun mimeOf(path: String): String =
        if (path.endsWith(".m4a", ignoreCase = true) || path.endsWith(".aac", ignoreCase = true)) {
            "audio/mp4"
        } else {
            "video/mp4"
        }
}
