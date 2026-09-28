package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.squish.app.home.formatSize
import com.squish.app.ui.components.OutputSizePicker
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.components.SquishOutlinedButton
import com.squish.app.ui.components.SquishToggleSwitch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.squish.app.ui.components.SectionHeading
import com.squish.app.ui.components.SquishCard
import com.squish.app.ui.theme.SquishColors

@Composable
fun PanelSurface(accent: Color? = null, content: @Composable ColumnScope.() -> Unit) =
    SquishCard(accent = accent, content = content)

/**
 * A panel heading, optionally with its section's colour beside it. The icon is
 * optional so the fifteen existing headings keep working untouched, and the ones
 * where a glyph actually helps can opt in.
 */
@Composable
fun PanelHeading(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    accent: Color = SquishColors.Primary,
    trailing: @Composable (() -> Unit)? = null
) = SectionHeading(
    title = title,
    subtitle = subtitle,
    icon = icon,
    accent = accent,
    trailing = trailing
)

// ---- Trim -------------------------------------------------------------------

@Composable
fun PrecisionTrimPanel(state: EditorUiState, viewModel: EditorViewModel) {
    // These controls trim a clip on the timeline, so they must read that clip's
    // source window - not the old whole-video trim range, which the exporter no
    // longer consults now that the timeline is authoritative.
    val clip = viewModel.clips.trimTargetClip(state)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Violet) {
            PanelHeading(
                "In and out points",
                if (clip == null) "Add a clip to trim" else "Trimming ${clip.label}",
                icon = Icons.Filled.ContentCut,
                accent = SquishColors.Violet
            )
            TrimPointRow(
                label = "In",
                value = Timecode.formatWithFrame(clip?.sourceInMs ?: 0L, state.fps),
                onNudgeBack = { viewModel.clips.nudgeTrim(isStart = true, frames = -1) },
                onNudgeForward = { viewModel.clips.nudgeTrim(isStart = true, frames = 1) },
                onSetToPlayhead = { viewModel.clips.setTrimPointToPlayhead(isStart = true) }
            )
            TrimPointRow(
                label = "Out",
                value = Timecode.formatWithFrame(clip?.sourceOutMs ?: 0L, state.fps),
                onNudgeBack = { viewModel.clips.nudgeTrim(isStart = false, frames = -1) },
                onNudgeForward = { viewModel.clips.nudgeTrim(isStart = false, frames = 1) },
                onSetToPlayhead = { viewModel.clips.setTrimPointToPlayhead(isStart = false) }
            )
            Text(
                "${Timecode.format(clip?.durationMs ?: 0L)} this shot · " +
                    "${Timecode.format(state.trimmedDurationMs)} total · ${state.fps.toInt()} fps",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextSecondary
            )
        }

        PanelSurface(accent = SquishColors.Violet) {
            PanelHeading(
                "Markers",
                "Drop a mark at the playhead and snap cuts to it",
                icon = Icons.Filled.Flag,
                accent = SquishColors.Violet
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SquishOutlinedButton(
                    text = "Drop marker",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.clips.addMarkerAtPlayhead() }
                )
                SquishOutlinedButton(
                    text = if (state.markers.isEmpty()) "No markers" else "Clear ${state.markers.size}",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.clips.clearMarkers() }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Snap to markers", style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary)
                SquishToggleSwitch(checked = state.snapToMarkers, onCheckedChange = viewModel.clips::setSnapToMarkers)
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
        Text(label, style = MaterialTheme.typography.titleSmall, color = SquishColors.Primary, modifier = Modifier.width(30.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SquishColors.TextPrimary, modifier = Modifier.weight(1f))
        NudgeButton("◀", Modifier.width(44.dp), onNudgeBack)
        NudgeButton("▶", Modifier.width(44.dp), onNudgeForward)
        NudgeButton("Set", Modifier.width(54.dp), onSetToPlayhead)
    }
}

// ---- Crop -------------------------------------------------------------------

/**
 * Auto-reframe: a crop that follows the subject instead of sitting in the middle.
 * Faces first, movement where there are none - analysed on the phone.
 */
@Composable
private fun AutoReframeRow(state: EditorUiState, viewModel: EditorViewModel) {
    val progress = state.reframeProgress
    val following = state.reframe != null && state.cropAspect.ratio != null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                progress.running && progress.total > 0 ->
                    "Finding the subject — ${progress.done * 100 / progress.total}%"
                progress.running -> "Finding the subject…"
                progress.failed -> "Could not read this clip to reframe it."
                following -> "The crop follows the subject. Play to see it move."
                else -> "Auto-reframe keeps faces and movement in the crop, instead of the middle."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (progress.failed) SquishColors.Pink else SquishColors.TextMuted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            when {
                progress.running -> SquishOutlinedButton(
                    text = "Stop",
                    modifier = Modifier.weight(1f),
                    onClick = viewModel.analysis::cancelReframe
                )
                following -> {
                    SquishOutlinedButton(text = "Again", modifier = Modifier.weight(1f), onClick = viewModel.analysis::autoReframe)
                    SquishOutlinedButton(text = "Centre it", modifier = Modifier.weight(1f), onClick = viewModel.analysis::clearReframe)
                }
                else -> SquishOutlinedButton(
                    text = "Auto-reframe",
                    modifier = Modifier.weight(1f),
                    onClick = viewModel.analysis::autoReframe
                )
            }
        }
    }
}

@Composable
fun CropPanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Violet) {
        PanelHeading(
            "Shape",
            "Crop to the aspect ratio you are posting to",
            icon = Icons.Filled.AspectRatio,
            accent = SquishColors.Violet
        )
        // Five now that Custom is one of them, which is one more than fits across
        // a phone at a readable size, so the row scrolls rather than squeezing
        // "Original" into an ellipsis.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            CropAspect.entries.forEach { aspect ->
                SelectableChip(
                    label = aspect.label,
                    selected = state.cropAspect == aspect,
                    accentColor = SquishColors.Purple,
                    onClick = { viewModel.clips.setCropAspect(aspect) }
                )
            }
        }

        if (state.cropAspect == CropAspect.Custom) {
            Text(
                "Drag any edge or corner on the picture, or the middle to move it. The dimmed part is what goes.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
        } else {
            AutoReframeRow(state, viewModel)
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
            SquishOutlinedButton(text = "Rotate 90°", onClick = { viewModel.clips.toggleRotate() })
        }
    }
}

// ---- Color -----------------------------------------------------------------

// ---- Export -----------------------------------------------------------------

@Composable
fun ExportPanel(state: EditorUiState, viewModel: EditorViewModel, onAddClip: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "Size",
                "Bigger means sharper and heavier",
                icon = Icons.Filled.HighQuality,
                accent = SquishColors.Blue
            )
            OutputSizePicker(
                outputP = state.outputP,
                fitToSize = state.fitToSize,
                // Rotated and cropped, as the file is: see ExportSheet.
                sourceWidth = state.croppedFrame.width,
                sourceHeight = state.croppedFrame.height,
                estimatedBytes = state.estimatedOutputBytes,
                originalBytes = state.originalSizeBytes,
                accent = SquishColors.Blue,
                onPick = viewModel::setOutputP
            )
        }

        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "Fit to a size",
                "We pick the bitrate for you",
                icon = Icons.Filled.Compress,
                accent = SquishColors.Blue,
                trailing = {
                    SquishToggleSwitch(
                        checked = state.fitToSize,
                        onCheckedChange = viewModel::setFitToSize
                    )
                }
            )
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

        PanelSurface(accent = SquishColors.Blue) {
            PanelHeading(
                "Video track",
                "Played one after another, in order",
                icon = Icons.Filled.Movie,
                accent = SquishColors.Blue
            )
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

// ---- Shared -----------------------------------------------------------------

/**
 * How a slider's value reads beside its label.
 *
 * Every slider used to print its value times a hundred with a percent sign, so
 * 45 degrees of rotation read "+4500%" and a half-turn "-18000%". The unit
 * belongs to the value, so the slider is told it.
 */
object Readout {
    /** A fraction as a percentage, signed when the range goes negative. */
    fun percent(range: ClosedFloatingPointRange<Float>): (Float) -> String = { v ->
        if (range.start < 0f) "%+.0f%%".format(v * 100) else "%.0f%%".format(v * 100)
    }

    /** An angle, already in degrees. */
    val degrees: (Float) -> String = { v -> if (v == 0f) "0°" else "%+.0f°".format(v) }

    /** A size relative to where it started: 1.5 reads "1.5×". */
    val times: (Float) -> String = { v -> "%.2f".format(v).trimEnd('0').trimEnd('.') + "×" }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) = LabeledSlider(label, value, range, readout = Readout.percent(range), onFinished = null, onChange = onChange)

/**
 * A slider with its label and value above it.
 *
 * [onFinished] is the finger lifting. Pass the view model's `endGesture` so a
 * second drag of the same slider straight after the first is its own undo step
 * rather than part of the first.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: (Float) -> String = Readout.percent(range),
    onFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit
) {
    Column {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = SquishColors.TextSecondary)
            Text(readout(value), style = MaterialTheme.typography.bodySmall, color = SquishColors.TextPrimary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
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
            .background(if (active) SquishColors.Primary.copy(alpha = 0.15f) else SquishColors.Surface)
            .clickable { onClick(!active) }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) SquishColors.Primary else SquishColors.TextSecondary,
            style = MaterialTheme.typography.labelLarge
        )
    }
}
