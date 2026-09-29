package com.squish.app.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * An overlay's opacity. Its own tool on the overlay's toolbar; it was one slider
 * among the Blend panel's Size, Across and Up / down, which Placement now owns.
 */
@Composable
fun OpacityPanel(clip: Clip, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Magenta) {
        PanelHeading(
            "Opacity",
            "How much of the picture underneath shows through",
            icon = Icons.Filled.Opacity,
            accent = SquishColors.Magenta
        )
        LabeledSlider("Opacity", clip.opacity, 0f..1f, onFinished = viewModel::endGesture) {
            viewModel.layers.setOverlayGeometry(clip.id, opacity = it)
        }
    }
}

/**
 * Which row an overlay sits on - what it is drawn over and under. Dropping it
 * onto the main track is its own button on the toolbar, To main.
 */
@Composable
fun LayerPanel(clip: Clip, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Magenta) {
        PanelHeading(
            "Layer ${clip.layer} of $MAX_LAYER",
            "Higher layers are drawn over lower ones",
            icon = Icons.Filled.Layers,
            accent = SquishColors.Magenta
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquishOutlinedButton(text = "Lower", modifier = Modifier.weight(1f)) {
                viewModel.layers.changeLayer(clip.id, -1)
            }
            SquishOutlinedButton(text = "Raise", modifier = Modifier.weight(1f)) {
                viewModel.layers.changeLayer(clip.id, +1)
            }
        }
        Text(
            "A row that is taken at this moment is skipped for the next free one. Lowering from " +
                "layer 1 puts the clip on the main track.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}
