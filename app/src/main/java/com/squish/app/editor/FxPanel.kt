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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * The effects library: tap one and it covers the next two seconds from the
 * playhead. Each placed effect can be stretched to wherever the playhead is,
 * turned up or down, or removed.
 */
@Composable
fun FxPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val placed = state.effects.sortedBy { it.startMs }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Violet) {
            PanelHeading(
                "Effects",
                "Tap one to add it at the playhead",
                icon = Icons.Filled.Bolt,
                accent = SquishColors.Violet
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
                                .clickable { viewModel.addEffect(kind) }
                                .padding(vertical = 10.dp)
                        ) {
                            GlyphTile(kind.glyph, size = 38.dp)
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

        PanelSurface(accent = SquishColors.Violet) {
            PanelHeading(
                "On the video",
                if (placed.isEmpty()) "None yet" else "${placed.size} placed",
                icon = Icons.Filled.Layers,
                accent = SquishColors.Violet
            )
            placed.forEach { effect ->
                PlacedEffect(
                    effect = effect,
                    playheadMs = state.playheadMs,
                    onJump = { viewModel.scrubTo(effect.startMs) },
                    onChange = { change -> viewModel.changeEffect(effect.id, change) },
                    onRemove = { viewModel.removeEffect(effect.id) }
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
    onRemove: () -> Unit
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
            GlyphTile(effect.kind.glyph, size = 26.dp)
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
        LabeledSlider("Strength", effect.intensity, 0.1f..1f) { v -> onChange { it.copy(intensity = v) } }
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
