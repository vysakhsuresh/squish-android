package com.squish.app.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.squish.app.media.video.MotionTrack
import com.squish.app.timeline.Clip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * Motion tracking: point at something, and everything you pin to it follows.
 *
 * The picker shows the frame under the playhead with the box you are about to track
 * drawn on it. That matters more than it sounds - tracking succeeds or fails almost
 * entirely on what you select, and a box that is mostly background, or mostly flat
 * colour, has nothing to lock onto. Seeing the box before committing to a minute of
 * analysis is the difference between one attempt and five.
 */
@Composable
fun TrackPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    val tracking = state.tracking
    var frame by remember(clip.id) { mutableStateOf<Bitmap?>(null) }
    var stale by remember(clip.id) { mutableStateOf(true) }

    LaunchedEffect(clip.id, stale) {
        if (stale) {
            frame = viewModel.sampleFrame(clip, state.playheadMs)
            stale = false
        }
    }

    PanelSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelHeading("Track an object", "Pin a caption or a layer to something moving")
            if (tracking.track != null) {
                Text(
                    "Discard",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Pink,
                    modifier = Modifier.clickable { viewModel.clearTrack() }
                )
            }
        }

        val bitmap = frame
        if (bitmap != null && bitmap.width > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, SquishColors.Border, RoundedCornerShape(10.dp))
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Tap what you want to follow",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(clip.id) {
                            detectTapGestures { offset ->
                                viewModel.setTrackPoint(
                                    offset.x / size.width,
                                    offset.y / size.height
                                )
                            }
                        }
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = tracking.pointX * size.width
                    val cy = tracking.pointY * size.height
                    val half = tracking.boxFraction * size.width / 2f
                    drawRect(
                        color = SquishColors.Cyan,
                        topLeft = Offset(cx - half, cy - half),
                        size = androidx.compose.ui.geometry.Size(half * 2, half * 2),
                        style = Stroke(width = 2f)
                    )
                    drawCircle(color = Color.White, radius = 3f, center = Offset(cx, cy))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tap the frame to aim",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
                SquishOutlinedButton(text = "Refresh") { stale = true }
            }
        }

        LabeledSlider("Box size", tracking.boxFraction, 0.06f..0.35f, viewModel::setTrackBox)
        Text(
            "Tight enough to be mostly the object, loose enough to include some of its pattern. " +
                "A box of flat colour has nothing to lock onto.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )

        when {
            tracking.running -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = SquishColors.Cyan,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        if (tracking.total > 0) "Following — frame ${tracking.done} of ${tracking.total}"
                        else "Reading the footage",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextSecondary
                    )
                }
                if (tracking.total > 0) {
                    LinearProgressIndicator(
                        progress = { tracking.done.toFloat() / tracking.total },
                        color = SquishColors.Cyan,
                        trackColor = SquishColors.Border,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            tracking.finished && tracking.failed -> Text(
                "Could not read enough frames to follow anything here.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Yellow
            )

            tracking.track != null -> {
                val held = tracking.track.heldFraction
                Text(
                    "Followed ${tracking.track.samples.size} frames, held on for " +
                        "${(held * 100).toInt()}% of them." +
                        if (held < 0.75f) " Where it lost the object it holds the last good position — " +
                            "try a tighter box or a more distinctive part of it." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (held < 0.75f) SquishColors.Yellow else SquishColors.Teal
                )
            }
        }

        SquishOutlinedButton(
            text = if (tracking.running) "Following…" else "Track from the playhead",
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (!tracking.running) viewModel.startTracking(clip.id) }
        )

        if (tracking.track != null) {
            PinTargets(state = state, clip = clip, viewModel = viewModel)
        }
    }
}

/** What the finished track can drive. */
@Composable
private fun PinTargets(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    Text("Pin to the track", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)

    if (clip.isOverlay) {
        SquishOutlinedButton(
            text = "Pin this layer to it",
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.pinLayerToTrack(clip.id) }
        )
    } else {
        state.videoClips.filter { it.isOverlay }.forEach { layer ->
            SquishOutlinedButton(
                text = "Pin ${layer.label} to it",
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.pinLayerToTrack(layer.id) }
            )
        }
    }

    if (state.textOverlays.isEmpty()) {
        Text(
            "Add a caption in the Captions tab and it can ride the track too.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    } else {
        state.textOverlays.sortedBy { it.startMs }.forEach { caption ->
            val label = caption.text.takeIf { it.isNotBlank() } ?: "Untitled line"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SquishOutlinedButton(
                    text = if (caption.track != null) "✓ $label" else "Pin “$label”",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.pinCaptionToTrack(caption.id) }
                )
                if (caption.track != null) {
                    Text(
                        "Unpin",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Pink,
                        modifier = Modifier.clickable { viewModel.unpinCaption(caption.id) }
                    )
                }
            }
        }
    }
}
