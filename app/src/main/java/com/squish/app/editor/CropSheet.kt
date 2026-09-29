package com.squish.app.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.Clip
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

/**
 * A clip's own crop: the shape its window is held to, the straighten dial, and
 * the two flips. The window itself is dragged on the picture, where the
 * preview shows the clip plain - uncropped, unplaced - with the window over it
 * (TimelinePreview's picture tool), as CapCut's crop screen does. Reset is on
 * the sheet's title.
 *
 * @param pictureAspect the clip's own picture, width over height, so a shape
 *   chip knows what a square is in fractions of it.
 */
@Composable
fun CropPanel(clip: Clip, pictureAspect: Float, viewModel: EditorViewModel, accent: androidx.compose.ui.graphics.Color) {
    val crop = clip.crop ?: ClipCrop()
    PanelSurface(accent = accent) {
        PanelHeading(
            "Crop",
            "Drag the window on the picture · the part outside it goes",
            icon = Icons.Filled.Crop,
            accent = accent
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            CropRatio.entries.forEach { ratio ->
                SelectableChip(
                    label = ratio.label,
                    selected = crop.ratio == ratio,
                    accentColor = accent,
                    onClick = { viewModel.clips.setClipCropRatio(clip.id, ratio, pictureAspect) }
                )
            }
        }
        LabeledSlider(
            "Straighten", crop.straightenDegrees, -CropRules.MAX_STRAIGHTEN_DEGREES..CropRules.MAX_STRAIGHTEN_DEGREES,
            readout = Readout.degrees,
            onFinished = viewModel::endGesture
        ) {
            viewModel.clips.setClipStraighten(clip.id, it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SquishOutlinedButton(
                text = if (crop.flipHorizontal) "Flipped ↔" else "Flip ↔",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.clips.flipClip(clip.id, horizontal = true) }
            )
            SquishOutlinedButton(
                text = if (crop.flipVertical) "Flipped ↕" else "Flip ↕",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.clips.flipClip(clip.id, horizontal = false) }
            )
        }
        Text(
            "The picture turns and zooms under the window, so straightening never shows past its corners. " +
                "The cropped picture is fitted to the frame; Placement makes it bigger.",
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
    }
}
