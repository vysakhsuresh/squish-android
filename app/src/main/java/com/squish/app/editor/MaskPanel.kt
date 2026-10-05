package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Mask
import com.squish.app.timeline.hasShapeKeyNear
import com.squish.app.timeline.MaskMode
import com.squish.app.timeline.MaskShape
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors

/**
 * Shape masks for the selected clip.
 *
 * The mask is applied by the preview's own shader, so what the sliders move is
 * the finished result, live; its edge is drawn over the picture as well
 * (MaskOutlineLayer), since with a wide feather there was no telling where
 * the edge was, and the shape is moved there with a finger.
 */
@Composable
fun MaskPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    // The shape at the playhead, which on a keyed mask is not the stored one:
    // the sliders show and set the shape the picture is being cut to.
    val sourceMs = clip.sourceAt(state.playheadMs)
    val held = clip.mask
    val mask = held?.at(sourceMs)
    val onClip = state.playheadMs in clip.timelineStartMs..clip.timelineEndMs

    PanelSurface(accent = SquishColors.Magenta) {
        PanelHeading(
            "Mask",
            "Show only part of this clip",
            icon = Icons.Filled.CenterFocusStrong,
            accent = SquishColors.Magenta,
            trailing = {
                if (mask != null) {
                    com.squish.app.ui.components.TextAction("Turn off", color = SquishColors.Pink) {
                        viewModel.layers.setMask(clip.id, null)
                    }
                }
            }
        )

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
                onClick = { viewModel.layers.setMask(clip.id, Mask()) }
            )
            return@PanelSurface
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            MaskMode.entries.forEach { mode ->
                SelectableChip(
                    label = mode.label,
                    selected = mask.mode == mode,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.layers.updateMask(clip.id, mode = mode) }
                )
            }
        }
        Text(
            when (mask.mode) {
                MaskMode.Cutout -> "Keeps the shape and hides the rest of the clip."
                MaskMode.Pixelate -> "Keeps the whole picture and pixelates what is inside the shape."
                MaskMode.Blur -> "Keeps the whole picture and blurs what is inside the shape."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )

        if (mask.mode != MaskMode.Cutout) {
            LabeledSlider("Obscure strength", mask.strength, 0f..1f, onFinished = viewModel::endGesture) {
                viewModel.layers.updateMask(clip.id, strength = it)
            }
            if (mask.track != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Following a track · ${(mask.track.heldFraction * 100).toInt()}% held",
                        style = MaterialTheme.typography.bodySmall,
                        color = SquishColors.Teal,
                        modifier = Modifier.weight(1f)
                    )
                    com.squish.app.ui.components.TextAction("Unpin", color = SquishColors.Pink) { viewModel.analysis.unpinMask(clip.id) }
                }
            }
        }

        // The shape keyed, which is the half of keyframing a mask that its
        // track does not cover: a track follows something in the picture, this
        // is drawn by hand - a circle grown over four seconds, a letterbox slid
        // open. A tracked mask takes its centre from the track every frame, so
        // keying it as well would be two things fighting over one number.
        if (mask.track == null) {
            KeyframeButton(
                keyed = held?.keys?.hasShapeKeyNear(sourceMs) == true,
                count = held?.keys?.size ?: 0,
                onClip = onClip,
                accent = SquishColors.Magenta,
                onToggle = { viewModel.layers.toggleMaskKey(clip.id) },
                onClear = { viewModel.layers.clearMaskKeys(clip.id) }
            )
        }

        // Six shapes now, which is more than fit across a phone: the row scrolls.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            MaskShape.entries.forEach { shape ->
                SelectableChip(
                    label = shape.label,
                    selected = mask.shape == shape,
                    onClick = { viewModel.layers.updateMask(clip.id, shape = shape) }
                )
            }
        }
        Text(
            if (mask.track == null) "Drag the shape on the picture to move it." else "The shape follows its track on the picture.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // What the switch does depends on the mode, and the words were only
            // right for Cut out. Pixelate and Blur destroy what is *inside* the
            // shape, so un-inverted they are hiding it and inverted they are the
            // one thing left clear - the Track tool's "Hide a face or plate
            // here" makes a Pixelate mask, and the row under it said "Keeping
            // the shape" over a pixelated face.
            Text(
                when (mask.mode) {
                    MaskMode.Cutout -> if (mask.inverted) "Hiding the shape" else "Keeping the shape"
                    else -> if (mask.inverted) "Everything but the shape" else "The shape"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary
            )
            SquishToggleSwitch(
                checked = mask.inverted,
                onCheckedChange = { viewModel.layers.updateMask(clip.id, inverted = it) }
            )
        }

        // A pinned mask takes its center from the track every frame, so these two
        // would be controls that visibly do nothing.
        if (mask.track == null) {
            LabeledSlider("Across", mask.centerXFraction, -1f..1f, onFinished = viewModel::endGesture) {
                viewModel.layers.updateMask(clip.id, centerX = it)
            }
            // Negated: a mask's own y runs *up* (the shader subtracts the centre
            // in texture coordinates, and MaskOutline.dragged returns -dy), while
            // every other "Up / down" on every other sheet - a sticker's, a
            // shape's, Placement's - runs down. Two identically labelled sliders
            // that went opposite ways was the complaint; the label reads left to
            // right, so right is down here as it is everywhere else.
            LabeledSlider("Up / down", -mask.centerYFraction, -1f..1f, onFinished = viewModel::endGesture) {
                viewModel.layers.updateMask(clip.id, centerY = -it)
            }
        }

        // Linear takes its edge from the center and the angle alone, and Mirror is a
        // band with no width - offering sliders that do nothing would just invite
        // someone to drag them and conclude the mask is broken.
        if (mask.shape.hasBox) {
            LabeledSlider("Width", mask.widthFraction, 0.02f..1.5f, onFinished = viewModel::endGesture) {
                viewModel.layers.updateMask(clip.id, width = it)
            }
        }
        if (mask.shape != MaskShape.Linear) {
            LabeledSlider("Height", mask.heightFraction, 0.02f..1.5f, onFinished = viewModel::endGesture) {
                viewModel.layers.updateMask(clip.id, height = it)
            }
        }
        if (mask.shape == MaskShape.Rectangle) {
            LabeledSlider("Corner round", mask.cornerRadius, 0f..0.5f, onFinished = viewModel::endGesture) {
                viewModel.layers.updateMask(clip.id, cornerRadius = it)
            }
        }

        LabeledSlider(
            "Rotation", mask.rotationDegrees, -180f..180f,
            readout = Readout.degrees,
            onFinished = viewModel::endGesture
        ) {
            viewModel.layers.updateMask(clip.id, rotation = it)
        }
        LabeledSlider("Feather", mask.feather, 0.001f..0.4f, onFinished = viewModel::endGesture) {
            viewModel.layers.updateMask(clip.id, feather = it)
        }
    }
}
