package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.squish.app.timeline.BackgroundFill
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.theme.SquishColors

private val BACKDROPS = listOf(
    0xFF101828.toInt(), 0xFFFFFFFF.toInt(), 0xFF00B140.toInt(), 0xFF2563EB.toInt(),
    0xFFF472B6.toInt(), 0xFFFBBF24.toInt(), 0xFF7C3AED.toInt()
)

/**
 * Background removal: the phone finds the person in every frame, then what is
 * behind them is blurred, painted over or cut out.
 */
@Composable
fun BackgroundPanel(state: EditorUiState, viewModel: EditorViewModel) {
    val clip = viewModel.analysis.backgroundTarget(state) ?: return
    val removal = clip.background
    val progress = state.backgroundProgress

    PanelSurface(accent = SquishColors.Magenta) {
        PanelHeading(
            "Background",
            "Blur or replace what is behind the person",
            icon = Icons.Filled.Person,
            accent = SquishColors.Magenta
        )
        Text(
            when {
                progress.running && progress.total > 0 ->
                    "Finding the person — ${progress.done * 100 / progress.total}%"
                progress.running -> "Finding the person…"
                progress.failed -> "Could not read this clip to find the person."
                removal != null -> "Works best with one person facing the camera."
                else -> "Runs on your phone. Nothing is uploaded."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (progress.failed) SquishColors.Pink else SquishColors.TextMuted
        )

        if (removal != null && !progress.running) {
            // Cut out leaves the person over whatever is underneath, and under
            // the video track that is black - in the preview even over a padded
            // canvas, since the base surface's chain writes alpha 1 (see
            // FloatOffer): the chip is only offered on an overlay, or kept where
            // a shot already has it (an old draft, a pasted setting), so it can
            // still be turned off. The person over a colour is Colour.
            val fills = BackgroundFill.entries.filter {
                it != BackgroundFill.Remove || clip.isOverlay || removal.fill == BackgroundFill.Remove
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fills.forEach { fill ->
                    SelectableChip(
                        label = fill.label,
                        selected = removal.fill == fill,
                        accentColor = SquishColors.Magenta,
                        onClick = { viewModel.analysis.setBackgroundFill(clip.id, fill) }
                    )
                }
            }
            if (removal.fill == BackgroundFill.Colour) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    BACKDROPS.forEach { argb ->
                        // Named and selectable, like every swatch in the app.
                        ColourSwatch(
                            colour = argb,
                            selected = removal.colorArgb == argb,
                            onPick = { viewModel.analysis.setBackgroundFill(clip.id, BackgroundFill.Colour, argb) }
                        )
                    }
                }
            }
            when {
                removal.fill != BackgroundFill.Remove -> Unit
                clip.isOverlay -> Text(
                    "Cut out leaves only the person, over whatever is under this overlay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
                // A shot that carries Cut out (an old draft, a pasted setting):
                // what it shows, and the way to another shot under it. Under
                // Blur or Colour there is nothing to float for, so nothing is
                // offered - the person over a colour is Colour, on any track.
                else -> FloatOffer(
                    state, clip, viewModel,
                    hole = "the cut-out",
                    forColour = "For the person over a plain colour, choose Colour."
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when {
                progress.running -> SquishOutlinedButton(
                    text = "Stop",
                    modifier = Modifier.weight(1f),
                    onClick = viewModel.analysis::cancelBackground
                )
                removal != null -> SquishOutlinedButton(
                    text = "Turn off",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.analysis.setBackground(clip.id, null) }
                )
                else -> SquishOutlinedButton(
                    text = "Remove background",
                    modifier = Modifier.weight(1f),
                    onClick = viewModel.analysis::removeBackground
                )
            }
        }
    }
}
