package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.ChromaKey
import com.squish.app.timeline.Clip
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * Green screen for the selected layer.
 *
 * The sampler is the point of this panel. Screen paint and cloth vary enormously -
 * a cheap fabric under warm light is nowhere near the digital green a preset
 * assumes - so keying against a canned color is guesswork. Tapping the actual
 * screen in your own frame is the difference between a key that works and an
 * afternoon of moving sliders.
 *
 * The screen is picked off the preview itself, with the loupe (EyedropperLayer):
 * dragged over the picture, it reads a patch of pixels averaged, so a grain
 * of compressed video noise is not the colour keyed. It used to be one pixel
 * of a thumbnail in the panel, which was noisy and was not the picture.
 * While the loupe is up the key is not drawn, so the loupe reads the screen's
 * own green and not the hole a first guess has cut (PreviewEngine.setKeyPreview).
 */
@Composable
fun ChromaKeyPanel(clip: Clip, viewModel: EditorViewModel, onEyedropper: ((Int) -> Unit) -> Unit) {
    val key = clip.chromaKey

    PanelSurface(accent = SquishColors.Magenta) {
        PanelHeading(
            "Green screen",
            if (clip.isOverlay) "Cut a colour out of this overlay" else "Cut a colour out of an overlay",
            icon = Icons.Filled.Colorize,
            accent = SquishColors.Magenta,
            trailing = {
                if (key != null) {
                    Text(
                        "Turn off",
                        style = MaterialTheme.typography.labelSmall,
                        color = SquishColors.Pink,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(SquishColors.Background)
                            .pointerInput(clip.id) {
                                detectTapGestures { viewModel.layers.setChromaKey(clip.id, null) }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        )

        // A key cuts a hole through to what is underneath, and under the video
        // track there is nothing: the file shows black there. So on a shot the
        // key buttons are not offered at all - they were, under a line saying
        // they were pointless - and the one useful step is, which is to lift
        // the shot onto an overlay row over the shot that should show through.
        // A key a shot already carries (pasted, or from an old draft) keeps its
        // Turn off above.
        if (!clip.isOverlay) {
            Text(
                "A key cuts a hole through to whatever is underneath. On the video track nothing is " +
                    "under this clip, so float it over another shot first.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            SquishOutlinedButton(text = "Float this clip", modifier = Modifier.fillMaxWidth()) {
                viewModel.layers.switchToOverlay(clip.id)
            }
            return@PanelSurface
        }

        if (key == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SquishOutlinedButton(text = "Key green", modifier = Modifier.weight(1f)) {
                    viewModel.layers.setChromaKey(clip.id, ChromaKey(keyColorArgb = ChromaKey.STANDARD_GREEN))
                }
                SquishOutlinedButton(text = "Key blue", modifier = Modifier.weight(1f)) {
                    viewModel.layers.setChromaKey(clip.id, ChromaKey(keyColorArgb = ChromaKey.STANDARD_BLUE))
                }
            }
            SquishOutlinedButton(text = "Pick the screen from the picture", modifier = Modifier.fillMaxWidth()) {
                onEyedropper { colour -> viewModel.layers.setChromaKey(clip.id, ChromaKey(keyColorArgb = colour)) }
            }
            return@PanelSurface
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(key.keyColorArgb))
                    .border(1.dp, SquishColors.Border, RoundedCornerShape(8.dp))
            )
            Text(
                "The colour being cut out",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            SquishOutlinedButton(text = "Pick") {
                onEyedropper { colour -> viewModel.layers.updateChromaKey(clip.id, keyColorArgb = colour) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SelectableChip(
                label = "Green",
                selected = key.keyColorArgb == ChromaKey.STANDARD_GREEN,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.layers.updateChromaKey(clip.id, keyColorArgb = ChromaKey.STANDARD_GREEN) }
            )
            SelectableChip(
                label = "Blue",
                selected = key.keyColorArgb == ChromaKey.STANDARD_BLUE,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.layers.updateChromaKey(clip.id, keyColorArgb = ChromaKey.STANDARD_BLUE) }
            )
        }

        LabeledSlider("Similarity", key.similarity, ChromaKey.SIMILARITY_RANGE, onFinished = viewModel::endGesture) {
            viewModel.layers.updateChromaKey(clip.id, similarity = it)
        }
        LabeledSlider("Edge softness", key.smoothness, 0.005f..0.3f, onFinished = viewModel::endGesture) {
            viewModel.layers.updateChromaKey(clip.id, smoothness = it)
        }
        LabeledSlider("Spill removal", key.spill, 0.005f..0.3f, onFinished = viewModel::endGesture) {
            viewModel.layers.updateChromaKey(clip.id, spill = it)
        }

        if (key.keyColorArgb == ChromaKey.STANDARD_BLUE) {
            Text(
                "Blue screens and denim are nearly the same color once luma is thrown away, " +
                    "so blue jeans will key out along with the screen. Green is the safer screen " +
                    "unless the subject is wearing green.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.Yellow
            )
        }
        Text(
            "Similarity decides how much counts as background. Softness feathers the edge. " +
                "Spill pulls the screen's color back out of hair and shoulders.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}
