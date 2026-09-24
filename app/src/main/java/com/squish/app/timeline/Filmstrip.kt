package com.squish.app.timeline

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.squish.app.media.video.FilmstripLoader
import com.squish.app.media.video.FilmstripPlan

/**
 * The clip's own pictures, laid along it.
 *
 * A timeline of coloured rectangles tells you where the cuts are and nothing
 * whatsoever about what is in them, so finding the moment you wanted meant
 * scrubbing for it. With the frames on the strip the shot is legible at a
 * glance, which is the single largest readability difference between a toy
 * timeline and one you can actually cut on.
 *
 * Draws nothing at all when the clip is too narrow to carry a picture - the cost
 * of a filmstrip is decoding, and decoding for a thirty-pixel smear is waste.
 */
@Composable
internal fun Filmstrip(
    uri: Uri,
    sourceInMs: Long,
    sourceOutMs: Long,
    widthDp: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tiles = FilmstripPlan.tileCount(widthDp)
    if (tiles <= 0) return

    val times = remember(sourceInMs, sourceOutMs, tiles) {
        FilmstripPlan.tileTimes(sourceInMs, sourceOutMs, tiles)
    }

    // Seeded from whatever is already cached, so a zoom or a scroll redraws the
    // strip in the same frame instead of blanking and decoding it again.
    val frames = remember(uri, times) {
        times.map { FilmstripLoader.cached(uri, it) }.toMutableStateList()
    }

    LaunchedEffect(uri, times) {
        FilmstripLoader.load(context, uri, times) { index, bitmap ->
            if (index in frames.indices) frames[index] = bitmap
        }
    }

    Row(modifier = modifier) {
        frames.forEach { frame -> Tile(frame) }
    }
}

/**
 * One frame's worth of strip.
 *
 * Weighted rather than fixed-width so the tiles always divide the clip exactly.
 * Past [FilmstripPlan.MAX_TILES] that means they stretch, which is the intended
 * trade: a very long clip zoomed right in shows fewer, wider frames rather than
 * decoding without limit.
 */
@Composable
private fun RowScope.Tile(frame: Bitmap?) {
    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
