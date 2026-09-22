package com.squish.app.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
 */
@Composable
fun ChromaKeyPanel(clip: Clip, playheadMs: Long, viewModel: EditorViewModel) {
    val key = clip.chromaKey

    PanelSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelHeading("Green screen", "Cut a color out of this layer")
            if (key != null) {
                Text(
                    "Turn off",
                    style = MaterialTheme.typography.labelSmall,
                    color = SquishColors.Pink,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(SquishColors.Background)
                        .pointerInput(clip.id) {
                            detectTapGestures { viewModel.setChromaKey(clip.id, null) }
                        }
                )
            }
        }

        if (key == null) {
            Text(
                "Keying only makes sense on a layer — cutting the base track just reveals black.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SquishOutlinedButton(text = "Key green", modifier = Modifier.weight(1f)) {
                    viewModel.setChromaKey(clip.id, ChromaKey(keyColorArgb = ChromaKey.STANDARD_GREEN))
                }
                SquishOutlinedButton(text = "Key blue", modifier = Modifier.weight(1f)) {
                    viewModel.setChromaKey(clip.id, ChromaKey(keyColorArgb = ChromaKey.STANDARD_BLUE))
                }
            }
            return@PanelSurface
        }

        FrameSampler(clip = clip, playheadMs = playheadMs, viewModel = viewModel, current = key)

        LabeledSlider("Similarity", key.similarity, ChromaKey.SIMILARITY_RANGE) {
            viewModel.updateChromaKey(clip.id, similarity = it)
        }
        LabeledSlider("Edge softness", key.smoothness, 0.005f..0.3f) {
            viewModel.updateChromaKey(clip.id, smoothness = it)
        }
        LabeledSlider("Spill removal", key.spill, 0.005f..0.3f) {
            viewModel.updateChromaKey(clip.id, spill = it)
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

@Composable
private fun FrameSampler(
    clip: Clip,
    playheadMs: Long,
    viewModel: EditorViewModel,
    current: ChromaKey
) {
    var frame by remember(clip.id) { mutableStateOf<Bitmap?>(null) }
    var stale by remember(clip.id) { mutableStateOf(true) }

    // Keyed on the clip, not the playhead: re-decoding a frame on every millisecond
    // of playback would be absurd. The button below re-samples on demand.
    LaunchedEffect(clip.id, stale) {
        if (stale) {
            frame = viewModel.sampleFrame(clip, playheadMs)
            stale = false
        }
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
                .background(Color(current.keyColorArgb))
                .border(1.dp, SquishColors.Border, RoundedCornerShape(8.dp))
        )
        Text(
            "Tap the screen in the frame to sample it",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        SquishOutlinedButton(text = "Refresh") { stale = true }
    }

    val bitmap = frame
    if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Tap to sample the screen color",
            // FillBounds against the bitmap's own aspect ratio: no distortion, and
            // the tap maps to a pixel by plain proportion rather than by unpicking
            // whatever letterboxing a Fit would have introduced.
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, SquishColors.Border, RoundedCornerShape(10.dp))
                .pointerInput(clip.id, bitmap) {
                    detectTapGestures { offset ->
                        val px = (offset.x / size.width * bitmap.width).toInt()
                            .coerceIn(0, bitmap.width - 1)
                        val py = (offset.y / size.height * bitmap.height).toInt()
                            .coerceIn(0, bitmap.height - 1)
                        viewModel.updateChromaKey(clip.id, keyColorArgb = bitmap.getPixel(px, py))
                    }
                }
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        SelectableChip(
            label = "Green",
            selected = current.keyColorArgb == ChromaKey.STANDARD_GREEN,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.updateChromaKey(clip.id, keyColorArgb = ChromaKey.STANDARD_GREEN) }
        )
        SelectableChip(
            label = "Blue",
            selected = current.keyColorArgb == ChromaKey.STANDARD_BLUE,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.updateChromaKey(clip.id, keyColorArgb = ChromaKey.STANDARD_BLUE) }
        )
    }
}
