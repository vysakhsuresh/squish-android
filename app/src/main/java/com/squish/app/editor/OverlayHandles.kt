package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Transform
import com.squish.app.ui.theme.SquishColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One overlay on the picture at the moment showing, as the box needs it. */
data class OverlayOnPicture(
    val clipId: String,
    val layer: Int,
    /** Where it is drawn, the stabilizer's correction included: what the box outlines. */
    val drawn: Transform,
    /** Where the editor put it, without that correction: what a gesture moves from. */
    val placed: Transform,
    /** Its picture's width over height, once known. */
    val aspect: Float?
)

/** What the box's gestures and corners do. */
class OverlayHandleActions(
    val onSelect: (String) -> Unit,
    /** A gesture's placement so far - the whole of it, not a step, so events can be dropped or doubled harmlessly. */
    val onPlace: (clipId: String, placement: Transform) -> Unit,
    /** The fingers lifted: the next placement is a new undo step. */
    val onPlaceEnd: () -> Unit,
    val onDelete: (String) -> Unit,
    val onDuplicate: (String) -> Unit,
    val onReset: (String) -> Unit
)

/** The corners, in the order [OverlayRules.Box.corners] gives them. */
private enum class Corner(val label: String, val icon: ImageVector) {
    Delete("Delete overlay", Icons.Filled.Close),
    Duplicate("Duplicate overlay", Icons.Filled.ContentCopy),
    Resize("Resize and turn overlay", Icons.Filled.OpenInFull),
    Reset("Reset overlay placement", Icons.Filled.RestartAlt)
}

/**
 * An overlay you can grab, the way CapCut's are: tap one on the picture to
 * select it; the selected one has a box with four corners - delete, duplicate,
 * reset, and a handle that resizes and turns it; one finger drags it, two pinch
 * and turn it (anywhere on the picture, once one is selected). While it moves
 * it snaps to the picture's centre lines and edges and to the other layers', with
 * a tick, and says where it is.
 *
 * Laid over exactly the frame the export keeps - the overlay's own canvas - so
 * the box is where the overlay is drawn in the file (OverlayRules.box). Every
 * move goes through the one way placement is written, which keys the move at the
 * playhead when the overlay is animated (TimelineState.withOverlayGeometry).
 *
 * Every touch inside the frame is this layer's, so a tap on bare picture comes
 * back as [onEmptyTap] - deselect, or play - rather than falling through to a
 * play button underneath as well.
 */
@Composable
fun OverlayHandles(
    overlays: List<OverlayOnPicture>,
    selectedId: String?,
    /** The box is left off while the picture plays; a touch stops it, and the box comes back. */
    showBox: Boolean,
    actions: OverlayHandleActions,
    /** A finger has started to move an overlay: the transport stops, so the playhead holds still under it. */
    onTouch: () -> Unit,
    onEmptyTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val latestOverlays by rememberUpdatedState(overlays)
    val latestSelected by rememberUpdatedState(selectedId)
    val latestActions by rememberUpdatedState(actions)
    val latestTouch by rememberUpdatedState(onTouch)
    val latestEmptyTap by rememberUpdatedState(onEmptyTap)
    val latestShowBox by rememberUpdatedState(showBox)

    var size by remember { mutableStateOf(IntSize.Zero) }
    var snap by remember { mutableStateOf<OverlayRules.Snapped?>(null) }
    var readout by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                val cornerReach = CORNER_REACH.toPx()
                val grace = HIT_GRACE.toPx()
                val snapPx = SNAP_DISTANCE.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val slop = viewConfiguration.touchSlop
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // Top row first: a tap where two overlap takes the one on top.
                    val items = latestOverlays.sortedByDescending { it.layer }
                    val boxes = items.associate { it.clipId to OverlayRules.box(it.drawn, it.aspect, w, h) }
                    val selected = items.firstOrNull { it.clipId == latestSelected }
                    val selectedBox = selected?.let { boxes[it.clipId] }

                    // A corner of the selected box - they reach past its edge. The
                    // nearest, on a box small enough for two to be in reach: the
                    // first in the list was Delete, a slip from Duplicate away.
                    // Only corners that are drawn: while it plays there are none.
                    val corner = selectedBox?.takeIf { latestShowBox }?.corners
                        ?.mapIndexed { i, (x, y) -> i to hypot(x - down.position.x, y - down.position.y) }
                        ?.filter { it.second <= cornerReach }
                        ?.minByOrNull { it.second }
                        ?.let { Corner.entries[it.first] }
                    if (selected != null && selectedBox != null && corner != null) {
                        if (corner == Corner.Resize) {
                            latestTouch()
                            var lastSnapped = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val raw = OverlayRules.handled(
                                    selected.placed, selectedBox.cx, selectedBox.cy,
                                    down.position.x, down.position.y, change.position.x, change.position.y
                                )
                                val s = OverlayRules.snapped(raw, selected.aspect, w, h, emptyList(), snapPx, snapPosition = false, snapAngle = true)
                                if (s.snappedAny && !lastSnapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnapped = s.snappedAny
                                val placed = OverlayRules.limited(s.transform)
                                snap = null
                                readout = OverlayRules.readout(placed, moving = false)
                                latestActions.onPlace(selected.clipId, placed)
                                event.changes.forEach(PointerInputChange::consume)
                            }
                            readout = null
                            latestActions.onPlaceEnd()
                        } else {
                            // A button: it acts when the finger lifts on it.
                            var stayed = true
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if ((change.position - down.position).getDistance() > slop) stayed = false
                                event.changes.forEach(PointerInputChange::consume)
                                if (!change.pressed) break
                            }
                            if (stayed) when (corner) {
                                Corner.Delete -> latestActions.onDelete(selected.clipId)
                                Corner.Duplicate -> latestActions.onDuplicate(selected.clipId)
                                Corner.Reset -> latestActions.onReset(selected.clipId)
                                Corner.Resize -> Unit
                            }
                        }
                        return@awaitEachGesture
                    }

                    val (x, y) = down.position
                    val hit = selected?.takeIf { selectedBox?.contains(x, y, grace) == true }
                        ?: items.firstOrNull { boxes[it.clipId]?.contains(x, y, grace) == true }
                    // Two fingers on bare picture work the selected overlay, so a
                    // small one can be pinched without landing both fingers on it.
                    val target = hit ?: selected
                    val others = { id: String -> items.filter { it.clipId != id }.mapNotNull { boxes[it.clipId] } }

                    var multi = false
                    var wandered = false
                    var moving = false
                    var pan = Offset.Zero
                    var zoom = 1f
                    var turn = 0f
                    var lastSnapped = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) multi = true
                        if (event.changes.any { (it.position - down.position).getDistance() > slop }) wandered = true
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        // One finger on bare picture moves nothing; two need something selected.
                        if (target == null || (hit == null && !multi)) continue

                        pan += event.calculatePan()
                        if (multi) {
                            zoom *= event.calculateZoom()
                            turn += event.calculateRotation()
                        }
                        if (!moving) {
                            val started = pan.getDistance() > slop ||
                                (multi && (abs(zoom - 1f) > PINCH_START || abs(turn) > TURN_START))
                            if (!started) continue
                            moving = true
                            latestTouch()
                            if (target.clipId != latestSelected) latestActions.onSelect(target.clipId)
                        }
                        val raw = OverlayRules.pinched(OverlayRules.dragged(target.placed, pan.x, pan.y, w, h), zoom, turn)
                        val s = OverlayRules.snapped(raw, target.aspect, w, h, others(target.clipId), snapPx, snapAngle = multi)
                        if (s.snappedAny && !lastSnapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastSnapped = s.snappedAny
                        val placed = OverlayRules.limited(s.transform)
                        snap = s
                        readout = OverlayRules.readout(placed, moving = !multi)
                        latestActions.onPlace(target.clipId, placed)
                    }
                    if (moving) {
                        snap = null
                        readout = null
                        latestActions.onPlaceEnd()
                    } else if (!wandered && !multi) {
                        // A tap: on an overlay selects it; on bare picture is the
                        // picture's own tap.
                        when {
                            hit == null -> latestEmptyTap()
                            hit.clipId != latestSelected -> latestActions.onSelect(hit.clipId)
                        }
                    }
                }
            }
    ) {
        val selected = overlays.firstOrNull { it.clipId == selectedId }
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val box = selected?.takeIf { showBox && w > 0f && h > 0f }?.let { OverlayRules.box(it.drawn, it.aspect, w, h) }

        Canvas(modifier = Modifier.fillMaxSize()) {
            snap?.let { s ->
                val guide = SquishColors.Magenta
                val line = with(density) { 1.dp.toPx() }
                s.xLines.forEach { gx -> drawLine(guide, Offset(gx, 0f), Offset(gx, this.size.height), strokeWidth = line) }
                s.yLines.forEach { gy -> drawLine(guide, Offset(0f, gy), Offset(this.size.width, gy), strokeWidth = line) }
            }
            box?.let { b ->
                val outline = Path().apply {
                    val c = b.corners
                    moveTo(c[0].first, c[0].second)
                    c.drop(1).forEach { (px, py) -> lineTo(px, py) }
                    close()
                }
                // Dark under light, so the edge reads on a white shirt and a night sky alike.
                drawPath(outline, Color.Black.copy(alpha = 0.45f), style = Stroke(width = with(density) { 3.dp.toPx() }))
                drawPath(outline, Color.White, style = Stroke(width = with(density) { 1.5.dp.toPx() }))
            }
        }

        box?.let { b ->
            b.corners.forEachIndexed { i, (cx, cy) ->
                val corner = Corner.entries[i]
                CornerButton(
                    corner = corner,
                    centreX = cx,
                    centreY = cy,
                    onClick = {
                        val id = selected.clipId
                        when (corner) {
                            Corner.Delete -> actions.onDelete(id)
                            Corner.Duplicate -> actions.onDuplicate(id)
                            Corner.Reset -> actions.onReset(id)
                            Corner.Resize -> Unit
                        }
                    }
                )
            }
        }

        readout?.let { text -> Readout(text) }
    }
}

/**
 * A corner of the box. Drawn here; the touch is the box's own (see the gesture
 * above), so a drag that starts on the resize corner is a resize and not a
 * move. The semantics give a screen reader the same buttons.
 */
@Composable
private fun CornerButton(corner: Corner, centreX: Float, centreY: Float, onClick: () -> Unit) {
    val half = with(LocalDensity.current) { (CORNER_SIZE / 2).roundToPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset(centreX.roundToInt() - half, centreY.roundToInt() - half) }
            .size(CORNER_SIZE)
            .clip(CircleShape)
            .background(if (corner == Corner.Delete) SquishColors.Danger else SquishColors.SurfaceElevated)
            .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .semantics {
                contentDescription = corner.label
                role = Role.Button
                if (corner != Corner.Resize) onClick(label = corner.label) { onClick(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(corner.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** Where the overlay is, or how big and turned, while a finger is on it. */
@Composable
private fun BoxScope.Readout(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** A corner's drawn size. */
private val CORNER_SIZE = 26.dp

/** How far from a corner's centre a finger still takes it: a thumb, not the dot. */
private val CORNER_REACH = 22.dp

/** Grace around the box, so a small overlay can still be picked up. */
private val HIT_GRACE = 12.dp

/** How close to a line an edge or centre is pulled onto it. */
private val SNAP_DISTANCE = 8.dp

/** How far two fingers have to spread, or turn, before they count as a pinch rather than a wobble. */
private const val PINCH_START = 0.03f
private const val TURN_START = 3f
