package com.squish.app.tools

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.squish.app.editor.Timecode
import com.squish.app.media.ThumbnailExtractor
import com.squish.app.ui.theme.SquishColors
import kotlin.math.roundToInt

/**
 * The in and out points of a quick trim, on frames of the footage.
 *
 * Two handles over a strip of thumbnails, the kept stretch bright and the
 * rest dimmed, each handle landing on a frame boundary (TrimRules) with a
 * frame button either side of the readout for the last frame's worth of
 * precision. [onRange] is handed the handle that moved as well as the new
 * range, so the preview can jump to it: dragging the out point should show
 * the last frame being kept.
 */
@Composable
fun TrimStrip(
    uri: Uri,
    durationMs: Long,
    fps: Float,
    startMs: Long,
    endMs: Long,
    accent: Color,
    onRange: (start: Long, end: Long, moved: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val frameMs = Timecode.frameDurationMs(fps)
    // Which handle the frame buttons nudge: the one last touched.
    var active by remember { mutableStateOf(Handle.End) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(STRIP_HEIGHT)) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val handlePx = with(density) { HANDLE_WIDTH.toPx() }
            val tiles = TrimRules.tileCount(maxWidth.value)
            var frames by remember(uri, tiles) { mutableStateOf<List<Bitmap>>(emptyList()) }
            LaunchedEffect(uri, tiles, durationMs) {
                if (durationMs > 0L) frames = ThumbnailExtractor.extractFrames(context, uri, tiles, durationMs)
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SquishColors.Background)
            ) {
                repeat(tiles) { i ->
                    val frame = frames.getOrNull(i)
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
            }

            // The parts cut away, dimmed, so the kept stretch reads as the picture.
            val startX = TrimRules.xAtMs(startMs, widthPx, durationMs)
            val endX = TrimRules.xAtMs(endMs, widthPx, durationMs)
            Box(
                modifier = Modifier
                    .width(with(density) { startX.toDp() })
                    .fillMaxHeight()
                    .background(SquishColors.Background.copy(alpha = 0.72f))
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(endX.roundToInt(), 0) }
                    .width(with(density) { (widthPx - endX).coerceAtLeast(0f).toDp() })
                    .fillMaxHeight()
                    .background(SquishColors.Background.copy(alpha = 0.72f))
            )
            // The kept stretch's frame.
            Box(
                modifier = Modifier
                    .offset { IntOffset(startX.roundToInt(), 0) }
                    .width(with(density) { (endX - startX).coerceAtLeast(0f).toDp() })
                    .fillMaxHeight()
                    .border(2.dp, accent, RoundedCornerShape(6.dp))
            )

            TrimHandle(
                x = startX - handlePx,
                accent = accent,
                active = active == Handle.Start,
                description = "Start handle, ${Timecode.format(startMs)}",
                onDrag = { dx ->
                    active = Handle.Start
                    val proposed = TrimRules.msAtX(TrimRules.xAtMs(startMs, widthPx, durationMs) + dx, widthPx, durationMs)
                    val moved = TrimRules.movedStart(proposed, endMs, durationMs, frameMs)
                    if (moved != startMs) onRange(moved, endMs, moved)
                }
            )
            TrimHandle(
                x = endX,
                accent = accent,
                active = active == Handle.End,
                description = "End handle, ${Timecode.format(endMs)}",
                onDrag = { dx ->
                    active = Handle.End
                    val proposed = TrimRules.msAtX(TrimRules.xAtMs(endMs, widthPx, durationMs) + dx, widthPx, durationMs)
                    val moved = TrimRules.movedEnd(proposed, startMs, durationMs, frameMs)
                    if (moved != endMs) onRange(startMs, moved, moved)
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Readout(
                label = Timecode.format(startMs),
                active = active == Handle.Start,
                accent = accent,
                onPick = { active = Handle.Start },
                onStep = { frames ->
                    active = Handle.Start
                    val moved = TrimRules.steppedStart(startMs, frames, endMs, durationMs, frameMs)
                    if (moved != startMs) onRange(moved, endMs, moved)
                }
            )
            Text(
                "${Timecode.format(endMs - startMs)} kept",
                style = MaterialTheme.typography.bodySmall,
                color = accent
            )
            Readout(
                label = Timecode.format(endMs),
                active = active == Handle.End,
                accent = accent,
                onPick = { active = Handle.End },
                onStep = { frames ->
                    active = Handle.End
                    val moved = TrimRules.steppedEnd(endMs, frames, startMs, durationMs, frameMs)
                    if (moved != endMs) onRange(startMs, moved, moved)
                }
            )
        }
    }
}

private enum class Handle { Start, End }

@Composable
private fun TrimHandle(x: Float, accent: Color, active: Boolean, description: String, onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), 0) }
            .width(HANDLE_WIDTH)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) accent else accent.copy(alpha = 0.7f))
            .pointerInput(Unit) {
                // Each event is a delta from where the handle now is, so a drag
                // that snapped to a frame does not drift off the finger.
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
            .clickable(role = Role.Button, onClickLabel = description) {},
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.width(2.dp).height(18.dp).background(SquishColors.Background.copy(alpha = 0.7f)))
    }
}

/** A handle's time, with a frame back and a frame forward either side of it. */
@Composable
private fun Readout(label: String, active: Boolean, accent: Color, onPick: () -> Unit, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FrameButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "One frame earlier") { onStep(-1) }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (active) accent else SquishColors.TextPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onPick)
                .padding(horizontal = 6.dp, vertical = 12.dp)
        )
        FrameButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "One frame later") { onStep(1) }
    }
}

@Composable
private fun FrameButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = SquishColors.TextSecondary, modifier = Modifier.size(22.dp))
    }
}

private val STRIP_HEIGHT = 64.dp
private val HANDLE_WIDTH = 22.dp
