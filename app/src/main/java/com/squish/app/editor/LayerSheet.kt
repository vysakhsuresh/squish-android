package com.squish.app.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.timeline.MAX_LAYER
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors

/**
 * An overlay's opacity. Its own tool on the overlay's toolbar; it was one slider
 * among the Blend panel's Size, Across and Up / down. Placement is done on the
 * picture now - the box, its handles, two fingers - and on the Placement sheet,
 * both through the one way placement is written (ClipEdits.setClipTransform).
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
            viewModel.layers.setOpacity(clip.id, it)
        }
    }
}

/**
 * Which row an overlay sits on - what it is drawn over and under - and the way
 * down onto the main track.
 */
@Composable
fun LayerPanel(clip: Clip, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Magenta) {
        PanelHeading(
            "Row ${clip.layer} of $MAX_LAYER",
            "Higher rows are drawn over lower ones",
            icon = Icons.Filled.Layers,
            accent = SquishColors.Magenta
        )
        // Only the moves there are: from the lowest row, down is the main track,
        // which is the button below and not a row.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (clip.layer > 1) {
                SquishOutlinedButton(text = "Send back", modifier = Modifier.weight(1f)) {
                    viewModel.layers.changeLayer(clip.id, -1)
                }
            }
            if (clip.layer < MAX_LAYER) {
                SquishOutlinedButton(text = "Bring forward", modifier = Modifier.weight(1f)) {
                    viewModel.layers.changeLayer(clip.id, +1)
                }
            }
        }
        SquishOutlinedButton(text = "Move to main track", modifier = Modifier.fillMaxWidth()) {
            viewModel.layers.switchToMain(clip.id)
        }
        Text(
            "A row that is taken at this moment is skipped for the next free one. On the main track " +
                "the clip goes in at the playhead, full frame, and the shots after it move along.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}

/**
 * A shot's or an overlay's own sound: a level, and a switch to silence it that
 * gives the level back when it is switched on again.
 *
 * A shot is also under the camera sound for the whole edit (Sound, Voice & FX);
 * when that is off, the sheet says so, rather than showing a level nobody hears.
 */
@Composable
fun ClipVolumePanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel) {
    // The level to come back to after Mute. Kept per clip, across a rotation.
    var lastHeard by rememberSaveable(clip.id) { mutableFloatStateOf(clip.volume.takeIf { it > 0f } ?: 1f) }
    if (clip.volume > 0f && clip.volume != lastHeard) lastHeard = clip.volume
    val accent = if (clip.isOverlay) SquishColors.Magenta else SquishColors.Violet
    val muted = clip.volume <= 0f
    PanelSurface(accent = accent) {
        PanelHeading(
            if (clip.isOverlay) "Overlay sound" else "Clip sound",
            "This clip's own sound, mixed with the rest",
            icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            accent = accent,
            trailing = {
                SquishToggleSwitch(
                    checked = !muted,
                    onCheckedChange = { on -> viewModel.clips.setClipMuted(clip.id, muted = !on, restoreTo = lastHeard) }
                )
            }
        )
        LabeledSlider("Level", clip.volume, 0f..1f, onFinished = viewModel::endGesture) {
            viewModel.clips.setClipVolume(clip.id, it)
        }
        if (!clip.isOverlay && state.muteOriginal) {
            Text(
                "Camera sound is off for the whole edit, so this clip is silent. Turn it back on in " +
                    "Sound, Voice & FX.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Amber
            )
        } else if (!clip.isOverlay && state.originalVolume < 0.999f) {
            Text(
                "Heard at this level under the camera sound for the whole edit " +
                    "(${(state.originalVolume * 100).toInt()}%, in Sound, Voice & FX).",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        }
    }
}
