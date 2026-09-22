package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
fun PanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SquishColors.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun PanelHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
    }
}

// ---- Trim -------------------------------------------------------------------

@Composable
fun PrecisionTrimPanel(state: EditorUiState, viewModel: EditorViewModel) {
    // These controls trim a clip on the timeline, so they must read that clip's
    // source window - not the old whole-video trim range, which the exporter no
    // longer consults now that the timeline is authoritative.
    val clip = viewModel.trimTargetClip(state)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface {
            PanelHeading(
                "In and out points",
                if (clip == null) "Add a clip to trim" else "Trimming ${clip.label}"
            )
            TrimPointRow(
                label = "In",
                value = Timecode.formatWithFrame(clip?.sourceInMs ?: 0L, state.fps),
                onNudgeBack = { viewModel.nudgeTrim(isStart = true, frames = -1) },
                onNudgeForward = { viewModel.nudgeTrim(isStart = true, frames = 1) },
                onSetToPlayhead = { viewModel.setTrimPointToPlayhead(isStart = true) }
            )
            TrimPointRow(
                label = "Out",
                value = Timecode.formatWithFrame(clip?.sourceOutMs ?: 0L, state.fps),
                onNudgeBack = { viewModel.nudgeTrim(isStart = false, frames = -1) },
                onNudgeForward = { viewModel.nudgeTrim(isStart = false, frames = 1) },
                onSetToPlayhead = { viewModel.setTrimPointToPlayhead(isStart = false) }
            )
            Text(
                "${Timecode.format(clip?.durationMs ?: 0L)} this shot · " +
                    "${Timecode.format(state.trimmedDurationMs)} total · ${state.fps.toInt()} fps",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
        }

        PanelSurface {
            PanelHeading("Markers", "Drop a mark at the playhead and snap cuts to it")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SquishOutlinedButton(
                    text = "Drop marker",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.addMarkerAtPlayhead() }
                )
                SquishOutlinedButton(
                    text = if (state.markers.isEmpty()) "No markers" else "Clear ${state.markers.size}",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.clearMarkers() }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Snap to markers", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
                SquishToggleSwitch(checked = state.snapToMarkers, onCheckedChange = viewModel::setSnapToMarkers)
            }
        }
    }
}

@Composable
private fun TrimPointRow(
    label: String,
    value: String,
    onNudgeBack: () -> Unit,
    onNudgeForward: () -> Unit,
    onSetToPlayhead: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = SquishColors.Coral, modifier = Modifier.width(30.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary, modifier = Modifier.weight(1f))
        NudgeButton("◀", Modifier.width(44.dp), onNudgeBack)
        NudgeButton("▶", Modifier.width(44.dp), onNudgeForward)
        NudgeButton("Set", Modifier.width(54.dp), onSetToPlayhead)
    }
}

// ---- Crop -------------------------------------------------------------------

@Composable
fun CropPanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelSurface {
        PanelHeading("Shape", "Crop to the aspect ratio you are posting to")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CropAspect.entries.forEach { aspect ->
                SelectableChip(
                    label = aspect.label,
                    selected = state.cropAspect == aspect,
                    accentColor = SquishColors.Purple,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setCropAspect(aspect) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Rotation", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
                Text("${state.rotationDegrees}°", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextMuted)
            }
            SquishOutlinedButton(text = "Rotate 90°", onClick = { viewModel.toggleRotate() })
        }
    }
}

// ---- Speed ------------------------------------------------------------------

@Composable
fun SpeedPanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelSurface {
        PanelHeading("Speed", "Pitch stays natural as the tempo changes")
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Playback", style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text("${"%.2f".format(state.speed)}x", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
        }
        Slider(
            value = state.speed,
            onValueChange = viewModel::setSpeed,
            valueRange = 0.5f..2f,
            colors = SliderDefaults.colors(
                thumbColor = SquishColors.Coral,
                activeTrackColor = SquishColors.Coral,
                inactiveTrackColor = SquishColors.Border
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(0.5f, 1f, 1.5f, 2f).forEach { preset ->
                SelectableChip(
                    label = "${"%.1f".format(preset)}x",
                    selected = state.speed == preset,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setSpeed(preset) }
                )
            }
        }
    }
}

// ---- Colour -----------------------------------------------------------------

// ---- Export -----------------------------------------------------------------

@Composable
fun ExportPanel(state: EditorUiState, viewModel: EditorViewModel, onAddClip: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface {
            PanelHeading("Quality", "Bigger means sharper and heavier")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Quality.entries.forEach { quality ->
                    SelectableChip(
                        label = quality.label,
                        selected = state.quality == quality && !state.fitToSize,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.setFitToSize(false)
                            viewModel.setQuality(quality)
                        }
                    )
                }
            }
        }

        PanelSurface {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelHeading("Fit to a size", "We pick the bitrate for you")
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

        EstimateCard(state)

        PanelSurface {
            PanelHeading("Video track", "Played one after another, in order")
            state.videoClips.forEachIndexed { index, clip ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}. ${clip.label}",
                        color = SquishColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Timecode.format(clip.durationMs),
                        color = SquishColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            SquishOutlinedButton(text = "Add another clip", modifier = Modifier.fillMaxWidth(), onClick = onAddClip)
        }
    }
}

@Composable
fun EstimateCard(state: EditorUiState) {
    PanelSurface {
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

// ---- Shared -----------------------------------------------------------------

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
