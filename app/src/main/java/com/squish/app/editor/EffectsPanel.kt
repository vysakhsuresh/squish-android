package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * The effects library: tap one and it covers the next two seconds from the
 * playhead. Each placed effect can be stretched to wherever the playhead is,
 * turned up or down, or removed.
 */
@Composable
fun EffectsPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val placed = state.effects.sortedBy { it.startMs }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "Effects",
                "Tap one to add it at the playhead",
                icon = Icons.Filled.Bolt,
                accent = SquishColors.Blue
            )
            EffectKind.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { kind ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SquishColors.Background)
                                .clickable { viewModel.clips.addEffect(kind) }
                                .padding(vertical = 10.dp)
                        ) {
                            Icon(kind.icon, contentDescription = null, tint = SquishColors.TextSecondary, modifier = Modifier.size(22.dp))
                            Text(
                                kind.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = SquishColors.TextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
        }

        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "On the video",
                if (placed.isEmpty()) "None yet" else "${placed.size} placed",
                icon = Icons.Filled.Layers,
                accent = SquishColors.Blue
            )
            placed.forEach { effect ->
                PlacedEffect(
                    effect = effect,
                    playheadMs = state.playheadMs,
                    onJump = { viewModel.scrubTo(effect.startMs) },
                    onChange = { change -> viewModel.clips.changeEffect(effect.id, change) },
                    onRemove = { viewModel.clips.removeEffect(effect.id) },
                    onGestureEnd = viewModel::endGesture
                )
            }
        }
    }
}

@Composable
private fun PlacedEffect(
    effect: TimedEffect,
    playheadMs: Long,
    onJump: () -> Unit,
    onChange: ((TimedEffect) -> TimedEffect) -> Unit,
    onRemove: () -> Unit,
    onGestureEnd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SquishColors.Background)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(effect.kind.icon, contentDescription = null, tint = effect.kind.color, modifier = Modifier.size(18.dp))
            Text("  ${effect.kind.label}", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
            Text(
                "  ${Timecode.format(effect.startMs)} → ${Timecode.format(effect.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Cyan,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(onClick = onJump)
            )
            Text(
                "Remove",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Pink,
                modifier = Modifier.clickable(onClick = onRemove)
            )
        }
        LabeledSlider("Strength", effect.intensity, 0.1f..1f, onFinished = onGestureEnd) { v -> onChange { it.copy(intensity = v) } }
        // The playhead is the most exact pointer on a phone; these move either end to it.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquishOutlinedButton(
                text = "Start here",
                modifier = Modifier.weight(1f),
                onClick = { onChange { it.copy(startMs = playheadMs.coerceAtMost(it.endMs - 100L)) } }
            )
            SquishOutlinedButton(
                text = "End here",
                modifier = Modifier.weight(1f),
                onClick = { onChange { it.copy(endMs = playheadMs.coerceAtLeast(it.startMs + 100L)) } }
            )
        }
    }
}

/** How strong one placed effect is: the effect's own toolbar, Strength. */
@Composable
fun EffectStrengthPanel(effect: TimedEffect, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Blue) {
        PanelHeading(
            effect.kind.label,
            "${Timecode.format(effect.startMs)} → ${Timecode.format(effect.endMs)} · drag its ends on the strip to retime it",
            icon = effect.kind.icon,
            accent = SquishColors.Blue
        )
        LabeledSlider("Strength", effect.intensity, 0.1f..1f, onFinished = viewModel::endGesture) { v ->
            viewModel.clips.changeEffect(effect.id) { it.copy(intensity = v) }
        }
    }
}
