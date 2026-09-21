package com.squish.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.squish.app.editor.Timecode
import com.squish.app.ui.theme.SquishColors

private val LANE_HEIGHT = 54.dp
private val GUTTER = 34.dp
private val RULER_HEIGHT = 26.dp
private val HANDLE_WIDTH = 20.dp

private fun Long.onTimeline(pixelsPerSecond: Float): Dp = (this / 1000f * pixelsPerSecond).dp

/**
 * A real multi-track timeline: clips can be selected, dragged, trimmed at either
 * edge, and split at the playhead, with video, audio and captions on their own
 * lanes against a shared ruler.
 */
@Composable
fun TimelineEditor(
    state: TimelineState,
    onSelect: (String?) -> Unit,
    onMove: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onScrub: (Long) -> Unit,
    onTransitionTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val pps = state.pixelsPerSecond
    val contentWidth = maxOf(state.durationMs, 8_000L).onTimeline(pps) + 240.dp
    val overlayLayers = (state.layerCount downTo 1).toList()
    val laneCount = overlayLayers.size + 3

    Row(modifier = modifier.fillMaxWidth().background(SquishColors.Background)) {

        Column(modifier = Modifier.width(GUTTER)) {
            Spacer(modifier = Modifier.height(RULER_HEIGHT))
            overlayLayers.forEach { LaneBadge(Icons.Filled.Layers, SquishColors.Magenta) }
            LaneBadge(Icons.Filled.Videocam, SquishColors.Violet)
            LaneBadge(Icons.Filled.MusicNote, SquishColors.Cyan)
            LaneBadge(Icons.Filled.TextFields, SquishColors.Amber)
        }

        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Column(modifier = Modifier.width(contentWidth)) {
                Ruler(durationMs = state.durationMs, pixelsPerSecond = pps, onScrub = onScrub)
                overlayLayers.forEach { layer ->
                    Lane(
                        clips = state.clips.filter { it.kind == ClipKind.Video && it.layer == layer },
                        state = state,
                        accent = SquishColors.Magenta,
                        onSelect = onSelect,
                        onMove = onMove,
                        onTrim = onTrim
                    )
                }
                Lane(
                    clips = state.baseVideoClips,
                    state = state,
                    accent = SquishColors.Violet,
                    onSelect = onSelect,
                    onMove = onMove,
                    onTrim = onTrim,
                    onTransitionTap = onTransitionTap
                )
                Lane(state.audioClips, state, SquishColors.Cyan, onSelect, onMove, onTrim)
                Lane(state.textClips, state, SquishColors.Amber, onSelect, onMove, onTrim)
            }

            Box(
                modifier = Modifier
                    .offset(x = state.playheadMs.onTimeline(pps))
                    .width(2.dp)
                    .height(RULER_HEIGHT + LANE_HEIGHT * laneCount)
                    .background(SquishColors.TextPrimary)
            )
        }
    }
}

@Composable
private fun LaneBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier.height(LANE_HEIGHT).fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Ruler(durationMs: Long, pixelsPerSecond: Float, onScrub: (Long) -> Unit) {
    val latestScrub by rememberUpdatedState(onScrub)
    // A tick every second is unreadable when zoomed out, so widen the step until
    // labels have room to breathe.
    val stepMs = when {
        pixelsPerSecond >= 90f -> 1_000L
        pixelsPerSecond >= 40f -> 2_000L
        pixelsPerSecond >= 18f -> 5_000L
        else -> 10_000L
    }
    val total = maxOf(durationMs, 8_000L)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
            .pointerInput(pixelsPerSecond) {
                detectTapGestures { offset ->
                    latestScrub((offset.x / density / pixelsPerSecond * 1000f).toLong())
                }
            }
    ) {
        var t = 0L
        while (t <= total) {
            val label = t
            Column(modifier = Modifier.offset(x = label.onTimeline(pixelsPerSecond))) {
                Box(modifier = Modifier.width(1.dp).height(6.dp).background(SquishColors.Border))
                Text(
                    Timecode.format(label).removeSuffix(".000"),
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
            t += stepMs
        }
    }
}

@Composable
private fun Lane(
    clips: List<Clip>,
    state: TimelineState,
    accent: Color,
    onSelect: (String?) -> Unit,
    onMove: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onTransitionTap: ((String) -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LANE_HEIGHT)
            .padding(vertical = 3.dp)
    ) {
        clips.forEach { clip ->
            ClipView(
                clip = clip,
                selected = clip.id == state.selectedClipId,
                pixelsPerSecond = state.pixelsPerSecond,
                accent = accent,
                onSelect = onSelect,
                onMove = onMove,
                onTrim = onTrim
            )
        }

        // A tappable marker on every cut, so adding a dissolve is a tap on the
        // join rather than a hunt through a menu.
        onTransitionTap?.let { tap ->
            clips.drop(1).forEach { clip ->
                TransitionBadge(
                    clip = clip,
                    pixelsPerSecond = state.pixelsPerSecond,
                    onTap = { tap(clip.id) }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.TransitionBadge(clip: Clip, pixelsPerSecond: Float, onTap: () -> Unit) {
    val active = clip.transitionIn.isActive
    Box(
        modifier = Modifier
            .offset(x = clip.timelineStartMs.onTimeline(pixelsPerSecond) - 9.dp)
            .align(Alignment.CenterStart)
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) SquishColors.Primary else SquishColors.Surface)
            .border(
                width = 1.dp,
                color = if (active) SquishColors.Primary else SquishColors.Border,
                shape = RoundedCornerShape(5.dp)
            )
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (active) "✕" else "|",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) SquishColors.Background else SquishColors.TextMuted
        )
    }
}

@Composable
private fun ClipView(
    clip: Clip,
    selected: Boolean,
    pixelsPerSecond: Float,
    accent: Color,
    onSelect: (String?) -> Unit,
    onMove: (String, Long) -> Unit,
    onTrim: (String, Long, Long) -> Unit
) {
    val latestMove by rememberUpdatedState(onMove)
    val latestTrim by rememberUpdatedState(onTrim)
    val latestSelect by rememberUpdatedState(onSelect)
    val width = clip.durationMs.onTimeline(pixelsPerSecond)

    Box(
        modifier = Modifier
            .offset(x = clip.timelineStartMs.onTimeline(pixelsPerSecond))
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = if (selected) 0.42f else 0.26f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(7.dp)
            )
            // Tap handled as a gesture rather than Modifier.clickable: clickable sat
            // ahead of the drag detector in the chain and swallowed the drag, which
            // is why clips could be selected but never moved.
            .pointerInput(clip.id) {
                detectTapGestures { latestSelect(clip.id) }
            }
            .pointerInput(clip.id, pixelsPerSecond) {
                detectHorizontalDragGestures(
                    onDragStart = { latestSelect(clip.id) }
                ) { change, dragAmount ->
                    change.consume()
                    latestMove(clip.id, (dragAmount / density / pixelsPerSecond * 1000f).toLong())
                }
            }
    ) {
        Text(
            text = clip.text ?: clip.label,
            style = MaterialTheme.typography.labelSmall,
            color = SquishColors.TextPrimary,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp)
        )

        if (selected) {
            TrimHandle(accent, Alignment.CenterStart) { delta ->
                latestTrim(clip.id, (delta / pixelsPerSecond * 1000f).toLong(), 0L)
            }
            TrimHandle(accent, Alignment.CenterEnd) { delta ->
                latestTrim(clip.id, 0L, (delta / pixelsPerSecond * 1000f).toLong())
            }
        }
    }
}

/** Receives drag in dp so the caller only has to convert time. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TrimHandle(
    accent: Color,
    alignment: Alignment,
    onDragDp: (Float) -> Unit
) {
    val latestDrag by rememberUpdatedState(onDragDp)
    Box(
        modifier = Modifier
            .align(alignment)
            .width(HANDLE_WIDTH)
            .fillMaxHeight()
            .background(accent)
            .pointerInput(alignment) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    latestDrag(dragAmount / density)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(SquishColors.Background.copy(alpha = 0.7f))
        )
    }
}

/** Contextual actions for whatever is selected, shown under the timeline. */
@Composable
fun TimelineActionBar(
    state: TimelineState,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onCloseGaps: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            Timecode.format(state.playheadMs),
            style = MaterialTheme.typography.labelLarge,
            color = SquishColors.TextPrimary
        )
        Spacer(modifier = Modifier.width(4.dp))
        MiniAction("Split", SquishColors.Primary, onSplit)
        MiniAction(
            "Delete",
            if (state.selectedClip == null) SquishColors.TextMuted else SquishColors.Magenta,
            onDelete
        )
        MiniAction("Close gaps", SquishColors.TextSecondary, onCloseGaps)
        Spacer(modifier = Modifier.fillMaxWidth(0.02f))
        MiniAction("−", SquishColors.TextSecondary, onZoomOut)
        MiniAction("+", SquishColors.TextSecondary, onZoomIn)
    }
}

@Composable
private fun MiniAction(label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SquishColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
