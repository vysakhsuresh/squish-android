package com.squish.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.media.effects.Look
import com.squish.app.media.effects.LookPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The frame under the playhead, small, for previewing looks on.
 *
 * One decode, shared by every thumbnail in the row: the panel shows a dozen looks
 * at once and pulling the same frame a dozen times would make opening the panel
 * a visible stall. It is deliberately tiny - a look is a judgement you can make
 * from a hundred pixels, and twelve graded copies of anything larger is real
 * memory for something on screen for a few seconds.
 */
class LookFrame(private val pixels: IntArray, val width: Int, val height: Int) {

    /** This frame with [look] applied, as a bitmap ready to draw. */
    fun graded(look: Look): Bitmap {
        val copy = pixels.copyOf()
        LookPreview.apply(copy, width, height, look)
        return Bitmap.createBitmap(copy, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Across, in pixels. Enough to read a face at thumbnail size, and no more. */
        private const val WIDTH = 96

        suspend fun grab(context: Context, uri: Uri, atMs: Long): LookFrame? =
            withContext(Dispatchers.IO) {
                val frame = ThumbnailExtractor.frameAt(context, uri, atMs) ?: return@withContext null
                try {
                    val height = (WIDTH.toFloat() * frame.height / frame.width)
                        .toInt().coerceIn(1, WIDTH * 2)
                    val small = Bitmap.createScaledBitmap(frame, WIDTH, height, true)
                    val pixels = IntArray(WIDTH * height)
                    small.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, height)
                    if (small !== frame) small.recycle()
                    LookFrame(pixels, WIDTH, height)
                } catch (t: Throwable) {
                    null
                } finally {
                    frame.recycle()
                }
            }
    }
}

/**
 * Keeps the look row showing the shot you are actually on.
 *
 * Re-grabbed when the playhead moves somewhere else, but not for every
 * millisecond of a scrub: the thumbnails are for judging a grade, and a grade
 * does not change between neighbouring frames. Rounding the position to a couple
 * of seconds means dragging the playhead across a clip re-decodes a handful of
 * times rather than a hundred.
 */
@Composable
fun rememberLookFrame(uri: Uri?, playheadMs: Long): LookFrame? {
    val context = LocalContext.current
    val bucket = playheadMs / FRAME_BUCKET_MS
    var frame by remember(uri) { mutableStateOf<LookFrame?>(null) }

    LaunchedEffect(uri, bucket) {
        if (uri == null) {
            frame = null
            return@LaunchedEffect
        }
        LookFrame.grab(context, uri, bucket * FRAME_BUCKET_MS)?.let { frame = it }
    }
    return frame
}

private const val FRAME_BUCKET_MS = 2_000L
