package com.squish.app.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.squish.app.media.effects.Look
import com.squish.app.media.effects.LookFamily
import com.squish.app.media.effects.Looks
import com.squish.app.ui.components.SelectableChip
import com.squish.app.ui.theme.SquishColors

/**
 * The filter library.
 *
 * Every chip paints what the look actually does, by running the grade over a
 * reference ramp with the same maths the shaders use. That is the whole reason to
 * build the swatch from the grade rather than hand-picking a color per filter: a
 * hand-picked chip is a drawing of a promise, and it starts lying the moment a
 * look is retuned.
 */
@Composable
fun EffectsPanel(state: EditorUiState, viewModel: EditorViewModel) {
    var family by remember { mutableStateOf(LookFamily.Essentials) }
    val active = Looks.byId(state.lookId)

    // Every look previewed on the frame you are stopped on, which is how the
    // choice is actually made - a swatch tells you a look is warm, the shot tells
    // you whether warm is right for this face, in this light.
    val frame = rememberLookFrame(state.sourceUri, state.playheadMs)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PanelSurface(accent = SquishColors.Primary) {
            PanelHeading(
                "Templates",
                "A whole style in one tap · undo to take it off",
                icon = Icons.Filled.AutoAwesome,
                accent = SquishColors.Primary
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                Template.entries.forEach { template ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .width(132.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SquishColors.Background)
                            .border(1.dp, SquishColors.Border, RoundedCornerShape(14.dp))
                            .clickable { viewModel.applyTemplate(template) }
                            .padding(12.dp)
                    ) {
                        Text(template.glyph, fontSize = 24.sp)
                        Text(template.label, style = MaterialTheme.typography.titleSmall, color = SquishColors.TextPrimary)
                        Text(
                            template.blurb,
                            style = MaterialTheme.typography.labelSmall,
                            color = SquishColors.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        BackgroundPanel(state, viewModel)

        PanelSurface(accent = SquishColors.Magenta) {
            PanelHeading(
                "Looks",
                "Tap to apply · tap again to clear",
                icon = Icons.Filled.AutoAwesome,
                accent = SquishColors.Magenta
            )

            // Five families no longer divide evenly into a phone's width, so the
            // row scrolls rather than squeezing "Essentials" down to an ellipsis.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                LookFamily.entries.forEach { entry ->
                    SelectableChip(
                        label = entry.label,
                        selected = family == entry,
                        onClick = { family = entry }
                    )
                }
            }

            val shown = Looks.catalog.filter { it.family == family || it.id == Looks.None.id }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                shown.forEach { look ->
                    LookChip(
                        frame = frame,
                        look = look,
                        // The chip previews at the strength you have dialled in, so
                        // the row re-reads correctly instead of advertising full
                        // strength for a look you have pulled back to a third.
                        intensity = if (look.id == state.lookId) state.lookIntensity else 1f,
                        selected = look.id == state.lookId || (state.lookId == null && look.id == Looks.None.id),
                        onClick = { viewModel.setLook(look.id.takeIf { it != Looks.None.id }) }
                    )
                }
            }
        }

        if (state.lookId != null) {
            PanelSurface(accent = SquishColors.Magenta) {
                PanelHeading(
                    active.label,
                    "How far the look is dialled in",
                    icon = Icons.Filled.FilterVintage,
                    accent = SquishColors.Magenta,
                    trailing = {
                        Text(
                            "${(state.lookIntensity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = SquishColors.Cyan
                        )
                    }
                )
                LabeledSlider("Strength", state.lookIntensity, 0f..1f, viewModel::setLookIntensity)
            }
        }

        PanelSurface(accent = SquishColors.Magenta) {
            PanelHeading(
                "Adjust",
                "Refines whatever look is on, rather than replacing it",
                icon = Icons.Filled.Tune,
                accent = SquishColors.Magenta
            )
            LabeledSlider("Brightness", state.brightness, -1f..1f, viewModel::setBrightness)
            LabeledSlider("Contrast", state.contrast, -1f..1f, viewModel::setContrast)
            LabeledSlider("Saturation", state.saturation, -1f..1f, viewModel::setSaturation)
        }
    }
}

@Composable
private fun LookChip(
    frame: LookFrame?,
    look: Look,
    intensity: Float,
    selected: Boolean,
    onClick: () -> Unit
) {
    // The swatch is the fallback, not the design. It is what shows for the second
    // before the frame arrives, and for an audio-only or unreadable source.
    val stops = remember(look.id, intensity) {
        Looks.swatch(look, intensity).map { Color(it) }
    }
    val preview = remember(frame, look.id, intensity) {
        frame?.graded(look.atIntensity(intensity))
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.width(IntrinsicChipWidth).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(IntrinsicChipWidth, 52.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) SquishColors.Cyan else SquishColors.Border,
                    shape = RoundedCornerShape(10.dp)
                )
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                    drawRect(brush = Brush.verticalGradient(stops))
                }
            }
        }
        Text(
            look.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) SquishColors.TextPrimary else SquishColors.TextSecondary,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

private val IntrinsicChipWidth = 64.dp
