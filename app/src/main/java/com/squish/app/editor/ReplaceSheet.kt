package com.squish.app.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay

/**
 * Where in the new file the clip's window starts - the one thing Replace asks,
 * since the clip keeps its length, its place, its speed and everything set on
 * it. A frame of the new file at the chosen moment sits above the slider, so
 * the start is picked by eye, not by a number; it follows the slider once the
 * finger rests for a moment, since a frame is a decode.
 *
 * The sheet's Done puts the file in (EditorToolSheet commits); closing it any
 * other way changes nothing.
 */
@Composable
fun ReplacePanel(request: ReplaceRequest, viewModel: EditorViewModel) {
    val context = LocalContext.current
    var frame by remember(request.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(request.uri, request.inPointMs) {
        delay(FRAME_SETTLE_MS)
        frame = ThumbnailExtractor.frameAt(context, request.uri, request.inPointMs)
    }
    // Left without replacing - back, another selection, the clip deleted -
    // the pick is dropped. Done commits first, and then this finds nothing to drop.
    DisposableEffect(request.clipId) {
        onDispose { viewModel.clips.cancelReplace() }
    }
    PanelSurface(accent = SquishColors.Violet) {
        PanelHeading(
            "Replace with “${request.label}”",
            "Keeps the clip's length, place, speed and everything set on it",
            icon = Icons.Filled.SwapHoriz,
            accent = SquishColors.Violet
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FRAME_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(SquishColors.Background),
            contentAlignment = Alignment.Center
        ) {
            val picture = frame
            if (picture != null) {
                Image(
                    bitmap = picture.asImageBitmap(),
                    contentDescription = "The new file at ${Timecode.format(request.inPointMs)}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(FRAME_HEIGHT)
                )
            } else {
                Text("Reading the frame…", style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
            }
        }
        if (request.latestInMs > 0L) {
            LabeledSlider(
                label = "Start at",
                value = request.inPointMs.toFloat(),
                range = 0f..request.latestInMs.toFloat(),
                readout = { Timecode.format(it.toLong()) },
                onFinished = null
            ) { viewModel.clips.setReplaceInPoint(it.toLong()) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Uses ${Timecode.format(request.inPointMs)} to ${Timecode.format(request.inPointMs + request.neededMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
            Text(
                "of ${Timecode.format(request.fileMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }
    }
}

/** How long the slider rests before the frame under it is read: one decode per pause, not per pixel. */
private const val FRAME_SETTLE_MS = 150L

private val FRAME_HEIGHT = 120.dp
