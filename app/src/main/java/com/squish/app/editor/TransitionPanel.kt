package com.squish.app.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.ClipKind
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.timeline.TransitionType
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * Transitions and compositing for whatever is selected. Both act on a clip, so
 * they share a panel rather than making you hunt in two places.
 */
@Composable
fun TransitionPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onAddOverlay: () -> Unit
) {
    val timeline = state.toTimeline()
    val selected = timeline.selectedClip
    val isVideo = selected?.kind == ClipKind.Video
    val baseOrder = timeline.baseVideoClips
    val canTransition = isVideo && selected != null &&
        selected.layer == 0 && baseOrder.indexOfFirst { it.id == selected.id } > 0

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        if (selected == null) {
            PanelSurface {
                PanelHeading("Nothing selected", "Tap a clip on the timeline to give it a transition or float it over the picture")
            }
        }

        PanelSurface {
            PanelHeading("Transition in", "How this shot arrives")
            if (!canTransition) {
                Text(
                    when {
                        selected == null -> "Select a clip on the video track."
                        selected.layer > 0 -> "Overlay clips fade with their own opacity, not a cut transition."
                        else -> "The first shot has nothing to transition from."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            } else {
                val current = selected.transitionIn
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    TransitionType.entries.take(3).forEach { type ->
                        SelectableChip(
                            label = type.label,
                            selected = current.type == type,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.setTransition(selected.id, type, current.durationMs) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    TransitionType.entries.drop(3).forEach { type ->
                        SelectableChip(
                            label = type.label,
                            selected = current.type == type,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.setTransition(selected.id, type, current.durationMs) }
                        )
                    }
                }

                if (current.isActive) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Length", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
                        Text(
                            "${current.durationMs} ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = SquishColors.TextPrimary
                        )
                    }
                    Slider(
                        value = current.durationMs.toFloat(),
                        onValueChange = {
                            viewModel.setTransition(selected.id, current.type, it.toLong())
                        },
                        valueRange = 150f..2000f,
                        colors = SliderDefaults.colors(
                            thumbColor = SquishColors.Primary,
                            activeTrackColor = SquishColors.Primary,
                            inactiveTrackColor = SquishColors.Border
                        )
                    )
                    Text(
                        "The shots overlap by this much, so the edit gets ${current.durationMs} ms shorter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.TextMuted
                    )
                }
            }
        }

        PanelSurface {
            PanelHeading("Layers", "Float a clip over the picture")
            if (isVideo && selected != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selected.layer == 0) "On the base picture" else "Layer ${selected.layer}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SquishOutlinedButton(text = "Lower") { viewModel.changeLayer(selected.id, -1) }
                        SquishOutlinedButton(text = "Raise") { viewModel.changeLayer(selected.id, +1) }
                    }
                }
                Text(
                    "Up to $MAX_LAYER layers above the base.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )

                if (selected.layer > 0) {
                    LabeledSlider("Opacity", selected.opacity, 0f..1f) {
                        viewModel.setOverlayGeometry(selected.id, opacity = it)
                    }
                    LabeledSlider("Size", selected.scale, 0.1f..1f) {
                        viewModel.setOverlayGeometry(selected.id, scale = it)
                    }
                    LabeledSlider("Across", selected.offsetXFraction, -1f..1f) {
                        viewModel.setOverlayGeometry(selected.id, offsetX = it)
                    }
                    LabeledSlider("Up / down", selected.offsetYFraction, -1f..1f) {
                        viewModel.setOverlayGeometry(selected.id, offsetY = it)
                    }
                }
            } else {
                Text(
                    "Select a video clip to move it between layers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            }

            SquishOutlinedButton(
                text = "Add overlay clip",
                modifier = Modifier.fillMaxWidth(),
                onClick = onAddOverlay
            )
        }

        if (isVideo && selected != null) {
            ChromaKeyPanel(clip = selected, playheadMs = state.playheadMs, viewModel = viewModel)
        }
    }
}
