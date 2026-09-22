package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Mask
import com.squish.app.timeline.MaskShape
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors

/**
 * Shape masks for the selected clip.
 *
 * There are no drag handles on the preview, and that is a deliberate omission
 * rather than a gap: the mask is applied by the preview's own shader, so what the
 * sliders move is the finished result, live. Handles would be a second, worse
 * representation of something already on screen.
 */
@Composable
fun MaskPanel(clip: Clip, viewModel: EditorViewModel) {
    val mask = clip.mask

    PanelSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelHeading("Mask", "Show only part of this clip")
            if (mask != null) {
                Text(
                    "Turn off",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Pink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SquishColors.Background)
                        .pointerInput(clip.id) {
                            detectTapGestures { viewModel.setMask(clip.id, null) }
                        }
                )
            }
        }

        if (mask == null) {
            Text(
                "A mask keeps the clip inside a shape and hides the rest — a spotlight, " +
                    "a split screen, a reveal. Invert it to punch the shape out instead.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            SquishOutlinedButton(
                text = "Add a mask",
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.setMask(clip.id, Mask()) }
            )
            return@PanelSurface
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            MaskShape.entries.forEach { shape ->
                SelectableChip(
                    label = shape.label,
                    selected = mask.shape == shape,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.updateMask(clip.id, shape = shape) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (mask.inverted) "Hiding the shape" else "Keeping the shape",
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary
            )
            SquishToggleSwitch(
                checked = mask.inverted,
                onCheckedChange = { viewModel.updateMask(clip.id, inverted = it) }
            )
        }

        LabeledSlider("Across", mask.centerXFraction, -1f..1f) {
            viewModel.updateMask(clip.id, centerX = it)
        }
        LabeledSlider("Up / down", mask.centerYFraction, -1f..1f) {
            viewModel.updateMask(clip.id, centerY = it)
        }

        // Linear takes its edge from the centre and the angle alone, and Mirror is a
        // band with no width - offering sliders that do nothing would just invite
        // someone to drag them and conclude the mask is broken.
        if (mask.shape == MaskShape.Rectangle || mask.shape == MaskShape.Ellipse) {
            LabeledSlider("Width", mask.widthFraction, 0.02f..1.5f) {
                viewModel.updateMask(clip.id, width = it)
            }
        }
        if (mask.shape != MaskShape.Linear) {
            LabeledSlider("Height", mask.heightFraction, 0.02f..1.5f) {
                viewModel.updateMask(clip.id, height = it)
            }
        }
        if (mask.shape == MaskShape.Rectangle) {
            LabeledSlider("Corner round", mask.cornerRadius, 0f..0.5f) {
                viewModel.updateMask(clip.id, cornerRadius = it)
            }
        }

        LabeledSlider("Rotation", mask.rotationDegrees, -180f..180f) {
            viewModel.updateMask(clip.id, rotation = it)
        }
        LabeledSlider("Feather", mask.feather, 0.001f..0.4f) {
            viewModel.updateMask(clip.id, feather = it)
        }
    }
}
