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
 * Chroma key for the selected clip: a green or blue screen cut out.
 *
 * On a main-track shot every control is here too, since a shot can carry a key
 * (pasted, or from an old draft) and it has to stay adjustable - but the hole
 * shows black there, and the sheet says so and offers to float the shot over
 * the next one (FloatOffer).
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
fun ChromaKeyPanel(state: EditorUiState, clip: Clip, viewModel: EditorViewModel, onEyedropper: ((Int) -> Unit) -> Unit) {
    val key = clip.chromaKey

    PanelSurface(accent = SquishColors.Magenta) {
        // Named as the chip that opens it is: it read "Green screen" under a
        // "Chroma key" chip.
        PanelHeading(
            "Chroma key",
            if (clip.isOverlay) "Cut a green or blue screen out of this overlay" else "Cut a green or blue screen out of this shot",
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
        // track there is nothing: black, in the preview and the file. So on a
        // shot the key buttons are not offered - they were, under a line saying
        // they were pointless - and the one useful step is: float the shot,
        // as it stands, over the shot that should show through. A key a shot
        // already carries (pasted, or from an old draft) keeps every control,
        // so it can be adjusted or turned off; the offer sits under them.
        if (key == null) {
            if (!clip.isOverlay) {
                FloatOffer(state, clip, viewModel, hole = "the keyed colour")
                return@PanelSurface
            }
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
        if (!clip.isOverlay) FloatOffer(state, clip, viewModel, hole = "the keyed colour")
    }
}
