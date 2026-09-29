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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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

// ---- Markers ------------------------------------------------------------------

/**
 * Marks dropped by hand, and whether edits snap to them. In the Sync chip of
 * Sound, beside the beat grid that fills them in; the In and out points panel
 * they lived in has gone, since a clip is trimmed by its handles on the strip.
 */
@Composable
fun MarkersPanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Cyan) {
        PanelHeading(
            "Markers",
            "Drop a mark at the playhead and snap cuts to it",
            icon = Icons.Filled.Flag,
            accent = SquishColors.Cyan
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

// ---- Frame --------------------------------------------------------------------

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

/** The Ratio chip of Frame: the shape the edit is cropped to. */
@Composable
fun RatioPanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Violet) {
        PanelHeading(
            "Ratio",
            "Crop to the shape you are posting to",
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
                    accentColor = SquishColors.Violet,
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
    }
}

/** The Rotate chip of Frame: the whole edit, a quarter turn at a time. */
@Composable
fun RotatePanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Violet) {
        PanelHeading(
            "Rotate",
            "Turns the whole edit",
            icon = Icons.AutoMirrored.Filled.RotateRight,
            accent = SquishColors.Violet
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${state.rotationDegrees}°", style = MaterialTheme.typography.titleMedium, color = SquishColors.TextPrimary)
            SquishOutlinedButton(text = "Rotate 90°", onClick = { viewModel.clips.toggleRotate() })
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
