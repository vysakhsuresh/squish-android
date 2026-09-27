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
    val clip = viewModel.backgroundTarget(state) ?: return
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackgroundFill.entries.forEach { fill ->
                    SelectableChip(
                        label = fill.label,
                        selected = removal.fill == fill,
                        accentColor = SquishColors.Magenta,
                        onClick = { viewModel.setBackgroundFill(clip.id, fill) }
                    )
                }
            }
            if (removal.fill == BackgroundFill.Colour) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    BACKDROPS.forEach { argb ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    if (removal.colorArgb == argb) 3.dp else 1.dp,
                                    if (removal.colorArgb == argb) SquishColors.Primary else SquishColors.Border,
                                    CircleShape
                                )
                                .clickable { viewModel.setBackgroundFill(clip.id, BackgroundFill.Colour, argb) }
                        )
                    }
                }
            }
            if (removal.fill == BackgroundFill.Remove) {
                Text(
                    "Cut out shows black behind the person, or the picture underneath when this clip is a layer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SquishColors.TextMuted
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when {
                progress.running -> SquishOutlinedButton(
                    text = "Stop",
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::cancelBackground
                )
                removal != null -> SquishOutlinedButton(
                    text = "Turn off",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setBackground(clip.id, null) }
                )
                else -> SquishOutlinedButton(
                    text = "Remove background",
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::removeBackground
                )
            }
        }
    }
}
