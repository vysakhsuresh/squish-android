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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.Dp
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
            val targetPx = with(density) { TARGET_WIDTH.toPx() }
            val tiles = TrimRules.tileCount(maxWidth.value)
            var frames by remember(uri, tiles) { mutableStateOf<List<Bitmap?>>(emptyList()) }
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
                    // A frame that would not decode borrows the one before it
                    // (then after), as the editor's filmstrip does: not a black hole.
                    val frame = frames.getOrNull(i)
                        ?: (i - 1 downTo 0).firstNotNullOfOrNull { frames.getOrNull(it) }
                        ?: (i + 1 until frames.size).firstNotNullOfOrNull { frames.getOrNull(it) }
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

            // Where each handle stood when the finger landed on it. A drag is
            // measured from here and the whole distance travelled, never from
            // the handle's last snapped place and the last event alone
            // (TrimRules.draggedTo says why).
            var startAnchor by remember { mutableLongStateOf(0L) }
            var endAnchor by remember { mutableLongStateOf(0L) }
            // The bars sit inside the kept stretch, so both are whole and
            // reachable at the full range, and each touch target reaches on
            // inward from its bar - kept inside the strip, since past its edge
            // the card clips it and the finger scrolls the page instead.
            // They meet at the middle of the kept stretch and reach outward
            // from it when the stretch cannot hold them both; the arithmetic is
            // TrimRules.handleBoxes, which says why.
            val barPx = with(density) { HANDLE_WIDTH.toPx() }
            val (startBox, endBox) = TrimRules.handleBoxes(startX, endX, widthPx, targetPx, barPx)
            val boxDp = with(density) { startBox.width.toDp() }
            TrimHandle(
                x = startBox.x,
                width = boxDp,
                barX = startBox.barX,
                accent = accent,
                active = active == Handle.Start,
                description = "Start handle, ${Timecode.format(startMs)}",
                onDragStart = {
                    active = Handle.Start
                    startAnchor = startMs
                },
                onDrag = { travelled ->
                    val proposed = TrimRules.draggedTo(startAnchor, travelled, widthPx, durationMs)
                    val moved = TrimRules.movedStart(proposed, endMs, durationMs, frameMs)
                    if (moved != startMs) onRange(moved, endMs, moved)
                }
            )
            TrimHandle(
                x = endBox.x,
                width = boxDp,
                barX = endBox.barX,
                accent = accent,
                active = active == Handle.End,
                description = "End handle, ${Timecode.format(endMs)}",
                onDragStart = {
                    active = Handle.End
                    endAnchor = endMs
                },
                onDrag = { travelled ->
                    val proposed = TrimRules.draggedTo(endAnchor, travelled, widthPx, durationMs)
                    val moved = TrimRules.movedEnd(proposed, startMs, durationMs, frameMs)
                    if (moved != endMs) onRange(startMs, moved, moved)
                }
            )
        }
        // On its own line, not between the two ends: three times across a phone
        // left the end one broken over two lines ("0:16.49 / 6").
        Text(
            "${Timecode.format(endMs - startMs)} kept",
            style = MaterialTheme.typography.bodySmall,
            color = accent,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

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

/**
 * One handle: a [HANDLE_WIDTH] bar at one end of a [TARGET_WIDTH] touch
 * target. [onDrag] is handed the whole distance travelled since [onDragStart].
 * Both lambdas are read through rememberUpdatedState: the gesture block is
 * keyed on nothing and lives as long as the handle does, and the lambda it
 * captured on first composition closed over the range as it was then - every
 * later drag was added to the handle's original place, so it snapped back
 * under the finger and jittered there.
 */
@Composable
private fun TrimHandle(
    x: Float,
    width: Dp,
    /** Where the bar sits inside the target, in pixels (TrimRules.handleBoxes). */
    barX: Float,
    accent: Color,
    active: Boolean,
    description: String,
    onDragStart: () -> Unit,
    onDrag: (travelledPx: Float) -> Unit
) {
    val latestStart by rememberUpdatedState(onDragStart)
    val latestDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), 0) }
            .width(width)
            .fillMaxHeight()
            .pointerInput(Unit) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        travelled = 0f
                        latestStart()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        travelled += dragAmount
                        latestDrag(travelled)
                    }
                )
            }
            .clickable(role = Role.Button, onClickLabel = description) {},
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(barX.roundToInt(), 0) }
                .width(HANDLE_WIDTH)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(if (active) accent else accent.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.width(2.dp).height(18.dp).background(SquishColors.Background.copy(alpha = 0.7f)))
        }
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
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = SquishColors.TextSecondary, modifier = Modifier.size(22.dp))
    }
}

private val STRIP_HEIGHT = 64.dp
/** The bar that is seen. */
private val HANDLE_WIDTH = 24.dp
/** The width a finger has to land in; the bar is at one end of it. */
private val TARGET_WIDTH = 48.dp
