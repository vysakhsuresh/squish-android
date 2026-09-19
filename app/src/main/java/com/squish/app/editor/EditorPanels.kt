package com.squish.app.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.squish.app.home.formatSize
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import com.squish.app.ui.theme.SquishColors

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
}

@Composable
fun FitToSizeCard(state: EditorUiState, viewModel: EditorViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SquishColors.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Fit to a size", style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
                Text("We pick the bitrate for you", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            }
            SquishToggleSwitch(checked = state.fitToSize, onCheckedChange = viewModel::setFitToSize)
        }
        if (state.fitToSize) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(16, 25, 50).forEach { mb ->
                    SelectableChip(
                        label = "$mb MB",
                        selected = state.targetSizeMb == mb,
                        accentColor = SquishColors.Teal,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setTargetSizeMb(mb) }
                    )
                }
            }
        }
    }
}

@Composable
fun EstimateCard(state: EditorUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SquishColors.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Original", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text(formatSize(state.originalSizeBytes), style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Estimated output", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text(formatSize(state.estimatedOutputBytes), style = MaterialTheme.typography.titleSmall, color = SquishColors.Teal)
        }
    }
}

@Composable
fun SpeedSlider(speed: Float, onSpeedChange: (Float) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Speed", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text("${"%.2f".format(speed)}x", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
        }
        Slider(
            value = speed,
            onValueChange = onSpeedChange,
            valueRange = 0.5f..2f,
            colors = SliderDefaults.colors(
                thumbColor = SquishColors.Coral,
                activeTrackColor = SquishColors.Coral,
                inactiveTrackColor = SquishColors.Border
            )
        )
    }
}

@Composable
fun OptionToggle(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: (Boolean) -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) SquishColors.Coral.copy(alpha = 0.15f) else SquishColors.Surface)
            .clickable { onClick(!active) }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) SquishColors.Coral else SquishColors.TextSecondary,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val readout = if (range.start < 0f) "%+.0f%%".format(value * 100) else "%.0f%%".format(value * 100)
    Column {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text(readout, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = SquishColors.Teal,
                activeTrackColor = SquishColors.Teal,
                inactiveTrackColor = SquishColors.Border
            )
        )
    }
}

@Composable
fun TextOverlaySection(state: EditorUiState, viewModel: EditorViewModel) {
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.textOverlays.forEach { overlay ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SquishColors.Surface).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(overlay.text, color = SquishColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Remove",
                    color = SquishColors.Pink,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable { viewModel.removeTextOverlay(overlay.id) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add a caption…", color = SquishColors.TextMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SquishColors.Coral,
                    unfocusedBorderColor = SquishColors.Border,
                    focusedTextColor = SquishColors.TextPrimary,
                    unfocusedTextColor = SquishColors.TextPrimary
                )
            )
            SquishOutlinedButton(text = "Add") {
                if (draft.text.isNotBlank()) {
                    viewModel.addTextOverlay(draft.text)
                    draft = TextFieldValue("")
                }
            }
        }
    }
}

@Composable
fun MergeQueueSection(state: EditorUiState, onAddClip: () -> Unit, onRemoveClip: (Uri) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.clipQueue.forEachIndexed { index, uri ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SquishColors.Surface).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Clip ${index + 2}", color = SquishColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Remove",
                    color = SquishColors.Pink,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable { onRemoveClip(uri) }
                )
            }
        }
        SquishOutlinedButton(text = "+ Add another clip", onClick = onAddClip)
    }
}
