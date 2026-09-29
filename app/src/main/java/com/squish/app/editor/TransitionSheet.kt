package com.squish.app.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.media.ExportPlan
import com.squish.app.timeline.Clip
import com.squish.app.timeline.TransitionCategory
import com.squish.app.timeline.TransitionType
import com.squish.app.timeline.transitionOverlapMs
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * How a shot arrives from the one before it. Opened from the shot's toolbar, or
 * by tapping the mark on its join in the strip; on an overlay butted after
 * another on its row, from its toolbar, where the transition plays over the
 * overlay's own head (ExportPlan.ownDrawAt).
 *
 * The kinds are in four tabs, each drawn as a small moving picture of two
 * shots - the same ExportPlan.blend the preview and the file draw from, so the
 * thumbnail is the transition, not an icon of it.
 *
 * Only ever for a clip with a clip before it: the toolbar offers it nowhere
 * else, so there is no "select a clip" or "the first shot has nothing to
 * transition from" to explain here. The Blend panel used to stack those messages
 * three deep above the one control that could be used.
 */
@Composable
fun TransitionPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    val current = clip.transitionIn
    val accent = if (clip.isOverlay) SquishColors.Magenta else SquishColors.Violet
    // The tab opens on the kind in use, then stays where it is put.
    var tab by rememberSaveable(clip.id) { mutableIntStateOf(current.type.category.ordinal) }
    val shown = TransitionType.entries.filter { it != TransitionType.None && it.category.ordinal == tab }
    // The shot before this one on the main track, for the length the join can
    // hold. By order, not by where it ends: with the transition set it ends
    // after this one starts.
    val previous = if (clip.isOverlay) null else state.videoClips.filter { !it.isOverlay }
        .sortedBy { it.timelineStartMs }.let { base -> base.getOrNull(base.indexOfFirst { it.id == clip.id } - 1) }
    val plays = if (clip.isOverlay) ExportPlan.overlayTransitionMs(clip) else previous?.let { transitionOverlapMs(clip, it) } ?: current.durationMs

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = accent) {
            PanelHeading(
                if (clip.isOverlay) "Transition" else "Transition in",
                if (current.isActive) "${current.type.label} · how ${clip.label} arrives" else "How ${clip.label} arrives",
                icon = Icons.Filled.Transform,
                accent = accent,
                trailing = {
                    if (current.isActive) {
                        Text(
                            "None",
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.Pink,
                            modifier = Modifier.clickable { viewModel.layers.setTransition(clip.id, TransitionType.None, current.durationMs) }
                        )
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                TransitionCategory.entries.forEach { category ->
                    SelectableChip(
                        label = category.label,
                        selected = tab == category.ordinal,
                        accentColor = accent,
                        modifier = Modifier.weight(1f),
                        onClick = { tab = category.ordinal }
                    )
                }
            }
            shown.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { type ->
                        TransitionTile(
                            type = type,
                            selected = current.type == type,
                            accent = accent,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.layers.setTransition(clip.id, type, current.durationMs) }
                        )
                    }
                    repeat(4 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }

            if (current.isActive) {
                // Seconds, as the rest of the editor reads time; a length in
                // milliseconds was the one number here nobody thinks in.
                LabeledSlider(
                    "Length",
                    current.durationMs.toFloat(),
                    MIN_TRANSITION_MS..MAX_TRANSITION_MS,
                    readout = { ms -> "%.1f s".format(ms / 1000f) },
                    onFinished = viewModel::endGesture
                ) {
                    viewModel.layers.setTransition(clip.id, current.type, it.toLong())
                }
                Text(
                    when {
                        clip.isOverlay -> "Plays over the start of this overlay, so it is at most as long as the overlay."
                        plays < current.durationMs ->
                            "Plays for %.1f s: a transition takes at most half of the shorter shot, and the shots here are short."
                                .format(plays / 1000f)
                        else -> "The shots overlap by this much, so the edit gets that much shorter."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (plays < current.durationMs) SquishColors.Amber else SquishColors.TextMuted
                )
                if (!clip.isOverlay) {
                    SquishOutlinedButton(
                        text = "Apply to all cuts",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.layers.applyTransitionToAll(clip.id) }
                    )
                }
            }
        }
    }
}

/**
 * One transition as a small moving picture: two shots, one dark and one light,
 * with the transition running between them over and over. Drawn from
 * ExportPlan.blend with the incoming shot on top, as the preview draws it.
 */
@Composable
private fun TransitionTile(
    type: TransitionType,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val loop = rememberInfiniteTransition(label = "transition")
    // Holds a moment on each shot either side of the run, so the eye can tell
    // where the transition starts and ends.
    val p by loop.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "progress"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else SquishColors.Background)
            .border(1.dp, if (selected) accent else SquishColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
        ) {
            val (incoming, outgoing) = ExportPlan.blend(type, p.coerceIn(0f, 1f), incomingOnTop = true)
            drawShot(outgoing, OUTGOING)
            drawShot(incoming, INCOMING)
        }
        Text(
            type.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else SquishColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One shot as the transition shader draws it: shifted, scaled, cut to its rectangle, whitened, faded. */
private fun DrawScope.drawShot(draw: ExportPlan.Draw, colour: Color) {
    if (draw.alpha <= 0f) return
    val w = size.width
    val h = size.height
    clipRect(
        left = w * draw.keepFrom.coerceIn(0f, 1f),
        top = h * draw.keepFromY.coerceIn(0f, 1f),
        right = w * draw.keepTo.coerceIn(0f, 1f),
        bottom = h * draw.keepToY.coerceIn(0f, 1f)
    ) {
        val sw = w * draw.scale
        val sh = h * draw.scale
        val left = w * draw.shiftX + (w - sw) / 2f
        val top = h * draw.shiftY + (h - sh) / 2f
        val lit = Color(
            red = colour.red + (1f - colour.red) * draw.white,
            green = colour.green + (1f - colour.green) * draw.white,
            blue = colour.blue + (1f - colour.blue) * draw.white
        )
        drawRect(color = lit, topLeft = Offset(left, top), size = Size(sw, sh), alpha = draw.alpha.coerceIn(0f, 1f))
        // A mark on each shot, so a slide reads as movement and not a change of colour.
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f * draw.alpha.coerceIn(0f, 1f)),
            radius = sh * 0.16f,
            center = Offset(left + sw * (if (colour == INCOMING) 0.68f else 0.32f), top + sh * 0.5f)
        )
    }
}

private val OUTGOING = Color(0xFF5B6478)
private val INCOMING = Color(0xFFB8C4DC)

/** Shorter than this a transition is a flicker; longer, it is a scene of its own. */
private const val MIN_TRANSITION_MS = 150f
private const val MAX_TRANSITION_MS = 2_000f
