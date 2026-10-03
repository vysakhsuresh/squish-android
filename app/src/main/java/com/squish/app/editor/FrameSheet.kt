package com.squish.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Icon
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
            // Beats too: the dots are marks as far as a drag is concerned, and
            // this is the way off them without clearing the grid.
            Text(
                "Snap to markers and beats",
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            SquishToggleSwitch(checked = state.snapToMarkers, onCheckedChange = viewModel.clips::setSnapToMarkers)
        }
    }
}

// ---- Frame --------------------------------------------------------------------

/**
 * Auto-reframe: a crop that follows the subject instead of sitting in the middle.
 * Faces first, movement where there are none - analysed on the phone, shot by
 * shot, each shot keeping its own track.
 */
@Composable
private fun AutoReframeRow(state: EditorUiState, viewModel: EditorViewModel) {
    val progress = state.reframeProgress
    val shots = state.videoClips.count { it.isMain }
    val following = state.cropAspect.ratio != null && state.videoClips.any { it.isMain && it.reframe != null }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                progress.running && progress.total > 0 ->
                    "Finding the subject — ${progress.done * 100 / progress.total}%"
                progress.running -> "Finding the subject…"
                progress.failed -> "Could not read the footage to reframe it."
                following && shots > 1 -> "The crop follows the subject in each shot. Play to see it move."
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

/** The Ratio chip of Frame: the shape the edit is cropped to, or - with a background - the canvas it is put on. */
@Composable
fun RatioPanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Violet) {
        PanelHeading(
            "Ratio",
            if (state.paddedCanvas) "The canvas the picture is put on" else "Crop to the shape you are posting to",
            icon = Icons.Filled.AspectRatio,
            accent = SquishColors.Violet
        )
        // Ten now, the ones every upload page asks for: far more than fit across
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
        // Where the chosen shape is posted: the name people look for, not the ratio.
        aspect_use(state.cropAspect)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = SquishColors.TextMuted)
        }

        // What the platform puts on top of your picture. Beside the ratio
        // because they are the same question - what shape is this for, and what
        // of it will actually be seen.
        Text(
            "Show where the app's buttons will be",
            style = MaterialTheme.typography.bodyMedium,
            color = SquishColors.TextPrimary
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            SelectableChip(
                label = "Off",
                selected = state.safeArea == null,
                accentColor = SquishColors.Teal,
                onClick = { viewModel.setSafeArea(null) }
            )
            SafeArea.entries.forEach { area ->
                SelectableChip(
                    label = area.label,
                    selected = state.safeArea == area,
                    accentColor = SquishColors.Teal,
                    onClick = { viewModel.setSafeArea(area) }
                )
            }
        }
        if (state.safeArea != null) {
            Text(
                "A guide only - it is never in the file. The apps move their buttons about, so treat it as near enough rather than exact.",
                style = MaterialTheme.typography.labelSmall,
                color = SquishColors.TextMuted
            )
        }

        when {
            state.cropAspect == CropAspect.Custom -> Text(
                "Drag any edge or corner on the picture, or the middle to move it. The dimmed part is what goes.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            state.paddedCanvas -> Text(
                "The whole picture is kept, with the background round it. Turn the background off to crop to the shape instead.",
                style = MaterialTheme.typography.bodySmall,
                color = SquishColors.TextMuted
            )
            else -> AutoReframeRow(state, viewModel)
        }
    }
}

private val CANVAS_COLOURS = listOf(
    0xFF000000.toInt(), 0xFF101828.toInt(), 0xFFFFFFFF.toInt(), 0xFF2563EB.toInt(),
    0xFF00B140.toInt(), 0xFFF472B6.toInt(), 0xFFFBBF24.toInt(), 0xFF7C3AED.toInt(), 0xFFEF4444.toInt()
)

/**
 * The Background chip of Frame: what fills a ratio's canvas round the
 * picture. Fill cuts the picture to the ratio, as Ratio always did; Colour,
 * Blur and Image fit the picture whole into the canvas and put that behind
 * it - the way a reel is made from a landscape shot.
 */
@Composable
fun CanvasPanel(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickImage: () -> Unit,
    onEyedropper: ((Int) -> Unit) -> Unit
) {
    val background = state.canvasBackground
    PanelSurface(accent = SquishColors.Violet) {
        PanelHeading(
            "Background",
            "What shows round the picture on the canvas",
            icon = Icons.Filled.Wallpaper,
            accent = SquishColors.Violet
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            CanvasFill.entries.forEach { fill ->
                SelectableChip(
                    label = fill.label,
                    selected = background.fill == fill,
                    accentColor = SquishColors.Violet,
                    onClick = {
                        when (fill) {
                            CanvasFill.Crop -> viewModel.clips.resetCanvasBackground()
                            CanvasFill.Image ->
                                if (background.imageUri != null) viewModel.clips.setCanvasBackground(background.copy(fill = fill))
                                else onPickImage()
                            else -> viewModel.clips.setCanvasBackground(background.copy(fill = fill))
                        }
                    }
                )
            }
        }
        // A background only shows round a picture fitted into a canvas of a
        // chosen shape. With Original the picture already fills the frame and
        // with Custom the drawn rectangle is the frame, so the chip says what
        // it is waiting for rather than describing a canvas that is not there.
        val waitingForRatio = background.fill != CanvasFill.Crop && !state.paddedCanvas
        Text(
            when {
                waitingForRatio && state.cropAspect == CropAspect.Custom ->
                    "The hand-drawn crop is the frame itself. Pick a shape on Ratio and the picture is put whole on a canvas of it, with this round it."
                waitingForRatio ->
                    "Pick a shape on Ratio to see it: with Original the picture already fills the frame."
                background.fill == CanvasFill.Crop -> "The picture fills the frame and is cut to its shape."
                background.fill == CanvasFill.Colour -> "The picture is kept whole, on a colour."
                background.fill == CanvasFill.Blur -> "The picture is kept whole, over a blurred copy of the shot."
                else -> "The picture is kept whole, over a picture of your own."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SquishColors.TextMuted
        )
        if (background.fill == CanvasFill.Colour) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                CANVAS_COLOURS.forEach { argb ->
                    val selected = background.colorArgb == argb
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) SquishColors.Primary else SquishColors.Border,
                                CircleShape
                            )
                            .clickable { viewModel.clips.setCanvasColour(argb) }
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(SquishColors.SurfaceElevated)
                        .border(1.dp, SquishColors.Border, CircleShape)
                        .clickable { onEyedropper { viewModel.clips.setCanvasColour(it) } },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Colorize, contentDescription = "Pick a colour from the picture", tint = SquishColors.TextPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (background.fill == CanvasFill.Image) {
            SquishOutlinedButton(text = "Choose another picture", modifier = Modifier.fillMaxWidth(), onClick = onPickImage)
        }
    }
}

/** The Rotate all chip of Frame: the whole edit, a quarter turn at a time. A clip alone is its own Rotate. */
@Composable
fun RotatePanel(state: EditorUiState, viewModel: EditorViewModel) {
    PanelSurface(accent = SquishColors.Violet) {
        PanelHeading(
            "Rotate all",
            "Turns the whole edit; Rotate on a clip's toolbar turns that clip alone",
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

    /** The Hue slider: a fraction of half the wheel, read in degrees. */
    val hueDegrees: (Float) -> String = { v -> degrees(v * com.squish.app.media.effects.Adjust.HUE_TURN_DEGREES) }

    /** An HSL band's hue: a fraction of a band, read in degrees. */
    val bandDegrees: (Float) -> String = { v -> degrees(v * com.squish.app.media.effects.HslBand.HUE_SWING_DEGREES) }

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

/** Where a shape is posted, in the words an upload page uses. */
private fun aspect_use(aspect: CropAspect): String? = when (aspect.label) {
    "9:16" -> "TikTok, Reels, Shorts, Stories"
    "1:1" -> "Instagram and Facebook feed posts"
    "16:9" -> "YouTube, TV, a laptop screen"
    "4:5" -> "Instagram portrait post - the most room in the feed"
    "3:4", "4:3" -> "Classic photo shapes"
    "2:1", "2.35:1" -> "Cinematic widescreen"
    else -> null
}
