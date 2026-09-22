package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.Keyframe
import com.squish.app.timeline.KeyframeEasing
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * Motion: where a clip's picture sits, and how it moves while the clip plays.
 *
 * The sliders always edit *the moment the playhead is on*. On a still clip that is
 * simply its placement; on an animated one it becomes a keyframe there. One
 * control, one meaning, whichever mode the clip is in.
 */
@Composable
fun MotionPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val clip = state.targetVideoClip

    if (clip == null) {
        PanelSurface {
            PanelHeading("Nothing to move", "Add a clip to the timeline first")
        }
        return
    }

    val here = clip.transformAt(state.playheadMs)
    val animated = clip.keyframes.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        PanelSurface {
            PanelHeading(
                clip.label,
                if (animated) "${clip.keyframes.size} keys · moves while it plays"
                else "Sitting still — add a move below"
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MotionPreset.entries.take(3).forEach { preset ->
                    SelectableChip(
                        label = preset.label,
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.applyMotionPreset(clip.id, preset) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                MotionPreset.entries.drop(3).forEach { preset ->
                    SelectableChip(
                        label = preset.label,
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.applyMotionPreset(clip.id, preset) }
                    )
                }
            }
            Text(
                "A preset lays two keys across the whole clip. Adjust them below, or add your own.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }

        PanelSurface {
            PanelHeading(
                if (animated) "At ${Timecode.format(state.playheadMs)}" else "Placement",
                if (animated) "Changing these sets a key at the playhead" else "Where the picture sits"
            )
            LabeledSlider("Scale", here.scale, 0.2f..3f) {
                viewModel.setClipTransform(clip.id, scale = it)
            }
            LabeledSlider("Across", here.offsetXFraction, -1f..1f) {
                viewModel.setClipTransform(clip.id, offsetX = it)
            }
            LabeledSlider("Up / down", here.offsetYFraction, -1f..1f) {
                viewModel.setClipTransform(clip.id, offsetY = it)
            }
            LabeledSlider("Rotation", here.rotationDegrees, -45f..45f) {
                viewModel.setClipTransform(clip.id, rotation = it)
            }
        }

        PanelSurface {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelHeading("Keyframes", "Control points along the clip")
                if (animated) {
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Pink,
                        modifier = Modifier.clickable { viewModel.clearKeyframes(clip.id) }
                    )
                }
            }

            SquishOutlinedButton(
                text = "Add key at playhead",
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.addKeyframeAtPlayhead(clip.id) }
            )

            if (!animated) {
                Text(
                    "With one key the clip holds that position. Two or more and it moves between them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            } else {
                clip.keyframes.forEach { key ->
                    KeyRow(
                        clip = clip,
                        key = key,
                        atPlayhead = isUnderPlayhead(clip, key, state),
                        onGoTo = { viewModel.scrubTo(clip.timelineStartMs + key.atMs) },
                        onEasing = { viewModel.setKeyframeEasing(clip.id, key.atMs, it) },
                        onRemove = { viewModel.removeKeyframe(clip.id, key.atMs) }
                    )
                }
            }
        }
    }
}

private fun isUnderPlayhead(clip: Clip, key: Keyframe, state: EditorUiState): Boolean {
    val at = state.playheadMs - clip.timelineStartMs
    return kotlin.math.abs(at - key.atMs) <= state.frameMs
}

@Composable
private fun KeyRow(
    clip: Clip,
    key: Keyframe,
    atPlayhead: Boolean,
    onGoTo: () -> Unit,
    onEasing: (KeyframeEasing) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (atPlayhead) SquishColors.SurfaceElevated else SquishColors.Background)
            .border(
                width = 1.dp,
                color = if (atPlayhead) SquishColors.Amber else SquishColors.Border,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).clickable(onClick = onGoTo)) {
                Text(
                    "◆ ${Timecode.format(key.atMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (atPlayhead) SquishColors.Amber else SquishColors.TextPrimary
                )
                Text(
                    "${(key.transform.scale * 100).toInt()}%" +
                        if (key.transform.rotationDegrees != 0f) " · ${key.transform.rotationDegrees.toInt()}°" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.TextMuted
                )
            }
            Text(
                "Remove",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.Pink,
                modifier = Modifier.clickable(onClick = onRemove)
            )
        }

        // Easing on the last key would describe a segment that does not exist.
        if (key.atMs != clip.keyframes.last().atMs) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                KeyframeEasing.entries.forEach { easing ->
                    SelectableChip(
                        label = easing.label,
                        selected = key.easing == easing,
                        modifier = Modifier.weight(1f),
                        onClick = { onEasing(easing) }
                    )
                }
            }
        }
    }
}
